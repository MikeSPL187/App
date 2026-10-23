package io.quietfiles.core.model.content

import io.quietfiles.core.model.FileNames
import io.quietfiles.core.model.text.Encodings
import java.io.File
import java.util.zip.ZipFile

/** Какие файлы индексируются для поиска по содержимому (F6.6) и как из них достаётся текст. */
object TextExtractor {

    /** Сколько текста одного файла попадает в индекс: этого хватает, чтобы найти документ. */
    const val MAX_CHARS = 200_000

    private val TEXT = setOf(
        "txt", "md", "markdown", "log", "csv", "tsv", "json", "xml", "yaml", "yml", "ini", "conf", "cfg", "properties",
        "toml", "html", "htm", "css", "js", "ts", "kt", "kts", "java", "py", "sh", "c", "cpp", "h", "sql", "srt", "tex", "rtf",
    )

    const val MAX_TEXT_BYTES = 5L * 1024 * 1024
    const val MAX_DOCUMENT_BYTES = 30L * 1024 * 1024

    fun isIndexable(name: String, size: Long): Boolean {
        val ext = FileNames.extension(name).lowercase()
        return when {
            ext in TEXT -> size <= MAX_TEXT_BYTES
            ext == "pdf" || ext == "docx" -> size <= MAX_DOCUMENT_BYTES
            else -> false
        }
    }

    /** Текст файла или null, если формат не поддержан или файл не читается. */
    fun extract(file: File): String? = runCatching {
        when (FileNames.extension(file.name).lowercase()) {
            "pdf" -> PdfText.extract(file.readBytes())
            "docx" -> docx(file)
            in TEXT -> plain(file)
            else -> null
        }
    }.getOrNull()

    /** Кодировку определяем так же, как редактор: UTF-8, UTF-16, Windows-1251, KOI8-R… */
    fun plain(file: File): String {
        val length = minOf(file.length(), MAX_TEXT_BYTES).toInt()
        val bytes = ByteArray(length)
        file.inputStream().use { input ->
            var read = 0
            while (read < length) {
                val n = input.read(bytes, read, length - read)
                if (n < 0) break
                read += n
            }
        }
        val detection = Encodings.detect(bytes, length)
        return Encodings.decode(bytes, length, detection.encoding).take(MAX_CHARS)
    }

    /** DOCX — zip с word/document.xml: текст в <w:t>, абзацы — <w:p>. */
    fun docx(file: File): String = ZipFile(file).use { zip ->
        val entry = zip.getEntry("word/document.xml") ?: return ""
        val xml = zip.getInputStream(entry).use { String(it.readBytes(), Charsets.UTF_8) }
        docxXml(xml)
    }

    internal fun docxXml(xml: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < xml.length && sb.length < MAX_CHARS) {
            val lt = xml.indexOf('<', i)
            if (lt < 0) break
            val gt = xml.indexOf('>', lt).takeIf { it > 0 } ?: break
            val tag = xml.substring(lt + 1, gt)
            when {
                tag.startsWith("w:t") && (tag.length == 3 || tag[3] == ' ') && !tag.endsWith("/") -> {
                    val close = xml.indexOf("</w:t>", gt).takeIf { it > 0 } ?: break
                    sb.append(unescape(xml.substring(gt + 1, close)))
                    i = close + 6
                    continue
                }
                tag == "/w:p" -> sb.append('\n')
                tag.startsWith("w:tab") || tag.startsWith("w:br") -> sb.append(' ')
            }
            i = gt + 1
        }
        return sb.toString().trim()
    }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** Для поиска «ё» и «е» — одно и то же (F6.1), регистр сравнивает сам индекс. */
    fun normalize(text: String): String = text.replace('ё', 'е').replace('Ё', 'Е')
}
