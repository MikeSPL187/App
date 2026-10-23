package io.quietfiles.core.security

import com.google.common.truth.Truth.assertThat
import io.quietfiles.core.model.network.ConnectionSecrets
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Программный AES-GCM вместо Keystore: в Robolectric Keystore нет. */
private class SoftwareCipher(private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()) : SecretCipher {
    var broken = false
    override fun encrypt(plain: ByteArray): ByteArray {
        if (broken) throw IllegalStateException("Keystore unavailable")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        return byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(plain)
    }
    override fun decrypt(sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 1, sealed[0].toInt())) }
        return cipher.doFinal(sealed, 1 + sealed[0], sealed.size - 1 - sealed[0])
    }
}

@RunWith(RobolectricTestRunner::class)
class SecretStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun storesSecretsEncrypted() {
        val dir = tmp.newFolder()
        val store = SecretStore(dir, SoftwareCipher())
        store.putConnection(4, ConnectionSecrets(password = "пароль-123", passphrase = ""))
        assertThat(store.connection(4)).isEqualTo(ConnectionSecrets(password = "пароль-123", passphrase = ""))
        // На диске нет открытого текста.
        val raw = File(dir, "connection-4.bin").readBytes()
        assertThat(String(raw, Charsets.ISO_8859_1)).doesNotContain("123")
        assertThat(String(raw, Charsets.UTF_8)).doesNotContain("пароль")

        store.removeConnection(4)
        assertThat(store.connection(4)).isNull()
    }

    @Test
    fun lostKeyMeansLoginAgain() {
        val dir = tmp.newFolder()
        SecretStore(dir, SoftwareCipher()).putConnection(1, ConnectionSecrets(password = "x"))
        // После восстановления на другом устройстве ключ Keystore другой (S2).
        assertThat(SecretStore(dir, SoftwareCipher()).connection(1)).isNull()
    }

    @Test
    fun encryptionFailureKeepsNothing() {
        val dir = tmp.newFolder()
        val cipher = SoftwareCipher()
        val store = SecretStore(dir, cipher)
        store.putConnection(2, ConnectionSecrets(password = "old"))
        cipher.broken = true
        assertThrows(SecretStoreException::class.java) { store.putConnection(2, ConnectionSecrets(password = "new")) }
        // Старое значение не остаётся «как будто сохранено новое» (S1).
        assertThat(store.connection(2)).isNull()
    }

    @Test
    fun fingerprintsLookLikeOpenSsh() {
        assertThat(Fingerprints.sshSha256(byteArrayOf(1, 2, 3))).isEqualTo("SHA256:A5BYxvLAy0ksUzsKTRTvd8wPeKvMztUofYShogEc+4E")
        assertThat(Fingerprints.hexColon(byteArrayOf(0x0A, -1))).isEqualTo("0A:FF")
    }
}
