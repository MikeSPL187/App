package io.quietfiles.feature.browser.picker

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.quietfiles.core.designsystem.component.BreadcrumbBar
import io.quietfiles.core.designsystem.component.Crumb
import io.quietfiles.core.designsystem.component.EmptyState
import io.quietfiles.core.designsystem.component.FileListItem
import io.quietfiles.core.designsystem.component.rememberFormatters
import io.quietfiles.core.designsystem.icon.QfIcons
import io.quietfiles.core.model.FileNames
import io.quietfiles.feature.browser.BrowserItem
import io.quietfiles.feature.browser.NameDialog
import io.quietfiles.feature.browser.NameMode
import io.quietfiles.feature.browser.R
import io.quietfiles.feature.browser.SourceKind
import io.quietfiles.feature.browser.itemMeta
import io.quietfiles.feature.browser.volumeIcon
import io.quietfiles.feature.browser.volumeLabel

/**
 * Выбор файлов для другого приложения или папки для сохранения (F15.1, F15.2).
 * Тот же список, что в «Обзоре», но без операций над файлами: только переход по папкам и выбор.
 *
 * @param confirmLabel подпись главной кнопки в режиме выбора папки («Сохранить сюда»).
 * @param subtitle что именно сохраняем, например «3 файла»; показывается под заголовком.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickerScreen(
    config: PickerConfig,
    onPicked: (List<String>) -> Unit,
    onCancel: () -> Unit,
    onRequestAccess: () -> Unit,
    confirmLabel: String? = null,
    subtitle: String? = null,
    viewModel: PickerViewModel = hiltViewModel(),
) {
    LaunchedEffect(config) { viewModel.configure(config) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val formatters = rememberFormatters()
    val snackbar = remember { SnackbarHostState() }
    var newFolder by rememberSaveable { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshAccess()
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) { viewModel.messageFlow.collect { snackbar.showSnackbar(it) } }
    BackHandler { if (!viewModel.up()) onCancel() }

    val folderMode = config.mode == PickerMode.FOLDER
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onCancel) { Icon(QfIcons.Close, stringResource(R.string.action_cancel)) }
                },
                title = {
                    Column {
                        Text(
                            stringResource(
                                when {
                                    folderMode -> R.string.picker_title_folder
                                    config.multiple -> R.string.picker_title_files
                                    else -> R.string.picker_title_file
                                },
                            ),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        subtitle?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    if (folderMode && state.hasAccess && state.path.isNotEmpty()) {
                        IconButton(onClick = { newFolder = true }) {
                            Icon(QfIcons.NewFolder, stringResource(R.string.dialog_new_folder))
                        }
                    }
                },
            )
        },
        bottomBar = {
            val canConfirm = folderMode && state.hasAccess && !state.failed && state.path.isNotEmpty()
            val multiSelected = !folderMode && config.multiple && state.selected.isNotEmpty()
            if (canConfirm || multiSelected) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
                    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Button(
                            onClick = { onPicked(if (folderMode) listOf(state.path) else state.selected) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        ) {
                            Text(
                                if (folderMode) {
                                    confirmLabel ?: stringResource(R.string.picker_choose_folder)
                                } else {
                                    pluralStringResource(R.plurals.picker_select_count, state.selected.size, state.selected.size)
                                },
                            )
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!state.hasAccess) {
                EmptyState(
                    icon = QfIcons.Lock,
                    title = stringResource(R.string.no_access_title),
                    message = stringResource(R.string.no_access_message),
                    actionLabel = stringResource(R.string.no_access_action),
                    onAction = onRequestAccess,
                )
                return@Column
            }
            if (state.volumes.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.volumes, key = { it.id }) { volume ->
                        FilterChip(
                            selected = state.volume?.id == volume.id,
                            onClick = { viewModel.open(volume.rootPath) },
                            label = { Text(volumeLabel(volume, short = true)) },
                            leadingIcon = { Icon(volumeIcon(volume), null, Modifier.size(18.dp)) },
                        )
                    }
                }
            }
            state.volume?.let { volume ->
                val label = volumeLabel(volume, short = true)
                val crumbs = remember(state.path, volume, label) {
                    buildList {
                        add(Crumb(label, volume.rootPath, volumeIcon(volume)))
                        var acc = volume.rootPath
                        state.path.removePrefix(volume.rootPath).trim('/').split('/').filter { it.isNotEmpty() }.forEach {
                            acc = FileNames.child(acc, it)
                            add(Crumb(it, acc))
                        }
                    }
                }
                BreadcrumbBar(crumbs, onCrumbClick = { viewModel.open(it.path) })
            }
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.failed -> EmptyState(QfIcons.Alert, stringResource(R.string.error_title))
                state.items.isEmpty() -> EmptyState(QfIcons.FolderOpen, stringResource(R.string.empty_folder_title))
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(state.items, key = { it.entry.path }) { item ->
                        val entry = item.entry
                        val selected = entry.path in state.selected
                        FileListItem(
                            name = entry.name,
                            meta = itemMeta(BrowserItem(entry), SourceKind.FOLDER, formatters),
                            category = entry.category,
                            path = entry.path,
                            selectionMode = config.multiple && !folderMode && !entry.isDirectory,
                            selected = selected,
                            dimmed = !item.enabled,
                            onClick = {
                                when {
                                    entry.isDirectory -> viewModel.open(entry.path)
                                    !item.enabled -> Unit
                                    config.multiple -> viewModel.toggle(entry.path)
                                    else -> onPicked(listOf(entry.path))
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (newFolder) {
        NameDialog(
            mode = NameMode.NEW_FOLDER,
            initial = "",
            selectBaseName = false,
            validate = viewModel::validationMessage,
            onDismiss = { newFolder = false },
            onConfirm = {
                newFolder = false
                viewModel.createFolder(it)
            },
        )
    }
}
