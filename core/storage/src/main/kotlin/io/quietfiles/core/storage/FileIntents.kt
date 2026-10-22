package io.quietfiles.core.storage

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import io.quietfiles.core.model.FileEntry
import io.quietfiles.core.model.FileTypes
import java.io.File

/**
 * Открытие и отправка файлов другим приложениям через собственный FileProvider (F15.3).
 * Права выдаются на конкретный файл и только на чтение.
 */
object FileIntents {
    fun authority(context: Context) = "${context.packageName}.files"

    fun uriFor(context: Context, path: String): Uri =
        FileProvider.getUriForFile(context, authority(context), File(path))

    fun mimeOf(entry: FileEntry): String = entry.mimeType ?: FileTypes.mimeTypeOf(entry.name)

    /** Открыть файл в подходящем приложении. [mimeOverride] — «Открыть как» (текст, изображение…). */
    fun view(context: Context, entry: FileEntry, mimeOverride: String? = null): Intent {
        val uri = uriFor(context, entry.path)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeOverride ?: mimeOf(entry))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun chooserForView(context: Context, entry: FileEntry, title: String): Intent =
        Intent.createChooser(view(context, entry), title)

    fun share(context: Context, entries: List<FileEntry>, title: String): Intent {
        val files = entries.filterNot { it.isDirectory }
        val uris = files.map { uriFor(context, it.path) }
        val mime = commonMime(files.map(::mimeOf))
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        send.type = mime
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (uris.isNotEmpty()) {
            send.clipData = ClipData.newUri(context.contentResolver, files.first().name, uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
        }
        return Intent.createChooser(send, title)
    }

    /** Общий MIME-тип: одинаковые типы — как есть, одна группа (image) — «группа/любой», иначе — любой тип. */
    fun commonMime(mimes: List<String>): String {
        if (mimes.isEmpty()) return "*/*"
        if (mimes.distinct().size == 1) return mimes.first()
        val groups = mimes.map { it.substringBefore('/') }.distinct()
        return if (groups.size == 1) "${groups.first()}/*" else "*/*"
    }
}
