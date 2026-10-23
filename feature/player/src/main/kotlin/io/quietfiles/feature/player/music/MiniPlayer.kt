package io.quietfiles.feature.player.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.quietfiles.core.designsystem.icon.QfIcons
import io.quietfiles.core.model.media.PlaybackTime
import io.quietfiles.feature.player.R

/** Мини-плеер над нижней навигацией (F11.3, макет 23): виден, пока в очереди есть музыка. */
@Composable
fun MiniPlayer(onOpen: () -> Unit, modifier: Modifier = Modifier, viewModel: MusicViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val position by viewModel.position.collectAsStateWithLifecycle(0L)
    val item = state.current ?: return
    val title = state.title ?: item.name.substringBeforeLast('.')
    val openLabel = stringResource(R.string.music_open_player)
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 6.dp,
        modifier = modifier.fillMaxWidth().height(72.dp).semantics { contentDescription = openLabel },
    ) {
        Box {
            Row(
                Modifier.fillMaxHeight().padding(start = 10.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Cover(state, Modifier.size(52.dp), cornerRadius = 14)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${PlaybackTime.format(position)} / ${PlaybackTime.format(state.durationMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PlayButton(state.isPlaying, Modifier.size(48.dp), cornerRadius = 16, iconSize = 22, onClick = viewModel::togglePlay)
                IconButton(onClick = viewModel::next, modifier = Modifier.size(width = 44.dp, height = 48.dp)) {
                    Icon(QfIcons.SkipNext, stringResource(R.string.music_next))
                }
            }
            LinearProgressIndicator(
                progress = { if (state.durationMs > 0) (position.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeCap = StrokeCap.Butt,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}
