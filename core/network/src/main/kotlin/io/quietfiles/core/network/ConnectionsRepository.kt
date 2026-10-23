package io.quietfiles.core.network

import io.quietfiles.core.data.db.ConnectionDao
import io.quietfiles.core.data.db.ConnectionEntity
import io.quietfiles.core.model.network.AuthMethod
import io.quietfiles.core.model.network.Connection
import io.quietfiles.core.model.network.ConnectionSecrets
import io.quietfiles.core.model.network.FtpSecurity
import io.quietfiles.core.model.network.Protocol
import io.quietfiles.core.security.SecretStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Список серверов (Room, без секретов) и их секреты (зашифрованное хранилище). */
@Singleton
class ConnectionsRepository @Inject constructor(
    private val dao: ConnectionDao,
    private val secrets: SecretStore,
) {
    val connections: Flow<List<Connection>> = dao.observeAll().map { list -> list.map { it.toModel() } }

    suspend fun get(id: Long): Connection? = dao.get(id)?.toModel()

    /**
     * Сохраняет подключение и его секреты. Если секреты зашифровать не удалось, бросает
     * [io.quietfiles.core.security.SecretStoreException], а новое подключение не остаётся в списке (S1).
     */
    suspend fun save(connection: Connection, secrets: ConnectionSecrets?): Long {
        val isNew = connection.id == 0L
        val entity = connection.copy(createdAt = connection.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis()).toEntity()
        val id = if (isNew) dao.insert(entity) else entity.id.also { dao.upsert(entity) }
        if (secrets != null) {
            try {
                this.secrets.putConnection(id, secrets)
            } catch (e: Exception) {
                if (isNew) dao.delete(id)
                throw e
            }
        }
        return id
    }

    fun secretsOf(id: Long): ConnectionSecrets? = secrets.connection(id)

    suspend fun delete(id: Long) {
        dao.delete(id)
        secrets.removeConnection(id)
    }

    suspend fun trustHostKey(id: Long, fingerprint: String?) = dao.setHostKey(id, fingerprint)

    suspend fun pinCertificate(id: Long, spki: String?) = dao.setPinnedCertificate(id, spki)

    suspend fun markConnected(id: Long) = dao.setLastConnected(id, System.currentTimeMillis())
}

internal fun ConnectionEntity.toModel() = Connection(
    id = id,
    protocol = enumValueOrNull<Protocol>(protocol) ?: Protocol.SFTP,
    host = host,
    port = port,
    username = username,
    auth = enumValueOrNull<AuthMethod>(auth) ?: AuthMethod.PASSWORD,
    name = name,
    path = path,
    ftpSecurity = enumValueOrNull<FtpSecurity>(ftpSecurity) ?: FtpSecurity.EXPLICIT,
    plainHttp = plainHttp,
    keyLabel = keyLabel,
    hostKey = hostKey,
    pinnedCertificate = pinnedCertificate,
    createdAt = createdAt,
    lastConnectedAt = lastConnectedAt,
)

internal fun Connection.toEntity() = ConnectionEntity(
    id = id,
    protocol = protocol.name,
    host = host.trim(),
    port = port,
    username = username.trim(),
    auth = auth.name,
    name = name.trim(),
    path = path.trim(),
    ftpSecurity = ftpSecurity.name,
    plainHttp = plainHttp,
    keyLabel = keyLabel,
    hostKey = hostKey,
    pinnedCertificate = pinnedCertificate,
    createdAt = createdAt,
    lastConnectedAt = lastConnectedAt,
)

private inline fun <reified T : Enum<T>> enumValueOrNull(name: String): T? = enumValues<T>().firstOrNull { it.name == name }
