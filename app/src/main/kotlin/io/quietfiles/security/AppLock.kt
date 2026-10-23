package io.quietfiles.security

import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Блокировка приложения (F14, S11). Разблокировка действует на весь процесс — главный экран,
 * выбор файлов и «Сохранить в…» — и сбрасывается, если приложение пробыло в фоне дольше [GRACE_MS].
 */
@Singleton
class AppLock @Inject constructor() : DefaultLifecycleObserver {

    private val unlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = unlocked.asStateFlow()

    private var backgroundSince = 0L

    /** Вызывается один раз из Application. */
    fun attach() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        backgroundSince = SystemClock.elapsedRealtime()
    }

    override fun onStart(owner: LifecycleOwner) {
        if (backgroundSince != 0L && SystemClock.elapsedRealtime() - backgroundSince > GRACE_MS) unlocked.value = false
    }

    fun unlock() {
        unlocked.value = true
    }

    companion object {
        const val GRACE_MS = 30_000L
    }
}
