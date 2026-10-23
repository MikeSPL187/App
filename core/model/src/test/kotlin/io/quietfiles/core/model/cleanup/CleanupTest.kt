package io.quietfiles.core.model.cleanup

import com.google.common.truth.Truth.assertThat
import io.quietfiles.core.model.FileEntry
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

class CleanupTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun file(path: String, bytes: ByteArray, modified: Long = 1_000): FileEntry {
        val f = File(tmp.root, path)
        f.parentFile.mkdirs()
        f.writeBytes(bytes)
        f.setLastModified(modified)
        return FileEntry(f.path, f.name, false, f.length(), f.lastModified())
    }

    /** Считает, сколько раз читали файлы целиком: полный хэш нужен только кандидатам. */
    private class CountingHasher : ContentHasher {
        val full = mutableListOf<String>()
        override fun edgeHash(path: String, size: Long, edge: Int) = FileContentHasher.edgeHash(path, size, edge)
        override fun fullHash(path: String): String = FileContentHasher.fullHash(path).also { full += path }
    }

    @Test
    fun findsDuplicatesAndKeepsCameraOriginal() {
        val photo = Random(1).nextBytes(300_000)
        val camera = file("DCIM/Camera/IMG_1.jpg", photo, modified = 5_000)
        val copy1 = file("Pictures/Telegram/photo.jpg", photo, modified = 1_000)
        val copy2 = file("Download/IMG_1 (1).jpg", photo, modified = 2_000)
        val other = file("Download/other.jpg", Random(2).nextBytes(300_000))
        val small = file("Download/a.txt", ByteArray(10))
        file("Download/b.txt", ByteArray(10))

        val hasher = CountingHasher()
        val groups = DuplicateFinder(hasher).find(listOf(camera, copy1, copy2, other, small))
        assertThat(groups).hasSize(1)
        // Снимок из камеры — оригинал, даже если копия старше.
        assertThat(groups[0].original.path).isEqualTo(camera.path)
        assertThat(groups[0].copies.map { it.path }).containsExactly(copy1.path, copy2.path)
        assertThat(groups[0].reclaimable).isEqualTo(600_000)
        // У «другого» файла того же размера иные края: целиком его не читали.
        assertThat(hasher.full).doesNotContain(other.path)
    }

    @Test
    fun sameEdgesDifferentMiddleAreNotDuplicates() {
        val a = ByteArray(400_000)
        val b = a.copyOf().also { it[200_000] = 1 }
        val fa = file("a.bin", a)
        val fb = file("b.bin", b)
        val hasher = CountingHasher()
        assertThat(DuplicateFinder(hasher).find(listOf(fa, fb))).isEmpty()
        assertThat(hasher.full).containsExactly(fa.path, fb.path)
    }

    @Test
    fun withoutCameraOldestIsOriginal() {
        val data = Random(3).nextBytes(300_000)
        val newer = file("Download/x.pdf", data, modified = 9_000)
        val older = file("Documents/x.pdf", data, modified = 1_000)
        val groups = DuplicateFinder().find(listOf(newer, older))
        assertThat(groups.single().original.path).isEqualTo(older.path)
    }

    @Test
    fun cancellationStopsScan() {
        val data = Random(4).nextBytes(300_000)
        val files = listOf(file("1.bin", data), file("2.bin", data))
        var calls = 0
        val finder = DuplicateFinder(checkCancelled = { if (++calls > 1) throw InterruptedException() })
        runCatching { finder.find(files) }.also { assertThat(it.exceptionOrNull()).isInstanceOf(InterruptedException::class.java) }
    }

    @Test
    fun selectionDefaultsToSafeCategoriesAndExclusions() {
        val apk = CleanupFile(FileEntry("/d/app.apk", "app.apk", false, 100, 0))
        val big = CleanupFile(FileEntry("/d/movie.mkv", "movie.mkv", false, 1_000, 0))
        val dup = CleanupFile(FileEntry("/d/copy.jpg", "copy.jpg", false, 10, 0))
        val sections = listOf(
            CleanupSection(CleanupCategory.INSTALLED_APKS, listOf(apk)),
            CleanupSection(CleanupCategory.LARGE_FILES, listOf(big)),
            CleanupSection(CleanupCategory.DUPLICATES, listOf(dup)),
        )
        var selection = CleanupSelection()
        assertThat(selection.selectedBytes(sections)).isEqualTo(110)
        selection = selection.toggleFile(dup.path)
        assertThat(selection.selectedBytes(sections)).isEqualTo(100)
        selection = selection.toggleCategory(CleanupCategory.LARGE_FILES)
        assertThat(selection.selectedFiles(sections).map { it.second.path }).containsExactly(apk.path, big.path)
    }

    @Test
    fun fileInTwoSectionsIsCountedOnce() {
        val movie = CleanupFile(FileEntry("/d/Download/movie.mkv", "movie.mkv", false, 1_000, 0))
        val sections = listOf(
            CleanupSection(CleanupCategory.LARGE_FILES, listOf(movie)),
            CleanupSection(CleanupCategory.OLD_DOWNLOADS, listOf(movie)),
        )
        val all = CleanupSelection(categories = CleanupCategory.entries.toSet())
        assertThat(all.selectedBytes(sections)).isEqualTo(1_000)
    }

    @Test
    fun oldDownloads() {
        val now = 100 * CleanupRules.DAY
        assertThat(CleanupRules.isOldDownload(FileEntry("/a", "a", false, 1, now - 91 * CleanupRules.DAY), now)).isTrue()
        assertThat(CleanupRules.isOldDownload(FileEntry("/a", "a", false, 1, now - 10 * CleanupRules.DAY), now)).isFalse()
    }

    @Test
    fun folderSizesAreSortedAndRecursive() {
        file("root/Movies/a.mp4", ByteArray(5_000))
        file("root/Movies/Trip/b.mp4", ByteArray(7_000))
        file("root/Docs/c.txt", ByteArray(100))
        file("root/top.bin", ByteArray(1_000))
        val sizes = FolderSizes.children(File(tmp.root, "root"))
        assertThat(sizes.map { it.name }).containsExactly("Movies", "top.bin", "Docs").inOrder()
        assertThat(sizes.first().bytes).isEqualTo(12_000)
        assertThat(sizes.first().files).isEqualTo(2)
    }
}
