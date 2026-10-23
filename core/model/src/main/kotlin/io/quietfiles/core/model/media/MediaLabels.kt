package io.quietfiles.core.model.media

import io.quietfiles.core.model.FileNames

/** Подписи кодеков и разрешений для строки «1080p · H.264 · 2,1 ГБ». */
object MediaLabels {

    private val VIDEO = mapOf(
        "video/avc" to "H.264",
        "video/hevc" to "H.265",
        "video/dolby-vision" to "Dolby Vision",
        "video/av01" to "AV1",
        "video/x-vnd.on2.vp9" to "VP9",
        "video/x-vnd.on2.vp8" to "VP8",
        "video/mp4v-es" to "MPEG-4",
        "video/mpeg2" to "MPEG-2",
        "video/mpeg" to "MPEG-1",
        "video/3gpp" to "H.263",
        "video/divx" to "DivX",
        "video/x-msvideo" to "AVI",
    )

    private val AUDIO = mapOf(
        "audio/mp4a-latm" to "AAC",
        "audio/mpeg" to "MP3",
        "audio/mpeg-l1" to "MP1",
        "audio/mpeg-l2" to "MP2",
        "audio/ac3" to "AC-3",
        "audio/eac3" to "E-AC-3",
        "audio/eac3-joc" to "Dolby Atmos",
        "audio/ac4" to "AC-4",
        "audio/true-hd" to "TrueHD",
        "audio/vnd.dts" to "DTS",
        "audio/vnd.dts.hd" to "DTS-HD",
        "audio/opus" to "Opus",
        "audio/vorbis" to "Vorbis",
        "audio/flac" to "FLAC",
        "audio/alac" to "ALAC",
        "audio/raw" to "PCM",
        "audio/3gpp" to "AMR",
        "audio/amr-wb" to "AMR-WB",
    )

    fun videoCodec(mimeType: String?): String? = mimeType?.lowercase()?.let { VIDEO[it] }

    fun audioCodec(mimeType: String?): String? = mimeType?.lowercase()?.let { AUDIO[it] }

    /** «4K», «1080p», «720p»: по короткой стороне, чтобы вертикальное видео называлось так же. */
    fun resolution(width: Int, height: Int): String? {
        val side = minOf(width, height)
        if (side <= 0) return null
        return when {
            side >= 2000 -> "4K"
            side >= 1400 -> "1440p"
            side >= 1000 -> "1080p"
            side >= 700 -> "720p"
            side >= 560 -> "576p"
            side >= 460 -> "480p"
            else -> "${side}p"
        }
    }

    /** «5.1», «2.0» и т. п.; моно и неизвестное — null, чтобы строка не пестрела цифрами. */
    fun channels(count: Int): String? = when (count) {
        2 -> "2.0"
        3 -> "2.1"
        6 -> "5.1"
        8 -> "7.1"
        else -> null
    }

    /** «FLAC · 24 бит / 96 кГц»: [bitsUnit] — «бит», [khzUnit] — «кГц». */
    fun audioQuality(format: String, info: AudioStreamInfo?, bitsUnit: String, khzUnit: String): String {
        val parts = mutableListOf<String>()
        info?.bitsPerSample?.takeIf { it > 0 }?.let { parts += "$it $bitsUnit" }
        info?.sampleRate?.takeIf { it > 0 }?.let { parts += "${khz(it)} $khzUnit" }
        return if (parts.isEmpty()) format else "$format · ${parts.joinToString(" / ")}"
    }

    /** 44100 → «44,1», 96000 → «96». */
    fun khz(sampleRate: Int): String {
        val whole = sampleRate / 1000
        val tenth = (sampleRate % 1000) / 100
        return if (tenth == 0) "$whole" else "$whole,$tenth"
    }

    /** Название формата по расширению, если плеер ещё не знает кодек: «FLAC», «MP3». */
    fun formatFromName(name: String): String = FileNames.extension(name).uppercase()
}
