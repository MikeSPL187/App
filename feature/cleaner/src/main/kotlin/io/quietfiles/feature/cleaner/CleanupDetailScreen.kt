package io.quietfiles.feature.cleaner

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.quietfiles.core.designsystem.component.FileTile
import io.quietfiles.core.designsystem.component.Formatters
import io.quietfiles.core.designsystem.component.rememberFormatters
import io.quietfiles.core.designsystem.icon.QfIcons
import io.quietfiles.core.designsystem.theme.MonoTextStyle
import io.quietfiles.core.model.FileNames
import io.quietfiles.core.model.cleanup.CleanupCategory
import io.quietfiles.core.model.cleanup.CleanupFile
import io.quietfiles.core.model.cleanup.CleanupRules
import io.quietfiles.core.operations.cleanup.CleanupScanner
import kotlinx.serialization.Serializable

@Serializable
data class CleanupDetailRoute(val category: String)

/** Файлы одного пункта очистки: можно снять отметку с любого (F13.1, F13.2). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanupDetailScreen(
    category: CleanupCategory,
    onBack: () -> Unit,
    viewModel: CleanerViewModel = hiltViewModel(),
) {
    val scan by viewModel.scan.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val formatters = rememberFormatters()
    val section = scan.section(category)
    // После очистки пункт пересчитывается заново — возвращаемся к списку пунктов.
    LaunchedEffect(viewModel) { viewModel.eventFlow.collect { if (it == CleanerEvent.Cleaned) onBack() } }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(QfIcons.Back, stringResource(R.string.cleaner_back)) } },
                title = {
                    Column {
                        Text(categoryTitle(category), maxLines = 1)
                        section?.let {
                            Text(
                                pluralStringResource(R.plurals.cleaner_files, it.count, it.count) + " · " + formatters.size.format(it.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    Checkbox(checked = category in selection.categories, onCheckedChange = { viewModel.toggleCategory(category) })
                },
            )
        },
        bottomBar = {
            val bytes = section?.let { selection.copy(categories = selection.categories.intersect(setOf(category))).selectedBytes(listOf(it)) } ?: 0
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Button(
                    onClick = { viewModel.clean(only = category) },
                    enabled = bytes > 0,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).heightIn(min = 48.dp),
                ) {
                    Text(
                        stringResource(
                            if (category.viaTrash) R.string.cleaner_move_to_trash else R.string.cleaner_delete,
                            formatters.size.format(bytes),
                        ),
                    )
                }
            }
        },
    ) { padding ->
        if (section == null) return@Scaffold
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            item {
                Text(
                    stringResource(
                        when (category) {
                            CleanupCategory.DUPLICATES -> R.string.cleaner_explain_duplicates
                            CleanupCategory.INSTALLED_APKS -> R.string.cleaner_explain_apks
                            CleanupCategory.CACHE -> R.string.cleaner_explain_cache
                            CleanupCategory.OLD_TRASH -> R.string.cleaner_explain_trash
                            CleanupCategory.LARGE_FILES -> R.string.cleaner_explain_large
                            CleanupCategory.OLD_DOWNLOADS -> R.string.cleaner_explain_downloads
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp, start = 4.dp, end = 4.dp),
                )
            }
            if (category == CleanupCategory.LARGE_FILES) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                        CleanupRules.LARGE_THRESHOLDS.forEach { threshold ->
                            FilterChip(
                                selected = scan.largeThreshold == threshold,
                                onClick = { viewModel.setLargeThreshold(threshold) },
                                label = { Text(stringResource(R.string.cleaner_larger_than, formatters.size.format(threshold))) },
                            )
                        }
                    }
                }
            }
            if (category == CleanupCategory.DUPLICATES) {
                section.groups.forEach { group ->
                    item(key = "o:" + group.original.path) {
                        FileRow(CleanupFile(group.original), formatters, viewModel::folderLabel, checked = null, badge = stringResource(R.string.cleaner_original), onToggle = {})
                    }
                    items(group.copies, key = { "c:" + it.path }) { copy ->
                        FileRow(CleanupFile(copy), formatters, viewModel::folderLabel, checked = selection.isSelected(CleanupFile(copy), category), onToggle = { viewModel.toggleFile(copy.path) })
                    }
                    item(key = "s:" + group.original.path) { Spacer(Modifier.height(12.dp)) }
                }
            } else {
                items(section.files, key = { it.path }) { file ->
                    FileRow(file, formatters, viewModel::folderLabel, checked = selection.isSelected(file, category), onToggle = { viewModel.toggleFile(file.path) })
                }
            }
        }
    }
}

/** Строка файла; [checked] = null — оригинал, его не удаляем и отметить нельзя. */
@Composable
private fun FileRow(
    file: CleanupFile,
    formatters: Formatters,
    folder: (String) -> String?,
    checked: Boolean?,
    onToggle: () -> Unit,
    badge: String? = null,
) {
    val entry = file.entry
    val appCache = entry.path == CleanupScanner.APP_CACHE_PATH
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp).background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(12.dp))
            .then(if (checked != null) Modifier.clickable(role = Role.Checkbox, onClick = onToggle) else Modifier)
            .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (checked != null) Checkbox(checked = checked, onCheckedChange = null)
        }
        FileTile(entry.category, path = entry.path.takeIf { file.trashId == null && !entry.isDirectory && !appCache })
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                if (appCache) stringResource(R.string.cleaner_app_cache) else entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                badge?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
                Text(formatters.size.format(entry.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val where = if (file.trashId != null || appCache) null else FileNames.parentOf(entry.path)?.let(folder)
            where?.let {
                Text(
                    it,
                    style = MonoTextStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                )
            }
        }
    }
}
