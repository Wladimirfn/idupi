package com.idupi.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class ServerStatus(
    val connected: Boolean,
    val pcName: String,
    val project: String,
    val agent: String,
    val busy: Boolean,
    val queueSize: Int,
    val activeAgents: List<String> = emptyList(),
    val cliTask: String = "Idle",
    val operatingAi: String = "gpt-5.6-luna",
    val operatingProvider: String? = null,
    val activeEngine: String = "pi-cli"
)
