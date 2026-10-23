package io.quietfiles.feature.player.common

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SleepTimerTest {

    @Test
    fun firesOnceAfterMinutesAndTurnsOff() = runTest {
        val timer = SleepTimer(backgroundScope)
        var fired = 0
        backgroundScope.launch { timer.fired.collect { fired++ } }
        runCurrent()
        timer.start(15, now = 0)
        assertThat(timer.mode.value).isEqualTo(SleepMode.At(15 * 60_000L, 15))
        advanceTimeBy(15 * 60_000L - 1)
        runCurrent()
        assertThat(fired).isEqualTo(0)
        advanceTimeBy(2)
        runCurrent()
        assertThat(fired).isEqualTo(1)
        assertThat(timer.mode.value).isEqualTo(SleepMode.Off)
    }

    @Test
    fun restartReplacesPreviousTimerAndCancelStops() = runTest {
        val timer = SleepTimer(backgroundScope)
        var fired = 0
        backgroundScope.launch { timer.fired.collect { fired++ } }
        runCurrent()
        timer.start(15, now = 0)
        advanceTimeBy(10 * 60_000L)
        timer.start(30, now = 0)
        advanceTimeBy(10 * 60_000L)
        runCurrent()
        assertThat(fired).isEqualTo(0)
        timer.cancel()
        advanceTimeBy(60 * 60_000L)
        runCurrent()
        assertThat(fired).isEqualTo(0)
        assertThat(timer.mode.first()).isEqualTo(SleepMode.Off)
    }

    @Test
    fun endOfItemFiresOnlyWhenItemEnds() = runTest {
        val timer = SleepTimer(TestScope(StandardTestDispatcher(testScheduler)))
        var fired = 0
        backgroundScope.launch { timer.fired.collect { fired++ } }
        runCurrent()
        timer.onItemEnded()
        runCurrent()
        assertThat(fired).isEqualTo(0)
        timer.stopAtEndOfItem()
        timer.onItemEnded()
        runCurrent()
        assertThat(fired).isEqualTo(1)
        assertThat(timer.mode.value).isEqualTo(SleepMode.Off)
    }
}
