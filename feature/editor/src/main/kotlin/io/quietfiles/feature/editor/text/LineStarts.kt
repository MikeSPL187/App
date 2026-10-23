package io.quietfiles.feature.editor.text

/**
 * Начала логических строк текста. Обновляется по каждой правке без полного пересчёта,
 * поэтому номер строки под курсором и подсветка видимых строк не тормозят на больших файлах.
 */
class LineStarts {
    private var starts = IntArray(64)

    var count: Int = 1
        private set

    fun rebuild(text: CharSequence) {
        count = 1
        starts[0] = 0
        for (i in text.indices) if (text[i] == '\n') add(i + 1)
    }

    fun startOf(line: Int): Int = starts[line]

    /** Номер строки, в которой стоит смещение [offset]. */
    fun lineOf(offset: Int): Int {
        var lo = 0
        var hi = count - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Участок [start, start + removedLength) заменён на [inserted]. */
    fun applyEdit(start: Int, removedLength: Int, inserted: CharSequence) {
        val removedEnd = start + removedLength
        val delta = inserted.length - removedLength
        // Строки, начинавшиеся внутри удалённого участка, исчезают.
        val firstAfter = firstIndexAfter(start)
        var firstKept = firstAfter
        while (firstKept < count && starts[firstKept] <= removedEnd) firstKept++
        val newStarts = ArrayList<Int>()
        for (i in inserted.indices) if (inserted[i] == '\n') newStarts += start + i + 1
        val tail = count - firstKept
        val newCount = firstAfter + newStarts.size + tail
        ensureCapacity(newCount)
        System.arraycopy(starts, firstKept, starts, firstAfter + newStarts.size, tail)
        for (k in 0 until tail) starts[firstAfter + newStarts.size + k] += delta
        newStarts.forEachIndexed { k, s -> starts[firstAfter + k] = s }
        count = newCount
    }

    private fun firstIndexAfter(offset: Int): Int {
        var lo = 0
        var hi = count
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= offset) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun add(start: Int) {
        ensureCapacity(count + 1)
        starts[count++] = start
    }

    private fun ensureCapacity(n: Int) {
        if (n > starts.size) starts = starts.copyOf(maxOf(n, starts.size * 2))
    }
}
