package com.example.idupi.domain.model

data class UiRequest(
    val id: String,
    val method: UiRequestMethod,
    val title: String,
    val message: String,
    val options: List<String> = emptyList()
)

enum class UiRequestMethod {
    CONFIRM, SELECT
}
