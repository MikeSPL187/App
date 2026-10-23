package io.quietfiles.core.operations.cleanup

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.quietfiles.core.common.ApplicationScope
import io.quietfiles.core.common.IoDispatcher
import io.quietfiles.core.data.repository.SettingsRepository
import io.quietfiles.core.model.FileEntry
import io.quietfiles.core.model.FileNames
import io.quietfiles.core.model.cleanup.CleanupCategory
import io.quietfiles.core.model.cleanup.CleanupFile
import io.quietfiles.core.model.cleanup.CleanupRules
import io.quietfiles.core.model.cleanup.CleanupSection
import io.quietfiles.core.model.cleanup.CleanupSelection
import io.quietfiles.core.model.cleanup.DuplicateFinder
import io.quietfiles.core.model.cleanup.FolderSizes
import io.quietfiles.core.operations.TrashManager
import io.quietfiles.core.storage.ApkInspector
import io.quietfiles.core.storage.ApkStatus
import io.quietfiles.core.storage.StorageVolumes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** Кэш самого приложения (миниатюры и т. п.): чистит модуль, который его завёл. */
interface AppCacheCleaner {
    fun size(): Long
    suspend fun clear()
}

/** Этап поиска для подписи «Ищем дубликаты… 40%». */
enum class ScanPhase { FILES, DUPLICATES, APKS, DONE }

data class CleanupScanState(
    val scanning: Boolean = false,
    val phase: ScanPhase = ScanPhase.FILES,
    /** 0..1 внутри этапа, null — неизвестно. */
    val progress: Float? = null,
    val sections: List<CleanupSection> = emptyList(),
    val scannedAt: Long? = null,
    val largeThreshold: Long = CleanupRules.DEFAULT_LARGE_THRESHOLD,
    /** Сколько дней лежит «старое» в корзине: срок хранения из настроек (F5.7). */
    val trashAgeDays: Int = CleanupRules.DEFAULT_TRASH_AGE_DAYS,
) {
    fun section(category: CleanupCategory): CleanupSection? = sections.firstOrNull { it.category == category }

    /** «Можно освободить N» на главной и в «Инструментах» (F2.3): только безопасные пункты. */
    val safeBytes: Long get() = CleanupSelection().selectedBytes(sections)
}

/**
 * Поиск того, что можно удалить (F13): обходит тома один раз и раскладывает найденное по пунктам.
 * Результат живёт в памяти процесса, чтобы главная и «Инструменты» показывали одно и то же.
 */
@Singleton
class CleanupScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val volumes: StorageVolumes,
    private val apks: ApkInspector,
    private val trash: TrashManager,
    private val settings: SettingsRepository,
    private val appCache: AppCacheCleaner,
    @IoDispatcher private val io: CoroutineDispatcher,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(CleanupScanState())
    val state: StateFlow<CleanupScanState> = _state.asStateFlow()

    private var job: Job? = null
    private var files: List<FileEntry> = emptyList()

    /** Запускает поиск, если его ещё не было или [force]; идущий поиск не перезапускается без [force]. */
    fun scan(force: Boolean = false) {
        if (!force && (_state.value.scanning || _state.value.scannedAt != null)) return
        job?.cancel()
        job = scope.launch {
            _state.update { it.copy(scanning = true, phase = ScanPhase.FILES, progress = null) }
            try {
                val sections = withContext(io) { collect() }
                _state.update { it.copy(scanning = false, phase = ScanPhase.DONE, progress = null, sections = sections, scannedAt = System.currentTimeMillis()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(scanning = false, phase = ScanPhase.DONE, progress = null) }
            }
        }
    }

    /** Порог «больших файлов» 100 или 500 МБ: пересчёт без нового обхода. */
    fun setLargeThreshold(bytes: Long) {
        _state.update { s ->
            s.copy(largeThreshold = bytes, sections = s.sections.map { if (it.category == CleanupCategory.LARGE_FILES) largeFiles(bytes) else it })
        }
    }

    private suspend fun collect(): List<CleanupSection> {
        val roots = volumes.current().map { File(it.rootPath) }
        files = walk(roots)
        val ctx = coroutineContext

        _state.update { it.copy(phase = ScanPhase.DUPLICATES, progress = 0f) }
        val groups = DuplicateFinder(checkCancelled = { ctx.ensureActive() }).find(files) { done, total ->
            _state.update { it.copy(progress = if (total > 0) done.toFloat() / total else null) }
        }
        val duplicates = CleanupSection(
            CleanupCategory.DUPLICATES,
            groups.flatMap { g -> g.copies.map { CleanupFile(it) } },
            groups,
        )

        _state.update { it.copy(phase = ScanPhase.APKS, progress = null) }
        val installed = files.filter { FileNames.extension(it.name).equals("apk", ignoreCase = true) }.filter { entry ->
            coroutineContext.ensureActive()
            val status = apks.inspect(entry.path, entry.lastModified)?.status
            // Установлена та же или более новая версия — файл больше не нужен.
            status == ApkStatus.INSTALLED || status == ApkStatus.OLDER
        }

        val retention = settings.settings.first().trashRetentionDays
        val trashAge = if (retention > 0) retention else CleanupRules.DEFAULT_TRASH_AGE_DAYS
        _state.update { it.copy(trashAgeDays = trashAge) }
        val oldTrash = trash.olderThan(trashAge).map { item ->
            CleanupFile(FileEntry(item.trashPath, item.name, item.isDirectory, item.size, item.deletedAt), trashId = item.id)
        }

        val now = System.currentTimeMillis()
        val downloads = roots.map { File(it, "Download").path + "/" }
        val oldDownloads = files.filter { f -> downloads.any { f.path.startsWith(it) } && CleanupRules.isOldDownload(f, now) }

        return listOf(
            duplicates,
            CleanupSection(CleanupCategory.INSTALLED_APKS, installed.map { CleanupFile(it) }),
            cache(roots),
            CleanupSection(CleanupCategory.OLD_TRASH, oldTrash),
            largeFiles(_state.value.largeThreshold),
            CleanupSection(CleanupCategory.OLD_DOWNLOADS, oldDownloads.map { CleanupFile(it) }),
        )
    }

    private fun largeFiles(threshold: Long) = CleanupSection(
        CleanupCategory.LARGE_FILES,
        files.filter { it.size >= threshold }.sortedByDescending { it.size }.map { CleanupFile(it) },
    )

    /**
     * Кэш, который восстановится сам (F13.4): миниатюры системы в общих папках и кэш приложения.
     * Кэш других приложений в Android/data с Android 11 недоступен — его очищает система по запросу.
     */
    private fun cache(roots: List<File>): CleanupSection {
        val dirs = roots.flatMap { root -> CleanupRules.THUMBNAIL_DIRS.map { File(root, it) } }.filter { it.isDirectory }
        val items = dirs.mapNotNull { dir ->
            val (bytes, _) = FolderSizes.total(dir)
            if (bytes > 0) CleanupFile(FileEntry(dir.path, dir.name, true, bytes, dir.lastModified())) else null
        }
        val own = appCache.size()
        val ownItem = if (own > 0) listOf(CleanupFile(FileEntry(APP_CACHE_PATH, context.packageName, true, own, 0))) else emptyList()
        return CleanupSection(CleanupCategory.CACHE, items + ownItem)
    }

    /** Все файлы томов, кроме скрытых папок, данных приложений и нашей корзины. */
    private suspend fun walk(roots: List<File>): List<FileEntry> {
        val result = ArrayList<FileEntry>()
        val stack = ArrayDeque(roots)
        while (stack.isNotEmpty() && result.size < MAX_FILES) {
            coroutineContext.ensureActive()
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (child.name.startsWith(".")) continue
                if (child.isDirectory) {
                    if (child.name == "Android" && child.parentFile in roots) continue
                    if (!Files.isSymbolicLink(child.toPath())) stack.addLast(child)
                } else {
                    result += FileEntry(child.path, child.name, false, child.length(), child.lastModified())
                }
            }
        }
        return result
    }

    /** После очистки найденное устарело: в следующий раз ищем заново. */
    fun invalidate() {
        job?.cancel()
        files = emptyList()
        _state.update { CleanupScanState(largeThreshold = it.largeThreshold) }
    }

    companion object {
        /** Условный путь для кэша приложения: удаляется не как файл, а через [AppCacheCleaner]. */
        const val APP_CACHE_PATH = "app-cache:"
        private const val MAX_FILES = 300_000
    }
}
