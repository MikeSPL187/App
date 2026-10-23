package io.quietfiles.core.model.media

/** Жесты видеоплеера (F9.3): перемотка горизонтальным движением, яркость и громкость — вертикальным. */
object PlayerGestures {

    /** Проведя по всей ширине экрана, перематываем на 90 с, но не больше длины ролика. */
    const val FULL_WIDTH_SEEK_MS = 90_000L

    fun seekDelta(dragPx: Float, widthPx: Float, durationMs: Long): Long {
        if (widthPx <= 0) return 0
        val span = if (durationMs > 0) minOf(FULL_WIDTH_SEEK_MS, durationMs) else FULL_WIDTH_SEEK_MS
        return (dragPx / widthPx * span).toLong()
    }

    /** Новое значение 0..1: движение вверх на всю высоту области прибавляет 100%. */
    fun level(start: Float, dragUpPx: Float, heightPx: Float): Float {
        if (heightPx <= 0) return start
        return (start + dragUpPx / heightPx).coerceIn(0f, 1f)
    }

    /** Двойное касание: левая треть — назад, правая — вперёд, середина — пауза. */
    fun zoneOf(x: Float, widthPx: Float): Int = when {
        x < widthPx / 3 -> -1
        x > widthPx * 2 / 3 -> 1
        else -> 0
    }

    const val DOUBLE_TAP_SEEK_MS = 10_000L
}
