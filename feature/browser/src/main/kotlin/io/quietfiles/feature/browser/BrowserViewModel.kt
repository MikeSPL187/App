package io.quietfiles.feature.browser

import android.content.Context
import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.quietfiles.core.data.repository.FavoritesRepository
import io.quietfiles.core.data.repository.FolderPrefs
import io.quietfiles.core.data.repository.FolderPrefsRepository
import io.quietfiles.core.data.repository.SettingsRepository
import io.quietfiles.core.model.FileEntry
import io.quietfiles.core.model.FileNames
import io.quietfiles.core.model.RenamePreview
import io.quietfiles.core.model.ListDensity
import io.quietfiles.core.model.SortField
import io.quietfiles.core.model.SortOrder
import io.quietfiles.core.model.StorageVolumeInfo
import io.quietfiles.core.model.UserSettings
import io.quietfiles.core.model.ViewMode
import io.quietfiles.core.operations.ArchiveTools
import io.quietfiles.core.operations.ClipboardContent
import io.quietfiles.core.operations.ErrorMessages
import io.quietfiles.core.operations.FileClipboard
import io.quietfiles.core.operations.ArchivePasswords
import io.quietfiles.core.operations.BatchRenamer
import io.quietfiles.core.operations.OperationException
import io.quietfiles.core.operations.OperationQueue
import io.quietfiles.core.operations.OperationRequest
import io.quietfiles.core.operations.TrashManager
import io.quietfiles.core.storage.FileIntents
import io.quietfiles.core.storage.LocationStripper
import io.quietfiles.core.storage.MediaIndex
import io.quietfiles.core.storage.StorageAccess
import io.quietfiles.core.storage.StorageException
import io.quietfiles.core.storage.StorageProvider
import io.quietfiles.core.storage.StorageVolumes
import io.quietfiles.core.storage.watchDirectory
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import io.quietfiles.core.model.network.Connection
import io.quietfiles.core.model.network.NetworkPaths
import io.quietfiles.core.model.network.RemoteProblem
import io.quietfiles.core.model.VolumeKind
import io.quietfiles.core.network.ConnectionsRepository
import io.quietfiles.core.model.FileCategory
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/** Позиция в истории экрана: папка, место внутри архива или библиотечный раздел. */
data class Location(val kind: SourceKind, val path: String = "", val inner: String = "") {
    val prefsKey: String get() = if (kind == SourceKind.FOLDER) path else "lib:${kind.name}"
}

sealed interface LoadState {
    data object Loading : LoadState
    data class Ready(val items: List<BrowserItem>) : LoadState
    data object NoAccess : LoadState
    data object NotFound : LoadState
    data class Error(val message: String) : LoadState

    /** Сервер не открылся; [fixInSettings] — помогут правка подключения или проверка ключа (F16.6). */
    data class Network(val message: String, val connectionId: Long, val fixInSettings: Boolean) : LoadState

    /** Архив защищён паролем (F5.11); [wrong] — введённый пароль не подошёл. */
    data class NeedsPassword(val archivePath: String, val wrong: Boolean) : LoadState
}

/** Что сделать с архивом, когда пользователь введёт пароль. */
sealed interface PendingArchive {
    data class Extract(val entries: List<String>, val intoNewFolder: Boolean) : PendingArchive
    data class Preview(val item: BrowserItem) : PendingArchive

    /** Пароль нужен, чтобы вообще увидеть содержимое. */
    data object Open : PendingArchive
}

data class BrowserUiState(
    val location: Location,
    val canGoUp: Boolean,
    val volume: StorageVolumeInfo?,
    val load: LoadState,
    val sort: SortOrder,
    val viewMode: ViewMode,
    val compact: Boolean,
    val selection: Set<String>,
    val clipboard: ClipboardContent?,
    val favorites: Set<String>,
    val useTrash: Boolean,
    val confirmDelete: Boolean,
    val trashRetentionDays: Int,
    val tapIconToSelect: Boolean = true,
) {
    val items: List<BrowserItem> get() = (load as? LoadState.Ready)?.items.orEmpty()
    val selectedItems: List<BrowserItem> get() = items.filter { it.entry.path in selection }
    val selectionMode: Boolean get() = selection.isNotEmpty()
    val canCreate: Boolean get() = location.kind == SourceKind.FOLDER
    val canPaste: Boolean get() = location.kind == SourceKind.FOLDER && clipboard != null
}

/** Файл с сервера копируется во временную папку, чтобы открыть его просмотрщиком (F16.7). */
data class DownloadState(val name: String, val done: Long, val total: Long)

/** Свойства файла или группы файлов; размер папок считается в фоне. */
data class PropertiesState(
    val entries: List<FileEntry>,
    val totalBytes: Long? = null,
    val files: Int = 0,
    val folders: Int = 0,
)

sealed interface BrowserEvent {
    data class Launch(val intent: Intent) : BrowserEvent

    /** Открыть файл: экран сначала предлагает его встроенным просмотрщикам, потом другим приложениям. */
    data class OpenFile(val entry: FileEntry, val siblings: List<FileEntry>, val readOnly: Boolean, val asText: Boolean = false) : BrowserEvent

    /** Открыть временную копию файла с сервера в другом приложении. */
    data class OpenExternally(val entry: FileEntry) : BrowserEvent
    data class Message(val text: String) : BrowserEvent
    data object ShowOperations : BrowserEvent
    data class OpenRoute(val route: BrowseRoute) : BrowserEvent

    /** Архив распаковывается подозрительно сильно: спросить, продолжать ли (F10.3). */
    data class AskPassword(val archivePath: String, val pending: PendingArchive, val wrong: Boolean = false) : BrowserEvent
    data class ConfirmExtract(
        val archivePath: String,
        val entries: List<String>,
        val intoNewFolder: Boolean,
        val unpackedBytes: Long,
        val ratio: Long,
    ) : BrowserEvent
}

@OptIn(FlowPreview::class)
@HiltViewModel
class BrowserViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val loader: BrowserSourceLoader,
    private val provider: StorageProvider,
    private val settingsRepository: SettingsRepository,
    private val folderPrefs: FolderPrefsRepository,
    private val favoritesRepository: FavoritesRepository,
    private val queue: OperationQueue,
    private val clipboard: FileClipboard,
    private val volumes: StorageVolumes,
    private val trash: TrashManager,
    private val archives: ArchiveTools,
    private val mediaIndex: MediaIndex,
    private val locationStripper: LocationStripper,
    private val batchRenamer: BatchRenamer,
    private val passwords: ArchivePasswords,
    connectionsRepository: ConnectionsRepository,
) : ViewModel() {

    /** Имена серверов для заголовка и хлебных крошек (корень подключения — как том). */
    private val connections = connectionsRepository.connections
        .map { list -> list.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val route = savedStateHandle.toRoute<BrowseRoute>()
    private val history = MutableStateFlow(listOf(Location(route.kind, route.path, route.inner)))
    private val selection = MutableStateFlow<Set<String>>(emptySet())
    private val reload = MutableStateFlow(0)

    private val _download = MutableStateFlow<DownloadState?>(null)
    val download: StateFlow<DownloadState?> = _download
    private var downloadJob: Job? = null

    private val _properties = MutableStateFlow<PropertiesState?>(null)
    val properties: StateFlow<PropertiesState?> = _properties

    private val events = Channel<BrowserEvent>(Channel.BUFFERED)
    val eventFlow: Flow<BrowserEvent> = events.receiveAsFlow()

    private val location: Flow<Location> = history.map { it.last() }.distinctUntilChanged()

    private val settings = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())

    private val prefs: Flow<FolderPrefs> = combine(location, settings) { loc, s -> loc to s }
        .flatMapLatest { (loc, s) ->
            folderPrefs.observe(loc.prefsKey).map { saved -> saved ?: defaultPrefs(loc.kind, s) }
        }

    /** Сигналы перезагрузки: смена места, ручное обновление, изменение папки на диске, завершённая операция. */
    private val changes: Flow<Unit> = location.flatMapLatest { loc ->
        val watcher = if (loc.kind == SourceKind.FOLDER && File(loc.path).isDirectory) {
            watchDirectory(loc.path).debounce(300)
        } else {
            emptyFlow()
        }
        merge(watcher, queue.finished.map { }, trash.items.map { }.takeIfTrash(loc))
    }

    private val load: Flow<LoadState> = combine(
        location,
        settings.map { it.showHidden }.distinctUntilChanged(),
        reload,
        changes.onStart { emit(Unit) },
    ) { loc, hidden, _, _ -> loc to hidden }
        .mapLatest { (loc, hidden) -> loadState(loc, hidden) }
        .onStart { emit(LoadState.Loading) }

    val state: StateFlow<BrowserUiState> = combine(
        combine(location, history.map { it.size > 1 }, load, prefs, connections) { loc, up, ld, pr, conns -> Quad(loc, up, ld, pr, conns) },
        settings,
        selection,
        clipboard.content,
        favoritesRepository.paths,
    ) { (loc, up, ld, pr, conns), s, sel, clip, favs ->
        val sorted = (ld as? LoadState.Ready)?.let { LoadState.Ready(it.items.sortedWith(itemComparator(pr.sort))) } ?: ld
        BrowserUiState(
            location = loc,
            canGoUp = up,
            volume = if (loc.kind == SourceKind.FOLDER) volumeOf(loc.path, conns) else null,
            load = sorted,
            sort = pr.sort,
            viewMode = pr.viewMode,
            compact = s.listDensity == ListDensity.COMPACT,
            selection = sel,
            clipboard = clip,
            favorites = favs,
            // На серверах корзины нет: удаление всегда окончательное и всегда с подтверждением.
            useTrash = s.useTrash && !NetworkPaths.isNetwork(loc.path),
            confirmDelete = s.confirmDelete,
            trashRetentionDays = s.trashRetentionDays,
            tapIconToSelect = s.tapIconToSelect,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        BrowserUiState(
            location = history.value.last(), canGoUp = false, volume = null, load = LoadState.Loading,
            sort = SortOrder(), viewMode = ViewMode.LIST, compact = false, selection = emptySet(),
            clipboard = null, favorites = emptySet(), useTrash = true, confirmDelete = true, trashRetentionDays = 30,
        ),
    )

    private data class Quad(val a: Location, val b: Boolean, val c: LoadState, val d: FolderPrefs, val e: Map<Long, Connection>)

    private fun volumeOf(path: String, connections: Map<Long, Connection>): StorageVolumeInfo? {
        val id = NetworkPaths.connectionIdOf(path) ?: return volumes.volumeFor(path)
        val connection = connections[id] ?: return null
        return StorageVolumeInfo(
            id = "net-$id",
            kind = VolumeKind.NETWORK,
            label = connection.displayName,
            rootPath = NetworkPaths.rootOf(id),
            isPrimary = false,
            isRemovable = false,
        )
    }

    private fun Flow<Unit>.takeIfTrash(loc: Location): Flow<Unit> = if (loc.kind == SourceKind.TRASH) this else emptyFlow()

    private suspend fun loadState(loc: Location, showHidden: Boolean): LoadState {
        val network = NetworkPaths.connectionIdOf(loc.path).takeIf { loc.kind == SourceKind.FOLDER }
        if (loc.kind == SourceKind.FOLDER && network == null && !StorageAccess.hasAllFilesAccess()) return LoadState.NoAccess
        return try {
            LoadState.Ready(loader.load(loc.kind, loc.path, loc.inner, showHidden))
        } catch (e: StorageException.NotFound) {
            LoadState.NotFound
        } catch (e: OperationException.ArchiveEncrypted) {
            LoadState.NeedsPassword(e.path, wrong = false)
        } catch (e: OperationException.WrongPassword) {
            LoadState.NeedsPassword(e.path, wrong = true)
        } catch (e: StorageException.Remote) {
            val fix = e.problem !is RemoteProblem.Unreachable && e.problem !is RemoteProblem.Protocol
            LoadState.Network(ErrorMessages.remote(context, e.problem), e.connectionId, fix)
        } catch (e: StorageException.AccessDenied) {
            if (network != null) LoadState.Error(context.getString(R.string.network_access_denied)) else LoadState.NoAccess
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            LoadState.Error(ErrorMessages.of(context, e))
        }
    }

    private fun defaultPrefs(kind: SourceKind, s: UserSettings): FolderPrefs = when (kind) {
        SourceKind.FOLDER, SourceKind.ARCHIVE -> FolderPrefs(s.defaultSort, s.defaultViewMode)
        SourceKind.PHOTOS, SourceKind.VIDEOS -> FolderPrefs(SortOrder(SortField.DATE, ascending = false, foldersFirst = false), ViewMode.GRID)
        SourceKind.LARGE_FILES -> FolderPrefs(SortOrder(SortField.SIZE, ascending = false, foldersFirst = false), ViewMode.LIST)
        else -> FolderPrefs(SortOrder(SortField.DATE, ascending = false, foldersFirst = s.defaultSort.foldersFirst), ViewMode.LIST)
    }

    private fun itemComparator(sort: SortOrder): Comparator<BrowserItem> {
        val c = sort.comparator()
        return Comparator { a, b -> c.compare(a.entry, b.entry) }
    }

    // ---- Навигация внутри экрана ----

    fun open(item: BrowserItem) {
        val loc = history.value.last()
        val entry = item.entry
        when {
            selection.value.isNotEmpty() -> toggleSelection(entry.path)
            loc.kind == SourceKind.TRASH -> Unit
            loc.kind == SourceKind.ARCHIVE && entry.isDirectory ->
                push(Location(SourceKind.ARCHIVE, loc.path, item.archivePath.orEmpty()))
            loc.kind == SourceKind.ARCHIVE -> previewArchiveEntry(loc.path, item)
            entry.isDirectory -> push(Location(SourceKind.FOLDER, entry.path))
            ArchiveTools.isBrowsableArchive(entry.name) ->
                push(Location(SourceKind.ARCHIVE, entry.path, ""))
            // Видео и музыка с сервера играют потоком; остальное открывается временной копией.
            NetworkPaths.isNetwork(entry.path) && entry.category != FileCategory.VIDEO && entry.category != FileCategory.AUDIO ->
                openNetworkCopy(entry, external = false)
            else -> {
                val siblings = state.value.items.map { it.entry }.filterNot { it.isDirectory }
                events.trySend(BrowserEvent.OpenFile(entry, siblings, readOnly = false))
            }
        }
    }

    /** «Открыть расположение» из библиотеки, недавних или избранного. */
    fun openParent(entry: FileEntry) {
        val parent = FileNames.parentOf(entry.path) ?: return
        push(Location(SourceKind.FOLDER, parent))
    }

    fun openFolder(path: String) {
        val stack = history.value
        val index = stack.indexOfLast { it.kind == SourceKind.FOLDER && it.path == path }
        if (index >= 0) {
            history.value = stack.subList(0, index + 1)
            selection.value = emptySet()
        } else {
            push(Location(SourceKind.FOLDER, path))
        }
    }

    fun openArchiveDir(inner: String) {
        val loc = history.value.last()
        if (loc.kind != SourceKind.ARCHIVE) return
        val stack = history.value
        val index = stack.indexOfLast { it.kind == SourceKind.ARCHIVE && it.path == loc.path && it.inner == inner }
        if (index >= 0) history.value = stack.subList(0, index + 1) else push(loc.copy(inner = inner))
        selection.value = emptySet()
    }

    /** `true`, если шаг назад сделан внутри экрана. */
    fun back(): Boolean {
        if (selection.value.isNotEmpty()) {
            selection.value = emptySet()
            return true
        }
        val stack = history.value
        if (stack.size <= 1) return false
        history.value = stack.dropLast(1)
        return true
    }

    fun refresh() = reload.update { it + 1 }

    private fun push(location: Location) {
        history.update { it + location }
        selection.value = emptySet()
    }

    // ---- Выбор ----

    /** Последний элемент, отмеченный вручную: от него считается диапазон (F4.4). */
    private var selectionAnchor: String? = null

    fun toggleSelection(path: String) {
        selection.update { if (path in it) it - path else it + path }
        selectionAnchor = path.takeIf { it in selection.value }
    }

    /** Долгое нажатие: начинает выбор, а при уже выбранных элементах отмечает диапазон до нажатого. */
    fun longPress(path: String) {
        val order = state.value.items.map { it.entry.path }
        val range = rangeSelection(order, selection.value, selectionAnchor, path)
        if (range == null) toggleSelection(path) else selection.value = range
        if (range != null) selectionAnchor = path
    }

    fun selectAll() {
        selectionAnchor = null
        selection.value = state.value.items.map { it.entry.path }.toSet()
    }

    fun clearSelection() {
        selectionAnchor = null
        selection.value = emptySet()
    }

    // ---- Сортировка и вид ----

    fun setSort(sort: SortOrder) = savePrefs { it.copy(sort = sort) }

    fun setViewMode(mode: ViewMode) = savePrefs { it.copy(viewMode = mode) }

    private fun savePrefs(transform: (FolderPrefs) -> FolderPrefs) {
        val s = state.value
        viewModelScope.launch {
            folderPrefs.save(s.location.prefsKey, transform(FolderPrefs(s.sort, s.viewMode)))
        }
    }

    // ---- Буфер и операции ----

    fun copySelection(items: List<BrowserItem> = state.value.selectedItems) {
        clipboard.copy(items.map { it.entry })
        clearSelection()
    }

    fun cutSelection(items: List<BrowserItem> = state.value.selectedItems) {
        clipboard.cut(items.map { it.entry })
        clearSelection()
    }

    fun paste() {
        val loc = state.value.location
        if (loc.kind != SourceKind.FOLDER) return
        clipboard.pasteInto(loc.path)
        events.trySend(BrowserEvent.ShowOperations)
    }

    fun cancelClipboard() = clipboard.clear()

    fun delete(items: List<BrowserItem>, permanently: Boolean) {
        if (items.isEmpty()) return
        val paths = items.map { it.entry.path }
        val useTrash = !permanently && state.value.useTrash
        queue.enqueue(if (useTrash) OperationRequest.Trash(paths) else OperationRequest.Delete(paths))
        clearSelection()
    }

    fun restore(items: List<BrowserItem>) {
        val ids = items.mapNotNull { it.trash?.id }
        if (ids.isNotEmpty()) queue.enqueue(OperationRequest.Restore(ids))
        clearSelection()
    }

    fun deleteFromTrash(items: List<BrowserItem>) {
        val trashItems = items.mapNotNull { it.trash }
        viewModelScope.launch {
            runCatching { trash.deleteForever(trashItems) }
                .onFailure { message(ErrorMessages.of(context, it)) }
            refresh()
        }
        clearSelection()
    }

    fun emptyTrash() {
        viewModelScope.launch {
            runCatching { trash.emptyTrash() }.onFailure { message(ErrorMessages.of(context, it)) }
            refresh()
        }
    }

    fun compress(items: List<BrowserItem>, archiveName: String, password: CharArray? = null) {
        val loc = state.value.location
        val dir = if (loc.kind == SourceKind.FOLDER) loc.path else FileNames.parentOf(items.first().entry.path) ?: return
        val name = archiveName.trim().let { if (it.endsWith(".zip", ignoreCase = true)) it else "$it.zip" }
        if (FileNames.validate(name) != FileNames.Validation.Ok) {
            message(context.getString(R.string.browser_error_invalid_name))
            return
        }
        viewModelScope.launch {
            val taken = provider.list(dir, includeHidden = true).map { it.name }.toSet()
            val free = FileNames.uniqueName(name, false) { it in taken }
            val target = FileNames.child(dir, free)
            val encrypted = password != null && password.isNotEmpty()
            if (encrypted) passwords.put(target, password!!)
            password?.fill('\u0000')
            queue.enqueue(OperationRequest.Compress(items.map { it.entry.path }, target, encrypted))
            events.send(BrowserEvent.ShowOperations)
        }
        clearSelection()
    }

    /** Распаковка: в папку архива или в новую папку с именем архива. */
    fun extract(archivePath: String, entries: List<String>, intoNewFolder: Boolean, confirmed: Boolean = false) {
        val parent = FileNames.parentOf(archivePath) ?: return
        viewModelScope.launch {
            if (passwords.get(archivePath) == null && archives.isEncrypted(archivePath)) {
                events.send(BrowserEvent.AskPassword(archivePath, PendingArchive.Extract(entries, intoNewFolder)))
                return@launch
            }
            if (!confirmed) {
                val packed = provider.stat(archivePath)?.size ?: 0L
                val wanted = entries.map { it.trim('/') }
                val unpacked = runCatching { archives.list(archivePath, passwords.get(archivePath)) }.getOrDefault(emptyList())
                    .filter { e -> !e.isDirectory && (wanted.isEmpty() || wanted.any { e.path == it || e.path.startsWith("$it/") }) }
                    .sumOf { it.size.coerceAtLeast(0) }
                if (ArchiveTools.isSuspiciousRatio(unpacked, packed)) {
                    events.send(BrowserEvent.ConfirmExtract(archivePath, entries, intoNewFolder, unpacked, unpacked / packed))
                    return@launch
                }
            }
            val destination = if (intoNewFolder) {
                val base = archiveBaseName(FileNames.nameOf(archivePath))
                val taken = provider.list(parent, includeHidden = true).map { it.name }.toSet()
                val folder = FileNames.uniqueName(base, true) { it in taken }
                runCatching { provider.createDirectory(parent, folder) }.getOrNull()?.path ?: return@launch
            } else {
                parent
            }
            queue.enqueue(OperationRequest.Extract(archivePath, destination, entries))
            events.send(BrowserEvent.ShowOperations)
        }
        clearSelection()
    }

    fun rename(entry: FileEntry, newName: String) {
        val error = validationMessage(newName)
        if (error != null) {
            message(error)
            return
        }
        viewModelScope.launch {
            try {
                val renamed = provider.rename(entry.path, newName.trim())
                favoritesRepository.onMoved(entry.path, renamed.path, renamed.name)
                mediaIndex.notifyChanged(listOf(entry.path, renamed.path))
                message(context.getString(R.string.browser_renamed, renamed.name))
            } catch (e: Exception) {
                message(ErrorMessages.of(context, e))
            }
            refresh()
        }
        clearSelection()
    }

    /** Пароль архива (F5.11) хранится только в памяти и подставляется в отложенное действие. */
    fun submitArchivePassword(archivePath: String, password: CharArray, pending: PendingArchive) {
        passwords.put(archivePath, password)
        password.fill('\u0000')
        when (pending) {
            is PendingArchive.Extract -> extract(archivePath, pending.entries, pending.intoNewFolder)
            is PendingArchive.Preview -> open(pending.item)
            PendingArchive.Open -> {
                loader.forgetArchive()
                refresh()
            }
        }
    }

    /** Групповое переименование (F5.1) по готовому предпросмотру. */
    fun batchRename(previews: List<RenamePreview>) {
        viewModelScope.launch {
            val result = batchRenamer.rename(previews)
            result.renamed.forEach { (from, to) -> favoritesRepository.onMoved(from, to, FileNames.nameOf(to)) }
            mediaIndex.notifyChanged(result.renamed.flatMap { listOf(it.first, it.second) })
            message(
                result.error?.let { ErrorMessages.of(context, it) }
                    ?: context.resources.getQuantityString(R.plurals.browser_batch_renamed, result.renamed.size, result.renamed.size),
            )
            refresh()
        }
        clearSelection()
    }

    fun create(name: String, directory: Boolean) {
        val loc = state.value.location
        if (loc.kind != SourceKind.FOLDER) return
        val error = validationMessage(name)
        if (error != null) {
            message(error)
            return
        }
        viewModelScope.launch {
            try {
                if (directory) provider.createDirectory(loc.path, name) else provider.createFile(loc.path, name)
            } catch (e: Exception) {
                message(ErrorMessages.of(context, e))
            }
            refresh()
        }
    }

    fun validationMessage(name: String): String? = nameValidationMessage(context, name)

    fun toggleFavorite(entry: FileEntry) {
        viewModelScope.launch {
            if (entry.path in favoritesRepository.paths.first()) {
                favoritesRepository.remove(entry.path)
                message(context.getString(R.string.browser_favorite_removed))
            } else {
                favoritesRepository.add(entry)
                message(context.getString(R.string.browser_favorite_added))
            }
        }
        clearSelection()
    }

    fun share(items: List<BrowserItem>) {
        val files = items.map { it.entry }.filterNot { it.isDirectory }
        if (files.isEmpty()) {
            message(context.getString(R.string.browser_share_folders))
            return
        }
        clearSelection()
        viewModelScope.launch {
            val strip = settingsRepository.settings.first().stripLocationOnShare
            val prepared = try {
                if (strip) files.map { it.copy(path = locationStripper.forSharing(it.path)) } else files
            } catch (e: Exception) {
                message(context.getString(R.string.browser_share_location_failed))
                return@launch
            }
            this@BrowserViewModel.launch(FileIntents.share(context, prepared, context.getString(R.string.browser_share_title)))
        }
    }

    fun openAsText(entry: FileEntry) {
        events.trySend(BrowserEvent.OpenFile(entry, emptyList(), readOnly = false, asText = true))
    }

    fun openWith(entry: FileEntry) =
        launch(FileIntents.chooserForView(context, entry, context.getString(R.string.browser_open_with)))

    // ---- Свойства ----

    fun showProperties(entries: List<FileEntry>) {
        _properties.value = PropertiesState(entries)
        viewModelScope.launch {
            var bytes = 0L
            var files = 0
            var folders = 0
            suspend fun walk(e: FileEntry) {
                if (e.isDirectory) {
                    folders++
                    runCatching { provider.list(e.path, includeHidden = true) }.getOrDefault(emptyList()).forEach { walk(it) }
                } else {
                    files++
                    bytes += e.size
                }
            }
            val physical = state.value.location.kind != SourceKind.ARCHIVE
            if (physical) entries.forEach { walk(it) } else entries.forEach { bytes += it.size; if (it.isDirectory) folders++ else files++ }
            val selfFolders = entries.count { it.isDirectory }
            _properties.update { it?.copy(totalBytes = bytes, files = files, folders = folders - if (physical) selfFolders else 0) }
        }
        clearSelection()
    }

    fun dismissProperties() {
        _properties.value = null
    }

    // ---- Вспомогательное ----

    private fun previewArchiveEntry(archivePath: String, item: BrowserItem) {
        val inner = item.archivePath ?: return
        viewModelScope.launch {
            val dir = File(context.cacheDir, "archive-preview").apply { deleteRecursively(); mkdirs() }
            val control = object : io.quietfiles.core.operations.OperationControl {
                override suspend fun checkpoint() = Unit
                override fun progress(update: (io.quietfiles.core.model.OperationProgress) -> io.quietfiles.core.model.OperationProgress) = Unit
                override suspend fun decide(conflict: io.quietfiles.core.model.Conflict) =
                    io.quietfiles.core.model.ConflictDecision(io.quietfiles.core.model.ConflictChoice.REPLACE, true)
                override fun setTempFile(path: String?) = Unit
            }
            val password = passwords.get(archivePath)
            if (password == null && archives.isEncrypted(archivePath)) {
                events.send(BrowserEvent.AskPassword(archivePath, PendingArchive.Preview(item)))
                return@launch
            }
            try {
                val written = try {
                    archives.extract(archivePath, dir.path, listOf(inner), control, password)
                } catch (e: OperationException.WrongPassword) {
                    passwords.remove(archivePath)
                    events.send(BrowserEvent.AskPassword(archivePath, PendingArchive.Preview(item), wrong = true))
                    return@launch
                } finally {
                    password?.fill('\u0000')
                }
                val file = written.firstOrNull()?.let(::File) ?: return@launch
                val entry = FileEntry(file.path, file.name, false, file.length(), file.lastModified())
                events.send(BrowserEvent.OpenFile(entry, emptyList(), readOnly = true))
            } catch (e: Exception) {
                message(ErrorMessages.of(context, e))
            }
        }
    }

    /** Копирует файл с сервера в кэш с прогрессом и открывает копию только для чтения. */
    fun openNetworkCopy(entry: FileEntry, external: Boolean) {
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            val dir = File(context.cacheDir, NETWORK_OPEN_DIR)
            val target = File(dir, entry.name)
            _download.value = DownloadState(entry.name, 0, entry.size)
            try {
                withContext(Dispatchers.IO) {
                    dir.deleteRecursively()
                    dir.mkdirs()
                    provider.openRead(entry.path).use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                done += n
                                _download.value = DownloadState(entry.name, done, entry.size)
                            }
                        }
                    }
                }
                val copy = FileEntry(target.path, target.name, false, target.length(), entry.lastModified)
                events.send(if (external) BrowserEvent.OpenExternally(copy) else BrowserEvent.OpenFile(copy, emptyList(), readOnly = true))
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { target.delete() }
                throw e
            } catch (e: Exception) {
                target.delete()
                message(ErrorMessages.of(context, e))
            } finally {
                _download.value = null
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    private fun launch(intent: Intent) {
        events.trySend(BrowserEvent.Launch(intent))
    }

    private fun message(text: String) {
        events.trySend(BrowserEvent.Message(text))
    }

    companion object {
        /**
         * Выбор диапазона: если что-то уже выбрано и нажат невыбранный элемент, добавляются все
         * элементы между опорным и нажатым. `null` — диапазона нет, это обычное переключение.
         */
        internal fun rangeSelection(order: List<String>, selected: Set<String>, anchor: String?, target: String): Set<String>? {
            if (selected.isEmpty() || target in selected) return null
            val from = order.indexOf(anchor?.takeIf { it in selected } ?: order.lastOrNull { it in selected })
            val to = order.indexOf(target)
            if (from < 0 || to < 0) return null
            return selected + order.subList(minOf(from, to), maxOf(from, to) + 1)
        }

        fun archiveBaseName(name: String): String {
            val lower = name.lowercase()
            val doubleExt = listOf(".tar.gz", ".tar.bz2", ".tar.xz")
            doubleExt.firstOrNull { lower.endsWith(it) }?.let { return name.dropLast(it.length) }
            return FileNames.baseName(name)
        }
    }
}

/** Временные копии файлов с серверов; в file_paths.xml — та же папка. */
private const val NETWORK_OPEN_DIR = "net-open"
