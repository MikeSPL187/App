package io.quietfiles.core.storage

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import org.robolectric.RuntimeEnvironment
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocationStripperTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val stripper = LocationStripper(RuntimeEnvironment.getApplication(), Dispatchers.Unconfined)

    private fun jpeg(name: String, withLocation: Boolean): File {
        val file = File(tmp.root, name)
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_MAKE, "Pixel")
            if (withLocation) setLatLong(55.7558, 37.6173)
            saveAttributes()
        }
        return file
    }

    @Test
    fun copyLosesLocationButKeepsOtherTagsAndOriginal() = runTest {
        val original = jpeg("IMG_1.jpg", withLocation = true)

        val shared = File(stripper.forSharing(original.path))

        assertThat(shared.path).isNotEqualTo(original.path)
        assertThat(shared.name).isEqualTo("IMG_1.jpg")
        assertThat(ExifInterface(shared.path).latLong).isNull()
        assertThat(ExifInterface(shared.path).getAttribute(ExifInterface.TAG_MAKE)).isEqualTo("Pixel")
        assertThat(ExifInterface(original.path).latLong).isNotNull()
    }

    @Test
    fun filesWithoutLocationAreSharedAsIs() = runTest {
        val plain = jpeg("plain.jpg", withLocation = false)
        assertThat(stripper.forSharing(plain.path)).isEqualTo(plain.path)
        val pdf = File(tmp.root, "doc.pdf").apply { writeText("%PDF") }
        assertThat(stripper.forSharing(pdf.path)).isEqualTo(pdf.path)
    }
}
