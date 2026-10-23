package io.quietfiles.core.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.quietfiles.core.data.index.ContentIndex
import io.quietfiles.core.data.index.ContentIndexDatabase
import io.quietfiles.core.storage.StorageVolumes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContentIndexTest {

    @get:Rule val tmp = TemporaryFolder()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = Room.inMemoryDatabaseBuilder(context, ContentIndexDatabase::class.java).allowMainThreadQueries().build()
    private val index = ContentIndex(db.index(), StorageVolumes(context), Dispatchers.Unconfined)

    @After
    fun close() = db.close()

    private fun write(path: String, text: String): File = File(tmp.root, path).apply {
        parentFile!!.mkdirs()
        writeText(text)
    }

    @Test
    fun findsWordsByPrefixIgnoringCaseAndYo() = runTest {
        write("Documents/План.txt", "Квартальный отчёт по продажам: рост 12%")
        write("Documents/config.yaml", "server:\n  host: nas.local")
        write("Documents/photo.jpg", "не текст")
        val pass = index.update(listOf(tmp.root))
        assertThat(pass.indexed).isEqualTo(2)

        val hits = index.search("ОТЧЕТ продаж")
        assertThat(hits.map { File(it.path).name }).containsExactly("План.txt")
        // Найденное в отрывке отмечено \u0001…\u0002.
        assertThat(hits.single().snippet).contains("\u0001отчет\u0002")
        assertThat(index.search("nas").map { File(it.path).name }).containsExactly("config.yaml")
        assertThat(index.search("").isEmpty()).isTrue()
        assertThat(index.search("\"; DROP TABLE")).isEmpty()
    }

    @Test
    fun secondPassReadsOnlyChangesAndDropsDeleted() = runTest {
        val a = write("a.txt", "альфа")
        val b = write("b.txt", "бета")
        index.update(listOf(tmp.root))
        assertThat(index.update(listOf(tmp.root)).indexed).isEqualTo(0)

        a.writeText("гамма")
        a.setLastModified(a.lastModified() + 2_000)
        b.delete()
        val pass = index.update(listOf(tmp.root))
        assertThat(pass.indexed).isEqualTo(1)
        assertThat(pass.removed).isEqualTo(1)
        assertThat(index.search("альфа")).isEmpty()
        assertThat(index.search("гамма")).hasSize(1)
        assertThat(index.search("бета")).isEmpty()
    }

    @Test
    fun matchQueryQuotesEveryWord() {
        assertThat(ContentIndex.matchQuery("Отчёт  за-квартал")).isEqualTo("\"отчет*\" \"за*\" \"квартал*\"")
        assertThat(ContentIndex.matchQuery("  -- ")).isNull()
    }
}
