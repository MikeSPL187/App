package io.quietfiles.core.model.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

class MediaLogicTest {

    @Test
    fun formatsPlaybackTime() {
        assertThat(PlaybackTime.format(0)).isEqualTo("0:00")
        assertThat(PlaybackTime.format(32_400)).isEqualTo("0:32")
        assertThat(PlaybackTime.format(761_000)).isEqualTo("12:41")
        assertThat(PlaybackTime.format(3_723_000)).isEqualTo("1:02:03")
        assertThat(PlaybackTime.format(-5)).isEqualTo("0:00")
    }

    @Test
    fun speedLabelsUseLocaleSeparator() {
        val ru = Locale.forLanguageTag("ru")
        assertThat(PlaybackTime.speedLabel(1f, ru)).isEqualTo("1×")
        assertThat(PlaybackTime.speedLabel(1.25f, ru)).isEqualTo("1,25×")
        assertThat(PlaybackTime.speedLabel(0.5f, ru)).isEqualTo("0,5×")
        assertThat(PlaybackTime.speedLabel(0.75f, Locale.US)).isEqualTo("0.75×")
    }

    @Test
    fun resumeSkipsStartAndEnd() {
        val hour = 3_600_000L
        assertThat(ResumePolicy.shouldRemember(3_000, hour)).isFalse()
        assertThat(ResumePolicy.shouldRemember(761_000, hour)).isTrue()
        assertThat(ResumePolicy.shouldRemember(hour - 10_000, hour)).isFalse()
        // 97% от короткого ролика — уже титры.
        assertThat(ResumePolicy.shouldRemember(58_500, 60_000)).isFalse()
        assertThat(ResumePolicy.shouldRemember(30_000, 0)).isTrue()
        assertThat(ResumePolicy.shouldRememberAudio(120_000, 240_000)).isFalse()
        assertThat(ResumePolicy.shouldRememberAudio(120_000, hour)).isTrue()
    }

    @Test
    fun codecAndResolutionLabels() {
        assertThat(MediaLabels.videoCodec("video/avc")).isEqualTo("H.264")
        assertThat(MediaLabels.videoCodec("video/HEVC")).isEqualTo("H.265")
        assertThat(MediaLabels.videoCodec("video/unknown")).isNull()
        assertThat(MediaLabels.audioCodec("audio/mp4a-latm")).isEqualTo("AAC")
        assertThat(MediaLabels.resolution(1920, 1080)).isEqualTo("1080p")
        assertThat(MediaLabels.resolution(1080, 1920)).isEqualTo("1080p")
        assertThat(MediaLabels.resolution(3840, 2160)).isEqualTo("4K")
        assertThat(MediaLabels.resolution(1280, 720)).isEqualTo("720p")
        assertThat(MediaLabels.resolution(320, 240)).isEqualTo("240p")
        assertThat(MediaLabels.resolution(0, 0)).isNull()
        assertThat(MediaLabels.channels(6)).isEqualTo("5.1")
        assertThat(MediaLabels.channels(1)).isNull()
    }

    @Test
    fun audioQualityLine() {
        val info = AudioStreamInfo(96_000, 24, 2)
        assertThat(MediaLabels.audioQuality("FLAC", info, "бит", "кГц")).isEqualTo("FLAC · 24 бит / 96 кГц")
        assertThat(MediaLabels.audioQuality("MP3", AudioStreamInfo(44_100, 0, 2), "bit", "kHz")).isEqualTo("MP3 · 44,1 kHz")
        assertThat(MediaLabels.audioQuality("OGG", null, "bit", "kHz")).isEqualTo("OGG")
    }

    @Test
    fun findsSidecarSubtitles() {
        val dir = "/storage/emulated/0/Movies"
        val siblings = listOf(
            "$dir/Отпуск_2026.mp4", "$dir/Отпуск_2026.srt", "$dir/отпуск_2026.EN.srt", "$dir/Отпуск_2026.rus.ass",
            "$dir/Отпуск_2026_02.srt", "$dir/Отпуск_2026.txt", "$dir/Другое.srt", "$dir/Отпуск_2026.forced.srt",
        )
        val found = SubtitleFinder.find("$dir/Отпуск_2026.mp4", siblings)
        assertThat(found.map { it.path.substringAfterLast('/') })
            .containsExactly("Отпуск_2026.srt", "Отпуск_2026.forced.srt", "отпуск_2026.EN.srt", "Отпуск_2026.rus.ass")
            .inOrder()
        assertThat(found.map { it.language }).containsExactly(null, null, "en", "rus").inOrder()
        assertThat(found.last().mimeType).isEqualTo("text/x-ssa")
    }

    @Test
    fun gestures() {
        assertThat(PlayerGestures.seekDelta(500f, 1000f, 3_600_000)).isEqualTo(45_000)
        // Короткий ролик: вся ширина — весь ролик.
        assertThat(PlayerGestures.seekDelta(-1000f, 1000f, 30_000)).isEqualTo(-30_000)
        assertThat(PlayerGestures.level(0.6f, 100f, 400f)).isWithin(0.001f).of(0.85f)
        assertThat(PlayerGestures.level(0.9f, 400f, 400f)).isEqualTo(1f)
        assertThat(PlayerGestures.level(0.1f, -400f, 400f)).isEqualTo(0f)
        assertThat(PlayerGestures.zoneOf(10f, 900f)).isEqualTo(-1)
        assertThat(PlayerGestures.zoneOf(450f, 900f)).isEqualTo(0)
        assertThat(PlayerGestures.zoneOf(800f, 900f)).isEqualTo(1)
    }
}
