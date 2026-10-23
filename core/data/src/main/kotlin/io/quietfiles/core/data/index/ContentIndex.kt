package io.quietfiles.core.data.index

import io.quietfiles.core.common.IoDispatcher
import io.quietfiles.core.model.content.TextExtractor
import io.quietfiles.core.storage.StorageVolumes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** Итог прохода индексации: сколько файлов прочитано заново и сколько убрано. */
data class IndexPass(val indexed: Int, val removed: Int, val total: Int)

/** Запуск индексации вне расписания («Обновить сейчас»); расписание при зарядке задаёт приложение. */
fun interface ContentIndexScheduler {
    fun indexNow()
}

/**
 * Поиск по содержимому (F6.6): обход томов, извлечение текста и FTS-индекс в Room.
 * Повторный проход читает только новые и изменившиеся файлы.
 */
@Singleton
class ContentIndex @Inject constructor(
    private val dao: ContentIndexDao,
    private val volumes: StorageVolumes,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val mutex = Mutex()

    val indexedCount: Flow<Int> = dao.observeCount()

    suspend fun update(roots: List<File> = volumes.current().map { File(it.rootPath) }, now: Long = System.currentTimeMillis()): IndexPass =
        mutex.withLock {
            withContext(io) {
                val known = dao.files().associateBy { it.path }
                val seen = HashSet<String>()
                var indexed = 0
                walk(roots) { file ->
                    seen += file.path
                    val old = known[file.path]
                    if (old != null && old.size == file.length() && old.modified == file.lastModified()) return@walk
                    val text = TextExtractor.extract(file)?.takeIf { it.isNotBlank() }
                    if (text == null) {
                        old?.let { dao.remove(it) }
                        return@walk
                    }
                    dao.replace(file.path, TextExtractor.normalize(file.name), TextExtractor.normalize(text), file.length(), file.lastModified(), old?.rowId, now)
                    indexed++
                }
                val gone = known.values.filter { it.path !in seen }
                gone.forEach { dao.remove(it) }
                IndexPass(indexed, gone.size, seen.size)
            }
        }

    /** Слова запроса — каждое как префикс, все должны встретиться (F6.6). */
    suspend fun search(query: String, limit: Int = LIMIT): List<ContentHit> {
        val match = matchQuery(query) ?: return emptyList()
        return withContext(io) { runCatching { dao.search(match, limit) }.getOrDefault(emptyList()) }
    }

    suspend fun clear() = withContext(io) {
        dao.clearText()
        dao.clearFiles()
    }

    private suspend fun walk(roots: List<File>, visit: suspend (File) -> Unit) {
        val stack = ArrayDeque(roots)
        var count = 0
        while (stack.isNotEmpty() && count < MAX_FILES) {
            coroutineContext.ensureActive()
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (child.name.startsWith(".")) continue
                if (child.isDirectory) {
                    if (child.name == "Android" && child.parentFile in roots) continue
                    if (!Files.isSymbolicLink(child.toPath())) stack.addLast(child)
                } else if (TextExtractor.isIndexable(child.name, child.length())) {
                    visit(child)
                    count++
                }
            }
        }
    }

    companion object {
        const val LIMIT = 300
        private const val MAX_FILES = 50_000

        /** «отчёт квартал» → `"отчет*" "квартал*"`; кавычки и операторы FTS из запроса не проходят. */
        fun matchQuery(query: String): String? {
            val words = TextExtractor.normalize(query).split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return null
            return words.joinToString(" ") { "\"${it.lowercase()}*\"" }
        }
    }
}
