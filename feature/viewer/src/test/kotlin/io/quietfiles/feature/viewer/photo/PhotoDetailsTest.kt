package io.quietfiles.feature.viewer.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PhotoDetailsTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun image(name: String, width: Int, height: Int, format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG): File {
        val file = tmp.newFile(name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(format, 90, it) }
        return file
    }

    @Test
    fun readsSizeCameraAndLocation() {
        val file = image("a.jpg", 400, 300)
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_MAKE, "Google")
            setAttribute(ExifInterface.TAG_MODEL, "Pixel 9")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:10:21 18:45:00")
            setAttribute(ExifInterface.TAG_F_NUMBER, "1.7")
            setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, "200")
            setLatLong(55.75, 37.61)
            saveAttributes()
        }
        val d = PhotoDetails.read(file.path)
        assertThat(d.width).isEqualTo(400)
        assertThat(d.height).isEqualTo(300)
        assertThat(d.camera).isEqualTo("Google Pixel 9")
        assertThat(d.aperture).isWithin(0.01).of(1.7)
        assertThat(d.iso).isEqualTo(200)
        assertThat(d.hasLocation).isTrue()
        assertThat(d.hasPrivateData).isTrue()
        val expected = LocalDateTime.of(2026, 10, 21, 18, 45).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertThat(d.takenAt).isEqualTo(expected)
    }

    @Test
    fun rotatedPhotoSwapsSidesAndFallsBackToFileDate() {
        val file = image("b.jpg", 400, 300)
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        file.setLastModified(1_700_000_000_000)
        val d = PhotoDetails.read(file.path)
        assertThat(d.width).isEqualTo(300)
        assertThat(d.height).isEqualTo(400)
        assertThat(d.takenAt).isEqualTo(1_700_000_000_000)
        assertThat(d.camera).isNull()
        assertThat(d.hasPrivateData).isFalse()
    }

    @Test
    fun serialNumberIsPrivateDataWithoutLocation() {
        val file = image("c.jpg", 10, 10)
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_BODY_SERIAL_NUMBER, "SN123")
            saveAttributes()
        }
        val d = PhotoDetails.read(file.path)
        assertThat(d.hasLocation).isFalse()
        assertThat(d.hasPrivateData).isTrue()
    }

    @Test
    fun cameraNameDoesNotRepeatMaker() {
        val file = image("d.jpg", 10, 10)
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_MAKE, "samsung")
            setAttribute(ExifInterface.TAG_MODEL, "Samsung Galaxy S25")
            saveAttributes()
        }
        assertThat(PhotoDetails.read(file.path).camera).isEqualTo("Samsung Galaxy S25")
    }

    @Test
    fun exposureAndCameraLine() {
        assertThat(PhotoDetails.exposureLabel(0.008333, "с")).isEqualTo("1/120 с")
        assertThat(PhotoDetails.exposureLabel(2.0, "s")).isEqualTo("2 s")
        assertThat(PhotoDetails.exposureLabel(1.5, "s")).isEqualTo("1.5 s")
        val d = PhotoDetails(1, 1, 0, null, 1.7, 0.008333, 200, 6.9, hasLocation = false, hasPrivateData = false)
        assertThat(PhotoDetails.cameraLine(d, "с", "мм")).isEqualTo("f/1.7 · 1/120 с · ISO 200 · 6,9 мм")
        assertThat(PhotoDetails.cameraLine(d.copy(aperture = null, exposureSeconds = null, iso = null, focalLength = null), "с", "мм")).isNull()
    }

    @Test
    fun parseDateRejectsGarbage() {
        assertThat(PhotoDetails.parseDate("0000:00:00 00:00:00")).isNull()
        assertThat(PhotoDetails.parseDate("not a date")).isNull()
    }

    @Test
    fun jpegRotationChangesOnlyOrientationTag() {
        val file = image("e.jpg", 40, 20)
        val before = BitmapFactory.decodeFile(file.path)
        PhotoRotation.rotateClockwise(file.path)
        assertThat(ExifInterface(file.path).rotationDegrees).isEqualTo(90)
        PhotoRotation.rotateClockwise(file.path)
        PhotoRotation.rotateClockwise(file.path)
        PhotoRotation.rotateClockwise(file.path)
        assertThat(ExifInterface(file.path).rotationDegrees).isEqualTo(0)
        // Пиксели не трогали: размеры кадра те же.
        val after = BitmapFactory.decodeFile(file.path)
        assertThat(after.width).isEqualTo(before.width)
        assertThat(after.height).isEqualTo(before.height)
    }

    @Test
    fun pngRotationTurnsPixelsAndLeavesNoTempFile() {
        val file = image("f.png", 40, 20, Bitmap.CompressFormat.PNG)
        PhotoRotation.rotateClockwise(file.path)
        val rotated = BitmapFactory.decodeFile(file.path)
        assertThat(rotated.width).isEqualTo(20)
        assertThat(rotated.height).isEqualTo(40)
        assertThat(tmp.root.list()!!.toList()).containsExactly("f.png")
    }

    @Test
    fun onlyLosslessFormatsAreRotatable() {
        assertThat(PhotoRotation.isSupported("/a/b.JPG")).isTrue()
        assertThat(PhotoRotation.isSupported("/a/b.png")).isTrue()
        assertThat(PhotoRotation.isSupported("/a/b.webp")).isFalse()
        assertThat(PhotoRotation.isSupported("/a/b.heic")).isFalse()
    }
}
