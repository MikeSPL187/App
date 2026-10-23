package io.quietfiles.core.network.webdav

import io.quietfiles.core.model.StorageSpace
import io.quietfiles.core.model.network.NetworkPaths
import io.quietfiles.core.network.RemoteEntry
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Разбор ответов WebDAV (RFC 4918) и кодирование путей; без сети, поэтому проверяется юнит-тестами. */
internal object WebDav {

    const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:quota-available-bytes/><d:quota-used-bytes/></d:prop></d:propfind>"""

    data class Item(val href: String, val isCollection: Boolean, val size: Long, val lastModified: Long, val available: Long?, val used: Long?)

    fun parseMultistatus(xml: String): List<Item> {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(StringReader(xml))
        val items = ArrayList<Item>()
        var href: String? = null
        var collection = false
        var size = 0L
        var modified = 0L
        var available: Long? = null
        var used: Long? = null
        var okStatus = true
        var text = StringBuilder()
        // Свойства из propstat с ошибкой (404) не учитываем.
        var pending = mutableMapOf<String, String>()
        var pendingCollection = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    text = StringBuilder()
                    when (parser.name) {
                        "response" -> {
                            href = null; collection = false; size = 0; modified = 0; available = null; used = null
                        }
                        "propstat" -> {
                            pending = mutableMapOf(); pendingCollection = false; okStatus = true
                        }
                        "collection" -> pendingCollection = true
                    }
                }
                XmlPullParser.TEXT -> text.append(parser.text)
                XmlPullParser.END_TAG -> {
                    val value = text.toString().trim()
                    when (parser.name) {
                        "href" -> if (href == null) href = value
                        "getcontentlength", "getlastmodified", "quota-available-bytes", "quota-used-bytes" -> pending[parser.name] = value
                        "status" -> okStatus = value.split(' ').getOrNull(1)?.startsWith("2") ?: true
                        "propstat" -> if (okStatus) {
                            collection = collection || pendingCollection
                            pending["getcontentlength"]?.toLongOrNull()?.let { size = it }
                            pending["getlastmodified"]?.let { modified = parseDate(it) }
                            pending["quota-available-bytes"]?.toLongOrNull()?.let { available = it }
                            pending["quota-used-bytes"]?.toLongOrNull()?.let { used = it }
                        }
                        "response" -> href?.let { items += Item(it, collection, size, modified, available, used) }
                    }
                    text = StringBuilder()
                }
            }
            parser.next()
        }
        return items
    }

    /** Элементы папки [dir]: без неё самой, с путями относительно [base]. */
    fun children(items: List<Item>, base: String, dir: String): List<RemoteEntry> = items.mapNotNull { item ->
        val path = remotePath(item.href, base) ?: return@mapNotNull null
        if (path == dir) return@mapNotNull null
        toEntry(item, path)
    }

    fun toEntry(item: Item, path: String) = RemoteEntry(
        path = path,
        name = path.substringAfterLast('/').ifEmpty { "/" },
        isDirectory = item.isCollection,
        size = if (item.isCollection) 0 else item.size,
        lastModified = item.lastModified,
    )

    fun space(item: Item): StorageSpace? {
        val available = item.available?.takeIf { it >= 0 } ?: return null
        val used = item.used?.takeIf { it >= 0 } ?: return null
        return StorageSpace(total = available + used, free = available)
    }

    /** `href` из ответа → путь на сервере относительно корня подключения, или null, если он вне корня. */
    fun remotePath(href: String, base: String): String? {
        var path = href
        val scheme = path.indexOf("://")
        if (scheme > 0) path = path.substring(path.indexOf('/', scheme + 3).takeIf { it > 0 } ?: return "/")
        path = decode(path.substringBefore('?'))
        val normalized = runCatching { NetworkPaths.normalize(path) }.getOrNull() ?: return null
        val root = NetworkPaths.normalize(base)
        return when {
            root == "/" -> normalized
            normalized == root -> "/"
            normalized.startsWith("$root/") -> normalized.removePrefix(root)
            else -> null
        }
    }

    /** Путь запроса: корень подключения + закодированные части пути; папки — с «/» на конце. */
    fun target(base: String, remote: String, directory: Boolean = false): String {
        val root = NetworkPaths.normalize(base)
        val full = if (root == "/") remote else if (remote == "/") root else root + remote
        val encoded = full.split('/').joinToString("/") { encodeSegment(it) }
        return if (directory && !encoded.endsWith("/")) "$encoded/" else encoded.ifEmpty { "/" }
    }

    fun encodeSegment(segment: String): String {
        val sb = StringBuilder()
        for (b in segment.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch in "-._~!$&'()*+,;=:@") sb.append(ch) else sb.append('%').append("%02X".format(c))
        }
        return sb.toString()
    }

    fun decode(s: String): String {
        if (!s.contains('%')) return s
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) {
                    out.write(v)
                    i += 3
                    continue
                }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return out.toString(Charsets.UTF_8.name())
    }

    fun parseDate(value: String): Long {
        val formats = listOf("EEE, dd MMM yyyy HH:mm:ss zzz", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ssXXX")
        for (pattern in formats) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.parse(value)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return 0
    }
}
