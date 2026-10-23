package io.quietfiles.core.model.media

import io.quietfiles.core.model.FileNames

/** Файл субтитров рядом с видео (F9.4): «Фильм.srt», «Фильм.en.srt», «Фильм.rus.ass». */
data class SidecarSubtitle(
    val path: String,
    /** Код языка из имени («en», «rus») или null, если его нет. */
    val language: String?,
    val mimeType: String,
)

object SubtitleFinder {

    private val MIME = mapOf(
        "srt" to "application/x-subrip",
        "ass" to "text/x-ssa",
        "ssa" to "text/x-ssa",
        "vtt" to "text/vtt",
    )

    fun isSubtitle(name: String) = FileNames.extension(name).lowercase() in MIME

    fun mimeTypeOf(name: String): String? = MIME[FileNames.extension(name).lowercase()]

    /**
     * Ищет среди [siblings] (полные пути файлов той же папки) субтитры к видео [videoPath].
     * Имя должно совпадать без учёта регистра; между ним и расширением допускается метка языка.
     */
    fun find(videoPath: String, siblings: List<String>): List<SidecarSubtitle> {
        val base = FileNames.nameOf(videoPath).let { it.substringBeforeLast('.', it) }.lowercase()
        return siblings.mapNotNull { path ->
            val name = FileNames.nameOf(path)
            val mime = mimeTypeOf(name) ?: return@mapNotNull null
            val stem = name.substringBeforeLast('.').lowercase()
            when {
                stem == base -> SidecarSubtitle(path, null, mime)
                stem.startsWith("$base.") -> {
                    val tag = stem.removePrefix("$base.").substringAfterLast('.')
                    SidecarSubtitle(path, tag.takeIf { it.length in 2..3 && it.all(Char::isLetter) }, mime)
                }
                else -> null
            }
        }.sortedWith(
            // Сначала «Фильм.srt», затем прочие без языка («Фильм.forced.srt»), затем с языком.
            compareBy(
                { if (it.language != null) 2 else if (FileNames.nameOf(it.path).substringBeforeLast('.').lowercase() == base) 0 else 1 },
                { FileNames.nameOf(it.path).lowercase() },
            ),
        )
    }
}
