package io.quietfiles.feature.viewer.photo

import android.content.Context
import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.quietfiles.core.common.IoDispatcher
import io.quietfiles.core.data.repository.SettingsRepository
import io.quietfiles.core.model.FileCategory
import io.quietfiles.core.model.FileEntry
import io.quietfiles.core.model.FileNames
import io.quietfiles.core.model.NaturalOrder
import io.quietfiles.core.operations.ErrorMessages
import io.quietfiles.core.operations.OperationQueue
import io.quietfiles.core.operations.OperationRequest
import io.quietfiles.core.storage.FileIntents
import io.quietfiles.core.storage.LocationStripper
import io.quietfiles.core.storage.MediaIndex
import io.quietfiles.core.storage.StorageProvider
import io.quietfiles.core.storage.StorageVolumes
import io.quietfiles.core.common.displayPath
import io.quietfiles.feature.viewer.R
import io.quietfiles.core.common.ViewerSessions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import javax.inject.Inject

/** Просмотр фото (F8). [session] — список соседних файлов из [ViewerSessions]. */
@Serializable
data class PhotoRoute(val path: String, val session: Long = 0)

data class PhotoUiState(
    val photos: List<FileEntry> = emptyList(),
    val index: Int = 0,
    val loading: Boolean = true,
    val chromeVisible: Boolean = true,
    val slideshow: Boolean = false,
    val details: PhotoDetails? = null,
    val showDetails: Boolean = false,
    val confirmDelete: Boolean = false,
    val useTrash: Boolean = true,
    val stripOnShare: Boolean = true,
    /** Меняется после поворота, чтобы миниатюры и кадр перечитались с диска. */
    val versions: Map<String, Long> = emptyMap(),
) {
    val current: FileEntry? get() = photos.getOrNull(index)
}

sealed interface PhotoEvent {
    data class Launch(val intent: Intent) : PhotoEvent
    data class Message(val text: String) : PhotoEvent
    data class OpenFolder(val path: String) : PhotoEvent
    data object Close : PhotoEvent
}

@HiltViewModel
class PhotoViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    sessions: ViewerSessions,
    private val provider: StorageProvider,
    private val settings: SettingsRepository,
    private val queue: OperationQueue,
    private val stripper: LocationStripper,
    private val mediaIndex: MediaIndex,
    private val volumes: StorageVolumes,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<PhotoRoute>()
    private val _state = MutableStateFlow(PhotoUiState())
    val state: StateFlow<PhotoUiState> = _state.asStateFlow()

    private val events = Channel<PhotoEvent>(Channel.BUFFERED)
    val eventFlow: Flow<PhotoEvent> = events.receiveAsFlow()

    private var slideshowJob: Job? = null
    private var detailsJob: Job? = null

    init {
        viewModelScope.launch {
            val s = settings.settings.first()
            val paths = sessions.get(route.session)
            val photos = withContext(io) {
                val list = paths?.map(::File)?.filter { it.isFile }?.map { it.toEntry() }
                    ?: siblingsInFolder(route.path)
                list.filter { it.category == FileCategory.IMAGE || it.path == route.path }
                    .ifEmpty { listOf(File(route.path).toEntry()) }
            }
            val index = photos.indexOfFirst { it.path == route.path }.coerceAtLeast(0)
            _state.update {
                it.copy(photos = photos, index = index, loading = false, useTrash = s.useTrash, stripOnShare = s.stripLocationOnShare)
            }
            loadDetails()
        }
        viewModelScope.launch {
            settings.settings.collect { s -> _state.update { it.copy(stripOnShare = s.stripLocationOnShare, useTrash = s.useTrash) } }
        }
    }

    private suspend fun siblingsInFolder(path: String): List<FileEntry> {
        val parent = FileNames.parentOf(path) ?: return emptyList()
        return runCatching { provider.list(parent, includeHidden = false) }.getOrDefault(emptyList())
            .filter { !it.isDirectory && it.category == FileCategory.IMAGE }
            .sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
    }

    fun select(index: Int) {
        if (index == _state.value.index) return
        _state.update { it.copy(index = index, details = null) }
        loadDetails()
    }

    fun toggleChrome() {
        if (_state.value.slideshow) {
            stopSlideshow()
            return
        }
        _state.update { it.copy(chromeVisible = !it.chromeVisible) }
    }

    // ---- Сведения ----

    private fun loadDetails() {
        val path = _state.value.current?.path ?: return
        detailsJob?.cancel()
        detailsJob = viewModelScope.launch {
            val details = withContext(io) { runCatching { PhotoDetails.read(path) }.getOrNull() }
            if (_state.value.current?.path == path) _state.update { it.copy(details = details) }
        }
    }

    fun showDetails(show: Boolean) = _state.update { it.copy(showDetails = show) }

    /** Папка текущего фото для людей: «Память/DCIM/Camera». */
    fun folderLabel(path: String): String {
        val parent = FileNames.parentOf(path).orEmpty()
        return context.displayPath(parent, volumes.volumeFor(parent))
    }

    fun setStripOnShare(enabled: Boolean) {
        viewModelScope.launch { settings.setStripLocationOnShare(enabled) }
    }

    fun stripMetadata() {
        val photo = _state.value.current ?: return
        viewModelScope.launch {
            try {
                stripper.stripInPlace(photo.path)
                mediaIndex.notifyChanged(listOf(photo.path))
                bumpVersion(photo.path)
                loadDetails()
                events.send(PhotoEvent.Message(context.getString(R.string.photo_metadata_removed)))
            } catch (e: Exception) {
                events.send(PhotoEvent.Message(ErrorMessages.of(context, e)))
            }
        }
    }

    // ---- Действия ----

    fun share() {
        val photo = _state.value.current ?: return
        viewModelScope.launch {
            val path = try {
                if (_state.value.stripOnShare) stripper.forSharing(photo.path) else photo.path
            } catch (e: Exception) {
                events.send(PhotoEvent.Message(context.getString(R.string.photo_share_location_failed)))
                return@launch
            }
            events.send(PhotoEvent.Launch(FileIntents.share(context, listOf(photo.copy(path = path)), context.getString(R.string.photo_share))))
        }
    }

    /** «Установить как»: обои, фото контакта — выбирает система (ACTION_ATTACH_DATA). */
    fun setAs() {
        val photo = _state.value.current ?: return
        val uri = FileIntents.uriFor(context, photo.path)
        val intent = Intent(Intent.ACTION_ATTACH_DATA)
            .setDataAndType(uri, FileIntents.mimeOf(photo))
            .putExtra("mimeType", FileIntents.mimeOf(photo))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        events.trySend(PhotoEvent.Launch(Intent.createChooser(intent, context.getString(R.string.photo_set_as))))
    }

    fun openWith() {
        val photo = _state.value.current ?: return
        events.trySend(PhotoEvent.Launch(FileIntents.chooserForView(context, photo, context.getString(R.string.photo_open_with))))
    }

    fun openFolder() {
        val photo = _state.value.current ?: return
        FileNames.parentOf(photo.path)?.let { events.trySend(PhotoEvent.OpenFolder(it)) }
    }

    /** Поворот на 90° по часовой (F8.2): для JPEG — только тег EXIF, без перекодирования и потери качества. */
    fun rotate() {
        val photo = _state.value.current ?: return
        if (!PhotoRotation.isSupported(photo.path)) {
            events.trySend(PhotoEvent.Message(context.getString(R.string.photo_rotate_unsupported)))
            return
        }
        viewModelScope.launch {
            try {
                withContext(io) { PhotoRotation.rotateClockwise(photo.path) }
                mediaIndex.notifyChanged(listOf(photo.path))
                bumpVersion(photo.path)
                loadDetails()
            } catch (e: Exception) {
                events.send(PhotoEvent.Message(ErrorMessages.of(context, e)))
            }
        }
    }

    private fun bumpVersion(path: String) =
        _state.update { it.copy(versions = it.versions + (path to System.nanoTime())) }

    fun requestDelete() {
        viewModelScope.launch {
            if (settings.settings.first().confirmDelete) _state.update { it.copy(confirmDelete = true) } else delete()
        }
    }

    fun dismissDelete() = _state.update { it.copy(confirmDelete = false) }

    /** Удаление через корзину (или навсегда, если корзина выключена) и переход к соседнему фото. */
    fun delete() {
        val s = _state.value
        val photo = s.current ?: return
        queue.enqueue(if (s.useTrash) OperationRequest.Trash(listOf(photo.path)) else OperationRequest.Delete(listOf(photo.path)))
        val remaining = s.photos.filterNot { it.path == photo.path }
        if (remaining.isEmpty()) {
            events.trySend(PhotoEvent.Close)
            return
        }
        _state.update { it.copy(photos = remaining, index = s.index.coerceAtMost(remaining.size - 1), confirmDelete = false, details = null) }
        loadDetails()
    }

    // ---- Слайд-шоу ----

    fun startSlideshow() {
        if (_state.value.photos.size < 2) return
        _state.update { it.copy(slideshow = true, chromeVisible = false) }
        slideshowJob?.cancel()
        slideshowJob = viewModelScope.launch {
            while (true) {
                delay(SLIDE_MS)
                val s = _state.value
                select((s.index + 1) % s.photos.size)
            }
        }
    }

    fun stopSlideshow() {
        slideshowJob?.cancel()
        _state.update { it.copy(slideshow = false, chromeVisible = true) }
    }

    private fun File.toEntry() = FileEntry(path, name, false, length(), lastModified())

    private companion object {
        const val SLIDE_MS = 3_500L
    }
}
