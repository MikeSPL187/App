package io.quietfiles.feature.cleaner

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.quietfiles.core.data.repository.CleanupRecord
import io.quietfiles.core.designsystem.component.Formatters
import io.quietfiles.core.designsystem.component.rememberFormatters
import io.quietfiles.core.designsystem.icon.QfIcons
import io.quietfiles.core.designsystem.theme.QfTheme
import io.quietfiles.core.model.FileCategory
import io.quietfiles.core.model.cleanup.CleanupCategory
import io.quietfiles.core.model.cleanup.CleanupRules
import io.quietfiles.core.model.cleanup.CleanupSection
import io.quietfiles.core.model.cleanup.CleanupSelection
import io.quietfiles.core.operations.cleanup.CleanupScanState
import io.quietfiles.core.operations.cleanup.ScanPhase

/** Дополнительные разделы «Инструментов» внизу экрана. */
enum class ToolLink { TRASH, ANALYSIS, RECENT, FAVORITES }

/** Раздел «Инструменты» с очисткой (F13, макет 07). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanerScreen(
    onOpenDetails: (CleanupCategory) -> Unit,
    onTool: (ToolLink) -> Unit,
    onSettings: () -> Unit,
    onRequestAccess: () -> Unit,
    viewModel: CleanerViewModel = hiltViewModel(),
) {
    val scan by viewModel.scan.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val space by viewModel.space.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val formatters = rememberFormatters()
    val snackbar = remember { SnackbarHostState() }
    var showHistory by remember { mutableStateOf(false) }
    val systemCache = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { viewModel.rescan() }

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val freedText = stringResource(R.string.cleaner_freed)
    val result by viewModel.result.collectAsStateWithLifecycle()
    val cacheIntent by viewModel.systemCache.collectAsStateWithLifecycle()
    LaunchedEffect(cacheIntent) {
        cacheIntent?.let {
            viewModel.consumeSystemCache()
            runCatching { systemCache.launch(it) }
        }
    }
    LaunchedEffect(result) {
        result?.let {
            viewModel.consumeResult()
            snackbar.showSnackbar(freedText.format(formatters.size.format(it.bytes)))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cleaner_title), style = MaterialTheme.typography.headlineMedium) },
                actions = {
                    IconButton(onClick = { showHistory = true }) { Icon(QfIcons.History, stringResource(R.string.cleaner_history)) }
                    IconButton(onClick = onSettings) { Icon(QfIcons.Settings, stringResource(R.string.cleaner_settings)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            SummaryCard(
                scan = scan,
                selection = selection,
                usedFraction = space?.usedFraction,
                formatters = formatters,
                hasAccess = viewModel.hasAccess,
                onClean = { viewModel.clean() },
                onRequestAccess = onRequestAccess,
            )

            val sections = scan.sections.filter { it.count > 0 }
            if (scan.scannedAt != null) {
                SectionTitle(stringResource(R.string.cleaner_recommendations))
                if (sections.isEmpty()) {
                    Text(
                        stringResource(R.string.cleaner_nothing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                } else {
                    Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(24.dp)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        sections.sortedBy { it.category.ordinal }.forEach { section ->
                            RecommendationRow(
                                section = section,
                                checked = section.category in selection.categories,
                                selectedBytes = selection.copy(categories = setOf(section.category)).selectedBytes(listOf(section)),
                                scan = scan,
                                formatters = formatters,
                                onCheck = { viewModel.toggleCategory(section.category) },
                                onOpen = { onOpenDetails(section.category) },
                            )
                        }
                    }
                }
            }

            SectionTitle(stringResource(R.string.cleaner_more_tools))
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolTile(QfIcons.Trash, stringResource(R.string.cleaner_tool_trash)) { onTool(ToolLink.TRASH) }
                ToolTile(QfIcons.PieChart, stringResource(R.string.cleaner_tool_analysis)) { onTool(ToolLink.ANALYSIS) }
                ToolTile(QfIcons.Clock, stringResource(R.string.cleaner_tool_recent)) { onTool(ToolLink.RECENT) }
                ToolTile(QfIcons.Star, stringResource(R.string.cleaner_tool_favorites)) { onTool(ToolLink.FAVORITES) }
            }
        }
    }

    if (showHistory) HistoryDialog(history, formatters) { showHistory = false }
}

@Composable
private fun SummaryCard(
    scan: CleanupScanState,
    selection: CleanupSelection,
    usedFraction: Float?,
    formatters: Formatters,
    hasAccess: Boolean,
    onClean: () -> Unit,
    onRequestAccess: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            UsageRing(usedFraction)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                when {
                    !hasAccess -> {
                        Text(stringResource(R.string.cleaner_no_access), style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = onRequestAccess, modifier = Modifier.padding(top = 8.dp).fillMaxWidth()) {
                            Text(stringResource(R.string.cleaner_grant_access))
                        }
                    }
                    scan.scanning || scan.scannedAt == null -> {
                        Text(stringResource(R.string.cleaner_scanning), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(
                                when (scan.phase) {
                                    ScanPhase.FILES -> R.string.cleaner_phase_files
                                    ScanPhase.DUPLICATES -> R.string.cleaner_phase_duplicates
                                    ScanPhase.APKS, ScanPhase.DONE -> R.string.cleaner_phase_apks
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val progress = scan.progress
                        if (progress != null) {
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
                        }
                    }
                    else -> {
                        val bytes = selection.selectedBytes(scan.sections)
                        Text(stringResource(R.string.cleaner_can_free), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            formatters.size.format(bytes),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(stringResource(R.string.cleaner_safe_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = onClean, enabled = bytes > 0, modifier = Modifier.padding(top = 10.dp).fillMaxWidth().height(40.dp)) {
                            Icon(QfIcons.Sparkle, null, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.cleaner_clean_selected), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Кольцо занятого места на основном томе. */
@Composable
private fun UsageRing(fraction: Float?) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val active = MaterialTheme.colorScheme.primary
    val percent = fraction?.let { (it * 100).toInt() }
    val label = percent?.let { stringResource(R.string.cleaner_used_percent, it) }.orEmpty()
    Box(Modifier.size(112.dp).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            if (fraction != null) {
                drawArc(active, -90f, 360f * fraction, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(percent?.let { "$it%" } ?: "—", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.cleaner_used), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Внешний вид пункта: значок и тональный цвет категории, как в макете. */
internal data class CategoryLook(val icon: ImageVector, val tone: FileCategory)

internal fun lookOf(category: CleanupCategory) = when (category) {
    CleanupCategory.DUPLICATES -> CategoryLook(QfIcons.Copy, FileCategory.IMAGE)
    CleanupCategory.LARGE_FILES -> CategoryLook(QfIcons.BarChart, FileCategory.VIDEO)
    CleanupCategory.INSTALLED_APKS -> CategoryLook(QfIcons.Apk, FileCategory.APK)
    CleanupCategory.CACHE -> CategoryLook(QfIcons.Database, FileCategory.DOCUMENT)
    CleanupCategory.OLD_TRASH -> CategoryLook(QfIcons.Trash, FileCategory.OTHER)
    CleanupCategory.OLD_DOWNLOADS -> CategoryLook(QfIcons.Download, FileCategory.ARCHIVE)
}

@Composable
internal fun categoryTitle(category: CleanupCategory): String = stringResource(
    when (category) {
        CleanupCategory.DUPLICATES -> R.string.cleaner_duplicates
        CleanupCategory.LARGE_FILES -> R.string.cleaner_large
        CleanupCategory.INSTALLED_APKS -> R.string.cleaner_apks
        CleanupCategory.CACHE -> R.string.cleaner_cache
        CleanupCategory.OLD_TRASH -> R.string.cleaner_old_trash
        CleanupCategory.OLD_DOWNLOADS -> R.string.cleaner_old_downloads
    },
)

/** Подпись с причиной безопасности (F13.4): «48 копий в 12 группах · оригиналы останутся». */
@Composable
internal fun categorySubtitle(section: CleanupSection, scan: CleanupScanState, formatters: Formatters): String {
    val n = section.count
    return when (section.category) {
        CleanupCategory.DUPLICATES ->
            pluralStringResource(R.plurals.cleaner_copies, n, n) + " " +
                pluralStringResource(R.plurals.cleaner_in_groups, section.groups.size, section.groups.size) + " · " +
                stringResource(R.string.cleaner_originals_stay)
        CleanupCategory.LARGE_FILES ->
            stringResource(R.string.cleaner_larger_than, formatters.size.format(scan.largeThreshold)) + " · " +
                pluralStringResource(R.plurals.cleaner_files, n, n) + " · " + stringResource(R.string.cleaner_check_yourself)
        CleanupCategory.INSTALLED_APKS -> stringResource(R.string.cleaner_apps_installed) + " · " + pluralStringResource(R.plurals.cleaner_files, n, n)
        CleanupCategory.CACHE -> stringResource(R.string.cleaner_cache_hint)
        CleanupCategory.OLD_TRASH -> pluralStringResource(R.plurals.cleaner_older_than_days, scan.trashAgeDays, scan.trashAgeDays) + " · " +
            pluralStringResource(R.plurals.cleaner_files, n, n)
        CleanupCategory.OLD_DOWNLOADS -> pluralStringResource(R.plurals.cleaner_unchanged_days, CleanupRules.OLD_DOWNLOAD_DAYS, CleanupRules.OLD_DOWNLOAD_DAYS) + " · " +
            pluralStringResource(R.plurals.cleaner_files, n, n) + " · " + stringResource(R.string.cleaner_check_yourself)
    }
}

@Composable
private fun RecommendationRow(
    section: CleanupSection,
    checked: Boolean,
    selectedBytes: Long,
    scan: CleanupScanState,
    formatters: Formatters,
    onCheck: () -> Unit,
    onOpen: () -> Unit,
) {
    val look = lookOf(section.category)
    val colors = QfTheme.extended.of(look.tone)
    val title = categoryTitle(section.category)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .clickable(role = Role.Button, onClick = onOpen).padding(start = 2.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = { onCheck() }, modifier = Modifier.semantics { contentDescription = title })
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(colors.container), contentAlignment = Alignment.Center) {
            Icon(look.icon, null, tint = colors.onContainer, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                categorySubtitle(section, scan, formatters),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            formatters.size.format(if (checked) selectedBytes else section.size),
            style = MaterialTheme.typography.labelLarge,
            color = if (checked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(QfIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp))
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ToolTile(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.weight(1f).heightIn(min = 76.dp),
    ) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

@Composable
private fun HistoryDialog(records: List<CleanupRecord>, formatters: Formatters, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cleaner_history)) },
        text = {
            if (records.isEmpty()) {
                Text(stringResource(R.string.cleaner_history_empty))
            } else {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    records.forEach { r ->
                        Row(Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text(formatters.date.long(r.at), style = MaterialTheme.typography.bodyMedium)
                                Text(pluralStringResource(R.plurals.cleaner_files, r.files, r.files), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(formatters.size.format(r.bytes), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cleaner_close)) } },
    )
}
