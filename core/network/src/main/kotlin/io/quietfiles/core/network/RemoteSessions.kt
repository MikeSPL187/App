package io.quietfiles.core.network

import io.quietfiles.core.common.IoDispatcher
import io.quietfiles.core.model.network.Connection
import io.quietfiles.core.model.network.ConnectionSecrets
import io.quietfiles.core.model.network.ConnectionStatus
import io.quietfiles.core.model.network.Protocol
import io.quietfiles.core.model.network.RemoteProblem
import io.quietfiles.core.network.ftp.FtpConnector
import io.quietfiles.core.network.sftp.SftpConnector
import io.quietfiles.core.network.smb.SmbConnector
import io.quietfiles.core.network.webdav.WebDavConnector
import io.quietfiles.core.storage.StorageException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** Открывает сессию с сервером своего протокола. Бросает [StorageException.Remote] с понятной причиной. */
fun interface RemoteConnector {
    fun connect(connection: Connection, secrets: ConnectionSecrets?): RemoteFileSystem
}

/** Коннекторы по протоколам; в тестах подставляются свои. */
class Connectors @Inject constructor(
    private val sftp: SftpConnector,
    private val ftp: FtpConnector,
    private val webDav: WebDavConnector,
    private val smb: SmbConnector,
) {
    fun of(protocol: Protocol): RemoteConnector = when (protocol) {
        Protocol.SFTP -> sftp
        Protocol.FTP -> ftp
        Protocol.WEBDAV -> webDav
        Protocol.SMB -> smb
    }
}

/**
 * Живые сессии с серверами. Сессия открывается при первом обращении, переоткрывается один раз, если связь
 * оборвалась, и закрывается после [IDLE_MS] без дела (если через неё ничего не читается и не пишется).
 */
@Singleton
class RemoteSessions internal constructor(
    private val repository: ConnectionLookup,
    private val connectors: (Protocol) -> RemoteConnector,
) {
    @Inject constructor(repository: ConnectionsRepository, connectors: Connectors, @IoDispatcher io: CoroutineDispatcher) :
        this(RepositoryLookup(repository, io), connectors::of)

    private class Session(val fs: RemoteFileSystem) {
        @Volatile var lastUsed = System.currentTimeMillis()
        val streams = AtomicInteger()
    }

    private val sessions = ConcurrentHashMap<Long, Session>()
    private val locks = ConcurrentHashMap<Long, Any>()
    private val _statuses = MutableStateFlow<Map<Long, ConnectionStatus>>(emptyMap())

    /** Состояние каждого подключения для списка (F16.6). */
    val statuses: StateFlow<Map<Long, ConnectionStatus>> = _statuses.asStateFlow()

    private val janitor by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "qf-net-idle").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay(::closeIdle, IDLE_MS, IDLE_MS / 2, TimeUnit.MILLISECONDS)
        }
    }

    /** Выполняет [block] в сессии подключения [id]; при обрыве связи переподключается один раз. */
    fun <T> use(id: Long, block: (RemoteFileSystem) -> T): T {
        val reused = sessions[id]
        val session = reused?.takeIf { it.fs.isAlive } ?: open(id)
        return try {
            block(session.fs).also { session.lastUsed = System.currentTimeMillis() }
        } catch (e: IOException) {
            if (!isConnectionFailure(e) || reused == null || reused !== session) throw e
            drop(id, session)
            block(open(id).fs)
        }
    }

    fun openRead(id: Long, path: String, offset: Long): InputStream {
        val (session, input) = withSession(id) { it.openRead(path, offset) }
        return object : FilterInputStream(input) {
            private var closed = false
            override fun read(): Int = super.read().also { session.lastUsed = System.currentTimeMillis() }
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { session.lastUsed = System.currentTimeMillis() }
            override fun close() {
                if (closed) return
                closed = true
                session.streams.decrementAndGet()
                super.close()
            }
        }
    }

    fun openWrite(id: Long, path: String): OutputStream {
        val (session, output) = withSession(id) { it.openWrite(path) }
        return object : FilterOutputStream(output) {
            private var closed = false
            override fun write(b: Int) {
                out.write(b)
                session.lastUsed = System.currentTimeMillis()
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                out.write(b, off, len)
                session.lastUsed = System.currentTimeMillis()
            }
            override fun close() {
                if (closed) return
                closed = true
                try {
                    out.close()
                } finally {
                    session.streams.decrementAndGet()
                }
            }
        }
    }

    private fun <T> withSession(id: Long, open: (RemoteFileSystem) -> T): Pair<Session, T> {
        var session: Session? = null
        val result = use(id) { fs ->
            session = sessions[id]?.takeIf { it.fs === fs }
            session?.streams?.incrementAndGet()
            try {
                open(fs)
            } catch (e: Throwable) {
                session?.streams?.decrementAndGet()
                throw e
            }
        }
        return (session ?: error("Session closed")) to result
    }

    /** Папка, с которой начинается обзор сервера: подключается, если сессии ещё нет. */
    fun startPath(id: Long): String = use(id) { it.startPath }

    /**
     * Проверка перед сохранением (кнопка «Проверить и подключить», макет 09): подключается с данными формы,
     * читает начальную папку и закрывает сессию. Возвращает null, если всё хорошо.
     */
    fun test(connection: Connection, secrets: ConnectionSecrets?): RemoteProblem? = try {
        connectors(connection.protocol).connect(connection, secrets).use { fs ->
            fs.list("/")
            null
        }
    } catch (e: StorageException.Remote) {
        e.problem
    } catch (e: SecurityException) {
        RemoteProblem.AuthFailed
    } catch (e: IOException) {
        if (isConnectionFailure(e)) RemoteProblem.Unreachable else RemoteProblem.Protocol(e.message ?: e.javaClass.simpleName)
    }

    /** Закрывает сессию: после правки подключения или по кнопке «Отключить». */
    fun disconnect(id: Long) {
        sessions.remove(id)?.let { runCatching { it.fs.close() } }
        setStatus(id, ConnectionStatus.UNKNOWN)
    }

    fun disconnectAll() = sessions.keys.toList().forEach(::disconnect)

    private fun open(id: Long): Session = synchronized(locks.getOrPut(id) { Any() }) {
        sessions[id]?.takeIf { it.fs.isAlive }?.let { return it }
        sessions.remove(id)?.let { runCatching { it.fs.close() } }
        val connection = repository.connection(id) ?: throw StorageException.NotFound(io.quietfiles.core.model.network.NetworkPaths.rootOf(id))
        val secrets = repository.secrets(id)
        if (secrets == null && needsSecrets(connection)) {
            setStatus(id, ConnectionStatus.NEEDS_LOGIN)
            throw StorageException.Remote(id, RemoteProblem.NeedsLogin)
        }
        setStatus(id, ConnectionStatus.CONNECTING)
        val fs = try {
            connectors(connection.protocol).connect(connection, secrets)
        } catch (e: StorageException.Remote) {
            setStatus(id, statusOf(e.problem))
            throw StorageException.Remote(id, e.problem, e.cause)
        } catch (e: IOException) {
            setStatus(id, ConnectionStatus.UNREACHABLE)
            throw StorageException.Remote(id, RemoteProblem.Unreachable, e)
        }
        repository.markConnected(id)
        setStatus(id, ConnectionStatus.ONLINE)
        janitor
        Session(fs).also { sessions[id] = it }
    }

    private fun drop(id: Long, session: Session) {
        sessions.remove(id, session)
        runCatching { session.fs.close() }
    }

    private fun closeIdle() {
        val now = System.currentTimeMillis()
        sessions.forEach { (id, session) ->
            if (session.streams.get() == 0 && now - session.lastUsed > IDLE_MS) {
                drop(id, session)
                setStatus(id, ConnectionStatus.UNKNOWN)
            }
        }
    }

    private fun setStatus(id: Long, status: ConnectionStatus) = _statuses.update { it + (id to status) }

    companion object {
        const val IDLE_MS = 5 * 60_000L

        fun needsSecrets(connection: Connection) = connection.auth != io.quietfiles.core.model.network.AuthMethod.ANONYMOUS

        fun statusOf(problem: RemoteProblem): ConnectionStatus = when (problem) {
            RemoteProblem.AuthFailed -> ConnectionStatus.AUTH_FAILED
            RemoteProblem.NeedsLogin -> ConnectionStatus.NEEDS_LOGIN
            is RemoteProblem.HostKeyChanged -> ConnectionStatus.HOST_KEY_CHANGED
            is RemoteProblem.CertificateChanged -> ConnectionStatus.CERTIFICATE_CHANGED
            else -> ConnectionStatus.UNREACHABLE
        }

        /** Ошибки, после которых сессию надо переоткрыть, — в отличие от «нет файла» или «нет прав». */
        fun isConnectionFailure(e: IOException): Boolean =
            e !is FileNotFoundException && e !is FileAlreadyExistsException && e !is RemoteRefusal
    }
}

/** Сервер ответил отказом на конкретную команду; связь при этом жива. */
open class RemoteRefusal(message: String) : IOException(message)

/** Откуда сессии берут подключения и секреты; отдельный интерфейс — для тестов без Room. */
interface ConnectionLookup {
    fun connection(id: Long): Connection?
    fun secrets(id: Long): ConnectionSecrets?
    fun markConnected(id: Long) = Unit
}

private class RepositoryLookup(private val repository: ConnectionsRepository, private val io: CoroutineDispatcher) : ConnectionLookup {
    override fun connection(id: Long) = runBlocking(io) { repository.get(id) }
    override fun secrets(id: Long) = repository.secretsOf(id)
    override fun markConnected(id: Long) {
        runBlocking(io) { repository.markConnected(id) }
    }
}
