package io.quietfiles.feature.player.video

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.quietfiles.core.designsystem.icon.QfIcons
import io.quietfiles.core.model.media.PlaybackTime
import io.quietfiles.feature.player.R
import io.quietfiles.feature.player.common.SleepTimerDialog
import io.quietfiles.feature.player.common.sleepLabel

/** Настройки воспроизведения (F9.4, макет 21): скорость, субтитры, масштаб, звук, таймер сна. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VideoSettingsSheet(state: VideoUiState, viewModel: VideoViewModel, onLoadSubtitles: () -> Unit, onDismiss: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val sleep by viewModel.sleepTimer.mode.collectAsStateWithLifecycle()
    var sleepDialog by remember { mutableStateOf(false) }
    var audioDialog by remember { mutableStateOf(false) }
    val endOfVideo = stringResource(R.string.video_sleep_end_of_video)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.video_settings), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))

            Section(stringResource(R.string.video_speed)) {
                Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PlaybackTime.SPEEDS.forEach { speed ->
                        val selected = speed == state.speed
                        Surface(
                            selected = selected,
                            onClick = { viewModel.setSpeed(speed) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                            contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                            border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.weight(1f).height(40.dp),
                        ) {
                            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    PlaybackTime.speedLabel(speed, locale),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
            }

            Section(stringResource(R.string.video_subtitles)) {
                Column(Modifier.clip(RoundedCornerShape(20.dp)).selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    RadioRow(stringResource(R.string.video_subtitles_off), null, state.subtitlesOff) { viewModel.selectSubtitle(null) }
                    state.subtitles.forEach { option ->
                        RadioRow(option.label, option.detail, option.selected) { viewModel.selectSubtitle(option.key) }
                    }
                    Row(
                        Modifier.fillMaxWidth().height(52.dp).background(MaterialTheme.colorScheme.surfaceContainer)
                            .clickable(role = Role.Button, onClick = onLoadSubtitles).padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(QfIcons.Plus, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Text(stringResource(R.string.video_subtitles_load), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            Section(stringResource(R.string.video_scale)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    VideoScale.entries.forEachIndexed { i, scale ->
                        SegmentedButton(
                            selected = state.scale == scale,
                            onClick = { viewModel.setScale(scale) },
                            shape = SegmentedButtonDefaults.itemShape(i, VideoScale.entries.size),
                            icon = {},
                        ) {
                            Text(
                                stringResource(
                                    when (scale) {
                                        VideoScale.FIT -> R.string.video_scale_fit
                                        VideoScale.FILL -> R.string.video_scale_fill
                                        VideoScale.STRETCH -> R.string.video_scale_stretch
                                    },
                                ),
                            )
                        }
                    }
                }
            }

            Column(Modifier.clip(RoundedCornerShape(20.dp)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val audio = state.audioTracks.firstOrNull { it.selected }
                ValueRow(
                    QfIcons.AudioTrack,
                    stringResource(R.string.video_audio_track),
                    audio?.let { listOfNotNull(it.label, it.detail).joinToString(" · ") } ?: stringResource(R.string.video_audio_none),
                    enabled = state.audioTracks.size > 1,
                ) { audioDialog = true }
                ValueRow(QfIcons.Clock, stringResource(R.string.player_sleep_timer), sleepLabel(sleep, endOfVideo)) { sleepDialog = true }
            }
        }
    }

    if (sleepDialog) {
        SleepTimerDialog(sleep, endOfVideo, onSelect = {
            viewModel.setSleep(it)
            sleepDialog = false
        }, onDismiss = { sleepDialog = false })
    }
    if (audioDialog) {
        TrackDialog(stringResource(R.string.video_audio_track), state.audioTracks, onSelect = {
            viewModel.selectAudio(it)
            audioDialog = false
        }, onDismiss = { audioDialog = false })
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp))
        content()
    }
}

@Composable
private fun RadioRow(label: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).background(MaterialTheme.colorScheme.surfaceContainer)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun ValueRow(icon: ImageVector, label: String, value: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Выбор аудиодорожки. */
@Composable
internal fun TrackDialog(title: String, options: List<TrackOption>, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup()) {
                options.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp)
                            .selectable(selected = option.selected, role = Role.RadioButton) { onSelect(option.key) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = option.selected, onClick = null)
                        Column {
                            Text(option.label, style = MaterialTheme.typography.bodyLarge)
                            option.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.player_close)) } },
    )
}
