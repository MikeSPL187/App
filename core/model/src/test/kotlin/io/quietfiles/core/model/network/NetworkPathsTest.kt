package io.quietfiles.core.model.network

import com.google.common.truth.Truth.assertThat
import io.quietfiles.core.model.FileNames
import org.junit.Assert.assertThrows
import org.junit.Test

class NetworkPathsTest {

    @Test
    fun buildsAndParsesPaths() {
        assertThat(NetworkPaths.pathOf(3, "/")).isEqualTo("/qf-net/3")
        assertThat(NetworkPaths.pathOf(3, "home//pi/")).isEqualTo("/qf-net/3/home/pi")
        assertThat(NetworkPaths.parse("/qf-net/3")).isEqualTo(3L to "/")
        assertThat(NetworkPaths.parse("/qf-net/3/Public/Фильмы")).isEqualTo(3L to "/Public/Фильмы")
        assertThat(NetworkPaths.parse("/storage/emulated/0")).isNull()
        assertThat(NetworkPaths.parse("/qf-net/x/a")).isNull()
        assertThat(NetworkPaths.isNetwork("/qf-net")).isTrue()
        assertThat(NetworkPaths.isNetwork("/qf-network")).isFalse()
        // Обычные функции путей работают и с сетевыми путями.
        assertThat(FileNames.parentOf("/qf-net/3/Public")).isEqualTo("/qf-net/3")
        assertThat(FileNames.nameOf("/qf-net/3/Public/a.mkv")).isEqualTo("a.mkv")
    }

    @Test
    fun refusesEscapingTheRoot() {
        assertThrows(IllegalArgumentException::class.java) { NetworkPaths.normalize("/a/../../etc") }
        assertThrows(IllegalArgumentException::class.java) { NetworkPaths.parse("/qf-net/1/../2") }
        assertThat(NetworkPaths.normalize("./a/./b")).isEqualTo("/a/b")
    }

    @Test
    fun addressesForTheList() {
        assertThat(Connection(protocol = Protocol.SMB, host = "192.168.1.20").address).isEqualTo("smb://192.168.1.20")
        assertThat(Connection(protocol = Protocol.SFTP, host = "192.168.1.50", username = "pi").address).isEqualTo("sftp://pi@192.168.1.50")
        assertThat(Connection(protocol = Protocol.WEBDAV, host = "nextcloud.home", path = "/dav/").address).isEqualTo("https://nextcloud.home/dav")
        assertThat(Connection(protocol = Protocol.FTP, host = "nas", port = 2121, ftpSecurity = FtpSecurity.NONE).address).isEqualTo("ftp://nas:2121")
        assertThat(Connection(protocol = Protocol.FTP, host = "nas", port = 990, ftpSecurity = FtpSecurity.IMPLICIT).address).isEqualTo("ftps://nas")
        assertThat(Connection(protocol = Protocol.SFTP, host = "fe80::1").address).isEqualTo("sftp://[fe80::1]")
        assertThat(Connection(protocol = Protocol.SFTP, host = "pi.local", name = " ").displayName).isEqualTo("pi.local")
    }
}
