package io.quietfiles.feature.browser

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.quietfiles.core.designsystem.component.Formatters
import io.quietfiles.core.designsystem.icon.QfIcons
import io.quietfiles.core.model.FileEntry
import io.quietfiles.core.model.FileNames
import io.quietfiles.core.model.StorageVolumeInfo
import io.quietfiles.core.model.VolumeKind
import io.quietfiles.core.storage.ApkStatus
import io.quietfiles.core.common.R as CommonR

@Composable
fun volumeLabel(volume: StorageVolumeInfo, short: Boolean = false): String = when (volume.kind) {
    VolumeKind.INTERNAL -> stringResource(if (short) R.string.volume_internal_short else R.string.volume_internal)
    VolumeKind.SD_CARD -> volume.label.ifBlank { stringResource(R.string.volume_sd) }
    VolumeKind.USB -> volume.label.ifBlank { stringResource(R.string.volume_usb) }
}

fun volumeIcon(volume: StorageVolumeInfo): ImageVector = when (volume.kind) {
    VolumeKind.INTERNAL -> QfIcons.Storage
    VolumeKind.SD_CARD -> QfIcons.SdCard
    VolumeKind.USB -> QfIcons.Usb
}

@Composable
fun kindTitle(kind: SourceKind): String = stringResource(
    when (kind) {
        SourceKind.PHOTOS -> R.string.browser_title_photos
        SourceKind.VIDEOS -> R.string.browser_title_videos
        SourceKind.AUDIO -> R.string.browser_title_audio
        SourceKind.DOCUMENTS -> R.string.browser_title_documents
        SourceKind.ARCHIVES -> R.string.browser_title_archives
        SourceKind.APKS -> R.string.browser_title_apks
        SourceKind.RECENT -> R.string.browser_title_recent
        SourceKind.FAVORITES -> R.string.browser_title_favorites
        SourceKind.TRASH -> R.string.browser_title_trash
        SourceKind.LARGE_FILES -> R.string.browser_title_large
        SourceKind.FOLDER -> R.string.browser_title_storage
        SourceKind.ARCHIVE -> R.string.browser_archive
    },
)

/** Вторая строка элемента: «PDF · 2,4 МБ · вчера, 18:40», «148 объектов · сегодня, 09:12». */
@Composable
fun itemMeta(item: BrowserItem, kind: SourceKind, formatters: Formatters): String {
    val entry = item.entry
    val sep = stringResource(R.string.meta_separator)
    val trash = item.trash
    if (trash != null) {
        val from = FileNames.parentOf(trash.originalPath)?.let(FileNames::nameOf).orEmpty()
        return stringResource(R.string.meta_trash, formatters.date.short(trash.deletedAt), from)
    }
    val date = if (entry.lastModified > 0) formatters.date.short(entry.lastModified) else null
    val parts = mutableListOf<String>()
    if (entry.isDirectory) {
        entry.childCount?.let { parts += pluralStringResource(CommonR.plurals.items_count, it, it) }
    } else {
        entry.extension.takeIf { it.isNotEmpty() }?.let { parts += it.uppercase() }
        if (kind != SourceKind.ARCHIVE || entry.size > 0) parts += formatters.size.format(entry.size)
        item.apk?.let { apk ->
            apk.versionName?.let { parts += stringResource(R.string.meta_apk_version, it) }
            when (apk.status) {
                ApkStatus.INSTALLED -> parts += stringResource(R.string.meta_apk_installed)
                ApkStatus.UPDATE -> parts += stringResource(R.string.meta_apk_update)
                ApkStatus.OLDER -> parts += stringResource(R.string.meta_apk_older)
                ApkStatus.UNKNOWN -> Unit
            }
        }
    }
    date?.let { parts += it }
    if (kind.isLibrary || kind == SourceKind.FAVORITES) {
        FileNames.parentOf(entry.path)?.let(FileNames::nameOf)?.let { parts += it }
    }
    return parts.joinToString(sep)
}

fun isRecent(entry: FileEntry, now: Long = System.currentTimeMillis()): Boolean =
    !entry.isDirectory && entry.lastModified > 0 && now - entry.lastModified in 0..DAY_MS

private const val DAY_MS = 24L * 60 * 60 * 1000

/** Понятная причина, почему имя не подходит, или `null`, если подходит. */
internal fun nameValidationMessage(context: Context, name: String): String? = when (val v = FileNames.validate(name)) {
    FileNames.Validation.Ok -> null
    FileNames.Validation.Empty -> context.getString(R.string.browser_error_empty_name)
    FileNames.Validation.DotOnly -> context.getString(R.string.browser_error_invalid_name)
    FileNames.Validation.TooLong -> context.getString(R.string.browser_error_long_name)
    is FileNames.Validation.ForbiddenChar -> context.getString(R.string.browser_error_forbidden_char, v.char.toString())
}
