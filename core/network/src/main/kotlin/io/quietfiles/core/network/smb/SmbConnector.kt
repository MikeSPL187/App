package io.quietfiles.core.network.smb

import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import io.quietfiles.core.model.network.AuthMethod
import io.quietfiles.core.model.network.Connection
import io.quietfiles.core.model.network.ConnectionSecrets
import io.quietfiles.core.model.network.RemoteProblem
import io.quietfiles.core.network.Crypto
import io.quietfiles.core.network.RemoteConnector
import io.quietfiles.core.network.RemoteFileSystem
import io.quietfiles.core.storage.StorageException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * SMB 2 и 3 через smbj (F16.1); подписывание и шифрование — как требует сервер.
 * SMB1 библиотека не поддерживает: сервер только с SMB1 получит понятную ошибку [RemoteProblem.Smb1Required].
 * Логин вида `ДОМЕН\пользователь` разбирается на домен и имя.
 */
class SmbConnector @Inject constructor() : RemoteConnector {

    override fun connect(connection: Connection, secrets: ConnectionSecrets?): RemoteFileSystem {
        Crypto.install()
        val config = SmbConfig.builder()
            .withDialects(SMB2Dialect.SMB_3_1_1, SMB2Dialect.SMB_3_0_2, SMB2Dialect.SMB_3_0, SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_2_0_2)
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(30, TimeUnit.SECONDS)
            .withDfsEnabled(false)
            .build()
        val client = SMBClient(config)
        try {
            val smb = client.connect(connection.host, connection.port)
            val auth = when (connection.auth) {
                AuthMethod.ANONYMOUS -> AuthenticationContext.guest()
                else -> {
                    val (domain, user) = splitUser(connection.username)
                    AuthenticationContext(user, secrets?.password.orEmpty().toCharArray(), domain)
                }
            }
            val session = try {
                smb.authenticate(auth)
            } catch (e: SMBApiException) {
                smb.close()
                throw StorageException.Remote(connection.id, RemoteProblem.AuthFailed, e)
            }
            return SmbFileSystem(client, smb, session, connection.host, connection.path)
        } catch (e: Exception) {
            runCatching { client.close() }
            throw when (e) {
                is StorageException -> e
                is UnknownHostException, is ConnectException, is NoRouteToHostException, is SocketTimeoutException ->
                    StorageException.Remote(connection.id, RemoteProblem.Unreachable, e)
                is TransportException -> if (e.message?.contains("SMBv1", ignoreCase = true) == true || e.message?.contains("SMB1") == true) {
                    StorageException.Remote(connection.id, RemoteProblem.Smb1Required, e)
                } else {
                    StorageException.Remote(connection.id, RemoteProblem.Unreachable, e)
                }
                is SMBApiException -> StorageException.Remote(connection.id, RemoteProblem.Protocol(e.status.name), e)
                else -> StorageException.Remote(connection.id, RemoteProblem.Protocol(e.message ?: e.javaClass.simpleName), e)
            }
        }
    }

    companion object {
        /** `WORKGROUP\ivan` или `ivan@corp` → домен и имя. */
        fun splitUser(login: String): Pair<String?, String> {
            val trimmed = login.trim()
            val backslash = trimmed.indexOf('\\')
            return when {
                backslash > 0 -> trimmed.substring(0, backslash) to trimmed.substring(backslash + 1)
                else -> null to trimmed
            }
        }
    }
}
