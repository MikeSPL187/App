package io.quietfiles.feature.editor.text

/** Окончания строк (F12.5). В редакторе всегда `\n`, при сохранении возвращаются исходные. */
enum class LineEnding(val chars: String, val label: String) {
    LF("\n", "LF"),
    CRLF("\r\n", "CRLF"),
    CR("\r", "CR"),
    ;

    companion object {
        /** Преобладающие окончания; файл без переводов строк считается LF. */
        fun detect(text: CharSequence): LineEnding {
            var crlf = 0
            var lf = 0
            var cr = 0
            var i = 0
            while (i < text.length) {
                when (text[i]) {
                    '\r' -> if (i + 1 < text.length && text[i + 1] == '\n') {
                        crlf++
                        i++
                    } else cr++
                    '\n' -> lf++
                }
                i++
            }
            return when {
                crlf >= lf && crlf >= cr && crlf > 0 -> CRLF
                cr > lf -> CR
                else -> LF
            }
        }

        fun normalize(text: String): String =
            if (text.indexOf('\r') < 0) text else text.replace("\r\n", "\n").replace('\r', '\n')

        fun apply(text: String, ending: LineEnding): String =
            if (ending == LF) text else text.replace("\n", ending.chars)
    }
}
