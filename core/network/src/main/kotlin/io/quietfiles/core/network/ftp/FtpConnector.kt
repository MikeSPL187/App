package io.quietfiles.core.network.ftp

import io.quietfiles.core.model.network.AuthMethod
import io.quietfiles.core.model.network.Connection
import io.quietfiles.core.model.network.ConnectionSecrets
import io.quietfiles.core.model.network.FtpSecurity
import io.quietfiles.core.model.network.RemoteProblem
import io.quietfiles.core.network.RemoteConnector
import io.quietfiles.core.network.RemoteFileSystem
import io.quietfiles.core.security.ServerTrust
import io.quietfiles.core.storage.StorageException
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Duration
import javax.inject.Inject
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLException

/**
 * FTP и FTPS (F16.1): явный (`AUTH TLS`) и неявный TLS. Сертификат проверяет [ServerTrust] (S3).
 * Управляющее соединение у FTP одно на сессию, поэтому каждое чтение и запись файла открывают своё.
 */
class FtpConnector @Inject constructor() : RemoteConnector {

    override fun connect(connection: Connection, secrets: ConnectionSecrets?): RemoteFileSystem {
        val first = login(connection, secrets)
        return FtpFileSystem(first, connection.path) { login(connection, secrets) }
    }

    /** Новое залогиненное управляющее соединение. */
    fun login(connection: Connection, secrets: ConnectionSecrets?): FTPClient {
        val trust = if (connection.ftpSecurity == FtpSecurity.NONE) null else ServerTrust(connection.host, connection.pinnedCertificate)
        val client = when (connection.ftpSecurity) {
            FtpSecurity.NONE -> FTPClient()
            FtpSecurity.EXPLICIT -> FTPSClient(false)
            FtpSecurity.IMPLICIT -> FTPSClient(true)
        }
        if (client is FTPSClient && trust != null) {
            client.setTrustManager(trust)
            client.hostnameVerifier = HostnameVerifier { _, session -> trust.verifyHostname(session) }
            client.isEndpointCheckingEnabled = false
        }
        client.controlEncoding = "UTF-8"
        client.connectTimeout = CONNECT_TIMEOUT_MS
        client.setDefaultTimeout(READ_TIMEOUT_MS)
        client.setDataTimeout(Duration.ofMillis(READ_TIMEOUT_MS.toLong()))
        try {
            client.connect(connection.host, connection.port)
            if (!FTPReply.isPositiveCompletion(client.replyCode)) throw IOException("FTP: ${client.replyString}")
            client.soTimeout = READ_TIMEOUT_MS
            val (user, password) = when (connection.auth) {
                AuthMethod.ANONYMOUS -> "anonymous" to "guest@"
                else -> connection.username to secrets?.password.orEmpty()
            }
            if (!client.login(user, password)) {
                throw StorageException.Remote(connection.id, RemoteProblem.AuthFailed)
            }
            if (client is FTPSClient) {
                client.execPBSZ(0)
                client.execPROT("P")
            }
            runCatching { client.sendCommand("OPTS UTF8 ON") }
            client.setFileType(FTP.BINARY_FILE_TYPE)
            client.enterLocalPassiveMode()
            client.setUseEPSVwithIPv4(false)
            return client
        } catch (e: Exception) {
            runCatching { client.disconnect() }
            trust?.problem?.let { throw StorageException.Remote(connection.id, it, e) }
            throw when (e) {
                is StorageException -> e
                is UnknownHostException, is ConnectException, is NoRouteToHostException, is SocketTimeoutException ->
                    StorageException.Remote(connection.id, RemoteProblem.Unreachable, e)
                is SSLException -> StorageException.Remote(connection.id, RemoteProblem.Protocol(e.message ?: "TLS"), e)
                else -> StorageException.Remote(connection.id, RemoteProblem.Protocol(e.message ?: "FTP"), e)
            }
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
