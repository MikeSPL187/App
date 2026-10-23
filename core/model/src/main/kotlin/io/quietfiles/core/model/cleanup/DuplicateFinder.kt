package io.quietfiles.core.model.cleanup

import io.quietfiles.core.model.FileEntry
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Группа одинаковых файлов: [original] остаётся, [copies] можно удалить (F13.3). */
data class DuplicateGroup(val original: FileEntry, val copies: List<FileEntry>) {
    val size: Long get() = original.size
    val reclaimable: Long get() = size * copies.size
}

/** Откуда брать хэши: в тестах — подсчёт обращений, в приложении — файлы на диске. */
interface ContentHasher {
    /** Хэш первых и последних [edge] байт и длины — дёшево отсекает разные файлы одного размера. */
    fun edgeHash(path: String, size: Long, edge: Int): String

    fun fullHash(path: String): String
}

object FileContentHasher : ContentHasher {
    override fun edgeHash(path: String, size: Long, edge: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(path, "r").use { file ->
            val buffer = ByteArray(minOf(edge.toLong(), size).toInt())
            file.readFully(buffer)
            digest.update(buffer)
            if (size > edge) {
                val tail = ByteArray(minOf(edge.toLong(), size - edge).toInt())
                file.seek(size - tail.size)
                file.readFully(tail)
                digest.update(tail)
            }
        }
        digest.update(size.toString().toByteArray())
        return digest.digest().toHex()
    }

    override fun fullHash(path: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        File(path).inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private const val BUFFER = 256 * 1024
}

/**
 * Поиск дубликатов в три шага (F13.3): совпадение размера, затем хэш первых и последних 64 КБ,
 * и только для оставшихся — полный SHA-256. Так большая часть файлов не читается вовсе.
 */
class DuplicateFinder(
    private val hasher: ContentHasher = FileContentHasher,
    private val minSize: Long = MIN_SIZE,
    private val checkCancelled: () -> Unit = {},
) {
    fun find(files: List<FileEntry>, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): List<DuplicateGroup> {
        val bySize = files.asSequence()
            .filter { !it.isDirectory && it.size >= minSize }
            .distinctBy { it.path }
            .groupBy { it.size }
            .values.filter { it.size > 1 }
        val total = bySize.sumOf { it.size }
        var done = 0
        val groups = mutableListOf<DuplicateGroup>()
        for (sameSize in bySize) {
            val byEdges = sameSize.groupBy { entry ->
                checkCancelled()
                runCatching { hasher.edgeHash(entry.path, entry.size, EDGE) }.getOrNull()
            }
            for ((edge, candidates) in byEdges) {
                if (edge == null || candidates.size < 2) continue
                val byContent = candidates.groupBy { entry ->
                    checkCancelled()
                    runCatching { hasher.fullHash(entry.path) }.getOrNull()
                }
                for ((hash, same) in byContent) {
                    if (hash == null || same.size < 2) continue
                    val original = OriginalPicker.pick(same)
                    groups += DuplicateGroup(original, same.filter { it.path != original.path })
                }
            }
            done += sameSize.size
            onProgress(done, total)
        }
        return groups.sortedByDescending { it.reclaimable }
    }

    companion object {
        const val EDGE = 64 * 1024

        /** Мелочь вроде значков и заметок места не освободит, а сканирование замедлит. */
        const val MIN_SIZE = 256 * 1024L
    }
}

/** Какой из одинаковых файлов оставить (F13.3): снимок из DCIM/Camera, иначе самый старый. */
object OriginalPicker {
    fun pick(files: List<FileEntry>): FileEntry = files.sortedWith(
        compareBy<FileEntry>(
            { if (isCameraPath(it.path)) 0 else 1 },
            { it.lastModified },
            { it.path.count { c -> c == '/' } },
            { it.path },
        ),
    ).first()

    fun isCameraPath(path: String): Boolean = path.contains("/DCIM/Camera/", ignoreCase = true)
}
