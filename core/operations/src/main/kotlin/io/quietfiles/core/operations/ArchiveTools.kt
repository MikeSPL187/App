package io.quietfiles.core.operations

import com.github.junrar.Archive
import com.github.junrar.exception.RarException
import com.github.junrar.exception.UnsupportedRarV5Exception
import net.lingala.zip4j.ZipFile as Zip4jFile
import net.lingala.zip4j.exception.ZipException as Zip4jException
import io.quietfiles.core.common.IoDispatcher
import io.quietfiles.core.model.FileNames
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.ArchiveInputStream
import net.lingala.zip4j.io.outputstream.ZipOutputStream as Zip4jOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
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

    /** Содержимое архива. Имена в зашифрованном zip видны и без пароля, в 7z со скрытыми именами — нет. */
    suspend fun list(archivePath: String, password: CharArray? = null): List<ArchiveEntry> = withContext(io) {
        val file = File(archivePath)
        val format = formatOf(file.name) ?: throw OperationException.ArchiveUnsupported(archivePath)
        wrapErrors(archivePath, password) {
            val raw = mutableListOf<ArchiveEntry>()
            forEachEntry(file, format, readData = false, password) { entry, _ -> raw += entry; true }
            withImplicitDirectories(raw)
        }
    }

    /** Нужен ли пароль, чтобы распаковать архив (F5.11): zip с шифрованием, 7z с AES, rar с паролем. */
    suspend fun isEncrypted(archivePath: String): Boolean = withContext(io) {
        val file = File(archivePath)
        try {
            when (formatOf(file.name)) {
                ArchiveFormat.ZIP -> openZip(file).use { zip -> zip.entries.toList().any { it.generalPurposeBit.usesEncryption() } }
                // Имена могут быть открыты, а данные — зашифрованы: пробуем прочитать первый байт.
                ArchiveFormat.SEVEN_Z -> SevenZFile.builder().setFile(file).get().use { sz ->
                    val first = sz.entries.firstOrNull { it.hasStream() && it.size > 0 }
                    first != null && runCatching { sz.getInputStream(first).use { it.read() } }
                        .exceptionOrNull() is org.apache.commons.compress.PasswordRequiredException
                }
                ArchiveFormat.RAR -> Archive(file).use { rar -> rar.isEncrypted || rar.fileHeaders.any { it.isEncrypted } }
                else -> false
            }
        } catch (e: org.apache.commons.compress.PasswordRequiredException) {
            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
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
        password: CharArray? = null,
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

        val all = wrapErrors(archivePath, password) {
            val list = mutableListOf<ArchiveEntry>()
            forEachEntry(file, format, readData = false, password) { e, _ -> list += e; true }
            list
        }
        val total = all.filter { selected(it.path) && !it.isDirectory }.sumOf { it.size.coerceAtLeast(0) }
        val count = all.count { selected(it.path) && !it.isDirectory }
        control.progress { it.copy(bytesTotal = total, itemsTotal = count) }

        // Защита от «zip-бомб» (F10.3): заявленный размер должен помещаться, а фактическая запись
        // не может съесть память целиком, даже если заголовки архива лгут о размерах.
        // getAllocatableBytes работает только для внутренних томов приложения, а распаковывают и на SD-карты.
        @Suppress("UsableSpace")
        val free = destRoot.usableSpace
        if (free > 0 && total > free) throw OperationException.NotEnoughSpace(total, free)
        val budget = WriteBudget(if (free > 0) (free - SPACE_RESERVE).coerceAtLeast(0) else Long.MAX_VALUE)

        val written = mutableListOf<String>()
        wrapErrors(archivePath, password) {
            forEachEntry(file, format, readData = true, password) { entry, input ->
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
                    // Как и при копировании: пишем во временный файл, конечное имя — только целому файлу.
                    val temp = File(finalTarget.parentFile, ".${finalTarget.name}.qfpart")
                    control.setTempFile(temp.path)
                    try {
                        temp.outputStream().use { out -> copyStream(requireNotNull(input), out, control, budget) }
                        if (entry.lastModified > 0) temp.setLastModified(entry.lastModified)
                        if (!temp.renameTo(finalTarget)) throw java.io.IOException("Cannot finalize ${finalTarget.path}")
                    } catch (e: Throwable) {
                        temp.delete()
                        throw e
                    } finally {
                        control.setTempFile(null)
                    }
                    control.progress { it.copy(itemsDone = it.itemsDone + 1) }
                    written += finalTarget.path
                }
                true
            }
        }
        written
    }

    /**
     * Сжатие в zip с именами в UTF-8. Пишется во временный файл, затем переименовывается.
     * С [password] — шифрование AES-256 (F5.11), его открывают 7-Zip, WinRAR и системы.
     */
    suspend fun zip(sources: List<String>, target: String, control: OperationControl, password: CharArray? = null) = withContext(io) {
        val files = sources.map(::File)
        val total = files.sumOf { sizeOf(it) }
        val count = files.sumOf { countFiles(it) }
        control.progress { it.copy(bytesTotal = total, itemsTotal = count) }
        val targetFile = File(target)
        val temp = File(targetFile.parentFile, ".${targetFile.name}.qfpart")
        control.setTempFile(temp.path)
        try {
            if (password != null && password.isNotEmpty()) {
                Zip4jOutputStream(temp.outputStream().buffered(), password).use { zip ->
                    files.forEach { addToEncryptedZip(zip, it, it.name, control) }
                }
            } else {
                ZipArchiveOutputStream(temp).use { zip ->
                    zip.setEncoding("UTF-8")
                    zip.setUseLanguageEncodingFlag(true)
                    files.forEach { addToZip(zip, it, it.name, control) }
                }
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

    private suspend fun addToEncryptedZip(zip: Zip4jOutputStream, file: File, name: String, control: OperationControl) {
        control.checkpoint()
        val params = ZipParameters().apply {
            fileNameInZip = if (file.isDirectory) "$name/" else name
            lastModifiedFileTime = file.lastModified()
            if (!file.isDirectory) {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            }
        }
        zip.putNextEntry(params)
        if (file.isDirectory) {
            zip.closeEntry()
            file.listFiles()?.sortedBy { it.name }?.forEach { addToEncryptedZip(zip, it, "$name/${it.name}", control) }
        } else {
            control.progress { it.copy(currentName = file.name) }
            FileInputStream(file).use { copyStream(it, zip, control) }
            zip.closeEntry()
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

    /** Сколько ещё можно записать при распаковке. */
    private class WriteBudget(var remaining: Long)

    private suspend fun copyStream(input: InputStream, output: OutputStream, control: OperationControl, budget: WriteBudget? = null) {
        val buffer = ByteArray(BUFFER)
        while (true) {
            control.checkpoint()
            val read = input.read(buffer)
            if (read < 0) break
            if (budget != null) {
                budget.remaining -= read
                if (budget.remaining < 0) throw OperationException.ArchiveTooLarge()
            }
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
        password: CharArray?,
        block: suspend (ArchiveEntry, InputStream?) -> Boolean,
    ) {
        when (format) {
            ArchiveFormat.ZIP -> openZip(file).use { zip ->
                val charset = zip.encoding
                val entries = zip.entries.toList()
                if (readData && entries.any { it.generalPurposeBit.usesEncryption() }) {
                    // Шифрованный zip (ZipCrypto или AES) читает zip4j; имена — в той же кодировке.
                    if (password == null || password.isEmpty()) throw OperationException.ArchiveEncrypted(file.path)
                    zip.close()
                    forEachEncryptedZip(file, password, Charset.forName(charset), block)
                    return
                }
                for (e in entries) {
                    val entry = ArchiveEntry(e.name.trimStart('/'), e.isDirectory, e.size, e.time)
                    val stream = if (readData && !e.isDirectory) zip.getInputStream(e) else null
                    val go = stream.useOrNull { block(entry, it) }
                    if (!go) break
                }
            }
            ArchiveFormat.SEVEN_Z -> SevenZFile.builder().setFile(file).apply { if (password != null) setPassword(password) }.get().use { sz ->
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
            ArchiveFormat.RAR -> (if (password != null) Archive(file, String(password)) else Archive(file)).use { rar ->
                if (rar.isEncrypted && password == null) throw OperationException.ArchiveEncrypted(file.path)
                for (h in rar.fileHeaders) {
                    if (h.isEncrypted && password == null && readData) throw OperationException.ArchiveEncrypted(file.path)
                    val entry = ArchiveEntry(h.fileName.replace('\\', '/').trimStart('/'), h.isDirectory, h.fullUnpackSize, h.mTime?.time ?: 0)
                    val stream = if (readData && !h.isDirectory) rar.getInputStream(h) else null
                    val go = stream.useOrNull { block(entry, it) }
                    if (!go) break
                }
            }
        }
    }

    private suspend fun forEachEncryptedZip(
        file: File,
        password: CharArray,
        charset: Charset,
        block: suspend (ArchiveEntry, InputStream?) -> Boolean,
    ) {
        Zip4jFile(file, password).use { zip ->
            zip.charset = charset
            for (h in zip.fileHeaders) {
                val entry = ArchiveEntry(h.fileName.trimStart('/'), h.isDirectory, h.uncompressedSize, h.lastModifiedTimeEpoch)
                val stream = if (!h.isDirectory) zip.getInputStream(h) else null
                val go = stream.useOrNull { block(entry, it) }
                if (!go) break
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

    /**
     * Ошибки библиотек — в понятные пользователю. С паролем повреждённые данные почти всегда значат
     * неверный пароль: расшифрованный мусор не проходит проверку контрольной суммы.
     */
    private inline fun <T> wrapErrors(path: String, password: CharArray?, block: () -> T): T = try {
        block()
    } catch (e: OperationException) {
        throw e
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        throw mapArchiveError(path, password != null, e)
    }

    private fun mapArchiveError(path: String, withPassword: Boolean, e: Exception): Exception = when (e) {
        is Zip4jException ->
            if (e.type == Zip4jException.Type.WRONG_PASSWORD || withPassword) OperationException.WrongPassword(path) else OperationException.ArchiveCorrupted(path, e)
        is UnsupportedRarV5Exception -> OperationException.ArchiveUnsupported(path)
        is org.apache.commons.compress.PasswordRequiredException ->
            if (withPassword) OperationException.WrongPassword(path) else OperationException.ArchiveEncrypted(path)
        is RarException, is java.util.zip.ZipException, is java.io.EOFException, is org.apache.commons.compress.archivers.ArchiveException ->
            if (withPassword) OperationException.WrongPassword(path) else OperationException.ArchiveCorrupted(path, e)
        is java.io.FileNotFoundException -> e
        is java.io.IOException -> if (withPassword) OperationException.WrongPassword(path) else e
        else -> e
    }

    private inline fun <T> InputStream?.useOrNull(block: (InputStream?) -> T): T =
        if (this == null) block(null) else use { block(it) }

    companion object {
        private const val BUFFER = 256 * 1024

        /** Сколько места оставляем системе при распаковке. */
        private const val SPACE_RESERVE = 64L * 1024 * 1024

        /** Во сколько раз распакованное может быть больше архива без предупреждения (F10.3). */
        const val SUSPICIOUS_RATIO = 100

        fun isSuspiciousRatio(unpacked: Long, packed: Long): Boolean =
            packed > 0 && unpacked / packed >= SUSPICIOUS_RATIO

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
