package io.quietfiles.feature.browser

import com.google.common.truth.Truth.assertThat
import io.quietfiles.feature.browser.BrowserViewModel.Companion.archiveBaseName
import io.quietfiles.feature.browser.BrowserViewModel.Companion.rangeSelection
import org.junit.Test

class SelectionTest {

    private val order = listOf("a", "b", "c", "d", "e")

    @Test
    fun firstLongPressIsPlainToggle() {
        assertThat(rangeSelection(order, emptySet(), null, "c")).isNull()
    }

    @Test
    fun longPressSelectsRangeFromAnchorInBothDirections() {
        assertThat(rangeSelection(order, setOf("b"), "b", "d")).containsExactly("b", "c", "d")
        assertThat(rangeSelection(order, setOf("d"), "d", "a")).containsExactly("a", "b", "c", "d")
    }

    @Test
    fun rangeKeepsEarlierSelectionAndFallsBackToLastSelected() {
        assertThat(rangeSelection(order, setOf("a", "c"), null, "e")).containsExactly("a", "c", "d", "e")
    }

    @Test
    fun longPressOnSelectedItemIsToggle() {
        assertThat(rangeSelection(order, setOf("b", "c"), "c", "b")).isNull()
    }

    @Test
    fun archiveNameDropsArchiveExtensions() {
        assertThat(archiveBaseName("Фото.zip")).isEqualTo("Фото")
        assertThat(archiveBaseName("backup.tar.gz")).isEqualTo("backup")
    }
}
