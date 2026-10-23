package io.quietfiles.feature.editor.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EncodingsTest {

    private val russian = "Список покупок: молоко 2 л, хлеб, сыр. Позвонить маме вечером!\n"

    @Test
    fun detectsUtf8WithAndWithoutBom() {
        assertThat(Encodings.detect(russian.toByteArray())).isEqualTo(Detection(TextEncoding.UTF_8, false))
        val bom = Encodings.encode(russian, TextEncoding.UTF_8, withBom = true)
        assertThat(Encodings.detect(bom)).isEqualTo(Detection(TextEncoding.UTF_8, true))
        assertThat(Encodings.decode(bom, bom.size, TextEncoding.UTF_8)).isEqualTo(russian)
    }

    @Test
    fun detectsLegacyRussianEncodings() {
        for (enc in listOf(TextEncoding.WINDOWS_1251, TextEncoding.KOI8_R, TextEncoding.CP866)) {
            val bytes = russian.toByteArray(enc.charset)
            assertThat(Encodings.detect(bytes).encoding).isEqualTo(enc)
        }
    }

    @Test
    fun detectsUtf16() {
        val text = "Hello, world! Привет\n"
        assertThat(Encodings.detect(Encodings.encode(text, TextEncoding.UTF_16LE, true))).isEqualTo(Detection(TextEncoding.UTF_16LE, true))
        assertThat(Encodings.detect(text.toByteArray(Charsets.UTF_16LE)).encoding).isEqualTo(TextEncoding.UTF_16LE)
        assertThat(Encodings.detect(text.toByteArray(Charsets.UTF_16BE)).encoding).isEqualTo(TextEncoding.UTF_16BE)
    }

    @Test
    fun utf8ValidationRejectsInvalidAndAllowsTruncatedSample() {
        assertThat(Encodings.isValidUtf8(byteArrayOf(0xC3.toByte(), 0x28))).isFalse()
        assertThat(Encodings.isValidUtf8(byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()))).isFalse()
        val cut = "яяя".toByteArray().copyOf(5)
        assertThat(Encodings.isValidUtf8(cut)).isFalse()
        assertThat(Encodings.isValidUtf8(cut, allowTruncatedEnd = true)).isTrue()
    }

    @Test
    fun suggestsBetterEncodingForGarbledText() {
        val bytes = russian.toByteArray(TextEncoding.WINDOWS_1251.charset)
        assertThat(Encodings.suggest(bytes, bytes.size, TextEncoding.UTF_8)).isEqualTo(TextEncoding.WINDOWS_1251)
        assertThat(Encodings.suggest(bytes, bytes.size, TextEncoding.WINDOWS_1251)).isNull()
        val utf8 = russian.toByteArray()
        assertThat(Encodings.suggest(utf8, utf8.size, TextEncoding.WINDOWS_1251)).isEqualTo(TextEncoding.UTF_8)
    }

    @Test
    fun canEncodeDetectsLoss() {
        assertThat(Encodings.canEncode("Привет 😀", TextEncoding.WINDOWS_1251)).isFalse()
        assertThat(Encodings.canEncode("Привет", TextEncoding.KOI8_R)).isTrue()
    }

    @Test
    fun lineEndings() {
        assertThat(LineEnding.detect("a\r\nb\r\nc")).isEqualTo(LineEnding.CRLF)
        assertThat(LineEnding.detect("a\nb")).isEqualTo(LineEnding.LF)
        assertThat(LineEnding.detect("a\rb\rc")).isEqualTo(LineEnding.CR)
        assertThat(LineEnding.detect("one line")).isEqualTo(LineEnding.LF)
        assertThat(LineEnding.normalize("a\r\nb\rc")).isEqualTo("a\nb\nc")
        assertThat(LineEnding.apply("a\nb", LineEnding.CRLF)).isEqualTo("a\r\nb")
    }
}
