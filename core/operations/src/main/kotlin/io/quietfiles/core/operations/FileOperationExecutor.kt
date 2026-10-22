package io.quietfiles.core.operations

import io.quietfiles.core.model.Conflict
import io.quietfiles.core.model.ConflictChoice
import io.quietfiles.core.model.ConflictDecision
import io.quietfiles.core.model.FileEntry
import io.quietfiles.core.model.FileNames
import io.quietfiles.core.storage.StorageException
import io.quietfiles.core.storage.StorageProvider
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * Выполняет одну операцию (F5). Копирование идёт во временный файл `.имя.qfpart` рядом с целью
 * и в конце переименовывается — частично скопированный файл никогда не остаётся под конечным именем.
 * Ошибка одного элемента не останавливает операцию: она считается и показывается в итоге.
 */
class FileOperationExecutor @Inject constructor(
    private val provider: StorageProvider,
    private val trash: TrashManager,
    private val archives: ArchiveTools,
    private val content: ContentSource,
) {

    suspend fun execute(request: OperationRequest, control: OperationControl): OperationOutcome = when (request) {
        is OperationRequest.Copy -> transfer(request.sources, request.destination, move = false, control)
        is OperationRequest.Move -> transfer(request.sources, request.destination, move = true, control)
        is OperationRequest.Delete -> delete(request.paths, control)
        is OperationRequest.Trash -> moveToTrash(request.paths, control)
        is OperationRequest.Restore -> restore(request.trashIds, control)
        is OperationRequest.Compress -> {
            archives.zip(request.sources, request.target, control)
            OperationOutcome(changedPaths = listOf(request.target))
        }
        is OperationRequest.Extract -> {
            val written = archives.extract(request.archive, request.destination, request.entries, control)
            OperationOutcome(changedPaths = written)
        }
        is OperationRequest.Import -> import(request.items, request.destination, control)
    }

    // ---- Копирование и перемещение ----

    private class TransferState(var policy: ConflictDecision? = null) {
        var failed = 0
        var firstError: Throwable? = null
        val changed = mutableListOf<String>()
        val moved = mutableListOf<Pair<String, String>>()
    }

    private suspend fun transfer(
        sources: List<String>,
        destination: String,
        move: Boolean,
        control: OperationControl,
    ): OperationOutcome {
        val entries = sources.mapNotNull { provider.stat(it) }
        if (entries.isEmpty()) throw StorageException.NotFound(sources.firstOrNull().orEmpty())
        entries.firstOrNull { it.isDirectory && FileNames.isSameOrDescendant(destination, it.path) }?.let {
            throw OperationException.SelfCopy(it.path)
        }
        if (move && entries.all { FileNames.parentOf(it.path) == destination.trimEnd('/') }) {
            throw OperationException.SameLocation(destination)
        }

        val totals = entries.map { measure(it, control) }
        val totalBytes = totals.sumOf { it.first }
        val totalItems = totals.sumOf { it.second }
        control.progress { it.copy(bytesTotal = totalBytes, itemsTotal = totalItems) }

        // Для перемещения в пределах тома место не нужно; проверяем только для копирования между томами.
        val needsSpace = !move || entries.any { !sameVolume(it.path, destination) }
        if (needsSpace) {
            val free = runCatching { provider.space(destination)?.free }.getOrNull()
            if (free != null && totalBytes > free) throw OperationException.NotEnoughSpace(totalBytes, free)
        }

        val state = TransferState()
        entries.forEachIndexed { index, entry ->
            control.checkpoint()
            try {
                val target = transferOne(entry, destination, move, control, state, remaining = entries.size - index - 1)
                if (target != null && move) state.moved += entry.path to target
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.failed++
                if (state.firstError == null) state.firstError = e
            }
        }
        return OperationOutcome(state.failed, state.firstError, state.changed, moved = state.moved)
    }

    /** Возвращает путь, куда попал элемент, или `null`, если он пропущен. */
    private suspend fun transferOne(
        source: FileEntry,
        targetDir: String,
        move: Boolean,
        control: OperationControl,
        state: TransferState,
        remaining: Int,
    ): String? {
        var targetPath = FileNames.child(targetDir, source.name)
        var mergeInto = false
        var replaceExisting: FileEntry? = null

        if (targetPath == source.path) {
            if (move) {
                skip(source, control)
                return null
            }
            targetPath = FileNames.child(targetDir, uniqueIn(targetDir, source.name, source.isDirectory))
        } else {
            val existing = provider.stat(targetPath)
            if (existing != null) {
                val decision = state.policy ?: control.decide(Conflict(source, existing, remaining)).also {
                    if (it.applyToAll) state.policy = it
                }
                when (decision.choice) {
                    ConflictChoice.SKIP -> {
                        skip(source, control)
                        return null
                    }
                    ConflictChoice.KEEP_BOTH ->
                        targetPath = FileNames.child(targetDir, uniqueIn(targetDir, source.name, source.isDirectory))
                    ConflictChoice.REPLACE ->
                        if (source.isDirectory && existing.isDirectory) mergeInto = true else replaceExisting = existing
                }
            }
        }

        // Быстрое перемещение переименованием, если цель свободна и том тот же.
        if (move && !mergeInto && replaceExisting == null) {
            val (bytes, items) = measure(source, control)
            val fast = runCatching { provider.moveFast(source.path, targetPath) }.getOrDefault(false)
            if (fast) {
                control.progress { it.copy(bytesDone = it.bytesDone + bytes, itemsDone = it.itemsDone + items) }
                state.changed += source.path
                state.changed += targetPath
                return targetPath
            }
        }

        if (replaceExisting != null && replaceExisting.isDirectory != source.isDirectory) {
            provider.delete(replaceExisting.path)
            replaceExisting = null
        }

        if (source.isDirectory) {
            if (!mergeInto) {
                provider.createDirectory(targetDir, FileNames.nameOf(targetPath))
            }
            var childFailed = false
            val children = provider.list(source.path, includeHidden = true)
            children.forEachIndexed { i, child ->
                control.checkpoint()
                try {
                    transferOne(child, targetPath, move, control, state, remaining = children.size - i - 1)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    childFailed = true
                    state.failed++
                    if (state.firstError == null) state.firstError = e
                }
            }
            provider.setLastModified(targetPath, source.lastModified)
            if (move && !childFailed && provider.list(source.path, includeHidden = true).isEmpty()) {
                provider.delete(source.path)
            }
        } else {
            copyFile(source, targetPath, replace = replaceExisting != null, control)
            state.changed += targetPath
            if (move) {
                provider.delete(source.path)
                state.changed += source.path
            }
        }
        return targetPath
    }

    private suspend fun copyFile(source: FileEntry, target: String, replace: Boolean, control: OperationControl) =
        writeFile(source.name, target, replace, source.lastModified, control) { provider.openRead(source.path) }

    /** Пишет поток во временный файл и только потом даёт ему конечное имя [target]. */
    private suspend fun writeFile(
        displayName: String,
        target: String,
        replace: Boolean,
        lastModified: Long?,
        control: OperationControl,
        open: () -> java.io.InputStream,
    ) {
        val dir = FileNames.parentOf(target) ?: "/"
        val temp = FileNames.child(dir, ".${FileNames.nameOf(target)}.qfpart")
        control.setTempFile(temp)
        control.progress { it.copy(currentName = displayName) }
        try {
            open().use { input ->
                provider.openWrite(temp).use { output ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        control.checkpoint()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        control.progress { it.copy(bytesDone = it.bytesDone + read) }
                    }
                    (output as? java.io.FileOutputStream)?.fd?.sync()
                }
            }
            if (lastModified != null) provider.setLastModified(temp, lastModified)
            if (replace) provider.delete(target)
            if (!provider.moveFast(temp, target)) throw StorageException.Io("Cannot finalize $target")
            control.progress { it.copy(itemsDone = it.itemsDone + 1) }
        } catch (e: Throwable) {
            runCatching { provider.delete(temp) }
            throw e
        } finally {
            control.setTempFile(null)
        }
    }

    // ---- Приём файлов из других приложений ----

    private suspend fun import(items: List<ImportItem>, destination: String, control: OperationControl): OperationOutcome {
        if (provider.stat(destination)?.isDirectory != true) throw StorageException.NotFound(destination)
        val sizes = items.map { it.text?.toByteArray()?.size?.toLong() ?: it.size }
        // Если хоть один размер неизвестен, показываем прогресс по числу файлов, а не по байтам.
        val totalBytes = if (sizes.all { it >= 0 }) sizes.sum() else 0L
        control.progress { it.copy(bytesTotal = totalBytes, itemsTotal = items.size) }
        if (totalBytes > 0) {
            val free = runCatching { provider.space(destination)?.free }.getOrNull()
            if (free != null && totalBytes > free) throw OperationException.NotEnoughSpace(totalBytes, free)
        }

        var failed = 0
        var firstError: Throwable? = null
        val changed = mutableListOf<String>()
        for (item in items) {
            control.checkpoint()
            try {
                val name = FileNames.sanitize(item.name)
                val target = FileNames.child(destination, uniqueIn(destination, name, isDirectory = false))
                writeFile(name, target, replace = false, lastModified = null, control) {
                    item.text?.byteInputStream() ?: openContent(item)
                }
                changed += target
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                if (firstError == null) firstError = e
                control.progress { it.copy(itemsDone = it.itemsDone + 1) }
            }
        }
        return OperationOutcome(failed, firstError, changed)
    }

    private fun openContent(item: ImportItem): java.io.InputStream {
        val uri = item.uri ?: throw StorageException.NotFound(item.name)
        return try {
            content.open(uri)
        } catch (e: StorageException.AccessDenied) {
            throw StorageException.AccessDenied(item.name, e)
        } catch (e: StorageException.NotFound) {
            throw StorageException.NotFound(item.name)
        }
    }

    private suspend fun skip(source: FileEntry, control: OperationControl) {
        val (bytes, items) = measure(source, control)
        control.progress { it.copy(bytesDone = it.bytesDone + bytes, itemsDone = it.itemsDone + items) }
    }

    private suspend fun uniqueIn(dir: String, name: String, isDirectory: Boolean): String {
        val taken = provider.list(dir, includeHidden = true).map { it.name }.toHashSet()
        return FileNames.uniqueName(name, isDirectory) { it in taken }
    }

    /** Размер в байтах и число файлов в дереве. */
    private suspend fun measure(entry: FileEntry, control: OperationControl): Pair<Long, Int> {
        if (!entry.isDirectory) return entry.size to 1
        var bytes = 0L
        var items = 0
        for (child in provider.list(entry.path, includeHidden = true)) {
            control.checkpoint()
            val (b, i) = measure(child, control)
            bytes += b
            items += i
        }
        return bytes to items
    }

    private fun sameVolume(a: String, b: String) =
        io.quietfiles.core.storage.LocalStorageProvider.volumeRootOf(a) ==
            io.quietfiles.core.storage.LocalStorageProvider.volumeRootOf(b)

    // ---- Удаление и корзина ----

    private suspend fun delete(paths: List<String>, control: OperationControl): OperationOutcome {
        control.progress { it.copy(itemsTotal = paths.size) }
        var failed = 0
        var first: Throwable? = null
        val changed = mutableListOf<String>()
        for (path in paths) {
            control.checkpoint()
            control.progress { it.copy(currentName = FileNames.nameOf(path)) }
            try {
                provider.delete(path)
                changed += path
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                if (first == null) first = e
            }
            control.progress { it.copy(itemsDone = it.itemsDone + 1) }
        }
        return OperationOutcome(failed, first, changed)
    }

    private suspend fun moveToTrash(paths: List<String>, control: OperationControl): OperationOutcome {
        control.progress { it.copy(itemsTotal = paths.size) }
        var failed = 0
        var first: Throwable? = null
        val ids = mutableListOf<Long>()
        val changed = mutableListOf<String>()
        for (path in paths) {
            control.checkpoint()
            try {
                val entry = provider.stat(path) ?: throw StorageException.NotFound(path)
                val size = measure(entry, control).first
                ids += trash.moveToTrash(path, size, entry.isDirectory)
                changed += path
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                if (first == null) first = e
            }
            control.progress { it.copy(itemsDone = it.itemsDone + 1) }
        }
        return OperationOutcome(failed, first, changed, trashIds = ids)
    }

    private suspend fun restore(ids: List<Long>, control: OperationControl): OperationOutcome {
        val items = trash.byIds(ids)
        control.progress { it.copy(itemsTotal = items.size) }
        var failed = 0
        var first: Throwable? = null
        val changed = mutableListOf<String>()
        for (item in items) {
            control.checkpoint()
            try {
                changed += trash.restore(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                if (first == null) first = e
            }
            control.progress { it.copy(itemsDone = it.itemsDone + 1) }
        }
        return OperationOutcome(failed, first, changed)
    }

    private companion object {
        const val BUFFER = 256 * 1024
    }
}
