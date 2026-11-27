package com.idupi.app.data.phone.shell

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.RSAPublicKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Creates the adb client identity: an RSA key pair plus a self-signed X.509
 * certificate that wraps its public key.
 *
 * adbd identifies a client by the public key in the certificate it presents
 * during the AUTH exchange, so the certificate only needs to be well-formed
 * and match the private key. There is no chain to trust: the pairing handshake
 * (or the owner's authorization) is what blesses the key on the device.
 *
 * Pure JVM code (no Android types) so the key/certificate path is unit-tested.
 */
internal object AdbIdentity {

    fun generate(): AdbCredentials {
        val generator = KeyPairGenerator.getInstance(KEY_ALGORITHM)
        generator.initialize(KEY_SIZE_BITS, SecureRandom())
        return fromKeyPair(generator.generateKeyPair())
    }

    fun fromKeyPair(keyPair: KeyPair): AdbCredentials =
        AdbCredentials(keyPair.private, buildCertificate(keyPair))

    /**
     * True when the certificate carries the public half of [privateKey]. Used
     * on reload to reject a pair of files that were written separately.
     */
    fun certificateMatchesKey(certificate: X509Certificate, privateKey: PrivateKey): Boolean {
        val crtKey = privateKey as? RSAPrivateCrtKey ?: return true
        val derived: PublicKey = try {
            KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(
                RSAPublicKeySpec(crtKey.modulus, crtKey.publicExponent)
            )
        } catch (e: Exception) {
            return false
        }
        return derived.encoded.contentEquals(certificate.publicKey.encoded)
    }

    private fun buildCertificate(keyPair: KeyPair): X509Certificate {
        val subject = X500Name(CERTIFICATE_SUBJECT)
        val now = System.currentTimeMillis()
        val notBefore = Date(now - TimeUnit.DAYS.toMillis(1))
        val notAfter = Date(now + TimeUnit.DAYS.toMillis(VALIDITY_DAYS))
        val serial = BigInteger(64, SecureRandom()).abs().max(BigInteger.ONE)
        val signer = JcaContentSignerBuilder(CERTIFICATE_SIGNATURE)
            .setProvider(BC_PROVIDER)
            .build(keyPair.private)
        val holder = JcaX509v3CertificateBuilder(
            subject,
            serial,
            notBefore,
            notAfter,
            subject,
            keyPair.public,
        ).build(signer)
        // Parse the DER with the platform factory so generation and reload
        // return the same X509Certificate implementation.
        return holder.encoded.inputStream().use { stream ->
            CertificateFactory.getInstance(CERTIFICATE_TYPE)
                .generateCertificate(stream) as X509Certificate
        }
    }

    private const val KEY_ALGORITHM = "RSA"
    private const val KEY_SIZE_BITS = 2048
    private const val CERTIFICATE_TYPE = "X.509"
    private const val CERTIFICATE_SUBJECT = "CN=IDUPI"
    private const val CERTIFICATE_SIGNATURE = "SHA256withRSA"
    private const val VALIDITY_DAYS = 3650L
    private val BC_PROVIDER = BouncyCastleProvider()
}
