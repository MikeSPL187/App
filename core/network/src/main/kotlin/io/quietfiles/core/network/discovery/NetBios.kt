package io.quietfiles.core.network.discovery

import java.nio.ByteBuffer

/** Запрос и разбор NBSTAT (RFC 1002, 4.2.17–18): имя компьютера Windows или Samba по его адресу. */
internal object NetBios {
    const val PORT = 137

    fun statusRequest(transactionId: Int): ByteArray {
        val buffer = ByteBuffer.allocate(50)
        buffer.putShort(transactionId.toShort())
        buffer.putShort(0) // запрос, без рекурсии
        buffer.putShort(1) // QDCOUNT
        buffer.putShort(0)
        buffer.putShort(0)
        buffer.putShort(0)
        buffer.put(encodeName("*"))
        buffer.putShort(0x21) // NBSTAT
        buffer.putShort(1) // IN
        return buffer.array()
    }

    /** Имя «*» дополняется нулями до 16 байт и кодируется полубайтами ('A' + n). */
    private fun encodeName(name: String): ByteArray {
        val raw = ByteArray(16)
        name.toByteArray(Charsets.US_ASCII).copyInto(raw)
        val out = ByteArray(34)
        out[0] = 32
        for (i in 0 until 16) {
            val b = raw[i].toInt() and 0xFF
            out[1 + i * 2] = ('A'.code + (b shr 4)).toByte()
            out[2 + i * 2] = ('A'.code + (b and 0x0F)).toByte()
        }
        out[33] = 0
        return out
    }

    /** Имя сервера из ответа: уникальное имя со службой файлового сервера (0x20), иначе первое уникальное. */
    fun parseStatus(data: ByteArray, length: Int = data.size): String? {
        if (length < 57) return null
        var pos = 12
        // Имя в ответе: длина + 32 байта + 0, или указатель сжатия (2 байта).
        pos += if (data[pos].toInt() and 0xC0 == 0xC0) 2 else 34
        pos += 2 + 2 + 4 + 2 // type, class, ttl, rdlength
        if (pos >= length) return null
        val count = data[pos].toInt() and 0xFF
        pos++
        var fallback: String? = null
        for (i in 0 until count) {
            if (pos + 18 > length) break
            val name = String(data, pos, 15, Charsets.ISO_8859_1).trimEnd(' ', '\u0000')
            val suffix = data[pos + 15].toInt() and 0xFF
            val flags = ((data[pos + 16].toInt() and 0xFF) shl 8) or (data[pos + 17].toInt() and 0xFF)
            val group = flags and 0x8000 != 0
            pos += 18
            if (group || name.isEmpty()) continue
            if (suffix == 0x20) return name
            if (suffix == 0x00 && fallback == null) fallback = name
        }
        return fallback
    }
}
