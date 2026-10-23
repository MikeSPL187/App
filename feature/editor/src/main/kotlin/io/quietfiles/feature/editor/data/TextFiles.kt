package io.quietfiles.feature.editor.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.quietfiles.core.common.IoDispatcher
import io.quietfiles.core.model.FileNames
import io.quietfiles.core.storage.MediaIndex
import io.quietfiles.core.storage.StorageException
import io.quietfiles.core.model.text.Detection
import io.quietfiles.core.model.text.Encodings
import io.quietfiles.feature.editor.text.LineEnding
import io.quietfiles.core.model.text.TextEncoding
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Что получилось при открытии файла. */
sealed interface OpenedText {
    val path: String
    val size: Long
    val lastModified: Long

    /** Файл помещается в память и правится целиком (F12.1, до 5 МБ). */
    data class Editable(
        override val path: String,
        override val size: Long,
        override val lastModified: Long,
        val text: String,
        val detection: Detection,
        val encoding: TextEncoding,
        val lineEnding: LineEnding,
        /** Кодировка, в которой текст выглядит правильнее выбранной (макет 25). */
        val suggestion: TextEncoding?,
        val readOnly: Boolean,
    ) : OpenedText

    /** Большой файл: только чтение по страницам (F12.1, до 200 МБ). */
    data class Large(
        override val path: String,
        override val size: Long,
        override val lastModified: Long,
        val encoding: TextEncoding,
    ) : OpenedText

    data class Binary(override val path: String, override val size: Long, override val lastModified: Long) : OpenedText
    data class TooLarge(override val path: String, override val size: Long, override val lastModified: Long) : OpenedText
}

/** Файл изменили снаружи, пока он был открыт: сохранение перезаписало бы чужие правки. */
class ModifiedExternallyException(val path: String) : Exception("Modified externally: $path")

/** Текст нельзя сохранить в выбранной кодировке без потерь. */
class UnencodableTextException(val encoding: TextEncoding) : Exception("Cannot encode in ${encoding.label}")

@Singleton
class TextFiles @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
    private val mediaIndex: MediaIndex,
) {
    suspend fun open(path: String, forceEncoding: TextEncoding? = null, forceText: Boolean = false): OpenedText = withContext(io) {
        val file = File(path)
        if (!file.isFile) throw StorageException.NotFound(path)
        if (!file.canRead()) throw StorageException.AccessDenied(path)
        val size = file.length()
        val modified = file.lastModified()
        if (size > MAX_VIEW_BYTES) return@withContext OpenedText.TooLarge(path, size, modified)

        val head = ByteArray(minOf(size, Encodings.SAMPLE_BYTES.toLong()).toInt())
        RandomAccessFile(file, "r").use { it.readFully(head) }
        val detection = Encodings.detect(head)
        val encoding = forceEncoding ?: detection.encoding
        if (!forceText && forceEncoding == null && looksBinary(head, detection)) return@withContext OpenedText.Binary(path, size, modified)
        if (size > MAX_EDIT_BYTES) return@withContext OpenedText.Large(path, size, modified, encoding)

        val bytes = file.readBytes()
        val raw = Encodings.decode(bytes, bytes.size, encoding)
        OpenedText.Editable(
            path = path,
            size = size,
            lastModified = modified,
            text = LineEnding.normalize(raw),
            detection = detection,
            encoding = encoding,
            lineEnding = LineEnding.detect(raw),
            suggestion = Encodings.suggest(bytes, bytes.size, encoding),
            readOnly = !file.canWrite(),
        )
    }

    /**
     * Сохранение: текст во временный файл рядом, затем переименование поверх — оригинал не
     * повреждается, даже если запись прервётся. Возвращает новое время изменения.
     */
    suspend fun save(
        path: String,
        text: String,
        encoding: TextEncoding,
        withBom: Boolean,
        lineEnding: LineEnding,
        expectedLastModified: Long?,
    ): Long = withContext(io) {
        val file = File(path)
        if (expectedLastModified != null && file.exists() && file.lastModified() != expectedLastModified) {
            throw ModifiedExternallyException(path)
        }
        if (!Encodings.canEncode(text, encoding)) throw UnencodableTextException(encoding)
        val bytes = Encodings.encode(LineEnding.apply(text, lineEnding), encoding, withBom)
        val temp = File(file.parentFile, ".${file.name}.qfpart")
        try {
            FileOutputStream(temp).use {
                it.write(bytes)
                it.fd.sync()
            }
            if (!temp.renameTo(file)) throw StorageException.Io("Cannot replace $path")
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
        mediaIndex.notifyChanged(listOf(path))
        file.lastModified()
    }

    private fun looksBinary(head: ByteArray, detection: Detection): Boolean {
        if (detection.encoding == TextEncoding.UTF_16LE || detection.encoding == TextEncoding.UTF_16BE) return false
        if (head.isEmpty()) return false
        val zeros = head.count { it.toInt() == 0 }
        return zeros > 0 && zeros * 100 > head.size
    }

    companion object {
        const val MAX_EDIT_BYTES = 5L * 1024 * 1024
        const val MAX_VIEW_BYTES = 200L * 1024 * 1024
    }
}

/**
 * Черновики (F12.7): если система закрыла приложение с несохранёнными правками, текст
 * не теряется и предлагается при следующем открытии того же файла.
 */
@Singleton
class Drafts @Inject constructor(
    @ApplicationContext context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    @Serializable
    data class Draft(
        val path: String,
        val text: String,
        val encoding: TextEncoding,
        val withBom: Boolean,
        val lineEnding: LineEnding,
        /** Время изменения файла, с которого начиналась правка. */
        val baseLastModified: Long,
        val savedAt: Long,
    )

    private val dir = File(context.filesDir, "drafts")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun save(draft: Draft) = withContext(io) {
        dir.mkdirs()
        val target = fileFor(draft.path)
        val temp = File(dir, target.name + ".tmp")
        temp.writeText(json.encodeToString(Draft.serializer(), draft))
        temp.renameTo(target)
    }

    suspend fun load(path: String): Draft? = withContext(io) {
        val f = fileFor(path)
        if (!f.isFile) return@withContext null
        runCatching { json.decodeFromString(Draft.serializer(), f.readText()) }.getOrNull()?.takeIf { it.path == path }
    }

    suspend fun delete(path: String) = withContext(io) {
        fileFor(path).delete()
        Unit
    }

    private fun fileFor(path: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(path.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.json")
    }
}

/**
 * Постраничное чтение большого файла: при открытии строится индекс начал строк,
 * а текст строк читается с диска только для видимой части.
 */
class LargeTextReader(private val path: String, val encoding: TextEncoding) {
    private var starts = IntArray(1024)

    var lineCount: Int = 0
        private set

    val fileName: String get() = FileNames.nameOf(path)

    /** Индексирует файл; [onProgress] получает долю от 0 до 1. */
    fun index(onProgress: (Float) -> Unit = {}) {
        val file = File(path)
        val size = file.length()
        val unit = if (encoding.isByteLineSplittable) 1 else 2
        val bigEndian = encoding == TextEncoding.UTF_16BE
        var count = 0
        fun add(offset: Int) {
            if (count == starts.size) starts = starts.copyOf(count * 2)
            starts[count++] = offset
        }
        val bom = Encodings.bomOf(ByteArray(4).also { RandomAccessFile(file, "r").use { r -> r.read(it) } })
        add(if (bom == encoding) encoding.bom.size else 0)
        val buffer = ByteArray(1 shl 20)
        var position = 0L
        file.inputStream().buffered(1 shl 20).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                var i = 0
                while (i < read) {
                    val abs = position + i
                    val isNewline = if (unit == 1) {
                        buffer[i].toInt() == 0x0A
                    } else {
                        abs % 2 == (if (bigEndian) 1L else 0L) && buffer[i].toInt() == 0x0A &&
                            (if (bigEndian) i > 0 && buffer[i - 1].toInt() == 0 else i + 1 < read && buffer[i + 1].toInt() == 0)
                    }
                    if (isNewline) {
                        // Следующая строка начинается сразу за переводом: «0A», «0A 00» (LE) или «00 0A» (BE).
                        val next = if (unit == 1 || bigEndian) abs + 1 else abs + 2
                        if (next < size) add(next.toInt())
                    }
                    i++
                }
                position += read
                onProgress((position.toFloat() / size).coerceAtMost(1f))
            }
        }
        lineCount = count
    }

    /** Строки с [from] по [from] + [count] без перевода строки в конце. */
    fun lines(from: Int, count: Int): List<String> {
        val end = minOf(lineCount, from + count)
        if (from >= end) return emptyList()
        val file = File(path)
        val size = file.length()
        val startByte = starts[from].toLong()
        val endByte = if (end < lineCount) starts[end].toLong() else size
        val bytes = ByteArray((endByte - startByte).toInt())
        RandomAccessFile(file, "r").use {
            it.seek(startByte)
            it.readFully(bytes)
        }
        val result = ArrayList<String>(end - from)
        for (line in from until end) {
            val a = (starts[line] - startByte).toInt()
            val b = (if (line + 1 < lineCount) starts[line + 1].toLong() else size).let { (it - startByte).toInt() }
            result += String(bytes, a, b - a, encoding.charset).trimEnd('\n', '\r')
        }
        return result
    }
}
