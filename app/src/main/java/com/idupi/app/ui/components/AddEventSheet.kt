package com.idupi.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.idupi.app.data.calendar.CalendarEventDraft
import com.idupi.app.domain.model.PhoneCalendarRef
import com.idupi.app.ui.screens.agendaConflictLine
import com.idupi.app.ui.theme.AppShapes
import com.idupi.app.ui.theme.AppSpacing
import com.idupi.app.ui.theme.AppTypography
import com.idupi.app.ui.theme.PrimaryIndigo
import com.idupi.app.ui.theme.SlateBg
import com.idupi.app.ui.theme.SlateBorder
import com.idupi.app.ui.theme.SlateCard
import com.idupi.app.ui.theme.StatusError
import com.idupi.app.ui.theme.StatusWorking
import com.idupi.app.ui.theme.TextPrimary
import com.idupi.app.ui.theme.TextSecondary
import com.idupi.app.viewmodel.CalendarAddEventState
import java.time.ZoneId

/**
 * Add-event confirm sheet (phone-calendar T3), shared by Alertas and Agenda.
 *
 * The owner sees and can edit every field before saving; the sheet never writes
 * on its own. A local conflict warning is informational, not a block: the owner
 * decides whether the overlap is acceptable.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddEventSheet(
    state: CalendarAddEventState,
    onDraftChange: (CalendarEventDraft) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    if (!state.isOpen) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SlateCard,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(AppSpacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        ) {
            Text(
                text = if (state.proposalId != null) "Revisar y guardar" else "Nuevo evento",
                style = AppTypography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                color = TextPrimary,
            )
            Text(
                text = "Revisa los datos antes de guardar. Nada se escribe hasta que toques Guardar.",
                style = AppTypography.bodySmall,
                color = TextSecondary,
            )

            OutlinedTextField(
                value = state.draft.title,
                onValueChange = { onDraftChange(state.draft.copy(title = it)) },
                label = { Text("Título", style = AppTypography.bodySmall) },
                singleLine = true,
                colors = calendarFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.draft.date,
                onValueChange = { onDraftChange(state.draft.copy(date = it)) },
                label = { Text("Fecha (AAAA-MM-DD)", style = AppTypography.bodySmall) },
                singleLine = true,
                colors = calendarFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Todo el día",
                    style = AppTypography.bodySmall,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = state.draft.allDay,
                    onCheckedChange = { onDraftChange(state.draft.copy(allDay = it)) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = PrimaryIndigo,
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = SlateBg,
                        uncheckedBorderColor = SlateBorder,
                    ),
                )
            }

            if (!state.draft.allDay) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                ) {
                    OutlinedTextField(
                        value = state.draft.startTime,
                        onValueChange = { onDraftChange(state.draft.copy(startTime = it)) },
                        label = { Text("Inicio (HH:MM)", style = AppTypography.bodySmall) },
                        singleLine = true,
                        colors = calendarFieldColors(),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = state.draft.endTime,
                        onValueChange = { onDraftChange(state.draft.copy(endTime = it)) },
                        label = { Text("Fin (HH:MM)", style = AppTypography.bodySmall) },
                        singleLine = true,
                        colors = calendarFieldColors(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            OutlinedTextField(
                value = state.draft.location,
                onValueChange = { onDraftChange(state.draft.copy(location = it)) },
                label = { Text("Lugar (opcional)", style = AppTypography.bodySmall) },
                singleLine = true,
                colors = calendarFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Calendario", style = AppTypography.bodySmall, color = TextSecondary)
            if (state.calendars.isEmpty()) {
                Text(
                    text = "No hay calendarios editables.",
                    style = AppTypography.bodySmall,
                    color = StatusError,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                ) {
                    state.calendars.forEach { calendar ->
                        FilterChip(
                            selected = calendar.id == state.draft.calendarId,
                            onClick = {
                                onDraftChange(state.draft.copy(calendarId = calendar.id))
                            },
                            label = {
                                Text(
                                    calendarChipLabel(calendar),
                                    style = AppTypography.labelSmall,
                                    maxLines = 1,
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = PrimaryIndigo.copy(alpha = 0.15f),
                                selectedLabelColor = PrimaryIndigo,
                                labelColor = TextSecondary,
                            ),
                        )
                    }
                }
            }

            agendaConflictLine(state.conflicts, zone)?.let { line ->
                Text(line, style = AppTypography.bodySmall, color = StatusWorking)
            }

            state.validationMessage?.let { message ->
                Text(message, style = AppTypography.bodySmall, color = StatusError)
            }
            state.errorMessage?.let { message ->
                Text(message, style = AppTypography.bodySmall, color = StatusError)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onSave,
                    enabled = !state.isSaving &&
                        state.hasWritePermission &&
                        state.calendars.isNotEmpty(),
                    shape = AppShapes.button,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryIndigo,
                        contentColor = Color.White,
                    ),
                ) {
                    Text("Guardar", style = AppTypography.bodySmall)
                }
                if (state.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(horizontal = AppSpacing.sm),
                        color = PrimaryIndigo,
                        strokeWidth = 2.dp,
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancelar", style = AppTypography.bodySmall, color = TextSecondary)
                }
            }
            Text(
                text = "Se guarda en el calendario que elijas, solo en este teléfono.",
                style = AppTypography.labelSmall,
                color = TextSecondary,
            )
        }
    }
}

private fun calendarChipLabel(calendar: PhoneCalendarRef): String {
    val name = calendar.name.ifBlank { "Calendario" }
    val account = when {
        calendar.accountType.contains("google", ignoreCase = true) -> "Google"
        calendar.accountType.contains("microsoft", ignoreCase = true) ||
            calendar.accountType.contains("outlook", ignoreCase = true) -> "Outlook"
        calendar.accountName.isNotBlank() -> calendar.accountName
        else -> ""
    }
    return if (account.isBlank()) name else "$name · $account"
}

@Composable
private fun calendarFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedBorderColor = PrimaryIndigo,
    unfocusedBorderColor = SlateBorder,
    focusedContainerColor = SlateBg,
    unfocusedContainerColor = SlateBg,
    focusedLabelColor = PrimaryIndigo,
    unfocusedLabelColor = TextSecondary,
)
