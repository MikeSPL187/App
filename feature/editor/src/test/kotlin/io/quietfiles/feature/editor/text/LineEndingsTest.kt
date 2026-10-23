package io.quietfiles.feature.editor.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LineEndingsTest {
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
