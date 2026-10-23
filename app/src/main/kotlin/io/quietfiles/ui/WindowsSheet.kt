package io.quietfiles.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.quietfiles.R
import io.quietfiles.core.common.displayPath
import io.quietfiles.core.designsystem.icon.QfIcons
import io.quietfiles.core.designsystem.theme.MonoTextStyle
import io.quietfiles.core.model.BrowseWindow
import io.quietfiles.core.model.OpenWindows
import io.quietfiles.core.model.StorageVolumeInfo
import io.quietfiles.core.model.WindowPlace

/** «Открытые окна» (F1.5): переключение, закрытие и новое окно. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WindowsSheet(
    windows: OpenWindows,
    volumeFor: (String) -> StorageVolumeInfo?,
    onSelect: (BrowseWindow) -> Unit,
    onClose: (BrowseWindow) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.windows_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
            Column(Modifier.clip(RoundedCornerShape(20.dp)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                windows.windows.forEach { window ->
                    val current = window.id == windows.currentId
                    val place = window.place
                    val title = place.title.ifEmpty { stringResource(R.string.windows_storages) }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp)
                            .background(if (current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable(role = Role.Tab) { onSelect(window) }
                            .semantics { selected = current }
                            .padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                iconFor(place),
                                null,
                                tint = if (current) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                            if (place.path.isNotEmpty()) {
                                Text(
                                    context.displayPath(place.path, volumeFor(place.path)),
                                    style = MonoTextStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                                    maxLines = 1,
                                    overflow = TextOverflow.StartEllipsis,
                                )
                            }
                        }
                        IconButton(onClick = { onClose(window) }) {
                            Icon(QfIcons.Close, stringResource(R.string.windows_close, title), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            FilledTonalButton(
                onClick = onNew,
                enabled = windows.windows.size < OpenWindows.MAX,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(QfIcons.Plus, null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.windows_new), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

private fun iconFor(place: WindowPlace): ImageVector = when (place.kind) {
    WindowPlace.ROOT -> QfIcons.Storage
    "ARCHIVE" -> QfIcons.Archive
    "TRASH" -> QfIcons.Trash
    "FAVORITES" -> QfIcons.Star
    "PHOTOS" -> QfIcons.Image
    "VIDEOS" -> QfIcons.Video
    "AUDIO" -> QfIcons.Music
    "DOCUMENTS" -> QfIcons.Document
    "APKS" -> QfIcons.Apk
    "RECENT" -> QfIcons.Clock
    else -> QfIcons.Folder
}
