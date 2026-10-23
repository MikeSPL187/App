package io.quietfiles.feature.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.quietfiles.core.designsystem.component.CategoryTile
import io.quietfiles.core.designsystem.component.FileTile
import io.quietfiles.core.designsystem.component.Formatters
import io.quietfiles.core.designsystem.component.SectionTitle
import io.quietfiles.core.designsystem.component.SegmentedBar
import io.quietfiles.core.designsystem.component.WindowsButton
import io.quietfiles.core.designsystem.component.rememberFormatters
import io.quietfiles.core.designsystem.icon.QfIcons
import io.quietfiles.core.designsystem.theme.QfTheme
import io.quietfiles.core.model.FileCategory
import io.quietfiles.core.model.FileEntry
import io.quietfiles.feature.browser.BrowseRoute
import io.quietfiles.feature.browser.SourceKind
import io.quietfiles.feature.browser.volumeIcon
import io.quietfiles.feature.browser.volumeLabel
import kotlin.math.roundToInt

/** Главная (макет 01): поиск, память с разбивкой по типам, категории, недавние файлы. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpen: (BrowseRoute) -> Unit,
    onOpenFile: (FileEntry) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onRequestAccess: () -> Unit,
    onCleanup: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val formatters = rememberFormatters()
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineMedium) },
                actions = {
                    // «Открытые окна» со счётчиком — как в макете 01 (F1.5).
                    WindowsButton()
                    IconButton(onClick = onSettings) {
                        Icon(QfIcons.Settings, contentDescription = stringResource(R.string.home_settings))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item { SearchPill(onSearch) }
            if (!state.hasAccess) item { AccessCard(onRequestAccess) }
            items(state.volumes, key = { it.volume.id }) { card ->
                if (card.volume.isPrimary) {
                    PrimaryStorageCard(card, formatters, state.freeable, onOpen = { onOpen(BrowseRoute(SourceKind.FOLDER, card.volume.rootPath)) }, onCleanup = onCleanup)
                } else {
                    SecondaryStorageCard(card, formatters) { onOpen(BrowseRoute(SourceKind.FOLDER, card.volume.rootPath)) }
                }
            }
            item { SectionTitle(stringResource(R.string.home_categories)) }
            item { Categories(state, onOpen) }
            if (state.recent.isNotEmpty()) {
                item {
                    SectionTitle(
                        stringResource(R.string.home_recent),
                        actionLabel = stringResource(R.string.home_all),
                        onAction = { onOpen(BrowseRoute(SourceKind.RECENT)) },
                    )
                }
                item { RecentRow(state.recent, formatters, onOpenFile) }
            }
        }
    }
}

@Composable
private fun SearchPill(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 56.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(QfIcons.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.home_search_hint), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AccessCard(onRequest: () -> Unit) {
    val ext = QfTheme.extended
    Surface(
        shape = MaterialTheme.shapes.large,
        color = ext.warningContainer,
        contentColor = ext.onWarningContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(QfIcons.Lock, contentDescription = null)
                Text(stringResource(R.string.home_access_title), style = MaterialTheme.typography.titleSmall)
            }
            Text(stringResource(R.string.home_access_message), style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick = onRequest) { Text(stringResource(R.string.home_access_action)) }
        }
    }
}

@Composable
private fun usageColor(group: UsageGroup): Color {
    val ext = QfTheme.extended
    return when (group) {
        UsageGroup.PHOTOS -> ext.of(FileCategory.IMAGE).chart
        UsageGroup.VIDEOS -> ext.of(FileCategory.VIDEO).chart
        UsageGroup.AUDIO -> ext.of(FileCategory.AUDIO).chart
        UsageGroup.DOCUMENTS -> ext.of(FileCategory.DOCUMENT).chart
        UsageGroup.ARCHIVES -> ext.of(FileCategory.APK).chart
        UsageGroup.OTHER -> ext.of(FileCategory.OTHER).chart
    }
}

@Composable
private fun usageLabel(group: UsageGroup) = stringResource(
    when (group) {
        UsageGroup.PHOTOS -> R.string.usage_photos
        UsageGroup.VIDEOS -> R.string.usage_videos
        UsageGroup.AUDIO -> R.string.usage_audio
        UsageGroup.DOCUMENTS -> R.string.usage_documents
        UsageGroup.ARCHIVES -> R.string.usage_archives
        UsageGroup.OTHER -> R.string.usage_other
    },
)

@Composable
private fun PrimaryStorageCard(card: VolumeCardState, formatters: Formatters, freeable: Long?, onOpen: () -> Unit, onCleanup: () -> Unit) {
    val space = card.space
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.clip(MaterialTheme.shapes.medium).clickable(role = Role.Button, onClick = onOpen),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                    Icon(volumeIcon(card.volume), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Column(Modifier.weight(1f)) {
                    Text(volumeLabel(card.volume), style = MaterialTheme.typography.titleSmall)
                    if (space != null) {
                        Text(
                            stringResource(R.string.home_space, formatters.size.format(space.used), formatters.size.format(space.total), formatters.size.format(space.free)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (space != null) {
                    Text("${(space.usedFraction * 100).roundToInt()}%", style = MaterialTheme.typography.titleLarge)
                }
            }
            if (space != null) {
                val usage = card.usage
                val segments = if (usage != null) {
                    UsageGroup.entries.map { (usage[it] ?: 0L).toFloat() / space.total to usageColor(it) }
                } else {
                    listOf(space.usedFraction to MaterialTheme.colorScheme.primary)
                }
                val description = stringResource(R.string.home_used_percent, (space.usedFraction * 100).roundToInt())
                SegmentedBar(segments, modifier = Modifier.semantics { contentDescription = description })
                if (usage != null) Legend(usage, formatters)
            }
            // F2.3: сумма безопасных пунктов очистки, пока её считают — просто «Очистка памяти».
            FilledTonalButton(onClick = onCleanup, modifier = Modifier.fillMaxWidth()) {
                Icon(QfIcons.Sparkle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (freeable != null && freeable > 0) {
                        stringResource(R.string.home_can_free, formatters.size.format(freeable))
                    } else {
                        stringResource(R.string.home_cleanup)
                    },
                )
            }
        }
    }
}

@Composable
private fun Legend(usage: Map<UsageGroup, Long>, formatters: Formatters) {
    val rows = UsageGroup.entries.chunked(2)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                pair.forEach { group ->
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(usageColor(group)))
                        Text(usageLabel(group), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1)
                        Text(formatters.size.format(usage[group] ?: 0), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun SecondaryStorageCard(card: VolumeCardState, formatters: Formatters, onOpen: () -> Unit) {
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(volumeIcon(card.volume), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(volumeLabel(card.volume), style = MaterialTheme.typography.titleSmall)
                card.space?.let { space ->
                    Text(
                        stringResource(R.string.home_space, formatters.size.format(space.used), formatters.size.format(space.total), formatters.size.format(space.free)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SegmentedBar(listOf(space.usedFraction to MaterialTheme.colorScheme.primary), height = 6.dp)
                }
            }
            Icon(QfIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

private data class CategoryDef(val label: Int, val category: FileCategory, val route: BrowseRoute?)

@Composable
private fun Categories(state: HomeUiState, onOpen: (BrowseRoute) -> Unit) {
    val primary = state.volumes.firstOrNull { it.volume.isPrimary }?.volume?.rootPath
    val defs = listOf(
        CategoryDef(R.string.cat_photos, FileCategory.IMAGE, BrowseRoute(SourceKind.PHOTOS)),
        CategoryDef(R.string.cat_videos, FileCategory.VIDEO, BrowseRoute(SourceKind.VIDEOS)),
        CategoryDef(R.string.cat_audio, FileCategory.AUDIO, BrowseRoute(SourceKind.AUDIO)),
        CategoryDef(R.string.cat_documents, FileCategory.DOCUMENT, BrowseRoute(SourceKind.DOCUMENTS)),
        CategoryDef(R.string.cat_archives, FileCategory.ARCHIVE, BrowseRoute(SourceKind.ARCHIVES)),
        CategoryDef(R.string.cat_apks, FileCategory.APK, BrowseRoute(SourceKind.APKS)),
        CategoryDef(R.string.cat_downloads, FileCategory.OTHER, primary?.let { BrowseRoute(SourceKind.FOLDER, "$it/Download") }),
        CategoryDef(R.string.cat_favorites, FileCategory.OTHER, BrowseRoute(SourceKind.FAVORITES)),
    )
    val ext = QfTheme.extended
    Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        defs.chunked(4).forEach { row ->
            Row {
                row.forEach { def ->
                    val colors = when (def.label) {
                        R.string.cat_downloads -> ext.downloads
                        R.string.cat_favorites -> ext.favorites
                        else -> ext.of(def.category)
                    }
                    val icon = when (def.label) {
                        R.string.cat_downloads -> QfIcons.Download
                        R.string.cat_favorites -> QfIcons.Star
                        else -> QfIcons.forCategory(def.category)
                    }
                    CategoryTile(
                        label = stringResource(def.label),
                        icon = icon,
                        container = colors.container,
                        content = colors.onContainer,
                        onClick = { def.route?.let(onOpen) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentRow(items: List<FileEntry>, formatters: Formatters, onOpenFile: (FileEntry) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(items, key = { it.path }) { entry ->
            Column(
                modifier = Modifier.width(132.dp).clip(MaterialTheme.shapes.medium).clickable(role = Role.Button) { onOpenFile(entry) },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box {
                    FileTile(entry.category, path = entry.path, size = 132.dp, cornerRadius = 16.dp)
                    if (entry.extension.isNotEmpty()) {
                        Text(
                            entry.extension.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(8.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
                Column(Modifier.padding(horizontal = 2.dp)) {
                    Text(entry.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    Text(
                        formatters.size.format(entry.size) + " · " + formatters.date.short(entry.lastModified),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}
