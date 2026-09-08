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

    /**
     * PR 3 / Task 4.1 wire-up: pushes the SettingsRepository's current
     * OpenCode auto-approve toggle to the underlying [RealIduPiClient] so
     * the next `/api/v1/chat/message` POST carries the matching
     * `X-OpenCode-Auto-Approve` header.
     *
     * Mirrors the existing `configureRealClient` shape (sync push from a
     * higher-level orchestrator into the network singleton). The caller is
     * `MainViewModel.setOpencodeAutoApprove(value)` — it must call this
     * AFTER persisting into the SettingsRepository so the order is
     * `repo.set -> provider.push` and the in-memory chat header never leads
     * the on-disk source of truth.
     */
    fun setOpencodeAutoApprove(value: Boolean) {
        realInstance.opencodeAutoApprove = value
    }
}
