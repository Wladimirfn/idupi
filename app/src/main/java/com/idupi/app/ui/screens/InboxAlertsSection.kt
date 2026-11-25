package com.idupi.app.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.idupi.app.data.inbox.InboxCaptureState
import com.idupi.app.data.inbox.InstalledApp
import com.idupi.app.data.inbox.InstalledApps
import com.idupi.app.data.inbox.isInboxListenerEnabled
import com.idupi.app.data.inbox.notificationListenerSettingsIntent
import com.idupi.app.data.inbox.requestInboxListenerRebind
import com.idupi.app.domain.model.InboxImportance
import com.idupi.app.domain.model.InboxSettings
import com.idupi.app.ui.theme.AppShapes
import com.idupi.app.ui.theme.AppSpacing
import com.idupi.app.ui.theme.AppTypography
import com.idupi.app.ui.theme.PrimaryIndigo
import com.idupi.app.ui.theme.SlateBg
import com.idupi.app.ui.theme.SlateBorder
import com.idupi.app.ui.theme.SlateCard
import com.idupi.app.ui.theme.StatusConnected
import com.idupi.app.ui.theme.StatusError
import com.idupi.app.ui.theme.StatusWorking
import com.idupi.app.ui.theme.TextPrimary
import com.idupi.app.ui.theme.TextSecondary
import com.idupi.app.viewmodel.InboxAgentConfigState
import com.idupi.app.viewmodel.InboxCaptureActionState
import com.idupi.app.viewmodel.InboxSettingsViewModel
import com.idupi.app.viewmodel.InstalledAppsState

/**
 * Ajustes > ALERTAS (phone-inbox T5): notification access, followed apps,
 * reminder cadence, template auto-reply and the PC-side agent configuration.
 *
 * Every phone-side switch is persisted on change; the agent/model/effort is
 * read from the server when this section appears and saved with an explicit
 * button because it changes what runs on the owner's PC.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun InboxAlertsSection(viewModel: InboxSettingsViewModel) {
    val settings by viewModel.inboxSettings.collectAsState()
    val config by viewModel.agentConfig.collectAsState()
    val captureStatus by viewModel.captureStatus.collectAsState()
    val captureAction by viewModel.captureAction.collectAsState()
    val installedApps by viewModel.installedApps.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var listenerEnabled by remember { mutableStateOf(isInboxListenerEnabled(context)) }
    // Notification access is granted in the system settings: re-check every
    // time the owner comes back instead of trusting the initial value.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                listenerEnabled = isInboxListenerEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        viewModel.loadConfig()
        viewModel.refreshCaptureStatus()
        viewModel.loadInstalledApps()
    }

    SettingsSection("ALERTAS") {
        NotificationAccessCard(
            enabled = listenerEnabled,
            onOpenSettings = {
                val intent = notificationListenerSettingsIntent()
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            }
        )

        CaptureStatusCard(
            enabled = listenerEnabled,
            status = captureStatus,
            action = captureAction,
            onScanNow = {
                // "Instead of scanning" a dead listener, ask the system to
                // rebind it; the card still shows the requested status below.
                if (!captureStatus.listenerConnected && listenerEnabled) {
                    requestInboxListenerRebind(context)
                    Toast.makeText(context, "Reconectando el lector", Toast.LENGTH_SHORT).show()
                }
                viewModel.scanNow()
            },
            onReconnect = {
                val requested = requestInboxListenerRebind(context)
                Toast.makeText(
                    context,
                    if (requested) {
                        "Reconectando el lector"
                    } else {
                        "No se pudo reconectar el lector"
                    },
                    Toast.LENGTH_SHORT
                ).show()
            }
        )

        FollowedAppsCard(
            settings = settings,
            state = installedApps,
            onToggleApp = { appId, followed -> viewModel.setAppFollowed(appId, followed) },
            onReload = { viewModel.loadInstalledApps(force = true) }
        )

        SilencedChatsCard(
            silenced = settings.silencedConversations,
            onRemove = { conversationKey ->
                viewModel.setConversationSilenced(conversationKey, silenced = false)
            }
        )

        RemindersCard(
            settings = settings,
            onIntervalSelect = viewModel::setReminderInterval,
            onMinImportanceSelect = viewModel::setRemindMinImportance
        )

        AutoReplyCard(
            settings = settings,
            onEnabledChange = viewModel::setAutoReplyEnabled,
            onDelaySelect = viewModel::setAutoReplyDelay,
            onTemplateSave = viewModel::setAutoReplyTemplate
        )

        AgentConfigCard(
            config = config,
            onAgentSelect = viewModel::setAgent,
            onModelChange = viewModel::setModel,
            onEffortSelect = viewModel::setEffort,
            onSave = viewModel::saveConfig
        )
    }
}

@Composable
private fun NotificationAccessCard(enabled: Boolean, onOpenSettings: () -> Unit) {
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
                        Icons.Default.Notifications,
                        contentDescription = null,
                        tint = if (enabled) StatusConnected else StatusError
                    )
                }
                Spacer(modifier = Modifier.width(AppSpacing.lg))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Acceso a notificaciones",
                        style = AppTypography.bodyLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = if (enabled) "Activado" else "Desactivado",
                        style = AppTypography.bodySmall,
                        color = if (enabled) StatusConnected else StatusError
                    )
                }
            }
            if (!enabled) {
                Text(
                    text = "Necesario para leer las notificaciones de las apps que sigues. " +
                        "Sin acceso, la app no recibe mensajes ni puede responder.",
                    style = AppTypography.bodySmall,
                    color = TextSecondary
                )
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppShapes.button,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryIndigo,
                        contentColor = Color.White
                    )
                ) {
                    Text("Dar acceso a notificaciones", style = AppTypography.bodySmall)
                }
            }
        }
    }
}

/**
 * Live capture state (inbox capture fix T1): whether the listener is really
 * bound, the last capture and upload result, how many retries are waiting, and
 * the two recovery actions. "Escuchando" is diagnostics state, not the
 * permission the access card above shows.
 */
@Composable
private fun CaptureStatusCard(
    enabled: Boolean,
    status: InboxCaptureState,
    action: InboxCaptureActionState,
    onScanNow: () -> Unit,
    onReconnect: () -> Unit
) {
    val now = System.currentTimeMillis()
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
            Text("Estado de captura", style = AppTypography.bodyLarge, color = TextPrimary)
            Text(
                text = "Escuchando: ${if (status.listenerConnected) "sí" else "no"}",
                style = AppTypography.bodySmall,
                color = if (status.listenerConnected) StatusConnected else StatusError
            )
            if (!status.listenerConnected) {
                Text(
                    text = if (enabled) {
                        "Android desconectó el lector; toca Reconectar"
                    } else {
                        "Sin acceso a notificaciones, el lector no puede escuchar."
                    },
                    style = AppTypography.bodySmall,
                    color = TextSecondary
                )
            }
            Text(
                text = lastCaptureLabel(status, now),
                style = AppTypography.bodySmall,
                color = TextSecondary
            )
            Text(
                text = lastUploadLabel(status, now),
                style = AppTypography.bodySmall,
                color = TextSecondary
            )
            Text(
                text = "Pendientes de reenviar: ${status.pendingRetryCount}",
                style = AppTypography.bodySmall,
                color = TextSecondary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                Button(
                    onClick = onScanNow,
                    enabled = !action.isScanning,
                    modifier = Modifier.weight(1f),
                    shape = AppShapes.button,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryIndigo,
                        contentColor = Color.White
                    )
                ) {
                    Text(
                        text = if (action.isScanning) {
                            "Leyendo..."
                        } else {
                            "Leer notificaciones ahora"
                        },
                        style = AppTypography.bodySmall
                    )
                }
                if (!status.listenerConnected && enabled) {
                    Button(
                        onClick = onReconnect,
                        modifier = Modifier.weight(1f),
                        shape = AppShapes.button,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SlateBorder,
                            contentColor = TextPrimary
                        )
                    ) {
                        Text("Reconectar", style = AppTypography.bodySmall)
                    }
                }
            }
            action.scanMessage?.let { message ->
                Text(
                    text = message,
                    style = AppTypography.labelSmall,
                    color = StatusWorking
                )
            }
        }
    }
}

private fun lastCaptureLabel(status: InboxCaptureState, nowMillis: Long): String {
    val at = status.lastCaptureAtMillis ?: return "Última notificación leída: ninguna todavía"
    val appLabel = status.lastCaptureApp?.let { inboxAppLabel(it) }
    val suffix = if (appLabel.isNullOrBlank()) "" else " ($appLabel)"
    return "Última notificación leída: ${relativeTimeLabel(at, nowMillis)}$suffix"
}

private fun lastUploadLabel(status: InboxCaptureState, nowMillis: Long): String {
    val errorClass = status.lastUploadErrorClass
    val errorAt = status.lastUploadErrorAtMillis
    if (!errorClass.isNullOrBlank() && errorAt != null) {
        return "Último envío al PC: falló: $errorClass ${relativeTimeLabel(errorAt, nowMillis)}"
    }
    val okAt = status.lastUploadOkAtMillis ?: return "Último envío al PC: ninguno todavía"
    return "Último envío al PC: ok ${relativeTimeLabel(okAt, nowMillis)}"
}

@Composable
private fun FollowedAppsCard(
    settings: InboxSettings,
    state: InstalledAppsState,
    onToggleApp: (String, Boolean) -> Unit,
    onReload: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val followingCount = remember(state.inventory, settings.followedApps) {
        state.inventory.followingCount(settings.followedApps)
    }
    val others = remember(state.inventory.others, query) {
        InstalledApps.matching(state.inventory.others, query)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        shape = AppShapes.card
    ) {
        Column(
            modifier = Modifier
                .padding(AppSpacing.cardPadding)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
        ) {
            Text("Apps a seguir", style = AppTypography.bodyLarge, color = TextPrimary)
            Text(
                text = "Siguiendo $followingCount apps",
                style = AppTypography.labelSmall,
                color = StatusWorking
            )
            Text(
                text = "Activa las apps cuyas notificaciones quieres ver en Alertas.",
                style = AppTypography.bodySmall,
                color = TextSecondary
            )

            if (state.isLoading) {
                Text(
                    text = "Leyendo apps instaladas…",
                    style = AppTypography.bodySmall,
                    color = TextSecondary
                )
            }
            state.errorMessage?.let { message ->
                Text(message, style = AppTypography.bodySmall, color = StatusError)
                TextButton(onClick = onReload) {
                    Text("Reintentar", style = AppTypography.labelSmall, color = PrimaryIndigo)
                }
            }

            if (!state.isLoading && !state.inventory.isEmpty) {
                state.inventory.presets.forEach { app ->
                    AppFollowRow(app = app, followed = settings.followedApps, onToggle = onToggleApp)
                }

                Spacer(modifier = Modifier.size(AppSpacing.xs))
                Text("Otras apps", style = AppTypography.bodySmall, color = TextSecondary)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Buscar apps", style = AppTypography.bodySmall) },
                    singleLine = true,
                    colors = inboxFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (others.isEmpty()) {
                    Text(
                        text = if (state.inventory.others.isEmpty()) {
                            "No hay otras apps instaladas"
                        } else {
                            "No hay apps que coincidan"
                        },
                        style = AppTypography.bodySmall,
                        color = TextSecondary
                    )
                } else {
                    others.forEach { app ->
                        AppFollowRow(
                            app = app,
                            followed = settings.followedApps,
                            onToggle = onToggleApp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppFollowRow(
    app: InstalledApp,
    followed: Set<String>,
    onToggle: (String, Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.displayLabel,
                style = AppTypography.bodySmall,
                color = TextPrimary
            )
            // The package is what the server stores as the id for non-presets,
            // so showing it keeps the row unambiguous.
            if (app.preset == null) {
                Text(
                    text = app.packageName,
                    style = AppTypography.labelSmall,
                    color = TextSecondary
                )
            }
        }
        Switch(
            checked = app.appId in followed,
            onCheckedChange = { onToggle(app.appId, it) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = PrimaryIndigo,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = SlateBg,
                uncheckedBorderColor = SlateBorder
            )
        )
    }
}

/**
 * Chats silenced inside IDUPI (inbox-management T4). The hint is the whole
 * contract: IDUPI can only read a chat the source app does not mute, so the
 * owner un-mutes it there and marks it here. Removing a row restores the
 * notifications on the next capture.
 */
@Composable
private fun SilencedChatsCard(
    silenced: Set<String>,
    onRemove: (String) -> Unit
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
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
        ) {
            Text("Chats silenciados por IDUPI", style = AppTypography.bodyLarge, color = TextPrimary)
            Text(
                text = "Para que IDUPI lea un grupo silenciado, quítale el silencio en la app " +
                    "original y márcalo aquí como silenciado.",
                style = AppTypography.bodySmall,
                color = TextSecondary
            )
            if (silenced.isEmpty()) {
                Text(
                    text = "No hay chats silenciados",
                    style = AppTypography.bodySmall,
                    color = TextSecondary
                )
            } else {
                silenced
                    .sortedBy { silencedConversationLabel(it) }
                    .forEach { conversationKey ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = silencedConversationLabel(conversationKey),
                                style = AppTypography.bodySmall,
                                color = TextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { onRemove(conversationKey) }) {
                                Text(
                                    text = "Quitar",
                                    style = AppTypography.labelSmall,
                                    color = PrimaryIndigo
                                )
                            }
                        }
                    }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemindersCard(
    settings: InboxSettings,
    onIntervalSelect: (Int) -> Unit,
    onMinImportanceSelect: (String) -> Unit
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
            Text("Recordatorios", style = AppTypography.bodyLarge, color = TextPrimary)
            Text(
                text = "Mientras un mensaje importante siga sin responder.",
                style = AppTypography.bodySmall,
                color = TextSecondary
            )
            Text("Avisarme cada", style = AppTypography.bodySmall, color = TextSecondary)
            MinutesChips(
                options = REMINDER_INTERVAL_OPTIONS,
                selected = settings.reminderIntervalMinutes,
                onSelect = onIntervalSelect
            )
            Text("Importancia mínima", style = AppTypography.bodySmall, color = TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                InboxChoiceChip(
                    label = "Alta",
                    selected = settings.remindMinImportance == InboxImportance.HIGH,
                    onClick = { onMinImportanceSelect(InboxImportance.HIGH) }
                )
                InboxChoiceChip(
                    label = "Media y alta",
                    selected = settings.remindMinImportance == InboxImportance.MEDIUM,
                    onClick = { onMinImportanceSelect(InboxImportance.MEDIUM) }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AutoReplyCard(
    settings: InboxSettings,
    onEnabledChange: (Boolean) -> Unit,
    onDelaySelect: (Int) -> Unit,
    onTemplateSave: (String) -> Unit
) {
    val context = LocalContext.current
    var templateDraft by remember(settings.autoReplyTemplate) {
        mutableStateOf(settings.autoReplyTemplate)
    }

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
                Text(
                    text = "Respuesta automática",
                    style = AppTypography.bodyLarge,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = settings.autoReplyEnabled,
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
            Text(
                text = "Solo responde WhatsApp con tu texto, nunca con texto de la IA",
                style = AppTypography.labelSmall,
                color = StatusWorking
            )
            Text("Responder después de", style = AppTypography.bodySmall, color = TextSecondary)
            MinutesChips(
                options = AUTO_REPLY_DELAY_OPTIONS,
                selected = settings.autoReplyDelayMinutes,
                onSelect = onDelaySelect
            )
            OutlinedTextField(
                value = templateDraft,
                onValueChange = { templateDraft = it },
                label = { Text("Tu mensaje", style = AppTypography.bodySmall) },
                minLines = 2,
                maxLines = 4,
                colors = inboxFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = {
                        onTemplateSave(templateDraft)
                        Toast.makeText(context, "Plantilla guardada", Toast.LENGTH_SHORT).show()
                    },
                    enabled = templateDraft != settings.autoReplyTemplate
                ) {
                    Text(
                        text = "Guardar plantilla",
                        style = AppTypography.labelSmall,
                        color = PrimaryIndigo
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentConfigCard(
    config: InboxAgentConfigState,
    onAgentSelect: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onEffortSelect: (String) -> Unit,
    onSave: () -> Unit
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
            Text("Analista en la PC", style = AppTypography.bodyLarge, color = TextPrimary)
            Text(
                text = "El agente que clasifica, resume y sugiere la respuesta.",
                style = AppTypography.bodySmall,
                color = TextSecondary
            )

            if (config.isLoading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = PrimaryIndigo
                    )
                    Text("Cargando configuración…", style = AppTypography.bodySmall, color = TextSecondary)
                }
            }

            InboxDropdown(
                label = "Agente",
                selectedLabel = agentLabel(config.agent),
                options = config.agents,
                optionLabel = { agentLabel(it) },
                onSelect = onAgentSelect
            )

            if (config.agent == InboxAgentConfigState.OPENCODE && config.models.isNotEmpty()) {
                InboxDropdown(
                    label = "Modelo",
                    selectedLabel = config.model,
                    options = config.models,
                    optionLabel = { it },
                    onSelect = onModelChange
                )
            } else {
                OutlinedTextField(
                    value = config.model,
                    onValueChange = onModelChange,
                    label = { Text("Modelo", style = AppTypography.bodySmall) },
                    singleLine = true,
                    placeholder = { Text("proveedor/modelo", color = TextSecondary) },
                    colors = inboxFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (config.agent == InboxAgentConfigState.OPENCODE && config.isLoadingModels) {
                Text("Cargando modelos…", style = AppTypography.bodySmall, color = TextSecondary)
            }

            InboxDropdown(
                label = "Esfuerzo",
                selectedLabel = effortLabel(config.effort),
                options = config.efforts,
                optionLabel = { effortLabel(it) },
                onSelect = onEffortSelect
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                Button(
                    onClick = onSave,
                    enabled = !config.isSaving,
                    shape = AppShapes.button,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryIndigo,
                        contentColor = Color.White
                    )
                ) {
                    Text("Guardar", style = AppTypography.bodySmall, fontWeight = FontWeight.Bold)
                }
                if (config.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = PrimaryIndigo
                    )
                }
            }

            config.errorMessage?.let { message ->
                Text(message, style = AppTypography.bodySmall, color = StatusError)
            }
            config.successMessage?.let { message ->
                Text(message, style = AppTypography.bodySmall, color = StatusConnected)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MinutesChips(
    options: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
    ) {
        options.forEach { minutes ->
            InboxChoiceChip(
                label = "$minutes min",
                selected = minutes == selected,
                onClick = { onSelect(minutes) }
            )
        }
    }
}

@Composable
private fun InboxChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = AppTypography.labelSmall) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = PrimaryIndigo.copy(alpha = 0.15f),
            selectedLabelColor = PrimaryIndigo,
            labelColor = TextSecondary
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> InboxDropdown(
    label: String,
    selectedLabel: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label, style = AppTypography.bodySmall) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = inboxFieldColors(),
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(SlateCard)
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option), color = TextPrimary) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun inboxFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedBorderColor = PrimaryIndigo,
    unfocusedBorderColor = SlateBorder,
    focusedLabelColor = PrimaryIndigo,
    unfocusedLabelColor = TextSecondary,
    focusedContainerColor = SlateBg,
    unfocusedContainerColor = SlateBg,
    cursorColor = PrimaryIndigo
)

private val REMINDER_INTERVAL_OPTIONS = listOf(5, 10, 15, 30, 60)
private val AUTO_REPLY_DELAY_OPTIONS = listOf(15, 30, 60, 120)
