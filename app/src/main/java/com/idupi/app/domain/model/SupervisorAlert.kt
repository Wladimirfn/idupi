package com.idupi.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class AlertSeverity {
    CRITICAL, WARNING, INFO
}

@Serializable
enum class AlertCategory {
    MASTER_PLAN, BUG_FINDING, GOVERNANCE_GATE, SECURITY_RISK, SEMANTIC_DEBT
}

@Serializable
data class SupervisorAlert(
    val id: String,
    val title: String,
    val description: String,
    val severity: AlertSeverity,
    val category: AlertCategory,
    val timestamp: String,
    val isRead: Boolean = false,
    val actionRequired: String? = null
)
