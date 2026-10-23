package io.quietfiles.core.network.webdav

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.quietfiles.core.model.network.AuthMethod
import io.quietfiles.core.model.network.Connection
import io.quietfiles.core.model.network.ConnectionSecrets
import io.quietfiles.core.model.network.RemoteProblem
import io.quietfiles.core.network.RemoteConnector
import io.quietfiles.core.network.RemoteFileSystem
import io.quietfiles.core.security.ServerTrust
import io.quietfiles.core.storage.StorageException
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.net.ssl.SSLException

/** WebDAV и WebDAVS (F16.1): Nextcloud, ownCloud, NAS, Apache/nginx. */
class WebDavConnector internal constructor(private val uploadDir: () -> File) : RemoteConnector {

    @Inject constructor(@ApplicationContext context: Context) : this({ File(context.cacheDir, "webdav-upload") })

    override fun connect(connection: Connection, secrets: ConnectionSecrets?): RemoteFileSystem {
        val trust = if (connection.plainHttp) null else ServerTrust(connection.host, connection.pinnedCertificate)
        val anonymous = connection.auth == AuthMethod.ANONYMOUS
        val client = HttpClient(
            host = connection.host,
            port = connection.port,
            tls = !connection.plainHttp,
            trust = trust,
            username = if (anonymous) "" else connection.username,
            password = if (anonymous) "" else secrets?.password.orEmpty(),
        )
        val fs = WebDavFileSystem(client, connection.path, uploadDir)
        try {
            fs.probe()
            return fs
        } catch (e: Exception) {
            client.close()
            trust?.problem?.let { throw StorageException.Remote(connection.id, it, e) }
            throw when (e) {
                is StorageException -> e
                is SecurityException -> StorageException.Remote(connection.id, RemoteProblem.AuthFailed, e)
                is UnknownHostException, is ConnectException, is NoRouteToHostException, is SocketTimeoutException ->
                    StorageException.Remote(connection.id, RemoteProblem.Unreachable, e)
                is SSLException -> StorageException.Remote(connection.id, RemoteProblem.Protocol(e.message ?: "TLS"), e)
                is IOException -> StorageException.Remote(connection.id, RemoteProblem.Protocol(e.message ?: "WebDAV"), e)
                else -> StorageException.Remote(connection.id, RemoteProblem.Protocol(e.message ?: e.javaClass.simpleName), e)
            }
        }
    }
}
