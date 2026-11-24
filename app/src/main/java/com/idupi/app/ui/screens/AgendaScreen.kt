package com.idupi.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.idupi.app.data.calendar.CalendarOverlap
import com.idupi.app.data.calendar.CalendarPalette
import com.idupi.app.data.calendar.CalendarPermissions
import com.idupi.app.data.calendar.CalendarRange
import com.idupi.app.data.calendar.CalendarReporting
import com.idupi.app.data.calendar.CalendarViewMode
import com.idupi.app.data.calendar.CalendarWeekLayout
import com.idupi.app.domain.model.PhoneCalendarEvent
import com.idupi.app.domain.model.PhoneCalendarProposal
import com.idupi.app.domain.model.PhoneCalendarRef
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
import com.idupi.app.viewmodel.CalendarAgendaState
import com.idupi.app.viewmodel.CalendarViewModel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

/** Width of the hour-label column of the week grid. */
private val WeekTimeLabelWidth: Dp = 38.dp

/** One hour of the week grid, in dp. */
private val WeekHourHeight: Dp = 46.dp

/**
 * Agenda (phone-calendar T2/T3/T5): the owner's calendar with Lista, Semana and
 * Mes views, overlapping events flagged, and pending PC proposals at the top of
 * every mode. Proposals only become events after "Revisar y guardar" and an
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
    val today = remember(agenda.viewMode, agenda.visibleMonth, agenda.weekStart) {
        Instant.ofEpochMilli(System.currentTimeMillis()).atZone(zone).toLocalDate()
    }
    var detailEvent by remember { mutableStateOf<PhoneCalendarEvent?>(null) }

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

    val allEvents = remember(agenda.days, agenda.eventsByDay) {
        (agenda.days.flatMap { it.events } + agenda.eventsByDay.values.flatten())
            .distinctBy { it.id }
    }
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
            AgendaViewModeSelector(
                selected = agenda.viewMode,
                onSelect = viewModel::setViewMode,
            )
            if (agenda.isLoading) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = PrimaryIndigo)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
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

                    when (agenda.viewMode) {
                        CalendarViewMode.LIST -> {
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

                        CalendarViewMode.MONTH -> {
                            if (!agenda.hasCalendarPermission) {
                                item {
                                    EmptyAgenda(
                                        hasPermission = CalendarPermissions.hasRead(context),
                                    )
                                }
                            }
                            item(key = "month-grid") {
                                MonthGridCard(
                                    state = agenda,
                                    today = today,
                                    onPrevious = viewModel::previousMonth,
                                    onNext = viewModel::nextMonth,
                                    onToday = viewModel::goToday,
                                    onSelectDay = viewModel::selectDate,
                                )
                            }
                            val selectedDate = agenda.selectedDate ?: today
                            item(key = "selected-day") {
                                DayHeader(title = dayHeadingLabel(selectedDate, today))
                            }
                            val selectedEvents = agenda.eventsByDay[selectedDate].orEmpty()
                            if (selectedEvents.isEmpty()) {
                                item(key = "selected-day-empty") {
                                    Text(
                                        text = "No hay eventos este día.",
                                        style = AppTypography.bodySmall,
                                        color = TextSecondary,
                                    )
                                }
                            } else {
                                items(
                                    selectedEvents,
                                    key = { "month-event-${selectedDate}-${it.id}" },
                                ) { event ->
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

                        CalendarViewMode.WEEK -> {
                            if (!agenda.hasCalendarPermission) {
                                item {
                                    EmptyAgenda(
                                        hasPermission = CalendarPermissions.hasRead(context),
                                    )
                                }
                            }
                            item(key = "week-grid") {
                                WeekGridCard(
                                    state = agenda,
                                    today = today,
                                    zone = zone,
                                    onPrevious = viewModel::previousWeek,
                                    onNext = viewModel::nextWeek,
                                    onToday = viewModel::goToday,
                                    onEventClick = { detailEvent = it },
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

    detailEvent?.let { event ->
        WeekEventDetailsDialog(
            event = event,
            calendars = agenda.calendars,
            zone = zone,
            onDismiss = { detailEvent = null },
        )
    }
}

@Composable
private fun AgendaViewModeSelector(
    selected: CalendarViewMode,
    onSelect: (CalendarViewMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShapes.chip)
            .background(SlateCard)
            .padding(AppSpacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.xxs),
    ) {
        CalendarViewMode.entries.forEach { mode ->
            val isSelected = mode == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(AppShapes.small)
                    .background(if (isSelected) PrimaryIndigo else Color.Transparent)
                    .clickable { onSelect(mode) }
                    .padding(vertical = AppSpacing.sm),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = mode.label,
                    style = AppTypography.labelSmall,
                    color = if (isSelected) Color.White else TextSecondary,
                )
            }
        }
    }
}

/** Month grid: Monday-first, leading/trailing days dimmed, dots by calendar. */
@Composable
private fun MonthGridCard(
    state: CalendarAgendaState,
    today: LocalDate,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onSelectDay: (LocalDate) -> Unit,
) {
    val month = state.visibleMonth ?: YearMonth.from(today)
    val selectedDate = state.selectedDate ?: today
    val weekdayLabels = remember { weekdayShortLabels() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        shape = AppShapes.card,
    ) {
        Column(
            modifier = Modifier
                .padding(AppSpacing.cardPadding)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPrevious) {
                    Icon(Icons.Default.ChevronLeft, "Mes anterior", tint = TextPrimary)
                }
                Text(
                    text = calendarMonthTitle(month),
                    style = AppTypography.bodyLarge,
                    color = TextPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onNext) {
                    Icon(Icons.Default.ChevronRight, "Mes siguiente", tint = TextPrimary)
                }
                TextButton(onClick = onToday) {
                    Text("Hoy", style = AppTypography.labelSmall, color = PrimaryIndigo)
                }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                weekdayLabels.forEach { label ->
                    Text(
                        text = label,
                        style = AppTypography.labelSmall,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            state.monthWeeks.forEach { week ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    week.forEach { date ->
                        MonthDayCell(
                            date = date,
                            inMonth = YearMonth.from(date) == month,
                            isToday = date == today,
                            isSelected = date == selectedDate,
                            conflict = date in state.conflictDates,
                            events = state.eventsByDay[date].orEmpty(),
                            calendars = state.calendars,
                            onClick = onSelectDay,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.MonthDayCell(
    date: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    conflict: Boolean,
    events: List<PhoneCalendarEvent>,
    calendars: List<PhoneCalendarRef>,
    onClick: (LocalDate) -> Unit,
) {
    val border = when {
        conflict -> BorderStroke(1.dp, StatusError)
        isSelected -> BorderStroke(1.dp, PrimaryIndigo)
        else -> null
    }
    Box(
        modifier = Modifier
            .weight(1f)
            .padding(1.dp)
            .heightIn(min = 46.dp)
            .clip(AppShapes.small)
            .background(if (isSelected) PrimaryIndigo.copy(alpha = 0.18f) else Color.Transparent)
            .then(if (border != null) Modifier.border(border, AppShapes.small) else Modifier)
            .clickable { onClick(date) },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .padding(top = AppSpacing.xxs)
                    .size(20.dp)
                    .background(
                        if (isToday) PrimaryIndigo else Color.Transparent,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = date.dayOfMonth.toString(),
                    style = AppTypography.labelSmall,
                    color = when {
                        isToday -> Color.White
                        inMonth -> TextPrimary
                        else -> TextSecondary
                    },
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                events.take(MAX_MONTH_DOTS).forEach { event ->
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .background(calendarColor(calendars, event), CircleShape)
                    )
                }
                if (events.size > MAX_MONTH_DOTS) {
                    Text(
                        text = "+${events.size - MAX_MONTH_DOTS}",
                        style = AppTypography.labelSmall,
                        color = TextSecondary,
                    )
                }
            }
        }
    }
}

/** Week grid: Monday-first columns, 07:00–22:00 rows, all-day strip on top. */
@Composable
private fun WeekGridCard(
    state: CalendarAgendaState,
    today: LocalDate,
    zone: ZoneId,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onEventClick: (PhoneCalendarEvent) -> Unit,
) {
    val weekStart = state.weekStart ?: CalendarRange.weekStart(today)
    val days = state.weekDays.takeIf { it.size == DAYS_PER_WEEK }
        ?: CalendarRange.weekDates(weekStart)
    val weekdayLabels = remember { weekdayShortLabels() }
    val totalHours = CalendarWeekLayout.END_HOUR - CalendarWeekLayout.START_HOUR
    val hasAllDay = days.any { date ->
        state.eventsByDay[date].orEmpty().any { it.allDay }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        shape = AppShapes.card,
    ) {
        Column(
            modifier = Modifier
                .padding(AppSpacing.cardPadding)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPrevious) {
                    Icon(Icons.Default.ChevronLeft, "Semana anterior", tint = TextPrimary)
                }
                Text(
                    text = calendarWeekTitle(weekStart),
                    style = AppTypography.bodyLarge,
                    color = TextPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onNext) {
                    Icon(Icons.Default.ChevronRight, "Semana siguiente", tint = TextPrimary)
                }
                TextButton(onClick = onToday) {
                    Text("Hoy", style = AppTypography.labelSmall, color = PrimaryIndigo)
                }
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.width(WeekTimeLabelWidth))
                days.forEach { date ->
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = weekdayLabels[date.dayOfWeek.value - 1],
                            style = AppTypography.labelSmall,
                            color = TextSecondary,
                        )
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .background(
                                    if (date == today) PrimaryIndigo else Color.Transparent,
                                    CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = date.dayOfMonth.toString(),
                                style = AppTypography.labelSmall,
                                color = if (date == today) Color.White else TextPrimary,
                            )
                        }
                    }
                }
            }

            if (hasAllDay) {
                AllDayStrip(
                    days = days,
                    state = state,
                    onEventClick = onEventClick,
                )
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.width(WeekTimeLabelWidth)) {
                    repeat(totalHours) { index ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(WeekHourHeight),
                            contentAlignment = Alignment.TopEnd,
                        ) {
                            Text(
                                text = hourLabel(CalendarWeekLayout.START_HOUR + index),
                                style = AppTypography.labelSmall,
                                color = TextSecondary,
                                modifier = Modifier.padding(end = AppSpacing.xxs),
                            )
                        }
                    }
                }
                days.forEach { date ->
                    BoxWithConstraints(
                        modifier = Modifier
                            .weight(1f)
                            .height(WeekHourHeight * totalHours)
                            .border(0.5.dp, SlateBorder),
                    ) {
                        Column {
                            repeat(totalHours) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(WeekHourHeight)
                                        .border(0.5.dp, SlateBorder.copy(alpha = 0.35f))
                                )
                            }
                        }
                        state.weekBlocks.filter { it.date == date }.forEach { block ->
                            val top = WeekHourHeight * (block.topMinutes / MINUTES_PER_HOUR.toFloat())
                            val blockHeight = maxOf(
                                WeekHourHeight * (block.heightMinutes / MINUTES_PER_HOUR.toFloat()),
                                MIN_WEEK_BLOCK_HEIGHT,
                            )
                            val blockWidth = maxWidth / block.columnCount
                            Box(
                                modifier = Modifier
                                    .offset(x = blockWidth * block.column, y = top)
                                    .width(blockWidth)
                                    .height(blockHeight)
                                    .padding(horizontal = 1.dp)
                                    .clip(AppShapes.small)
                                    .background(calendarColor(state.calendars, block.event))
                                    .then(
                                        if (block.event.id in state.conflictIds) {
                                            Modifier.border(1.dp, StatusError, AppShapes.small)
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .clickable { onEventClick(block.event) }
                                    .padding(horizontal = 2.dp, vertical = 1.dp),
                            ) {
                                Text(
                                    text = block.event.title.ifBlank { "Evento" },
                                    style = AppTypography.labelSmall,
                                    color = Color.White,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AllDayStrip(
    days: List<LocalDate>,
    state: CalendarAgendaState,
    onEventClick: (PhoneCalendarEvent) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "Todo el día",
            style = AppTypography.labelSmall,
            color = TextSecondary,
            modifier = Modifier
                .width(WeekTimeLabelWidth)
                .padding(top = AppSpacing.xxs),
        )
        days.forEach { date ->
            val allDay = state.eventsByDay[date].orEmpty().filter { it.allDay }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 1.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                allDay.take(MAX_ALL_DAY_BARS).forEach { event ->
                    Text(
                        text = event.title.ifBlank { "Evento" },
                        style = AppTypography.labelSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(AppShapes.small)
                            .background(calendarColor(state.calendars, event))
                            .clickable { onEventClick(event) }
                            .padding(horizontal = 2.dp, vertical = 1.dp),
                    )
                }
                if (allDay.size > MAX_ALL_DAY_BARS) {
                    Text(
                        text = "+${allDay.size - MAX_ALL_DAY_BARS}",
                        style = AppTypography.labelSmall,
                        color = TextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun WeekEventDetailsDialog(
    event: PhoneCalendarEvent,
    calendars: List<PhoneCalendarRef>,
    zone: ZoneId,
    onDismiss: () -> Unit,
) {
    val calendarName = calendars.firstOrNull { it.id == event.calendarId }
        ?.name
        ?.takeIf { it.isNotBlank() }
        ?: "Sin calendario"
    val date = CalendarOverlap.parseInstant(event.start)?.let { millis ->
        val eventZone = if (event.allDay) ZoneOffset.UTC else zone
        Instant.ofEpochMilli(millis).atZone(eventZone).toLocalDate()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SlateCard,
        shape = AppShapes.dialog,
        title = {
            Text(
                text = event.title.ifBlank { "Evento sin título" },
                style = AppTypography.bodyLarge,
                color = TextPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xxs)) {
                date?.let {
                    Text(
                        text = shortDateLabel(it),
                        style = AppTypography.bodySmall,
                        color = TextPrimary,
                    )
                }
                Text(
                    text = timeRangeLabel(event.start, event.end, event.allDay, zone),
                    style = AppTypography.bodySmall,
                    color = PrimaryIndigo,
                )
                Text(
                    text = "Calendario: $calendarName",
                    style = AppTypography.bodySmall,
                    color = TextSecondary,
                )
                if (event.location.isNotBlank()) {
                    Text(
                        text = "Lugar: ${event.location}",
                        style = AppTypography.bodySmall,
                        color = TextSecondary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar", style = AppTypography.labelSmall, color = PrimaryIndigo)
            }
        },
    )
}

@Composable
private fun calendarColor(
    calendars: List<PhoneCalendarRef>,
    event: PhoneCalendarEvent,
): Color {
    val argb = CalendarPalette.argb(
        calendars.firstOrNull { it.id == event.calendarId }?.color
    )
    return if (argb != null) Color(argb) else PrimaryIndigo
}

private fun hourLabel(hour: Int): String = "${hour.toString().padStart(2, '0')}:00"

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

private const val MAX_MONTH_DOTS = 3
private const val MAX_ALL_DAY_BARS = 2
private const val DAYS_PER_WEEK = 7
private const val MINUTES_PER_HOUR = 60
private val MIN_WEEK_BLOCK_HEIGHT: Dp = 16.dp
