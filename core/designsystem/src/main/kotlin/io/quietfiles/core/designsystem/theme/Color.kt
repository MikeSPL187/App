package io.quietfiles.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import io.quietfiles.core.model.FileCategory

// Токены «Тихий инструмент» (design/tokens.json). Контраст пар проверен по WCAG 2.2.

internal val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF006A64),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9DF2E9),
    onPrimaryContainer = Color(0xFF00201E),
    secondary = Color(0xFF4A6360),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E4),
    onSecondaryContainer = Color(0xFF051F1D),
    tertiary = Color(0xFF7A5900),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDF9E),
    onTertiaryContainer = Color(0xFF261A00),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF5F9F8),
    onBackground = Color(0xFF161D1C),
    surface = Color(0xFFF5F9F8),
    onSurface = Color(0xFF161D1C),
    surfaceVariant = Color(0xFFDAE5E2),
    onSurfaceVariant = Color(0xFF3F4948),
    surfaceTint = Color(0xFF006A64),
    inverseSurface = Color(0xFF2B3231),
    inverseOnSurface = Color(0xFFECF2F0),
    inversePrimary = Color(0xFF7FD6CD),
    outline = Color(0xFF6F7978),
    outlineVariant = Color(0xFFBEC9C7),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF5F9F8),
    surfaceDim = Color(0xFFD5DBD9),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEFF4F3),
    surfaceContainer = Color(0xFFE9EFEE),
    surfaceContainerHigh = Color(0xFFE3EAE8),
    surfaceContainerHighest = Color(0xFFDDE4E2),
)

internal val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF7FD6CD),
    onPrimary = Color(0xFF003734),
    primaryContainer = Color(0xFF00504B),
    onPrimaryContainer = Color(0xFF9DF2E9),
    secondary = Color(0xFFB1CCC8),
    onSecondary = Color(0xFF1C3532),
    secondaryContainer = Color(0xFF324B48),
    onSecondaryContainer = Color(0xFFCCE8E4),
    tertiary = Color(0xFFF2C060),
    onTertiary = Color(0xFF402D00),
    tertiaryContainer = Color(0xFF5C4300),
    onTertiaryContainer = Color(0xFFFFDF9E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1514),
    onBackground = Color(0xFFDDE4E2),
    surface = Color(0xFF0E1514),
    onSurface = Color(0xFFDDE4E2),
    surfaceVariant = Color(0xFF3F4948),
    onSurfaceVariant = Color(0xFFBEC9C7),
    surfaceTint = Color(0xFF7FD6CD),
    inverseSurface = Color(0xFFDDE4E2),
    inverseOnSurface = Color(0xFF2B3231),
    inversePrimary = Color(0xFF006A64),
    outline = Color(0xFF899391),
    outlineVariant = Color(0xFF3F4948),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF343B3A),
    surfaceDim = Color(0xFF0E1514),
    surfaceContainerLowest = Color(0xFF090F0E),
    surfaceContainerLow = Color(0xFF161D1C),
    surfaceContainer = Color(0xFF1A2120),
    surfaceContainerHigh = Color(0xFF252B2A),
    surfaceContainerHighest = Color(0xFF2F3635),
)

/** Цвета категории: фон плитки, значок на плитке и цвет для диаграмм. */
@Immutable
data class CategoryColors(val container: Color, val onContainer: Color, val chart: Color)

/** Дополнительные цвета, которых нет в схеме Material: категории файлов, предупреждения, подсветка поиска. */
@Immutable
data class ExtendedColors(
    val categories: Map<FileCategory, CategoryColors>,
    val folder: CategoryColors,
    val downloads: CategoryColors,
    val favorites: CategoryColors,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val success: Color,
    val searchHighlight: Color,
) {
    fun of(category: FileCategory): CategoryColors =
        if (category == FileCategory.FOLDER) folder else categories[category] ?: categories.getValue(FileCategory.OTHER)
}

private fun cc(container: Long, on: Long, chart: Long) = CategoryColors(Color(container), Color(on), Color(chart))

internal val LightExtended = ExtendedColors(
    categories = mapOf(
        FileCategory.IMAGE to cc(0xFFE9DDFF, 0xFF4A2C9E, 0xFF7B5CE0),
        FileCategory.VIDEO to cc(0xFFFFDAD4, 0xFF8C1D18, 0xFFD9534A),
        FileCategory.AUDIO to cc(0xFFFFE2B8, 0xFF6B4300, 0xFFC98A00),
        FileCategory.DOCUMENT to cc(0xFFD8E2FF, 0xFF1B3D8F, 0xFF3B6FD9),
        FileCategory.SHEET to cc(0xFFCDEFD9, 0xFF0F5132, 0xFF2E8B57),
        FileCategory.PRESENTATION to cc(0xFFFFDBCB, 0xFF7A2E00, 0xFFD9682E),
        FileCategory.TEXT to cc(0xFFDCE4F0, 0xFF2F3F55, 0xFF6B7F9E),
        FileCategory.ARCHIVE to cc(0xFFE4E6D7, 0xFF46493A, 0xFF8A8F6E),
        FileCategory.APK to cc(0xFFD5F0B9, 0xFF2C5000, 0xFF5C8A2E),
        FileCategory.OTHER to cc(0xFFE3EAE8, 0xFF3F4948, 0xFF9AA5A3),
    ),
    folder = cc(0xFFCCE8E4, 0xFF00504B, 0xFF006A64),
    downloads = cc(0xFFC9EEF8, 0xFF00515F, 0xFF1E8FA8),
    favorites = cc(0xFFFFD8E8, 0xFF7D1D4B, 0xFFC2457E),
    warning = Color(0xFF8A5300),
    warningContainer = Color(0xFFFFE2B8),
    onWarningContainer = Color(0xFF3D2600),
    success = Color(0xFF006A64),
    searchHighlight = Color(0xFFFFE08A),
)

internal val DarkExtended = ExtendedColors(
    categories = mapOf(
        FileCategory.IMAGE to cc(0xFF3A2A6B, 0xFFD2C2FF, 0xFF9C84F0),
        FileCategory.VIDEO to cc(0xFF5C1A16, 0xFFFFB4A9, 0xFFE8746A),
        FileCategory.AUDIO to cc(0xFF4D3300, 0xFFFFCF83, 0xFFE0A93A),
        FileCategory.DOCUMENT to cc(0xFF1F3266, 0xFFB3C5FF, 0xFF6E95EC),
        FileCategory.SHEET to cc(0xFF16392A, 0xFF9ED8B6, 0xFF5FB98A),
        FileCategory.PRESENTATION to cc(0xFF5A2410, 0xFFFFB693, 0xFFE8875A),
        FileCategory.TEXT to cc(0xFF263244, 0xFFBCCBE4, 0xFF8CA0C0),
        FileCategory.ARCHIVE to cc(0xFF34372B, 0xFFC8CAB9, 0xFFA6AA8A),
        FileCategory.APK to cc(0xFF233A0B, 0xFFB0D58E, 0xFF86B85A),
        FileCategory.OTHER to cc(0xFF2F3635, 0xFFBEC9C7, 0xFF6F7978),
    ),
    folder = cc(0xFF1F3A37, 0xFF9DF2E9, 0xFF7FD6CD),
    downloads = cc(0xFF003845, 0xFFA0E2F4, 0xFF4FB8D0),
    favorites = cc(0xFF5A1F3A, 0xFFFFB0CF, 0xFFE07BA6),
    warning = Color(0xFFFFB95C),
    warningContainer = Color(0xFF4D3300),
    onWarningContainer = Color(0xFFFFDF9E),
    success = Color(0xFF7FD6CD),
    searchHighlight = Color(0xFF6B4E00),
)

val LocalExtendedColors = staticCompositionLocalOf { LightExtended }
