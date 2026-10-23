package io.quietfiles.feature.editor

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.quietfiles.core.designsystem.component.EmptyState
import io.quietfiles.core.designsystem.component.rememberFormatters
import io.quietfiles.core.designsystem.icon.QfIcons
import io.quietfiles.core.designsystem.theme.QfTheme
import io.quietfiles.feature.editor.text.Language
import io.quietfiles.feature.editor.text.Lexer
import io.quietfiles.feature.editor.ui.CodeEditText
import io.quietfiles.feature.editor.ui.EditorColors

/**
 * Текстовый редактор (F12, макеты 24 и 25).
 * @param onOpenExternally открыть файл другим приложением — для двоичных и слишком больших файлов.
 */
@Composable
fun EditorScreen(
    onBack: () -> Unit,
    onOpenExternally: (String) -> Unit,
    viewModel: EditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editor by remember { mutableStateOf<CodeEditText?>(null) }
    var showParams by rememberSaveable { mutableStateOf(false) }
    var confirmExit by rememberSaveable { mutableStateOf(false) }
    val colors = editorColors()

    LaunchedEffect(viewModel) {
        viewModel.commandFlow.collect { cmd ->
            when (cmd) {
                is EditorCommand.Apply -> editor?.applySilently(cmd.start, cmd.end, cmd.replacement, cmd.cursor)
                is EditorCommand.Replace -> editor?.replaceRecorded(cmd.start, cmd.end, cmd.replacement)
                is EditorCommand.Reveal -> editor?.reveal(cmd.range)
                is EditorCommand.Message -> snackbar.showSnackbar(cmd.text)
                EditorCommand.Close -> onBack()
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.saveDraftIfDirty() }
    val exit = {
        when {
            state.search.visible -> viewModel.setSearchVisible(false)
            state.dirty && state.editable -> confirmExit = true
            else -> onBack()
        }
    }
    BackHandler(onBack = exit)

    Surface(color = Color(colors.background), modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            TopBar(state, onBack = exit, onUndo = viewModel::undo, onRedo = viewModel::redo,
                onSearch = { viewModel.setSearchVisible(!state.search.visible) }, onParams = { showParams = true },
                onSave = { viewModel.save() })
            AnimatedVisibility(state.search.visible) { SearchPanel(state, viewModel) }
            state.suggestion?.let { enc ->
                Banner(
                    text = stringResource(R.string.editor_garbled, enc.label),
                    action = stringResource(R.string.editor_reopen_in, enc.label),
                    onAction = { viewModel.reopen(enc) },
                    onDismiss = viewModel::dismissSuggestion,
                )
            }
            state.draftOffer?.let { draft ->
                val formatters = rememberFormatters()
                Banner(
                    text = stringResource(R.string.editor_draft_found, formatters.date.short(draft.savedAt)),
                    action = stringResource(R.string.editor_draft_restore),
                    onAction = viewModel::restoreDraft,
                    onDismiss = viewModel::discardDraft,
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (val content = state.content) {
                    EditorContent.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    is EditorContent.Text -> TextEditor(content, state, settings, colors, viewModel) { editor = it }
                    is EditorContent.Large -> LargeTextView(content, settings.editorFontSize, colors, viewModel)
                    is EditorContent.Binary -> EmptyState(
                        QfIcons.File,
                        stringResource(R.string.editor_binary_title),
                        message = stringResource(R.string.editor_binary_message),
                        actionLabel = stringResource(R.string.editor_open_as_text),
                        onAction = viewModel::openAsText,
                    )
                    is EditorContent.TooLarge -> EmptyState(
                        QfIcons.File,
                        stringResource(R.string.editor_too_large_title),
                        message = stringResource(R.string.editor_too_large_message),
                        actionLabel = stringResource(R.string.editor_open_with),
                        onAction = { onOpenExternally(state.path) },
                    )
                    is EditorContent.Failed -> EmptyState(QfIcons.Alert, stringResource(R.string.editor_failed), message = content.message)
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            }
            if (state.content is EditorContent.Text || state.content is EditorContent.Large) {
                StatusBar(state, onClick = { showParams = true })
                if (state.editable) SymbolBar(state.language, onKey = { key -> editor?.let { symbol(it, key, state.language) } })
                else Spacer(Modifier.navigationBarsPadding())
            }
        }
    }

    if (showParams) {
        FileParamsSheet(state, settings, viewModel, onDismiss = { showParams = false })
    }
    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text(stringResource(R.string.editor_unsaved_title)) },
            text = { Text(stringResource(R.string.editor_unsaved_message, state.name)) },
            confirmButton = {
                TextButton(onClick = { confirmExit = false; viewModel.save(closeAfter = true) }) { Text(stringResource(R.string.editor_save)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmExit = false }) { Text(stringResource(R.string.editor_cancel)) }
                    TextButton(onClick = { confirmExit = false; viewModel.discardChanges(); onBack() }) {
                        Text(stringResource(R.string.editor_discard), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
        )
    }
    if (state.externalChange) {
        AlertDialog(
            onDismissRequest = viewModel::dismissExternalChange,
            title = { Text(stringResource(R.string.editor_external_title)) },
            text = { Text(stringResource(R.string.editor_external_message)) },
            confirmButton = { TextButton(onClick = { viewModel.save(overwrite = true) }) { Text(stringResource(R.string.editor_overwrite)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissExternalChange) { Text(stringResource(R.string.editor_cancel)) } },
        )
    }
}

@Composable
private fun TopBar(
    state: EditorUiState,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSearch: () -> Unit,
    onParams: () -> Unit,
    onSave: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(QfIcons.Back, stringResource(R.string.editor_back)) }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    state.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (state.dirty) {
                    val label = stringResource(R.string.editor_unsaved_dot)
                    Box(
                        Modifier.size(8.dp).clip(CircleShape).background(QfTheme.extended.warning)
                            .semantics { contentDescription = label },
                    )
                }
            }
            Text(
                state.folder,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
            )
        }
        val compact = Modifier.size(width = 40.dp, height = 48.dp)
        if (state.editable) {
            IconButton(onClick = onUndo, enabled = state.canUndo, modifier = compact) { Icon(QfIcons.Undo, stringResource(R.string.editor_undo)) }
            IconButton(onClick = onRedo, enabled = state.canRedo, modifier = compact) { Icon(QfIcons.Redo, stringResource(R.string.editor_redo)) }
        }
        if (state.content is EditorContent.Text) {
            IconButton(onClick = onSearch, modifier = compact) { Icon(QfIcons.Search, stringResource(R.string.editor_find)) }
        }
        // Параметры файла открываются из строки состояния; в шапке они только для режимов без неё.
        if (state.content !is EditorContent.Text && state.content !is EditorContent.Large) {
            IconButton(onClick = onParams, modifier = compact) { Icon(QfIcons.Settings, stringResource(R.string.editor_params)) }
        }
        if (state.editable) {
            Button(
                onClick = onSave,
                enabled = state.dirty && !state.saving,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
                modifier = Modifier.padding(start = 2.dp, end = 4.dp).height(40.dp),
            ) { Text(stringResource(R.string.editor_save)) }
        } else if (state.readOnly && state.content is EditorContent.Text) {
            Text(
                stringResource(R.string.editor_read_only),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
    }
}

@Composable
private fun SearchPanel(state: EditorUiState, viewModel: EditorViewModel) {
    val s = state.search
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp).clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Field(
                value = s.query,
                onValue = viewModel::setQuery,
                hint = stringResource(R.string.editor_find),
                icon = QfIcons.Search,
                focused = true,
                trailing = when {
                    s.error != null -> stringResource(R.string.editor_regex_error)
                    s.query.isEmpty() -> null
                    s.matches.isEmpty() -> stringResource(R.string.editor_no_matches)
                    else -> stringResource(R.string.editor_match_counter, s.current + 1, s.matches.size) + if (s.truncated) "+" else ""
                },
                modifier = Modifier.weight(1f),
                shape = shape,
            )
            IconButton(onClick = { viewModel.nextMatch(-1) }, enabled = s.matches.isNotEmpty()) {
                Icon(QfIcons.ChevronUp, stringResource(R.string.editor_prev_match))
            }
            IconButton(onClick = { viewModel.nextMatch(1) }, enabled = s.matches.isNotEmpty()) {
                Icon(QfIcons.ChevronDown, stringResource(R.string.editor_next_match))
            }
            ToggleChip("Aa", s.matchCase, stringResource(R.string.editor_match_case), viewModel::toggleMatchCase)
            ToggleChip(".*", s.regex, stringResource(R.string.editor_regex), viewModel::toggleRegex)
        }
        if (state.editable) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Field(
                    value = s.replacement,
                    onValue = viewModel::setReplacement,
                    hint = stringResource(R.string.editor_replace_with),
                    icon = QfIcons.Replace,
                    focused = false,
                    trailing = null,
                    modifier = Modifier.weight(1f),
                    shape = shape,
                )
                OutlinedButton(onClick = viewModel::replaceCurrent, enabled = s.current >= 0, modifier = Modifier.height(40.dp)) {
                    Text(stringResource(R.string.editor_replace))
                }
                FilledTonalButton(onClick = viewModel::replaceAll, enabled = s.matches.isNotEmpty(), modifier = Modifier.height(40.dp)) {
                    Text(stringResource(R.string.editor_replace_all))
                }
            }
        }
    }
}

@Composable
private fun Field(
    value: String,
    onValue: (String) -> Unit,
    hint: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    focused: Boolean,
    trailing: String?,
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.height(40.dp).clip(shape).background(cs.surfaceContainerLowest)
            .border(if (focused) 2.dp else 1.dp, if (focused) cs.primary else cs.outlineVariant, shape)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, color = cs.onSurface),
                cursorBrush = SolidColor(cs.primary),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint },
            )
        }
        trailing?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1) }
    }
}

@Composable
private fun ToggleChip(label: String, checked: Boolean, description: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    TextButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp).semantics { contentDescription = description },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        shape = RoundedCornerShape(12.dp),
        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
            containerColor = if (checked) cs.secondaryContainer else Color.Transparent,
            contentColor = if (checked) cs.onSecondaryContainer else cs.onSurfaceVariant,
        ),
    ) { Text(label, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
}

@Composable
private fun Banner(text: String, action: String, onAction: () -> Unit, onDismiss: () -> Unit) {
    val ext = QfTheme.extended
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp).clip(RoundedCornerShape(16.dp))
            .background(ext.warningContainer).padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(QfIcons.Alert, null, tint = ext.onWarningContainer, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = ext.onWarningContainer, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
        TextButton(onClick = onAction) { Text(action, color = ext.onWarningContainer) }
        IconButton(onClick = onDismiss) { Icon(QfIcons.Close, stringResource(R.string.editor_dismiss), tint = ext.onWarningContainer) }
    }
}

@Composable
private fun TextEditor(
    content: EditorContent.Text,
    state: EditorUiState,
    settings: io.quietfiles.core.model.UserSettings,
    colors: EditorColors,
    viewModel: EditorViewModel,
    onReady: (CodeEditText?) -> Unit,
) {
    val search by rememberUpdatedState(state.search)
    val lexer = remember(state.language) { Lexer.of(state.language) }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            CodeEditText(ctx, colors).apply {
                listener = object : CodeEditText.Listener {
                    override fun onEdit(start: Int, removed: String, inserted: String) = viewModel.onEdit(start, removed, inserted)
                    override fun onCursor(line: Int, column: Int, offset: Int) = viewModel.onCursor(line, column, offset)
                }
                val retained = viewModel.retained
                load(retained?.first ?: content.initial)
                retained?.let { setSelection(it.second.coerceIn(0, text.length)) }
                viewModel.textSource = { text }
                onReady(this)
            }
        },
        update = { view ->
            view.setReadOnly(state.readOnly)
            view.configure(colors, settings.editorFontSize, settings.editorWrap, settings.editorLineNumbers, lexer)
            view.setMatches(if (search.visible) search.matches else emptyList(), search.current)
            if (view.tag != content.generation) {
                if (view.tag != null) view.load(content.initial)
                view.tag = content.generation
            }
        },
        onRelease = { view ->
            viewModel.retain(view.text.toString(), view.selectionStart)
            viewModel.textSource = null
            onReady(null)
        },
    )
}

/** Вставка с панели символов (F12.6). Стрелки двигают курсор, Tab — отступ по правилам языка. */
private fun symbol(view: CodeEditText, key: String, language: Language) {
    when (key) {
        "←" -> view.setSelection((view.selectionStart - 1).coerceAtLeast(0))
        "→" -> view.setSelection((view.selectionEnd + 1).coerceAtMost(view.text.length))
        "Tab" -> view.insertAtCursor(if (language == Language.YAML || language == Language.JSON) "  " else "    ")
        else -> view.insertAtCursor(key)
    }
}

private fun symbolsFor(language: Language): List<String> = when (language) {
    Language.YAML -> listOf("Tab", "←", "→", ":", "-", "\"", "#", "{", "}", "[", "]", "|", ">")
    Language.JSON -> listOf("Tab", "←", "→", "{", "}", "[", "]", ":", ",", "\"", "true", "null")
    Language.XML -> listOf("Tab", "←", "→", "<", ">", "/", "=", "\"", "!", "-", "&")
    Language.MARKDOWN -> listOf("Tab", "←", "→", "#", "*", "-", "`", "[", "]", "(", ")", ">")
    Language.SHELL -> listOf("Tab", "←", "→", "$", "{", "}", "|", ">", "\"", "'", "&", ";")
    Language.INI -> listOf("Tab", "←", "→", "=", "[", "]", "#", ";", "\"", ".")
    else -> listOf("Tab", "←", "→", ".", ",", ":", ";", "(", ")", "\"", "-", "#")
}

@Composable
private fun SymbolBar(language: Language, onKey: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().background(cs.surfaceContainer).horizontalScroll(rememberScrollState())
            .navigationBarsPadding().padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        symbolsFor(language).forEach { key ->
            TextButton(
                onClick = { onKey(key) },
                modifier = Modifier.heightIn(min = 40.dp).widthIn(min = 40.dp),
                shape = RoundedCornerShape(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(containerColor = cs.surfaceContainerLowest, contentColor = cs.onSurface),
            ) { Text(key, fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
        }
    }
}

@Composable
private fun StatusBar(state: EditorUiState, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))
    Row(
        Modifier.fillMaxWidth().background(cs.surfaceContainer)
            .then(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        val style = MaterialTheme.typography.labelSmall.copy(color = cs.onSurfaceVariant, fontSize = 12.sp)
        if (state.content is EditorContent.Text) {
            Text(stringResource(R.string.editor_position, state.line + 1, state.column + 1), style = style)
        } else {
            Text(stringResource(R.string.editor_read_only), style = style)
        }
        val language = if (state.language == Language.PLAIN) stringResource(R.string.editor_plain_text) else state.language.label
        val params = stringResource(R.string.editor_params)
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = params, onClick = onClick).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(listOf(language, state.encoding.label, state.lineEnding.label).joinToString(" · "), style = style)
            Icon(QfIcons.ChevronUp, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(14.dp))
        }
    }
}

/** Цвета редактора: синтаксис из макета 24, остальное из темы. */
@Composable
private fun editorColors(): EditorColors {
    val cs = MaterialTheme.colorScheme
    val ext = QfTheme.extended
    val bg = cs.surfaceContainerLowest
    val dark = bg.luminance() < 0.5f
    return remember(cs, dark) {
        EditorColors(
            text = cs.onSurface.toArgb(),
            background = bg.toArgb(),
            gutter = bg.toArgb(),
            lineNumber = cs.outline.toArgb(),
            currentLineNumber = cs.primary.toArgb(),
            currentLine = lerp(bg, cs.primary, if (dark) 0.10f else 0.06f).toArgb(),
            key = cs.primary.toArgb(),
            string = (if (dark) Color(0xFFFFCF83) else Color(0xFF8A5300)).toArgb(),
            number = (if (dark) Color(0xFFD2C2FF) else Color(0xFF5B3FB8)).toArgb(),
            comment = (if (dark) Color(0xFF9AA5A3) else Color(0xFF5E6A68)).toArgb(),
            error = cs.error.toArgb(),
            warning = ext.warning.toArgb(),
            match = (if (dark) Color(0xFF6B4E00) else Color(0xFFFFE9A8)).toArgb(),
            currentMatch = (if (dark) Color(0xFF8A6400) else Color(0xFFFFD24D)).toArgb(),
            cursor = cs.primary.toArgb(),
        )
    }
}
