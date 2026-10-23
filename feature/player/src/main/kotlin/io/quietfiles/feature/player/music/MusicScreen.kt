package io.quietfiles.feature.player.music

import android.content.ActivityNotFoundException
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import io.quietfiles.core.designsystem.icon.QfIcons
import io.quietfiles.core.designsystem.theme.MonoTextStyle
import io.quietfiles.core.model.FileNames
import io.quietfiles.core.model.media.PlaybackTime
import io.quietfiles.feature.player.R
import io.quietfiles.feature.player.common.SeekBar
import io.quietfiles.feature.player.common.SleepMode
import io.quietfiles.feature.player.common.SleepTimerDialog
import io.quietfiles.feature.player.common.ThumbStyle
import io.quietfiles.feature.player.common.sleepLabel
import kotlinx.coroutines.launch

/** «Сейчас играет» (F11, макет 22). */
@Composable
fun MusicScreen(
    onBack: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenFolder: (String) -> Unit,
    viewModel: MusicViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val infos by viewModel.infos.collectAsStateWithLifecycle()
    val favorite by viewModel.isFavorite.collectAsStateWithLifecycle()
    val sleep by viewModel.sleepMode.collectAsStateWithLifecycle()
    val position by viewModel.position.collectAsStateWithLifecycle(0L)
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var sleepDialog by remember { mutableStateOf(false) }
    var scrub by remember { mutableStateOf<Long?>(null) }

    // Очередь опустела (остановили, удалили последний трек) — экран больше не нужен.
    LaunchedEffect(state.active) { if (!state.active) onBack() }
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it) } }
    val item = state.current ?: return
    val info = infos[item.path]
    val noEqualizer = stringResource(R.string.music_no_equalizer)
    val endOfTrack = stringResource(R.string.music_sleep_end_of_track)

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.safeDrawingPadding()) {
            Column(Modifier.fillMaxSize().widthIn(max = 560.dp).align(Alignment.TopCenter)) {
                Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(QfIcons.ChevronDown, stringResource(R.string.music_collapse)) }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(R.string.music_now_playing),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(viewModel.folderLabel(item.path), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(QfIcons.More, stringResource(R.string.player_more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.player_show_in_folder)) },
                                onClick = {
                                    menu = false
                                    FileNames.parentOf(item.path)?.let(onOpenFolder)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.music_stop)) },
                                onClick = {
                                    menu = false
                                    viewModel.stop()
                                },
                            )
                        }
                    }
                }

                Cover(state, Modifier.padding(horizontal = 32.dp, vertical = 12.dp).weight(1f, fill = false))

                Row(Modifier.fillMaxWidth().padding(start = 32.dp, end = 24.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.title ?: item.name.substringBeforeLast('.'),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            state.artist ?: stringResource(R.string.music_unknown_artist),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = viewModel::toggleFavorite) {
                        Icon(
                            if (favorite) QfIcons.HeartFilled else QfIcons.Heart,
                            stringResource(if (favorite) R.string.music_unfavorite else R.string.music_favorite),
                            tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    viewModel.qualityLabel(item, info),
                    style = MonoTextStyle,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(start = 32.dp, top = 8.dp).clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 10.dp, vertical = 4.dp),
                )

                val duration = state.durationMs.takeIf { it > 0 } ?: info?.durationMs ?: 0
                Column(Modifier.padding(horizontal = 32.dp).padding(top = 16.dp)) {
                    SeekBar(
                        positionMs = position,
                        durationMs = duration,
                        onSeek = viewModel::seekTo,
                        onScrub = { scrub = it },
                        label = stringResource(R.string.music_position),
                        activeColor = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        trackHeight = 6.dp,
                        thumb = ThumbStyle.BAR,
                    )
                    Row(Modifier.fillMaxWidth()) {
                        TimeText(PlaybackTime.format(scrub ?: position), Modifier.weight(1f))
                        TimeText(PlaybackTime.format(duration))
                    }
                }

                Controls(state, viewModel)
                Spacer(Modifier.weight(0.01f))

                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolButton(QfIcons.Equalizer, stringResource(R.string.music_equalizer)) {
                        val intent = viewModel.equalizerIntent()
                        if (intent == null) {
                            scope.launch { snackbar.showSnackbar(noEqualizer) }
                        } else {
                            try {
                                context.startActivity(intent)
                            } catch (e: ActivityNotFoundException) {
                                scope.launch { snackbar.showSnackbar(noEqualizer) }
                            }
                        }
                    }
                    val timerLabel = sleepLabel(sleep, endOfTrack)
                    ToolButton(
                        QfIcons.Clock,
                        if (sleep == SleepMode.Off) stringResource(R.string.music_timer) else timerLabel,
                    ) { sleepDialog = true }
                    ToolButton(QfIcons.Queue, stringResource(R.string.music_queue_count, state.queue.size), onClick = onOpenQueue)
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp))
        }
    }

    if (sleepDialog) {
        SleepTimerDialog(sleep, endOfTrack, onSelect = {
            viewModel.setSleep(it)
            sleepDialog = false
        }, onDismiss = { sleepDialog = false })
    }
}

/** Обложка из тегов или cover.jpg в папке (F11.4); без неё — тональный квадрат с нотой. */
@Composable
internal fun Cover(state: MusicState, modifier: Modifier = Modifier, cornerRadius: Int = 28) {
    val shape = RoundedCornerShape(cornerRadius.dp)
    val model: Any? = state.artworkData ?: state.artworkUri
    var failed by remember(model) { mutableStateOf(false) }
    Box(
        modifier.aspectRatio(1f).clip(shape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (model == null || failed) {
            Icon(QfIcons.Music, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.fillMaxSize(0.36f))
        } else {
            AsyncImage(
                model = model,
                contentDescription = stringResource(R.string.music_cover),
                contentScale = ContentScale.Crop,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun Controls(state: MusicState, viewModel: MusicViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp).padding(top = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shuffleLabel = stringResource(if (state.shuffle) R.string.music_shuffle_on else R.string.music_shuffle_off)
        ToggleIcon(QfIcons.Shuffle, shuffleLabel, state.shuffle, onClick = viewModel::toggleShuffle)
        IconButton(onClick = viewModel::previous, modifier = Modifier.size(56.dp)) {
            Icon(QfIcons.SkipPrevious, stringResource(R.string.music_previous), modifier = Modifier.size(30.dp))
        }
        PlayButton(state.isPlaying, Modifier.size(width = 96.dp, height = 76.dp), cornerRadius = 26, iconSize = 34, onClick = viewModel::togglePlay)
        IconButton(onClick = viewModel::next, modifier = Modifier.size(56.dp)) {
            Icon(QfIcons.SkipNext, stringResource(R.string.music_next), modifier = Modifier.size(30.dp))
        }
        val repeatLabel = stringResource(
            when (state.repeatMode) {
                Player.REPEAT_MODE_ALL -> R.string.music_repeat_all
                Player.REPEAT_MODE_ONE -> R.string.music_repeat_one
                else -> R.string.music_repeat_off
            },
        )
        Box {
            ToggleIcon(QfIcons.Repeat, repeatLabel, state.repeatMode != Player.REPEAT_MODE_OFF, onClick = viewModel::cycleRepeat)
            if (state.repeatMode == Player.REPEAT_MODE_ONE) {
                Text(
                    "1",
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(14.dp).clip(RoundedCornerShape(7.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

/** Кнопка-переключатель: включённое состояние — тональный круг, как в макете. */
@Composable
private fun ToggleIcon(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(24.dp))
            .background(if (on) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                role = Role.Switch
                stateDescription = label
            },
    ) {
        Icon(icon, null, tint = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun PlayButton(playing: Boolean, modifier: Modifier, cornerRadius: Int, iconSize: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(cornerRadius.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                if (playing) QfIcons.PauseFilled else QfIcons.Play,
                stringResource(if (playing) R.string.player_pause else R.string.player_play),
                modifier = Modifier.size(iconSize.dp),
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ToolButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.weight(1f).height(48.dp),
    ) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun TimeText(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.padding(top = 2.dp))
}
