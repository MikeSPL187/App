package io.quietfiles.core.operations

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Архивы с паролем (F5.11): zip AES-256 создаём и открываем, 7z AES — открываем. */
class EncryptedArchivesTest {

    @get:Rule val tmp = TemporaryFolder()

    private val archives = ArchiveTools(Dispatchers.Unconfined)
    private val password = "Тихий-2026".toCharArray()

    private fun resource(name: String): File {
        val target = File(tmp.root, name)
        javaClass.classLoader!!.getResourceAsStream("archives/$name")!!.use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }

    private suspend fun encryptedZip(): File {
        val src = tmp.newFolder("src")
        File(src, "Отчёт.txt").writeText("Квартальный отчёт")
        File(src, "sub").mkdirs()
        File(src, "sub/data.bin").writeBytes(ByteArray(50_000) { it.toByte() })
        val zip = File(tmp.root, "secret.zip")
        archives.zip(listOf(src.path), zip.path, FakeControl(), password)
        return zip
    }

    @Test
    fun aesZipNeedsPasswordButShowsNames() = runTest {
        val zip = encryptedZip()
        assertThat(archives.isEncrypted(zip.path)).isTrue()
        // Имена в zip не шифруются: архив можно смотреть как папку без пароля.
        assertThat(archives.list(zip.path).map { it.path }).contains("src/Отчёт.txt")
        val out = tmp.newFolder("out")
        assertThrows(OperationException.ArchiveEncrypted::class.java) {
            kotlinx.coroutines.runBlocking { archives.extract(zip.path, out.path, emptyList(), FakeControl()) }
        }
        assertThrows(OperationException.WrongPassword::class.java) {
            kotlinx.coroutines.runBlocking { archives.extract(zip.path, out.path, emptyList(), FakeControl(), "не тот".toCharArray()) }
        }
        archives.extract(zip.path, out.path, emptyList(), FakeControl(), password)
        assertThat(File(out, "src/Отчёт.txt").readText()).isEqualTo("Квартальный отчёт")
        assertThat(File(out, "src/sub/data.bin").readBytes()).isEqualTo(ByteArray(50_000) { it.toByte() })
        // Неудачная попытка не оставила временных файлов.
        assertThat(out.walk().none { it.name.endsWith(".qfpart") }).isTrue()
    }

    @Test
    fun plainZipIsNotEncrypted() = runTest {
        val src = tmp.newFile("a.txt").apply { writeText("a") }
        val zip = File(tmp.root, "plain.zip")
        archives.zip(listOf(src.path), zip.path, FakeControl())
        assertThat(archives.isEncrypted(zip.path)).isFalse()
    }

    @Test
    fun sevenZipWithEncryptedNames() = runTest {
        val archive = resource("secret.7z")
        assertThat(archives.isEncrypted(archive.path)).isTrue()
        assertThrows(OperationException.ArchiveEncrypted::class.java) {
            kotlinx.coroutines.runBlocking { archives.list(archive.path) }
        }
        assertThrows(OperationException.WrongPassword::class.java) {
            kotlinx.coroutines.runBlocking { archives.list(archive.path, "неверно".toCharArray()) }
        }
        assertThat(archives.list(archive.path, password).map { it.path }).containsAtLeast("plan.txt", "docs/note.txt")
        val out = tmp.newFolder("out7")
        archives.extract(archive.path, out.path, emptyList(), FakeControl(), password)
        assertThat(File(out, "plan.txt").readText()).isEqualTo("Секретный план\n")
    }

    @Test
    fun sevenZipWithPlainNames() = runTest {
        val archive = resource("secret_names.7z")
        assertThat(archives.isEncrypted(archive.path)).isTrue()
        assertThat(archives.list(archive.path).map { it.path }).contains("plan.txt")
        val out = tmp.newFolder("out7n")
        assertThrows(OperationException.WrongPassword::class.java) {
            kotlinx.coroutines.runBlocking { archives.extract(archive.path, out.path, listOf("plan.txt"), FakeControl(), "x".toCharArray()) }
        }
        archives.extract(archive.path, out.path, listOf("plan.txt"), FakeControl(), password)
        assertThat(File(out, "plan.txt").readText()).isEqualTo("Секретный план\n")
    }

    @Test
    fun executorTakesPasswordFromMemoryAndForgetsWrongOne() = runTest {
        val passwords = ArchivePasswords()
        val executor = FileOperationExecutor(
            io.quietfiles.core.storage.LocalStorageProvider(Dispatchers.Unconfined),
            TrashManager(io.quietfiles.core.storage.LocalStorageProvider(Dispatchers.Unconfined), FakeTrashDao()),
            archives,
            ContentSource { throw UnsupportedOperationException() },
            passwords,
        )
        val src = tmp.newFile("doc.txt").apply { writeText("doc") }
        val target = File(tmp.root, "doc.zip").path
        passwords.put(target, password)
        executor.execute(OperationRequest.Compress(listOf(src.path), target, encrypted = true), FakeControl())
        // Пароль сжатия не задерживается в памяти.
        assertThat(passwords.get(target)).isNull()
        assertThat(archives.isEncrypted(target)).isTrue()

        passwords.put(target, "wrong".toCharArray())
        assertThrows(OperationException.WrongPassword::class.java) {
            kotlinx.coroutines.runBlocking {
                executor.execute(OperationRequest.Extract(target, tmp.newFolder("x").path), FakeControl())
            }
        }
        assertThat(passwords.get(target)).isNull()
    }
}
