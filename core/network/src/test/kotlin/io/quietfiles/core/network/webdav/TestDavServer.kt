package io.quietfiles.core.network.webdav

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLContext
import kotlin.concurrent.thread

/** Запрос и ответ тестового сервера: то немногое от HTTP, что нужно WebDAV. */
class Exchange(val requestMethod: String, val rawPath: String, private val headers: Map<String, String>, val body: ByteArray) {
    fun header(name: String): String? = headers[name.lowercase()]
    var status = 200
    val responseHeaders = mutableListOf<Pair<String, String>>()
    var responseBody: ByteArray? = null
    var chunked = false
    fun send(code: Int, bytes: ByteArray? = null) {
        status = code
        responseBody = bytes
    }
}

/** Маленький сервер WebDAV для тестов поверх папки [root]; корень DAV — [prefix]. */
class TestDavServer(
    private val root: File,
    private val prefix: String = "/dav",
    private val user: String = "ivan",
    private val password: String = "пароль",
    ssl: SSLContext? = null,
) : AutoCloseable {

    private val server: ServerSocket = ssl?.serverSocketFactory?.createServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        ?: ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))

    val port: Int get() = server.localPort
    val requests: MutableList<String> = CopyOnWriteArrayList()

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { serve(socket) }
            }
        }
    }

    private fun serve(socket: Socket) = socket.use {
        val input = BufferedInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        while (true) {
            val exchange = read(input) ?: break
            handle(exchange)
            write(output, exchange)
        }
    }

    private fun read(input: InputStream): Exchange? {
        val line = HttpClient.readLine(input) ?: return null
        if (line.isEmpty()) return null
        val (method, target) = line.split(' ')
        val headers = HashMap<String, String>()
        while (true) {
            val h = HttpClient.readLine(input) ?: return null
            if (h.isEmpty()) break
            headers[h.substringBefore(':').trim().lowercase()] = h.substringAfter(':').trim()
        }
        val length = headers["content-length"]?.toInt() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        return Exchange(method, target, headers, body)
    }

    private fun write(output: OutputStream, ex: Exchange) {
        val body = ex.responseBody
        val head = StringBuilder("HTTP/1.1 ${ex.status} X\r\n")
        ex.responseHeaders.forEach { (k, v) -> head.append("$k: $v\r\n") }
        if (body != null && ex.chunked) head.append("Transfer-Encoding: chunked\r\n") else head.append("Content-Length: ${body?.size ?: 0}\r\n")
        head.append("\r\n")
        output.write(head.toString().toByteArray())
        if (body != null && ex.chunked) {
            body.toList().chunked(4096).forEach { part ->
                output.write("${Integer.toHexString(part.size)}\r\n".toByteArray())
                output.write(part.toByteArray())
                output.write("\r\n".toByteArray())
            }
            output.write("0\r\n\r\n".toByteArray())
        } else if (body != null) {
            output.write(body)
        }
        output.flush()
    }

    private fun handle(ex: Exchange) {
        requests += ex.requestMethod + " " + ex.rawPath
        val auth = ex.header("Authorization")
        val expected = "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())
        if (auth != expected) {
            ex.responseHeaders.add("WWW-Authenticate" to "Basic realm=\"test\"")
            ex.send(401)
            return
        }
        val path = URI(ex.rawPath).path
        if (!path.startsWith(prefix)) {
            ex.send(404)
            return
        }
        val file = File(root, path.removePrefix(prefix))
        when (ex.requestMethod) {
            "PROPFIND" -> propfind(ex, file, path)
            "GET" -> get(ex, file)
            "PUT" -> {
                if (ex.header("If-None-Match") == "*" && file.exists()) return ex.send(412)
                if (file.parentFile?.isDirectory != true) return ex.send(409)
                val existed = file.exists()
                file.writeBytes(ex.body)
                ex.send(if (existed) 204 else 201)
            }
            "MKCOL" -> when {
                file.exists() -> ex.send(405)
                file.parentFile?.isDirectory != true -> ex.send(409)
                else -> {
                    file.mkdir()
                    ex.send(201)
                }
            }
            "DELETE" -> if (file.exists()) {
                file.deleteRecursively()
                ex.send(204)
            } else {
                ex.send(404)
            }
            "MOVE" -> {
                val destination = URI(ex.header("Destination")).path
                val target = File(root, destination.removePrefix(prefix))
                if (target.exists() && ex.header("Overwrite") == "F") return ex.send(412)
                if (!file.exists()) return ex.send(404)
                file.renameTo(target)
                ex.send(201)
            }
            else -> ex.send(405)
        }
    }

    private fun get(ex: Exchange, file: File) {
        if (!file.isFile) return ex.send(404)
        val bytes = file.readBytes()
        val range = ex.header("Range")
        if (range != null) {
            val start = range.removePrefix("bytes=").substringBefore('-').toInt()
            ex.responseHeaders.add("Content-Range" to "bytes $start-${bytes.size - 1}/${bytes.size}")
            ex.send(206, bytes.copyOfRange(start, bytes.size))
        } else {
            // Без длины: тело уйдёт кусками (chunked).
            ex.chunked = true
            ex.send(200, bytes)
        }
    }

    private fun propfind(ex: Exchange, file: File, path: String) {
        if (!file.exists()) return ex.send(404)
        val depth = ex.header("Depth") ?: "1"
        val items = mutableListOf(file to path)
        if (depth == "1" && file.isDirectory) {
            file.listFiles()!!.sortedBy { it.name }.forEach { items += it to path.trimEnd('/') + "/" + it.name }
        }
        val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
        val xml = buildString {
            append("""<?xml version="1.0" encoding="utf-8"?><d:multistatus xmlns:d="DAV:">""")
            for ((f, p) in items) {
                val href = p.split('/').joinToString("/") { WebDav.encodeSegment(it) } + if (f.isDirectory && !p.endsWith("/")) "/" else ""
                append("<d:response><d:href>").append(href).append("</d:href><d:propstat><d:prop>")
                if (f.isDirectory) append("<d:resourcetype><d:collection/></d:resourcetype>") else append("<d:resourcetype/><d:getcontentlength>${f.length()}</d:getcontentlength>")
                append("<d:getlastmodified>").append(date.format(f.lastModified())).append("</d:getlastmodified>")
                if (f == file) append("<d:quota-available-bytes>1000</d:quota-available-bytes><d:quota-used-bytes>500</d:quota-used-bytes>")
                append("</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>")
                append("<d:propstat><d:prop><d:displayname/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>")
            }
            append("</d:multistatus>")
        }.toByteArray()
        ex.responseHeaders.add("Content-Type" to "application/xml; charset=utf-8")
        ex.send(207, xml)
    }

    override fun close() = server.close()
}
