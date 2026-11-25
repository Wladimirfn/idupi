package com.idupi.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.idupi.app.ui.screens.commands.IdupiCommand
import com.idupi.app.ui.screens.commands.IdupiCommandCatalog
import com.idupi.app.ui.screens.commands.IdupiCommandSection
import com.idupi.app.ui.theme.AppShapes
import com.idupi.app.ui.theme.AppSpacing
import com.idupi.app.ui.theme.AppTypography
import com.idupi.app.ui.theme.PrimaryIndigo
import com.idupi.app.ui.theme.SlateBg
import com.idupi.app.ui.theme.SlateBorder
import com.idupi.app.ui.theme.SlateCard
import com.idupi.app.ui.theme.TextPrimary
import com.idupi.app.ui.theme.TextSecondary

/**
 * Read-only reference for the commands the PC side exposes. Reachable from
 * Ajustes only (it is not a drawer destination); the back arrow returns to
 * Ajustes and system Back mirrors it in AppNavigation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Comandos IDUPI", style = AppTypography.appBarTitle, color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SlateBg)
            )
        },
        containerColor = SlateBg
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(AppSpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap)
        ) {
            Text(
                text = "Ejecutar en PowerShell desde la carpeta del proyecto IDUPI en la PC.",
                style = AppTypography.bodySmall,
                color = TextSecondary
            )

            IdupiCommandCatalog.sections.forEach { section ->
                CommandSection(
                    section = section,
                    onCopy = { item ->
                        clipboardManager.setText(AnnotatedString(item.command))
                        Toast.makeText(context, "Comando copiado", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            Spacer(modifier = Modifier.height(AppSpacing.xxxl))
        }
    }
}

@Composable
private fun CommandSection(
    section: IdupiCommandSection,
    onCopy: (IdupiCommand) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
        Text(section.title, style = AppTypography.titleSmall, color = TextSecondary)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            shape = AppShapes.card
        ) {
            Column {
                section.commands.forEachIndexed { index, item ->
                    if (index > 0) {
                        HorizontalDivider(color = SlateBorder)
                    }
                    CommandRow(item = item, onCopy = { onCopy(item) })
                }
            }
        }
    }
}

@Composable
private fun CommandRow(
    item: IdupiCommand,
    onCopy: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(AppSpacing.cardPadding),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
    ) {
        Text(
            text = item.title,
            style = AppTypography.bodyLarge.copy(fontWeight = FontWeight.Bold),
            color = TextPrimary
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(AppShapes.small)
                    .background(SlateBg)
                    .border(1.dp, SlateBorder, AppShapes.small)
                    .padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm)
            ) {
                Text(
                    text = item.command,
                    style = AppTypography.codeMono,
                    color = TextPrimary
                )
            }

            IconButton(onClick = onCopy) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copiar comando: ${item.title}",
                    tint = PrimaryIndigo
                )
            }
        }

        Text(
            text = item.description,
            style = AppTypography.bodySmall,
            color = TextSecondary
        )
    }
}
