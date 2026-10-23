package io.quietfiles.core.model

/**
 * Место, открытое в окне обзора (F1.5): вид источника, папка или архив, путь внутри архива.
 * [kind] — имя вида источника экрана обзора или [ROOT] для списка хранилищ.
 */
data class WindowPlace(val kind: String, val path: String = "", val inner: String = "", val title: String = "") {
    companion object {
        const val ROOT = "ROOT"
        val Root = WindowPlace(ROOT)
    }
}

data class BrowseWindow(val id: Long, val place: WindowPlace)

/** Открытые окна (F1.5): у каждого своё место; одно — текущее. Всегда открыто хотя бы одно. */
data class OpenWindows(val windows: List<BrowseWindow>, val currentId: Long) {

    val current: BrowseWindow get() = windows.firstOrNull { it.id == currentId } ?: windows.first()

    /** Новое окно становится текущим; сверх [MAX] вытесняется самое давнее из нетекущих. */
    fun open(place: WindowPlace = WindowPlace.Root): OpenWindows {
        val id = (windows.maxOfOrNull { it.id } ?: 0) + 1
        val kept = if (windows.size >= MAX) windows.filterNot { it.id == windows.first { w -> w.id != currentId }.id } else windows
        return OpenWindows(kept + BrowseWindow(id, place), id)
    }

    /** Закрыли текущее — становится текущим соседнее справа, а если его нет — слева. */
    fun close(id: Long): OpenWindows {
        val index = windows.indexOfFirst { it.id == id }
        if (index < 0) return this
        if (windows.size == 1) return OpenWindows(listOf(BrowseWindow(windows[0].id, WindowPlace.Root)), windows[0].id)
        val rest = windows.toMutableList().apply { removeAt(index) }
        val current = if (id == currentId) rest[index.coerceAtMost(rest.lastIndex)].id else currentId
        return OpenWindows(rest, current)
    }

    fun select(id: Long): OpenWindows = if (windows.any { it.id == id }) copy(currentId = id) else this

    /** Пользователь перешёл в другую папку: окно запоминает новое место. */
    fun moveCurrent(place: WindowPlace): OpenWindows =
        copy(windows = windows.map { if (it.id == current.id) it.copy(place = place) else it })

    companion object {
        const val MAX = 8
        val Initial = OpenWindows(listOf(BrowseWindow(1, WindowPlace.Root)), 1)
    }
}
