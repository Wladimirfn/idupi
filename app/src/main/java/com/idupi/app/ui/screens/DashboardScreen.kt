package com.idupi.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.idupi.app.ui.components.StatusCard
import com.idupi.app.ui.theme.AppShapes
import com.idupi.app.ui.theme.AppSpacing
import com.idupi.app.ui.theme.AppTypography
import com.idupi.app.ui.theme.PrimaryIndigo
import com.idupi.app.ui.theme.SlateBg
import com.idupi.app.ui.theme.SlateBorder
import com.idupi.app.ui.theme.SlateCard
import com.idupi.app.ui.theme.TextPrimary
import com.idupi.app.ui.theme.TextSecondary
import com.idupi.app.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    onMenuClick: () -> Unit
) {
    val status by viewModel.status.collectAsState()
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dashboard IDUPI", style = AppTypography.appBarTitle, color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(imageVector = Icons.Default.Menu, contentDescription = "Menú", tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshStatus() }) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Actualizar", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SlateCard
                )
            )
        },
        containerColor = SlateBg
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(AppSpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap)
        ) {
            Text(
                text = "Estado del servidor conectado a esta app.",
                style = AppTypography.bodySmall,
                color = TextSecondary
            )

            // Live status card
            status?.let {
                StatusCard(
                    connected = it.connected,
                    pcName = it.pcName,
                    project = it.project,
                    agent = it.agent,
                    busy = it.busy,
                    queueSize = it.queueSize
                )
            } ?: Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(AppShapes.card)
                    .background(SlateCard)
                    .border(1.dp, SlateBorder, AppShapes.card),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = PrimaryIndigo)
            }
        }
    }
}
