package io.quietfiles.core.operations

import com.google.common.truth.Truth.assertThat
import io.quietfiles.core.model.ConflictChoice
import io.quietfiles.core.model.ConflictDecision
import io.quietfiles.core.storage.LocalStorageProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileOperationExecutorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val provider = LocalStorageProvider(Dispatchers.Unconfined)
    private val dao = FakeTrashDao()
    private val trash = TrashManager(provider, dao)
    private val archives = ArchiveTools(Dispatchers.Unconfined)
    private val executor = FileOperationExecutor(provider, trash, archives)
    private lateinit var src: File
    private lateinit var dst: File

    @Before
    fun setUp() {
        src = tmp.newFolder("src")
        dst = tmp.newFolder("dst")
        trash.volumeRootOf = { tmp.root.path }
    }

    @Test
    fun copiesTreeAndReportsProgress() = runTest {
        File(src, "Фото/2026").mkdirs()
        File(src, "Фото/2026/a.jpg").writeBytes(ByteArray(1000))
        File(src, "Фото/b.txt").writeText("hello")
        val control = FakeControl()

        val outcome = executor.execute(OperationRequest.Copy(listOf(File(src, "Фото").path), dst.path), control)

        assertThat(outcome.failedItems).isEqualTo(0)
        assertThat(File(dst, "Фото/2026/a.jpg").length()).isEqualTo(1000)
        assertThat(File(dst, "Фото/b.txt").readText()).isEqualTo("hello")
        assertThat(control.progress.bytesTotal).isEqualTo(1005)
        assertThat(control.progress.bytesDone).isEqualTo(1005)
        assertThat(control.progress.itemsDone).isEqualTo(2)
        assertThat(File(src, "Фото/b.txt").exists()).isTrue()
        assertThat(dst.walk().none { it.name.endsWith(".qfpart") }).isTrue()
    }

    @Test
    fun copyIntoSameFolderKeepsBoth() = runTest {
        File(src, "Отчёт.pdf").writeText("v1")
        executor.execute(OperationRequest.Copy(listOf(File(src, "Отчёт.pdf").path), src.path), FakeControl())
        assertThat(File(src, "Отчёт (1).pdf").readText()).isEqualTo("v1")
    }

    @Test
    fun conflictChoicesAreApplied() = runTest {
        File(src, "a.txt").writeText("new-a")
        File(src, "b.txt").writeText("new-b")
        File(src, "c.txt").writeText("new-c")
        File(dst, "a.txt").writeText("old-a")
        File(dst, "b.txt").writeText("old-b")
        File(dst, "c.txt").writeText("old-c")
        val control = FakeControl(
            ArrayDeque(
                listOf(
                    ConflictDecision(ConflictChoice.REPLACE, false),
                    ConflictDecision(ConflictChoice.KEEP_BOTH, applyToAll = true),
                ),
            ),
        )
        executor.execute(OperationRequest.Copy(listOf("a.txt", "b.txt", "c.txt").map { File(src, it).path }, dst.path), control)

        assertThat(File(dst, "a.txt").readText()).isEqualTo("new-a")
        assertThat(File(dst, "b.txt").readText()).isEqualTo("old-b")
        assertThat(File(dst, "b (1).txt").readText()).isEqualTo("new-b")
        assertThat(File(dst, "c (1).txt").readText()).isEqualTo("new-c")
        // Третий конфликт решён правилом «для всех», без вопроса.
        assertThat(control.conflicts).hasSize(2)
    }

    @Test
    fun replacingFolderMergesContents() = runTest {
        File(src, "dir").mkdir()
        File(src, "dir/new.txt").writeText("n")
        File(dst, "dir").mkdir()
        File(dst, "dir/old.txt").writeText("o")
        executor.execute(
            OperationRequest.Copy(listOf(File(src, "dir").path), dst.path),
            FakeControl(ArrayDeque(listOf(ConflictDecision(ConflictChoice.REPLACE, false)))),
        )
        assertThat(File(dst, "dir").list()!!.toList()).containsExactly("new.txt", "old.txt")
    }

    @Test
    fun moveRemovesSource() = runTest {
        File(src, "dir/sub").mkdirs()
        File(src, "dir/sub/x.bin").writeBytes(ByteArray(10))
        val outcome = executor.execute(OperationRequest.Move(listOf(File(src, "dir").path), dst.path), FakeControl())
        assertThat(File(src, "dir").exists()).isFalse()
        assertThat(File(dst, "dir/sub/x.bin").length()).isEqualTo(10)
        assertThat(outcome.moved).containsExactly(File(src, "dir").path to File(dst, "dir").path)
    }

    @Test
    fun refusesToCopyFolderIntoItself() {
        File(src, "dir/inner").mkdirs()
        assertThrows(OperationException.SelfCopy::class.java) {
            kotlinx.coroutines.runBlocking {
                executor.execute(OperationRequest.Copy(listOf(File(src, "dir").path), File(src, "dir/inner").path), FakeControl())
            }
        }
    }

    @Test
    fun trashAndRestore() = runTest {
        File(src, "doc.txt").writeText("keep me")
        val outcome = executor.execute(OperationRequest.Trash(listOf(File(src, "doc.txt").path)), FakeControl())
        assertThat(File(src, "doc.txt").exists()).isFalse()
        assertThat(outcome.trashIds).hasSize(1)
        val items = trash.items.first()
        assertThat(items.single().name).isEqualTo("doc.txt")
        assertThat(File(items.single().trashPath).readText()).isEqualTo("keep me")

        File(src, "doc.txt").writeText("new file with same name")
        executor.execute(OperationRequest.Restore(outcome.trashIds), FakeControl())
        assertThat(File(src, "doc (1).txt").readText()).isEqualTo("keep me")
        assertThat(trash.items.first()).isEmpty()
    }

    @Test
    fun purgeExpiredDeletesOldTrash() = runTest {
        File(src, "old.txt").writeText("x")
        val id = trash.moveToTrash(File(src, "old.txt").path, 1, false, now = 0)
        val path = trash.byIds(listOf(id)).single().trashPath
        assertThat(trash.purgeExpired(30, now = 31L * 24 * 3600 * 1000)).isEqualTo(1)
        assertThat(File(path).exists()).isFalse()
    }

    @Test
    fun zipAndExtractRoundTrip() = runTest {
        File(src, "Проект/docs").mkdirs()
        File(src, "Проект/docs/readme.md").writeText("# Привет")
        File(src, "Проект/data.bin").writeBytes(ByteArray(4096) { it.toByte() })
        val zip = File(dst, "Проект.zip")
        executor.execute(OperationRequest.Compress(listOf(File(src, "Проект").path), zip.path), FakeControl())
        assertThat(zip.exists()).isTrue()

        val entries = archives.list(zip.path).map { it.path }
        assertThat(entries).containsAtLeast("Проект", "Проект/docs", "Проект/docs/readme.md", "Проект/data.bin")

        val out = tmp.newFolder("out")
        executor.execute(OperationRequest.Extract(zip.path, out.path), FakeControl())
        assertThat(File(out, "Проект/docs/readme.md").readText()).isEqualTo("# Привет")
        assertThat(File(out, "Проект/data.bin").readBytes()).isEqualTo(ByteArray(4096) { it.toByte() })

        val partial = tmp.newFolder("partial")
        executor.execute(OperationRequest.Extract(zip.path, partial.path, listOf("Проект/docs")), FakeControl())
        assertThat(File(partial, "docs/readme.md").exists()).isTrue()
        assertThat(File(partial, "data.bin").exists()).isFalse()
    }

    @Test
    fun zipSlipEntriesAreRejected() = runTest {
        val evil = File(dst, "evil.zip")
        java.util.zip.ZipOutputStream(evil.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("../../escaped.txt"))
            zos.write("pwned".toByteArray())
            zos.closeEntry()
        }
        val out = tmp.newFolder("out")
        assertThrows(OperationException.UnsafeArchiveEntry::class.java) {
            kotlinx.coroutines.runBlocking { archives.extract(evil.path, out.path, emptyList(), FakeControl()) }
        }
        assertThat(File(tmp.root, "escaped.txt").exists()).isFalse()
    }
}
