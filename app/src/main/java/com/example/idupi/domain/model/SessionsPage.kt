package com.example.idupi.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SessionsPage(
    val sessions: List<SessionItem>,
    val nextCursor: String? = null,
    val partial: Boolean = false,
    val failures: List<SessionEngineFailure> = emptyList()
)

@Serializable
data class SessionEngineFailure(
    val engine: String,
    val message: String
)

@Serializable
data class SessionCountsResponse(
    val counts: SessionCounts,
    val partial: Boolean = false,
    val failures: List<SessionEngineFailure> = emptyList()
)

@Serializable
data class SessionCounts(
    @SerialName("pi-cli") val piCli: Int? = null,
    val opencode: Int? = null,
    val claude: Int? = null,
    val all: Int? = null
)