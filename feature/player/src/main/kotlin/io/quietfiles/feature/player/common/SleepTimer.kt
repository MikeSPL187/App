package io.quietfiles.feature.player.common

import io.quietfiles.core.common.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SleepMode {
    data object Off : SleepMode

    /** Остановить в момент [endsAt] (мс, `System.currentTimeMillis`). */
    data class At(val endsAt: Long, val minutes: Int) : SleepMode

    /** Остановить, когда закончится текущий трек или видео. */
    data object EndOfItem : SleepMode
}

/**
 * Таймер сна (F11.1) — общий для музыки и видео: срабатывает один раз, после чего выключается.
 * Плееры слушают [fired] и ставят воспроизведение на паузу.
 */
@Singleton
class SleepTimer @Inject constructor(@ApplicationScope private val scope: CoroutineScope) {

    private val _mode = MutableStateFlow<SleepMode>(SleepMode.Off)
    val mode: StateFlow<SleepMode> = _mode.asStateFlow()

    private val _fired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val fired: SharedFlow<Unit> = _fired.asSharedFlow()

    private var job: Job? = null

    fun start(minutes: Int, now: Long = System.currentTimeMillis()) {
        job?.cancel()
        val endsAt = now + minutes * 60_000L
        _mode.value = SleepMode.At(endsAt, minutes)
        job = scope.launch {
            delay(endsAt - now)
            fire()
        }
    }

    fun stopAtEndOfItem() {
        job?.cancel()
        _mode.value = SleepMode.EndOfItem
    }

    fun cancel() {
        job?.cancel()
        _mode.value = SleepMode.Off
    }

    /** Плеер сообщает, что трек или видео доиграли до конца. */
    fun onItemEnded() {
        if (_mode.value == SleepMode.EndOfItem) fire()
    }

    private fun fire() {
        _mode.value = SleepMode.Off
        _fired.tryEmit(Unit)
    }

    companion object {
        val OPTIONS_MINUTES = listOf(15, 30, 45, 60, 90)
    }
}
