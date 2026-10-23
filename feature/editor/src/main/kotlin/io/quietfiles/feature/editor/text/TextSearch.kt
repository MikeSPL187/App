package io.quietfiles.feature.editor.text

import java.util.regex.PatternSyntaxException

/** Параметры поиска (F12.2). */
data class SearchQuery(val text: String, val matchCase: Boolean = false, val regex: Boolean = false)

/** Найденные участки; [error] — текст ошибки в регулярном выражении. */
data class SearchResult(val matches: List<IntRange>, val error: String? = null, val truncated: Boolean = false)

object TextSearch {

    /** Подсвечивать больше совпадений бессмысленно, а держать их в памяти дорого. */
    const val MAX_MATCHES = 10_000

    fun find(text: CharSequence, query: SearchQuery): SearchResult {
        if (query.text.isEmpty()) return SearchResult(emptyList())
        val regex = try {
            toRegex(query)
        } catch (e: PatternSyntaxException) {
            return SearchResult(emptyList(), error = e.description)
        }
        val matches = ArrayList<IntRange>()
        var result = regex.find(text)
        while (result != null && matches.size < MAX_MATCHES) {
            val range = result.range
            // Пустое совпадение (например, «^») не подсвечиваем, но и не зацикливаемся на нём.
            if (!range.isEmpty()) matches += range
            result = result.next()
        }
        return SearchResult(matches, truncated = result != null)
    }

    /** Текст замены для одного совпадения: в режиме регулярных выражений работают $1, $2… */
    fun replacementFor(text: CharSequence, match: IntRange, query: SearchQuery, replacement: String): String {
        if (!query.regex) return replacement
        val m = toRegex(query).matchAt(text, match.first) ?: return replacement
        return expand(replacement, m)
    }

    /** Замена всех совпадений; возвращает новый текст и число замен. */
    fun replaceAll(text: String, query: SearchQuery, replacement: String): Pair<String, Int> {
        if (query.text.isEmpty()) return text to 0
        val regex = toRegex(query)
        var count = 0
        val result = regex.replace(text) { m ->
            if (m.value.isEmpty()) return@replace m.value
            count++
            if (query.regex) expand(replacement, m) else replacement
        }
        return result to count
    }

    /** Подстановка групп: `$1`…`$99`, `\$` — сам знак доллара. Самая длинная существующая группа побеждает. */
    private fun expand(template: String, m: MatchResult): String {
        val sb = StringBuilder()
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c == '\\' && i + 1 < template.length) {
                sb.append(template[i + 1])
                i += 2
                continue
            }
            if (c == '$' && i + 1 < template.length && template[i + 1].isDigit()) {
                var j = i + 1
                while (j < template.length && template[j].isDigit() && template.substring(i + 1, j + 1).toInt() < m.groupValues.size) j++
                val group = template.substring(i + 1, j).toIntOrNull()
                if (group != null && group < m.groupValues.size) {
                    sb.append(m.groupValues[group])
                    i = j
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun toRegex(query: SearchQuery): Regex {
        val options = buildSet {
            if (!query.matchCase) add(RegexOption.IGNORE_CASE)
            if (query.regex) add(RegexOption.MULTILINE)
        }
        val pattern = if (query.regex) query.text else Regex.escape(query.text)
        // UNICODE_CASE нужен, чтобы «Привет» находилось по «привет».
        return Regex(if (query.matchCase) pattern else "(?u)$pattern", options)
    }
}
