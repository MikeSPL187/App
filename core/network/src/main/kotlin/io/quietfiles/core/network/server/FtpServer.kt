package io.quietfiles.core.network.server

import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** Кто подключён к «Доступу с ПК» (F17.3). */
data class FtpClientInfo(val id: Int, val address: String, val user: String?, val connectedAt: Long, val secure: Boolean)

/**
 * Настройки сервера. [password] — null, если вход по паролю выключен (тогда только анонимный, если разрешён).
 * [tls] — null для FTP без шифрования; иначе явный FTPS (AUTH TLS), и вход без TLS не принимается (S6).
 */
class FtpServerConfig(
    val bind: InetAddress,
    val port: Int,
    val root: File,
    val user: String,
    val password: CharArray?,
    val anonymousReadOnly: Boolean,
    val tls: SSLContext?,
    val maxClients: Int = 8,
)

/**
 * Небольшой FTP-сервер для «Доступа с ПК» (F17): пассивный режим, UTF-8, MLSD/LIST, докачка (REST),
 * явный FTPS с защищённым каналом данных. Пути не выходят за [FtpServerConfig.root]: «..» и символические
 * ссылки наружу отклоняются. Слушает только адрес [FtpServerConfig.bind] (Wi-Fi или точка доступа, F17.2).
 */
class FtpServer(private val config: FtpServerConfig, private val onChange: () -> Unit = {}) : Closeable {

    private val listener = ServerSocket()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "qf-ftp").apply { isDaemon = true } }
    private val sessions = ConcurrentHashMap<Int, Session>()
    private val ids = AtomicInteger()
    private val rootPath: String = config.root.canonicalPath
    @Volatile private var closed = false

    /** Время последнего действия клиента — для автоостановки (F17.3). */
    val lastActivity = AtomicLong(System.currentTimeMillis())

    val port: Int get() = listener.localPort

    val clients: List<FtpClientInfo> get() = sessions.values.map { it.info() }.sortedBy { it.connectedAt }

    fun start() {
        listener.reuseAddress = true
        listener.bind(InetSocketAddress(config.bind, config.port), 16)
        pool.execute {
            while (!closed) {
                val socket = try {
                    listener.accept()
                } catch (e: IOException) {
                    break
                }
                if (sessions.size >= config.maxClients) {
                    runCatching { socket.getOutputStream().write("421 Too many connections\r\n".toByteArray()); socket.close() }
                    continue
                }
                val session = Session(ids.incrementAndGet(), socket)
                sessions[session.id] = session
                touch()
                onChange()
                pool.execute { session.run() }
            }
        }
    }

    /** «Отключить» в списке клиентов (F17.3). */
    fun disconnect(id: Int) {
        sessions[id]?.close()
    }

    override fun close() {
        closed = true
        runCatching { listener.close() }
        sessions.values.forEach { it.close() }
        pool.shutdownNow()
    }

    private fun touch() = lastActivity.set(System.currentTimeMillis())

    private inner class Session(val id: Int, socket: Socket) {
        private var control: Socket = socket
        private var reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        private var writer: OutputStream = socket.getOutputStream()
        private val connectedAt = System.currentTimeMillis()
        private val address = socket.inetAddress.hostAddress.orEmpty()

        @Volatile private var secure = false
        private var protectData = false
        private var pendingUser: String? = null
        @Volatile private var user: String? = null
        private var readOnly = true
        private var cwd = "/"
        private var restart = 0L
        private var renameFrom: File? = null
        private var passive: ServerSocket? = null
        private var failedLogins = 0

        fun info() = FtpClientInfo(id, address, user, connectedAt, secure)

        fun run() {
            try {
                control.soTimeout = CONTROL_TIMEOUT_MS
                reply(220, "Quiet Files ready")
                while (!closed) {
                    val line = try {
                        reader.readLine()
                    } catch (e: SocketTimeoutException) {
                        reply(421, "Idle timeout")
                        break
                    } ?: break
                    touch()
                    val space = line.indexOf(' ')
                    val command = (if (space < 0) line else line.substring(0, space)).uppercase(Locale.ROOT)
                    val argument = if (space < 0) "" else line.substring(space + 1)
                    if (!handle(command, argument)) break
                }
            } catch (_: IOException) {
            } finally {
                close()
                sessions.remove(id)
                onChange()
            }
        }

        fun close() {
            runCatching { passive?.close() }
            runCatching { control.close() }
        }

        private fun reply(code: Int, text: String) {
            writer.write("$code $text\r\n".toByteArray(Charsets.UTF_8))
            writer.flush()
        }

        private fun replyLines(code: Int, lines: List<String>, last: String) {
            val sb = StringBuilder("$code-${lines.firstOrNull() ?: last}\r\n")
            lines.drop(1).forEach { sb.append(' ').append(it).append("\r\n") }
            sb.append("$code $last\r\n")
            writer.write(sb.toString().toByteArray(Charsets.UTF_8))
            writer.flush()
        }

        /** false — закрыть соединение. */
        private fun handle(command: String, arg: String): Boolean {
            when (command) {
                "AUTH" -> {
                    val tls = config.tls
                    if (tls == null || (arg.uppercase() != "TLS" && arg.uppercase() != "SSL")) return reply(504, "TLS not available").let { true }
                    if (secure) return reply(503, "Already secure").let { true }
                    reply(234, "Proceed with TLS")
                    val ssl = tls.socketFactory.createSocket(control, address, control.port, true) as SSLSocket
                    ssl.useClientMode = false
                    ssl.startHandshake()
                    control = ssl
                    reader = BufferedReader(InputStreamReader(ssl.inputStream, Charsets.UTF_8))
                    writer = ssl.outputStream
                    secure = true
                    onChange()
                }
                "PBSZ" -> if (secure) reply(200, "PBSZ=0") else reply(503, "AUTH first")
                "PROT" -> when {
                    !secure -> reply(503, "AUTH first")
                    arg.uppercase() == "P" -> { protectData = true; reply(200, "Private") }
                    arg.uppercase() == "C" -> if (config.tls != null) reply(534, "Private data connection required") else { protectData = false; reply(200, "Clear") }
                    else -> reply(504, "Unknown level")
                }
                "USER" -> {
                    if (config.tls != null && !secure && !isAnonymous(arg)) return reply(530, "TLS required (AUTH TLS)").let { true }
                    pendingUser = arg
                    user = null
                    reply(331, "Password required")
                }
                "PASS" -> return login(arg)
                "QUIT" -> {
                    reply(221, "Bye")
                    return false
                }
                "NOOP" -> reply(200, "OK")
                "SYST" -> reply(215, "UNIX Type: L8")
                "FEAT" -> replyLines(211, listOfNotNull("Features:", "UTF8", "MLST type*;size*;modify*;", "MLSD", "SIZE", "MDTM", "REST STREAM", "EPSV", "PASV",
                    if (config.tls != null) "AUTH TLS" else null, if (config.tls != null) "PBSZ" else null, if (config.tls != null) "PROT" else null), "End")
                "OPTS" -> if (arg.uppercase().startsWith("UTF8")) reply(200, "UTF8 on") else reply(501, "Unknown option")
                else -> {
                    if (user == null) return reply(530, "Not logged in").let { true }
                    if (config.tls != null && !protectData && command in DATA_COMMANDS) return reply(521, "PROT P required").let { true }
                    authorized(command, arg)
                }
            }
            return true
        }

        private fun isAnonymous(name: String) = config.anonymousReadOnly && (name.equals("anonymous", true) || name.equals("ftp", true))

        private fun login(password: String): Boolean {
            val name = pendingUser ?: return reply(503, "USER first").let { true }
            val expected = config.password
            val ok = when {
                isAnonymous(name) -> {
                    readOnly = true
                    true
                }
                expected != null && name == config.user && constantTimeEquals(password, expected) -> {
                    readOnly = false
                    true
                }
                else -> false
            }
            if (!ok) {
                failedLogins++
                Thread.sleep(LOGIN_DELAY_MS * failedLogins)
                reply(530, "Login incorrect")
                return failedLogins < MAX_FAILED_LOGINS
            }
            user = name
            cwd = "/"
            onChange()
            reply(230, "Logged in")
            return true
        }

        private fun authorized(command: String, arg: String) {
            when (command) {
                "PWD", "XPWD" -> reply(257, "\"${cwd.replace("\"", "\"\"")}\" is current directory")
                "CWD", "XCWD" -> {
                    val target = virtual(arg)
                    val file = resolve(target)
                    if (file?.isDirectory == true) {
                        cwd = target
                        reply(250, "OK")
                    } else {
                        reply(550, "No such directory")
                    }
                }
                "CDUP", "XCUP" -> {
                    cwd = virtual("..")
                    reply(250, "OK")
                }
                "TYPE" -> reply(200, "Type set")
                "MODE" -> if (arg.uppercase() == "S") reply(200, "OK") else reply(504, "Only stream mode")
                "STRU" -> if (arg.uppercase() == "F") reply(200, "OK") else reply(504, "Only file structure")
                "PASV" -> openPassive(extended = false)
                "EPSV" -> openPassive(extended = true)
                "PORT", "EPRT" -> reply(502, "Active mode is not supported, use passive")
                "REST" -> {
                    restart = arg.toLongOrNull()?.takeIf { it >= 0 } ?: return reply(501, "Bad offset")
                    reply(350, "Restarting at $restart")
                }
                "SIZE" -> resolve(virtual(arg))?.takeIf { it.isFile }?.let { reply(213, it.length().toString()) } ?: reply(550, "No such file")
                "MDTM" -> resolve(virtual(arg))?.takeIf { it.exists() }?.let { reply(213, stamp(it.lastModified())) } ?: reply(550, "No such file")
                "MLST" -> {
                    val target = virtual(arg.ifBlank { "." })
                    val file = resolve(target)?.takeIf { it.exists() } ?: return reply(550, "No such file")
                    replyLines(250, listOf("Listing $target", fact(file, target)), "End")
                }
                "LIST", "NLST", "MLSD" -> list(command, arg)
                "RETR" -> retrieve(arg)
                "STOR", "APPE" -> store(arg, append = command == "APPE")
                "DELE" -> write { file -> if (file.isFile && file.delete()) reply(250, "Deleted") else reply(550, "Cannot delete") }(arg)
                "RMD", "XRMD" -> write { file -> if (file.isDirectory && file.delete()) reply(250, "Removed") else reply(550, "Cannot remove") }(arg)
                "MKD", "XMKD" -> {
                    if (readOnly) return reply(550, "Read-only access")
                    val target = virtual(arg)
                    val file = resolveNew(target) ?: return reply(550, "Bad path")
                    if (file.mkdir()) reply(257, "\"$target\" created") else reply(550, "Cannot create")
                }
                "RNFR" -> {
                    if (readOnly) return reply(550, "Read-only access")
                    renameFrom = resolve(virtual(arg))?.takeIf { it.exists() && it.canonicalPath != rootPath } ?: return reply(550, "No such file")
                    reply(350, "Ready for RNTO")
                }
                "RNTO" -> {
                    val from = renameFrom ?: return reply(503, "RNFR first")
                    renameFrom = null
                    val to = resolveNew(virtual(arg)) ?: return reply(550, "Bad path")
                    if (!to.exists() && from.renameTo(to)) reply(250, "Renamed") else reply(550, "Cannot rename")
                }
                "ABOR" -> reply(226, "Nothing to abort")
                "STAT" -> reply(211, "Quiet Files FTP")
                else -> reply(502, "Command not implemented")
            }
        }

        private fun write(block: (File) -> Unit): (String) -> Unit = { arg ->
            if (readOnly) {
                reply(550, "Read-only access")
            } else {
                val file = resolve(virtual(arg))
                if (file == null || file.canonicalPath == rootPath) reply(550, "No such file") else block(file)
            }
        }

        private fun openPassive(extended: Boolean) {
            runCatching { passive?.close() }
            val socket = ServerSocket()
            socket.bind(InetSocketAddress(config.bind, 0), 1)
            socket.soTimeout = DATA_ACCEPT_TIMEOUT_MS
            passive = socket
            if (extended) {
                reply(229, "Entering Extended Passive Mode (|||${socket.localPort}|)")
            } else {
                val ip = config.bind.hostAddress.orEmpty().replace('.', ',')
                reply(227, "Entering Passive Mode ($ip,${socket.localPort shr 8},${socket.localPort and 0xFF})")
            }
        }

        /** Принимает соединение данных; при FTPS — оборачивает его в TLS (PROT P). */
        private fun acceptData(): Socket? {
            val server = passive ?: return null.also { reply(425, "Use PASV first") }
            passive = null
            return try {
                val raw = server.use { it.accept() }
                if (raw.inetAddress != control.inetAddress) {
                    raw.close()
                    reply(425, "Data connection from another address")
                    return null
                }
                raw.soTimeout = CONTROL_TIMEOUT_MS
                val tls = config.tls
                if (protectData && tls != null) {
                    (tls.socketFactory.createSocket(raw, address, raw.port, true) as SSLSocket).apply {
                        useClientMode = false
                        startHandshake()
                    }
                } else {
                    raw
                }
            } catch (e: IOException) {
                reply(425, "Cannot open data connection")
                null
            }
        }

        private fun list(command: String, arg: String) {
            val path = arg.split(' ').lastOrNull { !it.startsWith("-") }.orEmpty()
            val target = virtual(path.ifBlank { "." })
            val dir = resolve(target)
            if (dir == null || !dir.exists()) return reply(550, "No such directory")
            reply(150, "Listing")
            val data = acceptData() ?: return
            try {
                data.use { socket ->
                    val out = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
                    val files = if (dir.isDirectory) dir.listFiles().orEmpty().filter { inside(it) }.sortedBy { it.name } else listOf(dir)
                    for (f in files) {
                        val line = when (command) {
                            "NLST" -> f.name
                            "MLSD" -> fact(f, f.name)
                            else -> unixLine(f)
                        }
                        out.write(line)
                        out.write("\r\n")
                    }
                    out.flush()
                }
                reply(226, "Done")
            } catch (e: IOException) {
                reply(426, "Transfer aborted")
            }
        }

        private fun retrieve(arg: String) {
            val file = resolve(virtual(arg))?.takeIf { it.isFile } ?: return reply(550, "No such file")
            val offset = restart.also { restart = 0 }
            reply(150, "Sending ${file.name}")
            val data = acceptData() ?: return
            try {
                data.use { socket ->
                    RandomAccessFile(file, "r").use { raf ->
                        raf.seek(offset.coerceAtMost(raf.length()))
                        copy(FileStream(raf), socket.getOutputStream())
                    }
                }
                reply(226, "Done")
            } catch (e: IOException) {
                reply(426, "Transfer aborted")
            }
        }

        private fun store(arg: String, append: Boolean) {
            if (readOnly) return reply(550, "Read-only access")
            val file = resolveNew(virtual(arg)) ?: return reply(550, "Bad path")
            if (file.isDirectory) return reply(550, "Is a directory")
            val offset = restart.also { restart = 0 }
            reply(150, "Receiving ${file.name}")
            val data = acceptData() ?: return
            try {
                data.use { socket ->
                    RandomAccessFile(file, "rw").use { raf ->
                        when {
                            append -> raf.seek(raf.length())
                            offset > 0 -> raf.seek(offset)
                            else -> raf.setLength(0)
                        }
                        val input = socket.getInputStream()
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            raf.write(buffer, 0, n)
                            touch()
                        }
                    }
                }
                reply(226, "Done")
            } catch (e: IOException) {
                reply(426, "Transfer aborted")
            }
        }

        private fun copy(input: InputStream, output: OutputStream) {
            val buffer = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                output.write(buffer, 0, n)
                touch()
            }
            output.flush()
        }

        /** Виртуальный путь от корня сервера, без «..» за пределы корня. */
        private fun virtual(arg: String): String {
            val raw = if (arg.startsWith("/")) arg else "$cwd/$arg"
            val parts = ArrayDeque<String>()
            for (part in raw.split('/')) {
                when (part) {
                    "", "." -> Unit
                    ".." -> parts.removeLastOrNull()
                    else -> parts.addLast(part)
                }
            }
            return "/" + parts.joinToString("/")
        }

        /** Существующий файл внутри корня (с учётом символических ссылок) или null. */
        private fun resolve(virtualPath: String): File? {
            val file = File(config.root, virtualPath.trimStart('/'))
            return file.takeIf { inside(it) }
        }

        /** Путь для нового файла: родитель должен существовать и лежать внутри корня. */
        private fun resolveNew(virtualPath: String): File? {
            if (virtualPath == "/") return null
            val file = File(config.root, virtualPath.trimStart('/'))
            val parent = file.parentFile ?: return null
            if (!parent.isDirectory || !inside(parent)) return null
            if (file.exists() && !inside(file)) return null
            return file
        }

        private fun inside(file: File): Boolean = try {
            val canonical = file.canonicalPath
            canonical == rootPath || canonical.startsWith(rootPath + File.separator)
        } catch (_: IOException) {
            false
        }

        private fun fact(file: File, name: String): String {
            val type = if (file.isDirectory) "dir" else "file"
            val size = if (file.isFile) "size=${file.length()};" else ""
            return "type=$type;${size}modify=${stamp(file.lastModified())}; $name"
        }

        private fun unixLine(file: File): String {
            val perms = if (file.isDirectory) "drwxr-xr-x" else "-rw-r--r--"
            val date = SimpleDateFormat("MMM dd HH:mm", Locale.US).format(Date(file.lastModified()))
            return "%s 1 user group %13d %s %s".format(Locale.US, perms, if (file.isFile) file.length() else 0L, date, file.name)
        }
    }

    private class FileStream(private val raf: RandomAccessFile) : InputStream() {
        override fun read(): Int = raf.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = raf.read(b, off, len)
    }

    companion object {
        private const val BUFFER = 64 * 1024
        private const val CONTROL_TIMEOUT_MS = 10 * 60_000
        private const val DATA_ACCEPT_TIMEOUT_MS = 30_000
        private const val LOGIN_DELAY_MS = 500L
        private const val MAX_FAILED_LOGINS = 3
        private val DATA_COMMANDS = setOf("LIST", "NLST", "MLSD", "RETR", "STOR", "APPE")

        fun stamp(time: Long): String = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(time))

        /** Сравнение пароля за постоянное время. */
        fun constantTimeEquals(given: String, expected: CharArray): Boolean =
            MessageDigest.isEqual(given.toByteArray(Charsets.UTF_8), String(expected).toByteArray(Charsets.UTF_8))
    }
}

