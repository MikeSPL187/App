package io.quietfiles

import android.app.Application
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.HiltAndroidApp
import io.quietfiles.core.data.repository.SettingsRepository
import io.quietfiles.core.operations.OperationService
import io.quietfiles.core.operations.TrashManager
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class QuietFilesApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        OperationService.ensureChannel(this)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            TrashPurgeWorker.NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<TrashPurgeWorker>(1, TimeUnit.DAYS).build(),
        )
    }

    /** Миниатюры: кэш на диске ограничен 200 МБ (раздел 8 ТЗ), кадры видео через MediaMetadataRetriever. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(VideoFrameDecoder.Factory()) }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("thumbnails"))
                .maxSizeBytes(200L * 1024 * 1024)
                .build()
        }
        .crossfade(true)
        .build()
}

/** Раз в сутки удаляет из корзины то, что лежит дольше выбранного срока (F5.7). */
@HiltWorker
class TrashPurgeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val trash: TrashManager,
    private val settings: SettingsRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val days = settings.settings.first().trashRetentionDays
        return runCatching { trash.purgeExpired(days) }.fold({ Result.success() }, { Result.retry() })
    }

    companion object {
        const val NAME = "trash-purge"
    }
}
