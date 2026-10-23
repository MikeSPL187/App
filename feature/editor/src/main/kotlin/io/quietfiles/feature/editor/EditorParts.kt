package io.quietfiles.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.quietfiles.core.designsystem.icon.QfIcons
import io.quietfiles.core.model.UserSettings
import io.quietfiles.feature.editor.text.LineEnding
import io.quietfiles.feature.editor.text.TextEncoding
import io.quietfiles.feature.editor.ui.EditorColors

/** Большой файл только для чтения: строки подгружаются страницами по мере прокрутки (F12.1). */
@Composable
internal fun LargeTextView(content: EditorContent.Large, fontSize: Int, colors: EditorColors, viewModel: EditorViewModel) {
    if (content.indexed < 1f) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.editor_indexing), style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { content.indexed }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        }
        return
    }
    val reader = content.reader
    val version by viewModel.pages.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val numberWidth = (reader.lineCount.toString().length * fontSize * 0.62f + 16).dp
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(count = reader.lineCount, key = { it }) { index ->
            val page = index / EditorViewModel.PAGE
            val lines = remember(version, page) { viewModel.page(page) }
            LaunchedEffect(page, lines == null) { if (lines == null) viewModel.loadPage(page) }
            Row(Modifier.fillMaxWidth().padding(end = 12.dp)) {
                Text(
                    (index + 1).toString(),
                    color = Color(colors.lineNumber),
                    fontFamily = FontFamily.Monospace,
                    fontSize = (fontSize * 0.85f).sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(numberWidth).padding(end = 10.dp),
                )
                Text(
                    lines?.getOrNull(index % EditorViewModel.PAGE) ?: "",
                    color = Color(colors.text),
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSize.sp,
                    lineHeight = (fontSize * 1.5f).sp,
                    softWrap = false,
                )
            }
        }
    }
}

/** Параметры файла (макет 25): кодировка с предпросмотром, окончания строк, вид. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FileParamsSheet(state: EditorUiState, settings: UserSettings, viewModel: EditorViewModel, onDismiss: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf(state.encoding) }
    val preview by produceState("", selected) { value = viewModel.preview(selected) }
    val textMode = state.content is EditorContent.Text
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(R.string.editor_params), style = MaterialTheme.typography.titleLarge)
            Section(stringResource(R.string.editor_encoding))
            Column(Modifier.clip(RoundedCornerShape(16.dp)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TextEncoding.entries.forEach { enc ->
                    Row(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLowest)
                            .selectable(selected = enc == selected, role = Role.RadioButton) { selected = enc }
                            .heightIn(min = 48.dp).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = enc == selected, onClick = null)
                        Text(enc.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 12.dp))
                        when {
                            enc == state.suggestion -> Tag(stringResource(R.string.editor_fits))
                            enc == state.encoding -> Tag(stringResource(R.string.editor_current))
                        }
                    }
                }
            }
            if (preview.isNotEmpty()) {
                Text(
                    stringResource(R.string.editor_preview, preview),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (selected != state.encoding) {
                Button(onClick = { viewModel.reopen(selected); onDismiss() }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text(stringResource(R.string.editor_reopen_in, selected.label))
                }
                if (state.editable) {
                    OutlinedButton(onClick = { viewModel.convertTo(selected); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.editor_save_in, selected.label))
                    }
                }
            }
            if (textMode) {
                Section(stringResource(R.string.editor_line_endings))
                Column(Modifier.clip(RoundedCornerShape(16.dp)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf(LineEnding.LF to R.string.editor_lf, LineEnding.CRLF to R.string.editor_crlf).forEach { (ending, label) ->
                        Row(
                            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLowest)
                                .selectable(selected = state.lineEnding == ending, enabled = state.editable, role = Role.RadioButton) {
                                    viewModel.setLineEnding(ending)
                                }
                                .heightIn(min = 48.dp).padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = state.lineEnding == ending, onClick = null, enabled = state.editable)
                            Text(stringResource(label), modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }
            Section(stringResource(R.string.editor_view))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.editor_font_size), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                FilledTonalIconButton(
                    onClick = { viewModel.setFontSize(settings.editorFontSize - 1) },
                    enabled = settings.editorFontSize > UserSettings.EDITOR_FONT_SIZES.first,
                ) { Text("−", fontSize = 18.sp) }
                Text(stringResource(R.string.editor_sp, settings.editorFontSize), modifier = Modifier.padding(horizontal = 12.dp))
                FilledTonalIconButton(
                    onClick = { viewModel.setFontSize(settings.editorFontSize + 1) },
                    enabled = settings.editorFontSize < UserSettings.EDITOR_FONT_SIZES.last,
                ) { Icon(QfIcons.Plus, stringResource(R.string.editor_font_bigger)) }
            }
            if (textMode) {
                SwitchLine(stringResource(R.string.editor_wrap), settings.editorWrap) { viewModel.setWrap(it) }
            }
            SwitchLine(stringResource(R.string.editor_line_numbers), settings.editorLineNumbers) { viewModel.setLineNumbers(it) }
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
}

@Composable
private fun Tag(text: String) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
private fun SwitchLine(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Switch) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}
