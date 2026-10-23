package io.quietfiles.core.network.server

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.quietfiles.core.network.tls.SelfSigned
import io.quietfiles.core.security.Fingerprints
import io.quietfiles.core.security.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/** Настройки «Доступа с ПК» (F17); по умолчанию — FTPS с паролем, без анонимного входа (S6). */
data class PcAccessSettings(val port: Int = DEFAULT_PORT, val tls: Boolean = true, val anonymousReadOnly: Boolean = false) {
    companion object {
        const val DEFAULT_PORT = 2221
    }
}

enum class PcAccessStop { USER, IDLE, NETWORK_LOST }

sealed interface PcAccessState {
    data class Stopped(val reason: PcAccessStop? = null) : PcAccessState

    /** Сервер работает: адрес для ПК, логин и пароль, отпечаток сертификата для сверки. */
    data class Running(
        val address: String,
        val user: String,
        val password: String,
        val tls: Boolean,
        val anonymousReadOnly: Boolean,
        val certificateSha256: String?,
        val clients: List<FtpClientInfo>,
        val startedAt: Long,
    ) : PcAccessState

    /** Не запустился: нет Wi-Fi или точки доступа (F17.2), порт занят, нет доступа к файлам. */
    data class Failed(val problem: PcAccessProblem) : PcAccessState
}

enum class PcAccessProblem { NO_WIFI, PORT_BUSY, NO_STORAGE, TLS }

/**
 * «Доступ с ПК» (F17): FTP-сервер на телефоне для компьютера в той же сети Wi-Fi.
 * Слушает только адрес Wi-Fi или точки доступа, в мобильной сети не запускается (F17.2), останавливается
 * через [IDLE_MS] без обращений (F17.3). Пароль случайный, сертификат самоподписанный; оба хранятся
 * в зашифрованном хранилище и не попадают в резервные копии (S1, S2).
 */
@Singleton
class PcAccessServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secrets: SecretStore,
) {
    private val _state = MutableStateFlow<PcAccessState>(PcAccessState.Stopped())
    val state: StateFlow<PcAccessState> = _state.asStateFlow()

    private val prefs = context.getSharedPreferences("pc_access", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(
        PcAccessSettings(
            port = prefs.getInt("port", PcAccessSettings.DEFAULT_PORT),
            tls = prefs.getBoolean("tls", true),
            anonymousReadOnly = prefs.getBoolean("anonymous", false),
        ),
    )
    val settings: StateFlow<PcAccessSettings> = _settings.asStateFlow()

    /** Адрес для тестов: в Robolectric нет Wi-Fi. */
    @VisibleForTesting
    var addressOverride: InetAddress? = null

    private var server: FtpServer? = null
    private var watchdog: ScheduledExecutorService? = null
    private var bound: InetAddress? = null

    fun updateSettings(settings: PcAccessSettings) {
        _settings.value = settings
        prefs.edit().putInt("port", settings.port).putBoolean("tls", settings.tls).putBoolean("anonymous", settings.anonymousReadOnly).apply()
    }

    @Synchronized
    fun start() {
        if (server != null) return
        val root = Environment.getExternalStorageDirectory()
        if (!root.canRead()) return fail(PcAccessProblem.NO_STORAGE)
        val address = lanAddress() ?: return fail(PcAccessProblem.NO_WIFI)
        val settings = _settings.value
        val password = password()
        val tls = if (settings.tls) runCatching { sslContext() }.getOrElse { return fail(PcAccessProblem.TLS) } else null
        val ftp = FtpServer(
            FtpServerConfig(
                bind = address,
                port = settings.port,
                root = root,
                user = USER,
                password = password.toCharArray(),
                anonymousReadOnly = settings.anonymousReadOnly,
                tls = tls?.first,
            ),
            onChange = ::publish,
        )
        try {
            ftp.start()
        } catch (e: java.io.IOException) {
            ftp.close()
            return fail(PcAccessProblem.PORT_BUSY)
        }
        server = ftp
        bound = address
        val scheme = if (settings.tls) "ftps" else "ftp"
        _state.value = PcAccessState.Running(
            address = "$scheme://${address.hostAddress}:${ftp.port}",
            user = USER,
            password = password,
            tls = settings.tls,
            anonymousReadOnly = settings.anonymousReadOnly,
            certificateSha256 = tls?.second,
            clients = emptyList(),
            startedAt = System.currentTimeMillis(),
        )
        watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "qf-pc-access").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay(::check, CHECK_MS, CHECK_MS, TimeUnit.MILLISECONDS)
        }
        ContextCompat.startForegroundService(context, Intent(context, PcAccessService::class.java))
    }

    @Synchronized
    fun stop(reason: PcAccessStop = PcAccessStop.USER) {
        server?.close()
        server = null
        bound = null
        watchdog?.shutdownNow()
        watchdog = null
        _state.value = PcAccessState.Stopped(reason)
        context.stopService(Intent(context, PcAccessService::class.java))
    }

    fun disconnect(clientId: Int) = server?.disconnect(clientId)

    /** Новый пароль: прежний сразу перестаёт подходить. */
    fun regeneratePassword() {
        secrets.remove(PASSWORD_KEY)
        val running = server != null
        if (running) {
            stop()
            start()
        }
    }

    private fun publish() {
        val current = _state.value as? PcAccessState.Running ?: return
        _state.value = current.copy(clients = server?.clients.orEmpty())
    }

    /** Автоостановка без обращений и при уходе из Wi-Fi (F17.2, F17.3). */
    private fun check() {
        val ftp = server ?: return
        val address = bound
        when {
            address == null || (address != addressOverride && lanAddresses().none { it == address }) -> stop(PcAccessStop.NETWORK_LOST)
            ftp.clients.isEmpty() && System.currentTimeMillis() - ftp.lastActivity.get() > IDLE_MS -> stop(PcAccessStop.IDLE)
        }
    }

    private fun fail(problem: PcAccessProblem) {
        _state.value = PcAccessState.Failed(problem)
    }

    private fun password(): String {
        secrets.get(PASSWORD_KEY)?.let { return String(it, Charsets.UTF_8) }
        val random = SecureRandom()
        val value = String(CharArray(PASSWORD_LENGTH) { PASSWORD_ALPHABET[random.nextInt(PASSWORD_ALPHABET.length)] })
        runCatching { secrets.put(PASSWORD_KEY, value.toByteArray(Charsets.UTF_8)) }
        return value
    }

    /** TLS-контекст сервера и отпечаток сертификата; ключ создаётся один раз и хранится зашифрованным. */
    private fun sslContext(): Pair<SSLContext, String> {
        val storePassword = (secrets.get(KEYSTORE_PASSWORD_KEY)?.let { String(it, Charsets.UTF_8) } ?: run {
            val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
            java.util.Base64.getEncoder().encodeToString(bytes).also { secrets.put(KEYSTORE_PASSWORD_KEY, it.toByteArray()) }
        }).toCharArray()
        val store = secrets.get(KEYSTORE_KEY)?.let { bytes ->
            runCatching { KeyStore.getInstance("PKCS12").apply { load(ByteArrayInputStream(bytes), storePassword) } }.getOrNull()
        } ?: SelfSigned.create("Quiet Files", password = storePassword).also { created ->
            val out = ByteArrayOutputStream()
            created.store(out, storePassword)
            secrets.put(KEYSTORE_KEY, out.toByteArray())
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, storePassword) }
        val context = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val certificate = SelfSigned.certificateOf(store)
        val fingerprint = Fingerprints.hexColon(MessageDigest.getInstance("SHA-256").digest(certificate.encoded))
        return context to fingerprint
    }

    /** Адрес Wi-Fi (или точки доступа, если телефон раздаёт интернет); мобильная сеть не подходит (F17.2). */
    private fun lanAddress(): InetAddress? {
        addressOverride?.let { return it }
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val active = connectivity?.activeNetwork
        val caps = active?.let(connectivity::getNetworkCapabilities)
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            connectivity.getLinkProperties(active)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.let { return it }
        }
        return lanAddresses().firstOrNull()
    }

    /** Адреса локальных интерфейсов Wi-Fi и точки доступа (wlan*, swlan*, ap*), без сотовых. */
    private fun lanAddresses(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && LAN_INTERFACES.any { prefix -> it.name.startsWith(prefix) } }
            .flatMap { it.inetAddresses.toList() }
            .filter { it is Inet4Address && it.isSiteLocalAddress }
    }.getOrDefault(emptyList())

    companion object {
        const val USER = "phone"
        const val IDLE_MS = 30 * 60_000L
        private const val CHECK_MS = 30_000L
        private const val PASSWORD_LENGTH = 10
        private const val PASSWORD_ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private const val PASSWORD_KEY = "pc-access-password"
        private const val KEYSTORE_KEY = "pc-access-keystore"
        private const val KEYSTORE_PASSWORD_KEY = "pc-access-keystore-password"
        private val LAN_INTERFACES = listOf("wlan", "swlan", "ap", "softap")
    }
}
