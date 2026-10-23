package io.quietfiles.core.network

import com.google.common.truth.Truth.assertThat
import io.quietfiles.core.network.discovery.NetBios
import org.junit.Test
import java.io.ByteArrayOutputStream

class NetBiosTest {

    @Test
    fun requestEncodesWildcardName() {
        val request = NetBios.statusRequest(0x1234)
        assertThat(request.size).isEqualTo(50)
        assertThat(request[0]).isEqualTo(0x12.toByte())
        assertThat(request[12]).isEqualTo(32.toByte())
        // «*» = 0x2A → «CK», затем нули → «AA».
        assertThat(String(request, 13, 4, Charsets.US_ASCII)).isEqualTo("CKAA")
        assertThat(request[47]).isEqualTo(0x21.toByte())
    }

    @Test
    fun parsesServerName() {
        val out = ByteArrayOutputStream()
        out.write(ByteArray(12)) // заголовок
        out.write(NetBios.statusRequest(0).copyOfRange(12, 46)) // имя
        out.write(byteArrayOf(0, 0x21, 0, 1, 0, 0, 0, 0, 0, 0)) // type, class, ttl, rdlength
        fun name(n: String, suffix: Int, group: Boolean) {
            out.write(n.padEnd(15).toByteArray(Charsets.US_ASCII))
            out.write(suffix)
            out.write(if (group) 0x84 else 0x04)
            out.write(0)
        }
        out.write(3)
        name("WORKGROUP", 0x00, group = true)
        name("NAS-HOME", 0x00, group = false)
        name("NAS-HOME", 0x20, group = false)
        assertThat(NetBios.parseStatus(out.toByteArray())).isEqualTo("NAS-HOME")
        assertThat(NetBios.parseStatus(ByteArray(10))).isNull()
    }
}
