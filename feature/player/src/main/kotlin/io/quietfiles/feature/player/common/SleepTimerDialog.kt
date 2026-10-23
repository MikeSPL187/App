package io.quietfiles.feature.player.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.quietfiles.feature.player.R
import kotlinx.coroutines.delay

/**
 * Выбор таймера сна. [endOfItemLabel] — «До конца трека» или «До конца видео».
 * [onSelect] получает минуты, -1 для «до конца» и null для «выключить».
 */
@Composable
fun SleepTimerDialog(mode: SleepMode, endOfItemLabel: String, onSelect: (Int?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.player_sleep_timer)) },
        text = {
            Column(Modifier.selectableGroup()) {
                Option(stringResource(R.string.player_sleep_off), mode == SleepMode.Off) { onSelect(null) }
                SleepTimer.OPTIONS_MINUTES.forEach { minutes ->
                    Option(
                        pluralStringResource(R.plurals.player_minutes, minutes, minutes),
                        (mode as? SleepMode.At)?.minutes == minutes,
                    ) { onSelect(minutes) }
                }
                Option(endOfItemLabel, mode == SleepMode.EndOfItem) { onSelect(END_OF_ITEM) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.player_close)) } },
    )
}

@Composable
private fun Option(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Подпись состояния таймера: «Выкл.», «23 мин», «До конца трека». Обновляется раз в 15 с. */
@Composable
fun sleepLabel(mode: SleepMode, endOfItemLabel: String): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(mode) {
        while (mode is SleepMode.At) {
            now = System.currentTimeMillis()
            delay(15_000)
        }
    }
    return when (mode) {
        SleepMode.Off -> stringResource(R.string.player_sleep_off_short)
        SleepMode.EndOfItem -> endOfItemLabel
        is SleepMode.At -> {
            val minutes = ((mode.endsAt - now + 59_999) / 60_000).toInt().coerceAtLeast(1)
            pluralStringResource(R.plurals.player_minutes_left, minutes, minutes)
        }
    }
}

const val END_OF_ITEM = -1
