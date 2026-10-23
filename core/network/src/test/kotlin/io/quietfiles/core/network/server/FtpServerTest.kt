package io.quietfiles.core.network.server

import com.google.common.truth.Truth.assertThat
import io.quietfiles.core.model.network.AuthMethod
import io.quietfiles.core.model.network.Connection
import io.quietfiles.core.model.network.ConnectionSecrets
import io.quietfiles.core.model.network.FtpSecurity
import io.quietfiles.core.model.network.Protocol
import io.quietfiles.core.model.network.RemoteProblem
import io.quietfiles.core.network.ftp.FtpConnector
import io.quietfiles.core.network.tls.SelfSigned
import io.quietfiles.core.security.Certificates
import io.quietfiles.core.storage.StorageException
import org.apache.commons.net.ftp.FTPClient
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/** Сервер «Доступа с ПК» против нашего же клиента FTP (commons-net) и голого FTPClient. */
class FtpServerTest {

    @get:Rule val tmp = TemporaryFolder()

    private var server: FtpServer? = null
    private val keyPassword = "k".toCharArray()
    private val keyStore by lazy { SelfSigned.create("Quiet Files", password = keyPassword) }

    private fun start(tls: Boolean, anonymous: Boolean = false): Pair<FtpServer, File> {
        val root = tmp.newFolder("phone")
        File(root, "DCIM").mkdirs()
        File(root, "DCIM/photo.jpg").writeText("0123456789")
        val context = if (tls) {
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, keyPassword) }
            SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        } else {
            null
        }
        val ftp = FtpServer(FtpServerConfig(InetAddress.getByName("127.0.0.1"), 0, root, "phone", "secret".toCharArray(), anonymous, context))
        ftp.start()
        server = ftp
        return ftp to root
    }

    @After
    fun stop() {
        server?.close()
    }

    private fun connection(port: Int, security: FtpSecurity, auth: AuthMethod = AuthMethod.PASSWORD, pin: String? = null) = Connection(
        id = 9, protocol = Protocol.FTP, host = "127.0.0.1", port = port, username = "phone", auth = auth, ftpSecurity = security, pinnedCertificate = pin,
    )

    @Test
    fun ftpsRoundTripWithPinnedCertificate() {
        val (ftp, root) = start(tls = true)
        val spki = Certificates.spkiSha256(SelfSigned.certificateOf(keyStore))
        FtpConnector().connect(connection(ftp.port, FtpSecurity.EXPLICIT, pin = spki), ConnectionSecrets(password = "secret")).use { fs ->
            assertThat(fs.list("/").map { it.name to it.isDirectory }).containsExactly("DCIM" to true)
            assertThat(fs.stat("/DCIM/photo.jpg")!!.size).isEqualTo(10)
            assertThat(fs.openRead("/DCIM/photo.jpg", 6).use { String(it.readBytes()) }).isEqualTo("6789")
            fs.mkdir("/Документы")
            fs.openWrite("/Документы/с ПК.txt").use { it.write("привет".toByteArray()) }
            assertThat(File(root, "Документы/с ПК.txt").readText()).isEqualTo("привет")
            fs.rename("/Документы/с ПК.txt", "/Документы/b.txt")
            fs.delete("/Документы/b.txt", isDirectory = false)
            fs.delete("/Документы", isDirectory = true)
            assertThat(File(root, "Документы").exists()).isFalse()
            assertThat(ftp.clients).isNotEmpty()
            assertThat(ftp.clients.first().secure).isTrue()
        }
    }

    @Test
    fun refusesPlainLoginWhenTlsIsOn() {
        val (ftp, _) = start(tls = true)
        val client = FTPClient()
        client.connect("127.0.0.1", ftp.port)
        assertThat(client.login("phone", "secret")).isFalse()
        assertThat(client.replyCode).isEqualTo(530)
        client.disconnect()
    }

    @Test
    fun wrongPasswordAndPathEscape() {
        val (ftp, root) = start(tls = false)
        val problem = assertThrows(StorageException.Remote::class.java) {
            FtpConnector().connect(connection(ftp.port, FtpSecurity.NONE), ConnectionSecrets(password = "нет"))
        }.problem
        assertThat(problem).isEqualTo(RemoteProblem.AuthFailed)

        File(root.parentFile, "secret.txt").writeText("outside")
        // Наш клиент «..» не отправляет, поэтому проверяем сервер напрямую: за корень не выйти.
        val client = FTPClient()
        client.connect("127.0.0.1", ftp.port)
        assertThat(client.login("phone", "secret")).isTrue()
        client.enterLocalPassiveMode()
        assertThat(client.listNames("/../..").toList()).containsExactly("DCIM")
        assertThat(client.changeWorkingDirectory("../..")).isTrue()
        assertThat(client.printWorkingDirectory()).isEqualTo("/")
        assertThat(client.retrieveFileStream("/../secret.txt")).isNull()
        assertThat(client.replyCode).isEqualTo(550)
        client.disconnect()
    }

    @Test
    fun anonymousIsReadOnly() {
        val (ftp, root) = start(tls = false, anonymous = true)
        FtpConnector().connect(connection(ftp.port, FtpSecurity.NONE, auth = AuthMethod.ANONYMOUS), null).use { fs ->
            assertThat(fs.openRead("/DCIM/photo.jpg", 0).use { String(it.readBytes()) }).isEqualTo("0123456789")
            assertThrows(Exception::class.java) { fs.mkdir("/new") }
            assertThrows(Exception::class.java) { fs.openWrite("/DCIM/x.txt").use { it.write(1) } }
        }
        assertThat(File(root, "new").exists()).isFalse()
        assertThat(File(root, "DCIM/x.txt").exists()).isFalse()
    }

    @Test
    fun disconnectsClient() {
        val (ftp, _) = start(tls = false)
        val client = FTPClient()
        client.connect("127.0.0.1", ftp.port)
        assertThat(client.login("phone", "secret")).isTrue()
        val id = ftp.clients.single().id
        assertThat(ftp.clients.single().user).isEqualTo("phone")
        ftp.disconnect(id)
        Thread.sleep(200)
        assertThat(ftp.clients).isEmpty()
        assertThat(runCatching { client.sendNoOp() }.getOrDefault(false)).isFalse()
    }
}
