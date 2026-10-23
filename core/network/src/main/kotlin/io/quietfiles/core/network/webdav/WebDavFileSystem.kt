package io.quietfiles.core.network.webdav

import io.quietfiles.core.model.StorageSpace
import io.quietfiles.core.network.RemoteEntry
import io.quietfiles.core.network.RemoteFileSystem
import io.quietfiles.core.network.RemoteRefusal
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException

internal class WebDavFileSystem(
    private val http: HttpClient,
    private val base: String,
    private val uploadDir: () -> File,
) : RemoteFileSystem {

    /** Проверка при подключении: корень отвечает на PROPFIND, вход принят. */
    fun probe() {
        propfind("/", depth = 0) ?: throw FileNotFoundException(base.ifBlank { "/" })
    }

    override fun list(path: String): List<RemoteEntry> {
        val items = propfind(path, depth = 1) ?: throw FileNotFoundException(path)
        return WebDav.children(items, base, path)
    }

    override fun stat(path: String): RemoteEntry? {
        val items = propfind(path, depth = 0) ?: return null
        val item = items.firstOrNull() ?: return null
        return WebDav.toEntry(item, path)
    }

    private fun propfind(path: String, depth: Int): List<WebDav.Item>? {
        // Для папок сервер может требовать «/» на конце; пробуем как есть, затем с «/» (или после редиректа).
        var response = http.execute(
            "PROPFIND", WebDav.target(base, path), mapOf("Depth" to depth.toString()),
            RequestBody.of(WebDav.PROPFIND_BODY.toByteArray(), "application/xml; charset=utf-8"),
        )
        if (response.code in REDIRECTS) {
            response.close()
            response = http.execute(
                "PROPFIND", WebDav.target(base, path, directory = true), mapOf("Depth" to depth.toString()),
                RequestBody.of(WebDav.PROPFIND_BODY.toByteArray(), "application/xml; charset=utf-8"),
            )
        }
        response.use {
            if (it.code == 404) return null
            check(it, path)
            return WebDav.parseMultistatus(it.text())
        }
    }

    override fun mkdir(path: String) {
        http.execute("MKCOL", WebDav.target(base, path, directory = true)).use {
            if (it.code == 405) throw FileAlreadyExistsException(path)
            if (it.code == 409) throw FileNotFoundException(path)
            check(it, path)
        }
    }

    override fun createFile(path: String) {
        http.execute("PUT", WebDav.target(base, path), mapOf("If-None-Match" to "*"), RequestBody.of(ByteArray(0))).use {
            if (it.code == 412) throw FileAlreadyExistsException(path)
            check(it, path)
        }
    }

    override fun rename(from: String, to: String) {
        val isDir = stat(from)?.isDirectory == true
        val destination = absolute(WebDav.target(base, to, directory = isDir))
        http.execute("MOVE", WebDav.target(base, from, directory = isDir), mapOf("Destination" to destination, "Overwrite" to "F")).use {
            if (it.code == 412) throw FileAlreadyExistsException(to)
            check(it, from)
        }
    }

    override fun delete(path: String, isDirectory: Boolean) {
        http.execute("DELETE", WebDav.target(base, path, directory = isDirectory)).use {
            if (it.code == 404) return
            check(it, path)
        }
    }

    override fun openRead(path: String, offset: Long): InputStream {
        val headers = if (offset > 0) mapOf("Range" to "bytes=$offset-") else emptyMap()
        val response = http.execute("GET", WebDav.target(base, path), headers, stream = true)
        try {
            check(response, path)
        } catch (e: IOException) {
            response.close()
            throw e
        }
        val body = response.body
        // Сервер без поддержки Range отдаёт файл целиком — пропускаем начало сами.
        if (offset > 0 && response.code == 200) {
            var left = offset
            while (left > 0) {
                val skipped = body.skip(left)
                if (skipped <= 0) {
                    if (body.read() < 0) break
                    left--
                } else {
                    left -= skipped
                }
            }
        }
        return body
    }

    /**
     * Запись через временный файл: длина нужна заранее, потому что не все серверы WebDAV принимают
     * PUT без Content-Length. Отправка — при закрытии потока; ошибка отправки бросается из close().
     */
    override fun openWrite(path: String): OutputStream {
        val dir = uploadDir().apply { mkdirs() }
        val temp = File.createTempFile("put-", ".part", dir)
        return object : FilterOutputStream(FileOutputStream(temp)) {
            private var closed = false
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() {
                if (closed) return
                closed = true
                try {
                    out.close()
                    val body = RequestBody(temp.length()) { output -> temp.inputStream().use { it.copyTo(output, 64 * 1024) } }
                    http.execute("PUT", WebDav.target(base, path), body = body, stream = true).use { check(it, path) }
                } finally {
                    temp.delete()
                }
            }
        }
    }

    override fun space(path: String): StorageSpace? = propfind(path, depth = 0)?.firstOrNull()?.let(WebDav::space)

    override val isAlive: Boolean get() = !http.closed

    override fun close() = http.close()

    private fun absolute(target: String): String = http.baseUrl() + target

    private fun check(response: HttpResponse, path: String) {
        val code = response.code
        if (code in 200..299) return
        throw when (code) {
            401 -> SecurityException("Unauthorized")
            403 -> SecurityException("Forbidden: $path")
            404, 410 -> FileNotFoundException(path)
            else -> RemoteRefusal("HTTP $code ${response.message}")
        }
    }

    private companion object {
        val REDIRECTS = setOf(301, 302, 307, 308)
    }
}
