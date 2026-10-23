package io.quietfiles.core.model.network

import java.nio.ByteBuffer
import java.util.Base64

/** Что за закрытый ключ SSH выбрал пользователь: тип и защищён ли он фразой (макет 09). */
data class SshKeyInfo(val type: String, val encrypted: Boolean)

object SshKeys {
    /** Ключ больше этого — точно не закрытый ключ SSH. */
    const val MAX_KEY_BYTES = 64 * 1024

    fun describe(text: String): SshKeyInfo? {
        val trimmed = text.trim()
        return when {
            trimmed.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----") -> openSsh(trimmed)
            trimmed.startsWith("-----BEGIN RSA PRIVATE KEY-----") -> SshKeyInfo("RSA", trimmed.contains("ENCRYPTED"))
            trimmed.startsWith("-----BEGIN EC PRIVATE KEY-----") -> SshKeyInfo("ECDSA", trimmed.contains("ENCRYPTED"))
            trimmed.startsWith("-----BEGIN DSA PRIVATE KEY-----") -> SshKeyInfo("DSA", trimmed.contains("ENCRYPTED"))
            trimmed.startsWith("-----BEGIN ENCRYPTED PRIVATE KEY-----") -> SshKeyInfo("PKCS#8", encrypted = true)
            trimmed.startsWith("-----BEGIN PRIVATE KEY-----") -> SshKeyInfo("PKCS#8", encrypted = false)
            trimmed.startsWith("PuTTY-User-Key-File-") -> SshKeyInfo(
                type = keyTypeName(trimmed.substringAfter(':').substringBefore('\n').trim()),
                encrypted = !trimmed.contains("Encryption: none"),
            )
            else -> null
        }
    }

    /** Формат openssh-key-v1: magic, шифр, KDF, параметры KDF, число ключей, открытый ключ (первое поле — тип). */
    private fun openSsh(text: String): SshKeyInfo? = runCatching {
        val body = text.lines().filterNot { it.startsWith("-----") }.joinToString("")
        val bytes = Base64.getMimeDecoder().decode(body)
        val magic = "openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII)
        if (!bytes.copyOf(magic.size).contentEquals(magic)) return null
        val buffer = ByteBuffer.wrap(bytes, magic.size, bytes.size - magic.size)
        fun string(): ByteArray = ByteArray(buffer.int).also { buffer.get(it) }
        val cipher = String(string(), Charsets.US_ASCII)
        string() // kdfname
        string() // kdfoptions
        buffer.int // число ключей
        val publicKey = ByteBuffer.wrap(string())
        val type = ByteArray(publicKey.int).also { publicKey.get(it) }
        SshKeyInfo(keyTypeName(String(type, Charsets.US_ASCII)), encrypted = cipher != "none")
    }.getOrNull()

    private fun keyTypeName(sshName: String): String = when {
        sshName == "ssh-ed25519" -> "ED25519"
        sshName == "ssh-rsa" -> "RSA"
        sshName.startsWith("ecdsa-") -> "ECDSA"
        sshName == "ssh-dss" -> "DSA"
        else -> sshName
    }
}
