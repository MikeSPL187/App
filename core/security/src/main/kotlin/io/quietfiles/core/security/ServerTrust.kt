package io.quietfiles.core.security

import android.annotation.SuppressLint
import io.quietfiles.core.model.network.CertificateInfo
import io.quietfiles.core.model.network.RemoteProblem
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Проверка сертификата сервера (S3, F16.5): сначала системные корневые сертификаты и имя хоста;
 * исключение — только закреплённый пользователем открытый ключ этого сервера. Глобального «доверять всем» нет.
 *
 * Если проверка не прошла, причина остаётся в [problem], чтобы показать сертификат и спросить пользователя.
 * Объект живёт одно подключение.
 */
// Свой TrustManager нужен только ради закрепления по согласию пользователя (S3); всё остальное
// проверяет системный, которому мы передаём цепочку без изменений.
@SuppressLint("CustomX509TrustManager")
class ServerTrust(
    private val host: String,
    private val pinnedSpki: String?,
    private val system: X509TrustManager = systemTrustManager(),
    private val hostnames: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier(),
) : X509TrustManager {

    @Volatile var problem: RemoteProblem? = null
        private set

    @Volatile private var pinMatched = false

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        if (chain.isEmpty()) throw CertificateException("Empty chain")
        val leaf = chain[0]
        val spki = Certificates.spkiSha256(leaf)
        if (pinnedSpki != null) {
            if (spki == pinnedSpki) {
                pinMatched = true
                return
            }
            problem = RemoteProblem.CertificateChanged(Certificates.info(leaf, "pin mismatch"), pinnedSpki)
            throw CertificateException("Pinned certificate changed")
        }
        try {
            system.checkServerTrusted(chain, authType)
        } catch (e: CertificateException) {
            problem = RemoteProblem.UntrustedCertificate(Certificates.info(leaf, e.message ?: "untrusted"))
            throw e
        }
    }

    /**
     * Имя хоста проверяется после рукопожатия. Для закреплённого сертификата имя не важно:
     * пользователь доверился именно этому ключу именно для этого подключения.
     */
    fun verifyHostname(session: SSLSession): Boolean {
        if (pinMatched) return true
        if (hostnames.verify(host, session)) return true
        val leaf = session.peerCertificates.firstOrNull() as? X509Certificate
        if (leaf != null) problem = RemoteProblem.UntrustedCertificate(Certificates.info(leaf, "hostname mismatch"))
        return false
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        throw CertificateException("Client certificates are not accepted")

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

    fun socketFactory(): SSLSocketFactory = sslContext().socketFactory

    fun sslContext(): SSLContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(this@ServerTrust), null) }

    companion object {
        fun systemTrustManager(): X509TrustManager {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        }
    }
}

object Certificates {
    fun spkiSha256(certificate: X509Certificate): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded))

    fun info(certificate: X509Certificate, reason: String) = CertificateInfo(
        subject = certificate.subjectX500Principal.name,
        issuer = certificate.issuerX500Principal.name,
        validFrom = certificate.notBefore.time,
        validUntil = certificate.notAfter.time,
        spkiSha256 = spkiSha256(certificate),
        sha256 = Fingerprints.hexColon(MessageDigest.getInstance("SHA-256").digest(certificate.encoded)),
        reason = reason,
    )
}

object Fingerprints {
    /** Отпечаток ключа SSH в виде OpenSSH: `SHA256:Xk3q9V…` (base64 без «=»). */
    fun sshSha256(publicKeyBlob: ByteArray): String =
        "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(publicKeyBlob))

    fun hexColon(bytes: ByteArray): String = bytes.joinToString(":") { "%02X".format(it) }
}
