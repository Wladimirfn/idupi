package com.idupi.app.data.phone.shell

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.IOException
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.TimeUnit

/**
 * Process-wide libadb connection manager (phone-shell T2).
 *
 * It points at the phone's own adbd over loopback: mounting the phone's own
 * Wireless debugging is what gives IDUPI a `shell` (uid 2000) channel without
 * a PC. The adb identity comes from [AdbKeyStore]; key generation is lazy so
 * the constructor never touches disk and every disk read happens on the
 * caller's worker thread.
 *
 * [close] is overridden on purpose: the base implementation destroys the
 * private key, and a process singleton must keep the persisted identity usable
 * for the next connection. Only the socket is closed here.
 */
class IdupiAdbConnectionManager private constructor(context: Context) : AbsAdbConnectionManager() {

    private val keyStore = AdbKeyStore(context)

    @Volatile
    private var credentials: AdbCredentials? = null

    init {
        // The client runs in the same device as adbd, so its SDK level is the
        // daemon's API level and improves both performance and security.
        setApi(Build.VERSION.SDK_INT)
        setHostAddress(LOOPBACK_ADDRESS)
        // Bounds the AUTH handshake. The library default (Long.MAX_VALUE)
        // would let a silent daemon hang the connect call forever.
        setTimeout(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    override fun getPrivateKey(): PrivateKey = credentials().privateKey

    override fun getCertificate(): Certificate = credentials().certificate

    override fun getDeviceName(): String = DEVICE_NAME

    override fun close() {
        try {
            disconnect()
        } catch (e: IOException) {
            // Closing a dead socket is not a failure worth surfacing; the
            // persisted key must survive either way.
        }
    }

    private fun credentials(): AdbCredentials {
        credentials?.let { return it }
        synchronized(this) {
            credentials?.let { return it }
            return keyStore.loadOrCreate().also { credentials = it }
        }
    }

    companion object {
        const val DEVICE_NAME = "IDUPI"
        const val LOOPBACK_ADDRESS = "127.0.0.1"

        private const val CONNECTION_TIMEOUT_SECONDS = 10L
        private val LOCK = Any()

        @Volatile
        private var instance: IdupiAdbConnectionManager? = null

        fun getInstance(context: Context): IdupiAdbConnectionManager {
            instance?.let { return it }
            synchronized(LOCK) {
                instance?.let { return it }
                return IdupiAdbConnectionManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
