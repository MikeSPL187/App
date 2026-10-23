package io.quietfiles.core.model.network

/** Почему не удалось поговорить с сервером; по виду проблемы интерфейс решает, что спросить у пользователя. */
sealed interface RemoteProblem {
    /** Сервер не отвечает или адрес не найден. */
    data object Unreachable : RemoteProblem

    /** Неверный логин, пароль или ключ. */
    data object AuthFailed : RemoteProblem

    /** Секреты недоступны (например, после восстановления из резервной копии, S2): нужно войти заново. */
    data object NeedsLogin : RemoteProblem

    /** Первое подключение к SFTP: пользователь должен сверить отпечаток (F16.4). */
    data class UnknownHostKey(val fingerprint: String, val keyType: String) : RemoteProblem

    /** Ключ сервера не совпал с запомненным — подключение заблокировано (F16.4). */
    data class HostKeyChanged(val expected: String, val actual: String, val keyType: String) : RemoteProblem

    /** Системная проверка сертификата не прошла; можно закрепить его по согласию (F16.5). */
    data class UntrustedCertificate(val certificate: CertificateInfo) : RemoteProblem

    /** Закреплённый сертификат сменился — подключение заблокировано (F16.5, S3). */
    data class CertificateChanged(val certificate: CertificateInfo, val expectedSpki: String) : RemoteProblem

    /** Сервер говорит только на SMB1, а он не включён (F16.1). */
    data object Smb1Required : RemoteProblem

    /** Остальные ошибки протокола; [message] — текст сервера, для подробностей. */
    data class Protocol(val message: String) : RemoteProblem
}

/** Сертификат сервера для экрана проверки (F16.5). */
data class CertificateInfo(
    val subject: String,
    val issuer: String,
    val validFrom: Long,
    val validUntil: Long,
    /** SHA-256 открытого ключа (SPKI) в base64 — то, что закрепляется. */
    val spkiSha256: String,
    /** SHA-256 всего сертификата в шестнадцатеричном виде с двоеточиями — для сверки глазами. */
    val sha256: String,
    val reason: String,
)
