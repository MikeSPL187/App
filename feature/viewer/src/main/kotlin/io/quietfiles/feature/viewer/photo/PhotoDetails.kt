package io.quietfiles.feature.viewer.photo

import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import io.quietfiles.core.model.FileNames
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Сведения о фото (F8.3, макет 19). */
data class PhotoDetails(
    val width: Int,
    val height: Int,
    /** Время съёмки из EXIF, иначе время изменения файла. */
    val takenAt: Long,
    val camera: String?,
    val aperture: Double?,
    val exposureSeconds: Double?,
    val iso: Int?,
    val focalLength: Double?,
    val hasLocation: Boolean,
    val hasPrivateData: Boolean,
) {
    val megapixels: Double get() = width.toLong() * height / 1_000_000.0

    companion object {
        private val EXIF_DATE = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
        private val PRIVATE_TAGS = listOf(
            ExifInterface.TAG_BODY_SERIAL_NUMBER, ExifInterface.TAG_LENS_SERIAL_NUMBER,
            ExifInterface.TAG_CAMERA_OWNER_NAME, ExifInterface.TAG_IMAGE_UNIQUE_ID,
        )

        fun read(path: String): PhotoDetails {
            val file = File(path)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val exif = runCatching { ExifInterface(path) }.getOrNull()
            // Снято «боком»: ширина и высота меняются местами.
            val rotated = exif?.rotationDegrees == 90 || exif?.rotationDegrees == 270
            val w = if (rotated) bounds.outHeight else bounds.outWidth
            val h = if (rotated) bounds.outWidth else bounds.outHeight
            val taken = exif?.let { e ->
                (e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: e.getAttribute(ExifInterface.TAG_DATETIME))?.let { parseDate(it) }
            }
            val make = exif?.getAttribute(ExifInterface.TAG_MAKE)?.trim().orEmpty()
            val model = exif?.getAttribute(ExifInterface.TAG_MODEL)?.trim().orEmpty()
            val camera = when {
                model.isEmpty() -> make.ifEmpty { null }
                make.isEmpty() || model.startsWith(make, ignoreCase = true) -> model
                else -> "$make $model"
            }
            val hasLocation = exif?.latLong != null
            return PhotoDetails(
                width = maxOf(w, 0),
                height = maxOf(h, 0),
                takenAt = taken ?: file.lastModified(),
                camera = camera,
                aperture = exif?.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)?.takeIf { it > 0 },
                exposureSeconds = exif?.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)?.takeIf { it > 0 },
                iso = exif?.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)?.takeIf { it > 0 },
                focalLength = exif?.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)?.takeIf { it > 0 },
                hasLocation = hasLocation,
                hasPrivateData = hasLocation || PRIVATE_TAGS.any { exif?.getAttribute(it) != null },
            )
        }

        internal fun parseDate(value: String): Long? = runCatching {
            LocalDateTime.parse(value.trim(), EXIF_DATE).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()

        /** «1/120 с» для коротких выдержек, «2 с» для длинных; [unit] — «с» или «s». */
        fun exposureLabel(seconds: Double, unit: String): String =
            if (seconds >= 1) "${trim(seconds)} $unit" else "1/${Math.round(1 / seconds)} $unit"

        /** «f/1.7 · 1/120 с · ISO 200 · 6,9 мм» без пустых частей. */
        fun cameraLine(d: PhotoDetails, secondsUnit: String, mmUnit: String): String? = listOfNotNull(
            d.aperture?.let { "f/${trim(it)}" },
            d.exposureSeconds?.let { exposureLabel(it, secondsUnit) },
            d.iso?.let { "ISO $it" },
            d.focalLength?.let { "${trim(it).replace('.', ',')} $mmUnit" },
        ).joinToString(" · ").ifEmpty { null }

        private fun trim(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else "%.1f".format(java.util.Locale.ROOT, v)

        fun isRotatable(path: String) = FileNames.extension(path).lowercase() in setOf("jpg", "jpeg", "png")
    }
}
