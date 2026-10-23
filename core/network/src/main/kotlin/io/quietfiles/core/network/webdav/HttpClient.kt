package io.quietfiles.core.network.webdav

import io.quietfiles.core.security.ServerTrust
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket

/** Тело запроса известной длины. */
class RequestBody(val length: Long, val contentType: String? = null, val writeTo: (OutputStream) -> Unit) {
    companion object {
        fun of(bytes: ByteArray, contentType: String? = null) = RequestBody(bytes.size.toLong(), contentType) { it.write(bytes) }
    }
}

class HttpResponse(
    val code: Int,
    val message: String,
    private val headers: List<Pair<String, String>>,
    /** Тело ответа; закрыть обязательно — иначе соединение не вернётся для следующих запросов. */
    val body: InputStream,
) : AutoCloseable {
    fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
    fun headers(name: String): List<String> = headers.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }
    fun text(): String = body.use { String(it.readBytes(), Charsets.UTF_8) }
    override fun close() = body.close()
}

/**
 * Небольшой клиент HTTP/1.1 для WebDAV. Свой, а не системный: системный не умеет методы WebDAV,
 * а сторонние библиотеки на Android подчиняются общему запрету открытого HTTP (S4) — нам же HTTP без TLS
 * нужен только для одного сервера, который пользователь добавил сам и явно выбрал «без шифрования».
 *
 * TLS проверяет [ServerTrust] (S3). Вход — Basic и Digest (RFC 7616, MD5 и SHA-256, qop=auth).
 * Одно соединение переиспользуется для коротких запросов; потоковые чтение и запись открывают своё.
 */
class HttpClient(
    private val host: String,
    private val port: Int,
    private val tls: Boolean,
    private val trust: ServerTrust?,
    private val username: String,
    private val password: String,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000,
) : AutoCloseable {

    private class Conn(val socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream(), 16 * 1024)
        val output: OutputStream = socket.getOutputStream().buffered(16 * 1024)
    }

    private val lock = Any()
    private var idle: Conn? = null
    @Volatile private var challenge: Challenge? = null
    private var nonceCount = 0
    @Volatile var closed = false
        private set

    /** Запрос; на 401 повторяется один раз со входом по вызову сервера. */
    fun execute(method: String, target: String, headers: Map<String, String> = emptyMap(), body: RequestBody? = null, stream: Boolean = false): HttpResponse {
        var response = send(method, target, headers, body, stream)
        if (response.code == 401 && username.isNotEmpty()) {
            val parsed = response.headers("WWW-Authenticate").mapNotNull(Challenge::parse).maxByOrNull { it.strength }
            response.close()
            if (parsed == null) return send(method, target, headers, body, stream)
            challenge = parsed
            response = send(method, target, headers, body, stream)
        }
        return response
    }

    private fun send(method: String, target: String, headers: Map<String, String>, body: RequestBody?, stream: Boolean): HttpResponse {
        if (closed) throw IOException("Client closed")
        // Повторить можно только если запрос ушёл в старое соединение, которое сервер успел закрыть.
        val reused = if (stream) null else synchronized(lock) { idle.also { idle = null } }
        if (reused != null) {
            try {
                return exchange(reused, method, target, headers, body, stream)
            } catch (e: IOException) {
                runCatching { reused.socket.close() }
            }
        }
        return exchange(open(), method, target, headers, body, stream)
    }

    private fun exchange(conn: Conn, method: String, target: String, headers: Map<String, String>, body: RequestBody?, stream: Boolean): HttpResponse {
        try {
            val head = StringBuilder()
            head.append(method).append(' ').append(target).append(" HTTP/1.1\r\n")
            head.append("Host: ").append(hostHeader()).append("\r\n")
            head.append("User-Agent: QuietFiles\r\n")
            authorization(method, target)?.let { head.append("Authorization: ").append(it).append("\r\n") }
            headers.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
            if (body != null) {
                body.contentType?.let { head.append("Content-Type: ").append(it).append("\r\n") }
                head.append("Content-Length: ").append(body.length).append("\r\n")
            } else if (method == "PUT" || method == "POST" || method == "PROPFIND" || method == "PROPPATCH") {
                head.append("Content-Length: 0\r\n")
            }
            head.append("\r\n")
            conn.output.write(head.toString().toByteArray(Charsets.UTF_8))
            body?.writeTo?.invoke(conn.output)
            conn.output.flush()
            return readResponse(conn, method)
        } catch (e: IOException) {
            runCatching { conn.socket.close() }
            throw e
        }
    }

    private fun readResponse(conn: Conn, method: String): HttpResponse {
        while (true) {
            val status = readLine(conn.input) ?: throw IOException("Connection closed by server")
            val parts = status.split(' ', limit = 3)
            if (parts.size < 2 || !parts[0].startsWith("HTTP/")) throw IOException("Bad status line")
            val code = parts[1].toIntOrNull() ?: throw IOException("Bad status code")
            val headers = ArrayList<Pair<String, String>>()
            while (true) {
                val line = readLine(conn.input) ?: throw IOException("Connection closed in headers")
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers += line.substring(0, colon).trim() to line.substring(colon + 1).trim()
            }
            if (code in 100..199) continue
            fun header(name: String) = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
            val keepAlive = !parts[0].equals("HTTP/1.0", true) && header("Connection")?.equals("close", ignoreCase = true) != true
            val chunked = header("Transfer-Encoding")?.contains("chunked", ignoreCase = true) == true
            val length = header("Content-Length")?.toLongOrNull()
            val noBody = method == "HEAD" || code == 204 || code == 304
            val raw: InputStream = when {
                noBody -> java.io.ByteArrayInputStream(ByteArray(0))
                chunked -> ChunkedInputStream(conn.input)
                length != null -> LimitedInputStream(conn.input, length)
                else -> conn.input
            }
            val reusable = keepAlive && (noBody || chunked || length != null)
            val body = object : FilterInputStream(raw) {
                private var done = false
                override fun close() {
                    if (done) return
                    done = true
                    if (reusable && drain(raw)) release(conn) else runCatching { conn.socket.close() }
                }
            }
            return HttpResponse(code, parts.getOrElse(2) { "" }, headers, body)
        }
    }

    /** Дочитывает остаток небольшого тела, чтобы соединение можно было использовать дальше. */
    private fun drain(input: InputStream): Boolean = try {
        val buffer = ByteArray(8192)
        var left = MAX_DRAIN
        while (left > 0) {
            val n = input.read(buffer)
            if (n < 0) break
            left -= n
        }
        left > 0
    } catch (_: IOException) {
        false
    }

    private fun release(conn: Conn) {
        synchronized(lock) {
            if (closed || idle != null) runCatching { conn.socket.close() } else idle = conn
        }
    }

    private fun open(): Conn {
        val plain = Socket()
        plain.connect(InetSocketAddress(host, port), connectTimeoutMs)
        plain.soTimeout = readTimeoutMs
        plain.tcpNoDelay = true
        if (!tls) return Conn(plain)
        val trust = trust ?: error("TLS without trust manager")
        val socket = trust.socketFactory().createSocket(plain, host, port, true) as SSLSocket
        try {
            socket.startHandshake()
            if (!trust.verifyHostname(socket.session)) throw SSLPeerUnverifiedException("Hostname $host not verified")
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw e
        }
        return Conn(socket)
    }

    /** `https://host:port` — для заголовка Destination у MOVE. */
    fun baseUrl(): String = (if (tls) "https://" else "http://") + hostHeader()

    private fun hostHeader(): String {
        val h = if (host.contains(':')) "[$host]" else host
        val defaultPort = if (tls) 443 else 80
        return if (port == defaultPort) h else "$h:$port"
    }

    private fun authorization(method: String, target: String): String? {
        val c = challenge ?: return if (tls && username.isNotEmpty()) basic() else null
        return when (c) {
            is Challenge.Basic -> basic()
            is Challenge.Digest -> digest(c, method, target)
        }
    }

    private fun basic() = "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))

    private fun digest(c: Challenge.Digest, method: String, uri: String): String {
        val algorithm = if (c.algorithm.startsWith("SHA-256", ignoreCase = true)) "SHA-256" else "MD5"
        fun h(s: String) = MessageDigest.getInstance(algorithm).digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val cnonce = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val nc = synchronized(lock) { ++nonceCount }.let { "%08x".format(it) }
        var ha1 = h("$username:${c.realm}:$password")
        if (c.algorithm.endsWith("-sess", ignoreCase = true)) ha1 = h("$ha1:${c.nonce}:$cnonce")
        val ha2 = h("$method:$uri")
        val qop = c.qop?.split(',')?.map { it.trim() }?.firstOrNull { it == "auth" }
        val response = if (qop != null) h("$ha1:${c.nonce}:$nc:$cnonce:$qop:$ha2") else h("$ha1:${c.nonce}:$ha2")
        return buildString {
            append("Digest username=\"").append(username.replace("\"", "\\\"")).append('"')
            append(", realm=\"").append(c.realm).append('"')
            append(", nonce=\"").append(c.nonce).append('"')
            append(", uri=\"").append(uri).append('"')
            append(", algorithm=").append(c.algorithm)
            append(", response=\"").append(response).append('"')
            c.opaque?.let { append(", opaque=\"").append(it).append('"') }
            if (qop != null) append(", qop=").append(qop).append(", nc=").append(nc).append(", cnonce=\"").append(cnonce).append('"')
        }
    }

    override fun close() {
        closed = true
        synchronized(lock) {
            idle?.let { runCatching { it.socket.close() } }
            idle = null
        }
    }

    sealed interface Challenge {
        val strength: Int

        data object Basic : Challenge {
            override val strength = 1
        }

        data class Digest(val realm: String, val nonce: String, val opaque: String?, val qop: String?, val algorithm: String) : Challenge {
            override val strength = 2
        }

        companion object {
            fun parse(header: String): Challenge? {
                val space = header.indexOf(' ')
                val scheme = (if (space < 0) header else header.substring(0, space)).lowercase(Locale.ROOT)
                val params = if (space < 0) emptyMap() else parseParams(header.substring(space + 1))
                return when (scheme) {
                    "basic" -> Basic
                    "digest" -> {
                        val nonce = params["nonce"] ?: return null
                        val algorithm = params["algorithm"] ?: "MD5"
                        if (!algorithm.startsWith("MD5", true) && !algorithm.startsWith("SHA-256", true)) return null
                        Digest(params["realm"].orEmpty(), nonce, params["opaque"], params["qop"], algorithm)
                    }
                    else -> null
                }
            }

            internal fun parseParams(s: String): Map<String, String> {
                val result = HashMap<String, String>()
                var i = 0
                while (i < s.length) {
                    while (i < s.length && (s[i] == ' ' || s[i] == ',')) i++
                    val eq = s.indexOf('=', i).takeIf { it > 0 } ?: break
                    val key = s.substring(i, eq).trim().lowercase(Locale.ROOT)
                    i = eq + 1
                    val value: String
                    if (i < s.length && s[i] == '"') {
                        val sb = StringBuilder()
                        i++
                        while (i < s.length && s[i] != '"') {
                            if (s[i] == '\\' && i + 1 < s.length) i++
                            sb.append(s[i])
                            i++
                        }
                        i++
                        value = sb.toString()
                    } else {
                        val end = s.indexOf(',', i).let { if (it < 0) s.length else it }
                        value = s.substring(i, end).trim()
                        i = end
                    }
                    result[key] = value
                }
                return result
            }
        }
    }

    companion object {
        private const val MAX_DRAIN = 64 * 1024

        internal fun readLine(input: InputStream): String? {
            val out = ByteArrayOutputStream(64)
            while (true) {
                val b = input.read()
                if (b < 0) return if (out.size() == 0) null else out.toString(Charsets.ISO_8859_1.name())
                if (b == '\n'.code) break
                if (out.size() > 16 * 1024) throw IOException("Header line too long")
                out.write(b)
            }
            val bytes = out.toByteArray()
            val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
            return String(bytes, 0, end, Charsets.UTF_8)
        }
    }
}

internal class LimitedInputStream(private val input: InputStream, private var left: Long) : InputStream() {
    override fun read(): Int {
        if (left <= 0) return -1
        val b = input.read()
        if (b < 0) throw IOException("Unexpected end of body")
        left--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (left <= 0) return -1
        val n = input.read(b, off, minOf(len.toLong(), left).toInt())
        if (n < 0) throw IOException("Unexpected end of body")
        left -= n
        return n
    }

    override fun available(): Int = minOf(input.available().toLong(), left).toInt()
}

internal class ChunkedInputStream(private val input: InputStream) : InputStream() {
    private var chunkLeft = 0L
    private var finished = false

    private fun nextChunk(): Boolean {
        if (finished) return false
        if (chunkLeft == 0L) {
            val line = HttpClient.readLine(input) ?: throw IOException("Unexpected end of chunked body")
            val size = line.substringBefore(';').trim()
            chunkLeft = size.toLongOrNull(16) ?: throw IOException("Bad chunk size")
            if (chunkLeft == 0L) {
                // Заголовки-трейлеры до пустой строки.
                while (true) {
                    val trailer = HttpClient.readLine(input) ?: break
                    if (trailer.isEmpty()) break
                }
                finished = true
                return false
            }
        }
        return true
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!nextChunk()) return -1
        val n = input.read(b, off, minOf(len.toLong(), chunkLeft).toInt())
        if (n < 0) throw IOException("Unexpected end of chunk")
        chunkLeft -= n
        if (chunkLeft == 0L) HttpClient.readLine(input)
        return n
    }
}
