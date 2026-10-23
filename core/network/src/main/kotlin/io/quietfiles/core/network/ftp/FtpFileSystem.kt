package io.quietfiles.core.network.ftp

import io.quietfiles.core.model.network.NetworkPaths
import io.quietfiles.core.network.RemoteEntry
import io.quietfiles.core.network.RemoteFileSystem
import io.quietfiles.core.network.RemoteRefusal
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal class FtpFileSystem(
    private val control: FTPClient,
    configuredPath: String,
    private val newClient: () -> FTPClient,
) : RemoteFileSystem {

    private val lock = Any()
    private val mlst: Boolean = synchronized(lock) { runCatching { control.hasFeature("MLST") }.getOrDefault(false) }

    override val startPath: String = configuredPath.takeIf { it.isNotBlank() }?.let(NetworkPaths::normalize)
        ?: synchronized(lock) { runCatching { control.printWorkingDirectory()?.let(NetworkPaths::normalize) }.getOrNull() } ?: "/"

    override fun list(path: String): List<RemoteEntry> = synchronized(lock) {
        val files = if (mlst) control.mlistDir(path) else control.listFiles(path)
        if (files == null || (!FTPReply.isPositiveCompletion(control.replyCode) && files.isEmpty())) {
            if (control.replyCode == 550 || control.replyCode == 450) throw FileNotFoundException(path)
            checkReply(path)
        }
        files.orEmpty()
            .filter { it != null && it.name != "." && it.name != ".." && it.name.isNotEmpty() }
            .map { file ->
                val name = file.name.substringAfterLast('/')
                entryOf(NetworkPaths.normalize("$path/$name"), name, file)
            }
    }

    override fun stat(path: String): RemoteEntry? = synchronized(lock) {
        if (path == "/") return@synchronized RemoteEntry("/", "/", true, 0, 0)
        val name = path.substringAfterLast('/')
        if (mlst) {
            val file = control.mlistFile(path)
            if (file != null) return@synchronized entryOf(path, name, file)
            if (control.replyCode == 550 || control.replyCode == 501) return@synchronized null
        }
        val parent = path.substringBeforeLast('/').ifEmpty { "/" }
        val siblings = control.listFiles(parent) ?: return@synchronized null
        siblings.firstOrNull { it != null && it.name.substringAfterLast('/') == name }?.let { entryOf(path, name, it) }
    }

    override fun mkdir(path: String) = synchronized(lock) {
        if (!control.makeDirectory(path)) refuse(path)
    }

    override fun createFile(path: String) = synchronized(lock) {
        if (!control.storeFile(path, java.io.ByteArrayInputStream(ByteArray(0)))) refuse(path)
    }

    override fun rename(from: String, to: String) = synchronized(lock) {
        if (!control.rename(from, to)) refuse(from)
    }

    override fun delete(path: String, isDirectory: Boolean) = synchronized(lock) {
        val ok = if (isDirectory) control.removeDirectory(path) else control.deleteFile(path)
        if (!ok) refuse(path)
    }

    override fun openRead(path: String, offset: Long): InputStream {
        val client = newClient()
        try {
            if (offset > 0) client.restartOffset = offset
            val input = client.retrieveFileStream(path) ?: run {
                val code = client.replyCode
                throw if (code == 550 || code == 450) FileNotFoundException(path) else RemoteRefusal(client.replyString.trim())
            }
            return object : FilterInputStream(input) {
                override fun close() = finishTransfer(client) { super.close() }
            }
        } catch (e: IOException) {
            runCatching { client.disconnect() }
            throw e
        }
    }

    override fun openWrite(path: String): OutputStream {
        val client = newClient()
        try {
            val output = client.storeFileStream(path) ?: throw RemoteRefusal(client.replyString.trim())
            return object : FilterOutputStream(output) {
                override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
                override fun close() = finishTransfer(client, strict = true) { out.close() }
            }
        } catch (e: IOException) {
            runCatching { client.disconnect() }
            throw e
        }
    }

    /** Закрывает поток передачи и дожидается ответа сервера; для записи неудача — ошибка. */
    private inline fun finishTransfer(client: FTPClient, strict: Boolean = false, closeStream: () -> Unit) {
        try {
            closeStream()
            val ok = client.completePendingCommand()
            if (strict && !ok) throw RemoteRefusal(client.replyString.trim())
        } finally {
            runCatching { client.logout() }
            runCatching { client.disconnect() }
        }
    }

    override fun setLastModified(path: String, time: Long) {
        synchronized(lock) {
            val stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(time))
            runCatching { control.setModificationTime(path, stamp) }
        }
    }

    override val isAlive: Boolean
        get() = synchronized(lock) { control.isConnected && runCatching { control.sendNoOp() }.getOrDefault(false) }

    override fun close() {
        synchronized(lock) {
            runCatching { control.logout() }
            runCatching { control.disconnect() }
        }
    }

    private fun entryOf(path: String, name: String, file: FTPFile) = RemoteEntry(
        path = path,
        name = name,
        isDirectory = file.isDirectory,
        size = file.size.coerceAtLeast(0),
        lastModified = file.timestamp?.timeInMillis ?: 0,
    )

    private fun checkReply(path: String) {
        val code = control.replyCode
        if (FTPReply.isPositiveCompletion(code) || FTPReply.isPositivePreliminary(code)) return
        if (code == 421 || code == 0) throw IOException("FTP connection closed")
        refuse(path)
    }

    private fun refuse(path: String): Nothing {
        val code = control.replyCode
        val text = control.replyString?.trim().orEmpty()
        throw when {
            code == 421 -> IOException("FTP connection closed: $text")
            code == 550 && text.contains("exist", ignoreCase = true) && !text.contains("not exist", ignoreCase = true) &&
                !text.contains("doesn't exist", ignoreCase = true) -> FileAlreadyExistsException(path)
            code == 550 && (text.contains("no such", ignoreCase = true) || text.contains("not found", ignoreCase = true) ||
                text.contains("not exist", ignoreCase = true) || text.contains("doesn't exist", ignoreCase = true)) -> FileNotFoundException(path)
            code == 550 && text.contains("permission", ignoreCase = true) -> SecurityException(text)
            code == 530 || code == 532 -> SecurityException(text)
            else -> RemoteRefusal(text.ifEmpty { "FTP $code" })
        }
    }
}
