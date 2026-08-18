package com.example.idupi.domain.model

data class SubagentLiveState(
    val id: String,
    val name: String,
    val task: String? = null,
    val output: String = "",
    val isRunning: Boolean = true,
    val startTime: Long = System.currentTimeMillis()
)
