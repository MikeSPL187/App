package io.quietfiles.core.storage

import android.content.Context
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import io.quietfiles.core.common.IoDispatcher
import io.quietfiles.core.model.FileNames
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Удаление геоданных и серийных номеров камеры при отправке (F8.4, F14). Оригинал не меняется:
 * другим приложениям уходит копия из кеша, который очищается раз в сутки.
 */
@Singleton
class LocationStripper @Inject constructor(
    @ApplicationContext context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val shareDir = File(context.cacheDir, SHARE_DIR)

    /** Путь, который можно отправлять: оригинал, если в нём нет координат, иначе очищенная копия. */
    suspend fun forSharing(path: String): String = withContext(io) {
        if (FileNames.extension(path).lowercase() !in WRITABLE) return@withContext path
        if (!hasPrivateData(path)) return@withContext path
        val copy = File(File(shareDir, UUID.randomUUID().toString()), FileNames.nameOf(path))
        try {
            copy.parentFile!!.mkdirs()
            File(path).copyTo(copy)
            strip(copy.path)
            copy.path
        } catch (e: Exception) {
            copy.parentFile?.deleteRecursively()
            // Лучше не отправить, чем отправить координаты, которые пользователь просил убрать.
            throw StorageException.Io("Cannot remove location from ${FileNames.nameOf(path)}", e)
        }
    }

    /** Есть ли в фото координаты или серийные номера. */
    suspend fun hasPrivateData(path: String): Boolean = withContext(io) {
        if (FileNames.extension(path).lowercase() !in WRITABLE) return@withContext false
        runCatching {
            val exif = ExifInterface(path)
            exif.hasLocation() || SERIAL_TAGS.any { exif.getAttribute(it) != null }
        }.getOrDefault(false)
    }

    /** Убирает те же поля из самого файла — по явной просьбе пользователя («Убрать метаданные»). */
    suspend fun stripInPlace(path: String) = withContext(io) { strip(path) }

    private fun strip(path: String) {
        ExifInterface(path).apply {
            (GPS_TAGS + SERIAL_TAGS).forEach { setAttribute(it, null) }
            saveAttributes()
        }
    }

    /** Удаляет копии, которые уже точно забрали получатели. */
    suspend fun cleanup(olderThanMillis: Long = DAY_MS, now: Long = System.currentTimeMillis()) = withContext(io) {
        shareDir.listFiles()?.filter { now - it.lastModified() > olderThanMillis }?.forEach { it.deleteRecursively() }
    }

    private fun ExifInterface.hasLocation(): Boolean =
        latLong != null || getAttribute(ExifInterface.TAG_GPS_LATITUDE) != null || getAttribute(ExifInterface.TAG_GPS_LONGITUDE) != null

    companion object {
        /** Каталог в cacheDir, открытый для FileProvider (см. file_paths.xml). */
        const val SHARE_DIR = "share"
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** Форматы, в которые ExifInterface умеет записывать. */
        private val WRITABLE = setOf("jpg", "jpeg", "png", "webp")

        private val SERIAL_TAGS = listOf(
            ExifInterface.TAG_BODY_SERIAL_NUMBER, ExifInterface.TAG_LENS_SERIAL_NUMBER, ExifInterface.TAG_CAMERA_OWNER_NAME,
            ExifInterface.TAG_IMAGE_UNIQUE_ID,
        )

        private val GPS_TAGS = listOf(
            ExifInterface.TAG_GPS_VERSION_ID, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_SATELLITES,
            ExifInterface.TAG_GPS_STATUS, ExifInterface.TAG_GPS_MEASURE_MODE, ExifInterface.TAG_GPS_DOP,
            ExifInterface.TAG_GPS_SPEED_REF, ExifInterface.TAG_GPS_SPEED, ExifInterface.TAG_GPS_TRACK_REF,
            ExifInterface.TAG_GPS_TRACK, ExifInterface.TAG_GPS_IMG_DIRECTION_REF, ExifInterface.TAG_GPS_IMG_DIRECTION,
            ExifInterface.TAG_GPS_MAP_DATUM, ExifInterface.TAG_GPS_DEST_LATITUDE_REF, ExifInterface.TAG_GPS_DEST_LATITUDE,
            ExifInterface.TAG_GPS_DEST_LONGITUDE_REF, ExifInterface.TAG_GPS_DEST_LONGITUDE,
            ExifInterface.TAG_GPS_DEST_BEARING_REF, ExifInterface.TAG_GPS_DEST_BEARING,
            ExifInterface.TAG_GPS_DEST_DISTANCE_REF, ExifInterface.TAG_GPS_DEST_DISTANCE,
            ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_AREA_INFORMATION,
            ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_DIFFERENTIAL, ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
        )
    }
}
