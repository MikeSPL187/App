package io.quietfiles.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.quietfiles.R
import io.quietfiles.core.common.formatDuration
import io.quietfiles.core.designsystem.component.rememberFormatters
import io.quietfiles.core.designsystem.icon.QfIcons
import io.quietfiles.core.designsystem.theme.QfTheme
import io.quietfiles.core.model.ConflictChoice
import io.quietfiles.core.model.OperationKind
import io.quietfiles.core.model.OperationSnapshot
import io.quietfiles.core.model.OperationStatus
import io.quietfiles.core.operations.isActive

/** Плашка над навигацией, пока идут операции: название и процент. */
@Composable
fun OperationsPill(operations: List<OperationSnapshot>, onClick: () -> Unit) {
    val current = operations.firstOrNull { it.status.isActive && it.status != OperationStatus.QUEUED }
        ?: operations.firstOrNull { it.status.isActive } ?: return
    val waiting = current.status == OperationStatus.WAITING_FOR_DECISION
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        color = if (waiting) QfTheme.extended.warningContainer else MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(if (waiting) QfIcons.Alert else iconOf(current.kind), contentDescription = null, modifier = Modifier.size(20.dp))
                Text(
                    if (waiting) stringResource(R.string.ops_needs_decision) else current.title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("${(current.progress.fraction * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
            }
            LinearProgressIndicator(progress = { current.progress.fraction }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Лист «Операции» (макет 04): прогресс, пауза, отмена, конфликты прямо здесь, повтор и «Вернуть». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OperationsSheet(
    operations: List<OperationSnapshot>,
    viewModel: AppViewModel,
    onDismiss: () -> Unit,
) {
    val active = operations.count { it.status.isActive }
    val queued = operations.count { it.status == OperationStatus.QUEUED }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.ops_title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (active == 0) stringResource(R.string.ops_none_active) else stringResource(R.string.ops_summary, active - queued, queued),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (operations.any { !it.status.isActive }) {
                    TextButton(onClick = viewModel::clearFinished) { Text(stringResource(R.string.ops_clear_finished)) }
                }
            }
            if (operations.isEmpty()) {
                Text(
                    stringResource(R.string.ops_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
            LazyColumn(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(operations.sortedByDescending { it.status.isActive }, key = { it.id }) { op ->
                    OperationCard(op, viewModel)
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun OperationCard(op: OperationSnapshot, viewModel: AppViewModel) {
    val formatters = rememberFormatters()
    val context = LocalContext.current
    val failed = op.status == OperationStatus.FAILED
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = if (op.conflict != null) BorderStroke(2.dp, QfTheme.extended.warning.copy(alpha = 0.5f)) else null,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val (bg, fg) = when {
                    failed -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
                    op.status == OperationStatus.COMPLETED -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
                    else -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
                }
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(bg), contentAlignment = Alignment.Center) {
                    Icon(
                        when {
                            failed -> QfIcons.Alert
                            op.status == OperationStatus.COMPLETED -> QfIcons.Check
                            else -> iconOf(op.kind)
                        },
                        contentDescription = null,
                        tint = fg,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(op.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val sub = when (op.status) {
                        OperationStatus.QUEUED -> stringResource(R.string.ops_queued)
                        OperationStatus.PAUSED -> stringResource(R.string.ops_paused)
                        OperationStatus.CANCELLED -> stringResource(R.string.ops_cancelled)
                        OperationStatus.COMPLETED -> stringResource(R.string.ops_done)
                        OperationStatus.FAILED -> op.errorMessage ?: stringResource(R.string.ops_failed)
                        else -> op.destinationName?.let { stringResource(R.string.ops_to, it) }
                    }
                    if (sub != null) {
                        Text(
                            sub,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (op.status.isActive && op.status != OperationStatus.QUEUED) {
                    Text("${(op.progress.fraction * 100).toInt()}%", style = MaterialTheme.typography.titleMedium)
                }
            }

            if (op.status.isActive) {
                val p = op.progress
                LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth().height(6.dp))
                Row {
                    val left = buildList {
                        if (p.bytesTotal > 0) add(stringResource(R.string.ops_bytes, formatters.size.format(p.bytesDone), formatters.size.format(p.bytesTotal)))
                        else if (p.itemsTotal > 0) add(stringResource(R.string.ops_items, p.itemsDone, p.itemsTotal))
                        if (p.bytesPerSecond > 0) add(stringResource(R.string.ops_speed, formatters.size.format(p.bytesPerSecond)))
                    }.joinToString(" · ")
                    Text(left, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    p.secondsLeft?.let { Text(context.formatDuration(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                p.currentName?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                }
            }

            op.conflict?.let { conflict ->
                ConflictBlock(
                    name = conflict.source.name,
                    newMeta = formatters.size.format(conflict.source.size) + " · " + formatters.date.short(conflict.source.lastModified),
                    oldMeta = formatters.size.format(conflict.existing.size) + " · " + formatters.date.short(conflict.existing.lastModified),
                    remaining = conflict.remaining,
                    onDecide = { choice, all -> viewModel.resolve(op.id, choice, all) },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (op.status) {
                    OperationStatus.RUNNING -> {
                        FilledTonalButton(onClick = { viewModel.pause(op.id) }) {
                            Icon(QfIcons.Pause, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text(stringResource(R.string.ops_pause))
                        }
                        TextButton(onClick = { viewModel.cancel(op.id) }) { Text(stringResource(R.string.ops_cancel)) }
                    }
                    OperationStatus.PAUSED -> {
                        FilledTonalButton(onClick = { viewModel.resume(op.id) }) {
                            Icon(QfIcons.Play, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text(stringResource(R.string.ops_resume))
                        }
                        TextButton(onClick = { viewModel.cancel(op.id) }) { Text(stringResource(R.string.ops_cancel)) }
                    }
                    OperationStatus.QUEUED -> TextButton(onClick = { viewModel.cancel(op.id) }) { Text(stringResource(R.string.ops_remove_from_queue)) }
                    OperationStatus.WAITING_FOR_DECISION -> TextButton(onClick = { viewModel.cancel(op.id) }) { Text(stringResource(R.string.ops_cancel)) }
                    OperationStatus.FAILED -> {
                        FilledTonalButton(onClick = { viewModel.retry(op.id) }) { Text(stringResource(R.string.ops_retry)) }
                        TextButton(onClick = { viewModel.dismiss(op.id) }) { Text(stringResource(R.string.ops_dismiss)) }
                    }
                    OperationStatus.COMPLETED -> {
                        if (op.kind == OperationKind.TRASH && op.trashIds.isNotEmpty()) {
                            FilledTonalButton(onClick = { viewModel.undoTrash(op.trashIds); viewModel.dismiss(op.id) }) {
                                Text(stringResource(R.string.msg_undo))
                            }
                        }
                        TextButton(onClick = { viewModel.dismiss(op.id) }) { Text(stringResource(R.string.ops_dismiss)) }
                    }
                    OperationStatus.CANCELLED -> TextButton(onClick = { viewModel.dismiss(op.id) }) { Text(stringResource(R.string.ops_dismiss)) }
                }
            }
        }
    }
}

@Composable
private fun ConflictBlock(
    name: String,
    newMeta: String,
    oldMeta: String,
    remaining: Int,
    onDecide: (ConflictChoice, Boolean) -> Unit,
) {
    var choice by remember(name) { mutableStateOf(ConflictChoice.KEEP_BOTH) }
    var applyAll by remember(name) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(QfIcons.Alert, null, tint = QfTheme.extended.warning)
            Text(stringResource(R.string.conflict_title, name), style = MaterialTheme.typography.titleSmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetaBox(stringResource(R.string.conflict_new), newMeta, Modifier.weight(1f))
            MetaBox(stringResource(R.string.conflict_existing), oldMeta, Modifier.weight(1f))
        }
        val options = listOf(
            ConflictChoice.REPLACE to R.string.conflict_replace,
            ConflictChoice.SKIP to R.string.conflict_skip,
            ConflictChoice.KEEP_BOTH to R.string.conflict_keep_both,
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (value, label) ->
                SegmentedButton(
                    selected = choice == value,
                    onClick = { choice = value },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text(stringResource(label), maxLines = 1) }
            }
        }
        if (remaining > 0) {
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { applyAll = !applyAll },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = applyAll, onCheckedChange = null)
                Spacer(Modifier.size(10.dp))
                Text(stringResource(R.string.conflict_apply_all), style = MaterialTheme.typography.bodyMedium)
            }
        }
        OutlinedButton(onClick = { onDecide(choice, applyAll) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.conflict_continue))
        }
    }
}

@Composable
private fun MetaBox(label: String, value: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun iconOf(kind: OperationKind) = when (kind) {
    OperationKind.COPY -> QfIcons.Copy
    OperationKind.MOVE -> QfIcons.Move
    OperationKind.DELETE, OperationKind.TRASH -> QfIcons.Trash
    OperationKind.RESTORE -> QfIcons.Restore
    OperationKind.COMPRESS -> QfIcons.Compress
    OperationKind.EXTRACT -> QfIcons.Extract
}
