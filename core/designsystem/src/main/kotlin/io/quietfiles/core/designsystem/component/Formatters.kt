package io.quietfiles.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import io.quietfiles.core.common.DateFormatter
import io.quietfiles.core.common.SizeFormatter

/** Форматтеры размеров и дат для текущей локали; пересоздаются при смене языка. */
class Formatters(val size: SizeFormatter, val date: DateFormatter)

@Composable
fun rememberFormatters(): Formatters {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) { Formatters(SizeFormatter.from(context), DateFormatter.from(context)) }
}
