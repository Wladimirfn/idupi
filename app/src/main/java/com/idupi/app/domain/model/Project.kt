package com.idupi.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Project(
    val id: String,
    val name: String,
    val path: String,
    val isActive: Boolean,
    val status: String = "Disponible",
    val lastActivity: String = "Hace 1 hora"
)
