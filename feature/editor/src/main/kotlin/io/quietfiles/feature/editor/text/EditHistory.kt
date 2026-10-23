package io.quietfiles.feature.editor.text

/**
 * Отмена и повтор (F12.2). Хранит не снимки текста, а сами правки, поэтому и для файла в 5 МБ
 * память не растёт. Набор подряд объединяется в одну правку — как в обычных редакторах.
 */
class EditHistory(private val capacity: Int = DEFAULT_CAPACITY) {

    /** Участок [start, start + removed.length) заменили на [inserted]. */
    data class Edit(
        val start: Int,
        val removed: String,
        val inserted: String,
        val time: Long,
        internal val id: Long,
    ) {
        val insertedEnd: Int get() = start + inserted.length
    }

    private val undo = ArrayDeque<Edit>()
    private val redo = ArrayDeque<Edit>()
    private var nextId = 1L
    private var savedId = 0L

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    /** Текст совпадает с сохранённым. */
    val isClean: Boolean get() = (undo.lastOrNull()?.id ?: 0L) == savedId

    fun record(start: Int, removed: String, inserted: String, time: Long) {
        if (removed == inserted) return
        redo.clear()
        val last = undo.lastOrNull()
        val merged = last?.takeIf { time - it.time < MERGE_WINDOW_MS }?.let { merge(it, start, removed, inserted) }
        if (merged != null) {
            undo.removeLast()
            undo.addLast(merged.copy(time = time, id = nextId++))
        } else {
            undo.addLast(Edit(start, removed, inserted, time, nextId++))
            if (undo.size > capacity) undo.removeFirst()
        }
    }

    /** Правка, которую нужно откатить: заменить [Edit.inserted] на [Edit.removed]. */
    fun undo(): Edit? = undo.removeLastOrNull()?.also { redo.addLast(it) }

    /** Правка, которую нужно повторить. */
    fun redo(): Edit? = redo.removeLastOrNull()?.also { undo.addLast(it) }

    fun markSaved() {
        savedId = undo.lastOrNull()?.id ?: 0L
    }

    fun clear() {
        undo.clear()
        redo.clear()
        savedId = 0L
    }

    private fun merge(last: Edit, start: Int, removed: String, inserted: String): Edit? {
        // Ввод подряд: следующий символ сразу за предыдущим, слово не заканчивается пробелом или переводом строки.
        if (removed.isEmpty() && last.removed.isEmpty() && start == last.insertedEnd && inserted.length == 1) {
            val boundary = inserted[0].isWhitespace() && last.inserted.lastOrNull()?.isWhitespace() == false
            if (!boundary && inserted[0] != '\n') return last.copy(inserted = last.inserted + inserted)
        }
        // Стирание подряд клавишей «назад».
        if (inserted.isEmpty() && last.inserted.isEmpty() && removed.length == 1 && start + 1 == last.start && removed[0] != '\n') {
            return last.copy(start = start, removed = removed + last.removed)
        }
        return null
    }

    companion object {
        /** ТЗ требует не меньше 100 шагов; правки маленькие, держим с запасом. */
        const val DEFAULT_CAPACITY = 500
        const val MERGE_WINDOW_MS = 1_500L
    }
}
