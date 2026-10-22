package io.quietfiles.core.data.repository

import io.quietfiles.core.data.db.FavoriteDao
import io.quietfiles.core.data.db.FavoriteEntity
import io.quietfiles.core.data.db.FolderPrefDao
import io.quietfiles.core.data.db.FolderPrefEntity
import io.quietfiles.core.data.db.SearchHistoryDao
import io.quietfiles.core.data.db.SearchHistoryEntity
import io.quietfiles.core.model.FileEntry
import io.quietfiles.core.model.SortField
import io.quietfiles.core.model.SortOrder
import io.quietfiles.core.model.ViewMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class Favorite(val path: String, val name: String, val isDirectory: Boolean)

@Singleton
class FavoritesRepository @Inject constructor(private val dao: FavoriteDao) {
    val favorites: Flow<List<Favorite>> = dao.observeAll().map { list ->
        list.map { Favorite(it.path, it.name, it.isDirectory) }
    }
    val paths: Flow<Set<String>> = dao.observePaths().map { it.toSet() }

    suspend fun add(entry: FileEntry) =
        dao.upsert(FavoriteEntity(entry.path, entry.name, entry.isDirectory, System.currentTimeMillis()))

    suspend fun remove(path: String) = dao.delete(path)

    /** После переименования или перемещения избранное указывает на новый путь. */
    suspend fun onMoved(oldPath: String, newPath: String, newName: String) = dao.move(oldPath, newPath, newName)
}

@Singleton
class SearchHistoryRepository @Inject constructor(private val dao: SearchHistoryDao) {
    val recent: Flow<List<String>> = dao.observeRecent(MAX).map { list -> list.map { it.query } }

    suspend fun add(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        dao.upsert(SearchHistoryEntity(q, System.currentTimeMillis()))
        dao.trim(MAX)
    }

    suspend fun remove(query: String) = dao.delete(query)
    suspend fun clear() = dao.clear()

    private companion object {
        const val MAX = 20
    }
}

data class FolderPrefs(val sort: SortOrder, val viewMode: ViewMode)

@Singleton
class FolderPrefsRepository @Inject constructor(private val dao: FolderPrefDao) {
    /** `null`, если для папки ничего не запомнено и действуют настройки по умолчанию. */
    fun observe(path: String): Flow<FolderPrefs?> = dao.observe(path).map { e ->
        e?.let {
            FolderPrefs(
                sort = SortOrder(
                    field = SortField.entries.firstOrNull { f -> f.name == it.sortField } ?: SortField.NAME,
                    ascending = it.ascending,
                    foldersFirst = it.foldersFirst,
                ),
                viewMode = ViewMode.entries.firstOrNull { v -> v.name == it.viewMode } ?: ViewMode.LIST,
            )
        }
    }

    suspend fun save(path: String, prefs: FolderPrefs) = dao.upsert(
        FolderPrefEntity(
            path = path,
            sortField = prefs.sort.field.name,
            ascending = prefs.sort.ascending,
            foldersFirst = prefs.sort.foldersFirst,
            viewMode = prefs.viewMode.name,
        ),
    )
}
