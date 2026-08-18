package com.example.idupi.data

import com.example.idupi.data.remote.RealIduPiClient
import com.example.idupi.domain.model.ConnectionProfile
import com.example.idupi.domain.repository.IduPiClient
import com.example.idupi.domain.repository.IduPiClientSource

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
