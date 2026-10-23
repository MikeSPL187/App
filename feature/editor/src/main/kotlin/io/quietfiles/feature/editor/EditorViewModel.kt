package io.quietfiles.feature.editor

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.quietfiles.core.common.IoDispatcher
import io.quietfiles.core.common.displayPath
import io.quietfiles.core.data.repository.SettingsRepository
import io.quietfiles.core.model.FileNames
import io.quietfiles.core.model.UserSettings
import io.quietfiles.core.storage.StorageVolumes
import io.quietfiles.core.operations.ErrorMessages
import io.quietfiles.feature.editor.data.Drafts
import io.quietfiles.feature.editor.data.LargeTextReader
import io.quietfiles.feature.editor.data.ModifiedExternallyException
import io.quietfiles.feature.editor.data.OpenedText
import io.quietfiles.feature.editor.data.TextFiles
import io.quietfiles.feature.editor.data.UnencodableTextException
import io.quietfiles.feature.editor.text.EditHistory
import io.quietfiles.feature.editor.text.Encodings
import io.quietfiles.feature.editor.text.Language
import io.quietfiles.feature.editor.text.LineEnding
import io.quietfiles.feature.editor.text.SearchQuery
import io.quietfiles.feature.editor.text.TextEncoding
import io.quietfiles.feature.editor.text.TextSearch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import javax.inject.Inject

/** Редактор файла. [readOnly] — например, файл из архива. */
@Serializable
data class EditorRoute(val path: String, val readOnly: Boolean = false)

sealed interface EditorContent {
    data object Loading : EditorContent
    data class Text(val initial: String, val generation: Int) : EditorContent
    data class Large(val reader: LargeTextReader, val indexed: Float) : EditorContent
    data class Binary(val size: Long) : EditorContent
    data class TooLarge(val size: Long) : EditorContent
    data class Failed(val message: String) : EditorContent
}

data class SearchState(
    val visible: Boolean = false,
    val query: String = "",
    val replacement: String = "",
    val matchCase: Boolean = false,
    val regex: Boolean = false,
    val matches: List<IntRange> = emptyList(),
    val current: Int = -1,
    val error: String? = null,
    val truncated: Boolean = false,
)

data class EditorUiState(
    val path: String,
    val content: EditorContent = EditorContent.Loading,
    val encoding: TextEncoding = TextEncoding.UTF_8,
    val withBom: Boolean = false,
    val lineEnding: LineEnding = LineEnding.LF,
    val language: Language = Language.PLAIN,
    val readOnly: Boolean = false,
    val dirty: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val line: Int = 0,
    val column: Int = 0,
    val suggestion: TextEncoding? = null,
    val search: SearchState = SearchState(),
    val saving: Boolean = false,
    val draftOffer: Drafts.Draft? = null,
    val externalChange: Boolean = false,
    /** Папка файла для подзаголовка: «Память/Documents/Сервер». */
    val folder: String = "",
) {
    val name: String get() = FileNames.nameOf(path)
    val editable: Boolean get() = content is EditorContent.Text && !readOnly
}

/** Команды полю редактора: применить правку без записи в историю или показать участок. */
sealed interface EditorCommand {
    data class Apply(val start: Int, val end: Int, val replacement: String, val cursor: Int) : EditorCommand
    data class Replace(val start: Int, val end: Int, val replacement: String) : EditorCommand
    data class Reveal(val range: IntRange) : EditorCommand
    data class Message(val text: String) : EditorCommand
    data object Close : EditorCommand
}

@HiltViewModel
class EditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val files: TextFiles,
    private val drafts: Drafts,
    settingsRepository: SettingsRepository,
    volumes: StorageVolumes,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<EditorRoute>()
    private val history = EditHistory()
    private var forcedDirty = false
    private var lastModified: Long? = null
    private var generation = 0
    private var cursorOffset = 0

    /** Текущее содержимое поля; поле подключает себя, пока оно на экране. */
    var textSource: (() -> CharSequence)? = null

    /** Текст, сохранённый при пересоздании поля (поворот экрана), и позиция курсора. */
    var retained: Pair<String, Int>? = null
        private set

    private val _state = MutableStateFlow(
        EditorUiState(path = route.path, language = Language.of(FileNames.nameOf(route.path)), readOnly = route.readOnly),
    )
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    val settings: StateFlow<UserSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())
    private val settingsRepo = settingsRepository

    private val commands = Channel<EditorCommand>(Channel.BUFFERED)
    val commandFlow: Flow<EditorCommand> = commands.receiveAsFlow()

    private var searchJob: Job? = null
    private val pageCache = HashMap<Int, List<String>>()
    val pages = MutableStateFlow(0)

    init {
        _state.update { it.copy(folder = folderLabel(route.path, volumes)) }
        open()
    }

    private fun folderLabel(path: String, volumes: StorageVolumes): String {
        val parent = FileNames.parentOf(path).orEmpty()
        return context.displayPath(parent, volumes.volumeFor(path))
    }

    // ---- Открытие ----

    private fun open(encoding: TextEncoding? = null, forceText: Boolean = false) {
        _state.update { it.copy(content = EditorContent.Loading) }
        viewModelScope.launch {
            val opened = try {
                files.open(route.path, encoding, forceText)
            } catch (e: Exception) {
                _state.update { it.copy(content = EditorContent.Failed(ErrorMessages.of(context, e))) }
                return@launch
            }
            lastModified = opened.lastModified
            when (opened) {
                is OpenedText.Editable -> {
                    history.clear()
                    forcedDirty = false
                    retained = null
                    val draft = drafts.load(route.path)?.takeIf { it.baseLastModified == opened.lastModified && it.text != opened.text }
                    _state.update {
                        it.copy(
                            content = EditorContent.Text(opened.text, ++generation),
                            encoding = opened.encoding,
                            withBom = opened.detection.hasBom && opened.detection.encoding == opened.encoding,
                            lineEnding = opened.lineEnding,
                            readOnly = route.readOnly || opened.readOnly,
                            suggestion = opened.suggestion,
                            draftOffer = draft,
                        ).withHistory()
                    }
                }
                is OpenedText.Large -> openLarge(opened)
                is OpenedText.Binary -> _state.update { it.copy(content = EditorContent.Binary(opened.size)) }
                is OpenedText.TooLarge -> _state.update { it.copy(content = EditorContent.TooLarge(opened.size)) }
            }
        }
    }

    private suspend fun openLarge(opened: OpenedText.Large) {
        val reader = LargeTextReader(opened.path, opened.encoding)
        pageCache.clear()
        _state.update { it.copy(content = EditorContent.Large(reader, 0f), encoding = opened.encoding, readOnly = true) }
        withContext(io) {
            reader.index { progress -> _state.update { s -> s.copy(content = EditorContent.Large(reader, progress)) } }
        }
        _state.update { it.copy(content = EditorContent.Large(reader, 1f)) }
    }

    fun openAsText() = open(forceText = true)

    fun reopen(encoding: TextEncoding) {
        _state.update { it.copy(suggestion = null) }
        open(encoding)
    }

    /** «Сохранять в X»: текст остаётся прежним, при сохранении меняется кодировка. */
    fun convertTo(encoding: TextEncoding) {
        _state.update { it.copy(encoding = encoding, withBom = false, suggestion = null) }
        forcedDirty = true
        refreshHistoryFlags()
    }

    fun setLineEnding(ending: LineEnding) {
        if (ending == _state.value.lineEnding) return
        _state.update { it.copy(lineEnding = ending) }
        forcedDirty = true
        refreshHistoryFlags()
    }

    fun dismissSuggestion() = _state.update { it.copy(suggestion = null) }

    fun restoreDraft() {
        val draft = _state.value.draftOffer ?: return
        _state.update {
            it.copy(
                content = EditorContent.Text(draft.text, ++generation),
                encoding = draft.encoding,
                withBom = draft.withBom,
                lineEnding = draft.lineEnding,
                draftOffer = null,
            )
        }
        history.clear()
        forcedDirty = true
        retained = null
        refreshHistoryFlags()
    }

    fun discardDraft() {
        _state.update { it.copy(draftOffer = null) }
        viewModelScope.launch { drafts.delete(route.path) }
    }

    // ---- Правка ----

    fun onEdit(start: Int, removed: String, inserted: String) {
        history.record(start, removed, inserted, System.currentTimeMillis())
        refreshHistoryFlags()
        if (_state.value.search.visible) scheduleSearch()
    }

    fun onCursor(line: Int, column: Int, offset: Int) {
        cursorOffset = offset
        _state.update { it.copy(line = line, column = column) }
    }

    fun undo() {
        val e = history.undo() ?: return
        commands.trySend(EditorCommand.Apply(e.start, e.start + e.inserted.length, e.removed, e.start + e.removed.length))
        refreshHistoryFlags()
    }

    fun redo() {
        val e = history.redo() ?: return
        commands.trySend(EditorCommand.Apply(e.start, e.start + e.removed.length, e.inserted, e.start + e.inserted.length))
        refreshHistoryFlags()
    }

    fun retain(text: String, cursor: Int) {
        retained = text to cursor
    }

    private fun refreshHistoryFlags() = _state.update { it.withHistory() }

    private fun EditorUiState.withHistory() =
        copy(dirty = forcedDirty || !history.isClean, canUndo = history.canUndo, canRedo = history.canRedo)

    // ---- Сохранение ----

    fun save(overwrite: Boolean = false, closeAfter: Boolean = false) {
        val s = _state.value
        val text = textSource?.invoke()?.toString() ?: return
        if (!s.editable || s.saving) return
        _state.update { it.copy(saving = true, externalChange = false) }
        viewModelScope.launch {
            try {
                lastModified = files.save(s.path, text, s.encoding, s.withBom, s.lineEnding, if (overwrite) null else lastModified)
                history.markSaved()
                forcedDirty = false
                drafts.delete(s.path)
                _state.update { it.copy(saving = false).withHistory() }
                commands.send(EditorCommand.Message(context.getString(R.string.editor_saved)))
                if (closeAfter) commands.send(EditorCommand.Close)
            } catch (e: ModifiedExternallyException) {
                _state.update { it.copy(saving = false, externalChange = true) }
            } catch (e: UnencodableTextException) {
                _state.update { it.copy(saving = false) }
                commands.send(EditorCommand.Message(context.getString(R.string.editor_unencodable, e.encoding.label)))
            } catch (e: Exception) {
                _state.update { it.copy(saving = false) }
                commands.send(EditorCommand.Message(ErrorMessages.of(context, e)))
            }
        }
    }

    fun dismissExternalChange() = _state.update { it.copy(externalChange = false) }

    /** Черновик при уходе в фон (F12.7): переживает закрытие приложения системой. */
    fun saveDraftIfDirty() {
        val s = _state.value
        val base = lastModified ?: return
        if (!s.dirty || !s.editable) return
        val text = textSource?.invoke()?.toString() ?: return
        viewModelScope.launch {
            runCatching {
                drafts.save(Drafts.Draft(s.path, text, s.encoding, s.withBom, s.lineEnding, base, System.currentTimeMillis()))
            }
        }
    }

    /** Закрыть без сохранения: черновик больше не нужен. */
    fun discardChanges() {
        viewModelScope.launch { drafts.delete(route.path) }
    }

    // ---- Поиск ----

    fun setSearchVisible(visible: Boolean) {
        _state.update { it.copy(search = if (visible) it.search.copy(visible = true) else SearchState(query = it.search.query)) }
        if (visible) scheduleSearch(immediate = true) else _state.update { it.copy(search = it.search.copy(matches = emptyList(), current = -1)) }
    }

    fun setQuery(query: String) = updateSearch { it.copy(query = query) }
    fun setReplacement(value: String) = _state.update { it.copy(search = it.search.copy(replacement = value)) }
    fun toggleMatchCase() = updateSearch { it.copy(matchCase = !it.matchCase) }
    fun toggleRegex() = updateSearch { it.copy(regex = !it.regex) }

    private fun updateSearch(block: (SearchState) -> SearchState) {
        _state.update { it.copy(search = block(it.search)) }
        scheduleSearch()
    }

    private fun scheduleSearch(immediate: Boolean = false) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (!immediate) delay(200)
            val text = textSource?.invoke()?.toString() ?: return@launch
            val s = _state.value.search
            val query = SearchQuery(s.query, s.matchCase, s.regex)
            val result = withContext(io) { TextSearch.find(text, query) }
            val cursor = cursorOffset
            val current = result.matches.indexOfFirst { it.first >= cursor }.let { if (it < 0 && result.matches.isNotEmpty()) 0 else it }
            _state.update { st ->
                st.copy(search = st.search.copy(matches = result.matches, current = current, error = result.error, truncated = result.truncated))
            }
        }
    }

    fun nextMatch(step: Int) {
        val s = _state.value.search
        if (s.matches.isEmpty()) return
        val next = ((s.current + step) % s.matches.size + s.matches.size) % s.matches.size
        _state.update { it.copy(search = it.search.copy(current = next)) }
        commands.trySend(EditorCommand.Reveal(s.matches[next]))
    }

    fun replaceCurrent() {
        val st = _state.value
        val s = st.search
        if (!st.editable || s.current !in s.matches.indices) return
        val text = textSource?.invoke() ?: return
        val match = s.matches[s.current]
        val replacement = runCatching {
            TextSearch.replacementFor(text, match, SearchQuery(s.query, s.matchCase, s.regex), s.replacement)
        }.getOrDefault(s.replacement)
        commands.trySend(EditorCommand.Replace(match.first, match.last + 1, replacement))
    }

    fun replaceAll() {
        val st = _state.value
        val s = st.search
        if (!st.editable || s.matches.isEmpty()) return
        val text = textSource?.invoke()?.toString() ?: return
        // Одна правка на участок от первого до последнего совпадения — отменяется одним шагом.
        val from = s.matches.first().first
        val to = s.matches.last().last + 1
        val region = text.substring(from, to)
        val (replaced, count) = runCatching {
            TextSearch.replaceAll(region, SearchQuery(s.query, s.matchCase, s.regex), s.replacement)
        }.getOrDefault(region to 0)
        if (count == 0) return
        commands.trySend(EditorCommand.Replace(from, to, replaced))
        commands.trySend(EditorCommand.Message(context.resources.getQuantityString(R.plurals.editor_replaced, count, count)))
    }

    // ---- Большие файлы ----

    fun page(index: Int): List<String>? = pageCache[index]

    fun loadPage(index: Int) {
        val content = _state.value.content as? EditorContent.Large ?: return
        if (pageCache.containsKey(index) || content.indexed < 1f) return
        viewModelScope.launch {
            val lines = withContext(io) { runCatching { content.reader.lines(index * PAGE, PAGE) }.getOrDefault(emptyList()) }
            // Держим в памяти только страницы рядом с видимой.
            if (pageCache.size >= MAX_PAGES) pageCache.remove(pageCache.keys.maxBy { kotlin.math.abs(it - index) })
            pageCache[index] = lines
            pages.value++
        }
    }

    // ---- Настройки вида ----

    fun setFontSize(sp: Int) = viewModelScope.launch { settingsRepo.setEditorFontSize(sp) }
    fun setWrap(enabled: Boolean) = viewModelScope.launch { settingsRepo.setEditorWrap(enabled) }
    fun setLineNumbers(enabled: Boolean) = viewModelScope.launch { settingsRepo.setEditorLineNumbers(enabled) }

    /** Превью строк в другой кодировке для листа «Параметры файла». */
    suspend fun preview(encoding: TextEncoding): String = withContext(io) {
        runCatching {
            val file = File(route.path)
            val bytes = ByteArray(minOf(file.length(), 4096L).toInt())
            file.inputStream().use { it.read(bytes) }
            LineEnding.normalize(Encodings.decode(bytes, bytes.size, encoding)).lineSequence()
                .filter { it.isNotBlank() }.take(2).joinToString(" · ").take(120)
        }.getOrDefault("")
    }

    companion object {
        const val PAGE = 200
        private const val MAX_PAGES = 40
    }
}
