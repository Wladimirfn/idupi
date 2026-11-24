package com.idupi.app.data

import android.content.Context
import com.idupi.app.data.connection.ConnectionStorage
import com.idupi.app.data.remote.RealIduPiClient
import com.idupi.app.domain.model.ConnectionProfile
import com.idupi.app.domain.repository.IduPiClient
import com.idupi.app.domain.repository.IduPiClientSource

object IduPiClientProvider : IduPiClientSource {
    private val realInstance = RealIduPiClient()

    override val client: IduPiClient
        get() = realInstance

    fun configureRealClient(profile: ConnectionProfile) {
        realInstance.configure(
            host = profile.host,
            port = profile.port,
            token = profile.token,
            useHttps = profile.useHttps
        )
    }

    /**
     * Applies the profile saved by "Conexión" to the shared client.
     *
     * MainActivity calls this so the delivery poller cannot race the
     * configuration, and the notification listener calls it too: the system
     * can start that service in a process where MainActivity never ran, and
     * an unconfigured client would post to the emulator default with an empty
     * token. One implementation keeps both paths on the same auth story.
     *
     * @return true when a host is stored and the client is usable.
     */
    fun configureFromStorage(context: Context): Boolean {
        val profile = ConnectionStorage(context.applicationContext).getProfile()
            ?: return false
        if (profile.host.isBlank()) return false
        configureRealClient(profile)
        return true
    }

    /**
     * Forgets the configured PC: the in-memory client falls back to the local
     * defaults and an empty token. "Borrar credenciales" must call this, or
     * every screen keeps talking to the old server with the old token until
     * the process restarts.
     */
    fun clear() {
        realInstance.configure(host = "", port = 0, token = "", useHttps = false)
    }
}
