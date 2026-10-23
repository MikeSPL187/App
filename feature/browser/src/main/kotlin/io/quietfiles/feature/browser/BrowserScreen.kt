package io.quietfiles.feature.browser

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.quietfiles.core.designsystem.component.BreadcrumbBar
import io.quietfiles.core.designsystem.component.Crumb
import io.quietfiles.core.designsystem.component.EmptyState
import io.quietfiles.core.designsystem.component.FileGridItem
import io.quietfiles.core.designsystem.component.FileListItem
import io.quietfiles.core.designsystem.component.FloatingActionToolbar
import io.quietfiles.core.designsystem.component.Formatters
import io.quietfiles.core.designsystem.component.SectionLabel
import io.quietfiles.core.designsystem.component.ToolbarAction
import io.quietfiles.core.designsystem.component.rememberFormatters
import io.quietfiles.core.designsystem.component.LocalFileOpener
import io.quietfiles.core.designsystem.component.LocalWindows
import io.quietfiles.core.designsystem.component.WindowsButton
import io.quietfiles.core.model.WindowPlace
import io.quietfiles.core.designsystem.icon.QfIcons
import io.quietfiles.core.storage.FileIntents
import io.quietfiles.core.model.FileEntry
import io.quietfiles.core.model.network.NetworkPaths
import io.quietfiles.core.model.FileNames
import io.quietfiles.core.model.SortField
import io.quietfiles.core.model.ViewMode

/** Что открыто поверх экрана. */
private sealed interface Overlay {
    data class ItemActions(val item: BrowserItem) : Overlay
    data class BatchRename(val items: List<BrowserItem>) : Overlay
    data class Password(val archivePath: String, val pending: PendingArchive, val wrong: Boolean) : Overlay
    data object Sort : Overlay
    data class Name(val mode: NameMode, val target: List<BrowserItem> = emptyList()) : Overlay
    data class Delete(val items: List<BrowserItem>, val forever: Boolean) : Overlay
    data object EmptyTrash : Overlay
    data class ConfirmExtract(val event: BrowserEvent.ConfirmExtract) : Overlay
}

enum class NameMode { NEW_FOLDER, NEW_FILE, RENAME, COMPRESS }

/**
 * Экран обзора (макеты 02, 03): папки, библиотечные разделы, избранное, корзина и архивы.
 *
 * @param onExit выход с экрана, когда история внутри него закончилась.
 * @param onSelectionModeChange нужно, чтобы приложение спрятало нижнюю навигацию на время выбора.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    onExit: (() -> Unit)?,
    onSearch: (folder: String?) -> Unit,
    onRequestAccess: () -> Unit,
    onPinShortcut: (FileEntry) -> Unit,
    onShowOperations: () -> Unit,
    onSelectionModeChange: (Boolean) -> Unit,
    onEditConnection: (Long) -> Unit = {},
    viewModel: BrowserViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val properties by viewModel.properties.collectAsStateWithLifecycle()
    val download by viewModel.download.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val formatters = rememberFormatters()
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var createMenu by rememberSaveable { mutableStateOf(false) }
    val noAppMessage = stringResource(R.string.browser_no_app)
    val fileOpener = LocalFileOpener.current

    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is BrowserEvent.Launch -> try {
                    context.startActivity(event.intent)
                } catch (e: ActivityNotFoundException) {
                    snackbar.showSnackbar(noAppMessage)
                }
                is BrowserEvent.OpenFile -> if (!fileOpener.open(event.entry, event.siblings, event.readOnly, event.asText)) {
                    if (NetworkPaths.isNetwork(event.entry.path)) {
                        viewModel.openNetworkCopy(event.entry, external = true)
                    } else try {
                        context.startActivity(FileIntents.view(context, event.entry))
                    } catch (e: ActivityNotFoundException) {
                        snackbar.showSnackbar(noAppMessage)
                    }
                }
                is BrowserEvent.OpenExternally -> try {
                    context.startActivity(FileIntents.view(context, event.entry))
                } catch (e: ActivityNotFoundException) {
                    snackbar.showSnackbar(noAppMessage)
                }
                is BrowserEvent.Message -> snackbar.showSnackbar(event.text)
                BrowserEvent.ShowOperations -> onShowOperations()
                is BrowserEvent.OpenRoute -> Unit
                is BrowserEvent.ConfirmExtract -> overlay = Overlay.ConfirmExtract(event)
                is BrowserEvent.AskPassword -> overlay = Overlay.Password(event.archivePath, event.pending, event.wrong)
            }
        }
    }
    LaunchedEffect(state.selectionMode) { onSelectionModeChange(state.selectionMode) }
    LaunchedEffect(state.load) { if (state.load !is LoadState.Loading) refreshing = false }

    BackHandler(enabled = state.selectionMode || state.canGoUp) { viewModel.back() }

    Scaffold(
        topBar = {
            if (state.selectionMode) {
                SelectionTopBar(
                    count = state.selection.size,
                    bytes = state.selectedItems.sumOf { it.entry.size },
                    formatters = formatters,
                    onClose = viewModel::clearSelection,
                    onSelectAll = viewModel::selectAll,
                )
            } else {
                BrowserTopBar(
                    state = state,
                    formatters = formatters,
                    onBack = if (state.canGoUp) ({ viewModel.back(); Unit }) else onExit,
                    onSearch = { onSearch(state.location.path.takeIf { state.location.kind == SourceKind.FOLDER }) },
                    onSort = { overlay = Overlay.Sort },
                    onSelectAll = viewModel::selectAll,
                    onRefresh = viewModel::refresh,
                    onEmptyTrash = { overlay = Overlay.EmptyTrash },
                    onFolderProperties = {
                        if (state.location.kind == SourceKind.FOLDER) {
                            viewModel.showProperties(listOf(FileEntry(state.location.path, FileNames.nameOf(state.location.path), true, 0, 0)))
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (state.canCreate && !state.selectionMode && state.load is LoadState.Ready) {
                Box {
                    ExtendedFloatingActionButton(
                        onClick = { createMenu = true },
                        icon = { Icon(QfIcons.Plus, contentDescription = null) },
                        text = { Text(stringResource(R.string.action_create)) },
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(bottom = if (state.canPaste) 72.dp else 0.dp),
                    )
                    DropdownMenu(expanded = createMenu, onDismissRequest = { createMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_new_folder)) },
                            leadingIcon = { Icon(QfIcons.NewFolder, null) },
                            onClick = { createMenu = false; overlay = Overlay.Name(NameMode.NEW_FOLDER) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_new_file)) },
                            leadingIcon = { Icon(QfIcons.NewFile, null) },
                            onClick = { createMenu = false; overlay = Overlay.Name(NameMode.NEW_FILE) },
                        )
                    }
                }
            }
        },
        bottomBar = {
            when {
                state.selectionMode -> FloatingActionToolbar(
                    selectionActions(
                        state = state,
                        onCopy = viewModel::copySelection,
                        onCut = viewModel::cutSelection,
                        onShare = { viewModel.share(state.selectedItems) },
                        onCompress = { overlay = Overlay.Name(NameMode.COMPRESS, state.selectedItems) },
                        onDelete = { overlay = Overlay.Delete(state.selectedItems, forever = !state.useTrash) },
                        onRestore = { viewModel.restore(state.selectedItems) },
                        onDeleteForever = { overlay = Overlay.Delete(state.selectedItems, forever = true) },
                        onExtract = { intoFolder ->
                            viewModel.extract(state.location.path, state.selectedItems.mapNotNull { it.archivePath }, intoFolder)
                        },
                        onRename = {
                            val items = state.selectedItems
                            overlay = if (items.size == 1) Overlay.Name(NameMode.RENAME, items) else Overlay.BatchRename(items)
                        },
                        onProperties = { viewModel.showProperties(state.selectedItems.map { it.entry }) },
                    ),
                    moreLabel = stringResource(R.string.action_more),
                )
                state.canPaste -> state.clipboard?.let { clip ->
                    PasteBar(
                        count = clip.items.size,
                        cut = clip.cut,
                        formatters = formatters,
                        bytes = clip.totalSize,
                        onPaste = viewModel::paste,
                        onCancel = viewModel::cancelClipboard,
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Crumbs(state, viewModel)
            // В галерее фото и видео порядок по датам, поэтому сортировка и вид не нужны.
            if (state.load is LoadState.Ready && state.items.isNotEmpty() && state.location.kind != SourceKind.TRASH && !state.location.kind.isGallery) {
                ControlsRow(
                    state = state,
                    onSort = { overlay = Overlay.Sort },
                    onToggleFoldersFirst = { viewModel.setSort(state.sort.copy(foldersFirst = !state.sort.foldersFirst)) },
                    onViewMode = viewModel::setViewMode,
                )
            }
            if (state.load is LoadState.Loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            }
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { refreshing = true; viewModel.refresh() },
                modifier = Modifier.weight(1f),
            ) {
                Content(
                    state = state,
                    formatters = formatters,
                    onOpen = viewModel::open,
                    onLongPress = { viewModel.longPress(it.entry.path) },
                    onMore = { overlay = Overlay.ItemActions(it) },
                    onRequestAccess = onRequestAccess,
                    onRetry = viewModel::refresh,
                    onPassword = { path, wrong -> overlay = Overlay.Password(path, PendingArchive.Open, wrong) },
                    onEditConnection = onEditConnection,
                )
            }
        }
    }

    download?.let { DownloadDialog(it, formatters, onCancel = viewModel::cancelDownload) }

    when (val o = overlay) {
        null -> Unit
        is Overlay.ItemActions -> ItemActionsSheet(
            item = o.item,
            state = state,
            onDismiss = { overlay = null },
            onAction = { action ->
                overlay = null
                val list = listOf(o.item)
                when (action) {
                    ItemAction.OPEN -> viewModel.open(o.item)
                    ItemAction.OPEN_WITH -> viewModel.openWith(o.item.entry)
                    ItemAction.OPEN_AS_TEXT -> viewModel.openAsText(o.item.entry)
                    ItemAction.SHARE -> viewModel.share(list)
                    ItemAction.COPY -> viewModel.copySelection(list)
                    ItemAction.CUT -> viewModel.cutSelection(list)
                    ItemAction.RENAME -> overlay = Overlay.Name(NameMode.RENAME, list)
                    ItemAction.COMPRESS -> overlay = Overlay.Name(NameMode.COMPRESS, list)
                    ItemAction.EXTRACT_HERE -> viewModel.extract(o.item.entry.path, emptyList(), intoNewFolder = false)
                    ItemAction.EXTRACT_FOLDER -> viewModel.extract(o.item.entry.path, emptyList(), intoNewFolder = true)
                    ItemAction.EXTRACT_ENTRY -> viewModel.extract(state.location.path, listOfNotNull(o.item.archivePath), intoNewFolder = false)
                    ItemAction.FAVORITE -> viewModel.toggleFavorite(o.item.entry)
                    ItemAction.PIN -> onPinShortcut(o.item.entry)
                    ItemAction.PROPERTIES -> viewModel.showProperties(listOf(o.item.entry))
                    ItemAction.OPEN_LOCATION -> viewModel.openParent(o.item.entry)
                    ItemAction.DELETE -> overlay = Overlay.Delete(list, forever = !state.useTrash)
                    ItemAction.RESTORE -> viewModel.restore(list)
                    ItemAction.DELETE_FOREVER -> overlay = Overlay.Delete(list, forever = true)
                }
            },
        )
        is Overlay.Password -> ArchivePasswordDialog(
            archiveName = FileNames.nameOf(o.archivePath),
            wrong = o.wrong,
            onDismiss = { overlay = null },
            onConfirm = { password ->
                overlay = null
                viewModel.submitArchivePassword(o.archivePath, password, o.pending)
            },
        )
        is Overlay.BatchRename -> BatchRenameSheet(
            entries = o.items.map { it.entry },
            folderNames = state.items.map { it.entry.name }.toSet(),
            onRename = { previews ->
                overlay = null
                viewModel.batchRename(previews)
            },
            onDismiss = { overlay = null },
        )
        Overlay.Sort -> SortSheet(
            sort = state.sort,
            viewMode = state.viewMode,
            onSort = viewModel::setSort,
            onViewMode = viewModel::setViewMode,
            onDismiss = { overlay = null },
        )
        is Overlay.Name -> if (o.mode == NameMode.COMPRESS) {
            CompressDialog(
                initial = if (o.target.size == 1) BrowserViewModel.archiveBaseName(o.target.first().entry.name) else stringResource(R.string.dialog_default_archive),
                validate = viewModel::validationMessage,
                onDismiss = { overlay = null },
                onConfirm = { name, password ->
                    overlay = null
                    viewModel.compress(o.target, name, password)
                },
            )
        } else NameDialog(
            mode = o.mode,
            initial = when (o.mode) {
                NameMode.RENAME -> o.target.first().entry.name
                NameMode.NEW_FOLDER -> stringResource(R.string.dialog_default_folder)
                NameMode.NEW_FILE -> stringResource(R.string.dialog_default_file)
                NameMode.COMPRESS -> if (o.target.size == 1) {
                    BrowserViewModel.archiveBaseName(o.target.first().entry.name)
                } else {
                    stringResource(R.string.dialog_default_archive)
                }
            },
            selectBaseName = o.mode == NameMode.RENAME && !o.target.first().entry.isDirectory,
            validate = viewModel::validationMessage,
            onDismiss = { overlay = null },
            onConfirm = { name ->
                overlay = null
                when (o.mode) {
                    NameMode.NEW_FOLDER -> viewModel.create(name, directory = true)
                    NameMode.NEW_FILE -> viewModel.create(name, directory = false)
                    NameMode.RENAME -> viewModel.rename(o.target.first().entry, name)
                    NameMode.COMPRESS -> viewModel.compress(o.target, name)
                }
            },
        )
        is Overlay.Delete -> DeleteDialog(
            count = o.items.size,
            forever = o.forever,
            inTrash = state.location.kind == SourceKind.TRASH,
            onServer = NetworkPaths.isNetwork(state.location.path),
            retentionDays = state.trashRetentionDays,
            onDismiss = { overlay = null },
            onConfirm = { forever ->
                overlay = null
                if (state.location.kind == SourceKind.TRASH) viewModel.deleteFromTrash(o.items) else viewModel.delete(o.items, forever)
            },
        )
        Overlay.EmptyTrash -> ConfirmDialog(
            title = stringResource(R.string.empty_trash_title),
            message = stringResource(R.string.empty_trash_message),
            confirm = stringResource(R.string.action_empty_trash),
            destructive = true,
            onDismiss = { overlay = null },
            onConfirm = { overlay = null; viewModel.emptyTrash() },
        )
        is Overlay.ConfirmExtract -> {
            val e = o.event
            ConfirmDialog(
                title = stringResource(R.string.extract_bomb_title),
                message = stringResource(R.string.extract_bomb_message, formatters.size.format(e.unpackedBytes), e.ratio),
                confirm = stringResource(R.string.extract_bomb_confirm),
                destructive = true,
                onDismiss = { overlay = null },
                onConfirm = {
                    overlay = null
                    viewModel.extract(e.archivePath, e.entries, e.intoNewFolder, confirmed = true)
                },
            )
        }
    }

    properties?.let { PropertiesSheet(it, formatters, onDismiss = viewModel::dismissProperties) }

}

@Composable
private fun Crumbs(state: BrowserUiState, viewModel: BrowserViewModel) {
    val loc = state.location
    when (loc.kind) {
        SourceKind.FOLDER -> {
            val volume = state.volume ?: return
            val label = volumeLabel(volume, short = true)
            val crumbs = remember(loc.path, volume, label) {
                buildList {
                    add(Crumb(label, volume.rootPath, volumeIcon(volume)))
                    val relative = loc.path.removePrefix(volume.rootPath).trim('/')
                    if (relative.isNotEmpty()) {
                        var acc = volume.rootPath
                        relative.split('/').forEach { part ->
                            acc = FileNames.child(acc, part)
                            add(Crumb(part, acc))
                        }
                    }
                }
            }
            BreadcrumbBar(crumbs, onCrumbClick = { viewModel.openFolder(it.path) })
        }
        SourceKind.ARCHIVE -> {
            val crumbs = remember(loc.path, loc.inner) {
                buildList {
                    add(Crumb(FileNames.nameOf(loc.path), "", QfIcons.Archive))
                    var acc = ""
                    loc.inner.trim('/').split('/').filter { it.isNotEmpty() }.forEach { part ->
                        acc = if (acc.isEmpty()) part else "$acc/$part"
                        add(Crumb(part, acc))
                    }
                }
            }
            BreadcrumbBar(crumbs, onCrumbClick = { viewModel.openArchiveDir(it.path) })
        }
        else -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserTopBar(
    state: BrowserUiState,
    formatters: Formatters,
    onBack: (() -> Unit)?,
    onSearch: () -> Unit,
    onSort: () -> Unit,
    onSelectAll: () -> Unit,
    onRefresh: () -> Unit,
    onEmptyTrash: () -> Unit,
    onFolderProperties: () -> Unit,
) {
    val loc = state.location
    val title = when (loc.kind) {
        SourceKind.FOLDER -> state.volume?.takeIf { it.rootPath == loc.path }?.let { volumeLabel(it) } ?: FileNames.nameOf(loc.path)
        SourceKind.ARCHIVE -> loc.inner.trim('/').takeIf { it.isNotEmpty() }?.let(FileNames::nameOf) ?: FileNames.nameOf(loc.path)
        else -> kindTitle(loc.kind)
    }
    val items = state.items
    val count = items.size
    val bytes = items.filterNot { it.entry.isDirectory }.sumOf { it.entry.size }
    val subtitle = buildList {
        when (loc.kind) {
            // В корне тома его имя уже стоит в заголовке.
            SourceKind.FOLDER -> state.volume?.takeIf { it.rootPath != loc.path }?.let { add(volumeLabel(it)) }
            SourceKind.ARCHIVE -> add(stringResource(R.string.browser_archive))
            else -> Unit
        }
        if (state.load is LoadState.Ready) {
            add(pluralStringResource(io.quietfiles.core.common.R.plurals.items_count, count, count))
            if (bytes > 0) add(formatters.size.format(bytes))
        }
    }.joinToString(stringResource(R.string.meta_separator))
    var menu by remember { mutableStateOf(false) }
    // Окно обзора (F1.5) запоминает, где пользователь сейчас.
    val windows = LocalWindows.current
    LaunchedEffect(loc, title) { windows?.onPlace(WindowPlace(loc.kind.name, loc.path, loc.inner, title)) }

    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(QfIcons.Back, contentDescription = stringResource(R.string.action_back)) }
            }
        },
        actions = {
            if (loc.kind != SourceKind.TRASH && loc.kind != SourceKind.ARCHIVE) {
                IconButton(onClick = onSearch) { Icon(QfIcons.Search, contentDescription = stringResource(R.string.action_search)) }
            }
            WindowsButton()
            Box {
                IconButton(onClick = { menu = true }) { Icon(QfIcons.More, contentDescription = stringResource(R.string.action_more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (loc.kind == SourceKind.TRASH) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_empty_trash)) },
                            leadingIcon = { Icon(QfIcons.Trash, null) },
                            enabled = items.isNotEmpty(),
                            onClick = { menu = false; onEmptyTrash() },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sort_title)) },
                            leadingIcon = { Icon(QfIcons.SortUp, null) },
                            onClick = { menu = false; onSort() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_select_all)) },
                        leadingIcon = { Icon(QfIcons.SelectAll, null) },
                        enabled = items.isNotEmpty(),
                        onClick = { menu = false; onSelectAll() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_refresh)) },
                        leadingIcon = { Icon(QfIcons.Restore, null) },
                        onClick = { menu = false; onRefresh() },
                    )
                    if (loc.kind == SourceKind.FOLDER) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_properties)) },
                            leadingIcon = { Icon(QfIcons.Info, null) },
                            onClick = { menu = false; onFolderProperties() },
                        )
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int,
    bytes: Long,
    formatters: Formatters,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
) {
    TopAppBar(
        title = {
            Column {
                Text(pluralStringResource(R.plurals.selection_count, count, count), style = MaterialTheme.typography.titleLarge)
                if (bytes > 0) {
                    Text(formatters.size.format(bytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(QfIcons.Close, contentDescription = stringResource(R.string.action_close_selection)) }
        },
        actions = {
            androidx.compose.material3.OutlinedButton(onClick = onSelectAll, modifier = Modifier.padding(end = 8.dp)) {
                Text(stringResource(R.string.action_all))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControlsRow(
    state: BrowserUiState,
    onSort: () -> Unit,
    onToggleFoldersFirst: () -> Unit,
    onViewMode: (ViewMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val sortLabel = stringResource(
            when (state.sort.field) {
                SortField.NAME -> R.string.sort_name
                SortField.DATE -> R.string.sort_date
                SortField.SIZE -> R.string.sort_size
                SortField.TYPE -> R.string.sort_type
            },
        )
        FilterChip(
            selected = false,
            onClick = onSort,
            label = { Text(sortLabel) },
            leadingIcon = { Icon(if (state.sort.ascending) QfIcons.SortUp else QfIcons.SortDown, null, Modifier.size(18.dp)) },
        )
        if (state.location.kind == SourceKind.FOLDER || state.location.kind == SourceKind.ARCHIVE || state.location.kind == SourceKind.FAVORITES) {
            FilterChip(
                selected = state.sort.foldersFirst,
                onClick = onToggleFoldersFirst,
                label = { Text(stringResource(R.string.sort_folders_first)) },
                leadingIcon = if (state.sort.foldersFirst) {
                    { Icon(QfIcons.Check, null, Modifier.size(18.dp)) }
                } else null,
            )
        }
        Spacer(Modifier.weight(1f))
        SingleChoiceSegmentedButtonRow {
            SegmentedButton(
                selected = state.viewMode == ViewMode.LIST,
                onClick = { onViewMode(ViewMode.LIST) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                icon = {},
                label = { Icon(QfIcons.ListView, contentDescription = stringResource(R.string.view_list), modifier = Modifier.size(18.dp)) },
            )
            SegmentedButton(
                selected = state.viewMode == ViewMode.GRID,
                onClick = { onViewMode(ViewMode.GRID) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                icon = {},
                label = { Icon(QfIcons.GridView, contentDescription = stringResource(R.string.view_grid), modifier = Modifier.size(18.dp)) },
            )
        }
    }
}

@Composable
private fun Content(
    state: BrowserUiState,
    formatters: Formatters,
    onOpen: (BrowserItem) -> Unit,
    onLongPress: (BrowserItem) -> Unit,
    onMore: (BrowserItem) -> Unit,
    onRequestAccess: () -> Unit,
    onRetry: () -> Unit,
    onPassword: (archivePath: String, wrong: Boolean) -> Unit,
    onEditConnection: (Long) -> Unit,
) {
    when (val load = state.load) {
        is LoadState.Network -> Scrollable {
            EmptyState(
                QfIcons.Alert,
                stringResource(R.string.network_error_title),
                message = load.message,
                actionLabel = stringResource(if (load.fixInSettings) R.string.network_error_settings else R.string.action_retry),
                onAction = { if (load.fixInSettings) onEditConnection(load.connectionId) else onRetry() },
            )
        }
        is LoadState.NeedsPassword -> Scrollable {
            EmptyState(
                QfIcons.Lock,
                stringResource(R.string.password_title),
                message = stringResource(if (load.wrong) R.string.password_wrong else R.string.password_needed),
                actionLabel = stringResource(R.string.password_enter),
                onAction = { onPassword(load.archivePath, load.wrong) },
            )
        }
        LoadState.Loading -> Box(Modifier.fillMaxSize())
        LoadState.NoAccess -> Scrollable {
            EmptyState(
                icon = QfIcons.Lock,
                title = stringResource(R.string.no_access_title),
                message = stringResource(R.string.no_access_message),
                actionLabel = stringResource(R.string.no_access_action),
                onAction = onRequestAccess,
            )
        }
        LoadState.NotFound -> Scrollable {
            EmptyState(QfIcons.FolderOpen, stringResource(R.string.not_found_title), message = stringResource(R.string.not_found_message))
        }
        is LoadState.Error -> Scrollable {
            EmptyState(QfIcons.Alert, stringResource(R.string.error_title), message = load.message, actionLabel = stringResource(R.string.action_retry), onAction = onRetry)
        }
        is LoadState.Ready -> if (load.items.isEmpty()) {
            Scrollable { EmptyFor(state.location.kind) }
        } else if (state.location.kind.isGallery) {
            MediaGallery(state, onOpen, onLongPress)
        } else if (state.viewMode == ViewMode.GRID) {
            FileGrid(state, onOpen, onLongPress)
        } else {
            FileList(state, formatters, onOpen, onLongPress, onMore)
        }
    }
}

/** Пустые состояния тоже должны поддерживать «потянуть для обновления». */
@Composable
private fun Scrollable(content: @Composable () -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) { item { content() } }
}

@Composable
private fun EmptyFor(kind: SourceKind) {
    when (kind) {
        SourceKind.FOLDER, SourceKind.ARCHIVE ->
            EmptyState(QfIcons.FolderOpen, stringResource(R.string.empty_folder_title), message = stringResource(R.string.empty_folder_message).takeIf { kind == SourceKind.FOLDER })
        SourceKind.TRASH -> EmptyState(QfIcons.Trash, stringResource(R.string.empty_trash_state), message = stringResource(R.string.empty_trash_hint))
        SourceKind.FAVORITES -> EmptyState(QfIcons.Star, stringResource(R.string.empty_favorites_title), message = stringResource(R.string.empty_favorites_hint))
        else -> EmptyState(QfIcons.File, stringResource(R.string.empty_library_title))
    }
}

@Composable
private fun FileList(
    state: BrowserUiState,
    formatters: Formatters,
    onOpen: (BrowserItem) -> Unit,
    onLongPress: (BrowserItem) -> Unit,
    onMore: (BrowserItem) -> Unit,
) {
    val items = state.items
    val showSections = state.sort.foldersFirst && items.any { it.entry.isDirectory } && items.any { !it.entry.isDirectory }
    val folders = if (showSections) items.filter { it.entry.isDirectory } else emptyList()
    val files = if (showSections) items.filterNot { it.entry.isDirectory } else items
    val kind = state.location.kind
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        if (showSections) {
            item(key = "h-folders", contentType = "header") { SectionLabel(stringResource(R.string.browser_section_folders, folders.size)) }
            items(folders, key = { it.entry.path }, contentType = { "item" }) { item ->
                ItemRow(item, state, kind, formatters, onOpen, onLongPress, onMore)
            }
            item(key = "h-files", contentType = "header") { SectionLabel(stringResource(R.string.browser_section_files, files.size)) }
        }
        items(files, key = { it.entry.path }, contentType = { "item" }) { item ->
            ItemRow(item, state, kind, formatters, onOpen, onLongPress, onMore)
        }
    }
}

@Composable
private fun ItemRow(
    item: BrowserItem,
    state: BrowserUiState,
    kind: SourceKind,
    formatters: Formatters,
    onOpen: (BrowserItem) -> Unit,
    onLongPress: (BrowserItem) -> Unit,
    onMore: (BrowserItem) -> Unit,
) {
    val entry = item.entry
    FileListItem(
        name = entry.name,
        meta = itemMeta(item, kind, formatters),
        category = entry.category,
        path = entry.path.takeIf { kind != SourceKind.ARCHIVE },
        selectionMode = state.selectionMode,
        selected = entry.path in state.selection,
        isNew = kind == SourceKind.FOLDER && isRecent(entry),
        dimmed = entry.isHidden,
        compact = state.compact,
        onClick = { onOpen(item) },
        onLongClick = { onLongPress(item) },
        onMoreClick = { onMore(item) },
        // Вне выбора долгое нажатие на невыбранный элемент — это ровно переключение; в выборе касание переключает.
        onTileClick = if (state.tapIconToSelect || state.selectionMode) {
            { if (state.selectionMode) onOpen(item) else onLongPress(item) }
        } else null,
    )
}

@Composable
private fun FileGrid(
    state: BrowserUiState,
    onOpen: (BrowserItem) -> Unit,
    onLongPress: (BrowserItem) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 96.dp),
    ) {
        items(state.items, key = { it.entry.path }) { item ->
            FileGridItem(
                name = item.entry.name,
                category = item.entry.category,
                path = item.entry.path.takeIf { state.location.kind != SourceKind.ARCHIVE },
                selectionMode = state.selectionMode,
                selected = item.entry.path in state.selection,
                onClick = { onOpen(item) },
                onLongClick = { onLongPress(item) },
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun selectionActions(
    state: BrowserUiState,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onShare: () -> Unit,
    onCompress: () -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
    onExtract: (intoFolder: Boolean) -> Unit,
    onRename: () -> Unit,
    onProperties: () -> Unit,
): List<ToolbarAction> = when (state.location.kind) {
    SourceKind.TRASH -> listOf(
        ToolbarAction(stringResource(R.string.action_restore), QfIcons.Restore, onClick = onRestore),
        ToolbarAction(stringResource(R.string.action_delete_forever), QfIcons.Trash, destructive = true, onClick = onDeleteForever),
    )
    SourceKind.ARCHIVE -> listOf(
        ToolbarAction(stringResource(R.string.action_extract_here), QfIcons.Extract, onClick = { onExtract(false) }),
        ToolbarAction(
            stringResource(R.string.action_extract_folder, BrowserViewModel.archiveBaseName(FileNames.nameOf(state.location.path))),
            QfIcons.NewFolder,
            onClick = { onExtract(true) },
        ),
    )
    else -> listOf(
        ToolbarAction(stringResource(R.string.action_copy), QfIcons.Copy, onClick = onCopy),
        ToolbarAction(stringResource(R.string.action_cut), QfIcons.Move, onClick = onCut),
        ToolbarAction(stringResource(R.string.action_share), QfIcons.Share, enabled = state.selectedItems.any { !it.entry.isDirectory }, onClick = onShare),
        ToolbarAction(stringResource(R.string.action_compress), QfIcons.Compress, onClick = onCompress),
        ToolbarAction(stringResource(R.string.action_delete), QfIcons.Trash, destructive = true, onClick = onDelete),
        // В «Ещё» (F4.3): переименование — одного файла или группы (F5.1), свойства.
        ToolbarAction(stringResource(R.string.action_rename), QfIcons.Rename, enabled = state.location.kind == SourceKind.FOLDER, onClick = onRename),
        ToolbarAction(stringResource(R.string.action_properties), QfIcons.Info, onClick = onProperties),
    )
}

@Composable
private fun PasteBar(
    count: Int,
    cut: Boolean,
    bytes: Long,
    formatters: Formatters,
    onPaste: () -> Unit,
    onCancel: () -> Unit,
) {
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(if (cut) QfIcons.Move else QfIcons.Copy, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(
                    pluralStringResource(if (cut) R.plurals.clipboard_cut else R.plurals.clipboard_copy, count, count),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (bytes > 0) Text(formatters.size.format(bytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            androidx.compose.material3.Button(onClick = onPaste) {
                Text(stringResource(if (cut) R.string.action_move_here else R.string.action_paste_here))
            }
        }
    }
}
