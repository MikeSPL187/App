package io.quietfiles.feature.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.quietfiles.core.designsystem.component.rememberFormatters
import io.quietfiles.core.designsystem.icon.QfIcons
import io.quietfiles.core.designsystem.theme.MonoTextStyle
import io.quietfiles.core.model.network.CertificateInfo
import io.quietfiles.core.model.network.ConnectionStatus
import io.quietfiles.core.model.network.RemoteProblem

/** Можно ли решить проблему доверием (а не правкой логина или адреса). */
val RemoteProblem.isTrustDecision: Boolean
    get() = this is RemoteProblem.UnknownHostKey || this is RemoteProblem.HostKeyChanged ||
        this is RemoteProblem.UntrustedCertificate || this is RemoteProblem.CertificateChanged

@Composable
fun problemText(problem: RemoteProblem): String = when (problem) {
    RemoteProblem.Unreachable -> stringResource(R.string.network_problem_unreachable)
    RemoteProblem.AuthFailed -> stringResource(R.string.network_problem_auth)
    RemoteProblem.NeedsLogin -> stringResource(R.string.network_problem_login)
    RemoteProblem.Smb1Required -> stringResource(R.string.network_problem_smb1)
    is RemoteProblem.UnknownHostKey -> stringResource(R.string.network_problem_unknown_key)
    is RemoteProblem.HostKeyChanged -> stringResource(R.string.network_problem_key_changed)
    is RemoteProblem.UntrustedCertificate -> stringResource(R.string.network_problem_certificate)
    is RemoteProblem.CertificateChanged -> stringResource(R.string.network_problem_certificate_changed)
    is RemoteProblem.Protocol -> stringResource(R.string.network_problem_protocol, problem.message)
}

@Composable
fun statusText(status: ConnectionStatus): String = stringResource(
    when (status) {
        ConnectionStatus.UNKNOWN -> R.string.network_status_unknown
        ConnectionStatus.CONNECTING -> R.string.network_status_connecting
        ConnectionStatus.ONLINE -> R.string.network_status_online
        ConnectionStatus.UNREACHABLE -> R.string.network_status_unreachable
        ConnectionStatus.AUTH_FAILED -> R.string.network_status_auth
        ConnectionStatus.NEEDS_LOGIN -> R.string.network_status_login
        ConnectionStatus.HOST_KEY_CHANGED -> R.string.network_status_key_changed
        ConnectionStatus.CERTIFICATE_CHANGED -> R.string.network_status_certificate_changed
    },
)

/**
 * Решение о доверии (F16.4, F16.5): отпечаток ключа SSH или сертификат сервера. Смена ключа или сертификата —
 * отдельное предупреждение и второе подтверждение: молча приложение не подключится никогда.
 */
@Composable
fun TrustDialog(problem: RemoteProblem, serverName: String, onTrust: () -> Unit, onDismiss: () -> Unit) {
    val changed = problem is RemoteProblem.HostKeyChanged || problem is RemoteProblem.CertificateChanged
    var confirmChange by remember { mutableStateOf(false) }
    if (confirmChange) {
        AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(QfIcons.Alert, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.network_trust_changed_confirm_title)) },
            text = { Text(stringResource(R.string.network_trust_changed_confirm_text, serverName)) },
            confirmButton = { TextButton(onClick = onTrust) { Text(stringResource(R.string.network_trust_changed_confirm), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.network_cancel)) } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (changed) QfIcons.Alert else QfIcons.Shield, null, tint = if (changed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) },
        title = {
            Text(
                stringResource(
                    when (problem) {
                        is RemoteProblem.UnknownHostKey -> R.string.network_trust_key_title
                        is RemoteProblem.HostKeyChanged -> R.string.network_trust_key_changed_title
                        is RemoteProblem.CertificateChanged -> R.string.network_trust_certificate_changed_title
                        else -> R.string.network_trust_certificate_title
                    },
                ),
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (problem) {
                    is RemoteProblem.UnknownHostKey -> {
                        Text(stringResource(R.string.network_trust_key_text, serverName))
                        Fingerprint(problem.keyType, problem.fingerprint)
                    }
                    is RemoteProblem.HostKeyChanged -> {
                        Text(stringResource(R.string.network_trust_key_changed_text, serverName), color = MaterialTheme.colorScheme.error)
                        Text(stringResource(R.string.network_trust_old), style = MaterialTheme.typography.labelLarge)
                        Text(problem.expected, style = MonoTextStyle)
                        Text(stringResource(R.string.network_trust_new), style = MaterialTheme.typography.labelLarge)
                        Fingerprint(problem.keyType, problem.actual)
                    }
                    is RemoteProblem.UntrustedCertificate -> {
                        Text(stringResource(R.string.network_trust_certificate_text, serverName))
                        Certificate(problem.certificate)
                    }
                    is RemoteProblem.CertificateChanged -> {
                        Text(stringResource(R.string.network_trust_certificate_changed_text, serverName), color = MaterialTheme.colorScheme.error)
                        Certificate(problem.certificate)
                    }
                    else -> Unit
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (changed) confirmChange = true else onTrust() }) {
                Text(
                    stringResource(if (changed) R.string.network_trust_replace else R.string.network_trust_accept),
                    color = if (changed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.network_cancel)) } },
    )
}

@Composable
private fun Fingerprint(type: String, fingerprint: String) {
    Text(stringResource(R.string.network_trust_fingerprint, type), style = MaterialTheme.typography.labelLarge)
    Text(fingerprint, style = MonoTextStyle)
}

@Composable
private fun Certificate(info: CertificateInfo) {
    val formatters = rememberFormatters()
    Text(stringResource(R.string.network_cert_subject), style = MaterialTheme.typography.labelLarge)
    Text(info.subject, style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.network_cert_issuer), style = MaterialTheme.typography.labelLarge)
    Text(info.issuer, style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.network_cert_valid), style = MaterialTheme.typography.labelLarge)
    Text(formatters.date.long(info.validFrom) + " — " + formatters.date.long(info.validUntil), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.network_cert_sha256), style = MaterialTheme.typography.labelLarge)
    Text(info.sha256, style = MonoTextStyle)
}
