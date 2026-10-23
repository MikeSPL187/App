package io.quietfiles.feature.editor.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EditHistoryTest {

    @Test
    fun typingMergesIntoWordsAndUndoRedoRoundTrip() {
        val h = EditHistory()
        "hi there".forEachIndexed { i, c -> h.record(i, "", c.toString(), time = i * 100L) }
        val second = h.undo()!!
        assertThat(second.inserted).isEqualTo(" there")
        val first = h.undo()!!
        assertThat(first.inserted).isEqualTo("hi")
        assertThat(h.canUndo).isFalse()
        assertThat(h.redo()).isEqualTo(first)
        assertThat(h.canRedo).isTrue()
    }

    @Test
    fun pausesAndNewLinesSplitEdits() {
        val h = EditHistory()
        h.record(0, "", "a", time = 0)
        h.record(1, "", "b", time = 5_000)
        h.record(2, "", "\n", time = 5_100)
        assertThat(h.undo()!!.inserted).isEqualTo("\n")
        assertThat(h.undo()!!.inserted).isEqualTo("b")
        assertThat(h.undo()!!.inserted).isEqualTo("a")
    }

    @Test
    fun backspacesMerge() {
        val h = EditHistory()
        h.record(4, "o", "", time = 0)
        h.record(3, "l", "", time = 100)
        h.record(2, "l", "", time = 200)
        val e = h.undo()!!
        assertThat(e.start).isEqualTo(2)
        assertThat(e.removed).isEqualTo("llo")
    }

    @Test
    fun cleanStateFollowsSavePoint() {
        val h = EditHistory()
        assertThat(h.isClean).isTrue()
        h.record(0, "", "a", 0)
        assertThat(h.isClean).isFalse()
        h.markSaved()
        assertThat(h.isClean).isTrue()
        h.record(1, "", "b", 100)
        assertThat(h.isClean).isFalse()
        h.undo()
        h.undo()
        assertThat(h.isClean).isFalse()
        h.redo()
        assertThat(h.isClean).isFalse()
        h.redo()
        assertThat(h.isClean).isFalse()
    }

    @Test
    fun keepsAtLeastHundredSteps() {
        val h = EditHistory()
        repeat(300) { h.record(0, "", "line$it\n", time = it * 10_000L) }
        var steps = 0
        while (h.undo() != null) steps++
        assertThat(steps).isAtLeast(100)
    }
}
