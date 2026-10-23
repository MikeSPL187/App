package io.quietfiles.feature.editor.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import io.quietfiles.feature.editor.text.Lexer
import io.quietfiles.feature.editor.text.LineStarts
import io.quietfiles.feature.editor.text.Token
import io.quietfiles.feature.editor.text.TokenType

/** Цвета редактора из темы (макет 24). */
data class EditorColors(
    val text: Int,
    val background: Int,
    val gutter: Int,
    val lineNumber: Int,
    val currentLineNumber: Int,
    val currentLine: Int,
    val key: Int,
    val string: Int,
    val number: Int,
    val comment: Int,
    val error: Int,
    val warning: Int,
    val match: Int,
    val currentMatch: Int,
    val cursor: Int,
)

/**
 * Поле редактора на основе EditText: он без проблем держит многомегабайтный текст и даёт
 * точные диапазоны правок. Подсветка и отметки поиска ставятся только на видимые строки.
 */
@SuppressLint("ViewConstructor", "AppCompatCustomView")
class CodeEditText(context: Context, private var colors: EditorColors) : EditText(context) {

    interface Listener {
        /** Пользователь (или панель символов) изменил текст: участок [start, start + removed.length) стал [inserted]. */
        fun onEdit(start: Int, removed: String, inserted: String)
        fun onCursor(line: Int, column: Int, offset: Int)
    }

    var listener: Listener? = null

    val lines = LineStarts()

    private var lexer: Lexer? = null
    private var lineStates = IntArray(0)
    private var statesValidThrough = -1

    private var matches: List<IntRange> = emptyList()
    private var currentMatch = -1

    private var showLineNumbers = true
    private var suppressRecording = false
    private var pendingRemoved = ""

    private val gutterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val fillPaint = Paint()
    private var gutterWidth = 0
    private val basePadding = dp(12)

    private val highlightRunnable = Runnable { applyVisibleStyles() }

    init {
        typeface = Typeface.MONOSPACE
        gravity = Gravity.TOP or Gravity.START
        background = null
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        isVerticalScrollBarEnabled = true
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        setLineSpacing(0f, 1.25f)
        applyColors(colors)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                pendingRemoved = s.subSequence(start, start + count).toString()
            }

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                val inserted = s.subSequence(start, start + count).toString()
                lines.applyEdit(start, before, inserted)
                invalidateStatesFrom(lines.lineOf(start))
                if (!suppressRecording) listener?.onEdit(start, pendingRemoved, inserted)
                updateGutter()
            }

            override fun afterTextChanged(s: Editable) = scheduleStyles()
        })
    }

    // ---- Содержимое ----

    /** Новый документ: история и индексы строятся заново. */
    fun load(text: String) {
        suppressRecording = true
        setText(text)
        suppressRecording = false
        lines.rebuild(text)
        invalidateStatesFrom(0)
        updateGutter()
        setSelection(0)
        scheduleStyles()
    }

    /** Программная правка без записи в историю — для отмены и повтора. */
    fun applySilently(start: Int, end: Int, replacement: String, cursor: Int) {
        suppressRecording = true
        text.replace(start, end, replacement)
        suppressRecording = false
        setSelection(cursor.coerceIn(0, text.length))
    }

    /** Правка, которая попадает в историю: вставка символа с панели, замена найденного. */
    fun replaceRecorded(start: Int, end: Int, replacement: String) {
        text.replace(start, end, replacement)
        setSelection((start + replacement.length).coerceAtMost(text.length))
    }

    fun insertAtCursor(value: String) {
        val a = selectionStart.coerceAtLeast(0)
        val b = selectionEnd.coerceAtLeast(0)
        replaceRecorded(minOf(a, b), maxOf(a, b), value)
    }

    // ---- Вид ----

    private val editingKeyListener = keyListener
    private var readOnly = false

    /** Только чтение: текст можно выделять и копировать, но не менять. */
    fun setReadOnly(value: Boolean) {
        if (value == readOnly) return
        readOnly = value
        keyListener = if (value) null else editingKeyListener
        showSoftInputOnFocus = !value
        if (value) setTextIsSelectable(true)
    }

    fun configure(colors: EditorColors, fontSizeSp: Int, wrap: Boolean, lineNumbers: Boolean, lexer: Lexer?) {
        val styleChanged = colors != this.colors || lexer !== this.lexer
        this.colors = colors
        this.lexer = lexer
        applyColors(colors)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp.toFloat())
        gutterPaint.textSize = textSize * 0.85f
        setHorizontallyScrolling(!wrap)
        showLineNumbers = lineNumbers
        updateGutter()
        if (styleChanged) {
            invalidateStatesFrom(0)
            clearSpans<HighlightSpan>()
            scheduleStyles()
        }
    }

    fun setMatches(matches: List<IntRange>, current: Int) {
        this.matches = matches
        this.currentMatch = current
        scheduleStyles(immediate = true)
    }

    /** Прокрутить так, чтобы участок был виден, и выделить его. */
    fun reveal(range: IntRange) {
        if (range.first > text.length) return
        setSelection(range.first, (range.last + 1).coerceAtMost(text.length))
        post { bringPointIntoView(range.first) }
    }

    fun goToLine(line: Int) {
        val target = lines.startOf(line.coerceIn(0, lines.count - 1))
        setSelection(target)
        post { bringPointIntoView(target) }
    }

    private fun applyColors(c: EditorColors) {
        setTextColor(c.text)
        setBackgroundColor(c.background)
        highlightColor = (c.match and 0x00FFFFFF) or 0x66000000
        textCursorDrawable?.setTint(c.cursor)
        invalidate()
    }

    private fun updateGutter() {
        val width = if (showLineNumbers) {
            val digits = lines.count.toString().length.coerceAtLeast(2)
            (gutterPaint.measureText("9".repeat(digits)) + dp(20)).toInt()
        } else 0
        if (width != gutterWidth || paddingLeft != width + basePadding) {
            gutterWidth = width
            setPadding(width + basePadding, dp(10), basePadding, dp(80))
        }
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        // EditText вызывает это ещё из своего конструктора, когда поля этого класса не созданы.
        @Suppress("SENSELESS_COMPARISON")
        if (lines == null) return
        val line = lines.lineOf(selStart.coerceAtLeast(0))
        listener?.onCursor(line, selStart - lines.startOf(line), selStart)
        invalidate()
    }

    override fun onScrollChanged(horiz: Int, vert: Int, oldHoriz: Int, oldVert: Int) {
        super.onScrollChanged(horiz, vert, oldHoriz, oldVert)
        if (vert != oldVert) scheduleStyles()
    }

    override fun onDraw(canvas: Canvas) {
        val layout = layout
        if (layout != null && text.isNotEmpty()) drawCurrentLine(canvas)
        super.onDraw(canvas)
        if (layout != null && showLineNumbers) drawGutter(canvas)
    }

    private fun drawCurrentLine(canvas: Canvas) {
        val sel = selectionStart
        if (sel < 0 || sel != selectionEnd) return
        val line = lines.lineOf(sel)
        val startOffset = lines.startOf(line)
        val endOffset = if (line + 1 < lines.count) lines.startOf(line + 1) - 1 else text.length
        val top = layout.getLineTop(layout.getLineForOffset(startOffset)) + paddingTop
        val bottom = layout.getLineBottom(layout.getLineForOffset(endOffset)) + paddingTop
        fillPaint.color = colors.currentLine
        canvas.drawRect(scrollX.toFloat(), top.toFloat(), (scrollX + width).toFloat(), bottom.toFloat(), fillPaint)
    }

    private fun drawGutter(canvas: Canvas) {
        fillPaint.color = colors.gutter
        canvas.drawRect(scrollX.toFloat(), scrollY.toFloat(), (scrollX + gutterWidth).toFloat(), (scrollY + height).toFloat(), fillPaint)
        val firstVisual = layout.getLineForVertical((scrollY - paddingTop).coerceAtLeast(0))
        val lastVisual = layout.getLineForVertical(scrollY + height - paddingTop)
        val cursorLine = if (selectionStart >= 0) lines.lineOf(selectionStart) else -1
        val x = (scrollX + gutterWidth - dp(10)).toFloat()
        for (visual in firstVisual..lastVisual) {
            val start = layout.getLineStart(visual)
            if (start != 0 && text[start - 1] != '\n') continue
            val logical = lines.lineOf(start)
            gutterPaint.color = if (logical == cursorLine) colors.currentLineNumber else colors.lineNumber
            val baseline = layout.getLineBaseline(visual) + paddingTop
            canvas.drawText((logical + 1).toString(), x, baseline.toFloat(), gutterPaint)
        }
    }

    // ---- Подсветка ----

    private fun scheduleStyles(immediate: Boolean = false) {
        removeCallbacks(highlightRunnable)
        if (immediate) post(highlightRunnable) else postDelayed(highlightRunnable, 60)
    }

    private fun invalidateStatesFrom(line: Int) {
        statesValidThrough = minOf(statesValidThrough, line - 1)
        if (lineStates.size < lines.count) lineStates = lineStates.copyOf(maxOf(lines.count, lineStates.size * 2))
    }

    /** Подсветка и отметки поиска для видимых строк с запасом на прокрутку. */
    private fun applyVisibleStyles() {
        val layout = layout ?: return
        val editable = text ?: return
        if (editable.isEmpty()) return
        val firstVisual = layout.getLineForVertical((scrollY - paddingTop).coerceAtLeast(0))
        val lastVisual = layout.getLineForVertical(scrollY + height - paddingTop)
        val firstLine = (lines.lineOf(layout.getLineStart(firstVisual)) - MARGIN_LINES).coerceAtLeast(0)
        val lastLine = (lines.lineOf(layout.getLineEnd(lastVisual)) + MARGIN_LINES).coerceAtMost(lines.count - 1)
        val from = lines.startOf(firstLine)
        val to = if (lastLine + 1 < lines.count) lines.startOf(lastLine + 1) else editable.length

        removeSpans<HighlightSpan>(editable, from, to)
        removeSpans<MatchSpan>(editable, 0, editable.length)
        lexer?.let { lx ->
            var state = stateBefore(firstLine, lx)
            val tokens = ArrayList<Token>()
            for (line in firstLine..lastLine) {
                val start = lines.startOf(line)
                val end = if (line + 1 < lines.count) lines.startOf(line + 1) - 1 else editable.length
                tokens.clear()
                state = lx.lex(editable.substring(start, end), state, tokens)
                recordState(line, state)
                tokens.forEach { t -> styleFor(t.type)?.let { editable.setSpan(it, start + t.start, start + t.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) } }
            }
        }
        matches.forEachIndexed { i, m ->
            if (m.last < from || m.first > to) return@forEachIndexed
            val color = if (i == currentMatch) colors.currentMatch else colors.match
            editable.setSpan(MatchSpan(color), m.first, (m.last + 1).coerceAtMost(editable.length), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** Состояние разбора перед строкой: досчитываем от последней известной точки. */
    private fun stateBefore(line: Int, lx: Lexer): Int {
        if (line == 0) return Lexer.INITIAL
        if (statesValidThrough >= line - 1) return lineStates[line - 1]
        var state = if (statesValidThrough >= 0) lineStates[statesValidThrough] else Lexer.INITIAL
        val sink = ArrayList<Token>()
        for (l in statesValidThrough + 1 until line) {
            val start = lines.startOf(l)
            val end = if (l + 1 < lines.count) lines.startOf(l + 1) - 1 else text.length
            sink.clear()
            state = lx.lex(text.substring(start, end), state, sink)
            recordState(l, state)
        }
        return state
    }

    private fun recordState(line: Int, state: Int) {
        if (line >= lineStates.size) lineStates = lineStates.copyOf(maxOf(line + 1, lineStates.size * 2))
        lineStates[line] = state
        if (line == statesValidThrough + 1) statesValidThrough = line
    }

    private fun styleFor(type: TokenType): Any? = when (type) {
        TokenType.KEY, TokenType.TAG, TokenType.HEADING -> HighlightSpan(colors.key, bold = type == TokenType.HEADING)
        TokenType.STRING, TokenType.CODE -> HighlightSpan(colors.string)
        TokenType.NUMBER, TokenType.VARIABLE, TokenType.ATTRIBUTE -> HighlightSpan(colors.number)
        TokenType.COMMENT -> HighlightSpan(colors.comment, italic = true)
        TokenType.MUTED -> HighlightSpan(colors.comment)
        TokenType.EMPHASIS -> HighlightSpan(colors.text, bold = true)
        TokenType.ERROR -> HighlightSpan(colors.error)
        TokenType.WARNING -> HighlightSpan(colors.warning)
    }

    private inline fun <reified T> removeSpans(editable: Editable, from: Int, to: Int) {
        editable.getSpans(from, to, T::class.java).forEach { editable.removeSpan(it) }
    }

    private inline fun <reified T> clearSpans() {
        val editable = text ?: return
        removeSpans<T>(editable, 0, editable.length)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /** Свои классы спанов, чтобы снимать только их и не трогать служебные спаны ввода. */
    private class HighlightSpan(color: Int, private val bold: Boolean = false, private val italic: Boolean = false) :
        ForegroundColorSpan(color) {
        override fun updateDrawState(textPaint: android.text.TextPaint) {
            super.updateDrawState(textPaint)
            if (bold) textPaint.isFakeBoldText = true
            if (italic) textPaint.textSkewX = -0.2f
        }
    }

    private class MatchSpan(color: Int) : BackgroundColorSpan(color)

    private companion object {
        /** Сколько строк за краями экрана подсвечивать, чтобы при прокрутке не мигало. */
        const val MARGIN_LINES = 40
    }
}
