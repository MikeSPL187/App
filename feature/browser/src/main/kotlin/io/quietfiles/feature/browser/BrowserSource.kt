package io.quietfiles.feature.browser

import io.quietfiles.core.data.repository.FavoritesRepository
import io.quietfiles.core.model.FileCategory
import io.quietfiles.core.model.FileEntry
import io.quietfiles.core.model.FileNames
import io.quietfiles.core.model.TrashItem
import io.quietfiles.core.operations.ArchiveEntry
import io.quietfiles.core.operations.ArchiveTools
import io.quietfiles.core.operations.TrashManager
import io.quietfiles.core.storage.LibraryCategory
import io.quietfiles.core.storage.MediaIndex
import io.quietfiles.core.storage.StorageProvider
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** Элемент списка и данные, которые нужны только для конкретного источника. */
data class BrowserItem(
    val entry: FileEntry,
    /** Для корзины: запись о файле (откуда удалён, когда). */
    val trash: TrashItem? = null,
    /** Для архива: путь внутри архива. */
    val archivePath: String? = null,
)

/** Загружает содержимое для каждого вида источника. */
class BrowserSourceLoader @Inject constructor(
    private val provider: StorageProvider,
    private val mediaIndex: MediaIndex,
    private val favorites: FavoritesRepository,
    private val trash: TrashManager,
    private val archives: ArchiveTools,
) {
    private var archiveCache: Pair<String, List<ArchiveEntry>>? = null

    suspend fun load(kind: SourceKind, path: String, inner: String, showHidden: Boolean): List<BrowserItem> = when (kind) {
        SourceKind.FOLDER -> provider.list(path, showHidden)
            .filterNot { trash.isInTrash(it.path) }
            .map { BrowserItem(it) }
        SourceKind.ARCHIVE -> archiveChildren(path, inner)
        SourceKind.PHOTOS -> library(LibraryCategory.PHOTOS)
        SourceKind.VIDEOS -> library(LibraryCategory.VIDEOS)
        SourceKind.AUDIO -> library(LibraryCategory.AUDIO)
        SourceKind.DOCUMENTS -> library(LibraryCategory.DOCUMENTS)
        SourceKind.ARCHIVES -> library(LibraryCategory.ARCHIVES)
        SourceKind.APKS -> library(LibraryCategory.APKS)
        SourceKind.RECENT -> mediaIndex.recent(limit = 200).map { BrowserItem(it) }
        SourceKind.LARGE_FILES -> mediaIndex.searchByName("", minSize = LARGE_FILE_BYTES, limit = 300)
            .sortedByDescending { it.size }
            .map { BrowserItem(it) }
        SourceKind.FAVORITES -> favorites.favorites.first().mapNotNull { fav ->
            provider.stat(fav.path)?.let { BrowserItem(it) }
        }
        SourceKind.TRASH -> trash.items.first().map { item ->
            BrowserItem(
                entry = FileEntry(
                    path = item.trashPath,
                    name = item.name,
                    isDirectory = item.isDirectory,
                    size = item.size,
                    lastModified = item.deletedAt,
                ),
                trash = item,
            )
        }
    }

    private suspend fun library(category: LibraryCategory) = mediaIndex.category(category).map { BrowserItem(it) }

    private suspend fun archiveEntries(path: String): List<ArchiveEntry> {
        archiveCache?.let { (cached, entries) -> if (cached == path) return entries }
        return archives.list(path).also { archiveCache = path to it }
    }

    private suspend fun archiveChildren(path: String, inner: String): List<BrowserItem> {
        val prefix = inner.trim('/')
        val entries = archiveEntries(path).filter { it.path.isNotEmpty() }
        fun parentOf(entryPath: String) = FileNames.parentOf("/" + entryPath.trimEnd('/'))?.trimStart('/').orEmpty()
        val childCounts = entries.groupingBy { parentOf(it.path) }.eachCount()
        return entries
            .filter { parentOf(it.path) == prefix }
            .map { entry ->
                val key = entry.path.trimEnd('/')
                BrowserItem(
                    entry = FileEntry(
                        path = "$path!/$key",
                        name = entry.name,
                        isDirectory = entry.isDirectory,
                        size = entry.size.coerceAtLeast(0),
                        lastModified = entry.lastModified,
                        childCount = if (entry.isDirectory) childCounts[key] ?: 0 else null,
                    ),
                    archivePath = key,
                )
            }
    }

    fun categoryOf(kind: SourceKind): FileCategory? = when (kind) {
        SourceKind.PHOTOS -> FileCategory.IMAGE
        SourceKind.VIDEOS -> FileCategory.VIDEO
        SourceKind.AUDIO -> FileCategory.AUDIO
        SourceKind.DOCUMENTS -> FileCategory.DOCUMENT
        SourceKind.ARCHIVES -> FileCategory.ARCHIVE
        SourceKind.APKS -> FileCategory.APK
        else -> null
    }

    companion object {
        const val LARGE_FILE_BYTES = 100L * 1024 * 1024
    }
}
