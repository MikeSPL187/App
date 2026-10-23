package io.quietfiles.feature.network

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.quietfiles.core.common.IoDispatcher
import io.quietfiles.core.model.network.AuthMethod
import io.quietfiles.core.model.network.Connection
import io.quietfiles.core.model.network.ConnectionSecrets
import io.quietfiles.core.model.network.FtpSecurity
import io.quietfiles.core.model.network.NetworkPaths
import io.quietfiles.core.model.network.Protocol
import io.quietfiles.core.model.network.RemoteProblem
import io.quietfiles.core.model.network.SshKeys
import io.quietfiles.core.network.ConnectionsRepository
import io.quietfiles.core.network.RemoteSessions
import io.quietfiles.core.network.discovery.DiscoveredServer
import io.quietfiles.core.network.discovery.NetworkDiscovery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Поля формы подключения (макет 09). Секреты живут только здесь и в зашифрованном хранилище. */
data class ConnectionForm(
    val protocol: Protocol = Protocol.SMB,
    val host: String = "",
    val port: String = Protocol.SMB.defaultPort.toString(),
    val username: String = "",
    val auth: AuthMethod = AuthMethod.PASSWORD,
    val password: String = "",
    val privateKey: String? = null,
    val keyLabel: String = "",
    val keyEncrypted: Boolean = false,
    val passphrase: String = "",
    val name: String = "",
    val path: String = "",
    val ftpSecurity: FtpSecurity = FtpSecurity.EXPLICIT,
    val plainHttp: Boolean = false,
    val hostKey: String? = null,
    val pinnedCertificate: String? = null,
) {
    val portNumber: Int? get() = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val hostValid: Boolean get() = host.isNotBlank() && host.trim().none { it.isWhitespace() || it == '/' }
    val needsUser: Boolean get() = auth != AuthMethod.ANONYMOUS
    val pathValid: Boolean get() = runCatching { NetworkPaths.normalize(path) }.isSuccess
    val isValid: Boolean get() = hostValid && portNumber != null && pathValid && (!needsUser || username.isNotBlank()) &&
        (auth != AuthMethod.KEY || privateKey != null || keepsKey)

    /** При правке пустое поле пароля значит «не менять». */
    var keepsPassword: Boolean = false
    var keepsKey: Boolean = false

    fun defaultPort(protocol: Protocol = this.protocol, security: FtpSecurity = ftpSecurity, plain: Boolean = plainHttp): Int = when {
        protocol == Protocol.FTP && security == FtpSecurity.IMPLICIT -> 990
        protocol == Protocol.WEBDAV && plain -> 80
        else -> protocol.defaultPort
    }
}

sealed interface ConnectionEvent {
    data class Connected(val path: String) : ConnectionEvent
    data object Saved : ConnectionEvent
}

@HiltViewModel
class ConnectionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val repository: ConnectionsRepository,
    private val sessions: RemoteSessions,
    private val discovery: NetworkDiscovery,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val editingId = savedStateHandle.toRoute<ConnectionRoute>().id
    val isEditing: Boolean get() = editingId != 0L
    private var original: Connection? = null

    private val _form = MutableStateFlow(ConnectionForm())
    val form: StateFlow<ConnectionForm> = _form

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking

    private val _problem = MutableStateFlow<RemoteProblem?>(null)
    val problem: StateFlow<RemoteProblem?> = _problem

    private val _error = MutableStateFlow<String?>(null)

    /** Секрет не удалось зашифровать (S1) или ключ не читается. */
    val error: StateFlow<String?> = _error

    private val _discovered = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val discovered: StateFlow<List<DiscoveredServer>> = _discovered
    private val _discovering = MutableStateFlow(false)
    val discovering: StateFlow<Boolean> = _discovering
    private var discoveryJob: Job? = null

    private val events = Channel<ConnectionEvent>(Channel.BUFFERED)
    val eventFlow: Flow<ConnectionEvent> = events.receiveAsFlow()

    init {
        if (isEditing) {
            viewModelScope.launch {
                val connection = repository.get(editingId) ?: return@launch
                original = connection
                val hasSecrets = withContext(io) { repository.secretsOf(editingId) } != null
                _form.value = ConnectionForm(
                    protocol = connection.protocol,
                    host = connection.host,
                    port = connection.port.toString(),
                    username = connection.username,
                    auth = connection.auth,
                    keyLabel = connection.keyLabel,
                    name = connection.name,
                    path = connection.path,
                    ftpSecurity = connection.ftpSecurity,
                    plainHttp = connection.plainHttp,
                    hostKey = connection.hostKey,
                    pinnedCertificate = connection.pinnedCertificate,
                ).also {
                    it.keepsPassword = hasSecrets && connection.auth == AuthMethod.PASSWORD
                    it.keepsKey = hasSecrets && connection.auth == AuthMethod.KEY
                }
            }
        } else {
            discover()
        }
    }

    fun update(change: (ConnectionForm) -> ConnectionForm) {
        val before = _form.value
        var after = change(before)
        // Порт по умолчанию следует за протоколом и шифрованием, если пользователь его не менял.
        if (before.port == before.defaultPort().toString() &&
            (after.protocol != before.protocol || after.ftpSecurity != before.ftpSecurity || after.plainHttp != before.plainHttp)
        ) {
            after = after.copy(port = after.defaultPort().toString())
        }
        if (after.protocol != Protocol.SFTP && after.auth == AuthMethod.KEY) after = after.copy(auth = AuthMethod.PASSWORD)
        if (after.protocol == Protocol.SFTP && after.auth == AuthMethod.ANONYMOUS) after = after.copy(auth = AuthMethod.PASSWORD)
        // Адрес сменился — старому доверию место нет.
        if (after.host.trim() != before.host.trim() || after.port != before.port || after.protocol != before.protocol) {
            after = after.copy(hostKey = null, pinnedCertificate = null)
        }
        after.keepsPassword = before.keepsPassword && after.auth == AuthMethod.PASSWORD
        after.keepsKey = before.keepsKey && after.auth == AuthMethod.KEY && after.privateKey == null
        _form.value = after
        _problem.value = null
        _error.value = null
    }

    fun useDiscovered(server: DiscoveredServer) = update {
        it.copy(protocol = server.protocol, host = server.host, port = server.port.toString(), name = it.name.ifBlank { server.name })
    }

    fun discover() {
        discoveryJob?.cancel()
        discoveryJob = viewModelScope.launch {
            _discovering.value = true
            try {
                discovery.discover().collect { _discovered.value = it }
            } finally {
                _discovering.value = false
            }
        }
    }

    /** Ключ SSH из файла (SAF). Читается целиком в память и сразу уходит в зашифрованное хранилище при сохранении. */
    fun importKey(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(io) {
                runCatching {
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: "key"
                    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (out.size() <= SshKeys.MAX_KEY_BYTES) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                        }
                        out.toByteArray().takeIf { it.size <= SshKeys.MAX_KEY_BYTES }
                    }
                    val text = bytes?.toString(Charsets.UTF_8)
                    val info = text?.let(SshKeys::describe)
                    if (text == null || info == null) null else Triple(name, text, info)
                }.getOrNull()
            }
            if (result == null) {
                _error.value = context.getString(R.string.connection_key_unreadable)
                return@launch
            }
            val (name, text, info) = result
            update { it.copy(privateKey = text, keyLabel = "$name · ${info.type}", keyEncrypted = info.encrypted) }
        }
    }

    private fun build(form: ConnectionForm): Pair<Connection, ConnectionSecrets?> {
        val connection = Connection(
            id = editingId,
            protocol = form.protocol,
            host = form.host.trim().removePrefix("[").removeSuffix("]"),
            port = form.portNumber ?: form.defaultPort(),
            username = if (form.needsUser) form.username.trim() else "",
            auth = form.auth,
            name = form.name.trim(),
            path = form.path.trim().takeIf { it.isNotEmpty() && it != "/" }?.let(NetworkPaths::normalize).orEmpty(),
            ftpSecurity = form.ftpSecurity,
            plainHttp = form.plainHttp,
            keyLabel = if (form.auth == AuthMethod.KEY) form.keyLabel else "",
            hostKey = form.hostKey,
            pinnedCertificate = form.pinnedCertificate,
            createdAt = original?.createdAt ?: 0,
            lastConnectedAt = original?.lastConnectedAt,
        )
        val secrets = when {
            form.auth == AuthMethod.ANONYMOUS -> ConnectionSecrets()
            form.auth == AuthMethod.PASSWORD && form.password.isEmpty() && form.keepsPassword -> null
            form.auth == AuthMethod.KEY && form.privateKey == null && form.keepsKey -> null
            form.auth == AuthMethod.PASSWORD -> ConnectionSecrets(password = form.password)
            else -> ConnectionSecrets(privateKey = form.privateKey, passphrase = form.passphrase.ifEmpty { null })
        }
        return connection to secrets
    }

    /** Секреты для проверки: введённые сейчас или уже сохранённые (при правке). */
    private fun secretsForTest(secrets: ConnectionSecrets?): ConnectionSecrets? = secrets ?: repository.secretsOf(editingId)

    /** «Проверить и подключить» (макет 09): подключение, доверие ключу или сертификату, сохранение, переход к файлам. */
    fun checkAndConnect() {
        val form = _form.value
        if (!form.isValid || _checking.value) return
        viewModelScope.launch {
            _checking.value = true
            _problem.value = null
            try {
                val (connection, secrets) = build(form)
                val problem = withContext(io) { sessions.test(connection, secretsForTest(secrets)) }
                if (problem != null) {
                    _problem.value = problem
                    return@launch
                }
                val id = save(connection, secrets) ?: return@launch
                val start = withContext(io) { sessions.startPath(id) }
                events.send(ConnectionEvent.Connected(NetworkPaths.pathOf(id, start)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _problem.value = (e as? io.quietfiles.core.storage.StorageException.Remote)?.problem
                    ?: RemoteProblem.Protocol(e.message ?: e.javaClass.simpleName)
            } finally {
                _checking.value = false
            }
        }
    }

    /** «Сохранить» без проверки: например, сервер сейчас выключен. */
    fun saveOnly() {
        val form = _form.value
        if (!form.isValid) return
        viewModelScope.launch {
            val (connection, secrets) = build(form)
            if (save(connection, secrets) != null) events.send(ConnectionEvent.Saved)
        }
    }

    private suspend fun save(connection: Connection, secrets: ConnectionSecrets?): Long? = try {
        withContext(io) {
            repository.save(connection, secrets).also { sessions.disconnect(it) }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        _error.value = context.getString(R.string.connection_secret_error)
        null
    }

    /** Доверие отпечатку или сертификату из проверки — и сразу повторная попытка. */
    fun trust(problem: RemoteProblem) {
        _form.update {
            when (problem) {
                is RemoteProblem.UnknownHostKey -> it.copy(hostKey = problem.fingerprint)
                is RemoteProblem.HostKeyChanged -> it.copy(hostKey = problem.actual)
                is RemoteProblem.UntrustedCertificate -> it.copy(pinnedCertificate = problem.certificate.spkiSha256)
                is RemoteProblem.CertificateChanged -> it.copy(pinnedCertificate = problem.certificate.spkiSha256)
                else -> it
            }.also { copy ->
                copy.keepsPassword = it.keepsPassword
                copy.keepsKey = it.keepsKey
            }
        }
        _problem.value = null
        checkAndConnect()
    }

    fun dismissProblem() {
        _problem.value = null
    }
}
