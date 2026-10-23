package io.quietfiles.core.network.sftp

import com.google.common.truth.Truth.assertThat
import io.quietfiles.core.model.network.AuthMethod
import io.quietfiles.core.model.network.Connection
import io.quietfiles.core.model.network.ConnectionSecrets
import io.quietfiles.core.model.network.Protocol
import io.quietfiles.core.model.network.RemoteProblem
import io.quietfiles.core.network.Crypto
import io.quietfiles.core.storage.StorageException
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyEncryptionContext
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyPair
import java.security.KeyPairGenerator

/** Клиент SFTP против настоящего сервера Apache MINA SSHD на локальном порту. */
class SftpTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: SshServer
    private lateinit var root: File
    private lateinit var clientKey: KeyPair
    private val connector = SftpConnector()

    @Before
    fun start() {
        Crypto.install()
        root = tmp.newFolder("root")
        clientKey = KeyPairGenerator.getInstance("Ed25519", "BC").generateKeyPair()
        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(tmp.root.toPath().resolve("host.ser")).apply { algorithm = "RSA" }
            setPasswordAuthenticator { user, password, _ -> user == "pi" && password == "raspberry" }
            publickeyAuthenticator = PublickeyAuthenticator { user, key, _ -> user == "pi" && key == clientKey.public }
            subsystemFactories = listOf(SftpSubsystemFactory())
            fileSystemFactory = VirtualFileSystemFactory(root.toPath())
            start()
        }
    }

    @After
    fun stop() = server.stop(true)

    private fun connection(hostKey: String? = null, auth: AuthMethod = AuthMethod.PASSWORD) = Connection(
        id = 7, protocol = Protocol.SFTP, host = "127.0.0.1", port = server.port, username = "pi", auth = auth, hostKey = hostKey,
    )

    private fun fingerprint(): String {
        val problem = assertThrows(StorageException.Remote::class.java) {
            connector.connect(connection(), ConnectionSecrets(password = "raspberry"))
        }.problem
        assertThat(problem).isInstanceOf(RemoteProblem.UnknownHostKey::class.java)
        return (problem as RemoteProblem.UnknownHostKey).fingerprint
    }

    @Test
    fun unknownHostKeyAsksAndChangedKeyBlocks() {
        val fp = fingerprint()
        assertThat(fp).startsWith("SHA256:")
        assertThat(fp).doesNotContain("=")

        val changed = assertThrows(StorageException.Remote::class.java) {
            connector.connect(connection(hostKey = "SHA256:other"), ConnectionSecrets(password = "raspberry"))
        }.problem as RemoteProblem.HostKeyChanged
        assertThat(changed.expected).isEqualTo("SHA256:other")
        assertThat(changed.actual).isEqualTo(fp)
    }

    @Test
    fun wrongPasswordIsAuthFailure() {
        val fp = fingerprint()
        val problem = assertThrows(StorageException.Remote::class.java) {
            connector.connect(connection(hostKey = fp), ConnectionSecrets(password = "wrong"))
        }.problem
        assertThat(problem).isEqualTo(RemoteProblem.AuthFailed)
    }

    @Test
    fun filesRoundTrip() {
        File(root, "Фото").mkdirs()
        File(root, "Фото/a.jpg").writeText("0123456789")
        val fs = connector.connect(connection(hostKey = fingerprint()), ConnectionSecrets(password = "raspberry"))
        fs.use {
            assertThat(fs.list("/").map { it.name to it.isDirectory }).containsExactly("Фото" to true)
            val photo = fs.list("/Фото").single()
            assertThat(photo.path).isEqualTo("/Фото/a.jpg")
            assertThat(photo.size).isEqualTo(10)

            assertThat(fs.openRead("/Фото/a.jpg", 4).use { String(it.readBytes()) }).isEqualTo("456789")

            fs.mkdir("/Документы")
            fs.openWrite("/Документы/b.txt").use { it.write("привет".toByteArray()) }
            assertThat(File(root, "Документы/b.txt").readText()).isEqualTo("привет")
            assertThat(fs.stat("/Документы/b.txt")!!.size).isEqualTo("привет".toByteArray().size.toLong())

            fs.rename("/Документы/b.txt", "/Документы/c.txt")
            assertThat(fs.stat("/Документы/b.txt")).isNull()
            fs.setLastModified("/Документы/c.txt", 1_600_000_000_000)
            assertThat(File(root, "Документы/c.txt").lastModified()).isEqualTo(1_600_000_000_000)

            fs.delete("/Документы/c.txt", isDirectory = false)
            fs.delete("/Документы", isDirectory = true)
            assertThat(File(root, "Документы").exists()).isFalse()

            assertThrows(FileNotFoundException::class.java) { fs.list("/нет") }
            assertThat(fs.isAlive).isTrue()
        }
        assertThat(fs.isAlive).isFalse()
    }

    @Test
    fun keyAuthWithPassphrase() {
        val pem = ByteArrayOutputStream().also {
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(
                clientKey, "test",
                OpenSSHKeyEncryptionContext().apply {
                    password = "фраза"
                    cipherName = "AES"
                    cipherMode = "CTR"
                    cipherType = "256"
                },
                it,
            )
        }.toString(Charsets.UTF_8)
        val fp = fingerprint()
        connector.connect(connection(hostKey = fp, auth = AuthMethod.KEY), ConnectionSecrets(privateKey = pem, passphrase = "фраза")).use {
            assertThat(it.list("/")).isEmpty()
        }
        val wrong = assertThrows(StorageException.Remote::class.java) {
            connector.connect(connection(hostKey = fp, auth = AuthMethod.KEY), ConnectionSecrets(privateKey = pem, passphrase = "не та"))
        }.problem
        assertThat(wrong).isEqualTo(RemoteProblem.AuthFailed)
    }
}
