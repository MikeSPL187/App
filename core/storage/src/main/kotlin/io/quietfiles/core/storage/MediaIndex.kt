package io.quietfiles.core.storage

import android.content.Context
import android.database.Cursor
import android.media.MediaScannerConnection
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import io.quietfiles.core.common.IoDispatcher
import io.quietfiles.core.model.FileCategory
import io.quietfiles.core.model.FileEntry
import io.quietfiles.core.model.FileNames
import io.quietfiles.core.model.FileTypes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** Разделы библиотеки на главной (F2.4). */
enum class LibraryCategory(val categories: Set<FileCategory>) {
    PHOTOS(setOf(FileCategory.IMAGE)),
    VIDEOS(setOf(FileCategory.VIDEO)),
    AUDIO(setOf(FileCategory.AUDIO)),
    DOCUMENTS(setOf(FileCategory.DOCUMENT, FileCategory.SHEET, FileCategory.PRESENTATION, FileCategory.TEXT)),
    ARCHIVES(setOf(FileCategory.ARCHIVE)),
    APKS(setOf(FileCategory.APK)),
}

/** Занятое место по типам файлов на одном томе. */
data class StorageBreakdown(val bytesByCategory: Map<FileCategory, Long>) {
    fun bytes(vararg categories: FileCategory): Long = categories.sumOf { bytesByCategory[it] ?: 0L }
}

/**
 * Быстрые выборки по системному индексу MediaStore: категории, недавние, разбивка памяти, поиск по имени.
 * Индекс покрывает общую память и SD-карты; скрытые папки и `Android/data` в выдачу не попадают.
 */
@Singleton
class MediaIndex @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
    private val projection = arrayOf(
        FileColumns.DATA,
        FileColumns.DISPLAY_NAME,
        FileColumns.SIZE,
        FileColumns.DATE_MODIFIED,
        FileColumns.MIME_TYPE,
        FileColumns.MEDIA_TYPE,
    )
    private val notDirectory = "${FileColumns.MIME_TYPE} IS NOT NULL"

    suspend fun category(category: LibraryCategory, volumeRoot: String? = null): List<FileEntry> = withContext(io) {
        val mediaType = when (category) {
            LibraryCategory.PHOTOS -> FileColumns.MEDIA_TYPE_IMAGE
            LibraryCategory.VIDEOS -> FileColumns.MEDIA_TYPE_VIDEO
            LibraryCategory.AUDIO -> FileColumns.MEDIA_TYPE_AUDIO
            else -> null
        }
        val selection = if (mediaType != null) "${FileColumns.MEDIA_TYPE} = $mediaType" else notDirectory
        query(selection, "${FileColumns.DATE_MODIFIED} DESC") { entry ->
            (volumeRoot == null || entry.path.startsWith("$volumeRoot/")) &&
                (mediaType != null || entry.category in category.categories)
        }
    }

    /** Последние изменённые файлы, без скрытых и служебных. */
    suspend fun recent(limit: Int = 20): List<FileEntry> = withContext(io) {
        query(notDirectory, "${FileColumns.DATE_MODIFIED} DESC", limit = limit) { true }
    }

    suspend fun breakdown(volumeRoot: String): StorageBreakdown = withContext(io) {
        val totals = HashMap<FileCategory, Long>()
        context.contentResolver.query(uri, projection, notDirectory, null, null)?.use { cursor ->
            val dataCol = cursor.getColumnIndexOrThrow(FileColumns.DATA)
            val nameCol = cursor.getColumnIndexOrThrow(FileColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(FileColumns.SIZE)
            val mimeCol = cursor.getColumnIndexOrThrow(FileColumns.MIME_TYPE)
            while (cursor.moveToNext()) {
                coroutineContext.ensureActive()
                val path = cursor.getString(dataCol) ?: continue
                if (!path.startsWith("$volumeRoot/")) continue
                val name = cursor.getString(nameCol) ?: FileNames.nameOf(path)
                val category = FileTypes.categoryOf(name, cursor.getString(mimeCol))
                totals[category] = (totals[category] ?: 0L) + cursor.getLong(sizeCol)
            }
        }
        StorageBreakdown(totals)
    }

    /**
     * Поиск по имени без учёта регистра, «ё» = «е». Фильтр в коде, потому что LIKE в SQLite
     * не сворачивает регистр кириллицы.
     */
    suspend fun searchByName(
        query: String,
        categories: Set<FileCategory> = emptySet(),
        modifiedAfter: Long = 0,
        minSize: Long = 0,
        maxSize: Long = Long.MAX_VALUE,
        limit: Int = 500,
    ): List<FileEntry> = withContext(io) {
        val needle = normalize(query.trim())
        if (needle.isEmpty() && categories.isEmpty()) return@withContext emptyList()
        query(notDirectory, "${FileColumns.DATE_MODIFIED} DESC", limit = limit) { entry ->
            (needle.isEmpty() || normalize(entry.name).contains(needle)) &&
                (categories.isEmpty() || entry.category in categories) &&
                entry.lastModified >= modifiedAfter &&
                entry.size in minSize..maxSize
        }
    }

    /** Сообщает системе об изменённых файлах, чтобы галерея и индекс сразу увидели изменения. */
    fun notifyChanged(paths: Collection<String>) {
        if (paths.isEmpty()) return
        MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
    }

    private suspend fun query(
        selection: String,
        sortOrder: String,
        limit: Int = Int.MAX_VALUE,
        filter: (FileEntry) -> Boolean,
    ): List<FileEntry> {
        val result = ArrayList<FileEntry>()
        context.contentResolver.query(uri, projection, selection, null, sortOrder)?.use { cursor ->
            val reader = RowReader(cursor)
            while (cursor.moveToNext() && result.size < limit) {
                coroutineContext.ensureActive()
                val entry = reader.read() ?: continue
                if (isHiddenOrPrivate(entry.path)) continue
                if (filter(entry)) result += entry
            }
        }
        return result
    }

    private class RowReader(private val cursor: Cursor) {
        private val dataCol = cursor.getColumnIndexOrThrow(FileColumns.DATA)
        private val nameCol = cursor.getColumnIndexOrThrow(FileColumns.DISPLAY_NAME)
        private val sizeCol = cursor.getColumnIndexOrThrow(FileColumns.SIZE)
        private val dateCol = cursor.getColumnIndexOrThrow(FileColumns.DATE_MODIFIED)
        private val mimeCol = cursor.getColumnIndexOrThrow(FileColumns.MIME_TYPE)

        fun read(): FileEntry? {
            val path = cursor.getString(dataCol) ?: return null
            return FileEntry(
                path = path,
                name = cursor.getString(nameCol) ?: FileNames.nameOf(path),
                isDirectory = false,
                size = cursor.getLong(sizeCol),
                lastModified = cursor.getLong(dateCol) * 1000,
                mimeType = cursor.getString(mimeCol),
            )
        }
    }

    companion object {
        fun normalize(s: String): String = s.lowercase().replace('ё', 'е')

        fun isHiddenOrPrivate(path: String): Boolean =
            path.contains("/.") || path.contains("/Android/data/") || path.contains("/Android/obb/")
    }
}
