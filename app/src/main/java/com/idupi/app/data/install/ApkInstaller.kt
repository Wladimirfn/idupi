package com.idupi.app.data.install

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.app.PendingIntent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.idupi.app.domain.model.DeliveryInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * A pre-commit installation failure. [message] is user-facing and already in
 * the app's neutral Spanish; the deliveries screen shows it verbatim.
 */
class ApkInstallException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Installs a pushed APK through a PackageInstaller session.
 *
 * Implemented as an interface so [com.idupi.app.viewmodel.DeliveriesViewModel]
 * can be unit-tested on the JVM (no PackageInstaller, no Android runtime).
 */
interface ApkInstaller {

    /** `true` when this app may start a sideload install (`REQUEST_INSTALL_PACKAGES`). */
    fun canInstallPackages(): Boolean

    /**
     * Downloads [delivery] into a fresh install session, verifies its SHA-256,
     * and commits it. Returns once the session is committed; the system's
     * final verdict arrives later at [InstallResultReceiver].
     *
     * [onProgress] receives the number of bytes written so far.
     */
    suspend fun install(delivery: DeliveryInfo, onProgress: (Long) -> Unit)
}

/**
 * [ApkInstaller] over the PackageInstaller Session API.
 *
 * The download flows straight into `session.openWrite(...)`, so the APK is
 * never buffered in memory or written to app storage. Android always asks the
 * user to confirm a sideload install; `USER_ACTION_NOT_REQUIRED` is only a
 * hint for device-owner managed setups, not a silent-install path.
 */
class PackageInstallerApkInstaller(
    private val context: Context,
    private val download: (String) -> Flow<ByteArray>,
) : ApkInstaller {

    override fun canInstallPackages(): Boolean =
        context.packageManager.canRequestPackageInstalls()

    override suspend fun install(delivery: DeliveryInfo, onProgress: (Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            if (delivery.size > 0L) {
                params.setSize(delivery.size)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(
                    PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                )
            }

            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)
            var committed = false
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var written = 0L
                session.openWrite(WRITE_NAME, 0L, if (delivery.size > 0L) delivery.size else -1L)
                    .use { out ->
                        download(delivery.id).collect { chunk ->
                            out.write(chunk)
                            digest.update(chunk)
                            written += chunk.size
                            onProgress(written)
                        }
                        out.flush()
                        session.fsync(out)
                    }

                val actualSha256 = digest.digest().toHex()
                if (!actualSha256.equals(delivery.sha256, ignoreCase = true)) {
                    throw ApkInstallException("El APK descargado está dañado (SHA-256 no coincide).")
                }
                if (delivery.size > 0L && written != delivery.size) {
                    throw ApkInstallException("La descarga del APK quedó incompleta.")
                }

                session.commit(commitIntentSender(delivery))
                committed = true
            } catch (e: Exception) {
                // Abandoning is what releases the reserved space; a session that
                // already reached commit owns its own lifecycle.
                if (!committed) {
                    runCatching { session.abandon() }
                }
                if (e is ApkInstallException || e is CancellationException) throw e
                throw ApkInstallException(
                    "No se pudo descargar o instalar el APK: ${e.localizedMessage}",
                    e
                )
            } finally {
                runCatching { session.close() }
                Log.d(TAG, "install session $sessionId for ${delivery.id} finished (committed=$committed)")
            }
        }

    /**
     * The system fires this broadcast with the session's final status. On
     * API 31+ the intent is filled in by the platform, so the PendingIntent
     * MUST be mutable.
     */
    private fun commitIntentSender(delivery: DeliveryInfo): IntentSender {
        val intent = Intent(context, InstallResultReceiver::class.java).apply {
            action = InstallResultReceiver.ACTION_INSTALL_RESULT
            putExtra(InstallResultReceiver.EXTRA_DELIVERY_ID, delivery.id)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            delivery.id.hashCode(),
            intent,
            flags
        )
        return pendingIntent.intentSender
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        private const val TAG = "PackageInstallerApk"
        private const val WRITE_NAME = "base.apk"
    }
}
