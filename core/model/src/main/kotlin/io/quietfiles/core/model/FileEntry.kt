package io.quietfiles.core.model

/**
 * Элемент файловой системы любого хранилища.
 *
 * [path] — абсолютный путь внутри хранилища в формате POSIX (`/storage/emulated/0/Download/a.pdf`).
 */
data class FileEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val isHidden: Boolean = name.startsWith("."),
    val childCount: Int? = null,
    val mimeType: String? = null,
) {
    val extension: String get() = if (isDirectory) "" else FileNames.extension(name)

    val category: FileCategory
        get() = if (isDirectory) FileCategory.FOLDER else FileTypes.categoryOf(name, mimeType)
}

/** Свободное и общее место на томе, в байтах. */
data class StorageSpace(val total: Long, val free: Long) {
    val used: Long get() = (total - free).coerceAtLeast(0)
    val usedFraction: Float get() = if (total <= 0) 0f else used.toFloat() / total
}

enum class VolumeKind { INTERNAL, SD_CARD, USB }

/** Подключённый том: внутренняя память, SD-карта или USB-накопитель. */
data class StorageVolumeInfo(
    val id: String,
    val kind: VolumeKind,
    val label: String,
    val rootPath: String,
    val isPrimary: Boolean,
    val isRemovable: Boolean,
)
