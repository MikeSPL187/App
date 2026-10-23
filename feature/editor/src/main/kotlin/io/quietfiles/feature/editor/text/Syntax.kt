package io.quietfiles.feature.editor.text

import io.quietfiles.core.model.FileNames

/** Языки с подсветкой (F12.3). */
enum class Language(val label: String) {
    PLAIN(""),
    JSON("JSON"),
    YAML("YAML"),
    XML("XML"),
    INI("INI"),
    MARKDOWN("Markdown"),
    SHELL("Shell"),
    LOG("Log"),
    ;

    companion object {
        fun of(fileName: String): Language {
            val lower = fileName.lowercase()
            val ext = FileNames.extension(lower)
            return when {
                ext in setOf("json", "jsonc", "geojson", "webmanifest", "har") -> JSON
                ext in setOf("yaml", "yml") -> YAML
                ext in setOf("xml", "html", "htm", "xhtml", "svg", "plist", "xsd", "xsl", "kml", "gpx", "rss", "atom") -> XML
                ext in setOf("ini", "properties", "conf", "cfg", "toml", "env", "gitconfig", "desktop") || lower == ".env" -> INI
                ext in setOf("md", "markdown", "mdown") -> MARKDOWN
                ext in setOf("sh", "bash", "zsh", "ksh") || lower in setOf(".bashrc", ".zshrc", ".profile") -> SHELL
                ext == "log" || ext == "out" && lower.contains("log") -> LOG
                else -> PLAIN
            }
        }
    }
}

enum class TokenType { KEY, STRING, NUMBER, COMMENT, TAG, ATTRIBUTE, HEADING, EMPHASIS, CODE, ERROR, WARNING, MUTED, VARIABLE }

/** Участок строки [start, end) одного типа. */
data class Token(val start: Int, val end: Int, val type: TokenType)

/**
 * Разбор по строкам: на вход строка и состояние после предыдущей (например, «внутри комментария»),
 * на выход — токены и состояние для следующей. Так можно подсвечивать только видимые строки.
 */
fun interface Lexer {
    fun lex(line: String, state: Int, out: MutableList<Token>): Int

    companion object {
        const val INITIAL = 0

        fun of(language: Language): Lexer? = when (language) {
            Language.PLAIN -> null
            Language.JSON -> JsonLexer
            Language.YAML -> YamlLexer
            Language.XML -> XmlLexer
            Language.INI -> IniLexer
            Language.MARKDOWN -> MarkdownLexer
            Language.SHELL -> ShellLexer
            Language.LOG -> LogLexer
        }
    }
}

private fun String.skipSpaces(from: Int): Int {
    var i = from
    while (i < length && (this[i] == ' ' || this[i] == '\t')) i++
    return i
}

/** Конец строки в кавычках с учётом `\"`; если кавычка не закрыта — конец строки. */
private fun String.quoteEnd(from: Int, quote: Char, escapes: Boolean = true): Int {
    var i = from + 1
    while (i < length) {
        if (escapes && this[i] == '\\') {
            i += 2
            continue
        }
        if (this[i] == quote) return i + 1
        i++
    }
    return length
}

private val YAML_LITERALS = setOf("true", "false", "null", "yes", "no", "on", "off", "~")
private fun isNumberLike(s: String): Boolean = s.isNotEmpty() && (s[0].isDigit() || (s[0] in "-+." && s.length > 1 && s[1].isDigit()))

internal object JsonLexer : Lexer {
    private const val IN_COMMENT = 1

    override fun lex(line: String, state: Int, out: MutableList<Token>): Int {
        var i = 0
        if (state == IN_COMMENT) {
            val end = line.indexOf("*/")
            if (end < 0) {
                if (line.isNotEmpty()) out += Token(0, line.length, TokenType.COMMENT)
                return IN_COMMENT
            }
            out += Token(0, end + 2, TokenType.COMMENT)
            i = end + 2
        }
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    val end = line.quoteEnd(i, '"')
                    val next = line.skipSpaces(end)
                    out += Token(i, end, if (next < line.length && line[next] == ':') TokenType.KEY else TokenType.STRING)
                    i = end
                }
                c == '/' && line.startsWith("//", i) -> {
                    out += Token(i, line.length, TokenType.COMMENT)
                    return Lexer.INITIAL
                }
                c == '/' && line.startsWith("/*", i) -> {
                    val end = line.indexOf("*/", i + 2)
                    if (end < 0) {
                        out += Token(i, line.length, TokenType.COMMENT)
                        return IN_COMMENT
                    }
                    out += Token(i, end + 2, TokenType.COMMENT)
                    i = end + 2
                }
                c == '-' || c.isDigit() -> {
                    var j = i + 1
                    while (j < line.length && (line[j].isLetterOrDigit() || line[j] in ".+-")) j++
                    out += Token(i, j, TokenType.NUMBER)
                    i = j
                }
                c.isLetter() -> {
                    var j = i + 1
                    while (j < line.length && line[j].isLetter()) j++
                    if (line.substring(i, j) in setOf("true", "false", "null")) out += Token(i, j, TokenType.NUMBER)
                    i = j
                }
                else -> i++
            }
        }
        return Lexer.INITIAL
    }
}

internal object YamlLexer : Lexer {
    override fun lex(line: String, state: Int, out: MutableList<Token>): Int {
        var i = line.skipSpaces(0)
        if (i >= line.length) return Lexer.INITIAL
        if (line[i] == '#') {
            out += Token(i, line.length, TokenType.COMMENT)
            return Lexer.INITIAL
        }
        if (line.startsWith("---", i) || line.startsWith("...", i)) {
            out += Token(i, line.length, TokenType.MUTED)
            return Lexer.INITIAL
        }
        if (line[i] == '-' && (i + 1 == line.length || line[i + 1] == ' ')) i = line.skipSpaces(i + 1)
        // Ключ: до двоеточия, за которым пробел или конец строки, если он не в кавычках.
        val keyEnd = keyEnd(line, i)
        if (keyEnd > i) {
            out += Token(i, keyEnd, TokenType.KEY)
            i = line.skipSpaces(keyEnd + 1)
        }
        value(line, i, out)
        return Lexer.INITIAL
    }

    private fun keyEnd(line: String, from: Int): Int {
        if (from >= line.length || line[from] in "\"'[{|>&*!") return -1
        var i = from
        while (i < line.length) {
            if (line[i] == ':' && (i + 1 == line.length || line[i + 1] == ' ')) return i
            if (line[i] == '#' && i > from && line[i - 1] == ' ') return -1
            i++
        }
        return -1
    }

    private fun value(line: String, from: Int, out: MutableList<Token>) {
        if (from >= line.length) return
        val c = line[from]
        if (c == '#') {
            out += Token(from, line.length, TokenType.COMMENT)
            return
        }
        if (c == '"' || c == '\'') {
            val end = line.quoteEnd(from, c, escapes = c == '"')
            out += Token(from, end, TokenType.STRING)
            comment(line, end, out)
            return
        }
        if (c in "&*") {
            var j = from + 1
            while (j < line.length && !line[j].isWhitespace()) j++
            out += Token(from, j, TokenType.VARIABLE)
            value(line, line.skipSpaces(j), out)
            return
        }
        if (c in "|>[{") return
        val hash = line.indexOf(" #", from)
        val end = (if (hash < 0) line.length else hash).let { e -> var k = e; while (k > from && line[k - 1] == ' ') k--; k }
        val word = line.substring(from, end)
        out += Token(from, end, if (word.lowercase() in YAML_LITERALS || isNumberLike(word)) TokenType.NUMBER else TokenType.STRING)
        comment(line, end, out)
    }

    private fun comment(line: String, from: Int, out: MutableList<Token>) {
        val hash = line.indexOf('#', from)
        if (hash >= 0 && (hash == 0 || line[hash - 1] == ' ')) out += Token(hash, line.length, TokenType.COMMENT)
    }
}

internal object XmlLexer : Lexer {
    private const val IN_COMMENT = 1
    private const val IN_TAG = 2
    private const val IN_CDATA = 3

    override fun lex(line: String, state: Int, out: MutableList<Token>): Int {
        var i = 0
        var st = state
        while (i < line.length) {
            when (st) {
                IN_COMMENT, IN_CDATA -> {
                    val closer = if (st == IN_COMMENT) "-->" else "]]>"
                    val end = line.indexOf(closer, i)
                    val stop = if (end < 0) line.length else end + closer.length
                    out += Token(i, stop, if (st == IN_COMMENT) TokenType.COMMENT else TokenType.STRING)
                    if (end < 0) return st
                    i = stop
                    st = Lexer.INITIAL
                }
                IN_TAG -> {
                    val c = line[i]
                    when {
                        c == '>' -> {
                            out += Token(i, i + 1, TokenType.TAG)
                            i++
                            st = Lexer.INITIAL
                        }
                        c == '/' || c == '?' -> if (i + 1 < line.length && line[i + 1] == '>') {
                            out += Token(i, i + 2, TokenType.TAG)
                            i += 2
                            st = Lexer.INITIAL
                        } else i++
                        c == '"' || c == '\'' -> {
                            val end = line.quoteEnd(i, c, escapes = false)
                            out += Token(i, end, TokenType.STRING)
                            i = end
                        }
                        c.isLetter() || c == '_' || c == ':' -> {
                            var j = i + 1
                            while (j < line.length && (line[j].isLetterOrDigit() || line[j] in "_:-.")) j++
                            out += Token(i, j, TokenType.ATTRIBUTE)
                            i = j
                        }
                        else -> i++
                    }
                }
                else -> {
                    val lt = line.indexOf('<', i)
                    if (lt < 0) return Lexer.INITIAL
                    i = lt
                    when {
                        line.startsWith("<!--", i) -> {
                            st = IN_COMMENT
                            out += Token(i, i + 4, TokenType.COMMENT)
                            i += 4
                        }
                        line.startsWith("<![CDATA[", i) -> {
                            st = IN_CDATA
                            out += Token(i, i + 9, TokenType.MUTED)
                            i += 9
                        }
                        else -> {
                            var j = i + 1
                            if (j < line.length && line[j] in "/?!") j++
                            while (j < line.length && (line[j].isLetterOrDigit() || line[j] in "_:-.")) j++
                            out += Token(i, j, TokenType.TAG)
                            i = j
                            st = IN_TAG
                        }
                    }
                }
            }
        }
        return st
    }
}

internal object IniLexer : Lexer {
    override fun lex(line: String, state: Int, out: MutableList<Token>): Int {
        val i = line.skipSpaces(0)
        if (i >= line.length) return Lexer.INITIAL
        when {
            line[i] == '#' || line[i] == ';' || line.startsWith("!", i) -> out += Token(i, line.length, TokenType.COMMENT)
            line[i] == '[' -> out += Token(i, line.length.coerceAtMost(line.indexOf(']', i).let { if (it < 0) line.length else it + 1 }), TokenType.TAG)
            else -> {
                val sep = line.indexOfAny(charArrayOf('=', ':'), i)
                if (sep < 0) return Lexer.INITIAL
                var keyEnd = sep
                while (keyEnd > i && line[keyEnd - 1] == ' ') keyEnd--
                out += Token(i, keyEnd, TokenType.KEY)
                val v = line.skipSpaces(sep + 1)
                if (v < line.length) {
                    val word = line.substring(v).trim()
                    val type = when {
                        word.startsWith('"') || word.startsWith('\'') -> TokenType.STRING
                        word.lowercase() in setOf("true", "false", "yes", "no", "on", "off") || isNumberLike(word) -> TokenType.NUMBER
                        else -> TokenType.STRING
                    }
                    out += Token(v, line.length, type)
                }
            }
        }
        return Lexer.INITIAL
    }
}

internal object MarkdownLexer : Lexer {
    private const val IN_FENCE = 1

    override fun lex(line: String, state: Int, out: MutableList<Token>): Int {
        val i = line.skipSpaces(0)
        val fence = line.startsWith("```", i) || line.startsWith("~~~", i)
        if (state == IN_FENCE) {
            if (line.isNotEmpty()) out += Token(0, line.length, TokenType.CODE)
            return if (fence) Lexer.INITIAL else IN_FENCE
        }
        if (fence) {
            out += Token(0, line.length, TokenType.CODE)
            return IN_FENCE
        }
        if (i < line.length && line[i] == '#') {
            out += Token(i, line.length, TokenType.HEADING)
            return Lexer.INITIAL
        }
        if (i < line.length && line[i] == '>') {
            out += Token(i, line.length, TokenType.MUTED)
            return Lexer.INITIAL
        }
        var j = i
        if (j < line.length && line[j] in "-*+" && j + 1 < line.length && line[j + 1] == ' ') {
            out += Token(j, j + 1, TokenType.KEY)
            j += 2
        } else {
            var k = j
            while (k < line.length && line[k].isDigit()) k++
            if (k > j && k < line.length && line[k] == '.') {
                out += Token(j, k + 1, TokenType.KEY)
                j = k + 1
            }
        }
        inline(line, j, out)
        return Lexer.INITIAL
    }

    private fun inline(line: String, from: Int, out: MutableList<Token>) {
        var i = from
        while (i < line.length) {
            val c = line[i]
            when {
                c == '`' -> {
                    val end = line.indexOf('`', i + 1)
                    if (end < 0) return
                    out += Token(i, end + 1, TokenType.CODE)
                    i = end + 1
                }
                c == '*' || c == '_' -> {
                    val double = i + 1 < line.length && line[i + 1] == c
                    val marker = if (double) "$c$c" else "$c"
                    val end = line.indexOf(marker, i + marker.length)
                    if (end > i + marker.length) {
                        out += Token(i, end + marker.length, TokenType.EMPHASIS)
                        i = end + marker.length
                    } else i += marker.length
                }
                c == '[' -> {
                    val close = line.indexOf("](", i)
                    val paren = if (close < 0) -1 else line.indexOf(')', close)
                    if (paren > 0) {
                        out += Token(i, paren + 1, TokenType.ATTRIBUTE)
                        i = paren + 1
                    } else i++
                }
                else -> i++
            }
        }
    }
}

internal object ShellLexer : Lexer {
    private val keywords = setOf(
        "if", "then", "else", "elif", "fi", "for", "in", "do", "done", "while", "until", "case", "esac",
        "function", "return", "export", "local", "readonly", "set", "unset", "source", "exit", "echo",
    )

    override fun lex(line: String, state: Int, out: MutableList<Token>): Int {
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '#' && (i == 0 || line[i - 1].isWhitespace()) -> {
                    out += Token(i, line.length, TokenType.COMMENT)
                    return Lexer.INITIAL
                }
                c == '"' || c == '\'' -> {
                    val end = line.quoteEnd(i, c, escapes = c == '"')
                    out += Token(i, end, TokenType.STRING)
                    i = end
                }
                c == '$' && i + 1 < line.length -> {
                    var j = i + 1
                    if (line[j] == '{') {
                        j = line.indexOf('}', j).let { if (it < 0) line.length else it + 1 }
                    } else {
                        while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '_' || (j == i + 1 && line[j] in "@#?$!*-"))) j++
                    }
                    if (j > i + 1) out += Token(i, j, TokenType.VARIABLE)
                    i = maxOf(j, i + 1)
                }
                c.isLetter() || c == '_' -> {
                    var j = i + 1
                    while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '_')) j++
                    val wordStart = i == 0 || !line[i - 1].isLetterOrDigit() && line[i - 1] != '-' && line[i - 1] != '/'
                    if (wordStart && line.substring(i, j) in keywords) out += Token(i, j, TokenType.KEY)
                    i = j
                }
                else -> i++
            }
        }
        return Lexer.INITIAL
    }
}

internal object LogLexer : Lexer {
    private val errors = setOf("ERROR", "ERR", "FATAL", "SEVERE", "CRITICAL", "E", "F", "PANIC")
    private val warnings = setOf("WARN", "WARNING", "W")
    private val infos = setOf("INFO", "I", "NOTICE")
    private val debugs = setOf("DEBUG", "TRACE", "VERBOSE", "D", "V")

    override fun lex(line: String, state: Int, out: MutableList<Token>): Int {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("at ") || trimmed.startsWith("...") || trimmed.startsWith("Caused by:")) {
            out += Token(0, line.length, if (trimmed.startsWith("Caused by:")) TokenType.ERROR else TokenType.MUTED)
            return Lexer.INITIAL
        }
        // Метка времени в начале: цифры и разделители.
        var i = 0
        while (i < line.length && (line[i].isDigit() || line[i] in "-:./ TZ+,")) i++
        while (i > 0 && !line[i - 1].isDigit()) i--
        if (i >= 6) out += Token(0, i, TokenType.MUTED)
        // Уровень: отдельное слово или logcat-буква «E/», «W/».
        var j = i
        while (j < line.length) {
            while (j < line.length && !line[j].isLetter()) j++
            var k = j
            while (k < line.length && line[k].isLetter()) k++
            if (k == j) break
            val word = line.substring(j, k)
            val type = when {
                word in errors -> TokenType.ERROR
                word in warnings -> TokenType.WARNING
                word in infos -> TokenType.KEY
                word in debugs -> TokenType.MUTED
                else -> null
            }
            val single = word.length == 1
            val delimited = !single || (k < line.length && line[k] in "/ ") && (j == 0 || line[j - 1] == ' ')
            if (type != null && delimited) {
                out += Token(j, k, type)
                if (type == TokenType.ERROR) out += Token(k, line.length, TokenType.ERROR)
                break
            }
            j = k
            if (j - i > 64) break
        }
        return Lexer.INITIAL
    }
}
