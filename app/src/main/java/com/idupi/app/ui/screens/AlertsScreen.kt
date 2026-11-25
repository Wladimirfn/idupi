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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.NotificationsActive
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.idupi.app.domain.model.InboxImportance
import com.idupi.app.domain.model.InboxItem
import com.idupi.app.domain.model.InboxItemState
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
import com.idupi.app.viewmodel.InboxFilter

/**
 * Alertas = the phone inbox (phone-inbox T5).
 *
 * The old supervisor-alert list read `/api/v1/alerts`, which is a stub that
 * always returns `[]`; only its test-notification action survived. Each card
 * shows the agent's analysis plus the actions the phone can actually perform
 * through the source notification's reply action. Polling is tied to this
 * screen's composition so leaving Alertas stops the 10 s requests.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen(
    viewModel: AlertsViewModel,
    onMenuClick: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val items by viewModel.items.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    val visibleItems = remember(items, filter) { AlertsViewModel.filterItems(items, filter) }
    val nowMillis = remember(items) { System.currentTimeMillis() }

    DisposableEffect(viewModel) {
        viewModel.startPolling()
        onDispose { viewModel.stopPolling() }
    }

    var editingItem by remember { mutableStateOf<InboxItem?>(null) }
    var editText by remember { mutableStateOf("") }

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
                    IconButton(onClick = { viewModel.triggerTestNotification(context) }) {
                        Icon(
                            Icons.Default.NotificationsActive,
                            contentDescription = "Probar Notificación",
                            tint = PrimaryIndigo
                        )
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
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
            FilterRow(selected = filter, onSelect = viewModel::setFilter)

            when {
                isLoading && items.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = PrimaryIndigo)
                    }
                }
                visibleItems.isEmpty() -> {
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
                        items(visibleItems, key = { it.id }) { item ->
                            InboxItemCard(
                                item = item,
                                nowMillis = nowMillis,
                                canReply = viewModel.canReply(item),
                                onSendSuggestion = { viewModel.sendSuggestion(item) },
                                onEdit = {
                                    editingItem = item
                                    editText = item.suggestedReply
                                },
                                onCopy = {
                                    clipboard.setText(AnnotatedString(item.suggestedReply))
                                    Toast.makeText(context, "Sugerencia copiada", Toast.LENGTH_SHORT)
                                        .show()
                                },
                                onHandled = { viewModel.markHandled(item) },
                                onDismiss = { viewModel.dismiss(item) },
                                onRetry = { viewModel.retryAnalysis(item) }
                            )
                        }
                    }
                }
            }
        }
    }

    editingItem?.let { item ->
        AlertDialog(
            onDismissRequest = { editingItem = null },
            title = {
                Text("Editar respuesta", color = TextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    minLines = 3,
                    placeholder = { Text("Escribí tu respuesta", color = TextSecondary) },
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
                    onClick = {
                        viewModel.sendEdited(item, editText)
                        editingItem = null
                    },
                    enabled = editText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryIndigo,
                        contentColor = Color.White
                    )
                ) {
                    Text("Enviar", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { editingItem = null }) {
                    Text("Cancelar", color = TextSecondary)
                }
            },
            containerColor = SlateCard
        )
    }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InboxItemCard(
    item: InboxItem,
    nowMillis: Long,
    canReply: Boolean,
    onSendSuggestion: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onHandled: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
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
                        text = inboxAppLabel(item.app),
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
                Text(
                    text = relativeTimeLabel(item, nowMillis),
                    style = AppTypography.bodySmall,
                    color = TextSecondary
                )
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
                inboxItemStateLabel(item.state)?.let { stateLabel ->
                    Text(stateLabel, style = AppTypography.labelSmall, color = StatusConnected)
                }
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
                }
                if (!canReply) {
                    Text(
                        text = "Respondé desde la app original",
                        style = AppTypography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
        }
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
    InboxFilter.ALL -> "No hay alertas"
    InboxFilter.IMPORTANT -> "No hay alertas importantes"
}

/** Beyond this many characters the original text gets "Ver más". */
private const val EXPAND_THRESHOLD = 160
