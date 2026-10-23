package io.quietfiles.core.model.content

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Текст из PDF для поиска по содержимому (F6.6). Не полноценный разборщик: читает объекты и потоки
 * (в том числе упакованные в ObjStm), распаковывает FlateDecode, переводит коды шрифтов в Unicode
 * по таблицам ToUnicode и собирает строки операторов Tj/TJ. Для поиска этого достаточно; сканы
 * без текстового слоя и зашифрованные PDF дают пустую строку.
 */
object PdfText {

    private class PdfObject(val dict: String, val stream: ByteArray?)

    fun extract(bytes: ByteArray, maxChars: Int = TextExtractor.MAX_CHARS): String {
        if (!startsWith(bytes, "%PDF")) return ""
        val objects = readObjects(bytes)
        if (objects.values.any { it.dict.contains("/Encrypt") } || latin(bytes).contains("/Encrypt ")) return ""
        val out = StringBuilder()
        val pages = objects.entries.filter { (_, o) -> TYPE_PAGE.containsMatchIn(o.dict) }.sortedBy { it.key }
        val globalFonts = globalFontMap(objects)
        for ((_, page) in pages) {
            val fonts = pageFonts(page.dict, objects).ifEmpty { globalFonts }
            for (content in contentStreams(page.dict, objects)) {
                appendText(content, fonts, out, maxChars)
                if (out.length >= maxChars) return out.substring(0, maxChars)
            }
            out.append('\n')
        }
        return out.toString().trim()
    }

    // ---- Объекты ----

    private fun readObjects(bytes: ByteArray): Map<Int, PdfObject> {
        val text = latin(bytes)
        val result = HashMap<Int, PdfObject>()
        for (m in OBJ.findAll(text)) {
            val num = m.groupValues[1].toInt()
            val start = m.range.last + 1
            val end = text.indexOf("endobj", start).takeIf { it > 0 } ?: continue
            val body = text.substring(start, end)
            val streamAt = body.indexOf("stream")
            if (streamAt >= 0 && body.lastIndexOf("endstream") > streamAt) {
                val dict = body.substring(0, streamAt)
                var dataStart = start + streamAt + "stream".length
                if (dataStart < bytes.size && bytes[dataStart] == '\r'.code.toByte()) dataStart++
                if (dataStart < bytes.size && bytes[dataStart] == '\n'.code.toByte()) dataStart++
                val length = LENGTH.find(dict)?.groupValues?.get(1)?.toIntOrNull()
                val dataEnd = if (length != null && dataStart + length <= bytes.size) dataStart + length else start + body.lastIndexOf("endstream")
                val raw = bytes.copyOfRange(dataStart, dataEnd.coerceAtLeast(dataStart))
                result[num] = PdfObject(dict, decode(dict, raw))
            } else {
                result[num] = PdfObject(body, null)
            }
        }
        // Сжатые наборы объектов (PDF 1.5+): там обычно лежат словари шрифтов и страниц.
        for (o in result.values.toList()) {
            if (!o.dict.contains("/ObjStm") || o.stream == null) continue
            val n = NUM_N.find(o.dict)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val first = NUM_FIRST.find(o.dict)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val data = latin(o.stream)
            val header = data.substring(0, first.coerceAtMost(data.length)).trim().split(WS).mapNotNull { it.toIntOrNull() }
            for (i in 0 until minOf(n, header.size / 2)) {
                val num = header[i * 2]
                val off = first + header[i * 2 + 1]
                val next = if (i + 1 < n && (i + 1) * 2 + 1 < header.size) first + header[(i + 1) * 2 + 1] else data.length
                if (off < data.length && num !in result) result[num] = PdfObject(data.substring(off, next.coerceIn(off, data.length)), null)
            }
        }
        return result
    }

    private fun decode(dict: String, raw: ByteArray): ByteArray? = when {
        dict.contains("/FlateDecode") -> inflate(raw)
        dict.contains("/Filter") -> null // DCT, JBIG2 и прочие — картинки, текста в них нет.
        else -> raw
    }

    private fun inflate(raw: ByteArray): ByteArray? {
        val inflater = Inflater()
        return try {
            inflater.setInput(raw)
            val out = ByteArrayOutputStream(raw.size * 3)
            val buffer = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_STREAM) break
            }
            out.toByteArray()
        } catch (e: DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }

    // ---- Страницы, ресурсы, шрифты ----

    private fun resolve(value: String, objects: Map<Int, PdfObject>): String {
        val ref = REF.matchEntire(value.trim()) ?: return value
        return objects[ref.groupValues[1].toInt()]?.dict ?: ""
    }

    private fun contentStreams(pageDict: String, objects: Map<Int, PdfObject>): List<ByteArray> {
        val contents = CONTENTS.find(pageDict)?.groupValues?.get(1) ?: return emptyList()
        return REF_IN.findAll(contents).mapNotNull { objects[it.groupValues[1].toInt()]?.stream }.toList()
    }

    /** Шрифты страницы: имя ресурса (/F1) → таблица кодов. */
    private fun pageFonts(pageDict: String, objects: Map<Int, PdfObject>): Map<String, CMap> {
        val resources = dictValue(pageDict, "/Resources")?.let { resolve(it, objects) } ?: return emptyMap()
        val fontDict = dictValue(resources, "/Font")?.let { resolve(it, objects) } ?: return emptyMap()
        return fontsFrom(fontDict, objects)
    }

    private fun globalFontMap(objects: Map<Int, PdfObject>): Map<String, CMap> {
        val all = HashMap<String, CMap>()
        objects.values.forEach { o ->
            val fontDict = dictValue(o.dict, "/Font")?.let { resolve(it, objects) } ?: return@forEach
            fontsFrom(fontDict, objects).forEach { (k, v) -> all.putIfAbsent(k, v) }
        }
        return all
    }

    private fun fontsFrom(fontDict: String, objects: Map<Int, PdfObject>): Map<String, CMap> =
        FONT_ENTRY.findAll(fontDict).associate { m ->
            val font = objects[m.groupValues[2].toInt()]?.dict.orEmpty()
            val cmapRef = TO_UNICODE.find(font)?.groupValues?.get(1)?.toInt()
            val cmap = cmapRef?.let { objects[it]?.stream }?.let { CMap.parse(latin(it)) }
            m.groupValues[1] to (cmap ?: CMap.EMPTY)
        }

    /** Значение ключа словаря: вложенный словарь целиком или ссылка «12 0 R». */
    private fun dictValue(dict: String, key: String): String? {
        val at = dict.indexOf(key)
        if (at < 0) return null
        var i = at + key.length
        while (i < dict.length && dict[i].isWhitespace()) i++
        if (dict.startsWith("<<", i)) {
            var depth = 0
            var j = i
            while (j < dict.length - 1) {
                if (dict.startsWith("<<", j)) {
                    depth++
                    j += 2
                } else if (dict.startsWith(">>", j)) {
                    depth--
                    j += 2
                    if (depth == 0) return dict.substring(i, j)
                } else {
                    j++
                }
            }
            return null
        }
        return REF_AT.find(dict, i)?.takeIf { it.range.first == i }?.value
    }

    // ---- Текст на странице ----

    private fun appendText(content: ByteArray, fonts: Map<String, CMap>, out: StringBuilder, maxChars: Int) {
        val s = latin(content)
        var font: CMap = CMap.EMPTY
        var i = 0
        var lastName: String? = null
        val pending = ArrayList<String>()
        while (i < s.length && out.length < maxChars) {
            val c = s[i]
            when {
                c == '(' -> {
                    val (bytes, next) = literal(s, i)
                    pending += font.decode(bytes)
                    i = next
                }
                c == '<' && i + 1 < s.length && s[i + 1] != '<' -> {
                    val end = s.indexOf('>', i).takeIf { it > 0 } ?: s.length
                    pending += font.decode(hex(s.substring(i + 1, end)))
                    i = end + 1
                }
                c == '/' -> {
                    val end = nameEnd(s, i + 1)
                    lastName = s.substring(i + 1, end)
                    i = end
                }
                c == '[' || c == ']' -> i++
                c == '-' || c == '.' || c.isDigit() -> {
                    val end = numberEnd(s, i)
                    // В TJ большой отрицательный сдвиг — это пробел между словами.
                    if ((s.substring(i, end).toDoubleOrNull() ?: 0.0) < -WORD_GAP && pending.isNotEmpty()) pending += " "
                    i = end
                }
                c.isLetter() || c == '\'' || c == '"' || c == '*' -> {
                    val end = operatorEnd(s, i)
                    when (s.substring(i, end)) {
                        "Tf" -> font = lastName?.let { fonts[it] } ?: CMap.EMPTY
                        "Tj", "TJ", "'", "\"" -> {
                            pending.forEach(out::append)
                            pending.clear()
                        }
                        "T*", "Td", "TD", "ET" -> if (out.isNotEmpty() && out.last() != '\n' && out.last() != ' ') out.append(' ')
                        "BT" -> pending.clear()
                    }
                    i = end
                }
                else -> i++
            }
        }
    }

    private fun literal(s: String, start: Int): Pair<ByteArray, Int> {
        val out = ByteArrayOutputStream()
        var depth = 0
        var i = start
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length -> {
                    val n = s[i + 1]
                    when (n) {
                        'n' -> out.write('\n'.code)
                        'r' -> out.write('\r'.code)
                        't' -> out.write('\t'.code)
                        'b', 'f' -> Unit
                        in '0'..'7' -> {
                            var j = i + 1
                            var v = 0
                            while (j < s.length && j < i + 4 && s[j] in '0'..'7') v = v * 8 + (s[j++] - '0')
                            out.write(v and 0xFF)
                            i = j
                            continue
                        }
                        '\n', '\r' -> Unit
                        else -> out.write(n.code and 0xFF)
                    }
                    i += 2
                    continue
                }
                c == '(' -> {
                    depth++
                    if (depth > 1) out.write(c.code)
                }
                c == ')' -> {
                    depth--
                    if (depth == 0) return out.toByteArray() to i + 1
                    out.write(c.code)
                }
                else -> out.write(c.code and 0xFF)
            }
            i++
        }
        return out.toByteArray() to i
    }

    internal fun hex(s: String): ByteArray {
        val digits = s.filter { it.isLetterOrDigit() }.let { if (it.length % 2 == 1) it + "0" else it }
        return ByteArray(digits.length / 2) { digits.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun nameEnd(s: String, from: Int): Int {
        var i = from
        while (i < s.length && !s[i].isWhitespace() && s[i] !in "/[]()<>{}%") i++
        return i
    }

    private fun numberEnd(s: String, from: Int): Int {
        var i = from + 1
        while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
        return i
    }

    private fun operatorEnd(s: String, from: Int): Int {
        var i = from + 1
        while (i < s.length && (s[i].isLetter() || s[i] == '*')) i++
        return i
    }

    private fun latin(bytes: ByteArray) = String(bytes, Charsets.ISO_8859_1)

    private fun startsWith(bytes: ByteArray, prefix: String) =
        bytes.size >= prefix.length && prefix.indices.all { bytes[it] == prefix[it].code.toByte() }

    private val OBJ = Regex("""(\d+)\s+(\d+)\s+obj\b""")
    private val LENGTH = Regex("""/Length\s+(\d+)(?!\s+\d+\s+R)""")
    private val NUM_N = Regex("""/N\s+(\d+)""")
    private val NUM_FIRST = Regex("""/First\s+(\d+)""")
    private val TYPE_PAGE = Regex("""/Type\s*/Page(?![s\w])""")
    private val CONTENTS = Regex("""/Contents\s*(\[[^\]]*]|\d+\s+\d+\s+R)""")
    private val REF = Regex("""(\d+)\s+(\d+)\s+R""")
    private val REF_IN = Regex("""(\d+)\s+\d+\s+R""")
    private val REF_AT = Regex("""\d+\s+\d+\s+R""")
    private val FONT_ENTRY = Regex("""/([^\s/<>\[\]()]+)\s+(\d+)\s+\d+\s+R""")
    private val TO_UNICODE = Regex("""/ToUnicode\s+(\d+)\s+\d+\s+R""")
    private val WS = Regex("""\s+""")
    private const val WORD_GAP = 180.0
    private const val MAX_STREAM = 16 * 1024 * 1024
}

/** Таблица ToUnicode: коды глифов шрифта (1 или 2 байта) → текст. */
class CMap private constructor(private val map: Map<Int, String>, private val twoBytes: Boolean) {

    fun decode(bytes: ByteArray): String {
        if (map.isEmpty()) return winAnsi(bytes)
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            if (twoBytes && i + 1 < bytes.size) {
                val code = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
                sb.append(map[code] ?: "")
                i += 2
            } else {
                sb.append(map[bytes[i].toInt() and 0xFF] ?: winAnsi(byteArrayOf(bytes[i])))
                i++
            }
        }
        return sb.toString()
    }

    companion object {
        val EMPTY = CMap(emptyMap(), false)

        fun parse(text: String): CMap {
            val map = HashMap<Int, String>()
            var twoBytes = Regex("""<([0-9A-Fa-f]+)>\s*<[0-9A-Fa-f]+>""").find(
                text.substringAfter("begincodespacerange", "").substringBefore("endcodespacerange"),
            )?.groupValues?.get(1)?.length == 4
            for (block in Regex("""beginbfchar(.*?)endbfchar""", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
                Regex("""<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]*)>""").findAll(block.groupValues[1]).forEach { m ->
                    if (m.groupValues[1].length >= 4) twoBytes = true
                    map[m.groupValues[1].toInt(16)] = utf16(m.groupValues[2])
                }
            }
            for (block in Regex("""beginbfrange(.*?)endbfrange""", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
                val body = block.groupValues[1]
                Regex("""<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>\s*(<[0-9A-Fa-f]*>|\[[^\]]*])""").findAll(body).forEach { m ->
                    val lo = m.groupValues[1].toInt(16)
                    val hi = m.groupValues[2].toInt(16).coerceAtMost(lo + 65_535)
                    if (m.groupValues[1].length >= 4) twoBytes = true
                    val dst = m.groupValues[3]
                    if (dst.startsWith("[")) {
                        Regex("""<([0-9A-Fa-f]*)>""").findAll(dst).forEachIndexed { k, d -> if (lo + k <= hi) map[lo + k] = utf16(d.groupValues[1]) }
                    } else {
                        val base = dst.trim('<', '>')
                        val start = if (base.isEmpty()) 0 else base.takeLast(4).toInt(16)
                        val prefix = utf16(base.dropLast(4))
                        for (code in lo..hi) map[code] = prefix + String(Character.toChars(start + (code - lo)))
                    }
                }
            }
            return CMap(map, twoBytes)
        }

        private fun utf16(hex: String): String {
            if (hex.isEmpty()) return ""
            val bytes = PdfText.hex(hex)
            return String(bytes, Charsets.UTF_16BE)
        }

        /** Без ToUnicode: однобайтовые коды почти всегда WinAnsi (Latin-1 с типографскими знаками). */
        private fun winAnsi(bytes: ByteArray): String = String(bytes, charset("windows-1252"))
    }
}
