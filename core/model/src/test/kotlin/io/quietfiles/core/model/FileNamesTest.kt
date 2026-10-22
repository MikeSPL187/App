package io.quietfiles.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FileNamesTest {

    @Test
    fun extensionAndBaseName() {
        assertThat(FileNames.extension("Отчёт.Q3.pdf")).isEqualTo("pdf")
        assertThat(FileNames.extension(".bashrc")).isEmpty()
        assertThat(FileNames.extension("file.")).isEmpty()
        assertThat(FileNames.baseName("архив.tar.gz")).isEqualTo("архив.tar")
    }

    @Test
    fun parentAndChild() {
        assertThat(FileNames.parentOf("/storage/emulated/0/Download")).isEqualTo("/storage/emulated/0")
        assertThat(FileNames.parentOf("/storage")).isEqualTo("/")
        assertThat(FileNames.parentOf("/")).isNull()
        assertThat(FileNames.child("/a/", "b")).isEqualTo("/a/b")
        assertThat(FileNames.child("/a", "b")).isEqualTo("/a/b")
    }

    @Test
    fun descendantCheckDoesNotMatchSiblingPrefix() {
        assertThat(FileNames.isSameOrDescendant("/a/b/c", "/a/b")).isTrue()
        assertThat(FileNames.isSameOrDescendant("/a/b", "/a/b/")).isTrue()
        assertThat(FileNames.isSameOrDescendant("/a/bc", "/a/b")).isFalse()
    }

    @Test
    fun uniqueNameAddsCounterBeforeExtension() {
        val taken = setOf("Отчёт.pdf", "Отчёт (1).pdf")
        assertThat(FileNames.uniqueName("Отчёт.pdf", false) { it in taken }).isEqualTo("Отчёт (2).pdf")
        assertThat(FileNames.uniqueName("Отчёт (1).pdf", false) { it in taken }).isEqualTo("Отчёт (2).pdf")
        assertThat(FileNames.uniqueName("Папка.v2", true) { it == "Папка.v2" }).isEqualTo("Папка.v2 (1)")
        assertThat(FileNames.uniqueName("free.txt", false) { false }).isEqualTo("free.txt")
    }

    @Test
    fun validation() {
        assertThat(FileNames.validate("  ")).isEqualTo(FileNames.Validation.Empty)
        assertThat(FileNames.validate("..")).isEqualTo(FileNames.Validation.DotOnly)
        assertThat(FileNames.validate("a/b")).isEqualTo(FileNames.Validation.ForbiddenChar('/'))
        assertThat(FileNames.validate("файл?.txt")).isEqualTo(FileNames.Validation.ForbiddenChar('?'))
        assertThat(FileNames.validate("я".repeat(200))).isEqualTo(FileNames.Validation.TooLong)
        assertThat(FileNames.validate("Нормальное имя 😀.txt")).isEqualTo(FileNames.Validation.Ok)
    }
}
