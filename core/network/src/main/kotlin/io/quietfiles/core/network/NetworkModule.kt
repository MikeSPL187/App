package io.quietfiles.core.network

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.quietfiles.core.storage.StorageProvider

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkModule {
    @Binds
    abstract fun bindStorageProvider(impl: RoutingStorageProvider): StorageProvider

    @Binds
    abstract fun bindConnectionList(impl: RepositoryConnectionList): ConnectionList
}
