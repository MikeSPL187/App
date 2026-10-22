package io.quietfiles.feature.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.quietfiles.core.designsystem.component.FileTile
import io.quietfiles.core.designsystem.component.Formatters
import io.quietfiles.core.designsystem.icon.QfIcons
import io.quietfiles.core.designsystem.theme.MonoTextStyle
import io.quietfiles.core.model.FileNames
import io.quietfiles.core.model.SortField
import io.quietfiles.core.model.SortOrder
import io.quietfiles.core.model.ViewMode
import io.quietfiles.core.operations.ArchiveTools
import java.text.NumberFormat

enum class ItemAction {
    OPEN, OPEN_WITH, SHARE, COPY, CUT, RENAME, COMPRESS, EXTRACT_HERE, EXTRACT_FOLDER, EXTRACT_ENTRY,
    FAVORITE, PIN, PROPERTIES, OPEN_LOCATION, DELETE, RESTORE, DELETE_FOREVER,
}

private data class ActionRow(val action: ItemAction, val label: String, val icon: ImageVector, val destructive: Boolean = false)

/** Меню действий одного файла (F3.7). Набор пунктов зависит от того, где открыт файл. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemActionsSheet(
    item: BrowserItem,
    state: BrowserUiState,
    onDismiss: () -> Unit,
    onAction: (ItemAction) -> Unit,
) {
    val entry = item.entry
    val kind = state.location.kind
    val isFavorite = entry.path in state.favorites
    val archive = !entry.isDirectory && ArchiveTools.isBrowsableArchive(entry.name)
    val rows = buildList {
        when (kind) {
            SourceKind.TRASH -> {
                add(ActionRow(ItemAction.RESTORE, stringResource(R.string.action_restore), QfIcons.Restore))
                add(ActionRow(ItemAction.DELETE_FOREVER, stringResource(R.string.action_delete_forever), QfIcons.Trash, destructive = true))
            }
            SourceKind.ARCHIVE -> {
                if (!entry.isDirectory) add(ActionRow(ItemAction.OPEN, stringResource(R.string.action_open), QfIcons.OpenWith))
                add(ActionRow(ItemAction.EXTRACT_ENTRY, stringResource(R.string.action_extract_here), QfIcons.Extract))
            }
            else -> {
                if (!entry.isDirectory) {
                    add(ActionRow(ItemAction.OPEN_WITH, stringResource(R.string.action_open_with), QfIcons.OpenWith))
                    add(ActionRow(ItemAction.SHARE, stringResource(R.string.action_share), QfIcons.Share))
                }
                if (kind != SourceKind.FOLDER) add(ActionRow(ItemAction.OPEN_LOCATION, stringResource(R.string.action_open_location), QfIcons.FolderOpen))
                add(ActionRow(ItemAction.COPY, stringResource(R.string.action_copy), QfIcons.Copy))
                add(ActionRow(ItemAction.CUT, stringResource(R.string.action_cut), QfIcons.Move))
                add(ActionRow(ItemAction.RENAME, stringResource(R.string.action_rename), QfIcons.Rename))
                if (archive) {
                    add(ActionRow(ItemAction.EXTRACT_HERE, stringResource(R.string.action_extract_here), QfIcons.Extract))
                    add(
                        ActionRow(
                            ItemAction.EXTRACT_FOLDER,
                            stringResource(R.string.action_extract_folder, BrowserViewModel.archiveBaseName(entry.name)),
                            QfIcons.NewFolder,
                        ),
                    )
                } else {
                    add(ActionRow(ItemAction.COMPRESS, stringResource(R.string.action_compress), QfIcons.Compress))
                }
                add(
                    ActionRow(
                        ItemAction.FAVORITE,
                        stringResource(if (isFavorite) R.string.action_favorite_remove else R.string.action_favorite_add),
                        if (isFavorite) QfIcons.StarFilled else QfIcons.Star,
                    ),
                )
                if (entry.isDirectory) add(ActionRow(ItemAction.PIN, stringResource(R.string.action_pin), QfIcons.Shortcut))
                add(ActionRow(ItemAction.PROPERTIES, stringResource(R.string.action_properties), QfIcons.Info))
                add(ActionRow(ItemAction.DELETE, stringResource(R.string.action_delete), QfIcons.Trash, destructive = true))
            }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                FileTile(entry.category, path = entry.path.takeIf { kind != SourceKind.ARCHIVE })
                Text(entry.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            rows.forEach { row ->
                val color = if (row.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clickable(role = Role.Button) { onAction(row.action) }
                        .padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Icon(row.icon, contentDescription = null, tint = if (row.destructive) color else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(row.label, style = MaterialTheme.typography.bodyLarge, color = color)
                }
            }
        }
    }
}

/** Выбор сортировки и вида. Направление подписано по смыслу: «Сначала новые», а не «▼». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortSheet(
    sort: SortOrder,
    viewMode: ViewMode,
    onSort: (SortOrder) -> Unit,
    onViewMode: (ViewMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(
        Triple(SortField.NAME, true, R.string.sort_name_asc),
        Triple(SortField.NAME, false, R.string.sort_name_desc),
        Triple(SortField.DATE, false, R.string.sort_date_desc),
        Triple(SortField.DATE, true, R.string.sort_date_asc),
        Triple(SortField.SIZE, false, R.string.sort_size_desc),
        Triple(SortField.SIZE, true, R.string.sort_size_asc),
        Triple(SortField.TYPE, true, R.string.sort_type_asc),
    )
    val fieldLabel = mapOf(
        SortField.NAME to R.string.sort_name,
        SortField.DATE to R.string.sort_date,
        SortField.SIZE to R.string.sort_size,
        SortField.TYPE to R.string.sort_type,
    )
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.sort_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            options.forEach { (field, asc, label) ->
                val selected = sort.field == field && sort.ascending == asc
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clickable(role = Role.RadioButton) { onSort(sort.copy(field = field, ascending = asc)) }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
                    Column {
                        Text(stringResource(fieldLabel.getValue(field)), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(role = Role.Switch) { onSort(sort.copy(foldersFirst = !sort.foldersFirst)) }
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.sort_folders_first), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = sort.foldersFirst, onCheckedChange = null)
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                SegmentedButton(
                    selected = viewMode == ViewMode.LIST,
                    onClick = { onViewMode(ViewMode.LIST) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    icon = { Icon(QfIcons.ListView, null, Modifier.size(18.dp)) },
                ) { Text(stringResource(R.string.view_list)) }
                SegmentedButton(
                    selected = viewMode == ViewMode.GRID,
                    onClick = { onViewMode(ViewMode.GRID) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    icon = { Icon(QfIcons.GridView, null, Modifier.size(18.dp)) },
                ) { Text(stringResource(R.string.view_grid)) }
            }
        }
    }
}

/** Ввод имени: создание, переименование, имя архива. Ошибки показываются сразу под полем. */
@Composable
fun NameDialog(
    mode: NameMode,
    initial: String,
    selectBaseName: Boolean,
    validate: (String) -> String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val selectionEnd = if (selectBaseName) FileNames.baseName(initial).length else initial.length
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, selectionEnd))) }
    val error = validate(value.text)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val title = stringResource(
        when (mode) {
            NameMode.NEW_FOLDER -> R.string.dialog_new_folder
            NameMode.NEW_FILE -> R.string.dialog_new_file
            NameMode.RENAME -> R.string.dialog_rename
            NameMode.COMPRESS -> R.string.dialog_compress
        },
    )
    val confirmLabel = stringResource(
        when (mode) {
            NameMode.RENAME -> R.string.action_save
            NameMode.COMPRESS -> R.string.action_compress
            else -> R.string.action_create
        },
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(stringResource(if (mode == NameMode.COMPRESS) R.string.dialog_archive_name else R.string.dialog_name)) },
                singleLine = true,
                isError = error != null && value.text.isNotEmpty(),
                supportingText = { if (error != null && value.text.isNotEmpty()) Text(error) },
                suffix = if (mode == NameMode.COMPRESS && !value.text.endsWith(".zip", true)) ({ Text(".zip") }) else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (error == null) onConfirm(value.text) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.text) }, enabled = error == null && value.text != initial.takeIf { mode == NameMode.RENAME }) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun DeleteDialog(
    count: Int,
    forever: Boolean,
    inTrash: Boolean,
    retentionDays: Int,
    onDismiss: () -> Unit,
    onConfirm: (forever: Boolean) -> Unit,
) {
    var skipTrash by remember { mutableStateOf(forever) }
    val permanent = inTrash || skipTrash
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(QfIcons.Trash, contentDescription = null) },
        title = { Text(stringResource(if (permanent) R.string.delete_forever_title else R.string.delete_trash_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when {
                        permanent -> pluralStringResource(R.plurals.delete_forever_message, count, count)
                        retentionDays <= 0 -> stringResource(R.string.delete_trash_message_forever)
                        else -> pluralStringResource(R.plurals.delete_trash_message, count, count, retentionDays)
                    },
                )
                if (!inTrash && !forever) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { skipTrash = !skipTrash },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = skipTrash, onCheckedChange = null)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.delete_skip_trash))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(permanent) }) {
                Text(
                    stringResource(if (permanent) R.string.action_delete_forever else R.string.action_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirm: String,
    destructive: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PropertiesSheet(state: PropertiesState, formatters: Formatters, onDismiss: () -> Unit) {
    val single = state.entries.singleOrNull()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.props_title), style = MaterialTheme.typography.titleLarge)
            if (single != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    FileTile(single.category, path = single.path, size = 48.dp)
                    Text(single.name, style = MaterialTheme.typography.titleMedium)
                }
                Property(
                    stringResource(R.string.props_type),
                    if (single.isDirectory) stringResource(R.string.props_folder)
                    else stringResource(R.string.props_file, single.extension.uppercase().ifEmpty { "—" }),
                )
                FileNames.parentOf(single.path)?.let { parent ->
                    Property(stringResource(R.string.props_location), parent, mono = true)
                }
                if (single.lastModified > 0) Property(stringResource(R.string.props_modified), formatters.date.long(single.lastModified))
                if (single.isHidden) Property(stringResource(R.string.props_hidden), "✓")
            } else {
                Property(stringResource(R.string.props_selected), state.entries.size.toString())
            }
            val bytes = state.totalBytes
            Property(
                stringResource(R.string.props_size),
                if (bytes == null) stringResource(R.string.props_counting)
                else stringResource(R.string.props_bytes, formatters.size.format(bytes), NumberFormat.getIntegerInstance().format(bytes)),
            )
            if (bytes != null && (single == null || single.isDirectory)) {
                Property(
                    stringResource(R.string.props_contains),
                    stringResource(
                        R.string.props_contains_value,
                        pluralStringResource(io.quietfiles.core.common.R.plurals.files_count, state.files, state.files),
                        pluralStringResource(io.quietfiles.core.common.R.plurals.folders_count, state.folders, state.folders),
                    ),
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Property(label: String, value: String, mono: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(value, style = if (mono) MonoTextStyle.copy(color = MaterialTheme.colorScheme.onSurface) else MaterialTheme.typography.bodyLarge)
        }
    }
}
