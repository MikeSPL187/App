package io.quietfiles.feature.search

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.quietfiles.core.data.repository.SearchHistoryRepository
import io.quietfiles.core.data.repository.SettingsRepository
import io.quietfiles.core.model.FileCategory
import io.quietfiles.core.model.FileEntry
import io.quietfiles.core.model.StorageVolumeInfo
import io.quietfiles.core.storage.FileIntents
import io.quietfiles.core.storage.MediaIndex
import io.quietfiles.core.storage.StorageProvider
import io.quietfiles.core.storage.StorageVolumes
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

/** Поиск по всему устройству или внутри папки [folder]. */
@Serializable
data class SearchRoute(val folder: String? = null)

enum class SearchScope { EVERYWHERE, FOLDER }

enum class TypeFilter(val categories: Set<FileCategory>) {
    ANY(emptySet()),
    IMAGES(setOf(FileCategory.IMAGE)),
    VIDEOS(setOf(FileCategory.VIDEO)),
    AUDIO(setOf(FileCategory.AUDIO)),
    DOCUMENTS(setOf(FileCategory.DOCUMENT, FileCategory.SHEET, FileCategory.PRESENTATION, FileCategory.TEXT)),
    ARCHIVES(setOf(FileCategory.ARCHIVE)),
    APKS(setOf(FileCategory.APK)),
}

enum class DateFilter { ANY, TODAY, WEEK, MONTH, YEAR }

enum class SizeFilter(val minBytes: Long) {
    ANY(0),
    OVER_10_MB(10L shl 20),
    OVER_100_MB(100L shl 20),
    OVER_1_GB(1L shl 30),
}

data class SearchFilters(
    val query: String = "",
    val scope: SearchScope = SearchScope.EVERYWHERE,
    val type: TypeFilter = TypeFilter.ANY,
    val date: DateFilter = DateFilter.ANY,
    val size: SizeFilter = SizeFilter.ANY,
) {
    val isEmpty: Boolean get() = query.isBlank() && type == TypeFilter.ANY && date == DateFilter.ANY && size == SizeFilter.ANY
}

data class SearchUiState(
    val folder: String?,
    val filters: SearchFilters,
    val results: List<FileEntry> = emptyList(),
    val searching: Boolean = false,
    val truncated: Boolean = false,
    val history: List<String> = emptyList(),
    /** Результаты по хранилищам (F6.4); внутренняя память первой. */
    val groups: List<ResultGroup> = emptyList(),
)

/** Результаты одного хранилища. [volume] = `null` — путь вне известных томов. */
data class ResultGroup(val volume: StorageVolumeInfo?, val entries: List<FileEntry>)

/** Группирует найденное по томам, сохраняя порядок внутри группы и порядок томов. */
internal fun groupByVolume(results: List<FileEntry>, volumes: List<StorageVolumeInfo>): List<ResultGroup> {
    fun volumeOf(path: String) = volumes
        .filter { path == it.rootPath || path.startsWith(it.rootPath.trimEnd('/') + "/") }
        .maxByOrNull { it.rootPath.length }
    val byVolume = results.groupBy { volumeOf(it.path) }
    return (volumes.map { it as StorageVolumeInfo? } + null)
        .mapNotNull { v -> byVolume[v]?.let { ResultGroup(v, it) } }
}

sealed interface SearchEvent {
    data class Launch(val intent: android.content.Intent) : SearchEvent
}

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val mediaIndex: MediaIndex,
    private val provider: StorageProvider,
    private val history: SearchHistoryRepository,
    private val settings: SettingsRepository,
    private val volumes: StorageVolumes,
) : ViewModel() {

    private val folder = savedStateHandle.toRoute<SearchRoute>().folder
    private val filters = MutableStateFlow(SearchFilters(scope = if (folder != null) SearchScope.FOLDER else SearchScope.EVERYWHERE))
    private val results = MutableStateFlow(Triple<List<FileEntry>, Boolean, Boolean>(emptyList(), false, false))

    private val events = Channel<SearchEvent>(Channel.BUFFERED)
    val eventFlow: Flow<SearchEvent> = events.receiveAsFlow()

    val state: StateFlow<SearchUiState> = kotlinx.coroutines.flow.combine(filters, results, history.recent) { f, (items, searching, truncated), recent ->
        SearchUiState(folder, f, items, searching, truncated, recent, groupByVolume(items, volumes.current()))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState(folder, filters.value))

    init {
        viewModelScope.launch {
            filters.debounce(250).distinctUntilChanged().collectLatest { f -> run(f) }
        }
    }

    fun setQuery(query: String) = filters.update { it.copy(query = query) }
    fun setScope(scope: SearchScope) = filters.update { it.copy(scope = scope) }
    fun setType(type: TypeFilter) = filters.update { it.copy(type = type) }
    fun setDate(date: DateFilter) = filters.update { it.copy(date = date) }
    fun setSize(size: SizeFilter) = filters.update { it.copy(size = size) }

    fun submit() {
        val q = filters.value.query
        viewModelScope.launch { history.add(q) }
    }

    fun removeHistory(query: String) {
        viewModelScope.launch { history.remove(query) }
    }

    fun clearHistory() {
        viewModelScope.launch { history.clear() }
    }

    fun openFile(entry: FileEntry) {
        submit()
        events.trySend(SearchEvent.Launch(FileIntents.view(context, entry)))
    }

    private suspend fun run(f: SearchFilters) {
        if (f.isEmpty) {
            results.value = Triple(emptyList(), false, false)
            return
        }
        results.update { it.copy(second = true) }
        val after = modifiedAfter(f.date)
        val items = when {
            f.scope == SearchScope.FOLDER && folder != null -> walk(folder, f, after)
            else -> runCatching {
                mediaIndex.searchByName(f.query, f.type.categories, after, f.size.minBytes, limit = LIMIT)
            }.getOrDefault(emptyList())
        }
        results.value = Triple(items, false, items.size >= LIMIT)
    }

    /** Рекурсивный поиск по папке с показом результатов по мере нахождения. */
    private suspend fun walk(root: String, f: SearchFilters, after: Long): List<FileEntry> {
        val needle = MediaIndex.normalize(f.query.trim())
        val showHidden = settings.settings.first().showHidden
        val found = ArrayList<FileEntry>()
        val stack = ArrayDeque<String>().apply { add(root) }
        var lastPublish = System.nanoTime()
        while (stack.isNotEmpty() && found.size < LIMIT) {
            coroutineContext.ensureActive()
            val dir = stack.removeLast()
            val children = runCatching { provider.list(dir, showHidden) }.getOrDefault(emptyList())
            for (child in children) {
                if (child.isDirectory) stack.add(child.path)
                val nameOk = needle.isEmpty() || MediaIndex.normalize(child.name).contains(needle)
                val typeOk = f.type == TypeFilter.ANY || (!child.isDirectory && child.category in f.type.categories)
                val dateOk = child.lastModified >= after
                val sizeOk = f.size == SizeFilter.ANY || (!child.isDirectory && child.size >= f.size.minBytes)
                if (nameOk && typeOk && dateOk && sizeOk) found += child
            }
            if (System.nanoTime() - lastPublish > 200_000_000L) {
                lastPublish = System.nanoTime()
                results.value = Triple(found.toList(), true, false)
            }
        }
        return found
    }

    private fun modifiedAfter(date: DateFilter): Long {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val start = when (date) {
            DateFilter.ANY -> return 0
            DateFilter.TODAY -> today
            DateFilter.WEEK -> today.minusDays(6)
            DateFilter.MONTH -> today.minusMonths(1)
            DateFilter.YEAR -> today.minusYears(1)
        }
        return start.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    private companion object {
        const val LIMIT = 1000
    }
}
