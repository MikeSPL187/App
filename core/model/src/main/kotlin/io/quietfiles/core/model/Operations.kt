package io.quietfiles.core.model

enum class OperationKind { COPY, MOVE, DELETE, TRASH, RESTORE, COMPRESS, EXTRACT }

enum class ConflictChoice { REPLACE, SKIP, KEEP_BOTH }

/** Запрос на решение конфликта имён: файл с таким именем уже есть в папке назначения. */
data class Conflict(
    val source: FileEntry,
    val existing: FileEntry,
    val remaining: Int,
)

data class ConflictDecision(val choice: ConflictChoice, val applyToAll: Boolean)

enum class OperationStatus { QUEUED, RUNNING, PAUSED, WAITING_FOR_DECISION, COMPLETED, FAILED, CANCELLED }

data class OperationProgress(
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val itemsDone: Int = 0,
    val itemsTotal: Int = 0,
    val currentName: String? = null,
    val bytesPerSecond: Long = 0,
) {
    val fraction: Float
        get() = when {
            bytesTotal > 0 -> (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
            itemsTotal > 0 -> (itemsDone.toFloat() / itemsTotal).coerceIn(0f, 1f)
            else -> 0f
        }

    /** Оценка оставшегося времени в секундах или `null`, если скорость ещё неизвестна. */
    val secondsLeft: Long?
        get() = if (bytesPerSecond <= 0 || bytesTotal <= 0) null
        else ((bytesTotal - bytesDone).coerceAtLeast(0) / bytesPerSecond)
}

/** Снимок состояния одной операции для интерфейса. */
data class OperationSnapshot(
    val id: Long,
    val kind: OperationKind,
    val title: String,
    val sourceCount: Int,
    val destinationName: String?,
    val status: OperationStatus,
    val progress: OperationProgress,
    val conflict: Conflict? = null,
    val errorMessage: String? = null,
    val failedItems: Int = 0,
    val finishedAt: Long? = null,
    /** Для перемещения в корзину: идентификаторы записей, чтобы отменить действие. */
    val trashIds: List<Long> = emptyList(),
)

/** Запись о файле в корзине. */
data class TrashItem(
    val id: Long,
    val originalPath: String,
    val trashPath: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val deletedAt: Long,
)
