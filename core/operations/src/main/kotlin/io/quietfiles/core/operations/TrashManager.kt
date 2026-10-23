package io.quietfiles.core.operations

import io.quietfiles.core.data.db.TrashDao
import io.quietfiles.core.data.db.TrashEntity
import io.quietfiles.core.model.FileNames
import io.quietfiles.core.model.TrashItem
import io.quietfiles.core.storage.LocalStorageProvider
import io.quietfiles.core.storage.StorageException
import io.quietfiles.core.storage.StorageProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Корзина (F5.7). Файлы переносятся в скрытую папку на том же томе, поэтому удаление и восстановление
 * мгновенные и не требуют свободного места. В папку кладётся `.nomedia`, чтобы галерея её не показывала.
 */
@Singleton
class TrashManager @Inject constructor(
    private val provider: StorageProvider,
    private val dao: TrashDao,
) {
    val items: Flow<List<TrashItem>> = dao.observeAll().map { list -> list.map { it.toItem() } }

    /** Корень тома для пути; в тестах подменяется на временную папку. */
    internal var volumeRootOf: (String) -> String = LocalStorageProvider::volumeRootOf

    fun trashDirFor(path: String): String = volumeRootOf(path).trimEnd('/') + "/$TRASH_DIR"

    fun isInTrash(path: String): Boolean = path.contains("/$TRASH_DIR/") || path.endsWith("/$TRASH_DIR")

    suspend fun moveToTrash(path: String, size: Long, isDirectory: Boolean, now: Long = System.currentTimeMillis()): Long {
        val dir = ensureTrashDir(trashDirFor(path))
        val name = FileNames.nameOf(path)
        var target = FileNames.child(dir, "$now-$name")
        var attempt = 1
        while (provider.exists(target)) target = FileNames.child(dir, "$now-${attempt++}-$name")
        if (!provider.moveFast(path, target)) throw StorageException.AccessDenied(path)
        return dao.insert(
            TrashEntity(
                originalPath = path,
                trashPath = target,
                name = name,
                isDirectory = isDirectory,
                size = size,
                deletedAt = now,
            ),
        )
    }

    /** Возвращает файл на исходное место; если имя занято — «имя (1)». Возвращает итоговый путь. */
    suspend fun restore(item: TrashItem): String {
        val parent = FileNames.parentOf(item.originalPath) ?: throw StorageException.NotFound(item.originalPath)
        File(parent).mkdirs()
        val freeName = FileNames.uniqueName(item.name, item.isDirectory) { candidate ->
            File(parent, candidate).exists()
        }
        val target = FileNames.child(parent, freeName)
        if (!provider.exists(item.trashPath)) {
            dao.delete(listOf(item.id))
            throw StorageException.NotFound(item.trashPath)
        }
        if (!provider.moveFast(item.trashPath, target)) throw StorageException.AccessDenied(target)
        dao.delete(listOf(item.id))
        return target
    }

    suspend fun byIds(ids: List<Long>): List<TrashItem> = dao.byIds(ids).map { it.toItem() }

    suspend fun deleteForever(items: List<TrashItem>) {
        items.forEach { provider.delete(it.trashPath) }
        dao.delete(items.map { it.id })
    }

    suspend fun emptyTrash() = deleteForever(dao.all().map { it.toItem() })

    /** То, что лежит в корзине дольше [days] дней (F13.1, «Корзина старше срока»). */
    suspend fun olderThan(days: Int, now: Long = System.currentTimeMillis()): List<TrashItem> =
        dao.olderThan(now - days * DAY_MS).map { it.toItem() }

    /** Удаляет то, что лежит дольше срока. 0 дней — хранить без ограничения. */
    suspend fun purgeExpired(retentionDays: Int, now: Long = System.currentTimeMillis()): Int {
        if (retentionDays <= 0) return 0
        val expired = dao.olderThan(now - retentionDays * DAY_MS).map { it.toItem() }
        deleteForever(expired)
        return expired.size
    }

    private suspend fun ensureTrashDir(dir: String): String {
        val file = File(dir)
        if (!file.exists() && !file.mkdirs()) throw StorageException.AccessDenied(dir)
        val nomedia = File(file, ".nomedia")
        if (!nomedia.exists()) runCatching { nomedia.createNewFile() }
        return dir
    }

    private fun TrashEntity.toItem() = TrashItem(id, originalPath, trashPath, name, isDirectory, size, deletedAt)

    companion object {
        const val TRASH_DIR = ".QuietFiles/trash"
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
