package io.quietfiles.core.designsystem.component

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import io.quietfiles.core.common.R
import io.quietfiles.core.designsystem.icon.QfIcons
import io.quietfiles.core.model.WindowPlace

/**
 * Открытые окна обзора (F1.5) для экранов: сколько их, как открыть список и куда сообщать,
 * какое место сейчас открыто. Предоставляет оболочка приложения.
 */
class WindowsController(
    val count: Int,
    val onShow: () -> Unit,
    val onPlace: (WindowPlace) -> Unit,
)

val LocalWindows = compositionLocalOf<WindowsController?> { null }

/** Кнопка «Открытые окна» со счётчиком (макет 01). */
@Composable
fun WindowsButton(modifier: Modifier = Modifier) {
    val windows = LocalWindows.current ?: return
    val label = pluralStringResource(R.plurals.windows_open, windows.count, windows.count)
    IconButton(onClick = windows.onShow, modifier = modifier) {
        BadgedBox(badge = { if (windows.count > 1) Badge(
                    Modifier.padding(top = 2.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) { Text("${windows.count}") } }) {
            Icon(QfIcons.Tabs, contentDescription = label)
        }
    }
}
