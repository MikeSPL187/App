package io.quietfiles.core.model.network

/** Сетевые протоколы F16.1. */
enum class Protocol(val scheme: String, val defaultPort: Int) {
    SMB("smb", 445),
    SFTP("sftp", 22),
    FTP("ftp", 21),
    WEBDAV("https", 443),
}

/** Способ входа на сервер. */
enum class AuthMethod { PASSWORD, KEY, ANONYMOUS }

/** Шифрование FTP (F16.1): явный FTPS — `AUTH TLS` на обычном порту, неявный — TLS сразу (порт 990). */
enum class FtpSecurity { NONE, EXPLICIT, IMPLICIT }

/**
 * Сохранённое подключение без секретов (раздел 9.3). Пароль, ключ и парольная фраза лежат отдельно,
 * в хранилище, зашифрованном ключом Keystore (S1), и не попадают в резервные копии (S2).
 *
 * [hostKey] — отпечаток ключа SFTP-сервера, принятый при первом подключении (TOFU, F16.4);
 * [pinnedCertificate] — SHA-256 открытого ключа (SPKI) сертификата, которому пользователь доверился вручную (S3).
 */
data class Connection(
    val id: Long = 0,
    val protocol: Protocol,
    val host: String,
    val port: Int = protocol.defaultPort,
    val username: String = "",
    val auth: AuthMethod = AuthMethod.PASSWORD,
    val name: String = "",
    /** Папка или общий ресурс, с которого начинается обзор: `/home/pi`, `Public`, `/remote.php/dav/files/ivan`. */
    val path: String = "",
    val ftpSecurity: FtpSecurity = FtpSecurity.EXPLICIT,
    /** WebDAV без TLS: только по явному выбору пользователя (S4). */
    val plainHttp: Boolean = false,
    /** Как называется ключ SSH в интерфейсе: «id_ed25519 · ED25519». Сам ключ — в зашифрованном хранилище. */
    val keyLabel: String = "",
    val hostKey: String? = null,
    val pinnedCertificate: String? = null,
    val createdAt: Long = 0,
    val lastConnectedAt: Long? = null,
) {
    val displayName: String get() = name.ifBlank { host }

    /** Адрес для списка (макет 08): `smb://192.168.1.20`, `sftp://pi@192.168.1.50`. */
    val address: String
        get() {
            val scheme = when (protocol) {
                Protocol.FTP -> if (ftpSecurity == FtpSecurity.NONE) "ftp" else "ftps"
                Protocol.WEBDAV -> if (plainHttp) "http" else "https"
                else -> protocol.scheme
            }
            val user = if (username.isNotBlank() && auth != AuthMethod.ANONYMOUS && protocol == Protocol.SFTP) "$username@" else ""
            val defaultPort = when {
                protocol == Protocol.FTP && ftpSecurity == FtpSecurity.IMPLICIT -> 990
                protocol == Protocol.WEBDAV && plainHttp -> 80
                else -> protocol.defaultPort
            }
            val portPart = if (port != defaultPort) ":$port" else ""
            val hostPart = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
            val pathPart = path.trim().let { if (it.isEmpty() || it == "/") "" else "/" + it.trim('/') }
            return "$scheme://$user$hostPart$portPart$pathPart"
        }
}

/** Секреты подключения; никогда не хранятся вместе с [Connection]. */
data class ConnectionSecrets(
    val password: String? = null,
    /** Закрытый ключ SSH в формате OpenSSH или PEM. */
    val privateKey: String? = null,
    val passphrase: String? = null,
) {
    val isEmpty: Boolean get() = password.isNullOrEmpty() && privateKey.isNullOrEmpty()
}

/** Состояние подключения в списке (F16.6). */
enum class ConnectionStatus { UNKNOWN, CONNECTING, ONLINE, UNREACHABLE, AUTH_FAILED, NEEDS_LOGIN, HOST_KEY_CHANGED, CERTIFICATE_CHANGED }
