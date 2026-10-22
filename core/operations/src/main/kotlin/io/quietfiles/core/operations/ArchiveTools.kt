package io.quietfiles.core.operations

import com.github.junrar.Archive
import com.github.junrar.exception.RarException
import com.github.junrar.exception.UnsupportedRarV5Exception
import io.quietfiles.core.common.IoDispatcher
import io.quietfiles.core.model.FileNames
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import javax.inject.Inject
import javax.inject.Singleton

/** Элемент архива. [path] — путь внутри архива через «/», без ведущего слэша. */
data class ArchiveEntry(val path: String, val isDirectory: Boolean, val size: Long, val lastModified: Long) {
    val name: String get() = FileNames.nameOf(path)
}

enum class ArchiveFormat { ZIP, SEVEN_Z, TAR, TAR_GZ, TAR_BZ2, TAR_XZ, GZ, BZ2, XZ, RAR }

/**
 * Архивы (F5.11, F10): просмотр без распаковки, распаковка с защитой от Zip Slip, сжатие в zip.
 * RAR — только распаковка (лицензия unrar запрещает создание RAR).
 */
@Singleton
class ArchiveTools @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    fun formatOf(name: String): ArchiveFormat? = formatOfName(name)

    fun isArchive(name: String): Boolean = formatOf(name) != null

    suspend fun list(archivePath: String): List<ArchiveEntry> = withContext(io) {
        val file = File(archivePath)
        val format = formatOf(file.name) ?: throw OperationException.ArchiveUnsupported(archivePath)
        wrapErrors(archivePath) {
            val raw = mutableListOf<ArchiveEntry>()
            forEachEntry(file, format, readData = false) { entry, _ -> raw += entry; true }
            withImplicitDirectories(raw)
        }
    }

    /**
     * Распаковывает [entries] (пусто — всё) в [destination]. Каждый путь проверяется: после нормализации
     * он обязан остаться внутри папки назначения (защита от Zip Slip).
     */
    suspend fun extract(
        archivePath: String,
        destination: String,
        entries: List<String>,
        control: OperationControl,
    ): List<String> = withContext(io) {
        val file = File(archivePath)
        val format = formatOf(file.name) ?: throw OperationException.ArchiveUnsupported(archivePath)
        val destRoot = File(destination).canonicalFile
        val wanted = entries.map { it.trim('/') }
        fun selected(path: String) = wanted.isEmpty() ||
            wanted.any { w -> path == w || path.startsWith("$w/") }
        // Корень извлечения: при выборе папки «a/b» её содержимое кладётся в destination/b/...
        fun relative(path: String): String {
            if (wanted.isEmpty()) return path
            val base = wanted.firstOrNull { w -> path == w || path.startsWith("$w/") } ?: return path
            val parent = FileNames.parentOf("/$base")?.trimStart('/').orEmpty()
            return if (parent.isEmpty()) path else path.removePrefix("$parent/")
        }

        val all = wrapErrors(archivePath) {
            val list = mutableListOf<ArchiveEntry>()
            forEachEntry(file, format, readData = false) { e, _ -> list += e; true }
            list
        }
        val total = all.filter { selected(it.path) && !it.isDirectory }.sumOf { it.size.coerceAtLeast(0) }
        val count = all.count { selected(it.path) && !it.isDirectory }
        control.progress { it.copy(bytesTotal = total, itemsTotal = count) }

        val written = mutableListOf<String>()
        wrapErrors(archivePath) {
            forEachEntry(file, format, readData = true) { entry, input ->
                if (!selected(entry.path)) return@forEachEntry true
                val target = safeTarget(destRoot, relative(entry.path))
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    val finalTarget = if (target.exists()) {
                        File(target.parentFile, FileNames.uniqueName(target.name, false) { File(target.parentFile, it).exists() })
                    } else target
                    control.progress { it.copy(currentName = entry.name) }
                    finalTarget.outputStream().use { out -> copyStream(requireNotNull(input), out, control) }
                    if (entry.lastModified > 0) finalTarget.setLastModified(entry.lastModified)
                    control.progress { it.copy(itemsDone = it.itemsDone + 1) }
                    written += finalTarget.path
                }
                true
            }
        }
        written
    }

    /** Сжатие в zip с именами в UTF-8. Пишется во временный файл, затем переименовывается. */
    suspend fun zip(sources: List<String>, target: String, control: OperationControl) = withContext(io) {
        val files = sources.map(::File)
        val total = files.sumOf { sizeOf(it) }
        val count = files.sumOf { countFiles(it) }
        control.progress { it.copy(bytesTotal = total, itemsTotal = count) }
        val targetFile = File(target)
        val temp = File(targetFile.parentFile, ".${targetFile.name}.qfpart")
        control.setTempFile(temp.path)
        try {
            ZipArchiveOutputStream(temp).use { zip ->
                zip.setEncoding("UTF-8")
                zip.setUseLanguageEncodingFlag(true)
                files.forEach { addToZip(zip, it, it.name, control) }
            }
            if (!temp.renameTo(targetFile)) throw java.io.IOException("Cannot rename ${temp.path}")
        } finally {
            if (temp.exists()) temp.delete()
            control.setTempFile(null)
        }
    }

    private suspend fun addToZip(zip: ZipArchiveOutputStream, file: File, name: String, control: OperationControl) {
        control.checkpoint()
        if (file.isDirectory) {
            zip.putArchiveEntry(ZipArchiveEntry(file, "$name/"))
            zip.closeArchiveEntry()
            file.listFiles()?.sortedBy { it.name }?.forEach { addToZip(zip, it, "$name/${it.name}", control) }
        } else {
            control.progress { it.copy(currentName = file.name) }
            zip.putArchiveEntry(ZipArchiveEntry(file, name))
            FileInputStream(file).use { copyStream(it, zip, control) }
            zip.closeArchiveEntry()
            control.progress { it.copy(itemsDone = it.itemsDone + 1) }
        }
    }

    private fun sizeOf(file: File): Long =
        if (file.isDirectory) file.listFiles()?.sumOf { sizeOf(it) } ?: 0 else file.length()

    private fun countFiles(file: File): Int =
        if (file.isDirectory) file.listFiles()?.sumOf { countFiles(it) } ?: 0 else 1

    private fun safeTarget(destRoot: File, entryPath: String): File {
        val cleaned = entryPath.replace('\\', '/').trimStart('/')
        val target = File(destRoot, cleaned).canonicalFile
        if (target != destRoot && !target.path.startsWith(destRoot.path + File.separator)) {
            throw OperationException.UnsafeArchiveEntry(entryPath)
        }
        return target
    }

    private suspend fun copyStream(input: InputStream, output: OutputStream, control: OperationControl) {
        val buffer = ByteArray(BUFFER)
        while (true) {
            control.checkpoint()
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            control.progress { it.copy(bytesDone = it.bytesDone + read) }
        }
    }

    /**
     * Перебор элементов. Для zip/7z/rar данные читаются по запросу, для tar-потоков — последовательно.
     * [block] возвращает `false`, чтобы остановить перебор.
     */
    private suspend fun forEachEntry(
        file: File,
        format: ArchiveFormat,
        readData: Boolean,
        block: suspend (ArchiveEntry, InputStream?) -> Boolean,
    ) {
        when (format) {
            ArchiveFormat.ZIP -> openZip(file).use { zip ->
                for (e in zip.entries.toList()) {
                    if (e.generalPurposeBit.usesEncryption()) throw OperationException.ArchiveEncrypted(file.path)
                    val entry = ArchiveEntry(e.name.trimStart('/'), e.isDirectory, e.size, e.time)
                    val stream = if (readData && !e.isDirectory) zip.getInputStream(e) else null
                    val go = stream.useOrNull { block(entry, it) }
                    if (!go) break
                }
            }
            ArchiveFormat.SEVEN_Z -> SevenZFile.builder().setFile(file).get().use { sz ->
                for (e in sz.entries) {
                    val entry = ArchiveEntry(e.name.trimStart('/'), e.isDirectory, e.size, if (e.hasLastModifiedDate) e.lastModifiedDate.time else 0)
                    val stream = if (readData && !e.isDirectory && e.hasStream()) sz.getInputStream(e) else null
                    val go = stream.useOrNull { block(entry, it) }
                    if (!go) break
                }
            }
            ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_BZ2, ArchiveFormat.TAR_XZ ->
                TarArchiveInputStream(decompress(file, format)).use { tar ->
                    iterateStream(tar) { e -> block(e, if (readData) tar else null) }
                }
            ArchiveFormat.GZ, ArchiveFormat.BZ2, ArchiveFormat.XZ -> {
                val inner = FileNames.baseName(file.name)
                val entry = ArchiveEntry(inner, false, -1, file.lastModified())
                decompress(file, format).use { block(entry, if (readData) it else null) }
            }
            ArchiveFormat.RAR -> Archive(file).use { rar ->
                if (rar.isEncrypted) throw OperationException.ArchiveEncrypted(file.path)
                for (h in rar.fileHeaders) {
                    if (h.isEncrypted) throw OperationException.ArchiveEncrypted(file.path)
                    val entry = ArchiveEntry(h.fileName.replace('\\', '/').trimStart('/'), h.isDirectory, h.fullUnpackSize, h.mTime?.time ?: 0)
                    val stream = if (readData && !h.isDirectory) rar.getInputStream(h) else null
                    val go = stream.useOrNull { block(entry, it) }
                    if (!go) break
                }
            }
        }
    }

    private suspend fun iterateStream(input: ArchiveInputStream<*>, block: suspend (ArchiveEntry) -> Boolean) {
        while (true) {
            val e = input.nextEntry ?: break
            val modified = runCatching { e.lastModifiedDate.time }.getOrDefault(0)
            if (!block(ArchiveEntry(e.name.trimStart('/').removePrefix("./"), e.isDirectory, e.size, modified))) break
        }
    }

    private fun decompress(file: File, format: ArchiveFormat): InputStream {
        val raw = BufferedInputStream(FileInputStream(file))
        return when (format) {
            ArchiveFormat.TAR -> raw
            ArchiveFormat.TAR_GZ, ArchiveFormat.GZ -> GzipCompressorInputStream(raw, true)
            ArchiveFormat.TAR_BZ2, ArchiveFormat.BZ2 -> BZip2CompressorInputStream(raw, true)
            ArchiveFormat.TAR_XZ, ArchiveFormat.XZ -> XZCompressorInputStream(raw, true)
            else -> error("Not a stream format: $format")
        }
    }

    /**
     * Zip без флага UTF-8 из Windows с русскими именами обычно в CP866. Если имена не читаются как UTF-8,
     * открываем заново в CP866.
     */
    private fun openZip(file: File): ZipFile {
        val utf8 = ZipFile.builder().setFile(file).setCharset(Charsets.UTF_8).get()
        val broken = utf8.entries.toList().any { it.name.contains('�') }
        if (!broken) return utf8
        utf8.close()
        return ZipFile.builder().setFile(file).setCharset(Charset.forName("CP866")).get()
    }

    private inline fun <T> wrapErrors(path: String, block: () -> T): T = try {
        block()
    } catch (e: OperationException) {
        throw e
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: UnsupportedRarV5Exception) {
        throw OperationException.ArchiveUnsupported(path)
    } catch (e: org.apache.commons.compress.PasswordRequiredException) {
        throw OperationException.ArchiveEncrypted(path)
    } catch (e: RarException) {
        throw OperationException.ArchiveCorrupted(path, e)
    } catch (e: java.util.zip.ZipException) {
        throw OperationException.ArchiveCorrupted(path, e)
    } catch (e: java.io.EOFException) {
        throw OperationException.ArchiveCorrupted(path, e)
    } catch (e: org.apache.commons.compress.archivers.ArchiveException) {
        throw OperationException.ArchiveCorrupted(path, e)
    }

    private inline fun <T> InputStream?.useOrNull(block: (InputStream?) -> T): T =
        if (this == null) block(null) else use { block(it) }

    companion object {
        private const val BUFFER = 256 * 1024

        fun formatOfName(name: String): ArchiveFormat? {
            val lower = name.lowercase()
            return when {
                lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> ArchiveFormat.TAR_GZ
                lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") || lower.endsWith(".tbz") -> ArchiveFormat.TAR_BZ2
                lower.endsWith(".tar.xz") || lower.endsWith(".txz") -> ArchiveFormat.TAR_XZ
                lower.endsWith(".zip") || lower.endsWith(".jar") || lower.endsWith(".apk") -> ArchiveFormat.ZIP
                lower.endsWith(".7z") -> ArchiveFormat.SEVEN_Z
                lower.endsWith(".tar") -> ArchiveFormat.TAR
                lower.endsWith(".gz") -> ArchiveFormat.GZ
                lower.endsWith(".bz2") -> ArchiveFormat.BZ2
                lower.endsWith(".xz") -> ArchiveFormat.XZ
                lower.endsWith(".rar") -> ArchiveFormat.RAR
                else -> null
            }
        }

        /** Можно ли открыть файл как архив по имени. APK — тоже zip, но открывается установщиком. */
        fun isBrowsableArchive(name: String): Boolean =
            formatOfName(name) != null && !name.endsWith(".apk", ignoreCase = true) && !name.endsWith(".jar", ignoreCase = true)

        /** Добавляет папки, которые есть только в путях файлов («a/b/c.txt» → «a», «a/b»). */
        fun withImplicitDirectories(entries: List<ArchiveEntry>): List<ArchiveEntry> {
            val known = entries.associateBy { it.path.trimEnd('/') }.toMutableMap()
            entries.forEach { e ->
                var parent = FileNames.parentOf("/" + e.path.trimEnd('/'))
                while (parent != null && parent != "/") {
                    val key = parent.trimStart('/')
                    if (key !in known) known[key] = ArchiveEntry(key, true, 0, 0)
                    parent = FileNames.parentOf(parent)
                }
            }
            return known.map { (key, e) -> e.copy(path = key) }
        }
    }
}
