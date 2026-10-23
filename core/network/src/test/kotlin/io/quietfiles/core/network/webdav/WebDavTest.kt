package io.quietfiles.core.network.webdav

import com.google.common.truth.Truth.assertThat
import io.quietfiles.core.model.network.Connection
import io.quietfiles.core.model.network.ConnectionSecrets
import io.quietfiles.core.model.network.Protocol
import io.quietfiles.core.model.network.RemoteProblem
import io.quietfiles.core.network.tls.SelfSigned
import io.quietfiles.core.security.Certificates
import io.quietfiles.core.storage.StorageException
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.FileAlreadyExistsException
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

@RunWith(RobolectricTestRunner::class)
class WebDavTest {

    @get:Rule val tmp = TemporaryFolder()

    private val connector by lazy { WebDavConnector { tmp.newFolder() } }

    private fun connection(port: Int, plain: Boolean, pin: String? = null) = Connection(
        id = 5, protocol = Protocol.WEBDAV, host = "127.0.0.1", port = port, username = "ivan", path = "/dav",
        plainHttp = plain, pinnedCertificate = pin,
    )

    @Test
    fun plainHttpRoundTrip() {
        val root = tmp.newFolder("dav")
        File(root, "Отчёты 2026").mkdirs()
        File(root, "Отчёты 2026/итог #1.txt").writeText("0123456789")
        TestDavServer(root).use { server ->
            connector.connect(connection(server.port, plain = true), ConnectionSecrets(password = "пароль")).use { fs ->
                val top = fs.list("/")
                assertThat(top.map { it.name to it.isDirectory }).containsExactly("Отчёты 2026" to true)
                val report = fs.list("/Отчёты 2026").single()
                assertThat(report.path).isEqualTo("/Отчёты 2026/итог #1.txt")
                assertThat(report.size).isEqualTo(10)
                assertThat(report.lastModified).isGreaterThan(0)

                assertThat(fs.openRead(report.path, 0).use { String(it.readBytes()) }).isEqualTo("0123456789")
                assertThat(fs.openRead(report.path, 6).use { String(it.readBytes()) }).isEqualTo("6789")

                fs.mkdir("/Новая")
                assertThrows(FileAlreadyExistsException::class.java) { fs.mkdir("/Новая") }
                fs.openWrite("/Новая/a.bin").use { it.write(ByteArray(100_000) { i -> i.toByte() }) }
                assertThat(File(root, "Новая/a.bin").length()).isEqualTo(100_000)
                fs.createFile("/Новая/пустой.txt")
                assertThrows(FileAlreadyExistsException::class.java) { fs.createFile("/Новая/пустой.txt") }

                fs.rename("/Новая/a.bin", "/Новая/b.bin")
                assertThat(fs.stat("/Новая/b.bin")!!.size).isEqualTo(100_000)
                assertThat(fs.stat("/Новая/a.bin")).isNull()
                assertThat(fs.space("/")!!.free).isEqualTo(1000)

                fs.delete("/Новая", isDirectory = true)
                assertThat(File(root, "Новая").exists()).isFalse()
            }
            // Запросы шли по закодированным путям.
            assertThat(server.requests).contains("PROPFIND /dav/%D0%9E%D1%82%D1%87%D1%91%D1%82%D1%8B%202026")
        }
    }

    @Test
    fun wrongPasswordIsAuthFailure() {
        TestDavServer(tmp.newFolder("dav")).use { server ->
            val problem = assertThrows(StorageException.Remote::class.java) {
                connector.connect(connection(server.port, plain = true), ConnectionSecrets(password = "нет"))
            }.problem
            assertThat(problem).isEqualTo(RemoteProblem.AuthFailed)
        }
    }

    @Test
    fun httpsSelfSignedIsPinned() {
        val password = "x".toCharArray()
        val store = SelfSigned.create("nextcloud.home", listOf("127.0.0.1"), password)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        val ssl = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        TestDavServer(tmp.newFolder("dav"), ssl = ssl).use { server ->
            val problem = assertThrows(StorageException.Remote::class.java) {
                connector.connect(connection(server.port, plain = false), ConnectionSecrets(password = "пароль"))
            }.problem as RemoteProblem.UntrustedCertificate
            val spki = Certificates.spkiSha256(SelfSigned.certificateOf(store))
            assertThat(problem.certificate.spkiSha256).isEqualTo(spki)

            connector.connect(connection(server.port, plain = false, pin = spki), ConnectionSecrets(password = "пароль")).use { fs ->
                assertThat(fs.list("/")).isEmpty()
            }
        }
    }

    @Test
    fun parsesMultistatusOfOtherServers() {
        // Nextcloud: абсолютные href, свойства 404 в отдельном propstat.
        val xml = """<?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
             <d:response><d:href>/remote.php/dav/files/ivan/</d:href>
              <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype><d:quota-available-bytes>-3</d:quota-available-bytes></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
             <d:response><d:href>https://cloud.example/remote.php/dav/files/ivan/Photos%20(old)/</d:href>
              <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype><d:getlastmodified>Mon, 12 Jan 2026 10:00:00 GMT</d:getlastmodified></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              <d:propstat><d:prop><d:getcontentlength/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>
             <d:response><d:href>/remote.php/dav/files/ivan/a%2Bb.txt</d:href>
              <d:propstat><d:prop><d:resourcetype/><d:getcontentlength>42</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
            </d:multistatus>"""
        val items = WebDav.parseMultistatus(xml)
        assertThat(WebDav.space(items[0])).isNull()
        val children = WebDav.children(items, "/remote.php/dav/files/ivan", "/")
        assertThat(children.map { Triple(it.path, it.isDirectory, it.size) })
            .containsExactly(Triple("/Photos (old)", true, 0L), Triple("/a+b.txt", false, 42L)).inOrder()
        assertThat(children[0].lastModified).isEqualTo(1768212000000)
        assertThat(WebDav.target("/remote.php/dav/files/ivan", "/Photos (old)", directory = true))
            .isEqualTo("/remote.php/dav/files/ivan/Photos%20(old)/")
    }

    @Test
    fun digestChallengeAndChunkedBody() {
        val challenge = HttpClient.Challenge.parse("""Digest realm="NAS", nonce="abc,def", qop="auth,auth-int", algorithm=MD5, opaque="xyz"""")
        assertThat(challenge).isEqualTo(HttpClient.Challenge.Digest("NAS", "abc,def", "xyz", "auth,auth-int", "MD5"))
        assertThat(HttpClient.Challenge.parse("Bearer realm=x")).isNull()

        val chunked = "5;ext=1\r\nhello\r\n6\r\n world\r\n0\r\nX-Trailer: 1\r\n\r\n"
        assertThat(String(ChunkedInputStream(ByteArrayInputStream(chunked.toByteArray())).readBytes())).isEqualTo("hello world")
    }
}
