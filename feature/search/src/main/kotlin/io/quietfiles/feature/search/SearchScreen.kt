package io.quietfiles.feature.search

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.quietfiles.core.designsystem.component.EmptyState
import io.quietfiles.core.designsystem.component.FileTile
import io.quietfiles.core.designsystem.component.Formatters
import io.quietfiles.core.designsystem.component.SectionLabel
import io.quietfiles.core.designsystem.component.rememberFormatters
import io.quietfiles.core.designsystem.icon.QfIcons
import io.quietfiles.core.designsystem.theme.MonoTextStyle
import io.quietfiles.core.designsystem.theme.QfTheme
import io.quietfiles.core.model.FileEntry
import io.quietfiles.core.model.StorageVolumeInfo
import io.quietfiles.feature.browser.volumeLabel
import io.quietfiles.core.model.FileNames
import io.quietfiles.core.storage.MediaIndex

/** Поиск (макет 05). Вводимый текст сразу ищется; история — когда поле пустое. */
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenFolder: (String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val formatters = rememberFormatters()
    val snackbar = remember { SnackbarHostState() }
    val noApp = stringResource(R.string.search_no_app)
    var query by rememberSaveable { mutableStateOf(state.filters.query) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is SearchEvent.Launch -> try {
                    context.startActivity(event.intent)
                } catch (e: ActivityNotFoundException) {
                    snackbar.showSnackbar(noApp)
                }
            }
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).heightIn(min = 56.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                    IconButton(onClick = onBack) { Icon(QfIcons.Back, contentDescription = stringResource(R.string.search_back)) }
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text(
                                stringResource(if (state.folder != null && state.filters.scope == SearchScope.FOLDER) R.string.search_hint_folder else R.string.search_hint),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it; viewModel.setQuery(it) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { viewModel.submit() }),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    }
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = ""; viewModel.setQuery("") }) {
                            Icon(QfIcons.Close, contentDescription = stringResource(R.string.search_clear))
                        }
                    }
                }
            }
            if (state.folder != null) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    SegmentedButton(
                        selected = state.filters.scope == SearchScope.EVERYWHERE,
                        onClick = { viewModel.setScope(SearchScope.EVERYWHERE) },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text(stringResource(R.string.search_scope_everywhere)) }
                    SegmentedButton(
                        selected = state.filters.scope == SearchScope.FOLDER,
                        onClick = { viewModel.setScope(SearchScope.FOLDER) },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text(stringResource(R.string.search_scope_folder, FileNames.nameOf(state.folder.orEmpty())), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            Filters(state.filters, viewModel)
            if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.filters.isEmpty) {
                History(state.history, onPick = { query = it; viewModel.setQuery(it) }, onRemove = viewModel::removeHistory, onClear = viewModel::clearHistory)
            } else {
                Results(state, formatters, onOpen = { entry ->
                    if (entry.isDirectory) {
                        viewModel.submit()
                        onOpenFolder(entry.path)
                    } else {
                        viewModel.openFile(entry)
                    }
                }, onOpenLocation = { entry -> FileNames.parentOf(entry.path)?.let(onOpenFolder) })
            }
        }
    }
}

@Composable
private fun Filters(filters: SearchFilters, viewModel: SearchViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MenuChip(
            label = if (filters.type == TypeFilter.ANY) stringResource(R.string.filter_type) else typeLabel(filters.type),
            selected = filters.type != TypeFilter.ANY,
            options = TypeFilter.entries.map { it to typeLabel(it) },
            onSelect = viewModel::setType,
        )
        MenuChip(
            label = if (filters.date == DateFilter.ANY) stringResource(R.string.filter_date) else dateLabel(filters.date),
            selected = filters.date != DateFilter.ANY,
            options = DateFilter.entries.map { it to dateLabel(it) },
            onSelect = viewModel::setDate,
        )
        MenuChip(
            label = if (filters.size == SizeFilter.ANY) stringResource(R.string.filter_size) else sizeLabel(filters.size),
            selected = filters.size != SizeFilter.ANY,
            options = SizeFilter.entries.map { it to sizeLabel(it) },
            onSelect = viewModel::setSize,
        )
    }
}

@Composable
private fun <T> MenuChip(label: String, selected: Boolean, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected,
            onClick = { open = true },
            label = { Text(label) },
            leadingIcon = if (selected) ({ Icon(QfIcons.Check, null, Modifier.size(18.dp)) }) else null,
            trailingIcon = { Icon(QfIcons.ChevronDown, null, Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onSelect(value) })
            }
        }
    }
}

@Composable
private fun typeLabel(t: TypeFilter) = stringResource(
    when (t) {
        TypeFilter.ANY -> R.string.filter_any_type
        TypeFilter.IMAGES -> R.string.filter_images
        TypeFilter.VIDEOS -> R.string.filter_videos
        TypeFilter.AUDIO -> R.string.filter_audio
        TypeFilter.DOCUMENTS -> R.string.filter_documents
        TypeFilter.ARCHIVES -> R.string.filter_archives
        TypeFilter.APKS -> R.string.filter_apks
    },
)

@Composable
private fun dateLabel(d: DateFilter) = stringResource(
    when (d) {
        DateFilter.ANY -> R.string.filter_any_date
        DateFilter.TODAY -> R.string.filter_today
        DateFilter.WEEK -> R.string.filter_week
        DateFilter.MONTH -> R.string.filter_month
        DateFilter.YEAR -> R.string.filter_year
    },
)

@Composable
private fun sizeLabel(s: SizeFilter) = stringResource(
    when (s) {
        SizeFilter.ANY -> R.string.filter_any_size
        SizeFilter.OVER_10_MB -> R.string.filter_10mb
        SizeFilter.OVER_100_MB -> R.string.filter_100mb
        SizeFilter.OVER_1_GB -> R.string.filter_1gb
    },
)

@Composable
private fun History(items: List<String>, onPick: (String) -> Unit, onRemove: (String) -> Unit, onClear: () -> Unit) {
    if (items.isEmpty()) {
        EmptyState(QfIcons.Search, stringResource(R.string.search_empty_title), message = stringResource(R.string.search_empty_hint))
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(stringResource(R.string.search_history), Modifier.weight(1f))
                TextButton(onClick = onClear) { Text(stringResource(R.string.search_history_clear)) }
            }
        }
        items(items, key = { it }) { q ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(q) }.heightIn(min = 52.dp).padding(start = 20.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(QfIcons.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(q, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { onRemove(q) }) {
                    Icon(QfIcons.Close, contentDescription = stringResource(R.string.search_history_remove, q), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Results(state: SearchUiState, formatters: Formatters, onOpen: (FileEntry) -> Unit, onOpenLocation: (FileEntry) -> Unit) {
    if (state.results.isEmpty() && !state.searching) {
        EmptyState(QfIcons.Search, stringResource(R.string.search_nothing), message = stringResource(R.string.search_nothing_hint))
        return
    }
    val highlight = QfTheme.extended.searchHighlight
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            val count = state.results.size
            SectionLabel(
                pluralStringResource(R.plurals.search_found, count, count) +
                    if (state.truncated) stringResource(R.string.search_truncated) else "",
            )
        }
        state.groups.forEach { group ->
            if (state.groups.size > 1 || group.volume?.isPrimary == false) {
                item(key = "volume:" + (group.volume?.id ?: "other")) {
                    SectionLabel(
                        (group.volume?.let { volumeLabel(it) } ?: stringResource(R.string.search_other_location)) +
                            stringResource(R.string.search_group_count, group.entries.size),
                    )
                }
            }
            items(group.entries, key = { it.path }) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .clickable(role = Role.Button) { onOpen(entry) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    FileTile(entry.category, path = entry.path)
                    Column(Modifier.weight(1f)) {
                        Text(highlighted(entry.name, state.filters.query, highlight), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            relativeParent(entry.path, group.volume),
                            style = MonoTextStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                            maxLines = 1,
                            overflow = TextOverflow.StartEllipsis,
                        )
                        Text(
                            (if (entry.isDirectory) "" else formatters.size.format(entry.size) + " · ") + formatters.date.short(entry.lastModified),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onOpenLocation(entry) }) {
                        Icon(QfIcons.FolderOpen, contentDescription = stringResource(R.string.search_open_location), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Путь к папке внутри хранилища: «/Download/Telegram» вместо «/storage/emulated/0/Download/Telegram». */
private fun relativeParent(path: String, volume: StorageVolumeInfo?): String {
    val parent = FileNames.parentOf(path).orEmpty()
    val root = volume?.rootPath?.trimEnd('/') ?: return parent
    return parent.removePrefix(root).ifEmpty { "/" }
}

/** Подсветка совпадения без учёта регистра и «ё». */
internal fun highlighted(name: String, query: String, color: androidx.compose.ui.graphics.Color): AnnotatedString {
    val needle = MediaIndex.normalize(query.trim())
    if (needle.isEmpty()) return AnnotatedString(name)
    val haystack = MediaIndex.normalize(name)
    if (haystack.length != name.length) return AnnotatedString(name)
    return buildAnnotatedString {
        var start = 0
        while (true) {
            val i = haystack.indexOf(needle, start)
            if (i < 0) break
            append(name.substring(start, i))
            withStyle(SpanStyle(background = color)) { append(name.substring(i, i + needle.length)) }
            start = i + needle.length
        }
        append(name.substring(start))
    }
}
