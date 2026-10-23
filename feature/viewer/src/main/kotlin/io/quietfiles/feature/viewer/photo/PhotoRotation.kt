package io.quietfiles.feature.viewer.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import io.quietfiles.core.model.FileNames
import io.quietfiles.core.storage.StorageException
import java.io.File
import java.io.FileOutputStream

/** Поворот фото на 90° по часовой (F8.2) без потери качества. */
internal object PhotoRotation {

    fun isSupported(path: String) = FileNames.extension(path).lowercase() in setOf("jpg", "jpeg", "png")

    fun rotateClockwise(path: String) {
        val file = File(path)
        if (!file.canWrite()) throw StorageException.AccessDenied(path)
        when (FileNames.extension(path).lowercase()) {
            // JPEG: меняется только тег ориентации, пиксели не перекодируются.
            "jpg", "jpeg" -> ExifInterface(path).apply {
                // rotate() молча ничего не делает, если тега ориентации нет, — так у многих снимков.
                if (isFlipped) rotate(90) else setAttribute(ExifInterface.TAG_ORIENTATION, orientationFor((rotationDegrees + 90) % 360).toString())
                saveAttributes()
            }
            "png" -> rotatePng(file)
            else -> throw StorageException.Io("Rotation is not supported for $path")
        }
    }

    private fun orientationFor(degrees: Int) = when (degrees) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    /** PNG хранит пиксели без потерь, поэтому пересохранение ничего не портит. */
    private fun rotatePng(file: File) {
        val source = BitmapFactory.decodeFile(file.path) ?: throw StorageException.Io("Cannot decode ${file.path}")
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, Matrix().apply { postRotate(90f) }, true)
        val temp = File(file.parentFile, ".${file.name}.qfpart")
        try {
            FileOutputStream(temp).use { rotated.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!temp.renameTo(file)) throw StorageException.Io("Cannot replace ${file.path}")
        } finally {
            temp.delete()
            source.recycle()
            rotated.recycle()
        }
    }
}
