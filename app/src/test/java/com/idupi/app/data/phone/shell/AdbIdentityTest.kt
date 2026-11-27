package com.idupi.app.data.phone.shell

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey

/**
 * adb client identity generation (phone-shell T2). Permission to be written
 * and kept private is only half of the contract: adbd rejects a certificate
 * whose public key does not match the key that signs the AUTH token.
 */
class AdbIdentityTest {

    @Test
    fun `generates a 2048-bit RSA identity with a matching self-signed certificate`() {
        val credentials = AdbIdentity.generate()
        val privateKey = credentials.privateKey as RSAPrivateKey
        val crtKey = credentials.privateKey as RSAPrivateCrtKey
        val publicKey = credentials.certificate.publicKey as RSAPublicKey

        assertEquals(2048, privateKey.modulus.bitLength())
        assertEquals(privateKey.modulus, publicKey.modulus)
        assertEquals(crtKey.publicExponent, publicKey.publicExponent)
        assertEquals("CN=IDUPI", credentials.certificate.subjectX500Principal.name)
        assertEquals("CN=IDUPI", credentials.certificate.issuerX500Principal.name)
        assertTrue(AdbIdentity.certificateMatchesKey(credentials.certificate, credentials.privateKey))
    }

    @Test
    fun `certificate validity covers the present`() {
        val certificate = AdbIdentity.generate().certificate
        val now = System.currentTimeMillis()

        assertTrue(certificate.notBefore.time <= now)
        assertTrue(certificate.notAfter.time > now)
    }

    @Test
    fun `certificate survives an encode and reload round trip`() {
        val credentials = AdbIdentity.generate()
        val reloaded = CertificateFactory.getInstance("X.509")
            .generateCertificate(credentials.certificate.encoded.inputStream()) as X509Certificate

        assertArrayEquals(credentials.certificate.encoded, reloaded.encoded)
        assertTrue(AdbIdentity.certificateMatchesKey(reloaded, credentials.privateKey))
    }

    @Test
    fun `each generation creates a different key`() {
        val first = AdbIdentity.generate()
        val second = AdbIdentity.generate()

        assertFalse(
            (first.privateKey as RSAPrivateKey).modulus ==
                (second.privateKey as RSAPrivateKey).modulus
        )
    }

    @Test
    fun `a certificate from another identity does not match`() {
        val first = AdbIdentity.generate()
        val second = AdbIdentity.generate()

        assertFalse(AdbIdentity.certificateMatchesKey(first.certificate, second.privateKey))
        assertTrue(AdbIdentity.certificateMatchesKey(first.certificate, first.privateKey))
    }
}
