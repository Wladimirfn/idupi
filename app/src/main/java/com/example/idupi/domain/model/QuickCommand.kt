package com.example.idupi.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class QuickCommand(
    val command: String,
    val description: String,
    val category: String
)
