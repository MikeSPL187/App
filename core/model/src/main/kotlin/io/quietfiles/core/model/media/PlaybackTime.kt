package io.quietfiles.core.model.media

import java.util.Locale

/** Время на шкале плеера: «0:32», «12:41», «1:02:03». */
object PlaybackTime {

    fun format(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%d:%02d".format(Locale.ROOT, m, s)
    }

    /** Скорость для кнопок: «1×», «0,75×», «1,25×» — с десятичным разделителем языка. */
    fun speedLabel(speed: Float, locale: Locale): String {
        val text = if (speed == speed.toInt().toFloat()) {
            speed.toInt().toString()
        } else {
            val raw = "%.2f".format(Locale.ROOT, speed).trimEnd('0')
            raw.replace('.', java.text.DecimalFormatSymbols.getInstance(locale).decimalSeparator)
        }
        return "$text×"
    }

    /** Скорости F9.4. */
    val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
}

/**
 * Когда продолжать с сохранённого места (F9.5). Первые секунды и самый конец не запоминаем:
 * видео, досмотренное до титров, при следующем открытии начинается сначала.
 */
object ResumePolicy {
    const val MIN_POSITION_MS = 5_000L
    const val END_MARGIN_MS = 15_000L
    const val END_FRACTION = 0.97

    /** Сколько файлов помнить (F9.5). */
    const val KEEP_FILES = 200

    /** Музыку продолжаем только для длинных записей — подкастов и аудиокниг. */
    const val MIN_AUDIO_DURATION_MS = 10 * 60_000L

    fun shouldRemember(positionMs: Long, durationMs: Long): Boolean {
        if (positionMs < MIN_POSITION_MS) return false
        if (durationMs <= 0) return true
        return positionMs < durationMs - END_MARGIN_MS && positionMs < durationMs * END_FRACTION
    }

    fun shouldRememberAudio(positionMs: Long, durationMs: Long): Boolean =
        durationMs >= MIN_AUDIO_DURATION_MS && shouldRemember(positionMs, durationMs)
}
