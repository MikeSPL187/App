package io.quietfiles.core.model

enum class SortField { NAME, DATE, SIZE, TYPE }

enum class ViewMode { LIST, GRID }

data class SortOrder(
    val field: SortField = SortField.NAME,
    val ascending: Boolean = true,
    val foldersFirst: Boolean = true,
) {
    fun comparator(): Comparator<FileEntry> {
        val byField: Comparator<FileEntry> = when (field) {
            SortField.NAME -> Comparator { a, b -> NaturalOrder.compare(a.name, b.name) }
            SortField.DATE -> compareBy<FileEntry> { it.lastModified }
                .thenComparator { a, b -> NaturalOrder.compare(a.name, b.name) }
            SortField.SIZE -> compareBy<FileEntry> { if (it.isDirectory) (it.childCount ?: 0).toLong() else it.size }
                .thenComparator { a, b -> NaturalOrder.compare(a.name, b.name) }
            SortField.TYPE -> compareBy<FileEntry> { it.extension.lowercase() }
                .thenComparator { a, b -> NaturalOrder.compare(a.name, b.name) }
        }
        val ordered = if (ascending) byField else byField.reversed()
        return if (foldersFirst) compareByDescending<FileEntry> { it.isDirectory }.then(ordered) else ordered
    }
}

/**
 * «Естественная» сортировка имён: без учёта регистра, «ё» как «е», числа сравниваются по значению
 * («файл 2» раньше «файл 10»).
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        val x = normalize(a)
        val y = normalize(b)
        var i = 0
        var j = 0
        while (i < x.length && j < y.length) {
            val cx = x[i]
            val cy = y[j]
            if (cx.isDigit() && cy.isDigit()) {
                val startX = i
                val startY = j
                while (i < x.length && x[i].isDigit()) i++
                while (j < y.length && y[j].isDigit()) j++
                val numX = x.substring(startX, i).trimStart('0')
                val numY = y.substring(startY, j).trimStart('0')
                if (numX.length != numY.length) return numX.length - numY.length
                val cmp = numX.compareTo(numY)
                if (cmp != 0) return cmp
            } else {
                if (cx != cy) return cx.compareTo(cy)
                i++
                j++
            }
        }
        val tail = (x.length - i) - (y.length - j)
        return if (tail != 0) tail else a.compareTo(b)
    }

    private fun normalize(s: String): String = s.lowercase().replace('ё', 'е')
}
