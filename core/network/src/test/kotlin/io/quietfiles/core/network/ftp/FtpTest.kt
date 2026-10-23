package io.quietfiles.core.network.ftp

import com.google.common.truth.Truth.assertThat
import io.quietfiles.core.model.network.AuthMethod
import io.quietfiles.core.model.network.Connection
import io.quietfiles.core.model.network.ConnectionSecrets
import io.quietfiles.core.model.network.FtpSecurity
import io.quietfiles.core.model.network.Protocol
import io.quietfiles.core.model.network.RemoteProblem
import io.quietfiles.core.network.tls.SelfSigned
import io.quietfiles.core.security.Certificates
import io.quietfiles.core.storage.StorageException
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.ssl.SslConfiguration
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException
import java.net.ServerSocket
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory

/** Клиент FTP/FTPS против Apache FtpServer на локальном порту. */
class FtpTest {

    @get:Rule val tmp = TemporaryFolder()

    private var server: FtpServer? = null
    private val connector = FtpConnector()
    private val password = "secret".toCharArray()
    private lateinit var keyStore: KeyStore

    private fun start(tls: Boolean, implicit: Boolean = false): Int {
        val root = tmp.newFolder("root")
        File(root, "Музыка").mkdirs()
        File(root, "Музыка/song.mp3").writeText("abcdefghij")
        val port = ServerSocket(0).use { it.localPort }
        val factory = FtpServerFactory()
        val listener = ListenerFactory().apply {
            this.port = port
            serverAddress = "127.0.0.1"
            if (tls) {
                keyStore = SelfSigned.create("nas.local", listOf("127.0.0.1"), password)
                sslConfiguration = sslConfig(keyStore)
                isImplicitSsl = implicit
            }
        }
        factory.addListener("default", listener.createListener())
        factory.userManager.save(
            BaseUser().apply {
                name = "ivan"
                setPassword("пароль")
                homeDirectory = root.absolutePath
                authorities = listOf(WritePermission())
            },
        )
        server = factory.createServer().apply { start() }
        return port
    }

    private fun sslConfig(store: KeyStore): SslConfiguration {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        val context = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        return object : SslConfiguration {
            override fun getSocketFactory(): SSLSocketFactory = context.socketFactory
            override fun getSSLContext(): SSLContext = context
            override fun getSSLContext(protocol: String?): SSLContext = context
            override fun getClientAuth() = org.apache.ftpserver.ssl.ClientAuth.NONE
            override fun getEnabledCipherSuites(): Array<String>? = null
            override fun getEnabledProtocol(): String = "TLSv1.3"
            override fun getEnabledProtocols(): Array<String> = arrayOf("TLSv1.2", "TLSv1.3")
        }
    }

    @After
    fun stop() {
        server?.stop()
    }

    private fun connection(port: Int, security: FtpSecurity, pin: String? = null) = Connection(
        id = 3, protocol = Protocol.FTP, host = "127.0.0.1", port = port, username = "ivan", ftpSecurity = security, pinnedCertificate = pin,
        auth = AuthMethod.PASSWORD,
    )

    @Test
    fun plainFtpRoundTrip() {
        val port = start(tls = false)
        connector.connect(connection(port, FtpSecurity.NONE), ConnectionSecrets(password = "пароль")).use { fs ->
            assertThat(fs.list("/").map { it.name }).containsExactly("Музыка")
            val song = fs.stat("/Музыка/song.mp3")!!
            assertThat(song.size).isEqualTo(10)
            assertThat(song.isDirectory).isFalse()
            assertThat(fs.stat("/Музыка")!!.isDirectory).isTrue()
            assertThat(fs.stat("/нет")).isNull()

            assertThat(fs.openRead("/Музыка/song.mp3", 3).use { String(it.readBytes()) }).isEqualTo("defghij")
            fs.openWrite("/Музыка/новая.txt").use { it.write("данные".toByteArray()) }
            assertThat(fs.openRead("/Музыка/новая.txt", 0).use { String(it.readBytes()) }).isEqualTo("данные")
            // Пока шла передача, управляющее соединение свободно.
            assertThat(fs.list("/Музыка")).hasSize(2)

            fs.mkdir("/Пусто")
            fs.rename("/Пусто", "/Папка")
            assertThat(fs.list("/").map { it.name }).containsExactly("Музыка", "Папка")
            fs.delete("/Папка", isDirectory = true)
            fs.delete("/Музыка/новая.txt", isDirectory = false)
            assertThat(fs.list("/Музыка").map { it.name }).containsExactly("song.mp3")
            assertThrows(FileNotFoundException::class.java) { fs.openRead("/нет.txt", 0) }
            assertThat(fs.isAlive).isTrue()
        }
    }

    @Test
    fun wrongPassword() {
        val port = start(tls = false)
        val problem = assertThrows(StorageException.Remote::class.java) {
            connector.connect(connection(port, FtpSecurity.NONE), ConnectionSecrets(password = "нет"))
        }.problem
        assertThat(problem).isEqualTo(RemoteProblem.AuthFailed)
    }

    @Test
    fun selfSignedCertificateNeedsPinAndThenWorks() {
        val port = start(tls = true)
        val problem = assertThrows(StorageException.Remote::class.java) {
            connector.connect(connection(port, FtpSecurity.EXPLICIT), ConnectionSecrets(password = "пароль"))
        }.problem as RemoteProblem.UntrustedCertificate
        val spki = Certificates.spkiSha256(SelfSigned.certificateOf(keyStore))
        assertThat(problem.certificate.spkiSha256).isEqualTo(spki)
        assertThat(problem.certificate.subject).contains("nas.local")

        connector.connect(connection(port, FtpSecurity.EXPLICIT, pin = spki), ConnectionSecrets(password = "пароль")).use { fs ->
            assertThat(fs.openRead("/Музыка/song.mp3", 0).use { String(it.readBytes()) }).isEqualTo("abcdefghij")
        }

        val changed = assertThrows(StorageException.Remote::class.java) {
            connector.connect(connection(port, FtpSecurity.EXPLICIT, pin = "AAAA"), ConnectionSecrets(password = "пароль"))
        }.problem
        assertThat(changed).isInstanceOf(RemoteProblem.CertificateChanged::class.java)
    }

    @Test
    fun implicitTls() {
        val port = start(tls = true, implicit = true)
        val spki = Certificates.spkiSha256(SelfSigned.certificateOf(keyStore))
        connector.connect(connection(port, FtpSecurity.IMPLICIT, pin = spki), ConnectionSecrets(password = "пароль")).use { fs ->
            assertThat(fs.list("/Музыка").single().name).isEqualTo("song.mp3")
        }
    }
}
