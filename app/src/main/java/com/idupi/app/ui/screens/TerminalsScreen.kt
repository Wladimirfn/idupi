package com.idupi.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.idupi.app.domain.model.PtyInfo
import com.idupi.app.domain.model.ptyDisplay
import com.idupi.app.ui.theme.AccentPurple
import com.idupi.app.ui.theme.AppShapes
import com.idupi.app.ui.theme.AppSpacing
import com.idupi.app.ui.theme.AppTypography
import com.idupi.app.ui.theme.PrimaryIndigo
import com.idupi.app.ui.theme.SlateBg
import com.idupi.app.ui.theme.SlateBorder
import com.idupi.app.ui.theme.SlateCard
import com.idupi.app.ui.theme.StatusConnected
import com.idupi.app.ui.theme.StatusError
import com.idupi.app.ui.theme.StatusTerminal
import com.idupi.app.ui.theme.StatusWorking
import com.idupi.app.ui.theme.TextPrimary
import com.idupi.app.ui.theme.TextSecondary
import com.idupi.app.viewmodel.TerminalsViewModel

/**
 * Live interactive terminals hosted on the PC. Polls while visible, creates a
 * new terminal from the header action, and opens one full screen on tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalsScreen(
    viewModel: TerminalsViewModel,
    onMenuClick: () -> Unit,
    onOpenTerminal: (String) -> Unit
) {
    val ptys by viewModel.ptys.collectAsState()
    val isCreating by viewModel.isCreating.collectAsState()
    val loadFailed by viewModel.loadFailed.collectAsState()

    // A PTY can exit (or be created elsewhere) without this app acting, so the
    // list refreshes while the screen is on screen and stops when it leaves.
    DisposableEffect(Unit) {
        viewModel.startPolling()
        onDispose { viewModel.stopPolling() }
    }

    // A terminal created here was already spawned on the PC; opening it must
    // attach to that id instead of creating a second one.
    val currentOnOpenTerminal by rememberUpdatedState(onOpenTerminal)
    LaunchedEffect(Unit) {
        for (id in viewModel.createdTerminalIds) {
            currentOnOpenTerminal(id)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.padding(end = 8.dp)) {
                        Text(
                            "Terminales",
                            style = AppTypography.appBarTitle,
                            color = TextPrimary
                        )
                        Text(
                            text = "${ptys.count { it.alive }} activas de ${ptys.size}",
                            style = AppTypography.labelSmall,
                            color = TextSecondary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Menú",
                            tint = TextPrimary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refrescar",
                            tint = TextPrimary
                        )
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
            Button(
                onClick = { viewModel.createTerminal() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isCreating,
                shape = AppShapes.button,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryIndigo,
                    contentColor = Color.White
                )
            ) {
                if (isCreating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isCreating) "Creando terminal..." else "Nueva terminal",
                    style = AppTypography.labelSmall.copy(fontWeight = FontWeight.Bold)
                )
            }

            when {
                loadFailed && ptys.isEmpty() -> ErrorState(onRetry = { viewModel.refresh() })

                ptys.isEmpty() -> EmptyState()

                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(vertical = AppSpacing.xs),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                ) {
                    items(ptys, key = { it.id }) { pty ->
                        PtyListItem(pty = pty, onClick = { onOpenTerminal(pty.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.EmptyState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            Icon(
                Icons.Default.Terminal,
                contentDescription = null,
                tint = TextSecondary.copy(alpha = 0.4f),
                modifier = Modifier.size(40.dp)
            )
            Text(
                "No hay terminales abiertas.\nCrea una con \"Nueva terminal\".",
                style = AppTypography.labelSmall,
                color = TextSecondary.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ColumnScope.ErrorState(onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            Icon(
                Icons.Default.CloudOff,
                contentDescription = null,
                tint = StatusError.copy(alpha = 0.6f),
                modifier = Modifier.size(40.dp)
            )
            Text(
                "No se pudieron cargar las terminales de tu PC.",
                style = AppTypography.labelSmall,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
            Button(
                onClick = onRetry,
                shape = AppShapes.button,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryIndigo,
                    contentColor = Color.White
                )
            ) {
                Text(
                    "Reintentar",
                    style = AppTypography.labelSmall.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}

@Composable
private fun PtyListItem(pty: PtyInfo, onClick: () -> Unit) {
    val display = ptyDisplay(pty)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
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
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(PrimaryIndigo.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = null,
                    tint = PrimaryIndigo,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Everything stacks in ONE Column: a Box would overlap the rows
            // (regression already seen on the Dashboard).
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = display.title,
                        modifier = Modifier.weight(1f),
                        style = AppTypography.bodySmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        ),
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    AgentBadge(agentId = display.agentId, label = display.agentLabel)
                }
                if (display.pathLine != null) {
                    // Leading-ellipsis compaction keeps the project folder
                    // (the useful end of the path) always visible.
                    Text(
                        text = display.pathLine,
                        style = AppTypography.codeMono.copy(fontSize = 10.sp),
                        color = TextSecondary.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (display.branchLabel != null) {
                        BranchChip(label = display.branchLabel, detached = display.detached)
                    }
                    Text(
                        text = buildString {
                            append("PID ${pty.pid} · ${pty.cols} x ${pty.rows}")
                            if (pty.alive) {
                                append(" · Activa")
                            } else {
                                append(" · Finalizada")
                                pty.exitCode?.let { append(" (código $it)") }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        style = AppTypography.labelSmall.copy(fontSize = 9.sp),
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (!pty.alive && !pty.error.isNullOrBlank()) {
                    Text(
                        text = pty.error,
                        style = AppTypography.labelSmall.copy(fontSize = 10.sp),
                        color = StatusError,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (pty.alive) StatusConnected else StatusError)
            )
        }
    }
}

/**
 * Small pill with a dot for the agent running in the terminal ("Claude Code",
 * "OpenCode", ...). A plain shell gets the muted "Shell" label.
 */
@Composable
private fun AgentBadge(agentId: String?, label: String) {
    val accent = agentAccent(agentId)
    Surface(
        shape = AppShapes.pill,
        color = accent.copy(alpha = 0.15f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Text(
                text = label,
                style = AppTypography.labelSmall.copy(
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = accent,
                maxLines = 1
            )
        }
    }
}

/**
 * One accent per agent id, drawn from the theme palette (a light tone on the
 * card, same alpha treatment as the connection chip). Unknown ids and the
 * plain shell stay muted so the terminal is still the focus.
 */
@Composable
private fun agentAccent(agentId: String?): Color = when (agentId) {
    "claude" -> AccentPurple
    "opencode" -> PrimaryIndigo
    "codex" -> StatusConnected
    "cmdc" -> StatusWorking
    "mcode" -> StatusTerminal
    "kimi" -> AccentPurple
    "qwen" -> PrimaryIndigo
    "gemini" -> StatusConnected
    "pi" -> StatusTerminal
    else -> TextSecondary
}

/**
 * The branch chip ("⎇ main"). A detached HEAD shows the short sha with the
 * distinct amber style plus the "sin rama" suffix, so it never reads like a
 * normal branch.
 */
@Composable
private fun BranchChip(label: String, detached: Boolean) {
    val accent = if (detached) StatusWorking else PrimaryIndigo
    Surface(
        shape = AppShapes.pill,
        color = accent.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.4f))
    ) {
        Text(
            text = "⎇ $label",
            modifier = Modifier
                .padding(horizontal = 6.dp, vertical = 1.dp)
                .widthIn(max = 150.dp),
            style = AppTypography.labelSmall.copy(fontSize = 9.sp),
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
