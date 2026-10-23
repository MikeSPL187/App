package io.quietfiles.integration

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.Binds
import dagger.Module
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.quietfiles.core.data.index.ContentIndex
import io.quietfiles.core.data.index.ContentIndexScheduler
import io.quietfiles.core.data.repository.SettingsRepository
import io.quietfiles.core.storage.StorageAccess
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Проход индексации содержимого (F6.6). По расписанию — только на зарядке (раздел 8 ТЗ). */
@HiltWorker
class ContentIndexWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val index: ContentIndex,
    private val settings: SettingsRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!settings.settings.first().contentIndexing || !StorageAccess.hasAllFilesAccess()) return Result.success()
        return runCatching { index.update() }.fold({ Result.success() }, { Result.retry() })
    }
}

@Singleton
class ContentIndexing @Inject constructor(@ApplicationContext private val context: Context) : ContentIndexScheduler {

    private val work get() = WorkManager.getInstance(context)

    /** Раз в полдня, когда телефон заряжается и батарея не на исходе. */
    fun schedule() {
        val constraints = Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build()
        work.enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ContentIndexWorker>(12, TimeUnit.HOURS).setConstraints(constraints).build(),
        )
    }

    fun cancel() {
        work.cancelUniqueWork(PERIODIC)
        work.cancelUniqueWork(NOW)
    }

    /** «Обновить сейчас» в поиске: пользователь сам попросил, поэтому без условия зарядки. */
    override fun indexNow() {
        work.enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<ContentIndexWorker>().build())
    }

    private companion object {
        const val PERIODIC = "content-index"
        const val NOW = "content-index-now"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ContentIndexingModule {
    @Binds
    abstract fun scheduler(impl: ContentIndexing): ContentIndexScheduler
}
