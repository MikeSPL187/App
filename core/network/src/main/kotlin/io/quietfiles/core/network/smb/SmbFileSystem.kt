package io.quietfiles.core.network.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msdtyp.FileTime
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ImpersonationLevel
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.PipeShare
import io.quietfiles.core.model.StorageSpace
import io.quietfiles.core.model.network.NetworkPaths
import io.quietfiles.core.network.RemoteEntry
import io.quietfiles.core.network.RemoteFileSystem
import io.quietfiles.core.network.RemoteRefusal
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap

/**
 * Путь на сервере SMB: первая часть — общий ресурс, дальше — путь внутри него: `/Public/Фильмы/a.mkv`.
 * Корень `/` — список общих ресурсов (через SRVSVC).
 */
internal class SmbFileSystem(
    private val client: SMBClient,
    private val connection: Connection,
    private val session: Session,
    private val host: String,
    configuredPath: String,
) : RemoteFileSystem {

    private val shares = ConcurrentHashMap<String, DiskShare>()

    override val startPath: String = configuredPath.takeIf { it.isNotBlank() }?.let(NetworkPaths::normalize) ?: "/"

    private data class Location(val share: String, val inner: String)

    private fun locate(path: String): Location? {
        val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        return Location(parts[0], parts.drop(1).joinToString("\\"))
    }

    private fun share(name: String): DiskShare = shares[name]?.takeIf { it.isConnected } ?: mapped("/$name") {
        (session.connectShare(name) as? DiskShare ?: throw FileNotFoundException(name)).also { shares[name] = it }
    }

    override fun list(path: String): List<RemoteEntry> {
        val location = locate(path) ?: return listShares()
        val share = share(location.share)
        return mapped(path) {
            share.list(location.inner).filter { it.fileName != "." && it.fileName != ".." }.map { info ->
                val directory = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                RemoteEntry(
                    path = NetworkPaths.normalize("$path/${info.fileName}"),
                    name = info.fileName,
                    isDirectory = directory,
                    size = if (directory) 0 else info.endOfFile,
                    lastModified = info.lastWriteTime.toEpochMillis(),
                )
            }
        }
    }

    private fun listShares(): List<RemoteEntry> = mapped("/") {
        val pipeShare = session.connectShare("IPC$") as PipeShare
        try {
            val pipe = pipeShare.open(
                "srvsvc", SMB2ImpersonationLevel.Impersonation, EnumSet.of(AccessMask.MAXIMUM_ALLOWED), null,
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE), SMB2CreateDisposition.FILE_OPEN, null,
            )
            pipe.use {
                SrvSvc.checkBindAck(pipe.transact(SrvSvc.bind()))
                val first = pipe.transact(SrvSvc.shareEnumRequest(host))
                val stub = SrvSvc.collectResponse(first) {
                    val buffer = ByteArray(SrvSvc.MAX_FRAG)
                    val n = pipe.read(buffer)
                    if (n <= 0) ByteArray(0) else buffer.copyOf(n)
                }
                SrvSvc.parseShares(stub).filter { it.isDisk }.map { RemoteEntry("/${it.name}", it.name, true, 0, 0) }
            }
        } finally {
            runCatching { pipeShare.close() }
        }
    }

    override fun stat(path: String): RemoteEntry? {
        val location = locate(path) ?: return RemoteEntry("/", "/", true, 0, 0)
        if (location.inner.isEmpty()) {
            return runCatching { share(location.share) }.getOrNull()?.let { RemoteEntry(path, location.share, true, 0, 0) }
        }
        val share = share(location.share)
        return try {
            val info = share.getFileInformation(location.inner)
            RemoteEntry(
                path = path,
                name = path.substringAfterLast('/'),
                isDirectory = info.standardInformation.isDirectory,
                size = info.standardInformation.endOfFile,
                lastModified = info.basicInformation.lastWriteTime.toEpochMillis(),
            )
        } catch (e: SMBApiException) {
            if (e.status in NOT_FOUND) null else throw convert(e, path)
        }
    }

    override fun mkdir(path: String) {
        val location = locate(path) ?: throw SecurityException(path)
        if (location.inner.isEmpty()) throw SecurityException("Cannot create share")
        mapped(path) { share(location.share).mkdir(location.inner) }
    }

    override fun createFile(path: String) {
        val location = inner(path)
        mapped(path) {
            share(location.share).openFile(
                location.inner, EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_CREATE, null,
            ).close()
        }
    }

    override fun canRename(from: String, to: String): Boolean = locate(from)?.share == locate(to)?.share

    override fun rename(from: String, to: String) {
        val source = inner(from)
        val target = inner(to)
        if (source.share != target.share) throw RemoteRefusal("Rename between shares")
        mapped(from) {
            share(source.share).open(
                source.inner, EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN, null,
            ).use { it.rename(target.inner, false) }
        }
    }

    override fun delete(path: String, isDirectory: Boolean) {
        val location = inner(path)
        mapped(path) {
            if (isDirectory) share(location.share).rmdir(location.inner, false) else share(location.share).rm(location.inner)
        }
    }

    override fun openRead(path: String, offset: Long): InputStream {
        val location = inner(path)
        val file = mapped(path) {
            share(location.share).openFile(
                location.inner, EnumSet.of(AccessMask.GENERIC_READ), null, EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
            )
        }
        return object : InputStream() {
            private var position = offset
            private val one = ByteArray(1)
            override fun read(): Int = if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                val n = mapped(path) { file.read(b, position, off, minOf(len, CHUNK)) }
                if (n <= 0) return -1
                position += n
                return n
            }
            override fun skip(n: Long): Long {
                if (n <= 0) return 0
                position += n
                return n
            }
            override fun close() = file.close()
        }
    }

    override fun openWrite(path: String): OutputStream {
        val location = inner(path)
        val file = mapped(path) {
            share(location.share).openFile(
                location.inner, EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
            )
        }
        return object : OutputStream() {
            private var position = 0L
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                var written = 0
                while (written < len) {
                    val n = mapped(path) { file.write(b, position, off + written, len - written) }.toInt()
                    if (n <= 0) throw IOException("SMB write failed")
                    written += n
                    position += n
                }
            }
            override fun close() = file.close()
        }
    }

    override fun setLastModified(path: String, time: Long) {
        val location = locate(path)?.takeIf { it.inner.isNotEmpty() } ?: return
        runCatching {
            val share = share(location.share)
            val current = share.getFileInformation(location.inner).basicInformation
            val stamp = FileTime.ofEpochMillis(time)
            share.setFileInformation(
                location.inner,
                FileBasicInformation(current.creationTime, current.lastAccessTime, stamp, current.changeTime, current.fileAttributes),
            )
        }
    }

    override fun space(path: String): StorageSpace? {
        val location = locate(path) ?: return null
        return runCatching {
            val info = share(location.share).shareInformation
            StorageSpace(total = info.totalSpace, free = info.callerFreeSpace)
        }.getOrNull()
    }

    override val isAlive: Boolean get() = connection.isConnected

    override fun close() {
        shares.values.forEach { runCatching { it.close() } }
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }
    }

    private fun inner(path: String): Location {
        val location = locate(path) ?: throw SecurityException(path)
        if (location.inner.isEmpty()) throw SecurityException("Share root: $path")
        return location
    }

    private inline fun <T> mapped(path: String, block: () -> T): T = try {
        block()
    } catch (e: SMBApiException) {
        throw convert(e, path)
    }

    private fun convert(e: SMBApiException, path: String): Exception = when (e.status) {
        in NOT_FOUND -> FileNotFoundException(path)
        NtStatus.STATUS_OBJECT_NAME_COLLISION -> FileAlreadyExistsException(path)
        NtStatus.STATUS_ACCESS_DENIED -> SecurityException(path)
        NtStatus.STATUS_DISK_FULL, NtStatus.STATUS_SHARING_VIOLATION, NtStatus.STATUS_DIRECTORY_NOT_EMPTY,
        NtStatus.STATUS_CANNOT_DELETE, NtStatus.STATUS_DELETE_PENDING, NtStatus.STATUS_FILE_IS_A_DIRECTORY,
        NtStatus.STATUS_NOT_A_DIRECTORY, NtStatus.STATUS_NOT_SUPPORTED, NtStatus.STATUS_OBJECT_NAME_INVALID,
        -> RemoteRefusal(e.status.name)
        else -> IOException(e.status.name, e)
    }

    private companion object {
        const val CHUNK = 64 * 1024
        val NOT_FOUND = setOf(
            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND, NtStatus.STATUS_NO_SUCH_FILE,
            NtStatus.STATUS_BAD_NETWORK_NAME, NtStatus.STATUS_NOT_FOUND,
        )
    }
}
