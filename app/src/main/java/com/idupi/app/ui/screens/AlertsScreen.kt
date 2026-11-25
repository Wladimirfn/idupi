package com.idupi.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.idupi.app.data.inbox.InboxCaptureSummary
import com.idupi.app.data.inbox.InboxConversationGroup
import com.idupi.app.data.inbox.InboxConversationGroups
import com.idupi.app.data.inbox.InboxNotifier
import com.idupi.app.data.calendar.CalendarEventBuilder
import com.idupi.app.domain.model.InboxDigest
import com.idupi.app.domain.model.InboxDigestStatus
import com.idupi.app.domain.model.InboxEvent
import com.idupi.app.domain.model.InboxEventConflict
import com.idupi.app.domain.model.InboxImportance
import com.idupi.app.domain.model.InboxInteraction
import com.idupi.app.domain.model.InboxInteractionStatus
import com.idupi.app.domain.model.InboxItem
import com.idupi.app.domain.model.InboxItemState
import com.idupi.app.domain.model.Project
import com.idupi.app.ui.components.AddEventSheet
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
import com.idupi.app.viewmodel.AlertsViewModel
import com.idupi.app.viewmodel.CalendarViewModel
import com.idupi.app.viewmodel.InboxFilter
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Alertas = the phone inbox (phone-inbox T5 + inbox-management T4).
 *
 * The old supervisor-alert list read `/api/v1/alerts`, which is a stub that
 * always returns `[]`. The list groups open items per conversation: one item
 * renders as the usual card, two or more render as one conversation card with
 * the PC digest, the questions pending for the owner and a suggested reply.
 * Each card menu can silence the chat inside IDUPI ("silence but read"): the
 * phone keeps capturing it, removes its notification from the shade and stops
 * reminding, while the conversation still shows up here. Promotions never
 * render as individual cards: one "Promociones" card at the end of Pendientes
 * holds every open offer with a single on-demand digest (alerts-flow A2).
 * Polling is tied to this screen's composition so leaving Alertas stops the
 * 10 s requests.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen(
    viewModel: AlertsViewModel,
    calendarViewModel: CalendarViewModel,
    onMenuClick: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val items by viewModel.items.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val digests by viewModel.digests.collectAsState()
    val requestingDigests by viewModel.requestingDigests.collectAsState()
    val silencedConversations by viewModel.silencedConversations.collectAsState()
    val projects by viewModel.projects.collectAsState()
    val addEvent by calendarViewModel.addEvent.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }

    val visibleItems = remember(items, filter) { AlertsViewModel.filterItems(items, filter) }
    val groups = remember(visibleItems) { InboxConversationGroups.group(visibleItems) }
    val nowMillis = remember(items) { System.currentTimeMillis() }

    DisposableEffect(viewModel) {
        // Opening Alertas consumes the capture summary (alerts-flow A1): the
        // owner is looking at the pile, so the silent notification goes away
        // and the next capture starts counting from zero.
        InboxCaptureSummary.reset()
        InboxNotifier.clearCaptureSummary(context)
        viewModel.startPolling()
        onDispose { viewModel.stopPolling() }
    }

    var editingItem by remember { mutableStateOf<InboxItem?>(null) }
    var editingGroup by remember { mutableStateOf<InboxConversationGroup?>(null) }
    var editText by remember { mutableStateOf("") }

    // Interactuar (alerts-flow A3): one sheet at a time, always for one item.
    var interactingItem by remember { mutableStateOf<InboxItem?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Alertas", style = AppTypography.appBarTitle, color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(Icons.Default.Menu, contentDescription = "Menú", tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Actualizar", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SlateCard)
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                androidx.compose.material3.Snackbar(
                    containerColor = SlateCard,
                    contentColor = TextPrimary,
                    shape = AppShapes.card
                ) {
                    Text(data.visuals.message, color = TextPrimary)
                }
            }
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
            FilterRow(selected = filter, onSelect = viewModel::setFilter)

            when {
                isLoading && items.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = PrimaryIndigo)
                    }
                }
                groups.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = emptyMessage(filter),
                            color = TextSecondary,
                            style = AppTypography.bodySmall
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
                        contentPadding = PaddingValues(bottom = AppSpacing.xl)
                    ) {
                        items(groups, key = { it.conversationKey }) { group ->
                            val silenced = group.conversationKey in silencedConversations
                            val onToggleSilence = {
                                viewModel.setConversationSilenced(
                                    group.conversationKey,
                                    !silenced
                                )
                            }
                            if (group.conversationKey == InboxConversationGroups.PROMOTIONS_KEY) {
                                PromotionsCard(
                                    group = group,
                                    digest = digests[InboxConversationGroups.PROMOTIONS_KEY],
                                    isRequesting = InboxConversationGroups.PROMOTIONS_KEY in
                                        requestingDigests,
                                    nowMillis = nowMillis,
                                    onRequestDigest = { viewModel.requestPromotionsDigest() },
                                    onMarkAllHandled = { viewModel.markPromotionsHandled(group) }
                                )
                            } else if (group.count == 1) {
                                val item = group.newestItem
                                InboxItemCard(
                                    item = item,
                                    nowMillis = nowMillis,
                                    canReply = viewModel.canReply(item),
                                    silenced = silenced,
                                    zone = zone,
                                    onToggleSilence = onToggleSilence,
                                    onSendSuggestion = { viewModel.sendSuggestion(item) },
                                    onEdit = {
                                        editingItem = item
                                        editText = item.suggestedReply
                                    },
                                    onCopy = {
                                        clipboard.setText(AnnotatedString(item.suggestedReply))
                                        Toast.makeText(
                                            context,
                                            "Sugerencia copiada",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    onHandled = { viewModel.markHandled(item) },
                                    onDismiss = { viewModel.dismiss(item) },
                                    onRetry = { viewModel.retryAnalysis(item) },
                                    onAddToCalendar = {
                                        item.event?.let { event ->
                                            calendarViewModel.openAddEvent(
                                                CalendarEventBuilder.prefillFor(event)
                                            )
                                        }
                                    },
                                    onInteract = {
                                        interactingItem = item
                                        viewModel.refreshProjects()
                                    },
                                    onRetryInteraction = { viewModel.retryInteraction(item) },
                                    onCopyInteraction = { answer ->
                                        clipboard.setText(AnnotatedString(answer))
                                        Toast.makeText(
                                            context,
                                            "Respuesta copiada",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                )
                            } else {
                                val digest = digests[group.conversationKey]
                                ConversationCard(
                                    group = group,
                                    digest = digest,
                                    isRequesting = group.conversationKey in requestingDigests,
                                    canReply = viewModel.canReply(group),
                                    silenced = silenced,
                                    nowMillis = nowMillis,
                                    zone = zone,
                                    onToggleSilence = onToggleSilence,
                                    onRequestDigest = { viewModel.requestDigest(group) },
                                    onSendSuggestion = { viewModel.sendGroupSuggestion(group) },
                                    onEdit = {
                                        editingGroup = group
                                        editText = digest?.suggestedReply.orEmpty()
                                    },
                                    onCopy = {
                                        clipboard.setText(
                                            AnnotatedString(digest?.suggestedReply.orEmpty())
                                        )
                                        Toast.makeText(
                                            context,
                                            "Sugerencia copiada",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    onMarkAllHandled = { viewModel.markGroupHandled(group) },
                                    onAddToCalendar = { item ->
                                        item.event?.let { event ->
                                            calendarViewModel.openAddEvent(
                                                CalendarEventBuilder.prefillFor(event)
                                            )
                                        }
                                    },
                                    onInteract = {
                                        interactingItem = group.newestItem
                                        viewModel.refreshProjects()
                                    },
                                    onRetryInteraction = {
                                        viewModel.retryInteraction(group.newestItem)
                                    },
                                    onCopyInteraction = { answer ->
                                        clipboard.setText(AnnotatedString(answer))
                                        Toast.makeText(
                                            context,
                                            "Respuesta copiada",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    editingItem?.let { item ->
        EditReplyDialog(
            text = editText,
            onTextChange = { editText = it },
            onConfirm = {
                viewModel.sendEdited(item, editText)
                editingItem = null
            },
            onDismiss = { editingItem = null }
        )
    }

    editingGroup?.let { group ->
        EditReplyDialog(
            text = editText,
            onTextChange = { editText = it },
            onConfirm = {
                viewModel.sendGroupEdited(group, editText)
                editingGroup = null
            },
            onDismiss = { editingGroup = null }
        )
    }

    AddEventSheet(
        state = addEvent,
        onDraftChange = calendarViewModel::updateDraft,
        onSave = {
            calendarViewModel.saveEvent { saved ->
                scope.launch {
                    snackbarHostState.showSnackbar("Evento guardado en ${saved.calendarName}")
                }
            }
        },
        onDismiss = calendarViewModel::closeAddEvent,
        zone = zone
    )

    interactingItem?.let { item ->
        AgentInteractionSheet(
            projects = projects,
            onSend = { instruction, projectId ->
                viewModel.interact(item.id, instruction, projectId)
                interactingItem = null
            },
            onDismiss = { interactingItem = null }
        )
    }
}

/**
 * Interactuar sheet (alerts-flow A3): one owner instruction plus an optional
 * linked project. The agent works read-only in that project and the answer
 * lands on the card, so nothing leaves the phone from here except the request.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentInteractionSheet(
    projects: List<Project>,
    onSend: (instruction: String, projectId: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var instruction by rememberSaveable { mutableStateOf("") }
    var selectedProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var dropdownExpanded by remember { mutableStateOf(false) }
    val selectedProject = projects.firstOrNull { it.id == selectedProjectId }

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
                text = "Interactuar",
                style = AppTypography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                color = TextPrimary,
            )
            Text(
                text = "El agente revisa la alerta y te responde aquí. Solo lee: no escribe archivos ni ejecuta comandos.",
                style = AppTypography.bodySmall,
                color = TextSecondary,
            )

            OutlinedTextField(
                value = instruction,
                onValueChange = { instruction = it },
                label = { Text("¿Qué quieres que haga el agente?", style = AppTypography.bodySmall) },
                placeholder = {
                    Text(
                        "Ej.: revisa si esto afecta mis proyectos",
                        style = AppTypography.bodySmall,
                        color = TextSecondary,
                    )
                },
                minLines = 3,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = PrimaryIndigo,
                    unfocusedBorderColor = SlateBorder,
                    focusedContainerColor = SlateBg,
                    unfocusedContainerColor = SlateBg,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { dropdownExpanded = true },
                    border = BorderStroke(1.dp, SlateBorder),
                    shape = AppShapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Proyecto (opcional): ${selectedProject?.name ?: "Ninguno"}",
                        style = AppTypography.bodySmall,
                        color = TextPrimary,
                    )
                }
                DropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { dropdownExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Ninguno", style = AppTypography.bodySmall, color = TextPrimary) },
                        onClick = {
                            dropdownExpanded = false
                            selectedProjectId = null
                        }
                    )
                    projects.forEach { project ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = project.name,
                                    style = AppTypography.bodySmall,
                                    color = TextPrimary,
                                )
                            },
                            onClick = {
                                dropdownExpanded = false
                                selectedProjectId = project.id
                            }
                        )
                    }
                }
            }

            Button(
                onClick = { onSend(instruction, selectedProjectId) },
                enabled = instruction.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryIndigo,
                    contentColor = Color.White,
                ),
                shape = AppShapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Enviar al agente", fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * The agent's answer block on a card (alerts-flow A3): progress while running,
 * the answer with "Copiar" when done, a short error with "Reintentar" when the
 * job failed.
 */
@Composable
private fun AgentInteractionSection(
    interaction: InboxInteraction,
    onRetry: () -> Unit,
    onCopy: () -> Unit,
) {
    when (interaction.status) {
        InboxInteractionStatus.RUNNING -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
            ) {
                CircularProgressIndicator(
                    color = PrimaryIndigo,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "El agente está revisando…",
                    style = AppTypography.bodySmall,
                    color = TextSecondary,
                )
            }
        }
        InboxInteractionStatus.DONE -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(AppShapes.small)
                    .background(SlateBg)
                    .border(1.dp, SlateBorder, AppShapes.small)
                    .padding(AppSpacing.md),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
            ) {
                Text(
                    text = interactionHeader(interaction),
                    style = AppTypography.labelSmall,
                    color = PrimaryIndigo,
                )
                SelectionContainer {
                    Text(
                        text = interaction.answer.ifBlank { "El agente no devolvió una respuesta." },
                        style = AppTypography.bodySmall,
                        color = TextPrimary,
                    )
                }
                if (interaction.answer.isNotBlank()) {
                    TextButton(
                        onClick = onCopy,
                        contentPadding = PaddingValues(horizontal = AppSpacing.xs),
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text("Copiar", style = AppTypography.labelSmall, color = PrimaryIndigo)
                    }
                }
            }
        }
        InboxInteractionStatus.FAILED -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
            ) {
                Text(
                    text = interaction.error?.takeIf { it.isNotBlank() }
                        ?: "No se pudo completar la interacción",
                    style = AppTypography.bodySmall,
                    color = StatusError,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRetry) {
                    Text("Reintentar", style = AppTypography.labelSmall, color = PrimaryIndigo)
                }
            }
        }
    }
}

@Composable
private fun EditReplyDialog(
    text: String,
    onTextChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Editar respuesta", color = TextPrimary, fontWeight = FontWeight.Bold)
        },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                minLines = 3,
                placeholder = { Text("Escribe tu respuesta", color = TextSecondary) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = PrimaryIndigo,
                    unfocusedBorderColor = SlateBorder,
                    focusedContainerColor = SlateBg,
                    unfocusedContainerColor = SlateBg
                ),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = text.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryIndigo,
                    contentColor = Color.White
                )
            ) {
                Text("Enviar", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", color = TextSecondary)
            }
        },
        containerColor = SlateCard
    )
}

@Composable
private fun FilterRow(
    selected: InboxFilter,
    onSelect: (InboxFilter) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
    ) {
        InboxFilter.entries.forEach { filter ->
            val isSelected = filter == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) PrimaryIndigo else SlateCard)
                    .border(
                        1.dp,
                        if (isSelected) PrimaryIndigo else SlateBorder,
                        RoundedCornerShape(8.dp)
                    )
                    .clickable { onSelect(filter) }
                    .padding(vertical = AppSpacing.sm),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = filter.label,
                    color = if (isSelected) Color.White else TextSecondary,
                    style = AppTypography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Per-conversation menu (inbox-management T4): "silence but read" removes the
 * source notifications while IDUPI keeps the chat in Alertas.
 */
@Composable
private fun ConversationMenu(
    silenced: Boolean,
    onToggleSilence: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = "Opciones del chat",
                tint = TextSecondary
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        text = if (silenced) {
                            "Dejar de silenciar"
                        } else {
                            "Silenciar en el teléfono (seguir leyendo)"
                        },
                        style = AppTypography.bodySmall,
                        color = TextPrimary
                    )
                },
                onClick = {
                    expanded = false
                    onToggleSilence()
                }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InboxItemCard(
    item: InboxItem,
    nowMillis: Long,
    canReply: Boolean,
    silenced: Boolean,
    zone: ZoneId,
    onToggleSilence: () -> Unit,
    onSendSuggestion: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onHandled: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onAddToCalendar: () -> Unit,
    onInteract: () -> Unit,
    onRetryInteraction: () -> Unit,
    onCopyInteraction: (String) -> Unit,
) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    val isOpen = item.state == InboxItemState.OPEN
    val isHigh = item.importance == InboxImportance.HIGH

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        shape = AppShapes.card,
        border = BorderStroke(
            width = if (isOpen && isHigh) 1.5.dp else 1.dp,
            color = if (isOpen && isHigh) StatusError else SlateBorder
        )
    ) {
        Column(
            modifier = Modifier
                .padding(AppSpacing.cardPadding)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                ) {
                    Text(
                        text = alertAppLabel(item.appLabel, item.app),
                        style = AppTypography.labelSmall,
                        color = PrimaryIndigo
                    )
                    if (item.category.isNotBlank()) {
                        Text(
                            text = "· ${item.category}",
                            style = AppTypography.labelSmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = relativeTimeLabel(item, nowMillis),
                        style = AppTypography.bodySmall,
                        color = TextSecondary
                    )
                    ConversationMenu(silenced = silenced, onToggleSilence = onToggleSilence)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                Text(
                    text = item.sender.ifBlank { "Sin remitente" },
                    style = AppTypography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                ImportanceChip(item.importance)
            }

            when {
                isInboxItemAnalyzing(item) -> {
                    Text("Analizando…", style = AppTypography.bodySmall, color = TextSecondary)
                }
                isInboxItemFailed(item) -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                    ) {
                        Text(
                            text = item.error?.takeIf { it.isNotBlank() }
                                ?: "No se pudo analizar el mensaje",
                            style = AppTypography.bodySmall,
                            color = StatusError,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onRetry) {
                            Text("Reintentar", style = AppTypography.labelSmall, color = PrimaryIndigo)
                        }
                    }
                }
                item.summary.isNotBlank() -> {
                    Text(item.summary, style = AppTypography.bodySmall, color = TextPrimary)
                }
            }

            if (item.text.isNotBlank()) {
                Text(
                    text = item.text,
                    style = AppTypography.bodySmall,
                    color = TextSecondary,
                    maxLines = if (expanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.text.length > EXPAND_THRESHOLD) {
                    TextButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.align(Alignment.End),
                        contentPadding = PaddingValues(horizontal = AppSpacing.xs)
                    ) {
                        Text(
                            text = if (expanded) "Ver menos" else "Ver más",
                            style = AppTypography.labelSmall,
                            color = PrimaryIndigo
                        )
                    }
                }
            }

            if (item.suggestedReply.isNotBlank()) {
                SuggestedReplyBox(item.suggestedReply)
            }

            item.event?.let { event ->
                AlertEventSection(
                    event = event,
                    conflicts = item.eventConflicts,
                    zone = zone,
                    onAdd = onAddToCalendar
                )
            }

            item.interaction?.let { interaction ->
                AgentInteractionSection(
                    interaction = interaction,
                    onRetry = onRetryInteraction,
                    onCopy = { onCopyInteraction(interaction.answer) }
                )
            }

            if (isOpen) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                ) {
                    if (canReply) {
                        if (item.suggestedReply.isNotBlank()) {
                            Button(
                                onClick = onSendSuggestion,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = PrimaryIndigo,
                                    contentColor = Color.White
                                ),
                                shape = AppShapes.small,
                                contentPadding = PaddingValues(
                                    horizontal = AppSpacing.md,
                                    vertical = AppSpacing.xs
                                )
                            ) {
                                Text("Enviar sugerencia", style = AppTypography.labelSmall)
                            }
                        }
                        CompactOutlinedButton(
                            label = "Editar y enviar",
                            onClick = onEdit,
                            contentColor = PrimaryIndigo
                        )
                    }
                    if (item.suggestedReply.isNotBlank()) {
                        CompactOutlinedButton(
                            label = "Copiar",
                            onClick = onCopy,
                            contentColor = TextPrimary
                        )
                    }
                    CompactOutlinedButton(
                        label = "Atendida",
                        onClick = onHandled,
                        contentColor = StatusConnected
                    )
                    CompactOutlinedButton(
                        label = "Descartar",
                        onClick = onDismiss,
                        contentColor = StatusError
                    )
                    CompactOutlinedButton(
                        label = "Interactuar",
                        onClick = onInteract,
                        contentColor = PrimaryIndigo
                    )
                }
                if (!canReply) {
                    Text(
                        text = "Responde desde la app original",
                        style = AppTypography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

/**
 * One chat with two or more unseen messages (inbox-management T4): a single
 * card with the conversation digest, the pending questions, the suggested
 * reply, the expandable message list and the bulk close action.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConversationCard(
    group: InboxConversationGroup,
    digest: InboxDigest?,
    isRequesting: Boolean,
    canReply: Boolean,
    silenced: Boolean,
    nowMillis: Long,
    zone: ZoneId,
    onToggleSilence: () -> Unit,
    onRequestDigest: () -> Unit,
    onSendSuggestion: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onMarkAllHandled: () -> Unit,
    onAddToCalendar: (InboxItem) -> Unit,
    onInteract: () -> Unit,
    onRetryInteraction: () -> Unit,
    onCopyInteraction: (String) -> Unit,
) {
    var expanded by rememberSaveable(group.conversationKey) { mutableStateOf(false) }
    val analyzing = isRequesting || digest?.status == InboxDigestStatus.ANALYZING
    val failed = digest?.status == InboxDigestStatus.FAILED
    val summary = digest?.summary?.trim().orEmpty()
    val pending = digest?.pendingForOwner.orEmpty()
    val suggestedReply = digest?.suggestedReply?.trim().orEmpty()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        shape = AppShapes.card,
        border = BorderStroke(1.dp, SlateBorder)
    ) {
        Column(
            modifier = Modifier
                .padding(AppSpacing.cardPadding)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = alertAppLabel(group.appLabel, group.app),
                        style = AppTypography.labelSmall,
                        color = PrimaryIndigo
                    )
                    Text(
                        text = group.conversation.ifBlank { "Sin conversación" },
                        style = AppTypography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = relativeTimeLabel(group.newestAtMillis, nowMillis),
                        style = AppTypography.bodySmall,
                        color = TextSecondary
                    )
                    ConversationMenu(silenced = silenced, onToggleSilence = onToggleSilence)
                }
            }

            Text(
                text = "${group.count} mensajes sin ver",
                style = AppTypography.labelSmall,
                color = StatusWorking
            )

            when {
                analyzing -> {
                    Text("Resumiendo…", style = AppTypography.bodySmall, color = TextSecondary)
                }
                failed -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                    ) {
                        Text(
                            text = digest?.error?.takeIf { it.isNotBlank() }
                                ?: "No se pudo resumir la conversación",
                            style = AppTypography.bodySmall,
                            color = StatusError,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onRequestDigest) {
                            Text(
                                "Reintentar",
                                style = AppTypography.labelSmall,
                                color = PrimaryIndigo
                            )
                        }
                    }
                }
                summary.isNotBlank() -> {
                    Text(summary, style = AppTypography.bodySmall, color = TextPrimary)
                }
            }

            if (pending.isNotEmpty()) {
                Text(
                    text = "Pendiente para ti",
                    style = AppTypography.labelSmall,
                    color = TextSecondary
                )
                pending.forEach { entry ->
                    Text(
                        text = "• $entry",
                        style = AppTypography.bodySmall,
                        color = TextPrimary
                    )
                }
            }

            if (summary.isBlank() && !analyzing && !failed) {
                TextButton(onClick = onRequestDigest) {
                    Text("Resumir", style = AppTypography.labelSmall, color = PrimaryIndigo)
                }
            }

            val eventItems = group.items.filter { it.event != null }
            if (eventItems.isNotEmpty()) {
                Text(
                    text = "Eventos detectados",
                    style = AppTypography.labelSmall,
                    color = TextSecondary
                )
                eventItems.forEach { item ->
                    item.event?.let { event ->
                        AlertEventSection(
                            event = event,
                            conflicts = item.eventConflicts,
                            zone = zone,
                            onAdd = { onAddToCalendar(item) }
                        )
                    }
                }
            }

            if (suggestedReply.isNotBlank()) {
                SuggestedReplyBox(suggestedReply)
            }

            group.newestItem.interaction?.let { interaction ->
                AgentInteractionSection(
                    interaction = interaction,
                    onRetry = onRetryInteraction,
                    onCopy = { onCopyInteraction(interaction.answer) }
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
            ) {
                if (canReply && suggestedReply.isNotBlank()) {
                    Button(
                        onClick = onSendSuggestion,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryIndigo,
                            contentColor = Color.White
                        ),
                        shape = AppShapes.small,
                        contentPadding = PaddingValues(
                            horizontal = AppSpacing.md,
                            vertical = AppSpacing.xs
                        )
                    ) {
                        Text("Enviar sugerencia", style = AppTypography.labelSmall)
                    }
                }
                if (canReply) {
                    CompactOutlinedButton(
                        label = "Editar y enviar",
                        onClick = onEdit,
                        contentColor = PrimaryIndigo
                    )
                }
                if (suggestedReply.isNotBlank()) {
                    CompactOutlinedButton(
                        label = "Copiar",
                        onClick = onCopy,
                        contentColor = TextPrimary
                    )
                }
                CompactOutlinedButton(
                    label = "Marcar todo atendido",
                    onClick = onMarkAllHandled,
                    contentColor = StatusConnected
                )
                CompactOutlinedButton(
                    label = "Interactuar",
                    onClick = onInteract,
                    contentColor = PrimaryIndigo
                )
            }
            if (!canReply) {
                Text(
                    text = "Responde desde la app original",
                    style = AppTypography.labelSmall,
                    color = TextSecondary
                )
            }

            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(horizontal = AppSpacing.xs)
            ) {
                Text(
                    text = if (expanded) "Ocultar mensajes" else "Ver mensajes",
                    style = AppTypography.labelSmall,
                    color = PrimaryIndigo
                )
            }

            if (expanded) {
                group.items.forEach { item ->
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.xxs)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = item.sender.ifBlank { "Sin remitente" },
                                style = AppTypography.labelSmall,
                                color = TextPrimary,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = relativeTimeLabel(item, nowMillis),
                                style = AppTypography.labelSmall,
                                color = TextSecondary
                            )
                        }
                        Text(
                            text = item.text,
                            style = AppTypography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}

/**
 * One card for every open promotion (alerts-flow A2): count line, source app
 * chips, one on-demand digest with its bullet summary, the expandable list and
 * the bulk close. It has no reply buttons: promotions are read, never answered.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PromotionsCard(
    group: InboxConversationGroup,
    digest: InboxDigest?,
    isRequesting: Boolean,
    nowMillis: Long,
    onRequestDigest: () -> Unit,
    onMarkAllHandled: () -> Unit,
) {
    var expanded by rememberSaveable(InboxConversationGroups.PROMOTIONS_KEY) {
        mutableStateOf(false)
    }
    val analyzing = isRequesting || digest?.status == InboxDigestStatus.ANALYZING
    val failed = digest?.status == InboxDigestStatus.FAILED
    val summary = digest?.summary?.trim().orEmpty()
    val flags = digest?.pendingForOwner.orEmpty()
    // One chip per source app, named the way the owner knows it.
    val appLabels = group.items
        .distinctBy { it.app }
        .map { alertAppLabel(it.appLabel, it.app) }
    val stale = digest == null || digest.itemCount < group.count

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        shape = AppShapes.card,
        border = BorderStroke(1.dp, SlateBorder)
    ) {
        Column(
            modifier = Modifier
                .padding(AppSpacing.cardPadding)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            Column {
                Text(
                    text = "Promociones",
                    style = AppTypography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary
                )
                Text(
                    text = promotionsCountLabel(group.count, appLabels.size),
                    style = AppTypography.labelSmall,
                    color = StatusWorking
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.xxs)
            ) {
                appLabels.forEach { label ->
                    Box(
                        modifier = Modifier
                            .clip(AppShapes.pill)
                            .background(SlateBg)
                            .border(1.dp, SlateBorder, AppShapes.pill)
                            .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xxs)
                    ) {
                        Text(
                            text = label,
                            style = AppTypography.labelSmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            when {
                analyzing -> {
                    Text("Resumiendo…", style = AppTypography.bodySmall, color = TextSecondary)
                }
                failed -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                    ) {
                        Text(
                            text = digest?.error?.takeIf { it.isNotBlank() }
                                ?: "No se pudo resumir las promociones",
                            style = AppTypography.bodySmall,
                            color = StatusError,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onRequestDigest) {
                            Text(
                                "Reintentar",
                                style = AppTypography.labelSmall,
                                color = PrimaryIndigo
                            )
                        }
                    }
                }
                summary.isNotBlank() -> {
                    Text(summary, style = AppTypography.bodySmall, color = TextPrimary)
                }
            }

            if (flags.isNotEmpty()) {
                Text(
                    text = "Revisa:",
                    style = AppTypography.labelSmall,
                    color = TextSecondary
                )
                flags.forEach { entry ->
                    Text(
                        text = "• $entry",
                        style = AppTypography.bodySmall,
                        color = TextPrimary
                    )
                }
            }

            if (!analyzing && !failed && (summary.isBlank() || stale)) {
                TextButton(onClick = onRequestDigest) {
                    Text("Resumir", style = AppTypography.labelSmall, color = PrimaryIndigo)
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
            ) {
                CompactOutlinedButton(
                    label = if (expanded) "Ocultar" else "Ver todas",
                    onClick = { expanded = !expanded },
                    contentColor = PrimaryIndigo
                )
                CompactOutlinedButton(
                    label = "Marcar todo revisado",
                    onClick = onMarkAllHandled,
                    contentColor = StatusConnected
                )
            }

            if (expanded) {
                group.items.forEach { item ->
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.xxs)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = alertAppLabel(item.appLabel, item.app),
                                style = AppTypography.labelSmall,
                                color = PrimaryIndigo,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = relativeTimeLabel(item, nowMillis),
                                style = AppTypography.labelSmall,
                                color = TextSecondary
                            )
                        }
                        Text(
                            text = item.summary.ifBlank { item.text },
                            style = AppTypography.bodySmall,
                            color = TextSecondary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/**
 * Alert event chip (phone-calendar T3): the extracted event, its conflict
 * warning and the only path to a write. Nothing is saved from here: the tap
 * opens the confirm sheet prefilled.
 */
@Composable
private fun AlertEventSection(
    event: InboxEvent,
    conflicts: List<InboxEventConflict>,
    zone: ZoneId,
    onAdd: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
    ) {
        Box(
            modifier = Modifier
                .clip(AppShapes.pill)
                .background(PrimaryIndigo.copy(alpha = 0.12f))
                .padding(horizontal = AppSpacing.md, vertical = AppSpacing.xxs)
        ) {
            Text(
                text = inboxEventChipLabel(event, zone),
                style = AppTypography.labelSmall,
                color = PrimaryIndigo
            )
        }
        inboxConflictLine(conflicts, zone)?.let { line ->
            Text(line, style = AppTypography.labelSmall, color = StatusWorking)
        }
        CompactOutlinedButton(
            label = "Agregar al calendario",
            onClick = onAdd,
            contentColor = PrimaryIndigo
        )
    }
}

@Composable
private fun SuggestedReplyBox(suggestedReply: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShapes.small)
            .background(SlateBg)
            .border(1.dp, SlateBorder, AppShapes.small)
            .padding(AppSpacing.md),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
    ) {
        Text("Respuesta sugerida", style = AppTypography.labelSmall, color = TextSecondary)
        Text(suggestedReply, style = AppTypography.bodySmall, color = TextPrimary)
    }
}

@Composable
private fun CompactOutlinedButton(
    label: String,
    onClick: () -> Unit,
    contentColor: Color
) {
    OutlinedButton(
        onClick = onClick,
        shape = AppShapes.small,
        border = BorderStroke(1.dp, SlateBorder),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor),
        contentPadding = PaddingValues(horizontal = AppSpacing.md, vertical = AppSpacing.xs)
    ) {
        Text(label, style = AppTypography.labelSmall)
    }
}

@Composable
private fun ImportanceChip(importance: String?) {
    val label = importanceLabel(importance) ?: return
    val color = when (importance) {
        InboxImportance.HIGH -> StatusError
        InboxImportance.MEDIUM -> StatusWorking
        else -> StatusConnected
    }
    Box(
        modifier = Modifier
            .clip(AppShapes.pill)
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xxs)
    ) {
        Text(label, style = AppTypography.labelSmall, color = color)
    }
}

private fun emptyMessage(filter: InboxFilter): String = when (filter) {
    InboxFilter.PENDING -> "No hay alertas pendientes"
    InboxFilter.IMPORTANT -> "No hay alertas importantes"
}

/** Beyond this many characters the original text gets "Ver más". */
private const val EXPAND_THRESHOLD = 160
