package io.quietfiles.core.common

import android.content.Context
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Размер в двоичных единицах (1 КБ = 1024 Б), как в системных настройках памяти.
 * До 100 единиц — с одним знаком после запятой («2,4 МБ»), дальше — целые («156 МБ»).
 */
class SizeFormatter(private val units: List<String>, private val locale: Locale) {

    fun format(bytes: Long): String {
        if (bytes < 0) return "—"
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        val pattern = if (unit == 0 || value >= 100) "0" else "0.#"
        val number = DecimalFormat(pattern, DecimalFormatSymbols.getInstance(locale)).format(value)
        return "$number ${units[unit]}"
    }

    companion object {
        fun from(context: Context): SizeFormatter = SizeFormatter(
            units = listOf(
                context.getString(R.string.unit_byte),
                context.getString(R.string.unit_kb),
                context.getString(R.string.unit_mb),
                context.getString(R.string.unit_gb),
                context.getString(R.string.unit_tb),
            ),
            locale = context.resources.configuration.locales[0],
        )
    }
}

/**
 * Даты в списках: «сегодня, 09:12», «вчера, 18:40», «21 окт.», «3 мар. 2025».
 */
class DateFormatter(
    private val today: String,
    private val yesterday: String,
    private val locale: Locale,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Instant = Instant::now,
) {
    private val time = DateTimeFormatter.ofPattern("HH:mm", locale)
    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", locale)
    private val dayMonthYear = DateTimeFormatter.ofPattern("d MMM yyyy", locale)
    private val full = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", locale)

    fun short(epochMillis: Long): String {
        val dateTime = Instant.ofEpochMilli(epochMillis).atZone(zone)
        val date = dateTime.toLocalDate()
        val now = clock().atZone(zone).toLocalDate()
        return when {
            date == now -> "$today, ${time.format(dateTime)}"
            date == now.minusDays(1) -> "$yesterday, ${time.format(dateTime)}"
            date.year == now.year -> dayMonth.format(dateTime)
            else -> dayMonthYear.format(dateTime)
        }
    }

    fun long(epochMillis: Long): String = full.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    companion object {
        fun from(context: Context): DateFormatter = DateFormatter(
            today = context.getString(R.string.date_today),
            yesterday = context.getString(R.string.date_yesterday),
            locale = context.resources.configuration.locales[0],
        )
    }
}

/** Оставшееся время операции: «≈ 2 с», «≈ 3 мин», «≈ 1 ч 5 мин». */
fun Context.formatDuration(seconds: Long): String = when {
    seconds < 60 -> getString(R.string.duration_seconds, seconds.coerceAtLeast(1))
    seconds < 3600 -> getString(R.string.duration_minutes, (seconds + 30) / 60)
    else -> getString(R.string.duration_hours_minutes, seconds / 3600, (seconds % 3600) / 60)
}
