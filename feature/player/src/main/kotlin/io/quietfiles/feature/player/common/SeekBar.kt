package io.quietfiles.feature.player.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.quietfiles.core.model.media.PlaybackTime

/** Вид бегунка: круг у видео (макет 20), вертикальная планка у музыки (макет 22). */
enum class ThumbStyle { CIRCLE, BAR }

/**
 * Шкала воспроизведения: пройденное, буфер, отметки глав. Перетаскивание показывает позицию
 * сразу, а в плеер уходит в конце жеста — так нет лишних перемоток.
 */
@Composable
fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    label: String,
    activeColor: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
    bufferedMs: Long = 0,
    bufferedColor: Color = Color.Transparent,
    marksMs: List<Long> = emptyList(),
    markColor: Color = Color.White,
    trackHeight: Dp = 4.dp,
    thumb: ThumbStyle = ThumbStyle.CIRCLE,
    onScrub: (Long?) -> Unit = {},
) {
    var scrub by remember { mutableStateOf<Float?>(null) }
    val duration by rememberUpdatedState(durationMs)
    val seek by rememberUpdatedState(onSeek)
    val scrubbing by rememberUpdatedState(onScrub)
    val fraction = scrub ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Canvas(
        modifier
            .fillMaxWidth()
            .height(24.dp)
            .semantics {
                contentDescription = label
                stateDescription = "${PlaybackTime.format((fraction * durationMs).toLong())} / ${PlaybackTime.format(durationMs)}"
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                setProgress { value ->
                    seek((value.coerceIn(0f, 1f) * duration).toLong())
                    true
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    if (duration > 0) seek((offset.x / size.width).coerceIn(0f, 1f).times(duration).toLong())
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        scrub = (offset.x / size.width).coerceIn(0f, 1f)
                        scrubbing((scrub!! * duration).toLong())
                    },
                    onDragEnd = {
                        scrub?.let { if (duration > 0) seek((it * duration).toLong()) }
                        scrub = null
                        scrubbing(null)
                    },
                    onDragCancel = {
                        scrub = null
                        scrubbing(null)
                    },
                ) { change, delta ->
                    change.consume()
                    scrub = ((scrub ?: 0f) + delta / size.width).coerceIn(0f, 1f)
                    scrubbing((scrub!! * duration).toLong())
                }
            },
    ) {
        val h = trackHeight.toPx()
        val y = size.height / 2 - h / 2
        val radius = CornerRadius(h / 2)
        drawRoundRect(trackColor, Offset(0f, y), Size(size.width, h), radius)
        if (durationMs > 0 && bufferedMs > 0) {
            val buffered = (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f)
            drawRoundRect(bufferedColor, Offset(0f, y), Size(size.width * buffered, h), radius)
        }
        drawRoundRect(activeColor, Offset(0f, y), Size(size.width * fraction, h), radius)
        if (durationMs > 0) {
            marksMs.forEach { mark ->
                val x = size.width * (mark.toFloat() / durationMs)
                drawRoundRect(markColor, Offset(x - 1.5.dp.toPx(), y - 2.dp.toPx()), Size(3.dp.toPx(), h + 4.dp.toPx()), CornerRadius(1.5.dp.toPx()))
            }
        }
        val x = size.width * fraction
        when (thumb) {
            ThumbStyle.CIRCLE -> drawCircle(activeColor, radius = if (scrub != null) 10.dp.toPx() else 8.dp.toPx(), center = Offset(x, size.height / 2))
            ThumbStyle.BAR -> drawRoundRect(
                activeColor,
                Offset(x - 3.dp.toPx(), size.height / 2 - 10.dp.toPx()),
                Size(6.dp.toPx(), 20.dp.toPx()),
                CornerRadius(3.dp.toPx()),
            )
        }
    }
}
