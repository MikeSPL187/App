package io.quietfiles.feature.player.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.quietfiles.core.designsystem.component.rememberFormatters
import io.quietfiles.core.designsystem.icon.QfIcons
import io.quietfiles.core.designsystem.theme.QfTheme
import io.quietfiles.core.model.FileCategory
import io.quietfiles.core.model.FileNames
import io.quietfiles.core.model.media.MediaLabels
import io.quietfiles.core.model.media.PlaybackTime
import io.quietfiles.feature.player.R

/** Очередь воспроизведения (F11.1, макет 23): порядок перетаскиванием, удаление смахиванием. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicQueueScreen(
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    viewModel: MusicViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val infos by viewModel.infos.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(state.active) { if (!state.active) onBack() }
    if (!state.active) return

    val formatters = rememberFormatters()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (state.index - 2).coerceAtLeast(0))
    val rowPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val queue by rememberUpdatedState(state.queue)

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(QfIcons.Back, stringResource(R.string.player_back)) }
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(stringResource(R.string.music_queue), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        queueSummary(state, infos.mapValues { it.value.durationMs }),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = viewModel::toggleShuffle) {
                    Icon(
                        QfIcons.Shuffle,
                        stringResource(if (state.shuffle) R.string.music_shuffle_on else R.string.music_shuffle_off),
                        tint = if (state.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(QfIcons.More, stringResource(R.string.player_more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.music_clear_queue)) }, onClick = {
                            menu = false
                            viewModel.stop()
                        })
                    }
                }
            }

            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(state.queue, key = { _, item -> item.path }) { index, item ->
                    val now = index == state.index
                    val dismiss = rememberSwipeToDismissBoxState()
                    LaunchedEffect(dismiss.currentValue) {
                        if (dismiss.currentValue != SwipeToDismissBoxValue.Settled) viewModel.remove(queue.indexOfFirst { it.path == item.path })
                    }
                    val isDragged = dragging == item.path
                    val moveUp = stringResource(R.string.music_move_up)
                    val moveDown = stringResource(R.string.music_move_down)
                    val removeLabel = stringResource(R.string.music_remove)
                    SwipeToDismissBox(
                        state = dismiss,
                        backgroundContent = {},
                        enableDismissFromStartToEnd = false,
                        modifier = Modifier
                            .zIndex(if (isDragged) 1f else 0f)
                            .graphicsLayer { translationY = if (isDragged) dragOffset else 0f }
                            .semantics {
                                customActions = listOfNotNull(
                                    CustomAccessibilityAction(moveUp) { viewModel.move(index, index - 1); true }.takeIf { index > 0 },
                                    CustomAccessibilityAction(moveDown) { viewModel.move(index, index + 1); true }.takeIf { index < state.queue.lastIndex },
                                    CustomAccessibilityAction(removeLabel) { viewModel.remove(index); true },
                                )
                            },
                    ) {
                        val info = infos[item.path]
                        Row(
                            Modifier.fillMaxWidth().height(ROW_HEIGHT - 2.dp).clip(RoundedCornerShape(16.dp))
                                .background(if (now) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                                .clickable { viewModel.playAt(index) }
                                .alpha(if (index < state.index && !state.shuffle) 0.6f else 1f)
                                .padding(start = 12.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            val audio = QfTheme.extended.of(FileCategory.AUDIO)
                            Box(
                                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                                    .background(if (now) MaterialTheme.colorScheme.primary else audio.container),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (now) {
                                    Icon(QfIcons.Playing, stringResource(R.string.music_playing), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
                                } else {
                                    Icon(QfIcons.Music, null, tint = audio.onContainer, modifier = Modifier.size(22.dp))
                                }
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (now) FontWeight.Bold else FontWeight.Medium,
                                    color = if (now) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.MiddleEllipsis,
                                )
                                Text(
                                    "${MediaLabels.formatFromName(item.name)} · ${formatters.size.format(item.size)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            info?.durationMs?.takeIf { it > 0 }?.let {
                                Text(PlaybackTime.format(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Box(
                                Modifier.size(44.dp).pointerInput(item.path) {
                                    detectDragGestures(
                                        onDragStart = {
                                            dragging = item.path
                                            dragOffset = 0f
                                        },
                                        onDragEnd = { dragging = null },
                                        onDragCancel = { dragging = null },
                                    ) { change, amount ->
                                        change.consume()
                                        dragOffset += amount.y
                                        val from = queue.indexOfFirst { it.path == item.path }
                                        // Сдвинули на полстроки — меняем местами с соседом.
                                        if (dragOffset > rowPx / 2 && from < queue.lastIndex) {
                                            viewModel.move(from, from + 1)
                                            dragOffset -= rowPx
                                        } else if (dragOffset < -rowPx / 2 && from > 0) {
                                            viewModel.move(from, from - 1)
                                            dragOffset += rowPx
                                        }
                                    }
                                },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(QfIcons.DragHandle, stringResource(R.string.music_drag), tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
            MiniPlayer(onOpen = onOpenPlayer, viewModel = viewModel, modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp))
        }
    }
}

/** «8 треков · 1 ч 12 мин · из папки «Музыка»». */
@Composable
private fun queueSummary(state: MusicState, durations: Map<String, Long>): String {
    val parts = mutableListOf(pluralStringResource(R.plurals.music_tracks, state.queue.size, state.queue.size))
    if (state.queue.all { (durations[it.path] ?: 0) > 0 }) {
        val total = state.queue.sumOf { durations[it.path] ?: 0 } / 60_000
        parts += if (total >= 60) {
            stringResource(R.string.player_hours_minutes, total / 60, total % 60)
        } else {
            stringResource(R.string.player_minutes_short, total.coerceAtLeast(1))
        }
    }
    val folders = state.queue.mapNotNull { FileNames.parentOf(it.path) }.distinct()
    folders.singleOrNull()?.let { parts += stringResource(R.string.music_from_folder, FileNames.nameOf(it)) }
    return parts.joinToString(" · ")
}

private val ROW_HEIGHT = 66.dp
