package io.quietfiles.core.storage

import android.os.StatFs
import io.quietfiles.core.common.IoDispatcher
import io.quietfiles.core.model.FileEntry
import io.quietfiles.core.model.FileNames
import io.quietfiles.core.model.StorageSpace
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** Файлы на устройстве: внутренняя память, SD-карта, USB. Нужен доступ «Все файлы». */
@Singleton
class LocalStorageProvider @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
) : StorageProvider {

    override suspend fun list(path: String, includeHidden: Boolean): List<FileEntry> = withContext(io) {
        val dir = File(path)
        if (!dir.exists()) throw StorageException.NotFound(path)
        if (!dir.isDirectory) throw StorageException.Io("Not a directory: $path")
        val children = dir.listFiles() ?: throw StorageException.AccessDenied(path)
        val result = ArrayList<FileEntry>(children.size)
        for (child in children) {
            coroutineContext.ensureActive()
            if (!includeHidden && child.name.startsWith(".")) continue
            result += child.toEntry(countHiddenChildren = includeHidden)
        }
        result
    }

    override suspend fun stat(path: String): FileEntry? = withContext(io) {
        val file = File(path)
        if (file.exists()) file.toEntry(countHiddenChildren = true) else null
    }

    override suspend fun exists(path: String): Boolean = withContext(io) { File(path).exists() }

    override suspend fun createDirectory(parent: String, name: String): FileEntry = withContext(io) {
        val target = checkedChild(parent, name)
        if (target.exists()) throw StorageException.AlreadyExists(target.path)
        if (!target.mkdir()) throw StorageException.AccessDenied(target.path)
        target.toEntry(countHiddenChildren = true)
    }

    override suspend fun createFile(parent: String, name: String): FileEntry = withContext(io) {
        val target = checkedChild(parent, name)
        try {
            if (!target.createNewFile()) throw StorageException.AlreadyExists(target.path)
        } catch (e: IOException) {
            throw StorageException.AccessDenied(target.path, e)
        }
        target.toEntry(countHiddenChildren = true)
    }

    override suspend fun rename(path: String, newName: String): FileEntry = withContext(io) {
        val source = File(path)
        if (!source.exists()) throw StorageException.NotFound(path)
        val target = checkedChild(source.parent ?: "/", newName)
        if (target.path == source.path) return@withContext source.toEntry(true)
        // Смена только регистра букв: на FAT/exFAT такой файл «уже существует», переименовываем через временное имя.
        val caseOnly = target.path.equals(source.path, ignoreCase = true)
        if (target.exists() && !caseOnly) throw StorageException.AlreadyExists(target.path)
        val ok = if (caseOnly) {
            val temp = File(source.parentFile, ".qf-rename-${System.nanoTime()}")
            source.renameTo(temp) && temp.renameTo(target)
        } else {
            source.renameTo(target)
        }
        if (!ok) throw StorageException.AccessDenied(path)
        target.toEntry(true)
    }

    override suspend fun moveFast(from: String, to: String): Boolean = withContext(io) {
        val source = File(from)
        val target = File(to)
        if (!source.exists()) throw StorageException.NotFound(from)
        if (target.exists()) throw StorageException.AlreadyExists(to)
        if (volumeRootOf(from) != volumeRootOf(to)) return@withContext false
        source.renameTo(target)
    }

    override suspend fun delete(path: String) = withContext(io) {
        val file = File(path)
        if (!file.exists()) return@withContext
        if (!deleteRecursively(file)) throw StorageException.AccessDenied(path)
    }

    private suspend fun deleteRecursively(file: File): Boolean {
        coroutineContext.ensureActive()
        // Символические ссылки удаляем как ссылки, не заходя внутрь.
        if (file.isDirectory && !java.nio.file.Files.isSymbolicLink(file.toPath())) {
            file.listFiles()?.forEach { if (!deleteRecursively(it)) return false }
        }
        return file.delete() || !file.exists()
    }

    override suspend fun space(path: String): StorageSpace? = withContext(io) {
        runCatching {
            val stat = StatFs(path)
            StorageSpace(total = stat.totalBytes, free = stat.availableBytes)
        }.getOrNull()
    }

    override fun openRead(path: String): InputStream = try {
        FileInputStream(path)
    } catch (e: IOException) {
        if (!File(path).exists()) throw StorageException.NotFound(path) else throw StorageException.AccessDenied(path, e)
    }

    override fun openRead(path: String, offset: Long): InputStream {
        val input = openRead(path)
        if (offset > 0) (input as FileInputStream).channel.position(offset)
        return input
    }

    override fun openWrite(path: String): OutputStream = try {
        FileOutputStream(path)
    } catch (e: IOException) {
        throw StorageException.AccessDenied(path, e)
    }

    override fun setLastModified(path: String, time: Long) {
        File(path).setLastModified(time)
    }

    private fun checkedChild(parent: String, name: String): File {
        val trimmed = name.trim()
        if (FileNames.validate(trimmed) != FileNames.Validation.Ok) throw StorageException.InvalidName(name)
        return File(parent, trimmed)
    }

    companion object {
        /** Корень тома: `/storage/emulated/0` или `/storage/XXXX-XXXX`. */
        fun volumeRootOf(path: String): String {
            val parts = path.trim('/').split('/')
            return when {
                parts.size >= 3 && parts[0] == "storage" && parts[1] == "emulated" -> "/storage/emulated/${parts[2]}"
                parts.size >= 2 && parts[0] == "storage" -> "/storage/${parts[1]}"
                parts.size >= 3 && parts[0] == "mnt" && parts[1] == "media_rw" -> "/mnt/media_rw/${parts[2]}"
                else -> "/"
            }
        }
    }
}

internal fun File.toEntry(countHiddenChildren: Boolean): FileEntry {
    val directory = isDirectory
    return FileEntry(
        path = absolutePath,
        name = name,
        isDirectory = directory,
        size = if (directory) 0 else length(),
        lastModified = lastModified(),
        childCount = if (directory) {
            list()?.let { names -> if (countHiddenChildren) names.size else names.count { !it.startsWith(".") } }
        } else null,
    )
}
