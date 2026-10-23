package io.quietfiles.feature.viewer.photo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.launch

/**
 * Масштаб щипком и двойным касанием (F8.1). Пока снимок не увеличен, одиночный жест
 * не перехватывается — им листает пейджер.
 */
@Composable
fun ZoomableBox(
    onTap: () -> Unit,
    onScaleChanged: (Float) -> Unit,
    resetKey: Any,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scale = remember { Animatable(1f) }
    val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val size = remember { arrayOf(IntSize.Zero) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(resetKey) {
        scale.snapTo(1f)
        offset.snapTo(Offset.Zero)
        onScaleChanged(1f)
    }

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = size[0].width * (s - 1) / 2
        val maxY = size[0].height * (s - 1) / 2
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { size[0] = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        scope.launch {
                            if (scale.value > 1.05f) {
                                launch { offset.animateTo(Offset.Zero) }
                                scale.animateTo(1f)
                                onScaleChanged(1f)
                            } else {
                                val target = DOUBLE_TAP_SCALE
                                val center = Offset(size[0].width / 2f, size[0].height / 2f)
                                launch { offset.animateTo(clamp((center - tap) * (target - 1), target)) }
                                scale.animateTo(target)
                                onScaleChanged(target)
                            }
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed > 1 || scale.value > 1.01f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            val old = scale.value
                            val new = (old * zoom).coerceIn(1f, MAX_SCALE)
                            val center = Offset(size[0].width / 2f, size[0].height / 2f)
                            val focus = centroid - center
                            val moved = (offset.value - focus) * (new / old) + focus + pan
                            scope.launch {
                                scale.snapTo(new)
                                offset.snapTo(clamp(moved, new))
                            }
                            onScaleChanged(new)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                translationX = offset.value.x
                translationY = offset.value.y
            },
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

private const val DOUBLE_TAP_SCALE = 2.5f
private const val MAX_SCALE = 8f
