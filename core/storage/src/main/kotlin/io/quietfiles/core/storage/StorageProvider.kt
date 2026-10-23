package io.quietfiles.core.storage

import io.quietfiles.core.model.FileEntry
import io.quietfiles.core.model.StorageSpace
import io.quietfiles.core.model.network.RemoteProblem
import java.io.InputStream
import java.io.OutputStream

/**
 * Доступ к одному типу хранилища (раздел 9.2 ТЗ). Операции F5 работают только через этот интерфейс,
 * поэтому сетевые и облачные провайдеры в следующих версиях подключаются без изменения очереди операций.
 *
 * Все функции могут бросить [StorageException].
 */
interface StorageProvider {
    suspend fun list(path: String, includeHidden: Boolean): List<FileEntry>
    suspend fun stat(path: String): FileEntry?
    suspend fun exists(path: String): Boolean
    suspend fun createDirectory(parent: String, name: String): FileEntry
    suspend fun createFile(parent: String, name: String): FileEntry
    suspend fun rename(path: String, newName: String): FileEntry

    /**
     * Перемещение без копирования данных, если источник и цель на одном томе.
     * Возвращает `false`, если так переместить нельзя и нужно копировать.
     */
    suspend fun moveFast(from: String, to: String): Boolean

    /** Рекурсивное удаление. */
    suspend fun delete(path: String)
    suspend fun space(path: String): StorageSpace?
    fun openRead(path: String): InputStream

    /** Чтение с середины файла: перемотка видео и докачка без повторной загрузки начала (F16.7). */
    fun openRead(path: String, offset: Long): InputStream = openRead(path).also { input ->
        var left = offset
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped <= 0) {
                if (input.read() < 0) break
                left--
            } else {
                left -= skipped
            }
        }
    }
    fun openWrite(path: String): OutputStream
    fun setLastModified(path: String, time: Long)
}

sealed class StorageException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotFound(val path: String) : StorageException("Not found: $path")
    class AlreadyExists(val path: String) : StorageException("Already exists: $path")
    class AccessDenied(val path: String, cause: Throwable? = null) : StorageException("Access denied: $path", cause)
    class NoSpace(val path: String, val required: Long, val available: Long) :
        StorageException("Not enough space in $path: need $required, have $available")
    class InvalidName(val name: String) : StorageException("Invalid name: $name")
    class Io(message: String, cause: Throwable? = null) : StorageException(message, cause)

    /** Ошибка сетевого хранилища; [problem] подсказывает интерфейсу, что делать дальше. */
    class Remote(val connectionId: Long, val problem: RemoteProblem, cause: Throwable? = null) :
        StorageException("Remote $connectionId: $problem", cause)

    /** Действие не поддерживается этим хранилищем (например, корзина на сервере). */
    class NotSupported(message: String) : StorageException(message)
}
