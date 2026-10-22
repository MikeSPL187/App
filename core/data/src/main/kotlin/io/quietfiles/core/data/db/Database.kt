package io.quietfiles.core.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val path: String,
    val name: String,
    val isDirectory: Boolean,
    val addedAt: Long,
)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val query: String,
    val usedAt: Long,
)

/** Сортировка и вид, запомненные для конкретной папки (F3.3). */
@Entity(tableName = "folder_prefs")
data class FolderPrefEntity(
    @PrimaryKey val path: String,
    val sortField: String,
    val ascending: Boolean,
    val foldersFirst: Boolean,
    val viewMode: String,
)

@Entity(tableName = "trash", indices = [Index("deletedAt"), Index("trashPath", unique = true)])
data class TrashEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originalPath: String,
    val trashPath: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val deletedAt: Long,
)

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT path FROM favorites")
    fun observePaths(): Flow<List<String>>

    @Upsert
    suspend fun upsert(entity: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE path = :path")
    suspend fun delete(path: String)

    @Query("UPDATE favorites SET path = :newPath, name = :newName WHERE path = :oldPath")
    suspend fun move(oldPath: String, newPath: String, newName: String)
}

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY usedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SearchHistoryEntity>>

    @Upsert
    suspend fun upsert(entity: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE `query` = :query")
    suspend fun delete(query: String)

    @Query("DELETE FROM search_history WHERE `query` NOT IN (SELECT `query` FROM search_history ORDER BY usedAt DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    @Query("DELETE FROM search_history")
    suspend fun clear()
}

@Dao
interface FolderPrefDao {
    @Query("SELECT * FROM folder_prefs WHERE path = :path")
    fun observe(path: String): Flow<FolderPrefEntity?>

    @Upsert
    suspend fun upsert(entity: FolderPrefEntity)
}

@Dao
interface TrashDao {
    @Query("SELECT * FROM trash ORDER BY deletedAt DESC")
    fun observeAll(): Flow<List<TrashEntity>>

    @Query("SELECT * FROM trash WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<TrashEntity>

    @Query("SELECT * FROM trash WHERE deletedAt < :before")
    suspend fun olderThan(before: Long): List<TrashEntity>

    @Query("SELECT * FROM trash")
    suspend fun all(): List<TrashEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: TrashEntity): Long

    @Query("DELETE FROM trash WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)
}

@Database(
    entities = [FavoriteEntity::class, SearchHistoryEntity::class, FolderPrefEntity::class, TrashEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class QfDatabase : RoomDatabase() {
    abstract fun favorites(): FavoriteDao
    abstract fun searchHistory(): SearchHistoryDao
    abstract fun folderPrefs(): FolderPrefDao
    abstract fun trash(): TrashDao
}
