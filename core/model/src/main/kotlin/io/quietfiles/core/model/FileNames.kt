package io.quietfiles.core.model

/** Работа с именами и путями в формате POSIX. Не обращается к файловой системе. */
object FileNames {
    /** Символы, запрещённые в именах на FAT/exFAT (SD-карты и флешки) и в Android. */
    private val forbiddenChars = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|', '\u0000')
    private const val MAX_NAME_BYTES = 255

    // Запас под « (99)» и временное имя «.имя.qfpart»: вместе с ними имя не превысит 255 байт.
    private const val SAFE_NAME_BYTES = 230

    fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1)
    }

    fun baseName(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name else name.substring(0, dot)
    }

    fun nameOf(path: String): String = path.trimEnd('/').substringAfterLast('/')

    fun parentOf(path: String): String? {
        val trimmed = path.trimEnd('/')
        if (trimmed.isEmpty()) return null
        val index = trimmed.lastIndexOf('/')
        return when {
            index < 0 -> null
            index == 0 -> "/"
            else -> trimmed.substring(0, index)
        }
    }

    fun child(parent: String, name: String): String =
        if (parent.endsWith('/')) parent + name else "$parent/$name"

    /** `true`, если [path] совпадает с [ancestor] или лежит внутри него. */
    fun isSameOrDescendant(path: String, ancestor: String): Boolean {
        val p = path.trimEnd('/')
        val a = ancestor.trimEnd('/')
        return p == a || p.startsWith("$a/")
    }

    sealed interface Validation {
        data object Ok : Validation
        data object Empty : Validation
        data object DotOnly : Validation
        data class ForbiddenChar(val char: Char) : Validation
        data object TooLong : Validation
    }

    fun validate(name: String): Validation {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> Validation.Empty
            trimmed == "." || trimmed == ".." -> Validation.DotOnly
            trimmed.any { it in forbiddenChars || it.code < 0x20 } ->
                Validation.ForbiddenChar(trimmed.first { it in forbiddenChars || it.code < 0x20 })
            trimmed.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES -> Validation.TooLong
            else -> Validation.Ok
        }
    }

    /**
     * Делает имя из чужого источника (другое приложение, архив) пригодным для файла: убирает путь,
     * заменяет запрещённые символы на «_», не даёт имени стать скрытым и укорачивает его,
     * оставляя место для суффикса « (1)» и временного файла. Пустое имя заменяется на [fallback].
     */
    fun sanitize(raw: String, fallback: String = "file"): String {
        var name = raw.substringAfterLast('/').substringAfterLast('\\')
            .map { if (it in forbiddenChars || it.code < 0x20) '_' else it }
            .joinToString("")
            .trim()
            .trimStart('.')
            .trim()
        if (name.isEmpty()) name = fallback
        val ext = extension(name).let { if (it.length in 1..16) it else "" }
        var base = if (ext.isEmpty()) name else name.dropLast(ext.length + 1)
        val suffix = if (ext.isEmpty()) "" else ".$ext"
        while ((base + suffix).toByteArray(Charsets.UTF_8).size > SAFE_NAME_BYTES && base.isNotEmpty()) {
            base = base.dropLast(1)
        }
        if (base.isEmpty()) base = fallback
        return base.trimEnd() + suffix
    }

    /**
     * Свободное имя в стиле «Отчёт (1).pdf», «Отчёт (2).pdf».
     * Для папок расширение не выделяется.
     */
    fun uniqueName(name: String, isDirectory: Boolean, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val ext = if (isDirectory) "" else extension(name)
        var base = if (ext.isEmpty()) name else name.dropLast(ext.length + 1)
        val suffix = Regex(""" \((\d+)\)$""").find(base)
        var counter = 1
        if (suffix != null) {
            counter = suffix.groupValues[1].toInt() + 1
            base = base.removeRange(suffix.range)
        }
        while (true) {
            val candidate = if (ext.isEmpty()) "$base ($counter)" else "$base ($counter).$ext"
            if (!exists(candidate)) return candidate
            counter++
        }
    }
}
