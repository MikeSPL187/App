package io.quietfiles.feature.editor.text

import java.nio.charset.Charset

/** Кодировка, которую можно выбрать в редакторе (F12.4). */
enum class TextEncoding(val charsetName: String, val label: String, val bom: ByteArray = ByteArray(0)) {
    UTF_8("UTF-8", "UTF-8", byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())),
    UTF_16LE("UTF-16LE", "UTF-16 LE", byteArrayOf(0xFF.toByte(), 0xFE.toByte())),
    UTF_16BE("UTF-16BE", "UTF-16 BE", byteArrayOf(0xFE.toByte(), 0xFF.toByte())),
    WINDOWS_1251("windows-1251", "Windows-1251"),
    KOI8_R("KOI8-R", "KOI8-R"),
    CP866("IBM866", "DOS 866"),
    WINDOWS_1252("windows-1252", "Windows-1252"),
    ;

    val charset: Charset get() = Charset.forName(charsetName)

    /** Однобайтовые и UTF-8 файлы можно резать на строки по байту `\n` — это нужно для постраничного чтения. */
    val isByteLineSplittable: Boolean get() = this != UTF_16LE && this != UTF_16BE
}

/** Результат определения: кодировка и был ли в начале файла маркер BOM. */
data class Detection(val encoding: TextEncoding, val hasBom: Boolean)

object Encodings {

    /** Сколько байт смотреть при определении: начала файла достаточно, а большие файлы так не читаются целиком. */
    const val SAMPLE_BYTES = 256 * 1024

    fun detect(bytes: ByteArray, length: Int = bytes.size): Detection {
        bomOf(bytes, length)?.let { return Detection(it, hasBom = true) }
        val sample = minOf(length, SAMPLE_BYTES)
        utf16WithoutBom(bytes, sample)?.let { return Detection(it, hasBom = false) }
        if (isValidUtf8(bytes, sample, allowTruncatedEnd = sample < length)) return Detection(TextEncoding.UTF_8, hasBom = false)
        val best = SINGLE_BYTE.maxBy { quality(String(bytes, 0, sample, it.charset)) }
        return Detection(best, hasBom = false)
    }

    fun bomOf(bytes: ByteArray, length: Int = bytes.size): TextEncoding? =
        listOf(TextEncoding.UTF_8, TextEncoding.UTF_16LE, TextEncoding.UTF_16BE).firstOrNull { enc ->
            enc.bom.size <= length && enc.bom.indices.all { bytes[it] == enc.bom[it] }
        }

    /** Текст без маркера BOM. */
    fun decode(bytes: ByteArray, length: Int, encoding: TextEncoding): String {
        val skip = if (bomOf(bytes, length) == encoding) encoding.bom.size else 0
        return String(bytes, skip, length - skip, encoding.charset)
    }

    fun encode(text: String, encoding: TextEncoding, withBom: Boolean): ByteArray {
        val body = text.toByteArray(encoding.charset)
        return if (withBom && encoding.bom.isNotEmpty()) encoding.bom + body else body
    }

    /** Можно ли сохранить текст в кодировке без потерь (например, эмодзи не помещаются в Windows-1251). */
    fun canEncode(text: String, encoding: TextEncoding): Boolean = encoding.charset.newEncoder().canEncode(text)

    /**
     * Подсказка «Текст выглядит искажённым» (макет 25): другая кодировка, если в ней текст заметно
     * правдоподобнее, чем в выбранной. `null` — с текущей всё в порядке.
     */
    fun suggest(bytes: ByteArray, length: Int, current: TextEncoding): TextEncoding? {
        val sample = minOf(length, SAMPLE_BYTES)
        val currentQuality = quality(decode(bytes, sample, current))
        val candidates = TextEncoding.entries.filter { it != current }
        val best = candidates.maxBy { quality(decode(bytes, sample, it)) }
        val bestQuality = quality(decode(bytes, sample, best))
        return best.takeIf { bestQuality > currentQuality + maxOf(8.0, sample / 400.0) && looksGarbled(decode(bytes, sample, current)) }
    }

    /** Признаки «кракозябр»: символы замены, управляющие символы, заглавные буквы посреди слов. */
    fun looksGarbled(text: String): Boolean {
        if (text.isEmpty()) return false
        var bad = 0
        var letters = 0
        text.forEachIndexed { i, c ->
            if (c.isLetter()) letters++
            if (c == '�' || (c.isISOControl() && c !in "\t\n\r\u000C")) bad += 3
            if (c in CYRILLIC_UPPER && i > 0 && text[i - 1] in CYRILLIC_LOWER) bad++
            if (c in NON_RUSSIAN_CYRILLIC) bad++
        }
        return bad > maxOf(3, letters / 50)
    }

    /** Насколько текст похож на обычный: русские буквы в нормальном регистре — плюс, мусор — минус. */
    internal fun quality(text: String): Double {
        var score = 0.0
        text.forEachIndexed { i, c ->
            when {
                c == '�' -> score -= 5
                c.isISOControl() && c !in "\t\n\r\u000C" -> score -= 3
                c in COMMON_RUSSIAN -> score += 1.0
                c in CYRILLIC_LOWER -> score += 0.5
                c in CYRILLIC_UPPER -> score += if (i > 0 && text[i - 1] in CYRILLIC_LOWER) -2.0 else 0.3
                c in NON_RUSSIAN_CYRILLIC -> score -= 1
                c in BOX_DRAWING -> score -= 1
            }
        }
        return score
    }

    /** Строгая проверка UTF-8. [allowTruncatedEnd] — образец обрезан, последний символ может быть неполным. */
    fun isValidUtf8(bytes: ByteArray, length: Int = bytes.size, allowTruncatedEnd: Boolean = false): Boolean {
        var i = 0
        while (i < length) {
            val b = bytes[i].toInt() and 0xFF
            val extra = when {
                b < 0x80 -> 0
                b in 0xC2..0xDF -> 1
                b in 0xE0..0xEF -> 2
                b in 0xF0..0xF4 -> 3
                else -> return false
            }
            if (i + extra >= length) return allowTruncatedEnd && extra > 0 && (i + 1 until length).all { isContinuation(bytes[it]) }
            for (k in 1..extra) if (!isContinuation(bytes[i + k])) return false
            // Отсекаем «длинные» формы и суррогаты.
            if (b == 0xE0 && (bytes[i + 1].toInt() and 0xFF) < 0xA0) return false
            if (b == 0xED && (bytes[i + 1].toInt() and 0xFF) >= 0xA0) return false
            if (b == 0xF0 && (bytes[i + 1].toInt() and 0xFF) < 0x90) return false
            if (b == 0xF4 && (bytes[i + 1].toInt() and 0xFF) >= 0x90) return false
            i += extra + 1
        }
        return true
    }

    private fun isContinuation(b: Byte) = (b.toInt() and 0xC0) == 0x80

    /** UTF-16 без BOM узнаётся по нулевым байтам через один в латинице и цифрах. */
    private fun utf16WithoutBom(bytes: ByteArray, length: Int): TextEncoding? {
        if (length < 8) return null
        var evenZero = 0
        var oddZero = 0
        val pairs = length / 2
        for (p in 0 until pairs) {
            if (bytes[2 * p].toInt() == 0) evenZero++
            if (bytes[2 * p + 1].toInt() == 0) oddZero++
        }
        return when {
            oddZero > pairs * 0.3 && evenZero < pairs * 0.05 -> TextEncoding.UTF_16LE
            evenZero > pairs * 0.3 && oddZero < pairs * 0.05 -> TextEncoding.UTF_16BE
            else -> null
        }
    }

    private val SINGLE_BYTE = listOf(TextEncoding.WINDOWS_1251, TextEncoding.KOI8_R, TextEncoding.CP866, TextEncoding.WINDOWS_1252)
    private val CYRILLIC_UPPER = ('А'..'Я').toSet() + 'Ё'
    private val CYRILLIC_LOWER = ('а'..'я').toSet() + 'ё'
    private val COMMON_RUSSIAN = "оеаинтсрвлкмдпуяызьгбчйхжшюцщэфъё".toSet()
    private val NON_RUSSIAN_CYRILLIC = ('Ѐ'..'Џ').toSet() + ('ѐ'..'џ').toSet() - 'Ё' - 'ё'
    private val BOX_DRAWING = ('─'..'▟').toSet()
}
