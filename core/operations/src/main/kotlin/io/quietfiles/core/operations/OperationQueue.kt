package io.quietfiles.core.operations

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.quietfiles.core.common.ApplicationScope
import io.quietfiles.core.data.repository.FavoritesRepository
import io.quietfiles.core.model.Conflict
import io.quietfiles.core.model.ConflictDecision
import io.quietfiles.core.model.FileNames
import io.quietfiles.core.model.OperationProgress
import io.quietfiles.core.model.OperationSnapshot
import io.quietfiles.core.model.OperationStatus
import io.quietfiles.core.storage.MediaIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Событие для интерфейса: операция завершилась (показать снэкбар «Вернуть», ошибку и т. п.). */
data class OperationFinished(val snapshot: OperationSnapshot, val request: OperationRequest)

/**
 * Очередь файловых операций (F5.3–F5.4). Операции выполняются по одной, в порядке добавления.
 * Пока есть активные операции, работает [OperationService], чтобы система не остановила процесс.
 */
@Singleton
class OperationQueue @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
    private val executor: FileOperationExecutor,
    private val mediaIndex: MediaIndex,
    private val favorites: FavoritesRepository,
    private val journal: OperationJournal,
) {
    private val ids = AtomicLong(System.currentTimeMillis())
    private val requests = HashMap<Long, OperationRequest>()
    private val pending = Channel<Long>(Channel.UNLIMITED)
    private val paused = MutableStateFlow<Set<Long>>(emptySet())
    private val decisions = HashMap<Long, CompletableDeferred<ConflictDecision>>()
    private var runningJob: Job? = null
    private var runningId: Long? = null

    private val _operations = MutableStateFlow<List<OperationSnapshot>>(emptyList())
    val operations: StateFlow<List<OperationSnapshot>> = _operations.asStateFlow()

    val hasActive: StateFlow<Boolean> = operations
        .map { list -> list.any { it.status.isActive } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val _finished = MutableSharedFlow<OperationFinished>(extraBufferCapacity = 8)
    val finished: SharedFlow<OperationFinished> = _finished.asSharedFlow()

    init {
        restoreInterrupted()
        scope.launch {
            for (id in pending) {
                if (snapshot(id)?.status == OperationStatus.QUEUED) run(id)
            }
        }
    }

    fun enqueue(request: OperationRequest): Long {
        val id = ids.incrementAndGet()
        synchronized(requests) { requests[id] = request }
        _operations.update { it + newSnapshot(id, request) }
        journal.save(activeRequests())
        pending.trySend(id)
        OperationService.start(context)
        return id
    }

    /** Пауза возможна только у выполняющейся операции: она остановится на ближайшей контрольной точке. */
    fun pause(id: Long) {
        if (snapshot(id)?.status != OperationStatus.RUNNING) return
        paused.update { it + id }
        setStatus(id) { it.copy(status = OperationStatus.PAUSED) }
    }

    fun resume(id: Long) {
        paused.update { it - id }
        setStatus(id) { if (it.status == OperationStatus.PAUSED) it.copy(status = OperationStatus.RUNNING) else it }
    }

    fun cancel(id: Long) {
        if (runningId == id) {
            runningJob?.cancel()
        } else {
            setStatus(id) { if (it.status == OperationStatus.QUEUED) it.copy(status = OperationStatus.CANCELLED) else it }
            journal.save(activeRequests())
        }
        decisions.remove(id)?.cancel()
        paused.update { it - id }
    }

    fun resolveConflict(id: Long, decision: ConflictDecision) {
        decisions.remove(id)?.complete(decision)
    }

    /** Повторить прерванную или завершившуюся с ошибкой операцию. */
    fun retry(id: Long): Long? {
        val request = synchronized(requests) { requests[id] } ?: return null
        dismiss(id)
        return enqueue(request)
    }

    /** Убрать завершённую операцию из листа. */
    fun dismiss(id: Long) {
        _operations.update { list -> list.filterNot { it.id == id && !it.status.isActive } }
    }

    fun clearFinished() = _operations.update { list -> list.filter { it.status.isActive } }

    fun requestOf(id: Long): OperationRequest? = synchronized(requests) { requests[id] }

    private suspend fun run(id: Long) {
        val request = requireNotNull(synchronized(requests) { requests[id] })
        setStatus(id) { it.copy(status = OperationStatus.RUNNING) }
        runningId = id
        val control = Control(id)
        var outcome: OperationOutcome? = null
        var error: Throwable? = null
        val job = scope.launch {
            try {
                outcome = executor.execute(request, control)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e
            }
        }
        runningJob = job
        job.join()
        runningId = null
        runningJob = null
        control.flush()

        val result = outcome
        val status = when {
            job.isCancelled -> OperationStatus.CANCELLED
            error != null -> OperationStatus.FAILED
            result != null && result.failedItems > 0 -> OperationStatus.FAILED
            else -> OperationStatus.COMPLETED
        }
        val message = (error ?: result?.firstError)?.let { ErrorMessages.of(context, it) }
        setStatus(id) {
            it.copy(
                status = status,
                errorMessage = message,
                failedItems = result?.failedItems ?: 0,
                trashIds = result?.trashIds.orEmpty(),
                conflict = null,
                finishedAt = System.currentTimeMillis(),
            )
        }
        journal.save(activeRequests())
        result?.let { r ->
            mediaIndex.notifyChanged(r.changedPaths)
            r.moved.forEach { (from, to) -> favorites.onMoved(from, to, FileNames.nameOf(to)) }
        }
        snapshot(id)?.let { _finished.tryEmit(OperationFinished(it, request)) }
    }

    private inner class Control(private val id: Long) : OperationControl {
        private var progress = OperationProgress()
        private var lastPublish = 0L
        private var speedWindowStart = System.nanoTime()
        private var speedWindowBytes = 0L
        private var speed = 0L

        override suspend fun checkpoint() {
            kotlinx.coroutines.yield()
            if (id in paused.value) paused.first { id !in it }
        }

        override fun progress(update: (OperationProgress) -> OperationProgress) {
            val before = progress.bytesDone
            progress = update(progress)
            speedWindowBytes += (progress.bytesDone - before).coerceAtLeast(0)
            val now = System.nanoTime()
            val window = now - speedWindowStart
            if (window >= 1_000_000_000L) {
                val current = speedWindowBytes * 1_000_000_000L / window
                speed = if (speed == 0L) current else (speed * 6 + current * 4) / 10
                speedWindowStart = now
                speedWindowBytes = 0
            }
            if (now - lastPublish >= 150_000_000L) {
                lastPublish = now
                flush()
            }
        }

        fun flush() {
            val p = progress.copy(bytesPerSecond = speed)
            setStatus(id) { it.copy(progress = p) }
        }

        override suspend fun decide(conflict: Conflict): ConflictDecision {
            flush()
            val deferred = CompletableDeferred<ConflictDecision>()
            decisions[id] = deferred
            setStatus(id) { it.copy(status = OperationStatus.WAITING_FOR_DECISION, conflict = conflict) }
            val decision = deferred.await()
            setStatus(id) { it.copy(status = OperationStatus.RUNNING, conflict = null) }
            return decision
        }

        override fun setTempFile(path: String?) = journal.setTempFile(id, path)
    }

    private fun newSnapshot(id: Long, request: OperationRequest) = OperationSnapshot(
        id = id,
        kind = request.kind,
        title = OperationTitles.title(context, request),
        sourceCount = request.itemCount,
        destinationName = request.destinationPath?.let(FileNames::nameOf),
        status = OperationStatus.QUEUED,
        progress = OperationProgress(),
    )

    private fun snapshot(id: Long) = _operations.value.firstOrNull { it.id == id }

    private fun setStatus(id: Long, transform: (OperationSnapshot) -> OperationSnapshot) =
        _operations.update { list -> list.map { if (it.id == id) transform(it) else it } }

    private fun activeRequests(): Map<Long, OperationRequest> {
        val active = _operations.value.filter { it.status.isActive }.map { it.id }.toSet()
        return synchronized(requests) { requests.filterKeys { it in active } }
    }

    /** F5.10: операции, которые шли в момент закрытия процесса, показываются как прерванные с кнопкой «Повторить». */
    private fun restoreInterrupted() {
        val interrupted = journal.loadAndCleanup()
        if (interrupted.isEmpty()) return
        val message = context.getString(R.string.op_error_interrupted)
        synchronized(requests) { requests.putAll(interrupted) }
        _operations.value = interrupted.map { (id, request) ->
            newSnapshot(id, request).copy(status = OperationStatus.FAILED, errorMessage = message, finishedAt = System.currentTimeMillis())
        }
        journal.save(emptyMap())
    }
}

val OperationStatus.isActive: Boolean
    get() = this == OperationStatus.QUEUED || this == OperationStatus.RUNNING ||
        this == OperationStatus.PAUSED || this == OperationStatus.WAITING_FOR_DECISION

val OperationRequest.itemCount: Int
    get() = when (this) {
        is OperationRequest.Copy -> sources.size
        is OperationRequest.Move -> sources.size
        is OperationRequest.Delete -> paths.size
        is OperationRequest.Trash -> paths.size
        is OperationRequest.Restore -> trashIds.size
        is OperationRequest.Compress -> sources.size
        is OperationRequest.Extract -> if (entries.isEmpty()) 1 else entries.size
        is OperationRequest.Import -> items.size
    }

val OperationRequest.destinationPath: String?
    get() = when (this) {
        is OperationRequest.Copy -> destination
        is OperationRequest.Move -> destination
        is OperationRequest.Compress -> FileNames.parentOf(target)
        is OperationRequest.Extract -> destination
        is OperationRequest.Import -> destination
        else -> null
    }
