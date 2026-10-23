package io.quietfiles.core.network

import io.quietfiles.core.model.StorageSpace
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/** Файл на сервере. [path] — абсолютный путь на сервере (`/home/pi/a.txt`). */
data class RemoteEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
)

/**
 * Открытая сессия с одним сервером. Методы блокирующие и вызываются на потоках ввода-вывода;
 * пути — абсолютные пути на сервере, уже нормализованные ([io.quietfiles.core.model.network.NetworkPaths.normalize]).
 *
 * Ошибки: [java.io.FileNotFoundException] — нет файла, [java.nio.file.FileAlreadyExistsException] — уже есть,
 * [SecurityException] — нет прав, остальные [java.io.IOException] — сбой связи (сессию надо переоткрыть).
 */
interface RemoteFileSystem : Closeable {
    fun list(path: String): List<RemoteEntry>
    fun stat(path: String): RemoteEntry?
    fun mkdir(path: String)
    fun createFile(path: String)
    fun rename(from: String, to: String)

    /** Удаление файла или пустой папки; содержимое папок удаляет [NetworkStorageProvider]. */
    fun delete(path: String, isDirectory: Boolean)
    fun openRead(path: String, offset: Long): InputStream
    fun openWrite(path: String): OutputStream

    /** Не все серверы позволяют менять дату; ошибки игнорируются. */
    fun setLastModified(path: String, time: Long) = Unit
    fun space(path: String): StorageSpace? = null

    /** Можно ли переименованием перенести файл между этими путями (для SMB — в пределах общего ресурса). */
    fun canRename(from: String, to: String): Boolean = true

    /** С какой папки начинается обзор: домашняя папка SFTP, указанный общий ресурс SMB и т. п. */
    val startPath: String get() = "/"

    /** Жива ли сессия: проверяется перед повторным использованием. */
    val isAlive: Boolean
}
