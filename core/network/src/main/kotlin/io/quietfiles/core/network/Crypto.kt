package io.quietfiles.core.network

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * В Android встроен урезанный провайдер «BC», в котором нет Ed25519, X25519 и разбора ключей OpenSSH.
 * Подменяем его полной BouncyCastle — в конец списка, чтобы остальной код приложения
 * по-прежнему получал системные реализации, а SSH и SMB брали алгоритмы явно у «BC».
 */
object Crypto {
    @Volatile private var installed = false

    fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val current = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
            if (current !is BouncyCastleProvider) {
                Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
                Security.addProvider(BouncyCastleProvider())
            }
            installed = true
        }
    }
}
