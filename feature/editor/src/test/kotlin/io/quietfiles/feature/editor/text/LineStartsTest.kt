package io.quietfiles.feature.editor.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

class LineStartsTest {

    private fun LineStarts.all() = (0 until count).map { startOf(it) }

    @Test
    fun lineLookup() {
        val ls = LineStarts().apply { rebuild("ab\ncd\n\nef") }
        assertThat(ls.all()).containsExactly(0, 3, 6, 7).inOrder()
        assertThat(ls.lineOf(0)).isEqualTo(0)
        assertThat(ls.lineOf(2)).isEqualTo(0)
        assertThat(ls.lineOf(3)).isEqualTo(1)
        assertThat(ls.lineOf(6)).isEqualTo(2)
        assertThat(ls.lineOf(9)).isEqualTo(3)
    }

    @Test
    fun incrementalEditsMatchRebuild() {
        val random = Random(42)
        val sb = StringBuilder("first\nsecond\n\nthird line\n")
        val ls = LineStarts().apply { rebuild(sb) }
        repeat(2_000) {
            val start = random.nextInt(sb.length + 1)
            val removed = random.nextInt(minOf(6, sb.length - start) + 1)
            val inserted = buildString { repeat(random.nextInt(5)) { append(if (random.nextInt(3) == 0) '\n' else 'x') } }
            sb.replace(start, start + removed, inserted)
            ls.applyEdit(start, removed, inserted)
            val expected = LineStarts().apply { rebuild(sb) }
            assertThat(ls.all()).isEqualTo(expected.all())
        }
    }
}
