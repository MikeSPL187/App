package io.quietfiles.feature.editor.data

import com.google.common.truth.Truth.assertThat
import io.quietfiles.feature.editor.text.Encodings
import io.quietfiles.feature.editor.text.TextEncoding
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LargeTextReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val lines = (1..5_000).map { if (it % 7 == 0) "" else "строка $it: значение ${it * 3}" }

    private fun check(encoding: TextEncoding, bom: Boolean, ending: String = "\n") {
        val file = File(tmp.root, "${encoding.name}-$bom.log")
        file.writeBytes(Encodings.encode(lines.joinToString(ending, postfix = ending), encoding, bom))
        val reader = LargeTextReader(file.path, encoding).apply { index() }
        assertThat(reader.lineCount).isEqualTo(lines.size)
        assertThat(reader.lines(0, 3)).isEqualTo(lines.take(3))
        assertThat(reader.lines(4_998, 10)).isEqualTo(lines.takeLast(2))
        assertThat(reader.lines(1_234, 50)).isEqualTo(lines.subList(1_234, 1_284))
    }

    @Test fun utf8() = check(TextEncoding.UTF_8, bom = false)
    @Test fun utf8WithBomAndCrlf() = check(TextEncoding.UTF_8, bom = true, ending = "\r\n")
    @Test fun windows1251() = check(TextEncoding.WINDOWS_1251, bom = false)
    @Test fun utf16le() = check(TextEncoding.UTF_16LE, bom = true)
    @Test fun utf16be() = check(TextEncoding.UTF_16BE, bom = true)
}
