package io.quietfiles.core.designsystem.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import io.quietfiles.core.model.FileCategory

/**
 * Значки «Тихого инструмента»: линейные, сетка 24 dp, линия 1,8, скруглённые концы.
 * Один стиль на всё приложение; цвет задаётся через `tint` у `Icon`.
 */
object QfIcons {
    private fun stroke(name: String, d: String, width: Float = 1.8f): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ).build()

    private fun filled(name: String, d: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black))
            .build()

    // Навигация и оболочка
    val Home by lazy { stroke("Home", "M4 10.5L12 4l8 6.5 M6 9v10.5h4.5V14h3v5.5H18V9") }
    val Folder by lazy { stroke("Folder", "M3.5 7.5a2 2 0 0 1 2-2h3.8l2 2h7.2a2 2 0 0 1 2 2v7.5a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z") }
    val FolderOpen by lazy { stroke("FolderOpen", "M3.5 17V7.5a2 2 0 0 1 2-2h3.8l2 2h6.2a2 2 0 0 1 2 2v1 M3.5 17l2.2-6a1.5 1.5 0 0 1 1.4-1H20a1 1 0 0 1 .95 1.3L19 17.5a1.5 1.5 0 0 1-1.4 1H5a1.5 1.5 0 0 1-1.5-1.5z") }
    val Cloud by lazy { stroke("Cloud", "M7.5 18.5a4.5 4.5 0 0 1-.4-9A6 6 0 0 1 18.3 10a4.3 4.3 0 0 1-.8 8.5z") }
    val Tools by lazy { stroke("Tools", "M14.5 5.5a4 4 0 0 0-5 5L4.5 15.5a2.1 2.1 0 0 0 3 3l5-5a4 4 0 0 0 5-5l-2.4 2.4-2.6-.4-.4-2.6z") }
    val Search by lazy { stroke("Search", "M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14z M20 20l-4.3-4.3") }
    val Settings by lazy { stroke("Settings", "M4 7h9 M17 7h3 M15 5v4 M4 12h3 M11 12h9 M9 10v4 M4 17h11 M19 17h1 M17 15v4") }
    val Tabs by lazy { stroke("Tabs", "M4 6.5A1.5 1.5 0 0 1 5.5 5h13A1.5 1.5 0 0 1 20 6.5v11a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 17.5z M4 9.5h16 M9 5v4.5") }
    val Back by lazy { stroke("Back", "M19 12H5 M11 6l-6 6 6 6") }
    val Close by lazy { stroke("Close", "M6 6l12 12 M18 6L6 18") }
    val More by lazy { stroke("More", "M12 5.5h.01 M12 12h.01 M12 18.5h.01", width = 2.6f) }
    val ChevronRight by lazy { stroke("ChevronRight", "M9 6l6 6-6 6") }
    val ChevronDown by lazy { stroke("ChevronDown", "M6 9l6 6 6-6") }
    val ChevronUp by lazy { stroke("ChevronUp", "M6 15l6-6 6 6") }
    val Check by lazy { stroke("Check", "M5 12.5l4.5 4.5L19 7.5") }
    val Plus by lazy { stroke("Plus", "M12 5v14 M5 12h14") }
    val Filter by lazy { stroke("Filter", "M4 6h16 M7 12h10 M10 18h4") }
    val SortUp by lazy { stroke("SortUp", "M7 20V5 M3.5 8.5L7 5l3.5 3.5 M13 7h7 M13 12h5 M13 17h3") }
    val SortDown by lazy { stroke("SortDown", "M7 4v15 M3.5 15.5L7 19l3.5-3.5 M13 7h3 M13 12h5 M13 17h7") }
    val ListView by lazy { stroke("ListView", "M9 6h11 M9 12h11 M9 18h11 M4.5 6h.01 M4.5 12h.01 M4.5 18h.01") }
    val GridView by lazy { stroke("GridView", "M4 4h6.5v6.5H4z M13.5 4H20v6.5h-6.5z M4 13.5h6.5V20H4z M13.5 13.5H20V20h-6.5z") }
    val History by lazy { stroke("History", "M4 12a8 8 0 1 0 2.4-5.7L4 8.6 M4 4v4.6h4.6 M12 8v4.5l3 1.8") }
    val Info by lazy { stroke("Info", "M12 3a9 9 0 1 1 0 18a9 9 0 0 1 0-18z M12 11v5 M12 8h.01") }
    val Alert by lazy { stroke("Alert", "M12 4l9 15.5H3z M12 10v4 M12 17h.01") }
    val Sparkle by lazy { stroke("Sparkle", "M12 4l1.6 4.4L18 10l-4.4 1.6L12 16l-1.6-4.4L6 10l4.4-1.6z M18.5 15.5l.7 1.8 1.8.7-1.8.7-.7 1.8-.7-1.8-1.8-.7 1.8-.7z") }
    val Lock by lazy { stroke("Lock", "M6 11h12v9H6z M8.5 11V8a3.5 3.5 0 0 1 7 0v3") }
    val Shield by lazy { stroke("Shield", "M12 3.5l7 2.6v5.4c0 4.4-3 7.6-7 9-4-1.4-7-4.6-7-9V6.1z M9 12l2 2 4-4") }
    val EyeOff by lazy { stroke("EyeOff", "M3 3l18 18 M10.6 6.1A9.8 9.8 0 0 1 12 6c5 0 8.5 4.2 9.5 6-.5.9-1.6 2.5-3.2 3.8 M6.4 7.6C4.6 8.9 3.2 10.7 2.5 12c1 1.8 4.5 6 9.5 6a9.6 9.6 0 0 0 4.3-1 M9.9 9.9a3 3 0 0 0 4.2 4.2") }

    // Действия с файлами
    val Copy by lazy { stroke("Copy", "M9 9h10a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H10a1 1 0 0 1-1-1z M5.5 15H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1h9a1 1 0 0 1 1 1v.5") }
    val Cut by lazy { stroke("Cut", "M6 4a2.5 2.5 0 1 1 0 5a2.5 2.5 0 0 1 0-5z M6 15a2.5 2.5 0 1 1 0 5a2.5 2.5 0 0 1 0-5z M8.1 8.1L20 18 M8.1 15.9L20 6") }
    val Move by lazy { stroke("Move", "M3.5 7.5a2 2 0 0 1 2-2h3.8l2 2h7.2a2 2 0 0 1 2 2v7.5a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z M9 13h6.5 M13 10.5l2.5 2.5-2.5 2.5") }
    val Paste by lazy { stroke("Paste", "M9 4h6v3H9z M15 5.5h2a1.5 1.5 0 0 1 1.5 1.5v12a1.5 1.5 0 0 1-1.5 1.5H7A1.5 1.5 0 0 1 5.5 19V7A1.5 1.5 0 0 1 7 5.5h2 M9 12h6 M9 15.5h4") }
    val Share by lazy { stroke("Share", "M18 3.5a2.5 2.5 0 1 1 0 5a2.5 2.5 0 0 1 0-5z M6 9.5a2.5 2.5 0 1 1 0 5a2.5 2.5 0 0 1 0-5z M18 15.5a2.5 2.5 0 1 1 0 5a2.5 2.5 0 0 1 0-5z M8.3 13.2l7.4 3.6 M15.7 7.2l-7.4 3.6") }
    val Trash by lazy { stroke("Trash", "M4 7h16 M10 11v6 M14 11v6 M6 7l1 12.2A1 1 0 0 0 8 20h8a1 1 0 0 0 1-.8L18 7 M9 7V4.5h6V7") }
    val Restore by lazy { stroke("Restore", "M4 12a8 8 0 1 0 2.4-5.7L4 8.6 M4 4v4.6h4.6") }
    val Rename by lazy { stroke("Rename", "M4 20h4L19 9l-4-4L4 16z M14 6l4 4") }
    val Pause by lazy { stroke("Pause", "M9 6v12 M15 6v12", width = 2.2f) }
    val Play by lazy { filled("Play", "M8 5.5v13l10.5-6.5z") }
    val OpenWith by lazy { stroke("OpenWith", "M14 4h6v6 M20 4l-9 9 M18 14v4.5a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 4 18.5v-11A1.5 1.5 0 0 1 5.5 6H10") }
    val Star by lazy { stroke("Star", "M12 4l2.5 5.1 5.6.8-4 4 1 5.6-5.1-2.7-5.1 2.7 1-5.6-4-4 5.6-.8z") }
    val StarFilled by lazy { filled("StarFilled", "M12 3.2l2.75 5.6 6.15.9-4.45 4.35 1.05 6.15L12 17.3l-5.5 2.9 1.05-6.15L3.1 9.7l6.15-.9z") }
    val SelectAll by lazy { stroke("SelectAll", "M4 4h16v16H4z M8 12.5l3 3 5-6") }
    val Shortcut by lazy { stroke("Shortcut", "M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z M9 15l6-6 M10 9h5v5") }
    val Compress by lazy { stroke("Compress", "M4 5h16v4H4z M5.5 9v9.5A1.5 1.5 0 0 0 7 20h10a1.5 1.5 0 0 0 1.5-1.5V9 M12 11v6 M9.5 14.5L12 17l2.5-2.5") }
    val Extract by lazy { stroke("Extract", "M4 5h16v4H4z M5.5 9v9.5A1.5 1.5 0 0 0 7 20h10a1.5 1.5 0 0 0 1.5-1.5V9 M12 17v-6 M9.5 13.5L12 11l2.5 2.5") }
    val NewFolder by lazy { stroke("NewFolder", "M3.5 7.5a2 2 0 0 1 2-2h3.8l2 2h7.2a2 2 0 0 1 2 2v7.5a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z M12 10.5v5 M9.5 13h5") }
    val NewFile by lazy { stroke("NewFile", "M13.5 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8.5z M13.5 3v5.5H19 M12 11.5v6 M9 14.5h6") }

    // Хранилища
    val Storage by lazy { stroke("Storage", "M4 14l2.6-8.2A1.2 1.2 0 0 1 7.7 5h8.6a1.2 1.2 0 0 1 1.1.8L20 14 M4 14h16v4.8a1.2 1.2 0 0 1-1.2 1.2H5.2A1.2 1.2 0 0 1 4 18.8z M8 17h.01 M11.5 17h.01") }
    val SdCard by lazy { stroke("SdCard", "M8 3h8.5L19 5.5V20a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V6z M9 7v3 M12 7v3 M15 7v3") }
    val Usb by lazy { stroke("Usb", "M12 3v14 M12 3l-2 3h4z M8 9v3l4 3 M16 8v3l-4 3 M7 8h2v1H7z M15 7h2v1h-2z M12 17a2 2 0 1 1 0 4a2 2 0 0 1 0-4z") }
    val Phone by lazy { stroke("Phone", "M8 3h8a1.5 1.5 0 0 1 1.5 1.5v15A1.5 1.5 0 0 1 16 21H8a1.5 1.5 0 0 1-1.5-1.5v-15A1.5 1.5 0 0 1 8 3z M11 18h2") }

    // Категории файлов
    val Image by lazy { stroke("Image", "M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z M4 16l4.5-4.5 4 4 2.5-2.5 5 5 M15.5 8.5h.01") }
    val Video by lazy { stroke("Video", "M4 7a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2z M10 9.2v5.6l4.6-2.8z") }
    val Music by lazy { stroke("Music", "M9 17.5V6l10-2v11.5 M9 17.5a2.5 2.5 0 1 1-5 0a2.5 2.5 0 0 1 5 0z M19 15.5a2.5 2.5 0 1 1-5 0a2.5 2.5 0 0 1 5 0z") }
    val Document by lazy { stroke("Document", "M13.5 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8.5z M13.5 3v5.5H19 M8.5 13h7 M8.5 16.5h7") }
    val Sheet by lazy { stroke("Sheet", "M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z M4 9.5h16 M4 14.5h16 M10 9.5V20") }
    val Presentation by lazy { stroke("Presentation", "M3.5 5h17 M5 5v9a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V5 M12 15v3 M8.5 20.5L12 18l3.5 2.5 M8.5 11.5l2.5-2.5 2 2 2.5-3") }
    val Text by lazy { stroke("Text", "M13.5 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8.5z M13.5 3v5.5H19 M8.5 12h7 M8.5 15h7 M8.5 18h4") }
    val Archive by lazy { stroke("Archive", "M4 5h16v4H4z M5.5 9v9.5A1.5 1.5 0 0 0 7 20h10a1.5 1.5 0 0 0 1.5-1.5V9 M10 13h4") }
    val Apk by lazy { stroke("Apk", "M12 3l8 4.5v9L12 21l-8-4.5v-9z M4.3 7.6L12 12l7.7-4.4 M12 12v9") }
    val Download by lazy { stroke("Download", "M12 4v11 M7.5 10.5L12 15l4.5-4.5 M5 20h14") }
    val File by lazy { stroke("File", "M13.5 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8.5z M13.5 3v5.5H19") }
    val Clock by lazy { stroke("Clock", "M12 3.5a8.5 8.5 0 1 1 0 17a8.5 8.5 0 0 1 0-17z M12 7.5v5l3 2") }

    fun forCategory(category: FileCategory): ImageVector = when (category) {
        FileCategory.FOLDER -> Folder
        FileCategory.IMAGE -> Image
        FileCategory.VIDEO -> Video
        FileCategory.AUDIO -> Music
        FileCategory.DOCUMENT -> Document
        FileCategory.SHEET -> Sheet
        FileCategory.PRESENTATION -> Presentation
        FileCategory.TEXT -> Text
        FileCategory.ARCHIVE -> Archive
        FileCategory.APK -> Apk
        FileCategory.OTHER -> File
    }
}
