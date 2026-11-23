package com.idupi.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.idupi.app.domain.model.OsProcess
import com.idupi.app.domain.model.formatProcessCpu
import com.idupi.app.domain.model.formatProcessMemory
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
import com.idupi.app.viewmodel.ConsoleViewModel
import com.idupi.app.viewmodel.ProcessCategoryFilter
import com.idupi.app.viewmodel.ProcessSort

/**
 * "Procesos": every process on the PC (T10), not only the shells the server
 * manages. The interactive shells live in Terminales; this screen is Task
 * Manager with a phone layout: category chips with live counts, search, sort,
 * and an end-process action with confirmation.
 *
 * Polling follows the screen lifecycle; the last good list stays visible when
 * a poll fails, and the error state only appears when nothing was ever loaded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleScreen(
    viewModel: ConsoleViewModel,
    onMenuClick: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Same lifecycle contract as the Dashboard: the screen is composed while
    // the activity is already RESUMED, so ON_START will not fire again. Start
    // here, stop on ON_STOP and on dispose (navigation away).
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.startPolling()
                Lifecycle.Event.ON_STOP -> viewModel.stopPolling()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        viewModel.startPolling()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopPolling()
        }
    }

    var detailProcess by remember { mutableStateOf<OsProcess?>(null) }
    var confirmKill by remember { mutableStateOf<OsProcess?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Procesos", style = AppTypography.appBarTitle, color = TextPrimary)
                        Text(
                            text = "${state.processes.size} en tu PC · cada 3 s",
                            style = AppTypography.labelSmall,
                            color = TextSecondary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(Icons.Default.Menu, contentDescription = "Menú", tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshNow() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refrescar", tint = TextPrimary)
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
                .padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            SearchField(query = state.query, onQueryChange = viewModel::setQuery)
            CategoryChips(
                state = state,
                onSelect = viewModel::setCategory
            )
            SortRow(selected = state.sort, onSelect = viewModel::setSort)

            if (state.refreshFailed && state.processes.isNotEmpty()) {
                Text(
                    text = "No se pudo actualizar: se muestran los últimos procesos.",
                    style = AppTypography.bodySmall,
                    color = StatusWorking
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.isLoading && state.processes.isEmpty() -> {
                        CircularProgressIndicator(
                            color = PrimaryIndigo,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    state.processes.isEmpty() && state.errorMessage != null -> {
                        ErrorState(
                            message = state.errorMessage.orEmpty(),
                            onRetry = { viewModel.refreshNow() }
                        )
                    }
                    state.filteredProcesses.isEmpty() -> {
                        EmptyState(category = state.category, hasQuery = state.query.isNotBlank())
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = AppSpacing.xs),
                            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                        ) {
                            items(state.filteredProcesses, key = { it.pid }) { process ->
                                ProcessRow(
                                    process = process,
                                    onClick = { detailProcess = process }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    detailProcess?.let { process ->
        ProcessDetailSheet(
            process = process,
            isKilling = state.killingPid == process.pid,
            onDismiss = { detailProcess = null },
            onRequestKill = {
                detailProcess = null
                confirmKill = process
            }
        )
    }

    confirmKill?.let { process ->
        KillProcessDialog(
            process = process,
            onDismiss = { confirmKill = null },
            onConfirm = {
                confirmKill = null
                viewModel.killProcess(process.pid)
            }
        )
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit
) {
    val focusManager = LocalFocusManager.current
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.input,
        singleLine = true,
        placeholder = {
            Text(
                text = "Buscar por nombre o ventana",
                style = AppTypography.bodySmall,
                color = TextSecondary
            )
        },
        leadingIcon = {
            Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary)
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = "Limpiar búsqueda", tint = TextSecondary)
                }
            }
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = SlateCard,
            unfocusedContainerColor = SlateCard,
            focusedIndicatorColor = PrimaryIndigo,
            unfocusedIndicatorColor = SlateBorder,
            disabledIndicatorColor = SlateBorder,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            cursorColor = PrimaryIndigo
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
    )
}

@Composable
private fun CategoryChips(
    state: ConsoleViewModel.UiState,
    onSelect: (ProcessCategoryFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
    ) {
        ProcessCategoryFilter.entries.forEach { filter ->
            val selected = state.category == filter
            FilterChip(
                selected = selected,
                onClick = { onSelect(filter) },
                label = {
                    Text(
                        text = "${filter.label} (${state.countFor(filter)})",
                        style = AppTypography.labelSmall,
                        maxLines = 1
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = PrimaryIndigo.copy(alpha = 0.15f),
                    selectedLabelColor = PrimaryIndigo,
                    labelColor = TextSecondary
                )
            )
        }
    }
}

@Composable
private fun SortRow(
    selected: ProcessSort,
    onSelect: (ProcessSort) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
    ) {
        Text("Ordenar", style = AppTypography.bodySmall, color = TextSecondary)
        ProcessSort.entries.forEach { sort ->
            FilterChip(
                selected = selected == sort,
                onClick = { onSelect(sort) },
                label = { Text(sort.label, style = AppTypography.labelSmall, maxLines = 1) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = PrimaryIndigo.copy(alpha = 0.15f),
                    selectedLabelColor = PrimaryIndigo,
                    labelColor = TextSecondary
                )
            )
        }
    }
}

@Composable
private fun ProcessRow(
    process: OsProcess,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.card,
        color = SlateCard,
        border = BorderStroke(1.dp, SlateBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = process.name,
                    style = AppTypography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!process.title.isNullOrBlank()) {
                    Text(
                        text = process.title,
                        style = AppTypography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                ) {
                    Text("PID ${process.pid}", style = AppTypography.labelSmall, color = TextSecondary)
                    Text(
                        text = categoryLabel(process.category),
                        style = AppTypography.labelSmall,
                        color = PrimaryIndigo.copy(alpha = 0.8f),
                        maxLines = 1
                    )
                    if (process.isProtected) {
                        Text("protegido", style = AppTypography.labelSmall, color = TextSecondary)
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatProcessMemory(process.memoryBytes),
                    style = AppTypography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = TextPrimary,
                    maxLines = 1
                )
                Text(
                    text = formatProcessCpu(process.cpuPercent) ?: "—",
                    style = AppTypography.labelSmall,
                    color = if (process.cpuPercent == null) TextSecondary else PrimaryIndigo,
                    maxLines = 1
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProcessDetailSheet(
    process: OsProcess,
    isKilling: Boolean,
    onDismiss: () -> Unit,
    onRequestKill: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SlateCard
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.xl)
                .padding(bottom = AppSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = process.name,
                    style = AppTypography.titleMedium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text("PID ${process.pid}", style = AppTypography.labelSmall, color = TextSecondary)
            }
            if (!process.title.isNullOrBlank()) {
                Text(
                    text = process.title,
                    style = AppTypography.bodySmall,
                    color = TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            HorizontalDivider(color = SlateBorder)
            DetailRow("PID padre", process.parentPid?.toString() ?: "—")
            DetailRow("Categoría", categoryLabel(process.category))
            DetailRow("Memoria", formatProcessMemory(process.memoryBytes))
            DetailRow("CPU", formatProcessCpu(process.cpuPercent) ?: "—")
            DetailRow("Ruta", process.path ?: "No disponible")

            if (process.isExplorer && !process.isProtected) {
                Text(
                    text = "Explorer es el shell de Windows: al finalizarlo, Windows lo reinicia solo.",
                    style = AppTypography.bodySmall,
                    color = StatusWorking
                )
            }

            if (process.isProtected) {
                Text(
                    text = "Proceso protegido: no se puede finalizar desde la app.",
                    style = AppTypography.bodySmall,
                    color = TextSecondary
                )
            } else {
                Button(
                    onClick = onRequestKill,
                    enabled = !isKilling,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = StatusError)
                ) {
                    if (isKilling) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Text("Finalizar proceso", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = AppTypography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.width(110.dp)
        )
        Text(
            text = value,
            style = AppTypography.bodySmall,
            color = TextPrimary,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun KillProcessDialog(
    process: OsProcess,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("¿Finalizar proceso?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                Text(
                    "${process.name} (PID ${process.pid}) se cerrará en la PC. " +
                        "Los cambios sin guardar de esa aplicación se pierden."
                )
                if (process.isExplorer) {
                    Text(
                        text = "Explorer es el shell de Windows y se reiniciará automáticamente.",
                        color = StatusWorking,
                        style = AppTypography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = StatusError)
            ) {
                Text("Finalizar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        },
        containerColor = SlateCard,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary
    )
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "No se pudieron cargar los procesos",
            style = AppTypography.titleMedium,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(AppSpacing.sm))
        Text(
            text = message,
            style = AppTypography.bodySmall,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(AppSpacing.md))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo)
        ) {
            Text("Reintentar", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun EmptyState(category: ProcessCategoryFilter, hasQuery: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.SearchOff,
            contentDescription = null,
            tint = TextSecondary.copy(alpha = 0.4f),
            modifier = Modifier.size(36.dp)
        )
        Spacer(modifier = Modifier.height(AppSpacing.sm))
        Text(
            text = if (hasQuery) {
                "Ningún proceso coincide con la búsqueda"
            } else {
                "Sin procesos en \"${category.label}\""
            },
            style = AppTypography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = AppSpacing.lg)
        )
    }
}

private fun categoryLabel(category: String): String = when (category) {
    "apps" -> "Apps"
    "background" -> "Segundo plano"
    "windows" -> "Windows"
    "agents" -> "Agentes"
    "idupi" -> "IDUPI"
    else -> category.replaceFirstChar { it.uppercase() }
}
