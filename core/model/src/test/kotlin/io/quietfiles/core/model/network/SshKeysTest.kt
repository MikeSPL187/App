package io.quietfiles.core.model.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SshKeysTest {

    // Структура openssh-key-v1 без шифрования, ключ из нулей.
    private val ed25519 = """
        -----BEGIN OPENSSH PRIVATE KEY-----
        b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
        QyNTUxOQAAACAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAJAAAAABAAAA
        AQAAAAtzc2gtZWQyNTUxOQAAACAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
        AAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
        AAAAAAAAAAAAAAAAAAAAAAAADHRlc3RAZXhhbXBsZQE=
        -----END OPENSSH PRIVATE KEY-----
    """.trimIndent()

    @Test
    fun recognisesKeyTypes() {
        assertThat(SshKeys.describe(ed25519)).isEqualTo(SshKeyInfo("ED25519", encrypted = false))
        assertThat(SshKeys.describe("-----BEGIN RSA PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\n...")).isEqualTo(SshKeyInfo("RSA", true))
        assertThat(SshKeys.describe("-----BEGIN ENCRYPTED PRIVATE KEY-----\n...")).isEqualTo(SshKeyInfo("PKCS#8", true))
        assertThat(SshKeys.describe("PuTTY-User-Key-File-3: ssh-ed25519\nEncryption: aes256-cbc\n")).isEqualTo(SshKeyInfo("ED25519", true))
        assertThat(SshKeys.describe("ssh-ed25519 AAAA… user@host")).isNull()
    }
}
