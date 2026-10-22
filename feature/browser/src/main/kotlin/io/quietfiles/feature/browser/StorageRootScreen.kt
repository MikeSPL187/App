package io.quietfiles.feature.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.quietfiles.core.data.repository.Favorite
import io.quietfiles.core.data.repository.FavoritesRepository
import io.quietfiles.core.designsystem.component.SectionTitle
import io.quietfiles.core.designsystem.component.SegmentedBar
import io.quietfiles.core.designsystem.component.rememberFormatters
import io.quietfiles.core.designsystem.icon.QfIcons
import io.quietfiles.core.model.FileCategory
import io.quietfiles.core.model.FileNames
import io.quietfiles.core.model.StorageSpace
import io.quietfiles.core.model.StorageVolumeInfo
import io.quietfiles.core.operations.TrashManager
import io.quietfiles.core.storage.StorageProvider
import io.quietfiles.core.storage.StorageVolumes
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.File
import javax.inject.Inject

data class VolumeUi(val volume: StorageVolumeInfo, val space: StorageSpace?)

data class StorageRootState(
    val volumes: List<VolumeUi> = emptyList(),
    val places: List<Pair<PlaceKind, String>> = emptyList(),
    val favorites: List<Favorite> = emptyList(),
    val trashCount: Int = 0,
)

enum class PlaceKind { DOWNLOADS, DOCUMENTS, CAMERA, PICTURES }

@HiltViewModel
class StorageRootViewModel @Inject constructor(
    volumes: StorageVolumes,
    provider: StorageProvider,
    favorites: FavoritesRepository,
    trash: TrashManager,
) : ViewModel() {
    val state: StateFlow<StorageRootState> = combine(
        volumes.observe().map { list -> list.map { VolumeUi(it, provider.space(it.rootPath)) } },
        favorites.favorites,
        trash.items.map { it.size },
    ) { vols, favs, trashCount ->
        val primary = vols.firstOrNull { it.volume.isPrimary }?.volume?.rootPath
        val places = if (primary == null) emptyList() else listOf(
            PlaceKind.DOWNLOADS to "$primary/Download",
            PlaceKind.DOCUMENTS to "$primary/Documents",
            PlaceKind.CAMERA to "$primary/DCIM/Camera",
            PlaceKind.PICTURES to "$primary/Pictures",
        ).filter { File(it.second).isDirectory }
        StorageRootState(vols, places, favs.filter { it.isDirectory }, trashCount)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StorageRootState())
}

/** Корень раздела «Обзор»: тома с заполненностью, частые папки, избранное и корзина. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageRootScreen(
    onOpen: (BrowseRoute) -> Unit,
    onSearch: () -> Unit,
    viewModel: StorageRootViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val formatters = rememberFormatters()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.browser_title_storage), style = MaterialTheme.typography.headlineMedium) },
                actions = {
                    IconButton(onClick = onSearch) { Icon(QfIcons.Search, contentDescription = stringResource(R.string.action_search)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { SectionTitle(stringResource(R.string.root_storages)) }
            items(state.volumes, key = { it.volume.id }) { v ->
                VolumeCard(v, formatters) { onOpen(BrowseRoute(SourceKind.FOLDER, v.volume.rootPath)) }
            }
            if (state.places.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.root_places)) }
                item {
                    Group {
                        state.places.forEach { (kind, path) ->
                            val (label, icon) = when (kind) {
                                PlaceKind.DOWNLOADS -> stringResource(R.string.root_downloads) to QfIcons.Download
                                PlaceKind.DOCUMENTS -> stringResource(R.string.root_documents) to QfIcons.Document
                                PlaceKind.CAMERA -> stringResource(R.string.root_camera) to QfIcons.Image
                                PlaceKind.PICTURES -> stringResource(R.string.root_pictures) to QfIcons.Image
                            }
                            GroupRow(icon, label, null) { onOpen(BrowseRoute(SourceKind.FOLDER, path)) }
                        }
                    }
                }
            }
            item { SectionTitle(stringResource(R.string.browser_title_favorites)) }
            item {
                Group {
                    state.favorites.take(6).forEach { fav ->
                        GroupRow(QfIcons.Folder, fav.name, FileNames.parentOf(fav.path)?.let(FileNames::nameOf)) {
                            onOpen(BrowseRoute(SourceKind.FOLDER, fav.path))
                        }
                    }
                    GroupRow(QfIcons.Star, stringResource(R.string.browser_title_favorites), null) {
                        onOpen(BrowseRoute(SourceKind.FAVORITES))
                    }
                    GroupRow(
                        QfIcons.Trash,
                        stringResource(R.string.browser_title_trash),
                        if (state.trashCount > 0) pluralStringResource(io.quietfiles.core.common.R.plurals.items_count, state.trashCount, state.trashCount) else null,
                    ) { onOpen(BrowseRoute(SourceKind.TRASH)) }
                }
            }
        }
    }
}

@Composable
private fun VolumeCard(v: VolumeUi, formatters: io.quietfiles.core.designsystem.component.Formatters, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(volumeIcon(v.volume), null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Column(Modifier.weight(1f)) {
                    Text(volumeLabel(v.volume), style = MaterialTheme.typography.titleSmall)
                    v.space?.let {
                        Text(
                            stringResource(R.string.volume_used_of, formatters.size.format(it.used), formatters.size.format(it.total)) +
                                stringResource(R.string.meta_separator) +
                                stringResource(R.string.volume_free, formatters.size.format(it.free)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(QfIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
            }
            v.space?.let { SegmentedBar(listOf(it.usedFraction to MaterialTheme.colorScheme.primary), height = 8.dp) }
        }
    }
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Column(
        Modifier.padding(horizontal = 16.dp).clip(MaterialTheme.shapes.large),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) { content() }
}

@Composable
private fun GroupRow(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 60.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val colors = io.quietfiles.core.designsystem.theme.QfTheme.extended.of(FileCategory.FOLDER)
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(colors.container), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = colors.onContainer, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(QfIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
    }
}
