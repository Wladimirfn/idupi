package com.idupi.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.idupi.app.data.calendar.CalendarOverlap
import com.idupi.app.data.calendar.CalendarPermissions
import com.idupi.app.data.calendar.CalendarReporting
import com.idupi.app.domain.model.PhoneCalendarProposal
import com.idupi.app.ui.components.AddEventSheet
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
import com.idupi.app.viewmodel.CalendarViewModel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * Agenda (phone-calendar T2/T3): the next 7 local days read from the phone's
 * own calendars, with overlapping events flagged, and pending PC proposals at
 * the top. Proposals only become events after "Revisar y guardar" and an
 * explicit Guardar; "Rechazar" reports the decision without writing.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AgendaScreen(
    viewModel: CalendarViewModel,
    onMenuClick: () -> Unit,
) {
    val agenda by viewModel.agenda.collectAsState()
    val addEvent by viewModel.addEvent.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val zone = remember { ZoneId.systemDefault() }
    val today = remember(agenda) {
        Instant.ofEpochMilli(System.currentTimeMillis()).atZone(zone).toLocalDate()
    }

    DisposableEffect(viewModel) {
        viewModel.startPolling()
        onDispose { viewModel.stopPolling() }
    }

    // A permission revoked in the system settings must be reflected without a
    // process restart; the first load also starts reporting so Agenda works
    // even when Ajustes was never opened in this process.
    LaunchedEffect(Unit) { CalendarReporting.start(context) }

    LaunchedEffect(agenda.errorMessage) {
        agenda.errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }

    val allEvents = remember(agenda.days) { agenda.days.flatMap { it.events }.distinctBy { it.id } }
    val conflictMap = remember(allEvents) {
        allEvents.associate { event ->
            event.id to CalendarOverlap.conflictsFor(
                startMillis = CalendarOverlap.parseInstant(event.start) ?: 0L,
                endMillis = CalendarOverlap.parseInstant(event.end) ?: 0L,
                allDay = event.allDay,
                events = allEvents,
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agenda", style = AppTypography.appBarTitle, color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(Icons.Default.Menu, contentDescription = "Menú", tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Actualizar",
                            tint = TextPrimary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SlateCard),
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                androidx.compose.material3.Snackbar(
                    containerColor = SlateCard,
                    contentColor = TextPrimary,
                    shape = AppShapes.card,
                ) {
                    Text(data.visuals.message, color = TextPrimary)
                }
            }
        },
        containerColor = SlateBg,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = AppSpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
        ) {
            Spacer(modifier = Modifier.height(AppSpacing.xs))
            if (agenda.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = PrimaryIndigo)
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
                    contentPadding = PaddingValues(bottom = AppSpacing.xl),
                ) {
                    if (agenda.proposals.isNotEmpty()) {
                        item {
                            Text(
                                text = "Propuestas del PC",
                                style = AppTypography.titleSmall,
                                color = TextSecondary,
                            )
                        }
                        items(agenda.proposals, key = { "proposal-${it.id}" }) { proposal ->
                            ProposalCard(
                                proposal = proposal,
                                deciding = proposal.id in agenda.decidingProposalIds,
                                zone = zone,
                                nowMillis = System.currentTimeMillis(),
                                onReview = { viewModel.openProposal(proposal) },
                                onReject = { viewModel.rejectProposal(proposal) },
                            )
                        }
                    }

                    if (agenda.days.isEmpty()) {
                        item {
                            EmptyAgenda(
                                hasPermission = agenda.hasCalendarPermission ||
                                    CalendarPermissions.hasRead(context),
                            )
                        }
                    } else {
                        item {
                            Text(
                                text = "Próximos 7 días",
                                style = AppTypography.titleSmall,
                                color = TextSecondary,
                            )
                        }
                        agenda.days.forEach { day ->
                            item(key = "day-${day.date}") {
                                DayHeader(title = dayHeadingLabel(day.date, today))
                            }
                            items(day.events, key = { "event-${day.date}-${it.id}" }) { event ->
                                AgendaEventRow(
                                    whenLabel = agendaEventWhen(event, zone),
                                    title = event.title.ifBlank { "Evento sin título" },
                                    location = event.location,
                                    allDay = event.allDay,
                                    conflictLine = agendaConflictLine(
                                        conflictMap[event.id].orEmpty(),
                                        zone,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    AddEventSheet(
        state = addEvent,
        onDraftChange = viewModel::updateDraft,
        onSave = {
            viewModel.saveEvent { saved ->
                scope.launch {
                    snackbarHostState.showSnackbar("Evento guardado en ${saved.calendarName}")
                }
            }
        },
        onDismiss = viewModel::closeAddEvent,
        zone = zone,
    )
}

@Composable
private fun DayHeader(title: String) {
    Text(
        text = title,
        style = AppTypography.bodyLarge.copy(fontWeight = FontWeight.Bold),
        color = PrimaryIndigo,
    )
}

@Composable
private fun EmptyAgenda(hasPermission: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        shape = AppShapes.card,
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
        ) {
            Text(
                text = if (hasPermission) {
                    "No hay eventos en los próximos 7 días."
                } else {
                    "Sin permiso para leer el calendario. Actívalo en Ajustes > CALENDARIO."
                },
                style = AppTypography.bodySmall,
                color = TextSecondary,
            )
        }
    }
}

@Composable
private fun AgendaEventRow(
    whenLabel: String,
    title: String,
    location: String,
    allDay: Boolean,
    conflictLine: String?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        shape = AppShapes.card,
        border = BorderStroke(1.dp, SlateBorder),
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xxs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (allDay) "Todo el día" else whenLabel,
                    style = AppTypography.labelSmall,
                    color = PrimaryIndigo,
                )
                Text(
                    text = title,
                    style = AppTypography.bodySmall,
                    color = TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (location.isNotBlank()) {
                Text(location, style = AppTypography.labelSmall, color = TextSecondary)
            }
            conflictLine?.let { line ->
                Text(line, style = AppTypography.labelSmall, color = StatusWorking)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProposalCard(
    proposal: PhoneCalendarProposal,
    deciding: Boolean,
    zone: ZoneId,
    nowMillis: Long,
    onReview: () -> Unit,
    onReject: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        shape = AppShapes.card,
        border = BorderStroke(1.dp, PrimaryIndigo.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
        ) {
            Text(
                text = proposal.title.ifBlank { "Propuesta de evento" },
                style = AppTypography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                color = TextPrimary,
            )
            Text(
                text = proposalWhenLabel(proposal, zone, nowMillis),
                style = AppTypography.bodySmall,
                color = PrimaryIndigo,
            )
            if (proposal.source.isNotBlank()) {
                Text(
                    text = "De: ${proposal.source}",
                    style = AppTypography.labelSmall,
                    color = TextSecondary,
                )
            }
            if (proposal.location.isNotBlank()) {
                Text(
                    text = proposal.location,
                    style = AppTypography.labelSmall,
                    color = TextSecondary,
                )
            }
            proposalConflictLine(proposal.conflicts, zone)?.let { line ->
                Text(line, style = AppTypography.labelSmall, color = StatusWorking)
            }
            if (deciding) {
                Text("Procesando…", style = AppTypography.labelSmall, color = TextSecondary)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
            ) {
                Button(
                    onClick = onReview,
                    enabled = !deciding,
                    shape = AppShapes.small,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryIndigo,
                        contentColor = Color.White,
                    ),
                    contentPadding = PaddingValues(
                        horizontal = AppSpacing.md,
                        vertical = AppSpacing.xs,
                    ),
                ) {
                    Text("Revisar y guardar", style = AppTypography.labelSmall)
                }
                OutlinedButton(
                    onClick = onReject,
                    enabled = !deciding,
                    shape = AppShapes.small,
                    border = BorderStroke(1.dp, SlateBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusError),
                    contentPadding = PaddingValues(
                        horizontal = AppSpacing.md,
                        vertical = AppSpacing.xs,
                    ),
                ) {
                    Text("Rechazar", style = AppTypography.labelSmall)
                }
            }
        }
    }
}
