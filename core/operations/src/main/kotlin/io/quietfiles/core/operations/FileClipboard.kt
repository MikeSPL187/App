package io.quietfiles.core.operations

import io.quietfiles.core.model.FileEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Содержимое буфера: что скопировали или вырезали (F5.2). */
data class ClipboardContent(val items: List<FileEntry>, val cut: Boolean) {
    val totalSize: Long get() = items.sumOf { it.size }
}

/** Буфер «Копировать / Вырезать → Вставить». Живёт, пока открыто приложение, и переживает переходы между папками. */
@Singleton
class FileClipboard @Inject constructor(private val queue: OperationQueue) {
    private val _content = MutableStateFlow<ClipboardContent?>(null)
    val content: StateFlow<ClipboardContent?> = _content.asStateFlow()

    fun copy(items: List<FileEntry>) {
        _content.value = ClipboardContent(items, cut = false)
    }

    fun cut(items: List<FileEntry>) {
        _content.value = ClipboardContent(items, cut = true)
    }

    fun clear() {
        _content.value = null
    }

    /** Ставит копирование или перемещение в очередь. После вставки вырезанного буфер очищается. */
    fun pasteInto(destination: String): Long? {
        val clip = _content.value ?: return null
        val paths = clip.items.map { it.path }
        val id = queue.enqueue(
            if (clip.cut) OperationRequest.Move(paths, destination) else OperationRequest.Copy(paths, destination),
        )
        if (clip.cut) clear()
        return id
    }
}
