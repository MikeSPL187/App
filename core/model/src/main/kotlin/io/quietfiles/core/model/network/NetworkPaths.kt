package io.quietfiles.core.model.network

/**
 * Пути сетевых хранилищ. Внутри приложения файл на сервере имеет обычный POSIX-путь
 * `/qf-net/<id подключения>/<путь на сервере>`, поэтому родитель, имя и хлебные крошки считаются
 * теми же функциями, что и для локальных файлов, а операции F5 работают без отдельного кода.
 *
 * Для SMB первая часть пути на сервере — общий ресурс: `/qf-net/3/Public/Фильмы`.
 */
object NetworkPaths {
    const val ROOT = "/qf-net"

    fun isNetwork(path: String): Boolean = path == ROOT || path.startsWith("$ROOT/")

    /** Корень подключения: `/qf-net/3`. */
    fun rootOf(connectionId: Long): String = "$ROOT/$connectionId"

    fun pathOf(connectionId: Long, remote: String): String {
        val clean = normalize(remote)
        return if (clean == "/") rootOf(connectionId) else rootOf(connectionId) + clean
    }

    /** Номер подключения и путь на сервере (`/`, `/home/pi`), или null для локального пути. */
    fun parse(path: String): Pair<Long, String>? {
        if (!path.startsWith("$ROOT/")) return null
        val rest = path.substring(ROOT.length + 1)
        val slash = rest.indexOf('/')
        val id = (if (slash < 0) rest else rest.substring(0, slash)).toLongOrNull() ?: return null
        val remote = if (slash < 0) "/" else normalize(rest.substring(slash))
        return id to remote
    }

    fun connectionIdOf(path: String): Long? = parse(path)?.first

    /** Убирает повторы и концевые «/», запрещает `.` и `..`, чтобы путь не вышел за корень. */
    fun normalize(remote: String): String {
        val parts = remote.split('/').filter { it.isNotEmpty() && it != "." }
        require(parts.none { it == ".." }) { "Parent references are not allowed" }
        return "/" + parts.joinToString("/")
    }
}
