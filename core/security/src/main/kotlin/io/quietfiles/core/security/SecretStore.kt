package io.quietfiles.core.security

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.quietfiles.core.model.network.ConnectionSecrets
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Секрет не удалось зашифровать: он не сохранён, пользователь должен увидеть ошибку (S1). */
class SecretStoreException(cause: Throwable) : Exception("Cannot protect secret", cause)

/**
 * Зашифрованные секреты (S1, S2). Каждый лежит отдельным файлом в `noBackupFilesDir/secrets` —
 * эта папка не входит ни в облачную копию, ни в перенос на другое устройство.
 */
@Singleton
class SecretStore internal constructor(
    private val dir: File,
    private val cipher: SecretCipher,
) {
    @Inject constructor(@ApplicationContext context: Context, cipher: KeystoreSecretCipher) :
        this(File(context.noBackupFilesDir, "secrets"), cipher)

    private val lock = Any()

    /** @throws SecretStoreException если зашифровать не вышло; старое значение при этом удаляется. */
    fun put(key: String, value: ByteArray) = synchronized(lock) {
        val file = fileOf(key)
        val sealed = try {
            cipher.encrypt(value)
        } catch (e: Exception) {
            file.delete()
            throw SecretStoreException(e)
        }
        dir.mkdirs()
        val temp = File(dir, file.name + ".tmp")
        temp.writeBytes(sealed)
        if (!temp.renameTo(file)) {
            temp.delete()
            throw SecretStoreException(IllegalStateException("rename failed"))
        }
    }

    /** null — секрета нет или он не расшифровывается (ключ Keystore потерян после восстановления, S2). */
    fun get(key: String): ByteArray? = synchronized(lock) {
        val file = fileOf(key)
        if (!file.exists()) return null
        runCatching { cipher.decrypt(file.readBytes()) }.getOrNull()
    }

    fun remove(key: String) {
        synchronized(lock) { fileOf(key).delete() }
    }

    fun putConnection(connectionId: Long, secrets: ConnectionSecrets) {
        if (secrets.isEmpty && secrets.passphrase.isNullOrEmpty()) {
            remove(connectionKey(connectionId))
            return
        }
        val json = JSONObject()
        secrets.password?.let { json.put("password", it) }
        secrets.privateKey?.let { json.put("privateKey", it) }
        secrets.passphrase?.let { json.put("passphrase", it) }
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        try {
            put(connectionKey(connectionId), bytes)
        } finally {
            bytes.fill(0)
        }
    }

    fun connection(connectionId: Long): ConnectionSecrets? {
        val bytes = get(connectionKey(connectionId)) ?: return null
        return try {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            ConnectionSecrets(
                password = json.optString("password").takeIf { json.has("password") },
                privateKey = json.optString("privateKey").takeIf { json.has("privateKey") },
                passphrase = json.optString("passphrase").takeIf { json.has("passphrase") },
            )
        } catch (_: Exception) {
            null
        } finally {
            bytes.fill(0)
        }
    }

    fun removeConnection(connectionId: Long) = remove(connectionKey(connectionId))

    private fun connectionKey(id: Long) = "connection-$id"

    private fun fileOf(key: String): File {
        require(key.matches(Regex("[a-z0-9.-]+"))) { "Bad secret key" }
        return File(dir, "$key.bin")
    }
}
