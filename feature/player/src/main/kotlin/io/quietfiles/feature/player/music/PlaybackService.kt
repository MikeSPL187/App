package io.quietfiles.feature.player.music

import android.content.Intent
import androidx.media3.common.Player
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Фоновое воспроизведение музыки (F11.2): уведомление с управлением, экран блокировки, гарнитура
 * и Bluetooth. Сервис экспортирован, как требует MediaSessionService, но подключиться к сессии
 * могут только само приложение и системные контроллеры (S7).
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var music: MusicPlayer

    override fun onCreate() {
        super.onCreate()
        addSession(music.session)
    }

    @OptIn(UnstableApi::class)
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        val trusted = controllerInfo.packageName == packageName || controllerInfo.isTrusted
        return if (trusted) music.session else null
    }

    /** Смахнули приложение из недавних: если музыка на паузе, уходим, иначе играем дальше. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = music.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Сессия и плеер живут в MusicPlayer: экран может снова запустить воспроизведение.
        removeSession(music.session)
        super.onDestroy()
    }
}
