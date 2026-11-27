package com.idupi.app.data.phone.shell

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

/** The RSA identity that makes IDUPI a known adb client on this phone. */
data class AdbCredentials(
    val privateKey: PrivateKey,
    val certificate: X509Certificate,
)

/**
 * Generates and persists the adb client identity (phone-shell T2).
 *
 * adbd authorizes a client by the public key it presents; the client proves
 * ownership by signing the adb AUTH token with the matching RSA key. The key
 * pair therefore must outlive a process restart or the owner would have to
 * pair again.
 *
 * Both files live in [Context.getNoBackupFilesDir], app-private and excluded
 * from cloud backup, and are never exported through a provider or shared
 * storage. Nothing here is ever logged: the key and certificate never reach a
 * log line.
 *
 * The directory is read and written on a worker thread; generation happens at
 * most once because [loadOrCreate] is synchronized.
 */
class AdbKeyStore(context: Context) {

    private val directory = File(context.noBackupFilesDir, DIRECTORY_NAME)
    private val keyFile = File(directory, KEY_FILE_NAME)
    private val certificateFile = File(directory, CERTIFICATE_FILE_NAME)
    private val lock = Any()

    /**
     * Loads the stored identity or creates it on first use.
     *
     * A missing file means first run (or an interrupted first write), so a
     * fresh pair is generated and both files are rewritten together. A file
     * that exists but cannot be read or does not match its counterpart is
     * reported instead of silently replaced: a new key would break the pairing
     * that the owner already approved, and silently dropping it would hide the
     * real failure from the UI.
     */
    fun loadOrCreate(): AdbCredentials = synchronized(lock) {
        if (keyFile.isFile && certificateFile.isFile) {
            return read()
        }
        // Remove a half-written first run. Regenerating both keeps the pair
        // consistent; a mismatched key/certificate would fail at adbd instead.
        keyFile.delete()
        certificateFile.delete()
        return AdbIdentity.generate().also { write(it) }
    }

    private fun read(): AdbCredentials {
        val privateKey = try {
            KeyFactory.getInstance(KEY_ALGORITHM)
                .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
        } catch (e: Exception) {
            throw IOException("The stored adb key is unreadable; pair the phone again.", e)
        }
        val certificate = try {
            val parsed = certificateFile.inputStream().use { stream ->
                CertificateFactory.getInstance(CERTIFICATE_TYPE).generateCertificate(stream)
            }
            parsed as? X509Certificate
                ?: throw IOException("The stored adb certificate is not X.509.")
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("The stored adb certificate is unreadable; pair the phone again.", e)
        }
        if (!AdbIdentity.certificateMatchesKey(certificate, privateKey)) {
            throw IOException("The stored adb key and certificate do not match; pair the phone again.")
        }
        return AdbCredentials(privateKey, certificate)
    }

    /**
     * Writes both files via temporary names, certificate first. If the process
     * dies between the renames, the next start sees a partially applied pair
     * and regenerates both instead of trusting one half.
     */
    private fun write(credentials: AdbCredentials) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Could not create the adb credential directory.")
        }
        val keyTemp = File(directory, "$KEY_FILE_NAME.tmp")
        val certificateTemp = File(directory, "$CERTIFICATE_FILE_NAME.tmp")
        try {
            keyTemp.writeBytes(credentials.privateKey.encoded)
            certificateTemp.writeBytes(credentials.certificate.encoded)
            if (!certificateTemp.renameTo(certificateFile)) {
                throw IOException("Could not store the adb certificate.")
            }
            if (!keyTemp.renameTo(keyFile)) {
                certificateFile.delete()
                throw IOException("Could not store the adb key.")
            }
        } finally {
            keyTemp.delete()
            certificateTemp.delete()
        }
    }

    private companion object {
        const val DIRECTORY_NAME = "adb"
        const val KEY_FILE_NAME = "idupi_adb_key.pk8"
        const val CERTIFICATE_FILE_NAME = "idupi_adb_cert.der"
        const val KEY_ALGORITHM = "RSA"
        const val CERTIFICATE_TYPE = "X.509"
    }
}
