package io.quietfiles.feature.network

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.quietfiles.core.designsystem.icon.QfIcons
import io.quietfiles.core.designsystem.theme.MonoTextStyle
import io.quietfiles.core.model.network.AuthMethod
import io.quietfiles.core.model.network.FtpSecurity
import io.quietfiles.core.model.network.Protocol
import io.quietfiles.core.model.network.RemoteProblem

/** Новое подключение (F16.3, макет 09). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConnectionScreen(
    onBack: () -> Unit,
    onConnected: (String) -> Unit,
    viewModel: ConnectionViewModel = hiltViewModel(),
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val checking by viewModel.checking.collectAsStateWithLifecycle()
    val problem by viewModel.problem.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val discovered by viewModel.discovered.collectAsStateWithLifecycle()
    val discovering by viewModel.discovering.collectAsStateWithLifecycle()
    val pickKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::importKey) }

    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is ConnectionEvent.Connected -> {
                    onBack()
                    onConnected(event.path)
                }
                ConnectionEvent.Saved -> onBack()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(QfIcons.Close, stringResource(R.string.network_close)) } },
                title = { Text(stringResource(if (viewModel.isEditing) R.string.connection_edit_title else R.string.connection_new_title)) },
                actions = { TextButton(onClick = viewModel::saveOnly, enabled = form.isValid && !checking) { Text(stringResource(R.string.connection_save)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Segmented(
                options = listOf(Protocol.SMB, Protocol.SFTP, Protocol.FTP, Protocol.WEBDAV),
                selected = form.protocol,
                label = { stringResource(protocolLabel(it)) },
                onSelect = { p -> viewModel.update { it.copy(protocol = p) } },
            )

            if (!viewModel.isEditing && (discovered.isNotEmpty() || discovering)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.connection_found), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    if (discovering) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    discovered.forEach { server ->
                        AssistChip(
                            onClick = { viewModel.useDiscovered(server) },
                            label = { Text("${server.name} · ${stringResource(protocolLabel(server.protocol))}") },
                            leadingIcon = { Icon(protocolIcon(server.protocol), null, Modifier.size(18.dp)) },
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = form.host,
                    onValueChange = { v -> viewModel.update { it.copy(host = v) } },
                    label = { Text(stringResource(R.string.connection_host)) },
                    placeholder = { Text(stringResource(R.string.connection_host_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = form.port,
                    onValueChange = { v -> if (v.length <= 5 && v.all(Char::isDigit)) viewModel.update { it.copy(port = v) } },
                    label = { Text(stringResource(R.string.connection_port)) },
                    singleLine = true,
                    isError = form.port.isNotEmpty() && form.portNumber == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(0.45f),
                )
            }

            when (form.protocol) {
                Protocol.FTP -> {
                    Segmented(
                        options = listOf(FtpSecurity.EXPLICIT, FtpSecurity.IMPLICIT, FtpSecurity.NONE),
                        selected = form.ftpSecurity,
                        label = {
                            stringResource(
                                when (it) {
                                    FtpSecurity.EXPLICIT -> R.string.connection_ftps_explicit
                                    FtpSecurity.IMPLICIT -> R.string.connection_ftps_implicit
                                    FtpSecurity.NONE -> R.string.connection_ftp_plain
                                },
                            )
                        },
                        onSelect = { s -> viewModel.update { it.copy(ftpSecurity = s) } },
                    )
                    if (form.ftpSecurity == FtpSecurity.NONE) Warning(stringResource(R.string.connection_plain_warning))
                }
                Protocol.WEBDAV -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.connection_plain_http), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = form.plainHttp, onCheckedChange = { v -> viewModel.update { it.copy(plainHttp = v) } })
                    }
                    if (form.plainHttp) Warning(stringResource(R.string.connection_plain_warning))
                }
                else -> Unit
            }

            OutlinedTextField(
                value = form.path,
                onValueChange = { v -> viewModel.update { it.copy(path = v) } },
                label = {
                    Text(
                        stringResource(
                            when (form.protocol) {
                                Protocol.SMB -> R.string.connection_share
                                Protocol.WEBDAV -> R.string.connection_dav_path
                                else -> R.string.connection_start_folder
                            },
                        ),
                    )
                },
                placeholder = {
                    Text(
                        when (form.protocol) {
                            Protocol.SMB -> "Public"
                            Protocol.WEBDAV -> "/remote.php/dav/files/ivan"
                            else -> "/home/pi"
                        },
                    )
                },
                isError = !form.pathValid,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            val authOptions = buildList {
                add(AuthMethod.PASSWORD)
                if (form.protocol == Protocol.SFTP) add(AuthMethod.KEY) else add(AuthMethod.ANONYMOUS)
            }
            Text(stringResource(R.string.connection_auth), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Segmented(
                options = authOptions,
                selected = form.auth,
                label = {
                    stringResource(
                        when (it) {
                            AuthMethod.PASSWORD -> R.string.connection_auth_password
                            AuthMethod.KEY -> R.string.connection_auth_key
                            AuthMethod.ANONYMOUS -> if (form.protocol == Protocol.SMB) R.string.connection_auth_guest else R.string.connection_auth_anonymous
                        },
                    )
                },
                onSelect = { a -> viewModel.update { it.copy(auth = a) } },
            )
            if (form.needsUser) {
                OutlinedTextField(
                    value = form.username,
                    onValueChange = { v -> viewModel.update { it.copy(username = v) } },
                    label = { Text(stringResource(R.string.connection_user)) },
                    supportingText = if (form.protocol == Protocol.SMB) ({ Text(stringResource(R.string.connection_user_domain)) }) else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val authFailed = problem == RemoteProblem.AuthFailed
            when (form.auth) {
                AuthMethod.PASSWORD -> SecretField(
                    value = form.password,
                    onChange = { v -> viewModel.update { it.copy(password = v) } },
                    label = stringResource(R.string.connection_password),
                    supporting = when {
                        authFailed -> stringResource(R.string.network_problem_auth)
                        form.keepsPassword -> stringResource(R.string.connection_keep_secret)
                        else -> null
                    },
                    isError = authFailed,
                )
                AuthMethod.KEY -> {
                    KeyCard(
                        label = form.keyLabel,
                        encrypted = form.keyEncrypted,
                        hasKey = form.privateKey != null || form.keepsKey,
                        onPick = { pickKey.launch(arrayOf("*/*")) },
                    )
                    if (form.keyEncrypted || form.keepsKey) {
                        SecretField(
                            value = form.passphrase,
                            onChange = { v -> viewModel.update { it.copy(passphrase = v) } },
                            label = stringResource(R.string.connection_passphrase),
                            supporting = if (authFailed) stringResource(R.string.connection_key_failed) else null,
                            isError = authFailed,
                        )
                    }
                }
                AuthMethod.ANONYMOUS -> Unit
            }

            OutlinedTextField(
                value = form.name,
                onValueChange = { v -> viewModel.update { it.copy(name = v) } },
                label = { Text(stringResource(R.string.connection_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            form.hostKey?.let { InfoCard(QfIcons.Shield, stringResource(R.string.connection_host_key), it, stringResource(R.string.connection_host_key_note)) }
            form.pinnedCertificate?.let { InfoCard(QfIcons.Shield, stringResource(R.string.connection_pinned), it, stringResource(R.string.connection_pinned_note)) }
            InfoCard(QfIcons.Lock, null, null, stringResource(R.string.connection_secrets_note))

            val shown = problem
            if (shown != null && !shown.isTrustDecision && shown != RemoteProblem.AuthFailed) Warning(problemText(shown))
            error?.let { Warning(it) }

            Button(
                onClick = viewModel::checkAndConnect,
                enabled = form.isValid && !checking,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                if (checking) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(stringResource(R.string.connection_check))
                }
            }
        }
    }

    problem?.takeIf { it.isTrustDecision }?.let { p ->
        TrustDialog(p, form.name.ifBlank { form.host }, onTrust = { viewModel.trust(p) }, onDismiss = viewModel::dismissProblem)
    }
}

fun protocolLabel(protocol: Protocol): Int = when (protocol) {
    Protocol.SMB -> R.string.protocol_smb
    Protocol.SFTP -> R.string.protocol_sftp
    Protocol.FTP -> R.string.protocol_ftp
    Protocol.WEBDAV -> R.string.protocol_webdav
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Segmented(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                icon = {},
            ) { Text(label(option), maxLines = 1) }
        }
    }
}

@Composable
private fun SecretField(value: String, onChange: (String) -> Unit, label: String, supporting: String?, isError: Boolean) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        supportingText = supporting?.let { { Text(it) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(QfIcons.EyeOff, stringResource(if (visible) R.string.connection_hide_password else R.string.connection_show_password))
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun KeyCard(label: String, encrypted: Boolean, hasKey: Boolean, onPick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(QfIcons.Key, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(if (hasKey) label.ifBlank { stringResource(R.string.connection_key_saved) } else stringResource(R.string.connection_key_none), style = MaterialTheme.typography.titleSmall)
                if (hasKey && label.isNotBlank()) {
                    Text(
                        stringResource(if (encrypted) R.string.connection_key_encrypted else R.string.connection_key_plain),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedButton(onClick = onPick) { Text(stringResource(if (hasKey) R.string.connection_key_replace else R.string.connection_key_pick)) }
        }
    }
}

@Composable
private fun InfoCard(icon: ImageVector, title: String?, mono: String?, text: String) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                title?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer) }
                mono?.let { Text(it, style = MonoTextStyle.copy(color = MaterialTheme.colorScheme.onSecondaryContainer)) }
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

@Composable
private fun Warning(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(QfIcons.Alert, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}
