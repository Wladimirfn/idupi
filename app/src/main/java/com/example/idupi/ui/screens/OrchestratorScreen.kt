package com.example.idupi.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.idupi.domain.model.*
import com.example.idupi.ui.theme.*
import com.example.idupi.viewmodel.OrchestratorTab
import com.example.idupi.viewmodel.OrchestratorViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrchestratorScreen(
    viewModel: OrchestratorViewModel,
    onMenuClick: () -> Unit
) {
    val status by viewModel.status.collectAsState()
    val activeTab by viewModel.activeTab.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isActionRunning by viewModel.isActionRunning.collectAsState()
    val actionOutput by viewModel.actionOutput.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val providerModels by viewModel.providerModels.collectAsState()

    var editingOpenCodePhase by remember { mutableStateOf<Pair<String, OpenCodeModelAssignment>?>(null) }
    var editingClaudePhase by remember { mutableStateOf<Pair<String, ClaudePhaseConfig>?>(null) }
    var showCreateProfileDialog by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<SddProfileItem?>(null) }

    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.refreshStatus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Orquestador SDD & Gentle-AI", style = AppTypography.appBarTitle, color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(imageVector = Icons.Default.Menu, contentDescription = "Menú", tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshStatus() }) {
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
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            Spacer(modifier = Modifier.height(AppSpacing.xs))

            // Error banner if any
            if (errorMessage != null) {
                Surface(
                    color = StatusError.copy(alpha = 0.15f),
                    shape = AppShapes.small,
                    border = BorderStroke(1.dp, StatusError.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(AppSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = errorMessage ?: "",
                            style = AppTypography.bodySmall,
                            color = StatusError,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.clearError() }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = StatusError, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            // Ecosystem Overview Header
            EcosystemHeaderCard(status = status)

            // Tabs Selector
            ScrollableTabRow(
                selectedTabIndex = activeTab.ordinal,
                containerColor = SlateCard,
                contentColor = PrimaryIndigo,
                edgePadding = 0.dp,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[activeTab.ordinal]),
                        color = PrimaryIndigo
                    )
                }
            ) {
                OrchestratorTab.values().forEach { tab ->
                    Tab(
                        selected = activeTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = {
                            Text(
                                text = tab.title,
                                style = AppTypography.labelSmall,
                                fontWeight = if (activeTab == tab) FontWeight.Bold else FontWeight.Normal,
                                color = if (activeTab == tab) PrimaryIndigo else TextSecondary,
                                maxLines = 1
                            )
                        }
                    )
                }
            }

            // Tab Content
            Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                if (isLoading && status == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = PrimaryIndigo)
                    }
                } else {
                    when (activeTab) {
                        OrchestratorTab.SDD_PHASES -> SddPhasesTabView(
                            status = status,
                            onEditOpenCode = { phase, current -> editingOpenCodePhase = phase to current },
                            onEditClaude = { phase, current -> editingClaudePhase = phase to current }
                        )
                        OrchestratorTab.OPENCODE_MODELS -> OpenCodeModelsTabView(
                            assignments = status?.modelAssignments ?: emptyMap(),
                            onEdit = { role, current -> editingOpenCodePhase = role to current }
                        )
                        OrchestratorTab.CLAUDE_MODELS -> ClaudeModelsTabView(
                            assignments = status?.claudePhaseAssignments ?: emptyMap(),
                            onEdit = { phase, current -> editingClaudePhase = phase to current }
                        )
                        OrchestratorTab.SDD_PROFILES -> SddProfilesTabView(
                            profiles = status?.sddProfiles ?: emptyList(),
                            activeProfileId = status?.activeProfile,
                            onApplyProfile = { id ->
                                viewModel.applyProfile(id) {
                                    Toast.makeText(context, "Perfil SDD activado y sincronizado", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onCreateProfileClick = { showCreateProfileDialog = true },
                            onEditProfile = { prof -> editingProfile = prof },
                            onDeleteProfile = { id ->
                                viewModel.deleteProfile(id) {
                                    Toast.makeText(context, "Perfil eliminado", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                        OrchestratorTab.ECOSYSTEM_TOOLS -> EcosystemToolsTabView(
                            status = status,
                            isActionRunning = isActionRunning,
                            actionOutput = actionOutput,
                            onRunAction = { action -> viewModel.runAction(action) },
                            onClearOutput = { viewModel.clearActionOutput() }
                        )
                    }
                }
            }
        }
    }

    // Dialogs
    editingOpenCodePhase?.let { (phase, current) ->
        EditOpenCodeModelDialog(
            phase = phase,
            current = current,
            availableProviders = status?.providers ?: listOf("opencode-go", "openai", "alibaba", "minimax", "zai", "moonshotai", "google"),
            providerModels = providerModels,
            onLoadProviderModels = { prov -> viewModel.loadProviderModels(prov) },
            onDismiss = { editingOpenCodePhase = null },
            onSave = { modelId, providerId, effort ->
                viewModel.updateModel(
                    engine = "opencode",
                    phase = phase,
                    modelId = modelId,
                    providerId = providerId,
                    effort = effort,
                    onSuccess = {
                        Toast.makeText(context, "Modelo actualizado y sincronizado en PC", Toast.LENGTH_SHORT).show()
                        editingOpenCodePhase = null
                    }
                )
            }
        )
    }

    editingClaudePhase?.let { (phase, current) ->
        EditClaudeModelDialog(
            phase = phase,
            current = current,
            onDismiss = { editingClaudePhase = null },
            onSave = { model ->
                viewModel.updateModel(
                    engine = "claude",
                    phase = phase,
                    modelId = model,
                    onSuccess = {
                        Toast.makeText(context, "Modelo de Claude actualizado y sincronizado", Toast.LENGTH_SHORT).show()
                        editingClaudePhase = null
                    }
                )
            }
        )
    }

    if (showCreateProfileDialog) {
        CreateOrEditSddProfileDialog(
            profile = null,
            currentModelAssignments = status?.modelAssignments ?: emptyMap(),
            currentClaudeAssignments = status?.claudePhaseAssignments ?: emptyMap(),
            onDismiss = { showCreateProfileDialog = false },
            onSave = { profileItem ->
                viewModel.saveProfile(profileItem) {
                    Toast.makeText(context, "Perfil '${profileItem.name}' guardado en tu PC", Toast.LENGTH_SHORT).show()
                    showCreateProfileDialog = false
                }
            }
        )
    }

    editingProfile?.let { prof ->
        CreateOrEditSddProfileDialog(
            profile = prof,
            currentModelAssignments = prof.modelAssignments.ifEmpty { status?.modelAssignments ?: emptyMap() },
            currentClaudeAssignments = prof.claudeAssignments.ifEmpty { status?.claudePhaseAssignments ?: emptyMap() },
            onDismiss = { editingProfile = null },
            onSave = { profileItem ->
                viewModel.saveProfile(profileItem) {
                    Toast.makeText(context, "Perfil '${profileItem.name}' actualizado en tu PC", Toast.LENGTH_SHORT).show()
                    editingProfile = null
                }
            }
        )
    }
}

@Composable
private fun EcosystemHeaderCard(status: OrchestratorStatus?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        shape = AppShapes.card,
        border = BorderStroke(1.dp, SlateBorder)
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Psychology, contentDescription = null, tint = AccentPurple, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(AppSpacing.xs))
                    Text("ECOSISTEMA GENTLE-AI", style = AppTypography.labelSmall, fontWeight = FontWeight.Bold, color = TextPrimary)
                }

                Surface(
                    color = StatusConnected.copy(alpha = 0.15f),
                    shape = AppShapes.small
                ) {
                    Text(
                        text = "RDD ${status?.rddMode?.uppercase() ?: "ON"}",
                        style = AppTypography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = StatusConnected,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Installed Agents chips
            val agents = status?.installedAgents?.ifEmpty { null } ?: listOf("opencode", "claude-code", "pi", "codex", "kiro-ide", "kimi")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(agents) { agent ->
                    Surface(
                        color = SlateBg,
                        shape = AppShapes.small,
                        border = BorderStroke(1.dp, SlateBorder)
                    ) {
                        Text(
                            text = "🤖 $agent",
                            style = AppTypography.labelSmall,
                            color = TextSecondary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // SDD Status snippet
            status?.sddStatus?.let { sdd ->
                val isIdle = sdd.blockedReasons.any { it.contains("No active OpenSpec", ignoreCase = true) }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isIdle) "SDD: EN REPOSO (Listo)" else "SDD: ${sdd.applyState.uppercase()}${if (sdd.changeName != null) " (${sdd.changeName})" else ""}",
                            style = AppTypography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isIdle) StatusConnected else StatusWorking
                        )
                        Text(
                            text = "Siguiente: ${sdd.nextRecommended}",
                            style = AppTypography.labelSmall,
                            color = PrimaryIndigo
                        )
                    }
                    if (isIdle) {
                        Text(
                            text = "Pipeline SDD preparado. Se activará automáticamente al iniciar un cambio.",
                            style = AppTypography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}

private val SDD_PIPELINE_STAGES = listOf(
    "sdd-init" to "0. Bootstrap & Inicializar SDD",
    "sdd-onboard" to "1. Onboarding Guiado en Codebase",
    "sdd-explore" to "2. Explorar Codebase & Contexto",
    "sdd-propose" to "3. Propuesta de Cambio",
    "sdd-spec" to "4. Especificación Formal",
    "sdd-design" to "5. Diseño de Arquitectura",
    "sdd-tasks" to "6. Desglose de Tareas",
    "sdd-apply" to "7. Aplicar Modificaciones",
    "sdd-verify" to "8. Verificación & Testing",
    "sdd-archive" to "9. Consolidar & Archivar"
)

private val AGENT_ROLE_DESCRIPTIONS = mapOf(
    "gentle-orchestrator" to "Orquestador Maestro de Gentle-AI (Mantiene la coordinación y delega fases)",
    "sdd-init" to "Bootstrap SDD context and project configuration",
    "sdd-onboard" to "Guía paso a paso en ciclo SDD usando el codebase real",
    "sdd-explore" to "Explorador de arquitectura y mapa del código",
    "sdd-propose" to "Generador de propuesta y análisis de impacto",
    "sdd-spec" to "Especificación formal de requisitos",
    "sdd-design" to "Diseño técnico de arquitectura y dependencias",
    "sdd-tasks" to "Desglose estructurado de tareas unitarias",
    "sdd-apply" to "Aplicador quirúrgico de modificaciones y código",
    "sdd-verify" to "Verificación, validación y ejecución de tests",
    "sdd-archive" to "Consolidación y archivado del cambio completado",
    "jd-judge-a" to "Adversarial code reviewer — blind judge A (Judgment Day)",
    "jd-judge-b" to "Adversarial code reviewer — blind judge B (Judgment Day)",
    "jd-fix-agent" to "Surgical fix agent for judgment-day protocol",
    "review-refuter" to "Batched adversarial refuter — evalúa candidatos a blocker",
    "review-risk" to "Análisis de riesgos y seguridad",
    "review-resilience" to "Resiliencia y tolerancia a fallos",
    "review-readability" to "Legibilidad y directrices de código",
    "review-reliability" to "Fiabilidad y cobertura de pruebas",
    "gga-reviewer" to "Gentleman Guardian Angel Reviewer",
    "build" to "Compilación y empaquetado",
    "explore" to "Exploración rápida de archivos",
    "plan" to "Planificación general",
    "general" to "Asistente multipropósito general"
)

private val AGENT_CATEGORIES = listOf(
    "🌟 Orquestación Central" to listOf("gentle-orchestrator"),
    "🔄 Ciclo SDD Completo" to listOf("sdd-init", "sdd-onboard", "sdd-explore", "sdd-propose", "sdd-spec", "sdd-design", "sdd-tasks", "sdd-apply", "sdd-verify", "sdd-archive"),
    "⚖️ Judgment Day (Adversarial Protocol)" to listOf("jd-judge-a", "jd-judge-b", "jd-fix-agent"),
    "🛡️ Comité de Revisión & Refutación" to listOf("review-refuter", "review-risk", "review-resilience", "review-readability", "review-reliability", "gga-reviewer"),
    "🔧 Subagentes Generales" to listOf("build", "explore", "plan", "general")
)

@Composable
private fun SddPhasesTabView(
    status: OrchestratorStatus?,
    onEditOpenCode: (String, OpenCodeModelAssignment) -> Unit,
    onEditClaude: (String, ClaudePhaseConfig) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        contentPadding = PaddingValues(bottom = AppSpacing.xl)
    ) {
        items(SDD_PIPELINE_STAGES) { (stageKey, stageTitle) ->
            val openCodeAssign = status?.modelAssignments?.get(stageKey) ?: OpenCodeModelAssignment(model_id = "default")
            val claudeConfig = status?.claudePhaseAssignments?.get(stageKey) ?: ClaudePhaseConfig(model = "sonnet")

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SlateCard),
                shape = AppShapes.card,
                border = BorderStroke(1.dp, SlateBorder)
            ) {
                Column(
                    modifier = Modifier.padding(AppSpacing.cardPadding),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                ) {
                    Text(
                        text = stageTitle,
                        style = AppTypography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )

                    AGENT_ROLE_DESCRIPTIONS[stageKey]?.let { desc ->
                        Text(text = desc, style = AppTypography.labelSmall.copy(fontSize = 10.sp), color = TextSecondary)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // OpenCode model pill
                        Surface(
                            color = PrimaryIndigo.copy(alpha = 0.15f),
                            shape = AppShapes.small,
                            border = BorderStroke(1.dp, PrimaryIndigo.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onEditOpenCode(stageKey, openCodeAssign) }
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                Text("${openCodeAssign.provider_id} / Pi", style = AppTypography.labelSmall, color = TextSecondary)
                                Text(
                                    text = openCodeAssign.model_id.ifBlank { "Sin asignar" },
                                    style = AppTypography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = PrimaryIndigo,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(AppSpacing.xs))

                        // Claude model pill
                        Surface(
                            color = AccentPurple.copy(alpha = 0.15f),
                            shape = AppShapes.small,
                            border = BorderStroke(1.dp, AccentPurple.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onEditClaude(stageKey, claudeConfig) }
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                Text("Claude", style = AppTypography.labelSmall, color = TextSecondary)
                                Text(
                                    text = claudeConfig.model,
                                    style = AppTypography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = AccentPurple,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SddProfilesTabView(
    profiles: List<SddProfileItem>,
    activeProfileId: String?,
    onApplyProfile: (String) -> Unit,
    onCreateProfileClick: () -> Unit,
    onEditProfile: (SddProfileItem) -> Unit,
    onDeleteProfile: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("PERFILES SDD EN GENTLE-AI", style = AppTypography.labelSmall, fontWeight = FontWeight.Bold, color = TextPrimary)

            Button(
                onClick = onCreateProfileClick,
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo),
                contentPadding = PaddingValues(horizontal = AppSpacing.sm, vertical = 2.dp),
                shape = AppShapes.small
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Crear Perfil", style = AppTypography.labelSmall)
            }
        }

        if (profiles.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Tune, contentDescription = null, tint = TextSecondary.copy(alpha = 0.5f), modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(AppSpacing.xs))
                    Text("No hay perfiles SDD creados en ~/.config/opencode/profiles/", style = AppTypography.bodySmall, color = TextSecondary)
                    Spacer(modifier = Modifier.height(AppSpacing.sm))
                    Button(
                        onClick = onCreateProfileClick,
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo),
                        shape = AppShapes.small
                    ) {
                        Text("Crear el primer perfil", style = AppTypography.labelSmall)
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().weight(1f),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                contentPadding = PaddingValues(bottom = AppSpacing.xl)
            ) {
                items(profiles) { prof ->
                    val isSelected = prof.id.equals(activeProfileId, ignoreCase = true)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = SlateCard),
                        shape = AppShapes.card,
                        border = BorderStroke(1.dp, if (isSelected) PrimaryIndigo else SlateBorder)
                    ) {
                        Column(modifier = Modifier.padding(AppSpacing.cardPadding), verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = prof.name,
                                        style = AppTypography.bodyLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                    if (isSelected) {
                                        Spacer(modifier = Modifier.width(AppSpacing.xs))
                                        Surface(
                                            color = StatusConnected.copy(alpha = 0.2f),
                                            shape = AppShapes.small
                                        ) {
                                            Text(
                                                text = "ACTIVO",
                                                style = AppTypography.labelSmall.copy(fontSize = 9.sp),
                                                fontWeight = FontWeight.Bold,
                                                color = StatusConnected,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }

                                Row {
                                    IconButton(onClick = { onEditProfile(prof) }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Default.Edit, contentDescription = "Editar", tint = TextSecondary, modifier = Modifier.size(16.dp))
                                    }
                                    IconButton(onClick = { onDeleteProfile(prof.id) }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Default.Delete, contentDescription = "Eliminar", tint = StatusError.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
                                    }
                                }
                            }

                            prof.description?.let { desc ->
                                Text(text = desc, style = AppTypography.bodySmall, color = TextSecondary)
                            }

                            // Model summary pills
                            val exploreModel = prof.modelAssignments["sdd-explore"]?.model_id ?: "default"
                            val applyModel = prof.modelAssignments["sdd-apply"]?.model_id ?: "default"
                            val verifyModel = prof.modelAssignments["sdd-verify"]?.model_id ?: "default"

                            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                                Surface(color = SlateBg, shape = AppShapes.small) {
                                    Text("Explorar: $exploreModel", style = AppTypography.labelSmall.copy(fontSize = 9.sp), color = TextSecondary, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                                }
                                Surface(color = SlateBg, shape = AppShapes.small) {
                                    Text("Apply: $applyModel", style = AppTypography.labelSmall.copy(fontSize = 9.sp), color = TextSecondary, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                                }
                                Surface(color = SlateBg, shape = AppShapes.small) {
                                    Text("Verify: $verifyModel", style = AppTypography.labelSmall.copy(fontSize = 9.sp), color = TextSecondary, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                                }
                            }

                            Spacer(modifier = Modifier.height(2.dp))

                            Button(
                                onClick = { onApplyProfile(prof.id) },
                                colors = ButtonDefaults.buttonColors(containerColor = if (isSelected) StatusConnected else PrimaryIndigo),
                                shape = AppShapes.small,
                                modifier = Modifier.fillMaxWidth().height(32.dp)
                            ) {
                                Icon(if (isSelected) Icons.Default.Check else Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (isSelected) "Perfil en Uso" else "Activar Perfil SDD", style = AppTypography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OpenCodeModelsTabView(
    assignments: Map<String, OpenCodeModelAssignment>,
    onEdit: (String, OpenCodeModelAssignment) -> Unit
) {
    val itemsList = assignments.entries.toList().sortedBy { it.key }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
        contentPadding = PaddingValues(bottom = AppSpacing.xl)
    ) {
        items(itemsList) { (role, assign) ->
            Surface(
                color = SlateCard,
                shape = AppShapes.small,
                border = BorderStroke(1.dp, SlateBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEdit(role, assign) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = role,
                            style = AppTypography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "${assign.provider_id} · ${if (assign.effort != null) "Esfuerzo: ${assign.effort}" else "Estándar"}",
                            style = AppTypography.labelSmall,
                            color = TextSecondary
                        )
                    }

                    Surface(
                        color = PrimaryIndigo.copy(alpha = 0.2f),
                        shape = AppShapes.small
                    ) {
                        Text(
                            text = assign.model_id,
                            style = AppTypography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryIndigo,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ClaudeModelsTabView(
    assignments: Map<String, ClaudePhaseConfig>,
    onEdit: (String, ClaudePhaseConfig) -> Unit
) {
    val itemsList = assignments.entries.toList().sortedBy { it.key }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
        contentPadding = PaddingValues(bottom = AppSpacing.xl)
    ) {
        items(itemsList) { (phase, config) ->
            Surface(
                color = SlateCard,
                shape = AppShapes.small,
                border = BorderStroke(1.dp, SlateBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEdit(phase, config) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = phase,
                        style = AppTypography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )

                    Surface(
                        color = AccentPurple.copy(alpha = 0.2f),
                        shape = AppShapes.small
                    ) {
                        Text(
                            text = config.model,
                            style = AppTypography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = AccentPurple,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EcosystemToolsTabView(
    status: OrchestratorStatus?,
    isActionRunning: Boolean,
    actionOutput: String?,
    onRunAction: (String) -> Unit,
    onClearOutput: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
    ) {
        // Quick Action Buttons
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
            Button(
                onClick = { onRunAction("sync") },
                enabled = !isActionRunning,
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo),
                shape = AppShapes.small,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Sync", style = AppTypography.labelSmall)
            }

            Button(
                onClick = { onRunAction("doctor") },
                enabled = !isActionRunning,
                colors = ButtonDefaults.buttonColors(containerColor = AccentPurple),
                shape = AppShapes.small,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.MedicalServices, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Doctor", style = AppTypography.labelSmall)
            }

            Button(
                onClick = { onRunAction("skill-refresh") },
                enabled = !isActionRunning,
                colors = ButtonDefaults.buttonColors(containerColor = StatusConnected),
                shape = AppShapes.small,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.AutoStories, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Skills", style = AppTypography.labelSmall)
            }
        }

        // Active Community Plugins & Components
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            shape = AppShapes.card,
            border = BorderStroke(1.dp, SlateBorder)
        ) {
            Column(modifier = Modifier.padding(AppSpacing.cardPadding), verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                Text("PLUGINS & COMPONENTES ACTIVOS", style = AppTypography.labelSmall, fontWeight = FontWeight.Bold, color = TextPrimary)

                val components = status?.components?.ifEmpty { null } ?: listOf("engram", "codegraph", "context7", "playwright", "supabase", "permissions", "gga")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(components) { comp ->
                        Surface(
                            color = SlateBg,
                            shape = AppShapes.small,
                            border = BorderStroke(1.dp, SlateBorder)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.size(6.dp).background(StatusConnected, CircleShape))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(comp, style = AppTypography.labelSmall, color = TextPrimary)
                            }
                        }
                    }
                }
            }
        }

        // Info card explaining automatic sync
        Surface(
            color = PrimaryIndigo.copy(alpha = 0.1f),
            shape = AppShapes.small,
            border = BorderStroke(1.dp, PrimaryIndigo.copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(AppSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                Icon(Icons.Default.Info, contentDescription = null, tint = PrimaryIndigo, modifier = Modifier.size(18.dp))
                Text(
                    text = "Al cambiar cualquier modelo o aplicar un perfil SDD, IDUPI actualiza state.json y ejecuta 'gentle-ai sync' automáticamente para propagar la configuración en tus agentes.",
                    style = AppTypography.labelSmall,
                    color = TextPrimary
                )
            }
        }

        // Action Output / Terminal Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            shape = AppShapes.card,
            border = BorderStroke(1.dp, SlateBorder)
        ) {
            Column(modifier = Modifier.padding(AppSpacing.cardPadding)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("SALIDA DE COMANDOS GENTLE-AI", style = AppTypography.labelSmall, color = TextSecondary)

                    Row {
                        if (actionOutput != null) {
                            IconButton(
                                onClick = {
                                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cb.setPrimaryClip(ClipData.newPlainText("Gentle-AI Log", actionOutput))
                                    Toast.makeText(context, "Copiado al portapapeles", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copiar", tint = TextSecondary, modifier = Modifier.size(16.dp))
                            }
                            IconButton(
                                onClick = onClearOutput,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Limpiar", tint = TextSecondary, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(AppSpacing.xs))

                Surface(
                    color = SlateBg,
                    shape = AppShapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 320.dp)
                ) {
                    if (isActionRunning) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = PrimaryIndigo, modifier = Modifier.size(32.dp))
                                Spacer(modifier = Modifier.height(AppSpacing.xs))
                                Text("Ejecutando en la PC...", style = AppTypography.labelSmall, color = TextSecondary)
                            }
                        }
                    } else {
                        Text(
                            text = actionOutput ?: "Presiona 'Sync', 'Doctor' o 'Skills' para ejecutar herramientas en tu PC.",
                            style = AppTypography.codeMono.copy(fontSize = 11.sp, lineHeight = 16.sp),
                            color = if (actionOutput != null) StatusTerminal else TextSecondary,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(AppSpacing.sm)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditOpenCodeModelDialog(
    phase: String,
    current: OpenCodeModelAssignment,
    availableProviders: List<String>,
    providerModels: Map<String, List<ProviderModelItem>>,
    onLoadProviderModels: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (modelId: String, providerId: String, effort: String?) -> Unit
) {
    var selectedProvider by remember { mutableStateOf(current.provider_id.ifBlank { "opencode-go" }) }
    var selectedModelId by remember { mutableStateOf(current.model_id) }
    var selectedEffort by remember { mutableStateOf(current.effort) }

    val effortOptions = listOf(
        null to "Estándar / Desactivado",
        "low" to "Low (Bajo)",
        "medium" to "Medium (Medio)",
        "high" to "High (Alto)",
        "max" to "Max (Máximo)"
    )

    LaunchedEffect(selectedProvider) {
        onLoadProviderModels(selectedProvider)
    }

    val currentModels = providerModels[selectedProvider] ?: emptyList()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SlateCard,
        title = {
            Text("Configurar Modelo OpenCode", style = AppTypography.titleMedium, color = TextPrimary)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                Text("Rol / Fase: $phase", style = AppTypography.bodySmall, fontWeight = FontWeight.Bold, color = PrimaryIndigo)

                // 1. Selector de Proveedor
                Text("1. Selecciona Proveedor de IA:", style = AppTypography.labelSmall, fontWeight = FontWeight.Bold, color = TextSecondary)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(availableProviders) { prov ->
                        FilterChip(
                            selected = selectedProvider == prov,
                            onClick = {
                                selectedProvider = prov
                                selectedModelId = "" // reset to pick from new provider
                            },
                            label = { Text(prov, style = AppTypography.labelSmall) }
                        )
                    }
                }

                // 2. Selector de Modelos del Proveedor (Sin escribir a mano)
                Text("2. Selecciona el Modelo de $selectedProvider:", style = AppTypography.labelSmall, fontWeight = FontWeight.Bold, color = TextSecondary)
                if (currentModels.isEmpty()) {
                    Surface(
                        color = SlateBg,
                        shape = AppShapes.small,
                        modifier = Modifier.fillMaxWidth().padding(vertical = AppSpacing.xs)
                    ) {
                        Row(modifier = Modifier.padding(AppSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = PrimaryIndigo, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(AppSpacing.sm))
                            Text("Consultando modelos de $selectedProvider...", style = AppTypography.labelSmall, color = TextSecondary)
                        }
                    }
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(currentModels) { m ->
                            val isChosen = selectedModelId == m.id || selectedModelId == m.fullId
                            FilterChip(
                                selected = isChosen,
                                onClick = { selectedModelId = m.id },
                                label = { Text(m.name, style = AppTypography.labelSmall) }
                            )
                        }
                    }
                }

                // Modelo Seleccionado Actualmente
                Surface(
                    color = PrimaryIndigo.copy(alpha = 0.15f),
                    shape = AppShapes.small,
                    border = BorderStroke(1.dp, PrimaryIndigo.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(AppSpacing.sm)) {
                        Text("Modelo seleccionado:", style = AppTypography.labelSmall.copy(fontSize = 10.sp), color = TextSecondary)
                        Text(
                            text = if (selectedModelId.isNotBlank()) "$selectedProvider/$selectedModelId" else "Ningún modelo seleccionado aún",
                            style = AppTypography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = if (selectedModelId.isNotBlank()) PrimaryIndigo else StatusError
                        )
                    }
                }

                // 3. Selector de Nivel de Esfuerzo (Sin escribir a mano)
                Text("3. Nivel de Esfuerzo de Razonamiento (Effort):", style = AppTypography.labelSmall, fontWeight = FontWeight.Bold, color = TextSecondary)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    effortOptions.forEach { (effKey, effLabel) ->
                        val isEffortSelected = selectedEffort == effKey
                        Surface(
                            color = if (isEffortSelected) PrimaryIndigo.copy(alpha = 0.15f) else SlateBg,
                            shape = AppShapes.small,
                            border = BorderStroke(1.dp, if (isEffortSelected) PrimaryIndigo else SlateBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedEffort = effKey }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = AppSpacing.sm, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isEffortSelected,
                                    onClick = { selectedEffort = effKey },
                                    colors = RadioButtonDefaults.colors(selectedColor = PrimaryIndigo)
                                )
                                Spacer(modifier = Modifier.width(AppSpacing.xs))
                                Text(
                                    text = effLabel,
                                    style = AppTypography.labelSmall,
                                    fontWeight = if (isEffortSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isEffortSelected) TextPrimary else TextSecondary
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(selectedModelId.trim(), selectedProvider.trim(), selectedEffort) },
                enabled = selectedModelId.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo)
            ) {
                Text("Guardar", style = AppTypography.labelSmall)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancelar", style = AppTypography.labelSmall, color = TextSecondary)
            }
        }
    )
}

@Composable
private fun EditClaudeModelDialog(
    phase: String,
    current: ClaudePhaseConfig,
    onDismiss: () -> Unit,
    onSave: (model: String) -> Unit
) {
    var selectedModel by remember { mutableStateOf(current.model) }
    val claudeModels = listOf("sonnet", "haiku", "opus")

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SlateCard,
        title = {
            Text("Configurar Modelo Claude", style = AppTypography.titleMedium, color = TextPrimary)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                Text("Fase: $phase", style = AppTypography.bodySmall, fontWeight = FontWeight.Bold, color = AccentPurple)

                claudeModels.forEach { m ->
                    Surface(
                        color = if (selectedModel == m) AccentPurple.copy(alpha = 0.2f) else SlateBg,
                        shape = AppShapes.small,
                        border = BorderStroke(1.dp, if (selectedModel == m) AccentPurple else SlateBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedModel = m }
                    ) {
                        Row(
                            modifier = Modifier.padding(AppSpacing.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedModel == m,
                                onClick = { selectedModel = m },
                                colors = RadioButtonDefaults.colors(selectedColor = AccentPurple)
                            )
                            Spacer(modifier = Modifier.width(AppSpacing.xs))
                            Text(
                                text = "claude-$m",
                                style = AppTypography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(selectedModel) },
                colors = ButtonDefaults.buttonColors(containerColor = AccentPurple)
            ) {
                Text("Guardar", style = AppTypography.labelSmall)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancelar", style = AppTypography.labelSmall, color = TextSecondary)
            }
        }
    )
}

@Composable
private fun CreateOrEditSddProfileDialog(
    profile: SddProfileItem?,
    currentModelAssignments: Map<String, OpenCodeModelAssignment>,
    currentClaudeAssignments: Map<String, ClaudePhaseConfig>,
    onDismiss: () -> Unit,
    onSave: (profile: SddProfileItem) -> Unit
) {
    var name by remember { mutableStateOf(profile?.name ?: "") }
    var description by remember { mutableStateOf(profile?.description ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SlateCard,
        title = {
            Text(
                text = if (profile == null) "Crear Nuevo Perfil SDD" else "Editar Perfil SDD",
                style = AppTypography.titleMedium,
                color = TextPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                Text(
                    text = "El perfil SDD guardará las asignaciones de modelos en ~/.config/opencode/profiles/ para aplicarlas cuando quieras.",
                    style = AppTypography.bodySmall,
                    color = TextSecondary
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre del Perfil (ej. DeepSeek Pro, Luna Ultra)") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = PrimaryIndigo,
                        unfocusedBorderColor = SlateBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Descripción (opcional)") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = PrimaryIndigo,
                        unfocusedBorderColor = SlateBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val safeId = profile?.id ?: name.trim().lowercase().replace(Regex("[^a-z0-9_-]"), "-")
                    onSave(
                        SddProfileItem(
                            id = safeId,
                            name = name.trim(),
                            description = description.trim().ifBlank { null },
                            isCustom = true,
                            modelAssignments = currentModelAssignments,
                            claudeAssignments = currentClaudeAssignments
                        )
                    )
                },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo)
            ) {
                Text("Guardar Perfil", style = AppTypography.labelSmall)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancelar", style = AppTypography.labelSmall, color = TextSecondary)
            }
        }
    )
}
