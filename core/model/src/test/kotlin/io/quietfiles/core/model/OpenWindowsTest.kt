package io.quietfiles.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OpenWindowsTest {

    private val docs = WindowPlace("FOLDER", "/s/Documents", title = "Documents")
    private val dl = WindowPlace("FOLDER", "/s/Download", title = "Download")

    @Test
    fun openSelectAndMove() {
        var w = OpenWindows.Initial.moveCurrent(docs).open(dl)
        assertThat(w.windows.map { it.place }).containsExactly(docs, dl).inOrder()
        assertThat(w.current.place).isEqualTo(dl)
        w = w.select(w.windows.first().id)
        assertThat(w.current.place).isEqualTo(docs)
        w = w.moveCurrent(WindowPlace("ARCHIVE", "/s/Documents/a.zip", "inner"))
        assertThat(w.windows.first().place.kind).isEqualTo("ARCHIVE")
        assertThat(w.windows.last().place).isEqualTo(dl)
        assertThat(w.select(999)).isEqualTo(w)
    }

    @Test
    fun closingCurrentPicksNeighbourAndLastBecomesRoot() {
        var w = OpenWindows.Initial.moveCurrent(docs).open(dl).open(WindowPlace.Root)
        val middle = w.windows[1].id
        w = w.select(middle).close(middle)
        // Справа было окно — оно и становится текущим.
        assertThat(w.current.place).isEqualTo(WindowPlace.Root)
        w = w.close(w.current.id)
        assertThat(w.current.place).isEqualTo(docs)
        w = w.close(w.current.id)
        assertThat(w.windows).hasSize(1)
        assertThat(w.current.place).isEqualTo(WindowPlace.Root)
    }

    @Test
    fun limitDropsOldestNonCurrent() {
        var w = OpenWindows.Initial
        repeat(OpenWindows.MAX + 2) { i -> w = w.open(WindowPlace("FOLDER", "/p$i")) }
        assertThat(w.windows).hasSize(OpenWindows.MAX)
        assertThat(w.current.place.path).isEqualTo("/p${OpenWindows.MAX + 1}")
        assertThat(w.windows.map { it.id }.toSet()).hasSize(OpenWindows.MAX)
    }
}
