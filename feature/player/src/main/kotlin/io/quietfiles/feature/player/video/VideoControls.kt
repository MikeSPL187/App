package io.quietfiles.feature.player.video

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.quietfiles.core.designsystem.component.rememberFormatters
import io.quietfiles.core.designsystem.icon.QfIcons
import io.quietfiles.core.model.media.MediaLabels
import io.quietfiles.core.model.media.PlaybackTime
import io.quietfiles.core.model.media.PlayerGestures
import io.quietfiles.feature.player.R
import io.quietfiles.feature.player.common.SeekBar

private val Muted = Color(0xFFD9DEDD)

/** Шапка, кнопки по центру и шкала видеоплеера (макет 20). */
@Composable
internal fun VideoControls(
    state: VideoUiState,
    positionMs: Long,
    bufferedMs: Long,
    supportsPip: Boolean,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeek: (Long) -> Unit,
    onScrub: (Long?) -> Unit,
    onSettings: () -> Unit,
    onAudio: () -> Unit,
    onPip: () -> Unit,
    onLock: () -> Unit,
    onScale: () -> Unit,
    onRotate: () -> Unit,
    onNext: () -> Unit,
    onOpenWith: () -> Unit,
    onShowInFolder: () -> Unit,
) {
    val formatters = rememberFormatters()
    val locale = LocalConfiguration.current.locales[0]
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f))) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(QfIcons.Back, stringResource(R.string.player_back), tint = Color.White) }
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(state.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    val meta = listOfNotNull(
                        MediaLabels.resolution(state.videoWidth, state.videoHeight),
                        state.videoCodec,
                        state.size.takeIf { it > 0 }?.let(formatters.size::format),
                    ).joinToString(" · ")
                    if (meta.isNotEmpty()) Text(meta, color = Muted, fontSize = 12.5.sp, maxLines = 1)
                }
                Surface(
                    onClick = onSettings,
                    shape = RoundedCornerShape(18.dp),
                    color = Color.Transparent,
                    contentColor = Color.White,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                    modifier = Modifier.height(36.dp),
                ) {
                    Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                        Text(PlaybackTime.speedLabel(state.speed, locale), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                TopIcon(QfIcons.Subtitles, stringResource(R.string.video_subtitles), onSettings)
                if (state.audioTracks.size > 1) TopIcon(QfIcons.AudioTrack, stringResource(R.string.video_audio_track), onAudio)
                if (supportsPip) TopIcon(QfIcons.PictureInPicture, stringResource(R.string.video_pip), onPip)
                Box {
                    TopIcon(QfIcons.More, stringResource(R.string.player_more)) { menu = true }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.video_settings)) }, onClick = {
                            menu = false
                            onSettings()
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.player_open_with)) }, onClick = {
                            menu = false
                            onOpenWith()
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.player_show_in_folder)) }, onClick = {
                            menu = false
                            onShowInFolder()
                        })
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                // При ошибке в центре — её панель, кнопки воспроизведения там бесполезны.
                if (state.error == null) Row(horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    SeekButton(QfIcons.Rewind, stringResource(R.string.video_back_10_label)) { onSeekBy(-PlayerGestures.DOUBLE_TAP_SEEK_MS) }
                    Surface(
                        onClick = onTogglePlay,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(76.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (state.isPlaying) QfIcons.PauseFilled else QfIcons.Play,
                                stringResource(if (state.isPlaying) R.string.player_pause else R.string.player_play),
                                modifier = Modifier.size(32.dp),
                            )
                        }
                    }
                    SeekButton(QfIcons.Forward, stringResource(R.string.video_forward_10_label)) { onSeekBy(PlayerGestures.DOUBLE_TAP_SEEK_MS) }
                }
            }

            Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(PlaybackTime.format(positionMs), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    SeekBar(
                        positionMs = positionMs,
                        durationMs = state.durationMs,
                        bufferedMs = bufferedMs,
                        onSeek = onSeek,
                        onScrub = onScrub,
                        label = stringResource(R.string.video_position),
                        activeColor = MaterialTheme.colorScheme.primary,
                        trackColor = Color.White.copy(alpha = 0.3f),
                        bufferedColor = Color.White.copy(alpha = 0.55f),
                        modifier = Modifier.weight(1f),
                    )
                    Text(PlaybackTime.format(state.durationMs), color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    BottomIcon(QfIcons.Lock, stringResource(R.string.video_lock), onLock)
                    BottomIcon(
                        QfIcons.Fit,
                        stringResource(
                            R.string.video_scale_current,
                            stringResource(
                                when (state.scale) {
                                    VideoScale.FIT -> R.string.video_scale_fit
                                    VideoScale.FILL -> R.string.video_scale_fill
                                    VideoScale.STRETCH -> R.string.video_scale_stretch
                                },
                            ),
                        ),
                        onScale,
                    )
                    BottomIcon(QfIcons.ScreenRotation, stringResource(R.string.video_rotate), onRotate)
                    Spacer(Modifier.weight(1f))
                    state.next?.let { next ->
                        Surface(
                            onClick = onNext,
                            shape = RoundedCornerShape(20.dp),
                            color = Color.White.copy(alpha = 0.16f),
                            contentColor = Color.White,
                            modifier = Modifier.height(40.dp).widthIn(max = 320.dp),
                        ) {
                            Row(Modifier.padding(start = 12.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(QfIcons.SkipNext, null, modifier = Modifier.size(18.dp))
                                Text(
                                    stringResource(R.string.video_next, next.substringAfterLast('/')),
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.MiddleEllipsis,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, label, tint = Color.White) }
}

@Composable
private fun BottomIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(22.dp)) }
}

/** ±10 с: круглая кнопка с цифрой внутри стрелки. */
@Composable
private fun SeekButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = Color.Black.copy(alpha = 0.35f), contentColor = Color.White, modifier = Modifier.size(56.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, label, modifier = Modifier.size(34.dp))
            Text("10", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp))
        }
    }
}
