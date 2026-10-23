package io.quietfiles.feature.player.music

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import dagger.hilt.android.qualifiers.ApplicationContext
import io.quietfiles.core.common.ApplicationScope
import io.quietfiles.core.data.repository.PlaybackPositions
import io.quietfiles.core.model.FileCategory
import io.quietfiles.core.model.FileEntry
import io.quietfiles.core.model.FileNames
import io.quietfiles.core.model.FileTypes
import io.quietfiles.core.model.NaturalOrder
import io.quietfiles.feature.player.R
import io.quietfiles.feature.player.common.AudioInfoReader
import io.quietfiles.feature.player.common.SleepMode
import io.quietfiles.feature.player.common.SleepTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Файл в очереди воспроизведения. */
data class QueueItem(val path: String, val name: String, val size: Long)

data class MusicState(
    val queue: List<QueueItem> = emptyList(),
    val index: Int = -1,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    /** Название и исполнитель из тегов; без тегов — имя файла. */
    val title: String? = null,
    val artist: String? = null,
    val artworkData: ByteArray? = null,
    val artworkUri: Uri? = null,
    val error: String? = null,
) {
    val current: QueueItem? get() = queue.getOrNull(index)
    val active: Boolean get() = current != null
}

/**
 * Музыкальный плеер (F11): один ExoPlayer и одна MediaSession на всё приложение. Экраны управляют им
 * напрямую, а [PlaybackService] держит его в фоне — уведомление, экран блокировки, гарнитура.
 * Все вызовы — с главного потока.
 */
@OptIn(UnstableApi::class)
@Singleton
class MusicPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val appScope: CoroutineScope,
    private val positions: PlaybackPositions,
    private val sleepTimer: SleepTimer,
    private val audioInfo: AudioInfoReader,
) {
    private val _state = MutableStateFlow(MusicState())
    val state: StateFlow<MusicState> = _state.asStateFlow()

    private var exo: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private val scope = CoroutineScope(appScope.coroutineContext + Dispatchers.Main.immediate)

    /** Сессия для [PlaybackService]; создаётся вместе с плеером. */
    val session: MediaSession get() = ensurePlayer().let { mediaSession!! }

    val player: Player? get() = exo

    init {
        scope.launch { sleepTimer.fired.collect { exo?.pause() } }
        scope.launch {
            sleepTimer.mode.collect { mode -> exo?.pauseAtEndOfMediaItems = mode == SleepMode.EndOfItem }
        }
    }

    private fun ensurePlayer(): ExoPlayer = exo ?: ExoPlayer.Builder(context)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .build()
        .also { player ->
            exo = player
            player.addListener(listener)
            mediaSession = MediaSession.Builder(context, player)
                .setId(SESSION_ID)
                .setSessionActivity(openPlayerIntent())
                .build()
            player.pauseAtEndOfMediaItems = sleepTimer.mode.value == SleepMode.EndOfItem
            openEqualizerSession(player.audioSessionId)
            scope.launch { savePositionsWhilePlaying() }
        }

    /**
     * Открыть аудиофайл (F3.10): очередь — аудио из списка, где его открыли, а если там он один
     * (недавние, поиск) — вся папка по имени.
     */
    fun open(entry: FileEntry, siblings: List<FileEntry>) {
        scope.launch {
            val audio = siblings.filter { it.category == FileCategory.AUDIO }
            val queue = if (audio.size > 1 && audio.any { it.path == entry.path }) {
                audio
            } else {
                withContext(Dispatchers.IO) { folderAudio(entry) }
            }
            play(queue, queue.indexOfFirst { it.path == entry.path }.coerceAtLeast(0))
        }
    }

    private fun folderAudio(entry: FileEntry): List<FileEntry> {
        val dir = File(entry.path).parentFile ?: return listOf(entry)
        val files = dir.listFiles { f -> f.isFile && !f.isHidden && FileTypes.categoryOf(f.name) == FileCategory.AUDIO } ?: return listOf(entry)
        return files.map { FileEntry(it.path, it.name, false, it.length(), it.lastModified()) }
            .sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
            .ifEmpty { listOf(entry) }
    }

    /** Играть [files] начиная с [start] (F11.1): очередь — эти файлы, обычно аудио из той же папки. */
    fun play(files: List<FileEntry>, start: Int) {
        if (files.isEmpty()) return
        val player = ensurePlayer()
        saveCurrentPosition()
        val queue = files.map { QueueItem(it.path, it.name, it.size) }
        player.setMediaItems(queue.map(::mediaItemOf), start.coerceIn(0, queue.lastIndex), C.TIME_UNSET)
        player.prepare()
        player.play()
        _state.update { it.copy(queue = queue, error = null) }
        context.startService(Intent(context, PlaybackService::class.java))
        syncState()
        onItemChanged()
    }

    fun togglePlay() {
        val player = exo ?: return
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0, 0)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        if (player.isPlaying) player.pause() else player.play()
    }

    fun next() = exo?.seekToNext()
    fun previous() = exo?.seekToPrevious()
    fun seekTo(positionMs: Long) = exo?.seekTo(positionMs)
    fun playAt(index: Int) {
        val player = exo ?: return
        player.seekTo(index, 0)
        player.play()
    }

    fun toggleShuffle() {
        val player = exo ?: return
        player.shuffleModeEnabled = !player.shuffleModeEnabled
    }

    /** Повтор: выкл → все → один → выкл (F11.1). */
    fun cycleRepeat() {
        val player = exo ?: return
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /** Перетаскивание в очереди. */
    fun move(from: Int, to: Int) {
        val player = exo ?: return
        val queue = _state.value.queue
        if (from !in queue.indices || to !in queue.indices || from == to) return
        player.moveMediaItem(from, to)
        _state.update { s -> s.copy(queue = s.queue.toMutableList().apply { add(to, removeAt(from)) }) }
        syncState()
    }

    fun remove(index: Int) {
        val player = exo ?: return
        if (index !in _state.value.queue.indices) return
        if (_state.value.queue.size == 1) {
            stop()
            return
        }
        player.removeMediaItem(index)
        _state.update { s -> s.copy(queue = s.queue.toMutableList().apply { removeAt(index) }) }
        syncState()
    }

    /** Остановить и очистить очередь: мини-плеер исчезает, уведомление снимается. */
    fun stop() {
        saveCurrentPosition()
        exo?.let {
            it.stop()
            it.clearMediaItems()
        }
        _state.value = MusicState()
        context.stopService(Intent(context, PlaybackService::class.java))
    }

    /** Текущая позиция для шкалы: опрашиваем, пока экран её показывает. */
    fun positionFlow(): Flow<Long> = flow {
        while (currentCoroutineContext().isActive) {
            emit(exo?.currentPosition ?: 0)
            delay(TICK_MS)
        }
    }

    /** Системный эквалайзер (F11.5), если на устройстве есть его экран. */
    fun equalizerIntent(): Intent? {
        val session = exo?.audioSessionId ?: return null
        val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
            .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, session)
            .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        return intent.takeIf { it.resolveActivity(context.packageManager) != null }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncState()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            onItemChanged()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) saveCurrentPosition()
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) sleepTimer.onItemEnded()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) sleepTimer.onItemEnded()
        }

        override fun onPlayerError(error: PlaybackException) {
            val name = _state.value.current?.name.orEmpty()
            _state.update { it.copy(error = context.getString(R.string.music_error, name)) }
            // Повреждённый файл не должен останавливать всю очередь.
            val player = exo ?: return
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            openEqualizerSession(audioSessionId)
        }
    }

    private fun syncState() {
        val player = exo ?: return
        val metadata = player.mediaMetadata
        _state.update { s ->
            s.copy(
                index = if (s.queue.isEmpty()) -1 else player.currentMediaItemIndex,
                isPlaying = player.isPlaying,
                buffering = player.playbackState == Player.STATE_BUFFERING,
                durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0,
                shuffle = player.shuffleModeEnabled,
                repeatMode = player.repeatMode,
                title = metadata.title?.toString(),
                artist = metadata.artist?.toString(),
                artworkData = metadata.artworkData,
                artworkUri = metadata.artworkUri,
            )
        }
    }

    /**
     * Новый трек: теги и обложка из папки дописываются в элемент очереди (их видит и уведомление),
     * длинная запись продолжается с сохранённого места.
     */
    private fun onItemChanged() {
        val player = exo ?: return
        val index = player.currentMediaItemIndex
        val item = player.currentMediaItem ?: return
        val path = item.mediaId
        if (_state.value.error != null) _state.update { it.copy(error = null) }
        scope.launch {
            val info = audioInfo.read(path)
            positions.get(path)?.let { saved ->
                if (player.currentMediaItem?.mediaId == path && player.currentPosition < saved) player.seekTo(saved)
            }
            if (index >= player.mediaItemCount || player.getMediaItemAt(index).mediaId != path) return@launch
            val metadata = item.mediaMetadata.buildUpon()
                .setTitle(info.title ?: item.mediaMetadata.title)
                .setArtist(info.artist)
                .setAlbumTitle(info.album)
                .apply { if (!info.hasEmbeddedCover) info.folderCover?.let { setArtworkUri(Uri.fromFile(it)) } }
                .build()
            player.replaceMediaItem(index, item.buildUpon().setMediaMetadata(metadata).build())
        }
    }

    private fun saveCurrentPosition() {
        val player = exo ?: return
        val path = player.currentMediaItem?.mediaId ?: return
        val position = player.currentPosition
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: return
        appScope.launch { positions.save(path, position, duration, audio = true) }
    }

    private suspend fun savePositionsWhilePlaying() {
        while (true) {
            delay(SAVE_EVERY_MS)
            if (exo?.isPlaying == true) saveCurrentPosition()
        }
    }

    /** Сообщаем системе о сессии звука, чтобы её эквалайзер и эффекты применялись к плееру. */
    private fun openEqualizerSession(sessionId: Int) {
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        context.sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC),
        )
    }

    private fun openPlayerIntent(): PendingIntent {
        val intent = (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent())
            .setAction(ACTION_OPEN_PLAYER)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun mediaItemOf(item: QueueItem): MediaItem = MediaItem.Builder()
        .setMediaId(item.path)
        .setUri(Uri.fromFile(File(item.path)))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(FileNames.nameOf(item.path).substringBeforeLast('.'))
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .build(),
        )
        .build()

    companion object {
        /** Действие для MainActivity: открыть экран плеера (уведомление, мини-плеер). */
        const val ACTION_OPEN_PLAYER = "io.quietfiles.action.OPEN_PLAYER"
        private const val SESSION_ID = "music"
        private const val TICK_MS = 250L
        private const val SAVE_EVERY_MS = 15_000L
    }
}
