package io.quietfiles.core.network.sftp

import io.quietfiles.core.model.network.AuthMethod
import io.quietfiles.core.model.network.Connection
import io.quietfiles.core.model.network.ConnectionSecrets
import io.quietfiles.core.model.network.RemoteProblem
import io.quietfiles.core.network.Crypto
import io.quietfiles.core.network.RemoteConnector
import io.quietfiles.core.network.RemoteFileSystem
import io.quietfiles.core.security.Fingerprints
import io.quietfiles.core.storage.StorageException
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.PublicKey
import javax.inject.Inject

/**
 * SFTP через sshj (F16.1): вход по паролю или ключу ED25519/RSA/ECDSA с парольной фразой.
 * Ключ сервера проверяется по схеме TOFU (F16.4): незнакомый — показываем отпечаток и ждём согласия,
 * изменившийся — не подключаемся.
 */
class SftpConnector @Inject constructor() : RemoteConnector {

    override fun connect(connection: Connection, secrets: ConnectionSecrets?): RemoteFileSystem {
        Crypto.install()
        val verifier = TofuVerifier(connection.hostKey)
        val ssh = SSHClient(DefaultConfig())
        ssh.connectTimeout = CONNECT_TIMEOUT_MS
        ssh.timeout = READ_TIMEOUT_MS
        ssh.addHostKeyVerifier(verifier)
        try {
            ssh.connect(connection.host, connection.port)
            authenticate(ssh, connection, secrets)
            val sftp = ssh.newSFTPClient()
            return SftpFileSystem(ssh, sftp, connection.path)
        } catch (e: Exception) {
            runCatching { ssh.disconnect() }
            verifier.problem?.let { throw StorageException.Remote(connection.id, it, e) }
            throw mapFailure(connection.id, e)
        }
    }

    private fun authenticate(ssh: SSHClient, connection: Connection, secrets: ConnectionSecrets?) {
        val user = connection.username
        when (connection.auth) {
            AuthMethod.PASSWORD -> ssh.authPassword(user, secrets?.password.orEmpty())
            AuthMethod.KEY -> {
                val key = secrets?.privateKey ?: throw StorageException.Remote(connection.id, RemoteProblem.NeedsLogin)
                val passphrase = secrets.passphrase?.takeIf { it.isNotEmpty() }
                val provider = ssh.loadKeys(key, null, passphrase?.let { PasswordUtils.createOneOff(it.toCharArray()) })
                ssh.authPublickey(user, provider)
            }
            AuthMethod.ANONYMOUS -> ssh.authPassword(user.ifBlank { "anonymous" }, "")
        }
    }

    private fun mapFailure(id: Long, e: Exception): Exception = when (e) {
        is StorageException -> e
        is UserAuthException -> StorageException.Remote(id, RemoteProblem.AuthFailed, e)
        is UnknownHostException, is ConnectException, is NoRouteToHostException, is SocketTimeoutException ->
            StorageException.Remote(id, RemoteProblem.Unreachable, e)
        is TransportException -> StorageException.Remote(id, RemoteProblem.Protocol(e.message ?: "SSH"), e)
        // Неверная парольная фраза или повреждённый ключ.
        is IOException -> if (e.message?.contains("key", ignoreCase = true) == true || e.cause is javax.crypto.BadPaddingException) {
            StorageException.Remote(id, RemoteProblem.AuthFailed, e)
        } else {
            StorageException.Remote(id, RemoteProblem.Unreachable, e)
        }
        else -> StorageException.Remote(id, RemoteProblem.Protocol(e.message ?: e.javaClass.simpleName), e)
    }

    private class TofuVerifier(private val expected: String?) : HostKeyVerifier {
        @Volatile var problem: RemoteProblem? = null

        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            val fingerprint = fingerprintOf(key)
            val type = KeyType.fromKey(key).toString()
            return when {
                expected == null -> {
                    problem = RemoteProblem.UnknownHostKey(fingerprint, type)
                    false
                }
                expected == fingerprint -> true
                else -> {
                    problem = RemoteProblem.HostKeyChanged(expected, fingerprint, type)
                    false
                }
            }
        }

        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 30_000

        /** Отпечаток в формате OpenSSH: `SHA256:…` — как показывает `ssh-keygen -lf`. */
        fun fingerprintOf(key: PublicKey): String = Fingerprints.sshSha256(Buffer.PlainBuffer().putPublicKey(key).compactData)
    }
}
