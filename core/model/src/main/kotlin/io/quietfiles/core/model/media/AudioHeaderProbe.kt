package io.quietfiles.core.model.media

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

/** Параметры аудиопотока для подписи «FLAC · 24 бит / 96 кГц» (F11.4). */
data class AudioStreamInfo(
    val sampleRate: Int,
    val bitsPerSample: Int,
    val channels: Int,
    val durationMs: Long = 0,
)

/**
 * Битность и частота из заголовков FLAC и WAV. На Android 12+ то же отдаёт MediaMetadataRetriever,
 * но на Android 11 его ключей битности нет, а lossless-файлы — именно те, где она важна.
 */
object AudioHeaderProbe {

    /** Читает не больше нескольких килобайт начала файла; null — формат не распознан. */
    fun probe(input: InputStream): AudioStreamInfo? = runCatching {
        val data = DataInputStream(input.buffered())
        val magic = ByteArray(4).also { data.readFully(it) }
        when {
            magic.ascii() == "fLaC" -> readFlac(data)
            magic.ascii().startsWith("ID3") -> {
                skipId3(data, magic)
                val next = ByteArray(4).also { data.readFully(it) }
                if (next.ascii() == "fLaC") readFlac(data) else null
            }
            magic.ascii() == "RIFF" -> readWav(data)
            else -> null
        }
    }.getOrNull()

    /** ID3v2 перед FLAC: размер записан «syncsafe» — по 7 бит в байте. */
    private fun skipId3(data: DataInputStream, head: ByteArray) {
        // head = "ID3" + major version; дальше: minor version, flags, 4 байта размера.
        check(head[3].toInt() in 2..4)
        data.readUnsignedByte()
        val flags = data.readUnsignedByte()
        var size = 0
        repeat(4) { size = (size shl 7) or (data.readUnsignedByte() and 0x7F) }
        if (flags and 0x10 != 0) size += 10
        data.skipFully(size.toLong())
    }

    private fun readFlac(data: DataInputStream): AudioStreamInfo? {
        val blockType = data.readUnsignedByte() and 0x7F
        if (blockType != 0) return null
        data.skipFully(3 + 10) // длина блока, размеры блоков и кадров
        val b = IntArray(8) { data.readUnsignedByte() }
        val sampleRate = (b[0] shl 12) or (b[1] shl 4) or (b[2] shr 4)
        val channels = ((b[2] shr 1) and 0x07) + 1
        val bits = (((b[2] and 0x01) shl 4) or (b[3] shr 4)) + 1
        val samples = ((b[3] and 0x0F).toLong() shl 32) or (b[4].toLong() shl 24) or (b[5].toLong() shl 16) or
            (b[6].toLong() shl 8) or b[7].toLong()
        if (sampleRate <= 0) return null
        return AudioStreamInfo(sampleRate, bits, channels, if (samples > 0) samples * 1000 / sampleRate else 0)
    }

    private fun readWav(data: DataInputStream): AudioStreamInfo? {
        data.skipFully(4) // размер RIFF
        if (ByteArray(4).also { data.readFully(it) }.ascii() != "WAVE") return null
        var sampleRate = 0
        var channels = 0
        var bits = 0
        var byteRate = 0L
        // Чанки идут подряд: «fmt » с параметрами, «data» с отсчётами; остальное пропускаем.
        repeat(MAX_CHUNKS) {
            val id = ByteArray(4).also { data.readFully(it) }.ascii()
            val size = data.readIntLe().toLong() and 0xFFFFFFFFL
            when (id) {
                "fmt " -> {
                    data.readShortLe() // формат: PCM, float, extensible
                    channels = data.readShortLe()
                    sampleRate = data.readIntLe()
                    byteRate = data.readIntLe().toLong() and 0xFFFFFFFFL
                    data.readShortLe() // выравнивание блока
                    bits = data.readShortLe()
                    data.skipFully(size - 16 + (size and 1))
                }
                "data" -> {
                    if (sampleRate <= 0) return null
                    val duration = if (byteRate > 0) size * 1000 / byteRate else 0
                    return AudioStreamInfo(sampleRate, bits, channels, duration)
                }
                else -> data.skipFully(size + (size and 1))
            }
        }
        return if (sampleRate > 0) AudioStreamInfo(sampleRate, bits, channels) else null
    }

    private fun ByteArray.ascii() = String(this, Charsets.ISO_8859_1)

    private fun DataInputStream.readIntLe(): Int {
        val b = IntArray(4) { readUnsignedByte() }
        return b[0] or (b[1] shl 8) or (b[2] shl 16) or (b[3] shl 24)
    }

    private fun DataInputStream.readShortLe(): Int = readUnsignedByte() or (readUnsignedByte() shl 8)

    private fun DataInputStream.skipFully(count: Long) {
        var left = count
        while (left > 0) {
            val skipped = skip(left)
            if (skipped <= 0) {
                if (read() < 0) throw EOFException()
                left--
            } else {
                left -= skipped
            }
        }
    }

    private const val MAX_CHUNKS = 16
}
