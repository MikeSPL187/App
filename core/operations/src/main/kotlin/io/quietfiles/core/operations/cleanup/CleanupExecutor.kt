package io.quietfiles.core.operations.cleanup

import io.quietfiles.core.data.repository.CleanupHistory
import io.quietfiles.core.model.cleanup.CleanupCategory
import io.quietfiles.core.model.cleanup.CleanupSection
import io.quietfiles.core.model.cleanup.CleanupSelection
import io.quietfiles.core.operations.OperationQueue
import io.quietfiles.core.operations.OperationRequest
import io.quietfiles.core.operations.TrashManager
import javax.inject.Inject
import javax.inject.Singleton

/** Итог очистки для сообщения «Освобождено 1,2 ГБ». */
data class CleanupResult(val bytes: Long, val files: Int, val cacheSelected: Boolean)

/**
 * Удаляет выбранное (F13.5): файлы — через корзину, чтобы их можно было вернуть; кэш и то, что уже
 * лежит в корзине, — навсегда. Длинная часть идёт очередью операций, как любое удаление.
 */
@Singleton
class CleanupExecutor @Inject constructor(
    private val queue: OperationQueue,
    private val trash: TrashManager,
    private val appCache: AppCacheCleaner,
    private val history: CleanupHistory,
    private val scanner: CleanupScanner,
) {
    suspend fun clean(selection: CleanupSelection, sections: List<CleanupSection>): CleanupResult {
        val chosen = selection.selectedFiles(sections)
        if (chosen.isEmpty()) return CleanupResult(0, 0, cacheSelected = false)

        val toTrash = chosen.filter { (category, _) -> category.viaTrash }.map { it.second.path }
        if (toTrash.isNotEmpty()) queue.enqueue(OperationRequest.Trash(toTrash))

        val cache = chosen.filter { it.first == CleanupCategory.CACHE }.map { it.second.path }
        if (CleanupScanner.APP_CACHE_PATH in cache) appCache.clear()
        val cacheDirs = cache.filter { it != CleanupScanner.APP_CACHE_PATH }
        if (cacheDirs.isNotEmpty()) queue.enqueue(OperationRequest.Delete(cacheDirs))

        val trashIds = chosen.filter { it.first == CleanupCategory.OLD_TRASH }.mapNotNull { it.second.trashId }
        if (trashIds.isNotEmpty()) trash.deleteForever(trash.byIds(trashIds))

        val bytes = chosen.sumOf { it.second.size }
        history.add(bytes, chosen.size)
        scanner.invalidate()
        return CleanupResult(bytes, chosen.size, cacheSelected = CleanupCategory.CACHE in selection.categories)
    }
}
