package io.quietfiles.core.network.smb

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SrvSvcTest {

    /** Строим ответ NetrShareEnum по правилам NDR, как его отдают Windows и Samba. */
    private class Stub {
        val out = ByteArrayOutputStream()
        fun int(v: Int) = apply {
            while (out.size() % 4 != 0) out.write(0)
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        }
        fun string(s: String) = apply {
            val chars = s + '\u0000'
            int(chars.length).int(0).int(chars.length)
            out.write(chars.toByteArray(Charsets.UTF_16LE))
        }
    }

    private fun response(): ByteArray = Stub()
        .int(1).int(1).int(0x20000) // Level, switch, контейнер
        .int(3).int(0x20004).int(3) // EntriesRead, Buffer, max_count
        .int(0x20008).int(0).int(0x2000c) // Public, диск
        .int(0x20010).int(0x80000003.toInt()).int(0x20014) // IPC$
        .int(0x20018).int(0).int(0) // Фильмы без описания
        .string("Public").string("Общая папка")
        .string("IPC$").string("Remote IPC")
        .string("Фильмы")
        .int(3).int(0).int(0) // TotalEntries, ResumeHandle = NULL, WERROR_OK
        .out.toByteArray()

    @Test
    fun parsesDiskSharesOnly() {
        val shares = SrvSvc.parseShares(response())
        assertThat(shares.map { it.name }).containsExactly("Public", "IPC$", "Фильмы").inOrder()
        assertThat(shares[0].remark).isEqualTo("Общая папка")
        assertThat(shares.filter { it.isDisk }.map { it.name }).containsExactly("Public", "Фильмы")
    }

    private fun pdu(stub: ByteArray, flags: Int): ByteArray {
        val length = 24 + stub.size
        return ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(5); put(0); put(2); put(flags.toByte())
            put(byteArrayOf(0x10, 0, 0, 0))
            putShort(length.toShort()); putShort(0); putInt(2)
            putInt(stub.size); putShort(0); put(0); put(0)
            put(stub)
        }.array()
    }

    @Test
    fun collectsFragmentedResponse() {
        val stub = response()
        val first = pdu(stub.copyOfRange(0, 40), flags = 0x01)
        val second = pdu(stub.copyOfRange(40, stub.size), flags = 0x02)
        // Второй фрагмент приходит по частям.
        val reads = ArrayDeque(listOf(second.copyOfRange(0, 10), second.copyOfRange(10, second.size)))
        val collected = SrvSvc.collectResponse(first) { reads.removeFirst() }
        assertThat(collected).isEqualTo(stub)
        assertThat(SrvSvc.parseShares(collected)).hasSize(3)
    }

    @Test
    fun requestsAreWellFormed() {
        val bind = SrvSvc.bind()
        assertThat(bind.size).isEqualTo(72)
        assertThat(bind[2]).isEqualTo(11.toByte())
        assertThat(ByteBuffer.wrap(bind).order(ByteOrder.LITTLE_ENDIAN).getShort(8).toInt()).isEqualTo(72)

        val request = SrvSvc.shareEnumRequest("nas")
        val buf = ByteBuffer.wrap(request).order(ByteOrder.LITTLE_ENDIAN)
        assertThat(buf.getShort(8).toInt()).isEqualTo(request.size)
        assertThat(buf.getShort(22).toInt()).isEqualTo(15) // opnum NetrShareEnum
        assertThat(request.size % 4).isEqualTo(0)

        val ack = ByteBuffer.allocate(60).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(5); put(0); put(12); put(3); putInt(0x10); putShort(60); putShort(0); putInt(1)
            putShort(4280); putShort(4280); putInt(0x1234)
            putShort(13); put("\\PIPE\\srvsvc\u0000".toByteArray()) // длина 13 → выравнивание
            put(ByteArray(1))
            put(1); put(0); putShort(0); putShort(0); putShort(0)
        }.array()
        SrvSvc.checkBindAck(ack)
        ack[2] = 13
        assertThrows(IOException::class.java) { SrvSvc.checkBindAck(ack) }
    }

    @Test
    fun splitsDomainLogin() {
        assertThat(SmbConnector.splitUser("WORKGROUP\\ivan")).isEqualTo("WORKGROUP" to "ivan")
        assertThat(SmbConnector.splitUser(" ivan ")).isEqualTo(null to "ivan")
    }
}
