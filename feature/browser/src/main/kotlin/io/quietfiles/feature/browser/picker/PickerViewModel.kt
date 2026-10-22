package io.quietfiles.feature.browser.picker

import android.content.Context
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.quietfiles.core.data.repository.SettingsRepository
import io.quietfiles.core.model.FileEntry
import io.quietfiles.core.model.FileNames
import io.quietfiles.core.model.FileTypes
import io.quietfiles.core.model.SortOrder
import io.quietfiles.core.model.StorageVolumeInfo
import io.quietfiles.core.operations.ErrorMessages
import io.quietfiles.core.operations.TrashManager
import io.quietfiles.core.storage.StorageAccess
import io.quietfiles.core.storage.StorageProvider
import io.quietfiles.core.storage.StorageVolumes
import io.quietfiles.feature.browser.nameValidationMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Что выбирает пользователь: файлы для другого приложения (F15.1) или папку для сохранения (F15.2). */
enum class PickerMode { FILES, FOLDER }

data class PickerConfig(
    val mode: PickerMode,
    /** MIME-фильтры запроса; пусто — любые файлы. */
    val mimeTypes: List<String> = emptyList(),
    val multiple: Boolean = false,
)

/** Элемент списка; [enabled] = false — файл не подходит под фильтр и показан приглушённым. */
data class PickerItem(val entry: FileEntry, val enabled: Boolean)

data class PickerUiState(
    val config: PickerConfig = PickerConfig(PickerMode.FILES),
    val hasAccess: Boolean = true,
    val volumes: List<StorageVolumeInfo> = emptyList(),
    val path: String = "",
    val items: List<PickerItem> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
    /** Выбранные файлы в порядке выбора. */
    val selected: List<String> = emptyList(),
) {
    val volume: StorageVolumeInfo?
        get() = volumes.filter { FileNames.isSameOrDescendant(path, it.rootPath) }.maxByOrNull { it.rootPath.length }
}

@HiltViewModel
class PickerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val provider: StorageProvider,
    private val storageVolumes: StorageVolumes,
    private val settings: SettingsRepository,
    private val trash: TrashManager,
) : ViewModel() {

    private val _state = MutableStateFlow(PickerUiState(hasAccess = StorageAccess.hasAllFilesAccess()))
    val state: StateFlow<PickerUiState> = _state.asStateFlow()

    private val messages = Channel<String>(Channel.BUFFERED)
    val messageFlow: Flow<String> = messages.receiveAsFlow()

    private var configured = false
    private var loadJob: Job? = null

    /** Вызывается экраном один раз; после поворота экрана состояние уже есть и повторно не настраивается. */
    fun configure(config: PickerConfig) {
        if (configured) return
        configured = true
        _state.update { it.copy(config = config) }
        viewModelScope.launch {
            storageVolumes.observe().collect { volumes ->
                _state.update { it.copy(volumes = volumes) }
                val current = _state.value.path
                if (current.isEmpty() || volumes.none { FileNames.isSameOrDescendant(current, it.rootPath) }) {
                    open(startPath(volumes))
                }
            }
        }
    }

    fun open(path: String) {
        _state.update { it.copy(path = path, loading = true, failed = false) }
        load()
    }

    /** Шаг вверх по папкам. `false` — уже в корне тома, экран можно закрыть. */
    fun up(): Boolean {
        val s = _state.value
        val root = s.volume?.rootPath ?: return false
        if (s.path.trimEnd('/') == root.trimEnd('/')) return false
        open(FileNames.parentOf(s.path) ?: return false)
        return true
    }

    fun toggle(path: String) {
        _state.update { s ->
            s.copy(selected = if (path in s.selected) s.selected - path else s.selected + path)
        }
    }

    fun refreshAccess() {
        val granted = StorageAccess.hasAllFilesAccess()
        val wasGranted = _state.value.hasAccess
        _state.update { it.copy(hasAccess = granted) }
        if (granted && !wasGranted && _state.value.path.isNotEmpty()) load()
    }

    fun validationMessage(name: String): String? = nameValidationMessage(context, name)

    fun createFolder(name: String) {
        val dir = _state.value.path
        if (validationMessage(name) != null || dir.isEmpty()) return
        viewModelScope.launch {
            try {
                val created = provider.createDirectory(dir, name.trim())
                open(created.path)
            } catch (e: Exception) {
                messages.send(ErrorMessages.of(context, e))
            }
        }
    }

    private fun load() {
        val s = _state.value
        if (!s.hasAccess || s.path.isEmpty()) {
            _state.update { it.copy(loading = false) }
            return
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val showHidden = settings.settings.first().showHidden
            val result = runCatching {
                provider.list(s.path, showHidden)
                    .filterNot { trash.isInTrash(it.path) }
                    .sortedWith(SortOrder().comparator())
                    .map { PickerItem(it, isSelectable(it, s.config)) }
            }
            val path = _state.value.path
            if (path != s.path) return@launch
            _state.update { st ->
                st.copy(
                    items = result.getOrDefault(emptyList()),
                    loading = false,
                    failed = result.isFailure,
                )
            }
        }
    }

    private fun isSelectable(entry: FileEntry, config: PickerConfig): Boolean = when {
        entry.isDirectory -> true
        config.mode == PickerMode.FOLDER -> false
        else -> FileTypes.mimeMatchesAny(entry.mimeType ?: FileTypes.mimeTypeOf(entry.name), config.mimeTypes)
    }

    /** Начинаем с «Загрузок»: туда попадает большинство файлов, которые прикрепляют и сохраняют. */
    private fun startPath(volumes: List<StorageVolumeInfo>): String {
        val primary = volumes.firstOrNull { it.isPrimary } ?: volumes.firstOrNull() ?: return storageVolumes.primaryRoot
        val downloads = FileNames.child(primary.rootPath, Environment.DIRECTORY_DOWNLOADS)
        return if (java.io.File(downloads).isDirectory) downloads else primary.rootPath
    }
}
