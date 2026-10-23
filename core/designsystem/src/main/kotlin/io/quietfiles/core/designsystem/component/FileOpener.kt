package io.quietfiles.core.designsystem.component

import androidx.compose.runtime.staticCompositionLocalOf
import io.quietfiles.core.model.FileEntry

/**
 * Открытие файла внутри приложения: текст — в редакторе, фото — в просмотрщике и т. д.
 * Экраны сначала спрашивают его и только при `false` отдают файл другому приложению.
 */
fun interface FileOpener {
    /**
     * @param siblings файлы, среди которых открыт этот (для перелистывания в просмотрщике фото).
     * @param readOnly файл временный, например извлечённый из архива: менять его бессмысленно.
     * @param asText открыть в редакторе независимо от типа («Открыть как текст»).
     */
    fun open(entry: FileEntry, siblings: List<FileEntry>, readOnly: Boolean, asText: Boolean): Boolean
}

val LocalFileOpener = staticCompositionLocalOf { FileOpener { _, _, _, _ -> false } }
