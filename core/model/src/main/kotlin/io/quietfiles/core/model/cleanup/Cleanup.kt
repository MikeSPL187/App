package io.quietfiles.core.model.cleanup

import io.quietfiles.core.model.FileEntry
import java.io.File

/**
 * Пункты очистки (F13). [safe] — выбран по умолчанию и не требует проверки (F13.1);
 * остальные пользователь просматривает сам (F13.2). [viaTrash] — удаление через корзину (F13.5).
 */
enum class CleanupCategory(val safe: Boolean, val viaTrash: Boolean) {
    DUPLICATES(safe = true, viaTrash = true),
    INSTALLED_APKS(safe = true, viaTrash = true),
    CACHE(safe = true, viaTrash = false),
    OLD_TRASH(safe = true, viaTrash = false),
    LARGE_FILES(safe = false, viaTrash = true),
    OLD_DOWNLOADS(safe = false, viaTrash = true),
}

/** Файл-кандидат на удаление. [trashId] — для пунктов из корзины. */
data class CleanupFile(val entry: FileEntry, val trashId: Long? = null) {
    val path: String get() = entry.path
    val size: Long get() = entry.size
}

data class CleanupSection(
    val category: CleanupCategory,
    val files: List<CleanupFile>,
    /** Для дубликатов: группы, где оригинал не входит в [files]. */
    val groups: List<DuplicateGroup> = emptyList(),
) {
    val size: Long get() = files.sumOf { it.size }
    val count: Int get() = files.size
}

/**
 * Что выбрано к очистке: пункты целиком и исключённые из них файлы.
 * Сумма «Можно освободить» (F2.3) — это [selectedBytes] при выборе по умолчанию.
 */
data class CleanupSelection(
    val categories: Set<CleanupCategory> = CleanupCategory.entries.filter { it.safe }.toSet(),
    val excluded: Set<String> = emptySet(),
) {
    fun isSelected(file: CleanupFile, category: CleanupCategory) = category in categories && file.path !in excluded

    /** Один файл может попасть в несколько пунктов (большой и старая загрузка) — считаем его один раз. */
    fun selectedFiles(sections: List<CleanupSection>): List<Pair<CleanupCategory, CleanupFile>> =
        sections.flatMap { s -> s.files.filter { isSelected(it, s.category) }.map { s.category to it } }.distinctBy { it.second.path }

    fun selectedBytes(sections: List<CleanupSection>): Long = selectedFiles(sections).sumOf { it.second.size }

    fun toggleCategory(category: CleanupCategory) =
        copy(categories = if (category in categories) categories - category else categories + category)

    fun toggleFile(path: String) = copy(excluded = if (path in excluded) excluded - path else excluded + path)
}

/** Правила для пунктов «Проверьте вручную» (F13.2). */
object CleanupRules {
    const val MB = 1024L * 1024
    const val DAY = 24L * 60 * 60 * 1000

    val LARGE_THRESHOLDS = listOf(100L * MB, 500L * MB)
    const val DEFAULT_LARGE_THRESHOLD = 500L * MB

    /** Старые загрузки — не открывались и не менялись 90 дней. */
    const val OLD_DOWNLOAD_DAYS = 90

    /** Если корзина хранит всё бессрочно, «старой» считаем то, что лежит дольше 30 дней. */
    const val DEFAULT_TRASH_AGE_DAYS = 30

    /** Папки миниатюр, которые система и галереи создают заново (F13.4: «восстановится сам»). */
    val THUMBNAIL_DIRS = listOf("DCIM/.thumbnails", "Pictures/.thumbnails", "Movies/.thumbnails", "Music/.thumbnails")

    fun isOldDownload(entry: FileEntry, now: Long): Boolean =
        !entry.isDirectory && now - entry.lastModified > OLD_DOWNLOAD_DAYS * DAY

}

/** Узел дерева размеров (F13.6). */
data class FolderSize(val path: String, val name: String, val isDirectory: Boolean, val bytes: Long, val files: Int)

/** Размеры содержимого папки для анализа (F13.6): каждая дочерняя папка — суммой всего, что в ней. */
object FolderSizes {
    fun children(dir: File, checkCancelled: () -> Unit = {}): List<FolderSize> {
        val children = dir.listFiles() ?: return emptyList()
        return children.map { child ->
            checkCancelled()
            if (child.isDirectory) {
                val (bytes, count) = total(child, checkCancelled)
                FolderSize(child.path, child.name, true, bytes, count)
            } else {
                FolderSize(child.path, child.name, false, child.length(), 1)
            }
        }.sortedByDescending { it.bytes }
    }

    /** Размер и число файлов в папке; обход без рекурсии, символьные ссылки не раскрываем. */
    fun total(dir: File, checkCancelled: () -> Unit = {}): Pair<Long, Int> {
        var bytes = 0L
        var count = 0
        val stack = ArrayDeque(listOf(dir))
        while (stack.isNotEmpty()) {
            checkCancelled()
            val current = stack.removeLast()
            val list = current.listFiles() ?: continue
            for (f in list) {
                if (java.nio.file.Files.isSymbolicLink(f.toPath())) continue
                if (f.isDirectory) {
                    stack.addLast(f)
                } else {
                    bytes += f.length()
                    count++
                }
            }
        }
        return bytes to count
    }
}
