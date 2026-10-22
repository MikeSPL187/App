package io.quietfiles.core.model

/** Тип файла для значка, цвета и фильтров. Порядок совпадает с порядком в фильтре поиска. */
enum class FileCategory {
    FOLDER,
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
    SHEET,
    PRESENTATION,
    TEXT,
    ARCHIVE,
    APK,
    OTHER,
}

/** Сопоставление расширений и MIME-типов с категориями. */
object FileTypes {
    private val byExtension: Map<String, FileCategory> = buildMap {
        fun put(category: FileCategory, vararg ext: String) = ext.forEach { put(it, category) }
        put(
            FileCategory.IMAGE,
            "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif", "bmp", "svg", "dng", "raw", "tif", "tiff", "ico",
        )
        put(
            FileCategory.VIDEO,
            "mp4", "m4v", "mkv", "webm", "mov", "avi", "3gp", "3g2", "flv", "wmv", "ts", "mpg", "mpeg",
        )
        put(
            FileCategory.AUDIO,
            "mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav", "wma", "amr", "mid", "midi", "aiff", "ape",
        )
        put(FileCategory.DOCUMENT, "pdf", "doc", "docx", "odt", "rtf", "epub", "fb2", "djvu", "pages")
        put(FileCategory.SHEET, "xls", "xlsx", "ods", "csv", "tsv", "numbers")
        put(FileCategory.PRESENTATION, "ppt", "pptx", "odp", "key")
        put(
            FileCategory.TEXT,
            "txt", "md", "log", "json", "xml", "yaml", "yml", "ini", "conf", "cfg", "properties", "toml",
            "html", "htm", "css", "js", "kt", "java", "py", "sh", "c", "cpp", "h", "sql", "srt",
        )
        put(FileCategory.ARCHIVE, "zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "tbz2", "xz", "txz", "jar")
        put(FileCategory.APK, "apk", "apks", "xapk", "apkm")
    }

    private val mimeByExtension: Map<String, String> = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
        "webp" to "image/webp", "heic" to "image/heic", "heif" to "image/heif", "avif" to "image/avif",
        "bmp" to "image/bmp", "svg" to "image/svg+xml",
        "mp4" to "video/mp4", "m4v" to "video/mp4", "mkv" to "video/x-matroska", "webm" to "video/webm",
        "mov" to "video/quicktime", "avi" to "video/x-msvideo", "3gp" to "video/3gpp",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "ogg" to "audio/ogg",
        "opus" to "audio/opus", "flac" to "audio/flac", "wav" to "audio/wav", "amr" to "audio/amr",
        "pdf" to "application/pdf", "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "odt" to "application/vnd.oasis.opendocument.text", "rtf" to "application/rtf",
        "epub" to "application/epub+zip",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ods" to "application/vnd.oasis.opendocument.spreadsheet", "csv" to "text/csv",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "txt" to "text/plain", "md" to "text/markdown", "log" to "text/plain", "json" to "application/json",
        "xml" to "text/xml", "yaml" to "text/plain", "yml" to "text/plain", "html" to "text/html",
        "htm" to "text/html", "csv" to "text/csv", "srt" to "application/x-subrip",
        "zip" to "application/zip", "7z" to "application/x-7z-compressed", "rar" to "application/vnd.rar",
        "tar" to "application/x-tar", "gz" to "application/gzip", "tgz" to "application/gzip",
        "bz2" to "application/x-bzip2", "xz" to "application/x-xz",
        "apk" to "application/vnd.android.package-archive",
    )

    fun categoryOf(fileName: String, mimeType: String? = null): FileCategory {
        byExtension[FileNames.extension(fileName).lowercase()]?.let { return it }
        return when {
            mimeType == null -> FileCategory.OTHER
            mimeType.startsWith("image/") -> FileCategory.IMAGE
            mimeType.startsWith("video/") -> FileCategory.VIDEO
            mimeType.startsWith("audio/") -> FileCategory.AUDIO
            mimeType.startsWith("text/") -> FileCategory.TEXT
            else -> FileCategory.OTHER
        }
    }

    /** MIME-тип по имени файла; `application/octet-stream`, если неизвестен. */
    fun mimeTypeOf(fileName: String): String =
        mimeByExtension[FileNames.extension(fileName).lowercase()] ?: "application/octet-stream"

    /** Расширения категории, для поиска по индексу. */
    fun extensionsOf(category: FileCategory): Set<String> =
        byExtension.filterValues { it == category }.keys

    /** Расширение для MIME-типа — чтобы файл без имени из другого приложения открывался правильно. */
    fun extensionForMime(mimeType: String): String? {
        val mime = mimeType.lowercase().substringBefore(';').trim()
        return preferredExtension[mime] ?: mimeByExtension.entries.firstOrNull { it.value == mime }?.key
    }

    /** Подходит ли [mimeType] под фильтр: группа вроде «image» с любым подтипом, «application/pdf» или «любой тип». Регистр не важен. */
    fun mimeMatches(mimeType: String, filter: String): Boolean {
        val type = mimeType.lowercase()
        val f = filter.lowercase().substringBefore(';').trim()
        if (f.isEmpty() || f == "*/*" || f == "*") return true
        return if (f.endsWith("/*")) type.substringBefore('/') == f.substringBefore('/') else type == f
    }

    /** Пустой список фильтров пропускает всё. */
    fun mimeMatchesAny(mimeType: String, filters: List<String>): Boolean =
        filters.isEmpty() || filters.any { mimeMatches(mimeType, it) }

    private val preferredExtension = mapOf(
        "image/jpeg" to "jpg", "text/plain" to "txt", "text/html" to "html", "audio/mpeg" to "mp3",
        "video/mp4" to "mp4", "application/gzip" to "gz",
    )
}
