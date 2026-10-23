package io.quietfiles.core.common

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Списки файлов, среди которых открыт просмотрщик: «Фото» целиком, результаты поиска, папка.
 * В маршрут навигации тысячи путей не положить, поэтому маршрут хранит только номер списка.
 * После перезапуска процесса список теряется — тогда просмотрщик берёт файлы из папки.
 */
@Singleton
class ViewerSessions @Inject constructor() {
    private val sessions = object : LinkedHashMap<Long, List<String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<String>>) = size > MAX_SESSIONS
    }
    private var nextId = 1L

    @Synchronized
    fun put(paths: List<String>): Long {
        if (paths.isEmpty()) return 0
        val id = nextId++
        sessions[id] = paths
        return id
    }

    @Synchronized
    fun get(id: Long): List<String>? = sessions[id]

    private companion object {
        const val MAX_SESSIONS = 8
    }
}
