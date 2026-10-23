package io.quietfiles.core.network.tls

import io.quietfiles.core.network.Crypto
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date

/** Самоподписанный сертификат для FTPS-сервера «Доступ с ПК» (F17.1) и тестов. */
object SelfSigned {
    const val ALIAS = "server"

    /** Хранилище PKCS12 с ключом EC P-256 и сертификатом на [years] лет для имён [names]. */
    fun create(commonName: String, names: List<String> = emptyList(), password: CharArray, years: Int = 10): KeyStore {
        Crypto.install()
        val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), SecureRandom()) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val subject = X500Name("CN=$commonName")
        val builder = JcaX509v3CertificateBuilder(
            subject, BigInteger(64, SecureRandom()), Date(now - 86_400_000L), Date(now + years * 365L * 86_400_000L), subject, keyPair.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        if (names.isNotEmpty()) {
            val general = names.map { name ->
                if (name.all { it.isDigit() || it == '.' } || name.contains(':')) GeneralName(GeneralName.iPAddress, name) else GeneralName(GeneralName.dNSName, name)
            }
            builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(general.toTypedArray()))
        }
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
        val certificate: X509Certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        return KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(ALIAS, keyPair.private, password, arrayOf(certificate))
        }
    }

    fun certificateOf(store: KeyStore): X509Certificate = store.getCertificate(ALIAS) as X509Certificate
}
