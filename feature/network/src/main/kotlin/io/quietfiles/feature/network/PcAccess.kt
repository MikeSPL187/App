package io.quietfiles.feature.network

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.quietfiles.core.designsystem.component.rememberFormatters
import io.quietfiles.core.designsystem.icon.QfIcons
import io.quietfiles.core.designsystem.theme.MonoTextStyle
import io.quietfiles.core.network.server.PcAccessProblem
import io.quietfiles.core.network.server.PcAccessSettings
import io.quietfiles.core.network.server.PcAccessState
import io.quietfiles.core.network.server.PcAccessStop

/** Карточка «Доступ с ПК» в разделе «Сеть» (макет 08, F17). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PcAccessCard(
    state: PcAccessState,
    settings: PcAccessSettings,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpen: () -> Unit,
) {
    val context = LocalContext.current
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onStart() }
    val start = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onStart()
        }
    }
    val running = state as? PcAccessState.Running
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = if (running != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(28.dp)).clickable(onClick = onOpen),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(QfIcons.Laptop, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.pc_access_title), style = MaterialTheme.typography.titleMedium)
                    Text(pcStatusText(state), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = running != null,
                    onCheckedChange = { if (it) start() else onStop() },
                    modifier = Modifier.semantics { contentDescription = context.getString(R.string.pc_access_switch) },
                )
            }
            if (running != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(running.address, style = MonoTextStyle.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize), modifier = Modifier.weight(1f))
                    IconButton(onClick = onOpen) { Icon(QfIcons.Qr, stringResource(R.string.pc_access_show_qr)) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Flag(QfIcons.Lock, stringResource(if (running.anonymousReadOnly) R.string.pc_access_flag_anonymous else R.string.pc_access_flag_password))
                    Flag(QfIcons.Wifi, stringResource(R.string.pc_access_flag_wifi))
                    if (running.tls) Flag(QfIcons.Shield, stringResource(R.string.pc_access_flag_tls))
                }
            } else if (!settings.tls) {
                Text(stringResource(R.string.pc_access_plain_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun Flag(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun pcStatusText(state: PcAccessState): String = when (state) {
    is PcAccessState.Running -> if (state.clients.isEmpty()) {
        stringResource(R.string.pc_access_waiting)
    } else {
        pluralStringResource(R.plurals.pc_access_connected, state.clients.size, state.clients.size)
    }
    is PcAccessState.Stopped -> stringResource(
        when (state.reason) {
            PcAccessStop.IDLE -> R.string.pc_access_stopped_idle
            PcAccessStop.NETWORK_LOST -> R.string.pc_access_stopped_network
            else -> R.string.pc_access_off
        },
    )
    is PcAccessState.Failed -> stringResource(
        when (state.problem) {
            PcAccessProblem.NO_WIFI -> R.string.pc_access_no_wifi
            PcAccessProblem.PORT_BUSY -> R.string.pc_access_port_busy
            PcAccessProblem.NO_STORAGE -> R.string.pc_access_no_storage
            PcAccessProblem.TLS -> R.string.pc_access_tls_failed
        },
    )
}

/** Подробности сервера: QR-код, логин и пароль, отпечаток сертификата, клиенты, настройки (F17.3). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PcAccessSheet(
    state: PcAccessState,
    settings: PcAccessSettings,
    onSettings: (PcAccessSettings) -> Unit,
    onDisconnect: (Int) -> Unit,
    onNewPassword: () -> Unit,
    onDismiss: () -> Unit,
) {
    val running = state as? PcAccessState.Running
    val formatters = rememberFormatters()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.pc_access_title), style = MaterialTheme.typography.titleLarge)
            Text(pcStatusText(state), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (running != null) {
                QrCode(
                    running.address.replace("://", "://${running.user}@"),
                    Modifier.align(Alignment.CenterHorizontally).widthIn(max = 220.dp).fillMaxWidth(),
                )
                Text(stringResource(R.string.pc_access_how), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Credential(stringResource(R.string.pc_access_address), running.address)
                Credential(stringResource(R.string.pc_access_user), running.user)
                Credential(stringResource(R.string.pc_access_password), running.password)
                TextButton(onClick = onNewPassword) { Text(stringResource(R.string.pc_access_new_password)) }
                running.certificateSha256?.let {
                    Text(stringResource(R.string.pc_access_certificate), style = MaterialTheme.typography.labelLarge)
                    Text(it, style = MonoTextStyle)
                }
                Text(stringResource(R.string.pc_access_clients), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                if (running.clients.isEmpty()) {
                    Text(stringResource(R.string.pc_access_no_clients), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                running.clients.forEach { client ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(client.address, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                listOfNotNull(client.user, formatters.date.short(client.connectedAt), if (client.secure) "TLS" else null).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onDisconnect(client.id) }) { Text(stringResource(R.string.pc_access_disconnect)) }
                    }
                }
                Text(stringResource(R.string.pc_access_autostop), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                SettingRow(stringResource(R.string.pc_access_setting_tls), stringResource(R.string.pc_access_setting_tls_hint), settings.tls) {
                    onSettings(settings.copy(tls = it))
                }
                if (!settings.tls) Text(stringResource(R.string.pc_access_plain_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                SettingRow(stringResource(R.string.pc_access_setting_anonymous), stringResource(R.string.pc_access_setting_anonymous_hint), settings.anonymousReadOnly) {
                    onSettings(settings.copy(anonymousReadOnly = it))
                }
                Text(stringResource(R.string.pc_access_port, settings.port), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Switch) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun Credential(label: String, value: String) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MonoTextStyle.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize))
        }
        IconButton(onClick = {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, value))
        }) { Icon(QfIcons.Copy, stringResource(R.string.pc_access_copy, label)) }
    }
}

/** QR-код адреса (без пароля: его вводят на ПК). */
@Composable
private fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        runCatching {
            QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2))
        }.getOrNull()
    } ?: return
    val description = stringResource(R.string.pc_access_qr_description, text)
    Box(
        modifier.aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(Color.White)
            .semantics { contentDescription = description },
    ) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            val cell = size.width / matrix.width
            for (y in 0 until matrix.height) {
                for (x in 0 until matrix.width) {
                    if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}

