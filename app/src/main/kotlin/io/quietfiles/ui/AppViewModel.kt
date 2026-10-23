package io.quietfiles.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.quietfiles.R
import io.quietfiles.core.data.repository.SettingsRepository
import io.quietfiles.core.model.ConflictChoice
import io.quietfiles.core.model.ConflictDecision
import io.quietfiles.core.model.FileNames
import io.quietfiles.core.model.OperationKind
import io.quietfiles.core.model.OperationSnapshot
import io.quietfiles.core.model.OperationStatus
import io.quietfiles.core.operations.OperationQueue
import io.quietfiles.core.operations.OperationRequest
import io.quietfiles.core.operations.destinationPath
import io.quietfiles.feature.viewer.ViewerSessions
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Что открыть при запуске извне. */
sealed interface LaunchRequest {
    data object ShowOperations : LaunchRequest
    data class OpenFolder(val path: String) : LaunchRequest
}

/** Сообщение внизу экрана после операции: «2 объекта в корзине · Вернуть». */
data class AppMessage(val text: String, val action: AppMessageAction? = null)

sealed interface AppMessageAction {
    data class Undo(val trashIds: List<Long>) : AppMessageAction
    data object ShowOperations : AppMessageAction
    data class OpenFolder(val path: String) : AppMessageAction
}

@HiltViewModel
class AppViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val queue: OperationQueue,
    private val settings: SettingsRepository,
    /** Списки соседних файлов для просмотрщиков: открываются из любого раздела. */
    val viewerSessions: ViewerSessions,
) : ViewModel() {

    val operations: StateFlow<List<OperationSnapshot>> = queue.operations
    val hasActiveOperations: StateFlow<Boolean> = queue.hasActive

    private val messages = Channel<AppMessage>(Channel.BUFFERED)
    val messageFlow: Flow<AppMessage> = messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            queue.finished.collect { (snapshot, request) -> messageFor(snapshot, request)?.let { messages.send(it) } }
        }
    }

    fun pause(id: Long) = queue.pause(id)
    fun resume(id: Long) = queue.resume(id)
    fun cancel(id: Long) = queue.cancel(id)
    fun retry(id: Long) = queue.retry(id)
    fun dismiss(id: Long) = queue.dismiss(id)
    fun clearFinished() = queue.clearFinished()
    fun resolve(id: Long, choice: ConflictChoice, applyToAll: Boolean) =
        queue.resolveConflict(id, ConflictDecision(choice, applyToAll))

    fun undoTrash(ids: List<Long>) {
        if (ids.isNotEmpty()) queue.enqueue(OperationRequest.Restore(ids))
    }

    fun completeOnboarding() {
        viewModelScope.launch { settings.setOnboardingDone() }
    }

    private fun messageFor(s: OperationSnapshot, request: OperationRequest): AppMessage? {
        val r = context.resources
        return when (s.status) {
            OperationStatus.FAILED -> AppMessage(
                s.errorMessage ?: context.getString(R.string.msg_failed, s.title),
                AppMessageAction.ShowOperations,
            )
            OperationStatus.COMPLETED -> when (s.kind) {
                OperationKind.TRASH -> AppMessage(
                    r.getQuantityString(R.plurals.msg_trashed, s.sourceCount, s.sourceCount),
                    AppMessageAction.Undo(s.trashIds),
                )
                OperationKind.DELETE -> AppMessage(r.getQuantityString(R.plurals.msg_deleted, s.sourceCount, s.sourceCount))
                OperationKind.RESTORE -> AppMessage(r.getQuantityString(R.plurals.msg_restored, s.sourceCount, s.sourceCount))
                OperationKind.COPY, OperationKind.MOVE, OperationKind.COMPRESS, OperationKind.EXTRACT -> {
                    val dest = request.destinationPath
                    val text = when (s.kind) {
                        OperationKind.COPY -> context.getString(R.string.msg_copied, FileNames.nameOf(dest.orEmpty()))
                        OperationKind.MOVE -> context.getString(R.string.msg_moved, FileNames.nameOf(dest.orEmpty()))
                        OperationKind.COMPRESS -> context.getString(R.string.msg_compressed)
                        else -> context.getString(R.string.msg_extracted, FileNames.nameOf(dest.orEmpty()))
                    }
                    AppMessage(text, dest?.let { AppMessageAction.OpenFolder(it) })
                }
            }
            else -> null
        }
    }
}
