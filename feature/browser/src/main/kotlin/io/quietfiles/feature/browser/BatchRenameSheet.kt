package io.quietfiles.feature.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.quietfiles.core.designsystem.icon.QfIcons
import io.quietfiles.core.designsystem.theme.MonoTextStyle
import io.quietfiles.core.model.BatchRename
import io.quietfiles.core.model.FileEntry
import io.quietfiles.core.model.NameCase
import io.quietfiles.core.model.RenamePreview
import io.quietfiles.core.model.RenameProblem
import io.quietfiles.core.model.RenameRules

/**
 * Групповое переименование (F5.1): шаблон с метками, нумерация, замена и регистр.
 * Предпросмотр пересчитывается при каждом изменении; с ошибками переименовать нельзя.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchRenameSheet(
    entries: List<FileEntry>,
    folderNames: Set<String>,
    onRename: (List<RenamePreview>) -> Unit,
    onDismiss: () -> Unit,
) {
    var template by rememberSaveable { mutableStateOf("{name}") }
    var find by rememberSaveable { mutableStateOf("") }
    var replace by rememberSaveable { mutableStateOf("") }
    var regex by rememberSaveable { mutableStateOf(false) }
    var matchCase by rememberSaveable { mutableStateOf(false) }
    var case by rememberSaveable { mutableStateOf(NameCase.KEEP) }
    var start by rememberSaveable { mutableStateOf("1") }
    var step by rememberSaveable { mutableStateOf("1") }
    var digits by rememberSaveable { mutableStateOf("0") }

    val rules = RenameRules(
        template = template,
        find = find,
        replace = replace,
        regex = regex,
        matchCase = matchCase,
        case = case,
        start = start.toIntOrNull() ?: 1,
        step = step.toIntOrNull() ?: 1,
        digits = (digits.toIntOrNull() ?: 0).coerceIn(0, 9),
    )
    val previews = remember(entries, folderNames, rules) { BatchRename.preview(entries, rules, folderNames) }
    val changed = previews.count { it.changed && it.problem == null }
    val hasProblems = previews.any { it.problem != null }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding()
                .padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(pluralStringResource(R.plurals.batch_rename_title, entries.size, entries.size), style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = template,
                onValueChange = { template = it },
                label = { Text(stringResource(R.string.batch_rename_template)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("{name}" to R.string.batch_rename_tag_name, "{n}" to R.string.batch_rename_tag_number, "{date}" to R.string.batch_rename_tag_date)
                    .forEach { (tag, label) ->
                        AssistChip(onClick = { template += tag }, label = { Text(stringResource(label)) })
                    }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(start, { start = it }, stringResource(R.string.batch_rename_start), Modifier.weight(1f))
                NumberField(step, { step = it }, stringResource(R.string.batch_rename_step), Modifier.weight(1f))
                NumberField(digits, { digits = it }, stringResource(R.string.batch_rename_digits), Modifier.weight(1f))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = find,
                    onValueChange = { find = it },
                    label = { Text(stringResource(R.string.batch_rename_find)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = replace,
                    onValueChange = { replace = it },
                    label = { Text(stringResource(R.string.batch_rename_replace)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = matchCase, onClick = { matchCase = !matchCase }, label = { Text(stringResource(R.string.batch_rename_match_case)) })
                FilterChip(selected = regex, onClick = { regex = !regex }, label = { Text(stringResource(R.string.batch_rename_regex)) })
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val cases = listOf(
                    NameCase.KEEP to R.string.batch_rename_case_keep,
                    NameCase.LOWER to R.string.batch_rename_case_lower,
                    NameCase.UPPER to R.string.batch_rename_case_upper,
                    NameCase.TITLE to R.string.batch_rename_case_title,
                )
                cases.forEachIndexed { i, (value, label) ->
                    SegmentedButton(
                        selected = case == value,
                        onClick = { case = value },
                        shape = SegmentedButtonDefaults.itemShape(i, cases.size),
                        icon = {},
                    ) { Text(stringResource(label), maxLines = 1) }
                }
            }

            Text(stringResource(R.string.batch_rename_preview), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Column(Modifier.clip(RoundedCornerShape(16.dp)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                previews.take(PREVIEW_ROWS).forEach { PreviewRow(it) }
            }
            if (previews.size > PREVIEW_ROWS) {
                Text(
                    pluralStringResource(R.plurals.batch_rename_more, previews.size - PREVIEW_ROWS, previews.size - PREVIEW_ROWS),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = { onRename(previews) },
                enabled = changed > 0 && !hasProblems,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(
                    if (hasProblems) {
                        stringResource(R.string.batch_rename_fix_problems)
                    } else {
                        pluralStringResource(R.plurals.batch_rename_action, changed, changed)
                    },
                )
            }
        }
    }
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> if (v.length <= 6 && v.all { it.isDigit() || it == '-' }) onChange(v) },
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun PreviewRow(preview: RenamePreview) {
    val problem = preview.problem
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(preview.oldName, style = MonoTextStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant), maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(QfIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
                Text(
                    preview.newName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
            if (problem != null) {
                Text(
                    stringResource(
                        when (problem) {
                            RenameProblem.EMPTY -> R.string.batch_rename_problem_empty
                            RenameProblem.INVALID -> R.string.batch_rename_problem_invalid
                            RenameProblem.DUPLICATE -> R.string.batch_rename_problem_duplicate
                            RenameProblem.EXISTS -> R.string.batch_rename_problem_exists
                            RenameProblem.BAD_PATTERN -> R.string.batch_rename_problem_pattern
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private const val PREVIEW_ROWS = 8
