package io.quietfiles.core.operations

import com.google.common.truth.Truth.assertThat
import io.quietfiles.core.model.BatchRename
import io.quietfiles.core.model.FileEntry
import io.quietfiles.core.model.RenamePreview
import io.quietfiles.core.model.RenameRules
import io.quietfiles.core.storage.LocalStorageProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BatchRenamerTest {

    @get:Rule val tmp = TemporaryFolder()

    private val renamer = BatchRenamer(LocalStorageProvider(Dispatchers.IO))

    private fun entries(vararg names: String) = names.map { name ->
        val f = File(tmp.root, name).apply { writeText(name) }
        FileEntry(f.path, f.name, false, f.length(), f.lastModified())
    }

    @Test
    fun numbersFilesKeepingContent() = runTest {
        val files = entries("b.jpg", "a.jpg", "c.jpg")
        val previews = BatchRename.preview(files, RenameRules(template = "Фото {n}", digits = 2), emptySet())
        val result = renamer.rename(previews)
        assertThat(result.error).isNull()
        assertThat(tmp.root.list()!!.sorted()).containsExactly("Фото 01.jpg", "Фото 02.jpg", "Фото 03.jpg")
        assertThat(File(tmp.root, "Фото 01.jpg").readText()).isEqualTo("b.jpg")
        assertThat(result.renamed.map { File(it.first).name }).containsExactly("b.jpg", "a.jpg", "c.jpg").inOrder()
    }

    @Test
    fun swapsNamesWithoutLosingFiles() = runTest {
        val (a, b) = entries("a.txt", "b.txt")
        renamer.rename(
            listOf(
                RenamePreview(a.path, "a.txt", "b.txt", null),
                RenamePreview(b.path, "b.txt", "a.txt", null),
            ),
        )
        assertThat(File(tmp.root, "a.txt").readText()).isEqualTo("b.txt")
        assertThat(File(tmp.root, "b.txt").readText()).isEqualTo("a.txt")
    }

    @Test
    fun failureRollsBack() = runTest {
        val (a, b) = entries("a.txt", "b.txt")
        // Второй файл исчез до переименования — первый должен вернуться к прежнему имени.
        File(b.path).delete()
        val result = renamer.rename(
            listOf(
                RenamePreview(a.path, "a.txt", "x.txt", null),
                RenamePreview(b.path, "b.txt", "y.txt", null),
            ),
        )
        assertThat(result.error).isNotNull()
        assertThat(tmp.root.list()!!.toList()).containsExactly("a.txt")
    }

    @Test
    fun skipsUnchangedAndProblematic() = runTest {
        val files = entries("keep.txt", "x.txt")
        val result = renamer.rename(
            listOf(
                RenamePreview(files[0].path, "keep.txt", "keep.txt", null),
                RenamePreview(files[1].path, "x.txt", "bad", io.quietfiles.core.model.RenameProblem.INVALID),
            ),
        )
        assertThat(result.renamed).isEmpty()
        assertThat(tmp.root.list()!!.sorted()).containsExactly("keep.txt", "x.txt")
    }
}
