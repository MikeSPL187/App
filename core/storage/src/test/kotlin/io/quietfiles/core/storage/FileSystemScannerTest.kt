package io.quietfiles.core.storage

import com.google.common.truth.Truth.assertThat
import io.quietfiles.core.model.FileCategory
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileSystemScannerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun findsCategorySkippingHiddenAndAppData() {
        val root = tmp.root
        fun touch(path: String) = File(root, path).apply { parentFile!!.mkdirs(); writeText("x") }
        touch("DCIM/Camera/a.jpg")
        touch("Pictures/Отпуск/б.png")
        touch("Download/doc.pdf")
        touch(".thumbnails/t.jpg")
        touch("Android/data/com.app/cache/c.jpg")
        touch("Pictures/.hidden.jpg")

        val found = FileSystemScanner.scan(listOf(root), accept = { it.category == FileCategory.IMAGE })

        assertThat(found.map { it.name }).containsExactly("a.jpg", "б.png")
    }

    @Test
    fun respectsLimitAndDepth() {
        val deep = File(tmp.root, "1/2/3/4").apply { mkdirs() }
        File(deep, "deep.jpg").writeText("x")
        repeat(5) { File(tmp.root, "p$it.jpg").writeText("x") }

        assertThat(FileSystemScanner.scan(listOf(tmp.root), accept = { true }, limit = 3)).hasSize(3)
        assertThat(FileSystemScanner.scan(listOf(tmp.root), accept = { it.name == "deep.jpg" }, maxDepth = 2)).isEmpty()
        assertThat(FileSystemScanner.scan(listOf(tmp.root), accept = { it.name == "deep.jpg" }, maxDepth = 4)).hasSize(1)
    }
}
