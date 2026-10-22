package io.quietfiles.core.storage

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalStorageProviderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val provider = LocalStorageProvider(Dispatchers.Unconfined)

    @Test
    fun listsWithAndWithoutHiddenFiles() = runTest {
        val root = tmp.newFolder("root")
        File(root, "a.txt").writeText("hello")
        File(root, ".hidden").writeText("x")
        File(root, "Папка").mkdir()
        File(root, "Папка/inner.txt").writeText("1")
        File(root, "Папка/.secret").writeText("1")

        val visible = provider.list(root.path, includeHidden = false)
        assertThat(visible.map { it.name }).containsExactly("a.txt", "Папка")
        val folder = visible.first { it.isDirectory }
        assertThat(folder.childCount).isEqualTo(1)
        assertThat(visible.first { it.name == "a.txt" }.size).isEqualTo(5)

        val all = provider.list(root.path, includeHidden = true)
        assertThat(all.map { it.name }).containsExactly("a.txt", ".hidden", "Папка")
        assertThat(all.first { it.isDirectory }.childCount).isEqualTo(2)
    }

    @Test
    fun createAndRename() = runTest {
        val root = tmp.newFolder("root")
        val dir = provider.createDirectory(root.path, "  Новая папка ")
        assertThat(dir.name).isEqualTo("Новая папка")
        assertThrows(StorageException.AlreadyExists::class.java) {
            kotlinx.coroutines.runBlocking { provider.createDirectory(root.path, "Новая папка") }
        }
        val file = provider.createFile(root.path, "note.txt")
        val renamed = provider.rename(file.path, "Note.TXT")
        assertThat(renamed.name).isEqualTo("Note.TXT")
        assertThat(File(root, "Note.TXT").exists()).isTrue()
    }

    @Test
    fun rejectsInvalidNames() = runTest {
        val root = tmp.newFolder("root")
        assertThrows(StorageException.InvalidName::class.java) {
            kotlinx.coroutines.runBlocking { provider.createFile(root.path, "a/b") }
        }
        assertThrows(StorageException.InvalidName::class.java) {
            kotlinx.coroutines.runBlocking { provider.createDirectory(root.path, "..") }
        }
    }

    @Test
    fun deletesRecursively() = runTest {
        val root = tmp.newFolder("root")
        File(root, "a/b/c").mkdirs()
        File(root, "a/b/c/file.bin").writeBytes(ByteArray(10))
        provider.delete(File(root, "a").path)
        assertThat(File(root, "a").exists()).isFalse()
    }

    @Test
    fun moveFastRenamesWithinVolume() = runTest {
        val root = tmp.newFolder("root")
        File(root, "src.txt").writeText("data")
        val moved = provider.moveFast(File(root, "src.txt").path, File(root, "dst.txt").path)
        assertThat(moved).isTrue()
        assertThat(File(root, "dst.txt").readText()).isEqualTo("data")
    }

    @Test
    fun volumeRoots() {
        assertThat(LocalStorageProvider.volumeRootOf("/storage/emulated/0/Download/a.pdf")).isEqualTo("/storage/emulated/0")
        assertThat(LocalStorageProvider.volumeRootOf("/storage/1A2B-3C4D/DCIM")).isEqualTo("/storage/1A2B-3C4D")
        assertThat(LocalStorageProvider.volumeRootOf("/data/local/tmp")).isEqualTo("/")
    }

    @Test
    fun hiddenAndPrivatePathsAreFilteredFromIndex() {
        assertThat(MediaIndex.isHiddenOrPrivate("/storage/emulated/0/.thumbnails/1.jpg")).isTrue()
        assertThat(MediaIndex.isHiddenOrPrivate("/storage/emulated/0/Android/data/app/x")).isTrue()
        assertThat(MediaIndex.isHiddenOrPrivate("/storage/emulated/0/DCIM/IMG.jpg")).isFalse()
        assertThat(MediaIndex.normalize("Ёлка")).isEqualTo("елка")
    }
}
