package com.example.idupi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.idupi.domain.model.AlertCategory
import com.example.idupi.domain.model.AlertSeverity
import com.example.idupi.domain.model.SupervisorAlert
import com.example.idupi.ui.theme.*
import com.example.idupi.viewmodel.AlertsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen(
    viewModel: AlertsViewModel,
    onMenuClick: () -> Unit
) {
    val context = LocalContext.current
    val alerts by viewModel.alerts.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val filter by viewModel.selectedFilter.collectAsState()

    var showRejectDialog by remember { mutableStateOf<SupervisorAlert?>(null) }
    var rejectReason by remember { mutableStateOf("") }

    val filteredAlerts = remember(alerts, filter) {
        if (filter == null) alerts else alerts.filter { it.severity == filter }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Alertas del Supervisor", style = AppTypography.appBarTitle, color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(Icons.Default.Menu, contentDescription = "Menú", tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.triggerTestNotification(context) }) {
                        Icon(Icons.Default.NotificationsActive, contentDescription = "Probar Notificación", tint = PrimaryIndigo)
                    }
                    IconButton(onClick = { viewModel.refreshAlerts() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Actualizar", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SlateCard)
            )
        },
        containerColor = SlateBg
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = AppSpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
        ) {
            Spacer(modifier = Modifier.height(AppSpacing.xs))

            // Severity Filters Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val filters = listOf(
                    null to "Todas",
                    AlertSeverity.CRITICAL to "Críticas",
                    AlertSeverity.WARNING to "Advertencias",
                    AlertSeverity.INFO to "Info"
                )

                filters.forEach { (severity, label) ->
                    val isSelected = filter == severity
                    val bg = if (isSelected) PrimaryIndigo else SlateCard
                    val textColor = if (isSelected) TextPrimary else TextSecondary

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(bg)
                            .border(1.dp, if (isSelected) PrimaryIndigo else SlateBorder, RoundedCornerShape(8.dp))
                            .clickable { viewModel.setFilter(severity) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, color = textColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = PrimaryIndigo)
                }
            } else if (filteredAlerts.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No hay alertas pendientes del supervisor Idu-pi", color = TextSecondary, style = AppTypography.bodySmall)
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
                    contentPadding = PaddingValues(bottom = AppSpacing.xl)
                ) {
                    items(filteredAlerts, key = { it.id }) { alert ->
                        AlertCard(
                            alert = alert,
                            onMarkRead = { viewModel.markAsRead(alert.id) },
                            onApprove = { viewModel.approvePlan(alert.id) },
                            onReject = { showRejectDialog = alert }
                        )
                    }
                }
            }
        }
    }

    // Reject Reason Dialog
    showRejectDialog?.let { alert ->
        AlertDialog(
            onDismissRequest = { showRejectDialog = null },
            title = { Text("Rechazar Plan Maestro", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ingresá el motivo de rechazo para que el supervisor Idu-pi reelabore la propuesta:", color = TextSecondary, fontSize = 12.sp)
                    OutlinedTextField(
                        value = rejectReason,
                        onValueChange = { rejectReason = it },
                        placeholder = { Text("ej. Falta considerar contrato de API", color = TextSecondary.copy(alpha = 0.5f)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = PrimaryIndigo,
                            unfocusedBorderColor = SlateBorder,
                            focusedContainerColor = SlateCard,
                            unfocusedContainerColor = SlateCard
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.rejectPlan(alert.id, rejectReason)
                        showRejectDialog = null
                        rejectReason = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusError, contentColor = TextPrimary)
                ) {
                    Text("Rechazar", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRejectDialog = null }) {
                    Text("Cancelar", color = TextSecondary)
                }
            },
            containerColor = SlateCard
        )
    }
}

@Composable
fun AlertCard(
    alert: SupervisorAlert,
    onMarkRead: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    val severityColor = when (alert.severity) {
        AlertSeverity.CRITICAL -> StatusError
        AlertSeverity.WARNING -> StatusWorking
        AlertSeverity.INFO -> PrimaryIndigo
    }

    val categoryIcon = when (alert.category) {
        AlertCategory.MASTER_PLAN -> Icons.Default.Description
        AlertCategory.BUG_FINDING -> Icons.Default.BugReport
        AlertCategory.GOVERNANCE_GATE -> Icons.Default.Gavel
        AlertCategory.SECURITY_RISK -> Icons.Default.Security
        AlertCategory.SEMANTIC_DEBT -> Icons.Default.CleaningServices
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (alert.isRead) SlateCard else SlateCard.copy(alpha = 0.9f)),
        shape = AppShapes.card,
        border = androidx.compose.foundation.BorderStroke(
            width = if (!alert.isRead) 1.5.dp else 1.dp,
            color = if (!alert.isRead) severityColor else SlateBorder
        )
    ) {
        Column(
            modifier = Modifier
                .padding(AppSpacing.cardPadding)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(categoryIcon, contentDescription = null, tint = severityColor, modifier = Modifier.size(20.dp))
                    Text(
                        text = alert.category.name.replace("_", " "),
                        style = AppTypography.labelSmall,
                        color = severityColor,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(alert.timestamp, style = AppTypography.bodySmall, color = TextSecondary)
            }

            Text(
                text = alert.title,
                style = AppTypography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                color = TextPrimary
            )

            Text(
                text = alert.description,
                style = AppTypography.bodySmall,
                color = TextSecondary
            )

            if (alert.category == AlertCategory.MASTER_PLAN && !alert.isRead) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onApprove,
                        colors = ButtonDefaults.buttonColors(containerColor = StatusConnected, contentColor = TextPrimary),
                        modifier = Modifier.weight(1f).height(38.dp),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("✓ Aprobar Plan", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = onReject,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusError),
                        modifier = Modifier.weight(1f).height(38.dp),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Rechazar", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else if (!alert.isRead) {
                TextButton(
                    onClick = onMarkRead,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Marcar como leída", color = PrimaryIndigo, fontSize = 12.sp)
                }
            }
        }
    }
}
