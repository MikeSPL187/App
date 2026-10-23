package io.quietfiles

import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import io.quietfiles.core.security.SecretCipher
import io.quietfiles.core.security.SecurityModule
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Singleton

/** Программный AES-GCM вместо Android Keystore, которого нет в Robolectric. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [SecurityModule::class])
object TestSecurityModule {
    @Provides
    @Singleton
    fun cipher(): SecretCipher = object : SecretCipher {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun encrypt(plain: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            return byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(plain)
        }
        override fun decrypt(sealed: ByteArray): ByteArray {
            val iv = sealed[0].toInt()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 1, iv)) }
            return cipher.doFinal(sealed, 1 + iv, sealed.size - 1 - iv)
        }
    }
}
