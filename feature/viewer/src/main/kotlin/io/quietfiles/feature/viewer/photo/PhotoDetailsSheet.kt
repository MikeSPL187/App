package io.quietfiles.feature.viewer.photo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.quietfiles.core.designsystem.component.rememberFormatters
import io.quietfiles.core.designsystem.icon.QfIcons
import io.quietfiles.core.designsystem.theme.MonoTextStyle
import io.quietfiles.core.designsystem.theme.QfTheme
import io.quietfiles.core.model.FileNames
import io.quietfiles.feature.viewer.R

/** Сведения о фото (F8.3, F8.4, макет 19). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhotoDetailsSheet(state: PhotoUiState, viewModel: PhotoViewModel, onOpenFolder: () -> Unit) {
    val photo = state.current ?: return
    val d = state.details
    val formatters = rememberFormatters()
    ModalBottomSheet(onDismissRequest = { viewModel.showDetails(false) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(photo.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 4.dp))
            Column(Modifier.clip(RoundedCornerShape(20.dp)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val ext = FileNames.extension(photo.name).uppercase()
                val resolution = d?.takeIf { it.width > 0 }?.let {
                    stringResource(R.string.photo_resolution, it.width, it.height, "%.1f".format(it.megapixels).replace('.', ','))
                }
                InfoRow(QfIcons.File, stringResource(R.string.photo_file), listOfNotNull(ext.ifEmpty { null }, formatters.size.format(photo.size), resolution).joinToString(" · "))
                InfoRow(QfIcons.Calendar, stringResource(R.string.photo_taken), formatters.date.long(d?.takenAt ?: photo.lastModified))
                val cameraLine = d?.let { PhotoDetails.cameraLine(it, stringResource(R.string.photo_seconds), stringResource(R.string.photo_mm)) }
                if (d?.camera != null || cameraLine != null) {
                    InfoRow(QfIcons.Camera, d?.camera ?: stringResource(R.string.photo_camera), cameraLine ?: "")
                }
                InfoRow(QfIcons.Folder, stringResource(R.string.photo_folder), viewModel.folderLabel(photo.path), mono = true, onClick = onOpenFolder)
            }
            if (d?.hasPrivateData == true) PrivacyBlock(d.hasLocation, state.stripOnShare, viewModel)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (d?.hasPrivateData == true) {
                    FilledTonalButton(onClick = viewModel::stripMetadata, modifier = Modifier.weight(1f).height(48.dp)) {
                        Text(stringResource(R.string.photo_strip_metadata))
                    }
                }
                Button(onClick = viewModel::share, modifier = Modifier.weight(1f).height(48.dp)) { Text(stringResource(R.string.photo_share)) }
            }
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String, mono: Boolean = false, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (value.isNotEmpty()) {
                Text(value, style = if (mono) MonoTextStyle.copy(color = MaterialTheme.colorScheme.onSurface) else MaterialTheme.typography.bodyLarge)
            }
        }
        if (onClick != null) Icon(QfIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.align(Alignment.CenterVertically))
    }
}

/** Предупреждение о геоданных (F8.4) и переключатель «удалять при отправке». */
@Composable
private fun PrivacyBlock(hasLocation: Boolean, strip: Boolean, viewModel: PhotoViewModel) {
    val ext = QfTheme.extended
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(ext.warningContainer).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(if (hasLocation) QfIcons.MapPin else QfIcons.Camera, null, tint = ext.onWarningContainer)
            Text(
                stringResource(if (hasLocation) R.string.photo_has_location else R.string.photo_has_serial),
                style = MaterialTheme.typography.titleSmall,
                color = ext.onWarningContainer,
            )
        }
        Row(
            Modifier.clickable(role = Role.Switch) { viewModel.setStripOnShare(!strip) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.photo_strip_on_share), style = MaterialTheme.typography.bodyMedium, color = ext.onWarningContainer, modifier = Modifier.weight(1f))
            Switch(checked = strip, onCheckedChange = null)
        }
    }
}
