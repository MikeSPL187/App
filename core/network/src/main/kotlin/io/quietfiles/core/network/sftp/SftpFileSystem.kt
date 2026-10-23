package io.quietfiles.core.network.sftp

import io.quietfiles.core.model.network.NetworkPaths
import io.quietfiles.core.network.RemoteEntry
import io.quietfiles.core.network.RemoteFileSystem
import io.quietfiles.core.network.RemoteRefusal
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.util.EnumSet

internal class SftpFileSystem(
    private val ssh: SSHClient,
    private val sftp: SFTPClient,
    configuredPath: String,
) : RemoteFileSystem {

    override val startPath: String = configuredPath.takeIf { it.isNotBlank() }?.let(NetworkPaths::normalize)
        ?: runCatching { NetworkPaths.normalize(sftp.canonicalize(".")) }.getOrDefault("/")

    override fun list(path: String): List<RemoteEntry> = mapped(path) {
        sftp.ls(path).filter { it.name != "." && it.name != ".." }.map { info ->
            entry(NetworkPaths.normalize("$path/${info.name}"), info.name, resolve(path, info.name, info.attributes))
        }
    }

    /** Ссылки показываем тем, на что они указывают; битая ссылка — как файл. */
    private fun resolve(dir: String, name: String, attributes: FileAttributes): FileAttributes =
        if (attributes.type == FileMode.Type.SYMLINK) runCatching { sftp.stat("$dir/$name") }.getOrDefault(attributes) else attributes

    override fun stat(path: String): RemoteEntry? = mapped(path) {
        val attributes = sftp.statExistence(path) ?: return@mapped null
        entry(path, path.substringAfterLast('/').ifEmpty { "/" }, attributes)
    }

    override fun mkdir(path: String) = mapped(path) { sftp.mkdir(path) }

    override fun createFile(path: String) = mapped(path) {
        sftp.open(path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL)).close()
    }

    override fun rename(from: String, to: String) = mapped(from) { sftp.rename(from, to) }

    override fun delete(path: String, isDirectory: Boolean) = mapped(path) {
        if (isDirectory) sftp.rmdir(path) else sftp.rm(path)
    }

    override fun openRead(path: String, offset: Long): InputStream = mapped(path) {
        val file = sftp.open(path, EnumSet.of(OpenMode.READ))
        val stream = file.ReadAheadRemoteFileInputStream(READ_AHEAD, offset)
        object : InputStream() {
            override fun read(): Int = stream.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = stream.read(b, off, len)
            override fun skip(n: Long): Long = stream.skip(n)
            override fun close() {
                try {
                    stream.close()
                } finally {
                    file.close()
                }
            }
        }
    }

    override fun openWrite(path: String): OutputStream = mapped(path) {
        val file: RemoteFile = sftp.open(path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC))
        val stream = file.RemoteFileOutputStream(0, WRITE_AHEAD)
        object : OutputStream() {
            override fun write(b: Int) = stream.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = stream.write(b, off, len)
            override fun flush() = stream.flush()
            override fun close() {
                try {
                    stream.close()
                } finally {
                    file.close()
                }
            }
        }
    }

    override fun setLastModified(path: String, time: Long) {
        val seconds = time / 1000
        runCatching { sftp.setattr(path, FileAttributes.Builder().withAtimeMtime(seconds, seconds).build()) }
    }

    override val isAlive: Boolean get() = ssh.isConnected && ssh.isAuthenticated

    override fun close() {
        runCatching { sftp.close() }
        runCatching { ssh.disconnect() }
    }

    private fun entry(path: String, name: String, a: FileAttributes) = RemoteEntry(
        path = path,
        name = name,
        isDirectory = a.type == FileMode.Type.DIRECTORY,
        size = a.size,
        lastModified = a.mtime * 1000,
    )

    private inline fun <T> mapped(path: String, block: () -> T): T = try {
        block()
    } catch (e: SFTPException) {
        throw when (e.statusCode) {
            Response.StatusCode.NO_SUCH_FILE, Response.StatusCode.NO_SUCH_PATH -> FileNotFoundException(path)
            Response.StatusCode.FILE_ALREADY_EXISTS -> FileAlreadyExistsException(path)
            Response.StatusCode.PERMISSION_DENIED -> SecurityException(path)
            // Сервер ответил отказом, связь при этом жива.
            Response.StatusCode.FAILURE, Response.StatusCode.OP_UNSUPPORTED, Response.StatusCode.DIR_NOT_EMPTY,
            Response.StatusCode.WRITE_PROTECT, Response.StatusCode.NO_SPACE_ON_FILESYSTEM, Response.StatusCode.QUOTA_EXCEEDED,
            -> RemoteRefusal("${e.statusCode}: ${e.message}")
            else -> IOException(e.message, e)
        }
    }

    private companion object {
        const val READ_AHEAD = 16
        const val WRITE_AHEAD = 16
    }
}
