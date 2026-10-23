package io.quietfiles.core.network

import io.quietfiles.core.common.IoDispatcher
import io.quietfiles.core.model.FileEntry
import io.quietfiles.core.model.FileNames
import io.quietfiles.core.model.StorageSpace
import io.quietfiles.core.model.network.Connection
import io.quietfiles.core.model.network.NetworkPaths
import io.quietfiles.core.storage.StorageException
import io.quietfiles.core.storage.StorageProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Файлы на серверах (F16) как обычное хранилище: пути `/qf-net/<id>/…` ([NetworkPaths]).
 * `/qf-net` — список подключений, чтобы их можно было выбрать как место для копирования.
 */
@Singleton
class NetworkStorageProvider @Inject constructor(
    private val sessions: RemoteSessions,
    private val connections: ConnectionList,
    @IoDispatcher private val io: CoroutineDispatcher,
) : StorageProvider {

    override suspend fun list(path: String, includeHidden: Boolean): List<FileEntry> = withContext(io) {
        if (path == NetworkPaths.ROOT) return@withContext connections.all().map(::rootEntry)
        val (id, remote) = parse(path)
        val entries = call(id, path) { it.list(remote) }
        entries.mapNotNull { entry ->
            coroutineContext.ensureActive()
            if (!includeHidden && entry.name.startsWith(".")) null else entry.toFileEntry(id)
        }
    }

    override suspend fun stat(path: String): FileEntry? = withContext(io) {
        if (path == NetworkPaths.ROOT) return@withContext FileEntry(path, "", isDirectory = true, size = 0, lastModified = 0)
        val (id, remote) = parse(path)
        if (remote == "/") {
            val connection = connections.get(id) ?: return@withContext null
            return@withContext rootEntry(connection)
        }
        call(id, path) { it.stat(remote) }?.toFileEntry(id)
    }

    override suspend fun exists(path: String): Boolean = stat(path) != null

    override suspend fun createDirectory(parent: String, name: String): FileEntry = withContext(io) {
        val (id, remote) = parse(child(parent, name))
        val target = NetworkPaths.pathOf(id, remote)
        if (call(id, target) { it.stat(remote) } != null) throw StorageException.AlreadyExists(target)
        call(id, target) { it.mkdir(remote) }
        call(id, target) { it.stat(remote) }?.toFileEntry(id) ?: FileEntry(target, FileNames.nameOf(target), true, 0, System.currentTimeMillis())
    }

    override suspend fun createFile(parent: String, name: String): FileEntry = withContext(io) {
        val (id, remote) = parse(child(parent, name))
        val target = NetworkPaths.pathOf(id, remote)
        if (call(id, target) { it.stat(remote) } != null) throw StorageException.AlreadyExists(target)
        call(id, target) { it.createFile(remote) }
        call(id, target) { it.stat(remote) }?.toFileEntry(id) ?: FileEntry(target, FileNames.nameOf(target), false, 0, System.currentTimeMillis())
    }

    override suspend fun rename(path: String, newName: String): FileEntry = withContext(io) {
        val (id, remote) = parse(path)
        if (remote == "/") throw StorageException.AccessDenied(path)
        val parent = FileNames.parentOf(path) ?: throw StorageException.AccessDenied(path)
        val (_, target) = parse(child(parent, newName))
        val targetPath = NetworkPaths.pathOf(id, target)
        if (target == remote) return@withContext call(id, path) { it.stat(remote) }?.toFileEntry(id) ?: throw StorageException.NotFound(path)
        val caseOnly = target.equals(remote, ignoreCase = true)
        if (!caseOnly && call(id, targetPath) { it.stat(target) } != null) throw StorageException.AlreadyExists(targetPath)
        call(id, path) { it.rename(remote, target) }
        call(id, targetPath) { it.stat(target) }?.toFileEntry(id) ?: throw StorageException.NotFound(targetPath)
    }

    override suspend fun moveFast(from: String, to: String): Boolean = withContext(io) {
        val source = NetworkPaths.parse(from) ?: return@withContext false
        val target = NetworkPaths.parse(to) ?: return@withContext false
        if (source.first != target.first) return@withContext false
        val id = source.first
        if (call(id, from) { it.stat(source.second) } == null) throw StorageException.NotFound(from)
        if (call(id, to) { it.stat(target.second) } != null) throw StorageException.AlreadyExists(to)
        if (!call(id, from) { it.canRename(source.second, target.second) }) return@withContext false
        call(id, from) { it.rename(source.second, target.second) }
        true
    }

    override suspend fun delete(path: String) = withContext(io) {
        val (id, remote) = parse(path)
        if (remote == "/") throw StorageException.AccessDenied(path)
        val entry = call(id, path) { it.stat(remote) } ?: return@withContext
        deleteRecursively(id, entry)
    }

    private suspend fun deleteRecursively(id: Long, entry: RemoteEntry) {
        coroutineContext.ensureActive()
        val appPath = NetworkPaths.pathOf(id, entry.path)
        if (entry.isDirectory) {
            call(id, appPath) { it.list(entry.path) }.forEach { deleteRecursively(id, it) }
        }
        call(id, appPath) { it.delete(entry.path, entry.isDirectory) }
    }

    override suspend fun space(path: String): StorageSpace? = withContext(io) {
        val (id, remote) = NetworkPaths.parse(path) ?: return@withContext null
        runCatching { sessions.use(id) { it.space(remote) } }.getOrNull()
    }

    override fun openRead(path: String): InputStream = openRead(path, 0)

    override fun openRead(path: String, offset: Long): InputStream {
        val (id, remote) = parse(path)
        return mapErrors(path) { sessions.openRead(id, remote, offset) }
    }

    override fun openWrite(path: String): OutputStream {
        val (id, remote) = parse(path)
        return mapErrors(path) { sessions.openWrite(id, remote) }
    }

    override fun setLastModified(path: String, time: Long) {
        val (id, remote) = NetworkPaths.parse(path) ?: return
        runCatching { sessions.use(id) { it.setLastModified(remote, time) } }
    }

    private fun rootEntry(connection: Connection) = FileEntry(
        path = NetworkPaths.rootOf(connection.id),
        name = connection.displayName,
        isDirectory = true,
        size = 0,
        lastModified = connection.lastConnectedAt ?: connection.createdAt,
    )

    private fun RemoteEntry.toFileEntry(id: Long) = FileEntry(
        path = NetworkPaths.pathOf(id, path),
        name = name,
        isDirectory = isDirectory,
        size = if (isDirectory) 0 else size,
        lastModified = lastModified,
    )

    private fun parse(path: String): Pair<Long, String> = try {
        NetworkPaths.parse(path) ?: throw StorageException.NotFound(path)
    } catch (_: IllegalArgumentException) {
        throw StorageException.AccessDenied(path)
    }

    private fun child(parent: String, name: String): String {
        val trimmed = name.trim()
        if (FileNames.validate(trimmed) != FileNames.Validation.Ok) throw StorageException.InvalidName(name)
        return parent.trimEnd('/') + "/" + trimmed
    }

    private fun <T> call(id: Long, appPath: String, block: (RemoteFileSystem) -> T): T = mapErrors(appPath) { sessions.use(id, block) }

    private inline fun <T> mapErrors(appPath: String, block: () -> T): T = try {
        block()
    } catch (e: StorageException) {
        throw e
    } catch (e: FileNotFoundException) {
        throw StorageException.NotFound(appPath)
    } catch (e: FileAlreadyExistsException) {
        throw StorageException.AlreadyExists(appPath)
    } catch (e: SecurityException) {
        throw StorageException.AccessDenied(appPath, e)
    } catch (e: RemoteRefusal) {
        throw StorageException.AccessDenied(appPath, e)
    } catch (e: IOException) {
        throw StorageException.Io(e.message ?: "Network error", e)
    }
}

/** Подключения для корня `/qf-net` (без обращения к Room из тестов). */
interface ConnectionList {
    suspend fun all(): List<Connection>
    suspend fun get(id: Long): Connection?
}

class RepositoryConnectionList @Inject constructor(private val repository: ConnectionsRepository) : ConnectionList {
    override suspend fun all() = repository.connections.first()
    override suspend fun get(id: Long) = repository.get(id)
}
