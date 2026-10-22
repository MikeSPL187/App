package io.quietfiles.core.operations

import io.quietfiles.core.model.OperationKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Что нужно сделать. Сериализуется, чтобы после перезапуска приложения можно было повторить прерванную операцию. */
@Serializable
sealed interface OperationRequest {
    val kind: OperationKind

    @Serializable
    @SerialName("copy")
    data class Copy(val sources: List<String>, val destination: String) : OperationRequest {
        override val kind get() = OperationKind.COPY
    }

    @Serializable
    @SerialName("move")
    data class Move(val sources: List<String>, val destination: String) : OperationRequest {
        override val kind get() = OperationKind.MOVE
    }

    /** Удаление навсегда, без корзины. */
    @Serializable
    @SerialName("delete")
    data class Delete(val paths: List<String>) : OperationRequest {
        override val kind get() = OperationKind.DELETE
    }

    @Serializable
    @SerialName("trash")
    data class Trash(val paths: List<String>) : OperationRequest {
        override val kind get() = OperationKind.TRASH
    }

    @Serializable
    @SerialName("restore")
    data class Restore(val trashIds: List<Long>) : OperationRequest {
        override val kind get() = OperationKind.RESTORE
    }

    @Serializable
    @SerialName("compress")
    data class Compress(val sources: List<String>, val target: String) : OperationRequest {
        override val kind get() = OperationKind.COMPRESS
    }

    /** [entries] — пути внутри архива; пусто — извлечь всё. */
    @Serializable
    @SerialName("extract")
    data class Extract(val archive: String, val destination: String, val entries: List<String> = emptyList()) :
        OperationRequest {
        override val kind get() = OperationKind.EXTRACT
    }

    /**
     * Сохранение того, чем поделилось другое приложение (F15.2). Для пользователя это копирование,
     * поэтому вид операции — [OperationKind.COPY]. Совпадающие имена не спрашиваются: файл получает
     * свободное имя «имя (1).ext».
     */
    @Serializable
    @SerialName("import")
    data class Import(val items: List<ImportItem>, val destination: String) : OperationRequest {
        override val kind get() = OperationKind.COPY
    }
}

/** Один принятый объект: содержимое по content-URI или просто текст. */
@Serializable
data class ImportItem(
    val name: String,
    val uri: String? = null,
    val text: String? = null,
    /** Размер, если источник его сообщил; −1 — неизвестен. */
    val size: Long = -1,
)

/** Связь исполнителя с очередью: прогресс, пауза и отмена, вопросы пользователю. */
interface OperationControl {
    /** Точка остановки: ждёт, пока операция на паузе, и бросает CancellationException при отмене. */
    suspend fun checkpoint()

    fun progress(update: (io.quietfiles.core.model.OperationProgress) -> io.quietfiles.core.model.OperationProgress)

    suspend fun decide(conflict: io.quietfiles.core.model.Conflict): io.quietfiles.core.model.ConflictDecision

    /** Временный файл, который пишется прямо сейчас; удаляется при восстановлении после сбоя. */
    fun setTempFile(path: String?)
}

/** Итог выполнения. */
data class OperationOutcome(
    val failedItems: Int = 0,
    val firstError: Throwable? = null,
    /** Пути, которые изменились: их нужно сообщить системному индексу. */
    val changedPaths: List<String> = emptyList(),
    val trashIds: List<Long> = emptyList(),
    /** Пары «старый путь → новый» для перемещённых элементов верхнего уровня. */
    val moved: List<Pair<String, String>> = emptyList(),
)

sealed class OperationException(message: String) : Exception(message) {
    class SelfCopy(val path: String) : OperationException("Destination is inside source: $path")
    class SameLocation(val path: String) : OperationException("Source and destination are the same: $path")
    class NotEnoughSpace(val required: Long, val available: Long) :
        OperationException("Not enough space: need $required, have $available")
    class ArchiveEncrypted(val path: String) : OperationException("Encrypted archive: $path")
    class ArchiveUnsupported(val path: String) : OperationException("Unsupported archive: $path")
    class ArchiveCorrupted(val path: String, cause: Throwable?) : OperationException("Corrupted archive: $path") {
        init { if (cause != null) initCause(cause) }
    }
    class UnsafeArchiveEntry(val entry: String) : OperationException("Entry escapes destination: $entry")
}
