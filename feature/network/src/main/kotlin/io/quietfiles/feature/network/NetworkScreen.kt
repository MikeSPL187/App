package io.quietfiles.feature.network

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.quietfiles.core.designsystem.icon.QfIcons
import io.quietfiles.core.designsystem.theme.MonoTextStyle
import io.quietfiles.core.model.network.ConnectionStatus
import io.quietfiles.core.model.network.Protocol
import io.quietfiles.core.model.network.RemoteProblem

/** Раздел «Сеть» (макет 08): серверы локальной сети и их состояние. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    onOpenFolder: (String) -> Unit,
    viewModel: NetworkViewModel = hiltViewModel(),
    pcAccess: @Composable () -> Unit = {},
) {
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val opening by viewModel.opening.collectAsStateWithLifecycle()
    val problem by viewModel.problem.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<ServerItem?>(null) }

    LaunchedEffect(viewModel) { viewModel.openFolder.collect(onOpenFolder) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.network_title), style = MaterialTheme.typography.headlineMedium) },
                actions = { IconButton(onClick = onAdd) { Icon(QfIcons.Plus, stringResource(R.string.network_add)) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            pcAccess()
            Text(
                stringResource(R.string.network_local),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
            )
            val list = servers
            when {
                list == null -> Unit
                list.isEmpty() -> EmptyServers(onAdd)
                else -> Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(24.dp)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    list.forEach { item ->
                        ServerRow(
                            item = item,
                            opening = opening == item.connection.id,
                            onOpen = { viewModel.open(item.connection) },
                            onEdit = { onEdit(item.connection.id) },
                            onDisconnect = { viewModel.disconnect(item.connection) },
                            onDelete = { deleting = item },
                        )
                    }
                }
            }
        }
    }

    problem?.let { pending ->
        if (pending.problem.isTrustDecision) {
            TrustDialog(pending.problem, pending.connection.displayName, onTrust = { viewModel.trust(pending) }, onDismiss = viewModel::dismissProblem)
        } else {
            val fixable = pending.problem == RemoteProblem.AuthFailed || pending.problem == RemoteProblem.NeedsLogin
            AlertDialog(
                onDismissRequest = viewModel::dismissProblem,
                icon = { Icon(QfIcons.Alert, null) },
                title = { Text(pending.connection.displayName) },
                text = { Text(problemText(pending.problem)) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.dismissProblem()
                        if (fixable) onEdit(pending.connection.id) else viewModel.open(pending.connection)
                    }) { Text(stringResource(if (fixable) R.string.network_edit else R.string.network_retry)) }
                },
                dismissButton = { TextButton(onClick = viewModel::dismissProblem) { Text(stringResource(R.string.network_close)) } },
            )
        }
    }

    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.network_delete_title, item.connection.displayName)) },
            text = { Text(stringResource(R.string.network_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(item.connection)
                    deleting = null
                }) { Text(stringResource(R.string.network_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.network_cancel)) } },
        )
    }
}

@Composable
private fun EmptyServers(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(QfIcons.Server, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        Text(
            stringResource(R.string.network_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onAdd) { Text(stringResource(R.string.network_add)) }
    }
}

fun protocolIcon(protocol: Protocol): ImageVector = when (protocol) {
    Protocol.SMB -> QfIcons.Server
    Protocol.SFTP -> QfIcons.Terminal
    Protocol.FTP -> QfIcons.Server
    Protocol.WEBDAV -> QfIcons.Globe
}

@Composable
private fun ServerRow(
    item: ServerItem,
    opening: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDisconnect: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val connection = item.connection
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .combinedClickable(role = Role.Button, onLongClick = { menu = true }, onClick = onOpen)
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (opening) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Icon(protocolIcon(connection.protocol), null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(connection.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                connection.address,
                style = MonoTextStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            StatusLine(item)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(QfIcons.More, stringResource(R.string.network_menu)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.network_edit)) }, onClick = {
                    menu = false
                    onEdit()
                })
                if (item.status == ConnectionStatus.ONLINE) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.network_disconnect)) }, onClick = {
                        menu = false
                        onDisconnect()
                    })
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.network_delete)) }, onClick = {
                    menu = false
                    onDelete()
                })
            }
        }
    }
}

@Composable
private fun StatusLine(item: ServerItem) {
    val connection = item.connection
    val error = item.status in setOf(
        ConnectionStatus.UNREACHABLE, ConnectionStatus.AUTH_FAILED, ConnectionStatus.NEEDS_LOGIN,
        ConnectionStatus.HOST_KEY_CHANGED, ConnectionStatus.CERTIFICATE_CHANGED,
    )
    val (icon, text) = when {
        item.status != ConnectionStatus.UNKNOWN -> (if (error) QfIcons.Alert else QfIcons.Dot) to statusText(item.status)
        connection.hostKey != null -> QfIcons.Shield to stringResource(R.string.network_status_key_verified)
        connection.pinnedCertificate != null -> QfIcons.Shield to stringResource(R.string.network_status_certificate_pinned)
        else -> null to statusText(item.status)
    }
    val color: Color = when {
        error -> MaterialTheme.colorScheme.error
        item.status == ConnectionStatus.ONLINE || icon == QfIcons.Shield -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (icon != null) Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}
