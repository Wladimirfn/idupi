package com.example.idupi.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.idupi.domain.model.SessionItem
import com.example.idupi.ui.theme.*
import com.example.idupi.viewmodel.SessionsViewModel
import kotlinx.coroutines.flow.distinctUntilChanged

private const val LOAD_MORE_THRESHOLD = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    viewModel: SessionsViewModel,
    onMenuClick: () -> Unit,
    onSessionSelect: (String) -> Unit = {}
) {
    val sessions by viewModel.sessions.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val counts by viewModel.counts.collectAsState()
    val countsPartial by viewModel.countsPartial.collectAsState()
    val selectedEngine by viewModel.selectedEngine.collectAsState()
    val canLoadMore by viewModel.canLoadMore.collectAsState()
    val includeAll by viewModel.includeAll.collectAsState()

    // Fresh scroll state per engine so a chip switch resets the list to the top,
    // matching the pre-refactor LazyColumn behavior (state was disposed on engine
    // change). Also guarantees loadMore cannot fire right after an engine switch.
    val listState = remember(selectedEngine) { LazyListState() }

    // Scroll-triggered pagination. Guards:
    //  - total > 0: never trigger on an empty list.
    //  - firstVisibleItemIndex > 0: the user must have actually scrolled before any
    //    loadMore fires, so initial composition / a freshly loaded page never
    //    triggers an unsolicited request.
    //  - lastVisible >= total - LOAD_MORE_THRESHOLD: only near the end.
    // snapshotFlow + distinctUntilChanged re-emit only on value changes, so a load
    // that appends items flips the signal back off until the user scrolls again —
    // no recomposition request loop. The ViewModel guards (null cursor, in-flight
    // flag, generation check) further deduplicate concurrent triggers.
    LaunchedEffect(listState) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val total = layoutInfo.totalItemsCount
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            total > 0 &&
                listState.firstVisibleItemIndex > 0 &&
                lastVisible >= total - LOAD_MORE_THRESHOLD
        }
            .distinctUntilChanged()
            .collect { nearEnd -> if (nearEnd) viewModel.loadMore() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Historial de Sesiones", style = AppTypography.appBarTitle, color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(imageVector = Icons.Default.Menu, contentDescription = "Menú", tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.startNewSession() }) {
                        Icon(imageVector = Icons.Default.AddComment, contentDescription = "Sesión nueva", tint = PrimaryIndigo)
                    }
                    IconButton(onClick = { viewModel.refreshSessions() }) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refrescar", tint = TextPrimary)
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

            // The list opens with the sessions the user actually started. On
            // OpenCode the unfiltered store holds 108 rows for this project, of
            // which 99 are subagent runs -- so showing everything is a choice,
            // not the default.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Mostrar subagentes y sesiones de un turno",
                    style = AppTypography.labelSmall,
                    color = TextSecondary,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = includeAll,
                    onCheckedChange = { viewModel.setIncludeAll(it) }
                )
            }

            // Engine Filter Chips. Badges come from the server counts StateFlow:
            // a null count (engine not yet loaded, or failed and omitted from a
            // partial response) renders NO badge, never 0. When the counts response
            // is partial, `all` is only a lower bound, so the All badge is
            // suppressed rather than presented as authoritative.
            val allCount = if (countsPartial) null else counts?.all
            val piCount = counts?.piCli
            val opencodeCount = counts?.opencode
            val claudeCount = counts?.claude

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    FilterChip(
                        selected = selectedEngine == "all",
                        onClick = { viewModel.selectEngine("all") },
                        label = { Text(if (allCount != null) "🌟 Todas ($allCount)" else "🌟 Todas", style = AppTypography.labelSmall) }
                    )
                }
                item {
                    FilterChip(
                        selected = selectedEngine == "pi-cli",
                        onClick = { viewModel.selectEngine("pi-cli") },
                        label = { Text(if (piCount != null) "📱 Pi CLI ($piCount)" else "📱 Pi CLI", style = AppTypography.labelSmall) }
                    )
                }
                item {
                    FilterChip(
                        selected = selectedEngine == "opencode",
                        onClick = { viewModel.selectEngine("opencode") },
                        label = { Text(if (opencodeCount != null) "🤖 OpenCode ($opencodeCount)" else "🤖 OpenCode", style = AppTypography.labelSmall) }
                    )
                }
                item {
                    FilterChip(
                        selected = selectedEngine == "claude",
                        onClick = { viewModel.selectEngine("claude") },
                        label = { Text(if (claudeCount != null) "🟠 Claude ($claudeCount)" else "🟠 Claude", style = AppTypography.labelSmall) }
                    )
                }
            }

            if (isLoading && sessions.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = PrimaryIndigo)
                }
            } else if (sessions.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.History, contentDescription = null, tint = TextSecondary.copy(alpha = 0.5f), modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(AppSpacing.sm))
                        Text(
                            text = if (selectedEngine == "all") "No hay sesiones en este proyecto todavía." else "No hay sesiones de este motor para este proyecto.",
                            style = AppTypography.bodySmall,
                            color = TextSecondary
                        )
                        // A non-null cursor means the server may have more pages even
                        // though this one was empty. Offer ONE explicit tap to keep
                        // loading — no auto-retry, no loops.
                        if (canLoadMore) {
                            Spacer(modifier = Modifier.height(AppSpacing.md))
                            Button(
                                onClick = { viewModel.loadMore() },
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp), tint = TextPrimary)
                                Spacer(modifier = Modifier.width(AppSpacing.xs))
                                Text("Cargar más", style = AppTypography.labelSmall, color = TextPrimary)
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
                    contentPadding = PaddingValues(bottom = AppSpacing.xxxl)
                ) {
                    items(sessions, key = { it.id }) { session ->
                        SessionCard(
                            session = session,
                            onSessionSelect = { onSessionSelect(session.id) },
                            onFavoriteClick = { viewModel.toggleFavorite(session.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SessionCard(
    session: SessionItem,
    onSessionSelect: () -> Unit,
    onFavoriteClick: () -> Unit
) {
    val engineName = session.engine ?: "pi-cli"
    val engineColor = when (engineName) {
        "opencode" -> StatusTerminal
        "claude" -> AccentPurple
        else -> PrimaryIndigo
    }
    val engineLabel = when (engineName) {
        "opencode" -> "OpenCode"
        "claude" -> "Claude"
        else -> "Pi CLI"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSessionSelect() },
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        shape = AppShapes.card,
        border = BorderStroke(1.dp, SlateBorder)
    ) {
        Column(modifier = Modifier.padding(AppSpacing.cardPadding)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(engineColor.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.ChatBubble, contentDescription = null, tint = engineColor, modifier = Modifier.size(20.dp))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                            Surface(
                                color = engineColor.copy(alpha = 0.15f),
                                shape = AppShapes.small
                            ) {
                                Text(
                                    text = engineLabel,
                                    style = AppTypography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = engineColor,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Text(
                                text = session.title,
                                style = AppTypography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                            Text(
                                text = session.project,
                                style = AppTypography.labelSmall,
                                color = TextSecondary
                            )
                            Box(modifier = Modifier.size(4.dp).background(TextSecondary, CircleShape))
                            Text(
                                text = session.date,
                                style = AppTypography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
                IconButton(onClick = onFavoriteClick, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (session.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = "Favorito",
                        tint = if (session.isFavorite) Color(0xFFF59E0B) else TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.sm))

            Text(
                text = session.preview,
                style = AppTypography.bodySmall,
                color = TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(AppSpacing.md))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                session.messageCount?.let { count ->
                    Box(
                        modifier = Modifier
                            .clip(AppShapes.small)
                            .background(SlateBg)
                            .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs)
                    ) {
                        Text(
                            text = "$count mensajes",
                            style = AppTypography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }

                Button(
                    onClick = onSessionSelect,
                    colors = ButtonDefaults.buttonColors(containerColor = engineColor),
                    contentPadding = PaddingValues(horizontal = AppSpacing.md, vertical = AppSpacing.xs),
                    shape = AppShapes.small
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp), tint = TextPrimary)
                    Spacer(modifier = Modifier.width(AppSpacing.xs))
                    Text("Reanudar", style = AppTypography.labelSmall, color = TextPrimary)
                }
            }
        }
    }
}
