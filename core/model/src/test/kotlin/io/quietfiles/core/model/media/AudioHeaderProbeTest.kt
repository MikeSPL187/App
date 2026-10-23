package io.quietfiles.core.model.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioHeaderProbeTest {

    /** Минимальный FLAC: «fLaC» и блок STREAMINFO. */
    private fun flac(sampleRate: Int, channels: Int, bits: Int, samples: Long, id3: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        if (id3) {
            out.write("ID3".toByteArray())
            out.write(byteArrayOf(4, 0, 0, 0, 0, 1, 0)) // версия, флаги, размер 128 (syncsafe)
            out.write(ByteArray(128))
        }
        out.write("fLaC".toByteArray())
        out.write(byteArrayOf(0x80.toByte(), 0, 0, 34))
        out.write(ByteArray(10))
        val packed = (sampleRate.toLong() shl 44) or ((channels - 1).toLong() shl 41) or ((bits - 1).toLong() shl 36) or samples
        out.write(ByteBuffer.allocate(8).putLong(packed).array())
        out.write(ByteArray(16))
        return out.toByteArray()
    }

    private fun wav(sampleRate: Int, channels: Int, bits: Int, dataBytes: Int): ByteArray {
        val b = ByteBuffer.allocate(44 + 12).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + 12 + dataBytes).put("WAVE".toByteArray())
        b.put("LIST".toByteArray()).putInt(4).put("INFO".toByteArray()) // посторонний чанк до fmt
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(sampleRate)
            .putInt(sampleRate * channels * bits / 8).putShort((channels * bits / 8).toShort()).putShort(bits.toShort())
        b.put("data".toByteArray()).putInt(dataBytes)
        return b.array()
    }

    @Test
    fun readsFlacStreamInfo() {
        val info = AudioHeaderProbe.probe(flac(96_000, 2, 24, 96_000L * 258).inputStream())!!
        assertThat(info).isEqualTo(AudioStreamInfo(96_000, 24, 2, 258_000))
    }

    @Test
    fun skipsId3BeforeFlac() {
        val info = AudioHeaderProbe.probe(flac(44_100, 1, 16, 0, id3 = true).inputStream())!!
        assertThat(info.sampleRate).isEqualTo(44_100)
        assertThat(info.bitsPerSample).isEqualTo(16)
        assertThat(info.channels).isEqualTo(1)
    }

    @Test
    fun readsWavFormat() {
        val info = AudioHeaderProbe.probe(wav(48_000, 2, 24, 48_000 * 2 * 3 * 5).inputStream())!!
        assertThat(info).isEqualTo(AudioStreamInfo(48_000, 24, 2, 5_000))
    }

    @Test
    fun unknownOrTruncatedIsNull() {
        assertThat(AudioHeaderProbe.probe("OggS....".toByteArray().inputStream())).isNull()
        assertThat(AudioHeaderProbe.probe(flac(44_100, 2, 16, 0).copyOf(12).inputStream())).isNull()
        assertThat(AudioHeaderProbe.probe(ByteArray(0).inputStream())).isNull()
    }
}
