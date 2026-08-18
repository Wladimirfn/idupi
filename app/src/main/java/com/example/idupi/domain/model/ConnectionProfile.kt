package com.example.idupi.domain.model

enum class ConnectionMode {
    TAILSCALE, LOCAL_LAN, TUNNEL_REMOTE
}

enum class TransportType {
    AUTO, WEBSOCKET, SSH
}

data class ConnectionProfile(
    val name: String,
    val mode: ConnectionMode,
    val host: String,
    val port: Int,
    val token: String,
    val useHttps: Boolean,
    val transportType: TransportType = TransportType.AUTO
)

