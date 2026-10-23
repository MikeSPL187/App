package io.quietfiles.feature.cleaner

import android.content.Context
import android.content.Intent
import android.os.storage.StorageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.quietfiles.core.common.displayPath
import io.quietfiles.core.data.repository.CleanupHistory
import io.quietfiles.core.data.repository.CleanupRecord
import io.quietfiles.core.model.StorageSpace
import io.quietfiles.core.model.cleanup.CleanupCategory
import io.quietfiles.core.model.cleanup.CleanupSelection
import io.quietfiles.core.operations.cleanup.CleanupExecutor
import io.quietfiles.core.operations.cleanup.CleanupResult
import io.quietfiles.core.operations.cleanup.CleanupScanState
import io.quietfiles.core.operations.cleanup.CleanupScanner
import io.quietfiles.core.storage.StorageAccess
import io.quietfiles.core.storage.StorageProvider
import io.quietfiles.core.storage.StorageVolumes
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface CleanerEvent {
    data object Cleaned : CleanerEvent
}

/** «Инструменты» и очистка (F13, макет 07). Выбор общий для экрана пунктов и их подробностей. */
@HiltViewModel
class CleanerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scanner: CleanupScanner,
    private val executor: CleanupExecutor,
    private val provider: StorageProvider,
    private val volumes: StorageVolumes,
    history: CleanupHistory,
    private val store: CleanupSelectionStore,
) : ViewModel() {

    val scan: StateFlow<CleanupScanState> = scanner.state

    private val _selection = store.selection
    val selection: StateFlow<CleanupSelection> = _selection.asStateFlow()

    private val _space = MutableStateFlow<StorageSpace?>(null)
    val space: StateFlow<StorageSpace?> = _space.asStateFlow()

    val history: StateFlow<List<CleanupRecord>> = history.records.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val events = Channel<CleanerEvent>(Channel.BUFFERED)
    val eventFlow: Flow<CleanerEvent> = events.receiveAsFlow()

    val hasAccess: Boolean get() = StorageAccess.hasAllFilesAccess()

    init {
        refresh()
    }

    /** При открытии экрана: место на диске и поиск, если его ещё не было. */
    fun refresh() {
        viewModelScope.launch {
            _space.value = volumes.current().firstOrNull { it.isPrimary }?.let { provider.space(it.rootPath) }
        }
        if (hasAccess) scanner.scan()
    }

    fun rescan() = scanner.scan(force = true)

    val result: StateFlow<CleanupResult?> = store.result
    val systemCache: StateFlow<Intent?> = store.systemCache

    fun consumeResult() {
        store.result.value = null
    }

    fun consumeSystemCache() {
        store.systemCache.value = null
    }

    /** «Память/DCIM/Camera» вместо абсолютного пути. */
    fun folderLabel(path: String): String = context.displayPath(path, volumes.volumeFor(path))

    fun toggleCategory(category: CleanupCategory) = _selection.update { it.toggleCategory(category) }

    fun toggleFile(path: String) = _selection.update { it.toggleFile(path) }

    fun setLargeThreshold(bytes: Long) = scanner.setLargeThreshold(bytes)

    /** Очистить всё выбранное или, из подробностей, только [only]. */
    fun clean(only: CleanupCategory? = null) {
        val sections = scan.value.sections
        val selection = _selection.value.let { s -> if (only != null) s.copy(categories = s.categories.intersect(setOf(only))) else s }
        viewModelScope.launch {
            val result = executor.clean(selection, sections)
            if (result.files > 0) store.result.value = result
            // Кэш других приложений система очищает сама, после подтверждения пользователя.
            if (result.cacheSelected) store.systemCache.value = systemCacheIntent()
            events.send(CleanerEvent.Cleaned)
            _selection.value = CleanupSelection()
            scanner.scan()
            refreshSpace()
        }
    }

    private fun refreshSpace() {
        viewModelScope.launch {
            _space.value = volumes.current().firstOrNull { it.isPrimary }?.let { provider.space(it.rootPath) }
        }
    }

    /**
     * Системная очистка кэша всех приложений (Android 11+, нужен доступ ко всем файлам).
     * Без системного экрана — null.
     */
    private fun systemCacheIntent(): Intent? {
        val intent = Intent(StorageManager.ACTION_CLEAR_APP_CACHE)
        return intent.takeIf { it.resolveActivity(context.packageManager) != null }
    }
}
