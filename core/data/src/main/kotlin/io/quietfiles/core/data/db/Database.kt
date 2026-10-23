package io.quietfiles.core.data.db

import androidx.room.AutoMigration
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

/** Место, где остановили видео или длинную запись (F9.5), для последних 200 файлов. */
@Entity(tableName = "playback_positions", indices = [Index("updatedAt")])
data class PlaybackPositionEntity(
    @PrimaryKey val path: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

@Dao
interface PlaybackPositionDao {
    @Query("SELECT * FROM playback_positions WHERE path = :path")
    suspend fun get(path: String): PlaybackPositionEntity?

    @Upsert
    suspend fun upsert(entity: PlaybackPositionEntity)

    @Query("DELETE FROM playback_positions WHERE path = :path")
    suspend fun delete(path: String)

    @Query("DELETE FROM playback_positions WHERE path NOT IN (SELECT path FROM playback_positions ORDER BY updatedAt DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    @Query("SELECT COUNT(*) FROM playback_positions")
    suspend fun count(): Int
}

/** «История очистки» (F13): когда и сколько освободили. */
@Entity(tableName = "cleanup_history")
data class CleanupHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val bytes: Long,
    val files: Int,
)

@Dao
interface CleanupHistoryDao {
    @Query("SELECT * FROM cleanup_history ORDER BY at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<CleanupHistoryEntity>>

    @Insert
    suspend fun insert(entity: CleanupHistoryEntity)

    @Query("DELETE FROM cleanup_history WHERE id NOT IN (SELECT id FROM cleanup_history ORDER BY at DESC LIMIT :keep)")
    suspend fun trim(keep: Int)
}

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

/** Сохранённые серверы без секретов (раздел 9.3); секреты — в `SecretStore`. */
@Entity(tableName = "connections")
data class ConnectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val protocol: String,
    val host: String,
    val port: Int,
    val username: String,
    val auth: String,
    val name: String,
    val path: String,
    val ftpSecurity: String,
    val plainHttp: Boolean,
    val keyLabel: String,
    val hostKey: String?,
    val pinnedCertificate: String?,
    val createdAt: Long,
    val lastConnectedAt: Long?,
)

@Dao
interface ConnectionDao {
    @Query("SELECT * FROM connections ORDER BY createdAt")
    fun observeAll(): Flow<List<ConnectionEntity>>

    @Query("SELECT * FROM connections WHERE id = :id")
    suspend fun get(id: Long): ConnectionEntity?

    @Insert
    suspend fun insert(entity: ConnectionEntity): Long

    @Upsert
    suspend fun upsert(entity: ConnectionEntity)

    @Query("UPDATE connections SET hostKey = :hostKey WHERE id = :id")
    suspend fun setHostKey(id: Long, hostKey: String?)

    @Query("UPDATE connections SET pinnedCertificate = :spki WHERE id = :id")
    suspend fun setPinnedCertificate(id: Long, spki: String?)

    @Query("UPDATE connections SET lastConnectedAt = :at WHERE id = :id")
    suspend fun setLastConnected(id: Long, at: Long)

    @Query("DELETE FROM connections WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(
    entities = [
        FavoriteEntity::class, SearchHistoryEntity::class, FolderPrefEntity::class, TrashEntity::class,
        PlaybackPositionEntity::class, CleanupHistoryEntity::class, ConnectionEntity::class,
    ],
    version = 4,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)],
)
abstract class QfDatabase : RoomDatabase() {
    abstract fun favorites(): FavoriteDao
    abstract fun searchHistory(): SearchHistoryDao
    abstract fun folderPrefs(): FolderPrefDao
    abstract fun trash(): TrashDao
    abstract fun playbackPositions(): PlaybackPositionDao
    abstract fun cleanupHistory(): CleanupHistoryDao
    abstract fun connections(): ConnectionDao
}
