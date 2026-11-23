package com.idupi.app.data

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
}
