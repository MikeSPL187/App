package io.quietfiles.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

class FormattersTest {

    private val ru = Locale.forLanguageTag("ru")
    private val sizes = SizeFormatter(listOf("Б", "КБ", "МБ", "ГБ", "ТБ"), ru)

    @Test
    fun sizes() {
        assertThat(sizes.format(0)).isEqualTo("0 Б")
        assertThat(sizes.format(812 * 1024L)).isEqualTo("812 КБ")
        assertThat(sizes.format((2.4 * 1024 * 1024).toLong())).isEqualTo("2,4 МБ")
        assertThat(sizes.format(156L * 1024 * 1024)).isEqualTo("156 МБ")
        assertThat(sizes.format(2L * 1024 * 1024 * 1024)).isEqualTo("2 ГБ")
        assertThat(sizes.format(-1)).isEqualTo("—")
    }

    @Test
    fun dates() {
        val zone: ZoneId = ZoneOffset.UTC
        val now = LocalDateTime.of(2026, 10, 22, 21, 0).toInstant(ZoneOffset.UTC)
        val f = DateFormatter("сегодня", "вчера", ru, zone) { now }
        fun at(y: Int, m: Int, d: Int, h: Int, min: Int) =
            LocalDateTime.of(y, m, d, h, min).toInstant(ZoneOffset.UTC).toEpochMilli()

        assertThat(f.short(at(2026, 10, 22, 9, 12))).isEqualTo("сегодня, 09:12")
        assertThat(f.short(at(2026, 10, 21, 18, 40))).isEqualTo("вчера, 18:40")
        assertThat(f.short(at(2026, 10, 3, 10, 0))).isEqualTo("3 окт.")
        assertThat(f.short(at(2025, 3, 3, 10, 0))).isEqualTo("3 мар. 2025")
        assertThat(Instant.ofEpochMilli(at(2026, 1, 1, 0, 0))).isNotNull()
    }
}
