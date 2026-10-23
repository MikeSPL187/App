package io.quietfiles.feature.player.common

import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import io.quietfiles.core.common.IoDispatcher
import io.quietfiles.core.model.FileNames
import io.quietfiles.core.model.media.AudioHeaderProbe
import io.quietfiles.core.model.media.AudioStreamInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Сведения об аудиофайле для очереди и экрана плеера: длительность, теги, битность, обложка. */
data class AudioInfo(
    val durationMs: Long,
    val title: String?,
    val artist: String?,
    val album: String?,
    val stream: AudioStreamInfo?,
    /** Картинка `cover.jpg`/`folder.jpg` в папке, если в самом файле обложки нет (F11.4). */
    val folderCover: File?,
    val hasEmbeddedCover: Boolean,
)

@Singleton
class AudioInfoReader @Inject constructor(@IoDispatcher private val io: CoroutineDispatcher) {

    private val cache = LruCache<String, AudioInfo>(CACHE_SIZE)

    fun cached(path: String): AudioInfo? = cache.get(path)

    suspend fun read(path: String): AudioInfo = cache.get(path) ?: withContext(io) {
        readNow(path).also { cache.put(path, it) }
    }

    /** Встроенная обложка: читаем по запросу, в кэше не держим. */
    suspend fun embeddedCover(path: String): ByteArray? = withContext(io) {
        retriever(path) { it.embeddedPicture }
    }

    private fun readNow(path: String): AudioInfo {
        var info: AudioInfo? = null
        retriever(path) { r ->
            val sampleRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) r.extractInt(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE) else 0
            val bits = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) r.extractInt(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE) else 0
            val probed = runCatching { File(path).inputStream().use(AudioHeaderProbe::probe) }.getOrNull()
            val stream = when {
                probed != null -> probed
                sampleRate > 0 -> AudioStreamInfo(sampleRate, bits, 0)
                else -> null
            }
            info = AudioInfo(
                durationMs = r.extractInt(MediaMetadataRetriever.METADATA_KEY_DURATION).toLong().takeIf { it > 0 } ?: probed?.durationMs ?: 0,
                title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()?.ifEmpty { null },
                artist = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST))
                    ?.trim()?.ifEmpty { null },
                album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim()?.ifEmpty { null },
                stream = stream,
                folderCover = folderCover(path),
                hasEmbeddedCover = r.embeddedPicture != null,
            )
        }
        return info ?: AudioInfo(
            durationMs = 0, title = null, artist = null, album = null,
            stream = runCatching { File(path).inputStream().use(AudioHeaderProbe::probe) }.getOrNull(),
            folderCover = folderCover(path), hasEmbeddedCover = false,
        )
    }

    private inline fun <T> retriever(path: String, block: (MediaMetadataRetriever) -> T): T? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            block(r)
        } catch (e: RuntimeException) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun MediaMetadataRetriever.extractInt(key: Int): Int = extractMetadata(key)?.toIntOrNull() ?: 0

    companion object {
        private const val CACHE_SIZE = 500
        private val COVER_NAMES = listOf("cover", "folder", "front", "albumart", "album")
        private val COVER_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

        /** Обложка альбома в папке: cover.jpg, folder.png и т. п. без учёта регистра. */
        fun folderCover(path: String): File? {
            val dir = File(path).parentFile ?: return null
            val files = dir.listFiles { f -> f.isFile && FileNames.extension(f.name).lowercase() in COVER_EXTENSIONS } ?: return null
            return COVER_NAMES.firstNotNullOfOrNull { name ->
                files.firstOrNull { it.name.substringBeforeLast('.').equals(name, ignoreCase = true) }
            }
        }
    }
}
