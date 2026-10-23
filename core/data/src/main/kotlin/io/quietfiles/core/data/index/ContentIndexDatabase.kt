package io.quietfiles.core.data.index

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Текст файлов для поиска по содержимому (F6.6). unicode61 приводит к одному регистру и кириллицу. */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61, notIndexed = ["path"])
@Entity(tableName = "content_fts")
data class ContentFtsEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long = 0,
    val path: String,
    val name: String,
    val body: String,
)

/** Что уже проиндексировано: по размеру и дате изменения видно, что файл менялся. */
@Entity(tableName = "content_files")
data class ContentFileEntity(
    @PrimaryKey val path: String,
    val size: Long,
    val modified: Long,
    val rowId: Long,
    val indexedAt: Long,
)

data class ContentHit(val path: String, val name: String, val snippet: String)

@Dao
interface ContentIndexDao {
    @Query("SELECT * FROM content_files")
    suspend fun files(): List<ContentFileEntity>

    @Query("SELECT COUNT(*) FROM content_files")
    fun observeCount(): Flow<Int>

    @Insert
    suspend fun insertText(entity: ContentFtsEntity): Long

    @Query("DELETE FROM content_fts WHERE rowid = :rowId")
    suspend fun deleteText(rowId: Long)

    @Upsert
    suspend fun upsertFile(entity: ContentFileEntity)

    @Query("DELETE FROM content_files WHERE path = :path")
    suspend fun deleteFile(path: String)

    @Transaction
    suspend fun replace(path: String, name: String, body: String, size: Long, modified: Long, previousRow: Long?, now: Long) {
        previousRow?.let { deleteText(it) }
        val row = insertText(ContentFtsEntity(path = path, name = name, body = body))
        upsertFile(ContentFileEntity(path, size, modified, row, now))
    }

    @Transaction
    suspend fun remove(file: ContentFileEntity) {
        deleteText(file.rowId)
        deleteFile(file.path)
    }

    /** Отрывок вокруг совпадения: найденное между \u0001 и \u0002. */
    @Query(
        "SELECT path, name, snippet(content_fts, '\u0001', '\u0002', '…', 2, 12) AS snippet " +
            "FROM content_fts WHERE content_fts MATCH :match LIMIT :limit",
    )
    suspend fun search(match: String, limit: Int): List<ContentHit>

    @Query("DELETE FROM content_fts")
    suspend fun clearText()

    @Query("DELETE FROM content_files")
    suspend fun clearFiles()
}

/**
 * Отдельная база: индекс большой, пересобирается сам и не должен попадать в резервные копии
 * вместе с настройками и избранным (содержимое документов остаётся только на устройстве).
 */
@Database(entities = [ContentFtsEntity::class, ContentFileEntity::class], version = 1, exportSchema = true)
abstract class ContentIndexDatabase : RoomDatabase() {
    abstract fun index(): ContentIndexDao
}
