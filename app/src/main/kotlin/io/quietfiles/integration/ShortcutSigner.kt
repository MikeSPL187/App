package io.quietfiles.integration

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Подпись ярлыков папок (F15.4). MainActivity открыта для лаунчера, поэтому любое приложение может
 * прислать ей интент с путём. Подпись HMAC-SHA256 ключом, который знает только наше приложение,
 * отличает наши ярлыки от чужих интентов. Ключ создаётся при первом запуске и не попадает в резервные копии.
 */
class ShortcutSigner(private val keyFile: File) {

    fun sign(path: String): String {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(key(), ALGORITHM))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(path.toByteArray(Charsets.UTF_8)))
    }

    fun verify(path: String, signature: String?): Boolean {
        if (signature.isNullOrEmpty()) return false
        return MessageDigest.isEqual(sign(path).toByteArray(), signature.toByteArray())
    }

    @Synchronized
    private fun key(): ByteArray {
        if (keyFile.length() == KEY_BYTES.toLong()) return keyFile.readBytes()
        val key = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        keyFile.parentFile?.mkdirs()
        val tmp = File(keyFile.parentFile, keyFile.name + ".tmp")
        tmp.writeBytes(key)
        if (!tmp.renameTo(keyFile)) keyFile.writeBytes(key)
        return key
    }

    companion object {
        private const val ALGORITHM = "HmacSHA256"
        private const val KEY_BYTES = 32

        fun from(context: Context) = ShortcutSigner(File(context.noBackupFilesDir, "shortcut.key"))
    }
}
