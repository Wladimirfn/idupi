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
import com.idupi.app.data.inbox.isInboxListenerEnabled
import com.idupi.app.data.inbox.notificationListenerSettingsIntent
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
import com.idupi.app.viewmodel.InboxSettingsViewModel

/**
 * Ajustes > ALERTAS (phone-inbox T5): notification access, monitored apps,
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

    LaunchedEffect(Unit) { viewModel.loadConfig() }

    SettingsSection("ALERTAS") {
        NotificationAccessCard(
            enabled = listenerEnabled,
            onOpenSettings = {
                val intent = notificationListenerSettingsIntent()
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            }
        )

        MonitoredAppsCard(
            settings = settings,
            onToggleApp = { appId, enabled -> viewModel.setAppEnabled(appId, enabled) }
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
                    text = "Necesario para leer WhatsApp, Gmail y Outlook. Sin acceso, " +
                        "la app no recibe mensajes ni puede responder.",
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

@Composable
private fun MonitoredAppsCard(
    settings: InboxSettings,
    onToggleApp: (String, Boolean) -> Unit
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
            Text("Apps monitoreadas", style = AppTypography.bodyLarge, color = TextPrimary)
            InboxSettingsViewModel.MONITORED_APPS.forEach { app ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = AppSpacing.xxs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = inboxAppLabel(app.id),
                        style = AppTypography.bodySmall,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = app.id in settings.enabledApps,
                        onCheckedChange = { onToggleApp(app.id, it) },
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
