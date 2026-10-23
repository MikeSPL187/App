package io.quietfiles.feature.player.common

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import io.quietfiles.core.model.network.NetworkPaths
import io.quietfiles.core.storage.StorageProvider
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InputStream

/**
 * Файлы с серверов (F16.7) для ExoPlayer: читаются потоком через [StorageProvider], без загрузки целиком;
 * перемотка открывает чтение с нужного места (Range в WebDAV, REST в FTP, смещение в SFTP и SMB).
 * Локальные файлы и content:// идут обычным [DefaultDataSource].
 */
@OptIn(UnstableApi::class)
class StorageDataSource(private val provider: StorageProvider) : BaseDataSource(true) {
    private var uri: Uri? = null
    private var input: InputStream? = null
    private var left = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        val path = dataSpec.uri.path ?: throw DataSourceException(DataSourceException.POSITION_OUT_OF_RANGE)
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val size = try {
            runBlocking { provider.stat(path)?.size } ?: throw IOException("Not found: $path")
        } catch (e: IOException) {
            throw DataSourceException(e, androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        } catch (e: Exception) {
            throw DataSourceException(e, androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        if (dataSpec.position > size) throw DataSourceException(DataSourceException.POSITION_OUT_OF_RANGE)
        input = try {
            provider.openRead(path, dataSpec.position)
        } catch (e: Exception) {
            throw DataSourceException(e, androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        left = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else size - dataSpec.position
        transferStarted(dataSpec)
        return left
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (left == 0L) return C.RESULT_END_OF_INPUT
        val stream = input ?: throw IOException("Not opened")
        val n = try {
            stream.read(buffer, offset, minOf(length.toLong(), left).toInt())
        } catch (e: IOException) {
            throw DataSourceException(e, androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        if (n < 0) return C.RESULT_END_OF_INPUT
        left -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        val stream = input
        input = null
        uri = null
        try {
            stream?.close()
        } finally {
            if (stream != null) transferEnded()
        }
    }

    /** Источник, который сам выбирает: сервер или обычный файл. */
    private class Routing(private val local: DataSource, private val network: StorageDataSource) : DataSource by local {
        private var current: DataSource = local
        override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
            local.addTransferListener(transferListener)
            network.addTransferListener(transferListener)
        }
        override fun open(dataSpec: DataSpec): Long {
            current = if (dataSpec.uri.scheme == "file" && NetworkPaths.isNetwork(dataSpec.uri.path.orEmpty())) network else local
            return current.open(dataSpec)
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current.read(buffer, offset, length)
        override fun getUri(): Uri? = current.uri
        override fun getResponseHeaders(): Map<String, List<String>> = current.responseHeaders
        override fun close() = current.close()
    }

    companion object {
        fun factory(context: Context, provider: StorageProvider): DataSource.Factory {
            val local = DefaultDataSource.Factory(context)
            return DataSource.Factory { Routing(local.createDataSource(), StorageDataSource(provider)) }
        }

        fun mediaSourceFactory(context: Context, provider: StorageProvider): MediaSource.Factory =
            DefaultMediaSourceFactory(factory(context, provider))
    }
}

/**
 * Чтение тегов файла с сервера (MediaMetadataRetriever): поток открывается заново только при прыжке,
 * а теги обычно лежат в начале и в конце файла.
 */
class StorageMediaDataSource(private val provider: StorageProvider, private val path: String, private val length: Long) : MediaDataSource() {
    private var stream: InputStream? = null
    private var position = 0L

    @Synchronized
    override fun readAt(offset: Long, buffer: ByteArray, start: Int, size: Int): Int {
        if (offset >= length) return -1
        if (stream == null || offset != position) {
            stream?.close()
            stream = provider.openRead(path, offset)
            position = offset
        }
        val n = stream!!.read(buffer, start, size)
        if (n > 0) position += n
        return n
    }

    override fun getSize(): Long = length

    @Synchronized
    override fun close() {
        stream?.close()
        stream = null
    }
}
