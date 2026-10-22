package io.quietfiles.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SortingTest {

    private fun file(name: String, size: Long = 0, modified: Long = 0) =
        FileEntry(path = "/x/$name", name = name, isDirectory = false, size = size, lastModified = modified)

    private fun dir(name: String) = FileEntry(path = "/x/$name", name = name, isDirectory = true, size = 0, lastModified = 0)

    @Test
    fun naturalOrderComparesNumbersByValueAndIgnoresCase() {
        val names = listOf("файл 10", "Файл 2", "файл 1", "ёлка", "Ель", "a")
        assertThat(names.sortedWith(NaturalOrder))
            .containsExactly("a", "ёлка", "Ель", "файл 1", "Файл 2", "файл 10").inOrder()
    }

    @Test
    fun foldersFirstThenByName() {
        val items = listOf(file("b.txt"), dir("Zeta"), file("a.txt"), dir("alpha"))
        val sorted = items.sortedWith(SortOrder().comparator()).map { it.name }
        assertThat(sorted).containsExactly("alpha", "Zeta", "a.txt", "b.txt").inOrder()
    }

    @Test
    fun sizeDescendingKeepsFoldersOnTop() {
        val items = listOf(file("small", 10), file("big", 1000), dir("folder"))
        val sorted = items.sortedWith(SortOrder(SortField.SIZE, ascending = false).comparator()).map { it.name }
        assertThat(sorted).containsExactly("folder", "big", "small").inOrder()
    }

    @Test
    fun foldersMixedWhenOptionDisabled() {
        val items = listOf(file("a", modified = 3), dir("b"), file("c", modified = 1))
        val sorted = items.sortedWith(SortOrder(SortField.DATE, ascending = true, foldersFirst = false).comparator())
        assertThat(sorted.map { it.name }).containsExactly("b", "c", "a").inOrder()
    }

    @Test
    fun categories() {
        assertThat(FileTypes.categoryOf("IMG_1.JPG")).isEqualTo(FileCategory.IMAGE)
        assertThat(FileTypes.categoryOf("setup.apk")).isEqualTo(FileCategory.APK)
        assertThat(FileTypes.categoryOf("noext", "video/mp4")).isEqualTo(FileCategory.VIDEO)
        assertThat(FileTypes.categoryOf("data.bin")).isEqualTo(FileCategory.OTHER)
        assertThat(FileTypes.mimeTypeOf("a.PDF")).isEqualTo("application/pdf")
    }
}
