package io.quietfiles.core.network

import com.google.common.truth.Truth.assertThat
import io.quietfiles.core.model.network.Connection
import io.quietfiles.core.model.network.ConnectionSecrets
import io.quietfiles.core.model.network.ConnectionStatus
import io.quietfiles.core.model.network.NetworkPaths
import io.quietfiles.core.model.network.Protocol
import io.quietfiles.core.model.network.RemoteProblem
import io.quietfiles.core.network.sftp.SftpConnector
import io.quietfiles.core.storage.LocalStorageProvider
import io.quietfiles.core.storage.StorageException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Сетевое хранилище целиком: маршрутизация путей, сессии, переподключение — на настоящем SFTP. */
class NetworkStorageProviderTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: SshServer
    private lateinit var root: File
    private var connection = Connection(id = 1, protocol = Protocol.SFTP, host = "127.0.0.1", username = "pi", name = "Raspberry Pi")
    private var secrets: ConnectionSecrets? = ConnectionSecrets(password = "raspberry")
    private lateinit var sessions: RemoteSessions
    private lateinit var router: RoutingStorageProvider

    @Before
    fun start() {
        Crypto.install()
        root = tmp.newFolder("server")
        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(tmp.root.toPath().resolve("host.ser")).apply { algorithm = "RSA" }
            setPasswordAuthenticator { user, password, _ -> user == "pi" && password == "raspberry" }
            subsystemFactories = listOf(SftpSubsystemFactory())
            fileSystemFactory = VirtualFileSystemFactory(root.toPath())
            start()
        }
        connection = connection.copy(port = server.port)
        val sftp = SftpConnector()
        val fingerprint = (assertThrows(StorageException.Remote::class.java) { sftp.connect(connection, secrets) }.problem as RemoteProblem.UnknownHostKey).fingerprint
        connection = connection.copy(hostKey = fingerprint)
        sessions = RemoteSessions(
            object : ConnectionLookup {
                override fun connection(id: Long) = connection.takeIf { it.id == id }
                override fun secrets(id: Long) = secrets
            },
        ) { sftp }
        val list = object : ConnectionList {
            override suspend fun all() = listOf(connection)
            override suspend fun get(id: Long) = connection.takeIf { it.id == id }
        }
        router = RoutingStorageProvider(LocalStorageProvider(Dispatchers.Unconfined), NetworkStorageProvider(sessions, list, Dispatchers.Unconfined))
    }

    @After
    fun stop() {
        sessions.disconnectAll()
        server.stop(true)
    }

    private val base get() = NetworkPaths.rootOf(1)

    @Test
    fun browsesAndEditsServerFiles() = runBlocking {
        val connections = router.list(NetworkPaths.ROOT, includeHidden = false)
        assertThat(connections.map { it.name to it.path }).containsExactly("Raspberry Pi" to base)
        assertThat(router.stat(base)!!.isDirectory).isTrue()

        File(root, ".hidden").writeText("x")
        File(root, "a.txt").writeText("hello world")
        assertThat(router.list(base, includeHidden = false).map { it.name }).containsExactly("a.txt")
        assertThat(router.list(base, includeHidden = true).map { it.name }).containsExactly("a.txt", ".hidden")

        val dir = router.createDirectory(base, "Папка")
        assertThat(dir.path).isEqualTo("$base/Папка")
        assertThrows(StorageException.AlreadyExists::class.java) { runBlocking { router.createDirectory(base, "Папка") } }
        assertThrows(StorageException.InvalidName::class.java) { runBlocking { router.createDirectory(base, "a/b") } }

        router.openWrite("$base/Папка/b.txt").use { it.write("данные".toByteArray()) }
        assertThat(router.openRead("$base/a.txt", 6).use { String(it.readBytes()) }).isEqualTo("world")

        val renamed = router.rename("$base/a.txt", "c.txt")
        assertThat(renamed.path).isEqualTo("$base/c.txt")
        assertThat(router.moveFast("$base/c.txt", "$base/Папка/c.txt")).isTrue()
        assertThat(File(root, "Папка/c.txt").readText()).isEqualTo("hello world")
        // Между устройством и сервером «быстрого» переноса нет — только копирование.
        assertThat(router.moveFast("$base/Папка/c.txt", tmp.root.absolutePath + "/c.txt")).isFalse()

        router.delete("$base/Папка")
        assertThat(File(root, "Папка").exists()).isFalse()
        assertThrows(StorageException.NotFound::class.java) { runBlocking { router.list("$base/нет", false) } }
        assertThat(sessions.statuses.value[1]).isEqualTo(ConnectionStatus.ONLINE)
    }

    @Test
    fun reconnectsAfterServerDropsSession() = runBlocking {
        File(root, "a.txt").writeText("1")
        assertThat(router.list(base, false)).hasSize(1)
        server.activeSessions.forEach { it.close(true) }
        Thread.sleep(200)
        assertThat(router.list(base, false)).hasSize(1)
    }

    @Test
    fun missingSecretsAskToLogIn() {
        secrets = null
        val error = assertThrows(StorageException.Remote::class.java) { runBlocking { router.list(base, false) } }
        assertThat(error.problem).isEqualTo(RemoteProblem.NeedsLogin)
        assertThat(sessions.statuses.value[1]).isEqualTo(ConnectionStatus.NEEDS_LOGIN)
    }

    @Test
    fun testsConnectionBeforeSaving() {
        assertThat(sessions.test(connection, secrets)).isNull()
        assertThat(sessions.test(connection, ConnectionSecrets(password = "нет"))).isEqualTo(RemoteProblem.AuthFailed)
        assertThat(sessions.test(connection.copy(hostKey = null), secrets)).isInstanceOf(RemoteProblem.UnknownHostKey::class.java)
        assertThat(sessions.test(connection.copy(port = 1), secrets)).isEqualTo(RemoteProblem.Unreachable)
    }
}
