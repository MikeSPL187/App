package io.quietfiles.integration

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import io.quietfiles.core.model.FileNames
import io.quietfiles.core.model.FileTypes
import io.quietfiles.core.operations.ImportItem

/** Что пришло через «Поделиться»: файлы по content-URI и/или текст. */
data class SharedContent(val uris: List<Uri>, val text: String?, val subject: String?) {
    val isEmpty: Boolean get() = uris.isEmpty() && text.isNullOrBlank()

    companion object {
        /**
         * Разбирает SEND/SEND_MULTIPLE. Принимаются только `content://` чужих приложений:
         * `file://` и URI нашего собственного FileProvider отбрасываются, иначе через «Сохранить»
         * можно было бы вытащить внутренние файлы приложения в общую память (раздел S ТЗ).
         */
        fun from(intent: Intent, ownAuthorities: Set<String>): SharedContent {
            val uris = buildList {
                when (intent.action) {
                    Intent.ACTION_SEND ->
                        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(::add)
                    Intent.ACTION_SEND_MULTIPLE ->
                        IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(::addAll)
                }
                // Некоторые приложения кладут файлы только в ClipData.
                if (isEmpty()) {
                    val clip = intent.clipData
                    if (clip != null) for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(::add)
                }
            }
                .filter { it.scheme == ContentResolver.SCHEME_CONTENT && it.authority !in ownAuthorities }
                .distinct()
            val text = if (uris.isEmpty()) intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() else null
            return SharedContent(uris, text, intent.getStringExtra(Intent.EXTRA_SUBJECT))
        }
    }
}

/**
 * Имена и размеры берём сразу, пока у нас есть право на чтение. Безымянный файл получает
 * имя по MIME-типу, текст сохраняется в «.txt».
 */
fun SharedContent.toImportItems(resolver: ContentResolver, textFallbackName: String): List<ImportItem> {
    val files = uris.mapIndexed { index, uri ->
        var name: String? = null
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0 && !c.isNull(nameIdx)) name = c.getString(nameIdx)
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                }
            }
        }
        val mime = runCatching { resolver.getType(uri) }.getOrNull()
        val base = FileNames.sanitize(name ?: uri.lastPathSegment.orEmpty(), fallback = "file ${index + 1}")
        val ext = mime?.let(FileTypes::extensionForMime)
        val finalName = if (FileNames.extension(base).isEmpty() && ext != null) "$base.$ext" else base
        ImportItem(name = finalName, uri = uri.toString(), size = size)
    }
    val textItem = text?.takeIf { it.isNotBlank() }?.let {
        val base = FileNames.sanitize(subject.orEmpty().take(80), fallback = textFallbackName)
        ImportItem(name = if (base.endsWith(".txt", ignoreCase = true)) base else "$base.txt", text = it)
    }
    return files + listOfNotNull(textItem)
}
