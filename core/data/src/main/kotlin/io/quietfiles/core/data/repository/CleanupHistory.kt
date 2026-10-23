package io.quietfiles.core.data.repository

import io.quietfiles.core.data.db.CleanupHistoryDao
import io.quietfiles.core.data.db.CleanupHistoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Запись «История очистки»: когда, сколько места и файлов. */
data class CleanupRecord(val at: Long, val bytes: Long, val files: Int)

@Singleton
class CleanupHistory @Inject constructor(private val dao: CleanupHistoryDao) {
    val records: Flow<List<CleanupRecord>> = dao.observeRecent(KEEP).map { list -> list.map { CleanupRecord(it.at, it.bytes, it.files) } }

    suspend fun add(bytes: Long, files: Int, at: Long = System.currentTimeMillis()) {
        dao.insert(CleanupHistoryEntity(at = at, bytes = bytes, files = files))
        dao.trim(KEEP)
    }

    private companion object {
        const val KEEP = 50
    }
}
