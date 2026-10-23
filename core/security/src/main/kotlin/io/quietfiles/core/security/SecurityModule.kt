package io.quietfiles.core.security

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {
    /** В тестах приложения подменяется программным ключом: в Robolectric нет Android Keystore. */
    @Binds
    abstract fun bindSecretCipher(impl: KeystoreSecretCipher): SecretCipher
}
