package com.idupi.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class SessionItem(
    val id: String,
    val title: String,
    val project: String,
    val date: String,
    val messageCount: Int?,
    val preview: String,
    val isFavorite: Boolean = false,
    val engine: String? = null
)
