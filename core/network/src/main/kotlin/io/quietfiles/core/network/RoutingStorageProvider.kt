package io.quietfiles.core.network

import io.quietfiles.core.model.FileEntry
import io.quietfiles.core.model.StorageSpace
import io.quietfiles.core.model.network.NetworkPaths
import io.quietfiles.core.storage.LocalStorageProvider
import io.quietfiles.core.storage.StorageProvider
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Единая точка входа в хранилища: пути `/qf-net/…` уходят на серверы, остальные — в файлы устройства.
 * Копирование между разными хранилищами идёт обычным потоком через очередь операций (раздел 9.2).
 */
@Singleton
class RoutingStorageProvider @Inject constructor(
    private val local: LocalStorageProvider,
    private val network: NetworkStorageProvider,
) : StorageProvider {

    private fun of(path: String): StorageProvider = if (NetworkPaths.isNetwork(path)) network else local

    override suspend fun list(path: String, includeHidden: Boolean): List<FileEntry> = of(path).list(path, includeHidden)
    override suspend fun stat(path: String): FileEntry? = of(path).stat(path)
    override suspend fun exists(path: String): Boolean = of(path).exists(path)
    override suspend fun createDirectory(parent: String, name: String): FileEntry = of(parent).createDirectory(parent, name)
    override suspend fun createFile(parent: String, name: String): FileEntry = of(parent).createFile(parent, name)
    override suspend fun rename(path: String, newName: String): FileEntry = of(path).rename(path, newName)

    override suspend fun moveFast(from: String, to: String): Boolean {
        if (NetworkPaths.isNetwork(from) != NetworkPaths.isNetwork(to)) return false
        return of(from).moveFast(from, to)
    }

    override suspend fun delete(path: String) = of(path).delete(path)
    override suspend fun space(path: String): StorageSpace? = of(path).space(path)
    override fun openRead(path: String): InputStream = of(path).openRead(path)
    override fun openRead(path: String, offset: Long): InputStream = of(path).openRead(path, offset)
    override fun openWrite(path: String): OutputStream = of(path).openWrite(path)
    override fun setLastModified(path: String, time: Long) = of(path).setLastModified(path, time)
}
