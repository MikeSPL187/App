package io.quietfiles.core.data.repository

import io.quietfiles.core.data.db.PlaybackPositionDao
import io.quietfiles.core.data.db.PlaybackPositionEntity
import io.quietfiles.core.model.media.ResumePolicy
import javax.inject.Inject
import javax.inject.Singleton

/** Позиции воспроизведения (F9.5): хранятся для последних [ResumePolicy.KEEP_FILES] файлов. */
@Singleton
class PlaybackPositions @Inject constructor(private val dao: PlaybackPositionDao) {

    /** Сохранённое место или null, если продолжать не с чего. */
    suspend fun get(path: String): Long? = dao.get(path)?.positionMs

    /**
     * Запоминает место; досмотренное до конца или едва начатое забывается, чтобы в следующий раз
     * файл начался сначала. [audio] — для музыки помним только длинные записи.
     */
    suspend fun save(path: String, positionMs: Long, durationMs: Long, audio: Boolean = false) {
        val remember = if (audio) ResumePolicy.shouldRememberAudio(positionMs, durationMs) else ResumePolicy.shouldRemember(positionMs, durationMs)
        if (!remember) {
            dao.delete(path)
            return
        }
        dao.upsert(PlaybackPositionEntity(path, positionMs, durationMs, System.currentTimeMillis()))
        dao.trim(ResumePolicy.KEEP_FILES)
    }

    suspend fun forget(path: String) = dao.delete(path)
}
