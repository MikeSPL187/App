package io.quietfiles.core.operations

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Журнал активных операций на диске. Если процесс завершится посреди копирования, при следующем запуске
 * удаляются недописанные временные файлы, а операция показывается как прерванная (F5.10).
 */
@Singleton
class OperationJournal @Inject constructor(
    @ApplicationContext context: Context,
) {
    @Serializable
    private data class Entry(val id: Long, val request: OperationRequest, val tempFile: String? = null)

    private val file = File(context.filesDir, "operations/journal.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private val temps = HashMap<Long, String>()
    private var current: Map<Long, OperationRequest> = emptyMap()

    fun save(active: Map<Long, OperationRequest>) = synchronized(lock) {
        current = active
        temps.keys.retainAll(active.keys)
        write()
    }

    fun setTempFile(id: Long, path: String?) = synchronized(lock) {
        if (path == null) temps.remove(id) else temps[id] = path
        write()
    }

    /** Читает журнал прошлого запуска и удаляет оставшиеся временные файлы. */
    fun loadAndCleanup(): Map<Long, OperationRequest> = synchronized(lock) {
        if (!file.exists()) return emptyMap()
        val entries = runCatching { json.decodeFromString<List<Entry>>(file.readText()) }.getOrDefault(emptyList())
        entries.mapNotNull { it.tempFile }.forEach { path ->
            if (path.endsWith(".qfpart")) File(path).delete()
        }
        entries.associate { it.id to it.request }
    }

    private fun write() {
        val entries = current.map { (id, request) -> Entry(id, request, temps[id]) }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "journal.json.tmp")
        tmp.writeText(json.encodeToString(entries))
        tmp.renameTo(file)
    }
}
