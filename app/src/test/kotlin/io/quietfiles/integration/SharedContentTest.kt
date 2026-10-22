package io.quietfiles.integration

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedContentTest {

    private val own = setOf("io.quietfiles.files")

    @Test
    fun rejectsFileUrisAndOwnProvider() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(
            Intent.EXTRA_STREAM,
            arrayListOf(
                Uri.parse("content://com.whatsapp.provider/media/1"),
                Uri.parse("file:///data/data/io.quietfiles/databases/quietfiles.db"),
                Uri.parse("content://io.quietfiles.files/primary/secret.txt"),
                Uri.parse("content://com.whatsapp.provider/media/1"),
            ),
        )

        val content = SharedContent.from(intent, own)

        assertThat(content.uris.map { it.toString() }).containsExactly("content://com.whatsapp.provider/media/1")
    }

    @Test
    fun fallsBackToClipDataAndText() {
        val clip = ClipData.newRawUri("", Uri.parse("content://mail/attachment/7"))
        val withClip = Intent(Intent.ACTION_SEND).apply { clipData = clip }
        assertThat(SharedContent.from(withClip, own).uris).hasSize(1)

        val text = Intent(Intent.ACTION_SEND)
            .putExtra(Intent.EXTRA_TEXT, "Список покупок")
            .putExtra(Intent.EXTRA_SUBJECT, "Покупки")
        val content = SharedContent.from(text, own)
        assertThat(content.isEmpty).isFalse()
        assertThat(content.text).isEqualTo("Список покупок")
        assertThat(content.subject).isEqualTo("Покупки")
    }

    @Test
    fun emptyShareIsDetected() {
        assertThat(SharedContent.from(Intent(Intent.ACTION_SEND), own).isEmpty).isTrue()
    }
}
