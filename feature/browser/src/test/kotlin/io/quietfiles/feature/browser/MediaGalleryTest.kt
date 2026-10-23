package io.quietfiles.feature.browser

import com.google.common.truth.Truth.assertThat
import io.quietfiles.core.model.FileEntry
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

class MediaGalleryTest {

    @Test
    fun sourceByPath() {
        assertThat(mediaSourceOf("/storage/emulated/0/DCIM/Camera/IMG_1.jpg")).isEqualTo(MediaSource.CAMERA)
        assertThat(mediaSourceOf("/storage/emulated/0/DCIM/Screenshots/s.png")).isEqualTo(MediaSource.SCREENSHOTS)
        assertThat(mediaSourceOf("/storage/emulated/0/Pictures/Screenshots/s.png")).isEqualTo(MediaSource.SCREENSHOTS)
        assertThat(mediaSourceOf("/storage/emulated/0/Pictures/Telegram/t.jpg")).isEqualTo(MediaSource.MESSENGERS)
        assertThat(mediaSourceOf("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/w.jpg"))
            .isEqualTo(MediaSource.MESSENGERS)
        assertThat(mediaSourceOf("/storage/emulated/0/Download/d.jpg")).isEqualTo(MediaSource.DOWNLOADS)
        assertThat(mediaSourceOf("/storage/emulated/0/Pictures/Отпуск/o.jpg")).isEqualTo(MediaSource.ALL)
    }

    @Test
    fun groupsByTodayYesterdayAndMonth() {
        val zone = ZoneOffset.UTC
        val today = LocalDate.of(2026, 10, 23)
        fun item(name: String, date: LocalDate) =
            BrowserItem(FileEntry("/p/$name", name, false, 1, date.atStartOfDay(zone).toInstant().toEpochMilli() + 3_600_000))

        val groups = groupByDate(
            listOf(
                item("old.jpg", LocalDate.of(2026, 9, 2)),
                item("now.jpg", today),
                item("yday.jpg", today.minusDays(1)),
                item("early.jpg", LocalDate.of(2026, 10, 3)),
            ),
            today,
            zone,
        )

        assertThat(groups.map { it.first }).containsExactly(
            DateBucket.Today,
            DateBucket.Yesterday,
            DateBucket.Month(YearMonth.of(2026, 10)),
            DateBucket.Month(YearMonth.of(2026, 9)),
        ).inOrder()
    }
}
