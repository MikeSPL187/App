package io.quietfiles.integration

import android.content.Context
import coil3.SingletonImageLoader
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.quietfiles.core.common.IoDispatcher
import io.quietfiles.core.model.cleanup.FolderSizes
import io.quietfiles.core.operations.cleanup.AppCacheCleaner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Кэш приложения для пункта «Кэш и миниатюры» (F13): миниатюры Coil чистит сам Coil, остальное —
 * файлы старше часа, чтобы не удалить копию, которую прямо сейчас отправляют через «Поделиться».
 */
@Singleton
class AppCache @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : AppCacheCleaner {

    override fun size(): Long = listOfNotNull(context.cacheDir, context.externalCacheDir).sumOf { FolderSizes.total(it).first }

    override suspend fun clear() {
        val loader = SingletonImageLoader.get(context)
        loader.memoryCache?.clear()
        withContext(io) {
            loader.diskCache?.clear()
            val cutoff = System.currentTimeMillis() - KEEP_RECENT_MS
            listOfNotNull(context.cacheDir, context.externalCacheDir).forEach { dir ->
                dir.listFiles()?.filter { it.name != THUMBNAILS }?.forEach { deleteOlder(it, cutoff) }
            }
        }
    }

    private fun deleteOlder(file: File, cutoff: Long) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteOlder(it, cutoff) }
            if (file.listFiles()?.isEmpty() == true) file.delete()
        } else if (file.lastModified() < cutoff) {
            file.delete()
        }
    }

    private companion object {
        const val THUMBNAILS = "thumbnails"
        const val KEEP_RECENT_MS = 60 * 60 * 1000L
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AppCacheModule {
    @Binds
    abstract fun appCache(impl: AppCache): AppCacheCleaner
}
