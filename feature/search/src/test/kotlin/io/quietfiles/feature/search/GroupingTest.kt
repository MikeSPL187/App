package io.quietfiles.feature.search

import com.google.common.truth.Truth.assertThat
import io.quietfiles.core.model.FileEntry
import io.quietfiles.core.model.StorageVolumeInfo
import io.quietfiles.core.model.VolumeKind
import org.junit.Test

class GroupingTest {

    private val internal = StorageVolumeInfo("primary", VolumeKind.INTERNAL, "", "/storage/emulated/0", true, false)
    private val sd = StorageVolumeInfo("1A2B", VolumeKind.SD_CARD, "SD", "/storage/1A2B-3C4D", false, true)

    private fun file(path: String) = FileEntry(path, path.substringAfterLast('/'), false, 1, 0)

    @Test
    fun groupsByVolumeInVolumeOrder() {
        val results = listOf(
            file("/storage/1A2B-3C4D/DCIM/a.jpg"),
            file("/storage/emulated/0/Download/b.pdf"),
            file("/storage/emulated/0/c.txt"),
            file("/mnt/other/d.bin"),
        )

        val groups = groupByVolume(results, listOf(internal, sd))

        assertThat(groups.map { it.volume?.id }).containsExactly("primary", "1A2B", null).inOrder()
        assertThat(groups[0].entries.map { it.name }).containsExactly("b.pdf", "c.txt").inOrder()
    }

    @Test
    fun prefixOfAnotherVolumeIsNotConfused() {
        val tricky = StorageVolumeInfo("x", VolumeKind.USB, "", "/storage/emulated/01", false, true)
        val groups = groupByVolume(listOf(file("/storage/emulated/01/x.txt")), listOf(internal, tricky))
        assertThat(groups.single().volume?.id).isEqualTo("x")
    }

    @Test
    fun highlightFindsMatchIgnoringCaseAndYo() {
        val text = highlighted("Ёлка Новогодняя.jpg", "елка", androidx.compose.ui.graphics.Color.Red)
        assertThat(text.spanStyles.single().start).isEqualTo(0)
        assertThat(text.spanStyles.single().end).isEqualTo(4)
    }
}
