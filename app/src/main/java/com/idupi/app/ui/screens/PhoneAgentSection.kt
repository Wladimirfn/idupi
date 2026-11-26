package com.idupi.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.idupi.app.data.phone.PhoneStatusReporting
import com.idupi.app.data.phone.approval.ApprovalNotificationText
import com.idupi.app.data.phone.bubble.MicPermission
import com.idupi.app.data.phone.control.PhoneCommandLogEntry
import com.idupi.app.data.phone.control.PhoneCommandLogFormat
import com.idupi.app.data.phone.control.accessibilitySettingsIntent
import com.idupi.app.data.phone.control.isAccessibilityControlEnabled
import com.idupi.app.data.phone.isUsageAccessGranted
import com.idupi.app.data.phone.usageAccessSettingsIntent
import com.idupi.app.domain.model.PhoneApproval
import com.idupi.app.ui.theme.AppShapes
import com.idupi.app.ui.theme.AppSpacing
import com.idupi.app.ui.theme.AppTypography
import com.idupi.app.ui.theme.PrimaryIndigo
import com.idupi.app.ui.theme.SlateBg
import com.idupi.app.ui.theme.SlateBorder
import com.idupi.app.ui.theme.SlateCard
import com.idupi.app.ui.theme.StatusConnected
import com.idupi.app.ui.theme.StatusError
import com.idupi.app.ui.theme.TextPrimary
import com.idupi.app.ui.theme.TextSecondary
import com.idupi.app.viewmodel.PhoneAgentReportState
import com.idupi.app.viewmodel.PhoneAgentSettingsViewModel
import com.idupi.app.viewmodel.PhoneApprovalsState

/**
 * Ajustes > ASISTENTE DEL TELÉFONO (phone-agent T2/T3): status reporting, the
 * "Control desde la PC" master switch, the accessibility and READ_PHONE_STATE
 * grants, and the recent command log.
 *
 * Accessibility, usage access and the runtime phone-state permission are
 * platform state, so they are re-read on every resume instead of surviving in
 * the ViewModel. The section stays in this file next to its cards because it is
 * the single place that knows how the phone-side permissions are granted.
 */
@Composable
fun PhoneAgentSection(viewModel: PhoneAgentSettingsViewModel) {
    val statusEnabled by viewModel.phoneStatusEnabled.collectAsState()
    val controlEnabled by viewModel.phoneControlEnabled.collectAsState()
    val bubbleEnabled by viewModel.phoneBubbleEnabled.collectAsState()
    val report by viewModel.reportState.collectAsState()
    val recentCommands by viewModel.recentCommands.collectAsState()
    val approvals by viewModel.approvals.collectAsState()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var usageAccess by remember { mutableStateOf(isUsageAccessGranted(context)) }
    var accessibilityEnabled by remember { mutableStateOf(isAccessibilityControlEnabled(context)) }
    var phoneStateGranted by remember { mutableStateOf(hasReadPhoneState(context)) }
    var microphoneGranted by remember { mutableStateOf(MicPermission.isGranted(context)) }

    // Every grant below happens in the system settings or a system dialog:
    // re-check them all when the owner comes back instead of trusting the
    // value from the first render.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                usageAccess = isUsageAccessGranted(context)
                accessibilityEnabled = isAccessibilityControlEnabled(context)
                phoneStateGranted = hasReadPhoneState(context)
                microphoneGranted = MicPermission.isGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Idempotent process-wide start: it makes "Enviar ahora" work even when no
    // service is connected in this process yet. The listener owns the lifecycle.
    LaunchedEffect(Unit) { PhoneStatusReporting.start(context) }

    // One immediate pending refresh while the section is visible. The 10 s poll
    // lives in PhoneApprovals and feeds the same store Alertas reads.
    LaunchedEffect(Unit) { viewModel.startApprovalPolling() }

    val phoneStatePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> phoneStateGranted = granted }

    // T11: in-bubble dictation records the microphone itself, so the switch
    // asks for RECORD_AUDIO here instead of surprising the owner mid-dictation.
    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> microphoneGranted = granted }

    val openAccessibilitySettings = {
        runCatching {
            context.startActivity(
                accessibilitySettingsIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        Unit
    }

    SettingsSection {
        StatusReportingCard(
            enabled = statusEnabled,
            usageAccessGranted = usageAccess,
            report = report,
            onEnabledChange = viewModel::setPhoneStatusEnabled,
            onOpenUsageAccess = {
                val intent = usageAccessSettingsIntent()
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            },
            onSendNow = viewModel::sendNow
        )

        PhoneControlCard(
            enabled = controlEnabled,
            accessibilityEnabled = accessibilityEnabled,
            phoneStateGranted = phoneStateGranted,
            recentCommands = recentCommands,
            onEnabledChange = { enabled ->
                viewModel.setPhoneControlEnabled(enabled)
                // The switch alone executes nothing: without the system grant
                // the service is never bound. Guide the owner there right away.
                if (enabled && !accessibilityEnabled) openAccessibilitySettings()
            },
            onOpenAccessibility = openAccessibilitySettings,
            onRequestPhoneState = {
                phoneStatePermission.launch(Manifest.permission.READ_PHONE_STATE)
            }
        )

        PhoneBubbleCard(
            enabled = bubbleEnabled,
            controlEnabled = controlEnabled,
            accessibilityEnabled = accessibilityEnabled,
            onEnabledChange = { enabled ->
                viewModel.setPhoneBubbleEnabled(enabled)
                if (enabled && !microphoneGranted) {
                    microphonePermission.launch(MicPermission.PERMISSION)
                }
                // The bubble is drawn by the accessibility service; without the
                // grant nothing can appear, so guide the owner there right away.
                if (enabled && !accessibilityEnabled) openAccessibilitySettings()
            },
            onOpenAccessibility = openAccessibilitySettings,
        )

        // Empty state stays hidden: the card only exists when something needs
        // the owner (or a decision just left a message).
        if (approvals.pending.isNotEmpty() || approvals.message != null) {
            PendingApprovalsCard(
                state = approvals,
                onApprove = viewModel::approveApproval,
                onReject = viewModel::rejectApproval,
            )
        }
    }
}

@Composable
private fun PendingApprovalsCard(
    state: PhoneApprovalsState,
    onApprove: (PhoneApproval) -> Unit,
    onReject: (PhoneApproval) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        shape = AppShapes.card
    ) {
        Column(
            modifier = Modifier
                .padding(AppSpacing.cardPadding)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            Text(
                text = "Aprobaciones pendientes",
                style = AppTypography.bodyLarge,
                color = TextPrimary
            )
            Text(
                text = "La PC espera tu confirmación antes de hacer algo que no se puede deshacer.",
                style = AppTypography.bodySmall,
                color = TextSecondary
            )

            if (state.pending.isEmpty()) {
                Text(
                    text = "No hay aprobaciones pendientes.",
                    style = AppTypography.bodySmall,
                    color = TextSecondary
                )
            } else {
                state.pending.forEach { approval ->
                    PendingApprovalRow(
                        approval = approval,
                        isDeciding = approval.id in state.decidingIds,
                        onApprove = { onApprove(approval) },
                        onReject = { onReject(approval) },
                    )
                }
            }

            state.message?.let { message ->
                Text(
                    text = message,
                    style = AppTypography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun PendingApprovalRow(
    approval: PhoneApproval,
    isDeciding: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
    ) {
        Text(
            text = ApprovalNotificationText.describe(approval),
            style = AppTypography.bodyLarge,
            color = TextPrimary
        )
        ApprovalNotificationText.remainingLabel(
            approval.expiresAt,
            System.currentTimeMillis(),
        )?.let { label ->
            Text(
                text = label,
                style = AppTypography.bodySmall,
                color = TextSecondary
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
            Button(
                onClick = onApprove,
                enabled = !isDeciding,
                shape = AppShapes.button,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryIndigo,
                    contentColor = Color.White
                )
            ) {
                Text("Aprobar", style = AppTypography.bodySmall)
            }
            Button(
                onClick = onReject,
                enabled = !isDeciding,
                shape = AppShapes.button,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SlateBg,
                    contentColor = TextPrimary
                )
            ) {
                Text("Rechazar", style = AppTypography.bodySmall)
            }
        }
    }
}

private fun hasReadPhoneState(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
        PackageManager.PERMISSION_GRANTED

@Composable
private fun StatusReportingCard(
    enabled: Boolean,
    usageAccessGranted: Boolean,
    report: PhoneAgentReportState,
    onEnabledChange: (Boolean) -> Unit,
    onOpenUsageAccess: () -> Unit,
    onSendNow: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        shape = AppShapes.card
    ) {
        Column(
            modifier = Modifier
                .padding(AppSpacing.cardPadding)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(SlateBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.PhoneAndroid,
                        contentDescription = null,
                        tint = if (enabled) StatusConnected else TextSecondary
                    )
                }
                Spacer(modifier = Modifier.width(AppSpacing.lg))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Enviar estado del teléfono a la PC",
                        style = AppTypography.bodyLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = "Batería, señal, red, memoria, almacenamiento y uso de hoy.",
                        style = AppTypography.bodySmall,
                        color = TextSecondary
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = PrimaryIndigo,
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = SlateBg,
                        uncheckedBorderColor = SlateBorder
                    )
                )
            }

            Divider(color = SlateBorder)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Acceso a datos de uso",
                        style = AppTypography.bodyLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = if (usageAccessGranted) "Activado" else "Desactivado",
                        style = AppTypography.bodySmall,
                        color = if (usageAccessGranted) StatusConnected else StatusError
                    )
                }
            }
            if (!usageAccessGranted) {
                Text(
                    text = "Necesario para el tiempo por app y la app en primer plano.",
                    style = AppTypography.bodySmall,
                    color = TextSecondary
                )
                Button(
                    onClick = onOpenUsageAccess,
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppShapes.button,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryIndigo,
                        contentColor = Color.White
                    )
                ) {
                    Text("Dar acceso a datos de uso", style = AppTypography.bodySmall)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                Button(
                    onClick = onSendNow,
                    enabled = !report.isSending,
                    shape = AppShapes.button,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryIndigo,
                        contentColor = Color.White
                    )
                ) {
                    Text("Enviar ahora", style = AppTypography.bodySmall)
                }
                if (report.isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = PrimaryIndigo
                    )
                }
            }

            report.message?.let { message ->
                Text(
                    text = message,
                    style = AppTypography.bodySmall,
                    color = if (report.success == true) StatusConnected else StatusError
                )
            }
        }
    }
}

@Composable
private fun PhoneControlCard(
    enabled: Boolean,
    accessibilityEnabled: Boolean,
    phoneStateGranted: Boolean,
    recentCommands: List<PhoneCommandLogEntry>,
    onEnabledChange: (Boolean) -> Unit,
    onOpenAccessibility: () -> Unit,
    onRequestPhoneState: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        shape = AppShapes.card
    ) {
        Column(
            modifier = Modifier
                .padding(AppSpacing.cardPadding)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(SlateBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.PhoneAndroid,
                        contentDescription = null,
                        tint = if (enabled && accessibilityEnabled) StatusConnected else TextSecondary
                    )
                }
                Spacer(modifier = Modifier.width(AppSpacing.lg))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Control desde la PC",
                        style = AppTypography.bodyLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = "Leer la pantalla, tocar, escribir, desplazar y abrir apps.",
                        style = AppTypography.bodySmall,
                        color = TextSecondary
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = PrimaryIndigo,
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = SlateBg,
                        uncheckedBorderColor = SlateBorder
                    )
                )
            }

            Divider(color = SlateBorder)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Accesibilidad",
                        style = AppTypography.bodyLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = if (accessibilityEnabled) "Activado" else "Desactivado",
                        style = AppTypography.bodySmall,
                        color = if (accessibilityEnabled) StatusConnected else StatusError
                    )
                }
                if (!accessibilityEnabled) {
                    Button(
                        onClick = onOpenAccessibility,
                        shape = AppShapes.button,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryIndigo,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Activar", style = AppTypography.bodySmall)
                    }
                }
            }
            if (!accessibilityEnabled) {
                Text(
                    text = "Habilita IDUPI en Accesibilidad para que la PC pueda controlar el teléfono.",
                    style = AppTypography.bodySmall,
                    color = TextSecondary
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Text(
                        text = "Si aparece bloqueado: Ajustes del teléfono > Apps > IDUPI > ⋮ > Permitir ajustes restringidos",
                        style = AppTypography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            Divider(color = SlateBorder)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Tipo de red (4G/5G)",
                        style = AppTypography.bodyLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = if (phoneStateGranted) "Activado" else "Desactivado",
                        style = AppTypography.bodySmall,
                        color = if (phoneStateGranted) StatusConnected else StatusError
                    )
                }
                if (!phoneStateGranted) {
                    Button(
                        onClick = onRequestPhoneState,
                        shape = AppShapes.button,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryIndigo,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Permitir", style = AppTypography.bodySmall)
                    }
                }
            }
            if (!phoneStateGranted) {
                Text(
                    text = "Permite que el estado informe si estás en 4G o 5G.",
                    style = AppTypography.bodySmall,
                    color = TextSecondary
                )
            }

            Divider(color = SlateBorder)

            Text(
                text = "Actividad reciente",
                style = AppTypography.bodyLarge,
                color = TextPrimary
            )
            if (recentCommands.isEmpty()) {
                Text(
                    text = "Sin comandos todavía.",
                    style = AppTypography.bodySmall,
                    color = TextSecondary
                )
            } else {
                recentCommands.take(RECENT_COMMANDS_IN_UI).forEach { entry ->
                    RecentCommandRow(entry)
                }
            }
        }
    }
}

@Composable
private fun PhoneBubbleCard(
    enabled: Boolean,
    controlEnabled: Boolean,
    accessibilityEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onOpenAccessibility: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        shape = AppShapes.card
    ) {
        Column(
            modifier = Modifier
                .padding(AppSpacing.cardPadding)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(SlateBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.ChatBubbleOutline,
                        contentDescription = null,
                        tint = if (enabled && controlEnabled && accessibilityEnabled) {
                            StatusConnected
                        } else {
                            TextSecondary
                        }
                    )
                }
                Spacer(modifier = Modifier.width(AppSpacing.lg))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Burbuja de IDUPI",
                        style = AppTypography.bodyLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = "Pide cosas al agente desde una burbuja sobre otras apps.",
                        style = AppTypography.bodySmall,
                        color = TextSecondary
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = PrimaryIndigo,
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = SlateBg,
                        uncheckedBorderColor = SlateBorder
                    )
                )
            }

            Divider(color = SlateBorder)

            when {
                !accessibilityEnabled -> {
                    Text(
                        text = "Necesita Accesibilidad activa para dibujarse sobre otras apps.",
                        style = AppTypography.bodySmall,
                        color = StatusError
                    )
                    Button(
                        onClick = onOpenAccessibility,
                        shape = AppShapes.button,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryIndigo,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Activar Accesibilidad", style = AppTypography.bodySmall)
                    }
                }
                !controlEnabled -> Text(
                    text = "Necesita «Control desde la PC» encendido. Actívalo arriba para que la burbuja aparezca.",
                    style = AppTypography.bodySmall,
                    color = StatusError
                )
                else -> Text(
                    text = "La burbuja aparece sobre las demás apps: escribe o dicta tu pedido y el agente responde ahí.",
                    style = AppTypography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun RecentCommandRow(entry: PhoneCommandLogEntry) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${PhoneCommandLogFormat.clock(entry.atMillis)} · ${entry.type} · ${entry.target}",
                style = AppTypography.bodySmall,
                color = TextPrimary
            )
            entry.error?.let { error ->
                Text(
                    text = error,
                    style = AppTypography.bodySmall,
                    color = StatusError
                )
            }
        }
        Text(
            text = if (entry.ok) "OK" else "Error",
            style = AppTypography.bodySmall,
            color = if (entry.ok) StatusConnected else StatusError
        )
    }
}

/** The recent-activity card stays scannable; the full log keeps 30 entries. */
private const val RECENT_COMMANDS_IN_UI = 8
