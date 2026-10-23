package io.quietfiles.core.network.smb

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Список общих ресурсов сервера: вызов NetrShareEnum (уровень 1) службы SRVSVC по DCE/RPC
 * через именованный канал `\PIPE\srvsvc` ([MS-SRVS] 3.1.4.8, [C706] гл. 12, 14).
 * SMB-библиотека этого не умеет, а отдельная библиотека DCE/RPC тянет за собой несколько мегабайт.
 */
internal object SrvSvc {

    data class Share(val name: String, val type: Int, val remark: String) {
        /** Обычная папка, не принтер, не IPC$ и не скрытый административный ресурс (C$, ADMIN$). */
        val isDisk: Boolean get() = type and 0x0FFFFFFF == 0 && type and STYPE_SPECIAL == 0 && !name.endsWith("$")
    }

    private const val STYPE_SPECIAL = 0x80000000.toInt()
    private const val PTYPE_REQUEST: Byte = 0
    private const val PTYPE_RESPONSE: Byte = 2
    private const val PTYPE_FAULT: Byte = 3
    private const val PTYPE_BIND: Byte = 11
    private const val PTYPE_BIND_ACK: Byte = 12
    private const val PFC_FIRST = 0x01
    private const val PFC_LAST = 0x02
    private const val OPNUM_NETR_SHARE_ENUM: Short = 15
    const val MAX_FRAG = 4280

    // 4b324fc8-1670-01d3-1278-5a47bf6ee188 v3.0
    private val SRVSVC_UUID = uuid(0x4b324fc8, 0x1670, 0x01d3, byteArrayOf(0x12, 0x78, 0x5a, 0x47, 0xbf.toByte(), 0x6e, 0xe1.toByte(), 0x88.toByte()))

    // 8a885d04-1ceb-11c9-9fe8-08002b104860 v2 — NDR
    private val NDR_UUID = uuid(0x8a885d04.toInt(), 0x1ceb, 0x11c9, byteArrayOf(0x9f.toByte(), 0xe8.toByte(), 0x08, 0x00, 0x2b, 0x10, 0x48, 0x60))

    private fun uuid(a: Int, b: Int, c: Int, d: ByteArray): ByteArray =
        ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(a).putShort(b.toShort()).putShort(c.toShort()).put(d).array()

    private fun header(type: Byte, length: Int, callId: Int): ByteBuffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(5) // rpc_vers
        put(0) // rpc_vers_minor
        put(type)
        put((PFC_FIRST or PFC_LAST).toByte())
        put(byteArrayOf(0x10, 0, 0, 0)) // little-endian, ASCII, IEEE
        putShort(length.toShort())
        putShort(0) // auth_length
        putInt(callId)
    }

    fun bind(): ByteArray {
        val length = 16 + 12 + 44
        return header(PTYPE_BIND, length, 1).apply {
            putShort(MAX_FRAG.toShort())
            putShort(MAX_FRAG.toShort())
            putInt(0) // assoc_group_id
            put(1) // n_context_elem
            put(0)
            putShort(0)
            putShort(0) // p_cont_id
            put(1) // n_transfer_syn
            put(0)
            put(SRVSVC_UUID)
            putShort(3)
            putShort(0)
            put(NDR_UUID)
            putInt(2)
        }.array()
    }

    /** Проверяет bind_ack: контекст принят (result = 0). */
    fun checkBindAck(response: ByteArray) {
        val buf = ByteBuffer.wrap(response).order(ByteOrder.LITTLE_ENDIAN)
        if (response.size < 26 || buf.get(2) != PTYPE_BIND_ACK) throw IOException("SRVSVC bind rejected")
        // После заголовка: max_xmit(2) max_recv(2) assoc(4) sec_addr_len(2) sec_addr(...) выравнивание на 4, затем n_results(1) ...
        var pos = 16 + 8
        val addrLen = buf.getShort(pos).toInt() and 0xFFFF
        pos += 2 + addrLen
        pos = (pos + 3) and 3.inv()
        if (pos + 6 > response.size) throw IOException("Short bind_ack")
        val results = response[pos].toInt() and 0xFF
        val result = buf.getShort(pos + 4).toInt()
        if (results < 1 || result != 0) throw IOException("SRVSVC context rejected: $result")
    }

    /** Запрос NetrShareEnum для сервера [server], уровень 1. */
    fun shareEnumRequest(server: String): ByteArray {
        val stub = Ndr()
        stub.uniqueString("\\\\" + server)
        stub.int(1) // Level
        stub.int(1) // switch_is(Level)
        stub.int(0x20000) // указатель на SHARE_INFO_1_CONTAINER
        stub.int(0) // EntriesRead
        stub.int(0) // Buffer = NULL
        stub.int(-1) // PreferedMaximumLength
        stub.int(0x20004) // ResumeHandle (unique)
        stub.int(0)
        val body = stub.bytes()
        val length = 16 + 8 + body.size
        return header(PTYPE_REQUEST, length, 2).apply {
            putInt(body.size) // alloc_hint
            putShort(0) // p_cont_id
            putShort(OPNUM_NETR_SHARE_ENUM)
            put(body)
        }.array()
    }

    /**
     * Собирает данные заглушки из фрагментов ответа. [first] — ответ на transact, [readMore] — следующие
     * фрагменты, если сервер разбил ответ (бит PFC_LAST не выставлен).
     */
    fun collectResponse(first: ByteArray, readMore: () -> ByteArray): ByteArray {
        val stub = ByteArrayOutputStream()
        var pending = first
        var buffered = ByteArray(0)
        while (true) {
            buffered += pending
            while (buffered.size >= 16) {
                val buf = ByteBuffer.wrap(buffered).order(ByteOrder.LITTLE_ENDIAN)
                val fragLength = buf.getShort(8).toInt() and 0xFFFF
                if (fragLength < 24 || buffered.size < fragLength) break
                val type = buffered[2]
                if (type == PTYPE_FAULT) throw IOException("SRVSVC fault 0x%08x".format(buf.getInt(24)))
                if (type != PTYPE_RESPONSE) throw IOException("Unexpected PDU type $type")
                val authLength = buf.getShort(10).toInt() and 0xFFFF
                val flags = buffered[3].toInt()
                stub.write(buffered, 24, fragLength - 24 - authLength)
                buffered = buffered.copyOfRange(fragLength, buffered.size)
                if (flags and PFC_LAST != 0) return stub.toByteArray()
            }
            pending = readMore()
            if (pending.isEmpty()) throw IOException("SRVSVC response truncated")
        }
    }

    fun parseShares(stub: ByteArray): List<Share> {
        val r = NdrReader(stub)
        r.int() // Level
        r.int() // switch
        if (r.int() == 0) return emptyList() // контейнер
        val count = r.int()
        if (r.int() == 0 || count == 0) return emptyList() // Buffer
        val max = r.int()
        if (max < count) throw IOException("Bad share array")
        val headers = List(count) { Triple(r.int(), r.int(), r.int()) } // netname ptr, type, remark ptr
        return headers.map { (namePtr, type, remarkPtr) ->
            val name = if (namePtr != 0) r.string() else ""
            val remark = if (remarkPtr != 0) r.string() else ""
            Share(name, type, remark)
        }.also {
            r.int() // TotalEntries
            if (r.int() != 0) r.int() // ResumeHandle
            val status = r.int()
            if (status != 0 && status != 0xEA) throw IOException("NetrShareEnum failed: 0x%08x".format(status))
        }
    }

    private class Ndr {
        private val out = ByteArrayOutputStream()
        fun int(v: Int) {
            align()
            out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        }
        fun uniqueString(s: String) {
            int(0x20008) // referent id
            val chars = (s + '\u0000')
            int(chars.length)
            int(0)
            int(chars.length)
            out.write(chars.toByteArray(Charsets.UTF_16LE))
            align()
        }
        private fun align() {
            while (out.size() % 4 != 0) out.write(0)
        }
        fun bytes(): ByteArray = out.toByteArray()
    }

    private class NdrReader(private val data: ByteArray) {
        private var pos = 0
        private val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        fun int(): Int {
            pos = (pos + 3) and 3.inv()
            if (pos + 4 > data.size) throw IOException("NDR underflow")
            return buf.getInt(pos).also { pos += 4 }
        }
        fun string(): String {
            int() // max_count
            val offset = int()
            val actual = int()
            if (actual < 0 || offset < 0 || pos + actual * 2 > data.size) throw IOException("Bad NDR string")
            val s = String(data, pos, actual * 2, Charsets.UTF_16LE)
            pos += actual * 2
            return s.trimEnd('\u0000')
        }
    }
}
