package io.quietfiles.feature.player.music

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.quietfiles.core.common.volumeShortName
import io.quietfiles.core.data.repository.FavoritesRepository
import io.quietfiles.core.model.FileEntry
import io.quietfiles.core.model.FileNames
import io.quietfiles.core.model.media.MediaLabels
import io.quietfiles.core.storage.StorageVolumes
import io.quietfiles.feature.player.R
import io.quietfiles.feature.player.common.AudioInfo
import io.quietfiles.feature.player.common.AudioInfoReader
import io.quietfiles.feature.player.common.SleepMode
import io.quietfiles.feature.player.common.SleepTimer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** Экраны «Сейчас играет», «Очередь» и мини-плеер (F11, макеты 22, 23). */
@HiltViewModel
class MusicViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val music: MusicPlayer,
    private val reader: AudioInfoReader,
    private val favorites: FavoritesRepository,
    private val volumes: StorageVolumes,
    val sleepTimer: SleepTimer,
) : ViewModel() {

    val state: StateFlow<MusicState> = music.state

    val position: Flow<Long> get() = music.positionFlow()

    /** Сведения о файлах очереди: длительность, формат. Подгружаются в фоне. */
    private val _infos = MutableStateFlow<Map<String, AudioInfo>>(emptyMap())
    val infos: StateFlow<Map<String, AudioInfo>> = _infos.asStateFlow()

    val isFavorite: StateFlow<Boolean> =
        combine(favorites.paths, state.map { it.current?.path }.distinctUntilChanged()) { paths, path -> path != null && path in paths }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val sleepMode: StateFlow<SleepMode> = sleepTimer.mode

    init {
        viewModelScope.launch {
            state.map { s -> s.queue.map { it.path } }.distinctUntilChanged().collect { paths ->
                // Сначала текущий трек, затем остальные — так экран плеера заполняется сразу.
                val current = state.value.current?.path
                val ordered = listOfNotNull(current) + paths.filter { it != current }
                for (path in ordered.take(MAX_INFOS)) {
                    if (path in _infos.value) continue
                    val info = reader.read(path)
                    _infos.update { it + (path to info) }
                }
            }
        }
    }

    fun togglePlay() = music.togglePlay()
    fun next() = music.next()
    fun previous() = music.previous()
    fun seekTo(ms: Long) = music.seekTo(ms)
    fun playAt(index: Int) = music.playAt(index)
    fun toggleShuffle() = music.toggleShuffle()
    fun cycleRepeat() = music.cycleRepeat()
    fun move(from: Int, to: Int) = music.move(from, to)
    fun remove(index: Int) = music.remove(index)
    fun stop() = music.stop()
    fun equalizerIntent(): Intent? = music.equalizerIntent()

    fun toggleFavorite() {
        val item = state.value.current ?: return
        viewModelScope.launch {
            if (isFavorite.value) {
                favorites.remove(item.path)
            } else {
                val file = File(item.path)
                favorites.add(FileEntry(item.path, item.name, false, file.length(), file.lastModified()))
            }
        }
    }

    fun setSleep(minutes: Int?) {
        when (minutes) {
            null -> sleepTimer.cancel()
            END_OF_TRACK -> sleepTimer.stopAtEndOfItem()
            else -> sleepTimer.start(minutes)
        }
    }

    /** «SD-карта › Музыка» для шапки плеера. */
    fun folderLabel(path: String): String {
        val parent = FileNames.parentOf(path) ?: return ""
        val volume = volumes.volumeFor(parent) ?: return FileNames.nameOf(parent)
        val volumeName = context.volumeShortName(volume)
        return if (parent.trimEnd('/') == volume.rootPath.trimEnd('/')) volumeName else "$volumeName › ${FileNames.nameOf(parent)}"
    }

    /** «FLAC · 24 бит / 96 кГц». */
    fun qualityLabel(item: QueueItem, info: AudioInfo?): String = MediaLabels.audioQuality(
        MediaLabels.formatFromName(item.name),
        info?.stream,
        context.getString(R.string.music_bits),
        context.getString(R.string.music_khz),
    )

    companion object {
        const val END_OF_TRACK = -1
        private const val MAX_INFOS = 500
    }
}
