package io.quietfiles.feature.player.video

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.OptIn
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.quietfiles.core.common.ApplicationScope
import io.quietfiles.core.common.IoDispatcher
import io.quietfiles.core.common.ViewerSessions
import io.quietfiles.core.data.repository.PlaybackPositions
import io.quietfiles.core.model.FileCategory
import io.quietfiles.core.model.FileNames
import io.quietfiles.core.model.FileTypes
import io.quietfiles.core.model.NaturalOrder
import io.quietfiles.core.model.media.MediaLabels
import io.quietfiles.core.model.media.PlayerGestures
import io.quietfiles.core.model.media.SidecarSubtitle
import io.quietfiles.core.model.media.SubtitleFinder
import io.quietfiles.feature.player.R
import io.quietfiles.feature.player.common.END_OF_ITEM
import io.quietfiles.feature.player.common.SleepMode
import io.quietfiles.feature.player.common.SleepTimer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.Locale
import javax.inject.Inject

/** Видеоплеер (F9). [session] — список соседних файлов из [ViewerSessions] для «Далее». */
@Serializable
data class VideoRoute(val path: String, val session: Long = 0)

/** Масштаб кадра (F9.4). */
enum class VideoScale { FIT, FILL, STRETCH }

/** Дорожка субтитров или звука для выбора в настройках. [key] — «группа:дорожка» в текущих Tracks. */
data class TrackOption(val key: String, val label: String, val detail: String?, val selected: Boolean)

data class VideoUiState(
    val path: String,
    val size: Long = 0,
    val playlist: List<String> = emptyList(),
    val isPlaying: Boolean = false,
    val buffering: Boolean = true,
    val durationMs: Long = 0,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val videoCodec: String? = null,
    val speed: Float = 1f,
    val scale: VideoScale = VideoScale.FIT,
    val locked: Boolean = false,
    val subtitles: List<TrackOption> = emptyList(),
    val audioTracks: List<TrackOption> = emptyList(),
    /** Продолжили с этого места (F9.5): показываем «Продолжено с 12:41 · С начала». */
    val resumedFrom: Long? = null,
    val error: String? = null,
) {
    val name: String get() = FileNames.nameOf(path)
    val next: String? get() = playlist.getOrNull(playlist.indexOf(path) + 1)
    val subtitlesOff: Boolean get() = subtitles.none { it.selected }
}

@HiltViewModel
class VideoViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    sessions: ViewerSessions,
    private val positions: PlaybackPositions,
    val sleepTimer: SleepTimer,
    @IoDispatcher private val io: CoroutineDispatcher,
    /** Позиция сохраняется и после закрытия экрана, когда viewModelScope уже отменён. */
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<VideoRoute>()
    private val sessionPaths = sessions.get(route.session)

    private val _state = MutableStateFlow(VideoUiState(path = savedStateHandle.get<String>(KEY_PATH) ?: route.path))
    val state: StateFlow<VideoUiState> = _state.asStateFlow()

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .setSeekBackIncrementMs(PlayerGestures.DOUBLE_TAP_SEEK_MS)
        .setSeekForwardIncrementMs(PlayerGestures.DOUBLE_TAP_SEEK_MS)
        .build()

    /** Сессия даёт кнопки в «картинке в картинке» и управление с гарнитуры. */
    private val mediaSession = MediaSession.Builder(context, player).setId("video-${System.nanoTime()}").build()

    /** Короткие сообщения для экрана. */
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Субтитры, загруженные вручную (F9.4): content:// из системного выбора файла. */
    private val manualSubtitles = mutableListOf<MediaItem.SubtitleConfiguration>()
    private var selectSubtitleId: String? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            _state.update {
                it.copy(
                    isPlaying = player.isPlaying,
                    buffering = player.playbackState == Player.STATE_BUFFERING || (player.playbackState == Player.STATE_IDLE && it.error == null),
                    durationMs = player.duration.takeIf { d -> d != C.TIME_UNSET } ?: it.durationMs,
                )
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) savePosition()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0) _state.update { it.copy(videoWidth = videoSize.width, videoHeight = videoSize.height) }
        }

        override fun onTracksChanged(tracks: Tracks) {
            updateTracks(tracks)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_ENDED) return
            val path = _state.value.path
            appScope.launch { positions.forget(path) }
            val stopHere = sleepTimer.mode.value == SleepMode.EndOfItem
            sleepTimer.onItemEnded()
            if (!stopHere && _state.value.next != null) playNext()
        }

        override fun onPlayerError(error: PlaybackException) {
            val message = when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
                    context.getString(R.string.video_error_file)
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                -> context.getString(R.string.video_error_format)
                else -> context.getString(R.string.video_error_generic)
            }
            _state.update { it.copy(error = message, buffering = false) }
        }
    }

    init {
        player.addListener(listener)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguage(Locale.getDefault().language)
            .setPreferredAudioLanguage(Locale.getDefault().language)
            .build()
        viewModelScope.launch {
            val playlist = withContext(io) { playlistFor(_state.value.path) }
            _state.update { it.copy(playlist = playlist) }
            open(_state.value.path, autoPlay = true)
        }
        viewModelScope.launch { sleepTimer.fired.collect { player.pause() } }
        viewModelScope.launch {
            while (true) {
                delay(SAVE_EVERY_MS)
                if (player.isPlaying) savePosition()
            }
        }
    }

    /** Видео той же папки по имени (F9.6) или список, из которого открыли (галерея, поиск). */
    private fun playlistFor(path: String): List<String> {
        val fromSession = sessionPaths?.filter { FileTypes.categoryOf(FileNames.nameOf(it)) == FileCategory.VIDEO }
        if (!fromSession.isNullOrEmpty() && path in fromSession) return fromSession
        val dir = File(path).parentFile ?: return listOf(path)
        return (dir.listFiles { f -> f.isFile && !f.isHidden && FileTypes.categoryOf(f.name) == FileCategory.VIDEO } ?: emptyArray())
            .map { it.path }
            .sortedWith { a, b -> NaturalOrder.compare(FileNames.nameOf(a), FileNames.nameOf(b)) }
            .ifEmpty { listOf(path) }
    }

    private suspend fun open(path: String, autoPlay: Boolean) {
        val siblings = withContext(io) { File(path).parentFile?.listFiles()?.map { it.path }.orEmpty() }
        val sidecars = SubtitleFinder.find(path, siblings)
        val saved = positions.get(path)
        val file = File(path)
        savedStateHandle[KEY_PATH] = path
        _state.update {
            it.copy(
                path = path, size = file.length(), durationMs = 0, videoWidth = 0, videoHeight = 0, videoCodec = null,
                subtitles = emptyList(), audioTracks = emptyList(), resumedFrom = saved, error = null, buffering = true,
            )
        }
        manualSubtitles.clear()
        player.setMediaItem(mediaItem(path, sidecars), saved ?: 0)
        player.prepare()
        player.playWhenReady = autoPlay
    }

    private fun mediaItem(path: String, sidecars: List<SidecarSubtitle>): MediaItem {
        val subtitles = sidecars.map { sub ->
            MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(File(sub.path)))
                .setId("$SIDECAR${sub.path}")
                .setMimeType(sub.mimeType)
                .setLanguage(sub.language)
                .setLabel(sub.language?.let(::languageName) ?: FileNames.nameOf(sub.path))
                // «Фильм.srt» рядом с «Фильм.mp4» положили, чтобы смотреть с ним: включаем сразу.
                .setSelectionFlags(if (sub.language == null && sub == sidecars.first()) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
        return MediaItem.Builder()
            .setMediaId(path)
            .setUri(Uri.fromFile(File(path)))
            .setSubtitleConfigurations(subtitles + manualSubtitles)
            .build()
    }

    // ---- Управление ----

    fun togglePlay() {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        if (player.isPlaying) player.pause() else player.play()
    }

    fun pause() = player.pause()
    fun seekBy(deltaMs: Long) = player.seekTo((player.currentPosition + deltaMs).coerceIn(0, durationOrMax()))
    fun seekTo(ms: Long) = player.seekTo(ms.coerceIn(0, durationOrMax()))
    private fun durationOrMax() = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE

    fun restart() {
        player.seekTo(0)
        player.play()
        _state.update { it.copy(resumedFrom = null) }
        viewModelScope.launch { positions.forget(_state.value.path) }
    }

    fun dismissResume() = _state.update { it.copy(resumedFrom = null) }

    fun setSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
        _state.update { it.copy(speed = speed) }
    }

    fun setScale(scale: VideoScale) = _state.update { it.copy(scale = scale) }

    fun cycleScale() = setScale(VideoScale.entries[(_state.value.scale.ordinal + 1) % VideoScale.entries.size])

    fun setLocked(locked: Boolean) = _state.update { it.copy(locked = locked) }

    fun playNext() {
        val next = _state.value.next ?: return
        savePosition()
        viewModelScope.launch { open(next, autoPlay = true) }
    }

    fun selectSubtitle(key: String?) {
        val builder = player.trackSelectionParameters.buildUpon()
        if (key == null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            val (group, track) = groupFor(key) ?: return
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track))
        }
        player.trackSelectionParameters = builder.build()
    }

    fun selectAudio(key: String) {
        val (group, track) = groupFor(key) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track))
            .build()
    }

    private fun groupFor(key: String): Pair<Tracks.Group, Int>? {
        val (g, t) = key.split(':').map { it.toIntOrNull() ?: return null }
        val group = player.currentTracks.groups.getOrNull(g) ?: return null
        return if (t < group.length) group to t else null
    }

    /** Субтитры из выбранного файла: плеер пересобирает источник и продолжает с того же места. */
    fun addSubtitle(uri: Uri) {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()
        val mime = SubtitleFinder.mimeTypeOf(name)
        if (mime == null) {
            messages.tryEmit(context.getString(R.string.video_subtitle_unsupported))
            return
        }
        val id = "$MANUAL${manualSubtitles.size}:$name"
        manualSubtitles += MediaItem.SubtitleConfiguration.Builder(uri).setId(id).setMimeType(mime).setLabel(name).build()
        selectSubtitleId = id
        val position = player.currentPosition
        val playing = player.playWhenReady
        viewModelScope.launch {
            val path = _state.value.path
            val siblings = withContext(io) { File(path).parentFile?.listFiles()?.map { it.path }.orEmpty() }
            player.setMediaItem(mediaItem(path, SubtitleFinder.find(path, siblings)), position)
            player.prepare()
            player.playWhenReady = playing
        }
    }

    fun setSleep(minutes: Int?) {
        when (minutes) {
            null -> sleepTimer.cancel()
            END_OF_ITEM -> sleepTimer.stopAtEndOfItem()
            else -> sleepTimer.start(minutes)
        }
    }

    /** Позиция и буфер для шкалы, пока экран на неё смотрит. */
    fun progress(): Flow<Pair<Long, Long>> = flow {
        while (currentCoroutineContext().isActive) {
            emit(player.currentPosition to player.bufferedPosition)
            delay(TICK_MS)
        }
    }

    fun onStop() {
        savePosition()
    }

    private fun savePosition() {
        val path = _state.value.path
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: return
        val position = player.currentPosition
        appScope.launch { positions.save(path, position, duration) }
    }

    @OptIn(UnstableApi::class)
    private fun updateTracks(tracks: Tracks) {
        val subtitles = mutableListOf<TrackOption>()
        val audio = mutableListOf<TrackOption>()
        var videoUnsupported = false
        var hasVideo = false
        var pending: String? = null
        tracks.groups.forEachIndexed { g, group ->
            for (t in 0 until group.length) {
                val format = group.getTrackFormat(t)
                val key = "$g:$t"
                when (group.type) {
                    C.TRACK_TYPE_VIDEO -> {
                        hasVideo = true
                        if (group.isTrackSupported(t)) {
                            if (group.isTrackSelected(t)) {
                                // Размер кадра известен из контейнера ещё до первого кадра.
                                val turned = format.rotationDegrees % 180 != 0
                                _state.update {
                                    it.copy(
                                        videoCodec = MediaLabels.videoCodec(format.sampleMimeType),
                                        videoWidth = it.videoWidth.takeIf { w -> w > 0 } ?: if (turned) format.height else format.width,
                                        videoHeight = it.videoHeight.takeIf { h -> h > 0 } ?: if (turned) format.width else format.height,
                                    )
                                }
                            }
                        } else {
                            videoUnsupported = true
                        }
                    }
                    C.TRACK_TYPE_AUDIO -> if (group.isTrackSupported(t)) {
                        audio += TrackOption(key, trackName(format, audio.size + 1), audioDetail(format), group.isTrackSelected(t))
                    }
                    C.TRACK_TYPE_TEXT -> if (group.isTrackSupported(t)) {
                        val id = format.id.orEmpty()
                        val detail = when {
                            SIDECAR in id -> context.getString(R.string.video_subtitles_sidecar, FileNames.extension(id.substringAfter(SIDECAR)).uppercase())
                            MANUAL in id -> context.getString(R.string.video_subtitles_loaded)
                            else -> context.getString(R.string.video_subtitles_embedded)
                        }
                        subtitles += TrackOption(key, trackName(format, subtitles.size + 1), detail, group.isTrackSelected(t))
                        if (selectSubtitleId != null && id.endsWith(selectSubtitleId!!)) pending = key
                    }
                }
            }
        }
        _state.update {
            it.copy(
                subtitles = subtitles,
                audioTracks = audio,
                error = if (hasVideo && videoUnsupported && it.videoCodec == null) context.getString(R.string.video_error_format) else it.error,
            )
        }
        pending?.let {
            selectSubtitleId = null
            selectSubtitle(it)
        }
    }

    /** «Русский», «English», подпись из файла или «Дорожка 2». */
    private fun trackName(format: Format, number: Int): String =
        format.label?.takeIf { it.isNotBlank() }
            ?: format.language?.takeIf { it != C.LANGUAGE_UNDETERMINED }?.let(::languageName)
            ?: context.getString(R.string.video_track_number, number)

    /** «AAC 5.1». */
    private fun audioDetail(format: Format): String? =
        listOfNotNull(MediaLabels.audioCodec(format.sampleMimeType), MediaLabels.channels(format.channelCount)).joinToString(" ").ifEmpty { null }

    private fun languageName(code: String): String {
        val locale = Locale.forLanguageTag(code)
        val name = locale.getDisplayLanguage(locale).ifEmpty { code }
        return name.replaceFirstChar { it.titlecase(locale) }
    }

    override fun onCleared() {
        savePosition()
        mediaSession.release()
        player.removeListener(listener)
        player.release()
    }

    private companion object {
        const val KEY_PATH = "video_path"
        const val SIDECAR = "sidecar:"
        const val MANUAL = "manual"
        const val TICK_MS = 250L
        const val SAVE_EVERY_MS = 10_000L
    }
}
