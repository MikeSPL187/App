package io.quietfiles.core.operations

import io.quietfiles.core.model.BatchRename
import io.quietfiles.core.model.FileNames
import io.quietfiles.core.model.RenamePreview
import io.quietfiles.core.storage.StorageProvider
import javax.inject.Inject
import javax.inject.Singleton

/** Итог группового переименования: пары «было → стало» для избранного и медиаиндекса. */
data class BatchRenameResult(val renamed: List<Pair<String, String>>, val error: Exception?)

/**
 * Групповое переименование (F5.1). Переименование в пределах папки — мгновенная операция, поэтому
 * идёт без очереди, но в два шага через временные имена: обмен и сдвиг номеров ничего не затирают.
 * При ошибке уже переименованное возвращается к прежним именам.
 */
@Singleton
class BatchRenamer @Inject constructor(private val provider: StorageProvider) {

    suspend fun rename(previews: List<RenamePreview>): BatchRenameResult {
        val plan = BatchRename.plan(previews)
        val half = plan.size / 2
        val done = mutableListOf<Pair<String, String>>()
        try {
            for ((from, to) in plan) {
                provider.rename(from, FileNames.nameOf(to))
                done += from to to
            }
        } catch (e: Exception) {
            rollback(done)
            return BatchRenameResult(emptyList(), e)
        }
        // Итог для вызывающего: исходный путь → конечный.
        val result = (0 until half).map { i -> plan[i].first to plan[half + i].second }
        return BatchRenameResult(result, null)
    }

    private suspend fun rollback(done: List<Pair<String, String>>) {
        for ((from, to) in done.asReversed()) {
            runCatching { provider.rename(to, FileNames.nameOf(from)) }
        }
    }
}
