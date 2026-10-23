package io.quietfiles.core.storage

import io.quietfiles.core.model.FileEntry
import java.io.File

/**
 * Запасной обход файловой системы, когда системный индекс MediaStore пуст или недоступен (F7.1).
 * Обходит без рекурсии стека, пропускает скрытые папки и данные приложений, ограничен по глубине и числу находок.
 */
internal object FileSystemScanner {

    fun scan(
        roots: List<File>,
        accept: (FileEntry) -> Boolean,
        maxDepth: Int = 12,
        limit: Int = 5_000,
        checkCancelled: () -> Unit = {},
    ): List<FileEntry> {
        val result = ArrayList<FileEntry>()
        val stack = ArrayDeque(roots.map { it to 0 })
        while (stack.isNotEmpty() && result.size < limit) {
            checkCancelled()
            val (dir, depth) = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (child.name.startsWith(".") || MediaIndex.isHiddenOrPrivate(child.path + "/")) continue
                if (child.isDirectory) {
                    if (depth < maxDepth) stack.addLast(child to depth + 1)
                } else {
                    val entry = child.toEntry(countHiddenChildren = false)
                    if (accept(entry)) {
                        result += entry
                        if (result.size >= limit) break
                    }
                }
            }
        }
        return result
    }
}
