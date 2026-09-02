package com.idupi.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.idupi.app.domain.model.FileNode
import com.idupi.app.domain.model.Project
import com.idupi.app.ui.components.CodeViewer
import com.idupi.app.ui.theme.*
import com.idupi.app.viewmodel.ProjectsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    viewModel: ProjectsViewModel,
    onMenuClick: () -> Unit
) {
    val projects by viewModel.projects.collectAsState()
    val activeProjectId by viewModel.activeProjectId.collectAsState()
    val projectFiles by viewModel.projectFiles.collectAsState()

    val fileContent by viewModel.selectedFileContent.collectAsState()
    val selectedFileName by viewModel.selectedFileName.collectAsState()

    val addProjectError by viewModel.addProjectError.collectAsState()
    val isAddingProject by viewModel.isAddingProject.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    // Multi-selection and deletion states
    val selectedProjectIds by viewModel.selectedProjectIds.collectAsState()
    val isSelectionMode by viewModel.isSelectionMode.collectAsState()
    val isRemovingProjects by viewModel.isRemovingProjects.collectAsState()

    var showDeleteDialog by remember { mutableStateOf(false) }
    var projectsToDelete by remember { mutableStateOf<List<Project>>(emptyList()) }

    val showingFile = fileContent != null

    // If viewing a file or in selection mode, back button handles exit
    BackHandler(enabled = showingFile || isSelectionMode) {
        if (showingFile) {
            viewModel.closeFile()
        } else if (isSelectionMode) {
            viewModel.exitSelectionMode()
        }
    }

    if (showAddDialog) {
        AddProjectDialog(
            viewModel = viewModel,
            isAdding = isAddingProject,
            errorMessage = addProjectError,
            onDismiss = {
                showAddDialog = false
                viewModel.clearAddProjectError()
            },
            onConfirm = { name, path ->
                viewModel.addNewProject(name, path) {
                    showAddDialog = false
                }
            }
        )
    }

    if (showDeleteDialog && projectsToDelete.isNotEmpty()) {
        DeleteProjectsDialog(
            projects = projectsToDelete,
            isDeleting = isRemovingProjects,
            onDismiss = { showDeleteDialog = false },
            onConfirm = { deleteFiles ->
                viewModel.removeProjects(projectsToDelete.map { it.id }, deleteFiles) {
                    showDeleteDialog = false
                }
            }
        )
    }

    if (showingFile) {
        // Full Screen Code Viewer Overlay with Syntax Highlighting & Markdown Support
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(selectedFileName ?: "", style = AppTypography.appBarTitle, color = TextPrimary) },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.closeFile() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = TextPrimary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = SlateCard)
                )
            },
            containerColor = SlateBg
        ) { paddingValues ->
            CodeViewer(
                fileName = selectedFileName ?: "",
                content = fileContent ?: "",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            )
        }
    } else {
        // Main Projects Screen
        Scaffold(
            topBar = {
                if (isSelectionMode) {
                    // Contextual Multi-Selection Top Bar
                    TopAppBar(
                        title = {
                            Text(
                                text = "${selectedProjectIds.size} seleccionados",
                                style = AppTypography.appBarTitle,
                                color = TextPrimary
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = { viewModel.exitSelectionMode() }) {
                                Icon(Icons.Default.Close, contentDescription = "Cancelar selección", tint = TextPrimary)
                            }
                        },
                        actions = {
                            IconButton(onClick = { viewModel.selectAllProjects() }) {
                                Icon(Icons.Default.SelectAll, contentDescription = "Seleccionar todo", tint = TextPrimary)
                            }
                            IconButton(
                                onClick = {
                                    projectsToDelete = projects.filter { it.id in selectedProjectIds }
                                    showDeleteDialog = true
                                },
                                enabled = selectedProjectIds.isNotEmpty()
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Eliminar seleccionados",
                                    tint = if (selectedProjectIds.isNotEmpty()) StatusError else TextSecondary.copy(alpha = 0.4f)
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = SlateCard)
                    )
                } else {
                    // Standard Top Bar
                    TopAppBar(
                        title = { Text("Proyectos", style = AppTypography.appBarTitle, color = TextPrimary) },
                        navigationIcon = {
                            IconButton(onClick = onMenuClick) {
                                Icon(Icons.Default.Menu, contentDescription = "Menú", tint = TextPrimary)
                            }
                        },
                        actions = {
                            IconButton(onClick = { viewModel.refreshProjects() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refrescar", tint = TextPrimary)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = SlateCard)
                    )
                }
            },
            containerColor = SlateBg
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = AppSpacing.screenPadding),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap)
            ) {
                Spacer(modifier = Modifier.height(AppSpacing.xs))

                // Projects List Section Header with "+ Agregar Ruta" button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("PROYECTOS CONFIGURADOS", style = AppTypography.titleSmall, color = TextSecondary)

                    if (!isSelectionMode) {
                        Button(
                            onClick = { showAddDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo),
                            contentPadding = PaddingValues(horizontal = AppSpacing.md, vertical = AppSpacing.xs),
                            shape = AppShapes.small
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = TextPrimary)
                            Spacer(modifier = Modifier.width(AppSpacing.xs))
                            Text("Agregar Ruta", style = AppTypography.labelSmall, color = TextPrimary)
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier.weight(0.4f),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
                ) {
                    items(projects) { project ->
                        ProjectCard(
                            project = project,
                            isActive = project.id == activeProjectId,
                            isSelectionMode = isSelectionMode,
                            isSelected = project.id in selectedProjectIds,
                            onActivate = { viewModel.selectProject(project.id) },
                            onToggleSelect = { viewModel.toggleProjectSelection(project.id) },
                            onLongClick = { viewModel.enterSelectionMode(project.id) },
                            onDeleteClick = {
                                projectsToDelete = listOf(project)
                                showDeleteDialog = true
                            }
                        )
                    }
                }

                // File Explorer Section
                Column(modifier = Modifier.weight(0.6f)) {
                    Text("ESTRUCTURA DEL PROYECTO SELECCIONADO", style = AppTypography.titleSmall, color = TextSecondary)
                    Spacer(modifier = Modifier.height(AppSpacing.sm))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(AppShapes.card)
                            .background(SlateCard)
                            .border(1.dp, SlateBorder, AppShapes.card)
                    ) {
                        if (projectFiles.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize().padding(AppSpacing.xl), contentAlignment = Alignment.Center) {
                                Text("No hay archivos o seleccionando proyecto...", style = AppTypography.bodySmall, color = TextSecondary)
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize().padding(AppSpacing.md),
                                contentPadding = PaddingValues(bottom = AppSpacing.lg)
                            ) {
                                items(projectFiles) { node ->
                                    FileTreeNodeView(
                                        node = node,
                                        level = 0,
                                        onNodeClick = { clickedNode ->
                                            if (!clickedNode.isDirectory) {
                                                viewModel.openFile(clickedNode.name, clickedNode.path)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DeleteProjectsDialog(
    projects: List<Project>,
    isDeleting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (deleteFiles: Boolean) -> Unit
) {
    var deleteFiles by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SlateCard,
        icon = {
            Icon(Icons.Default.DeleteForever, contentDescription = null, tint = StatusError, modifier = Modifier.size(32.dp))
        },
        title = {
            Text(
                text = if (projects.size == 1) "¿Quitar proyecto?" else "¿Quitar ${projects.size} proyectos?",
                style = AppTypography.titleMedium,
                color = TextPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                Text(
                    text = if (projects.size == 1) {
                        "Estás a punto de quitar '${projects.first().name}' de IDUPI."
                    } else {
                        "Estás a punto de quitar ${projects.size} proyectos de IDUPI."
                    },
                    style = AppTypography.bodySmall,
                    color = TextSecondary
                )

                // Option 1: Safe remove from list (default)
                Surface(
                    color = if (!deleteFiles) PrimaryIndigo.copy(alpha = 0.1f) else SlateBg,
                    shape = AppShapes.small,
                    border = BorderStroke(1.dp, if (!deleteFiles) PrimaryIndigo.copy(alpha = 0.5f) else SlateBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { deleteFiles = false }
                ) {
                    Row(
                        modifier = Modifier.padding(AppSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = !deleteFiles,
                            onClick = { deleteFiles = false },
                            colors = RadioButtonDefaults.colors(selectedColor = PrimaryIndigo)
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.xs))
                        Column {
                            Text("Solo quitar de IDUPI", style = AppTypography.bodySmall, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text("Mantiene tus archivos en la PC intactos (Recomendado)", style = AppTypography.labelSmall, color = StatusConnected)
                        }
                    }
                }

                // Option 2: Physical deletion on PC disk
                Surface(
                    color = if (deleteFiles) StatusError.copy(alpha = 0.1f) else SlateBg,
                    shape = AppShapes.small,
                    border = BorderStroke(1.dp, if (deleteFiles) StatusError.copy(alpha = 0.5f) else SlateBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { deleteFiles = true }
                ) {
                    Row(
                        modifier = Modifier.padding(AppSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = deleteFiles,
                            onClick = { deleteFiles = true },
                            colors = RadioButtonDefaults.colors(selectedColor = StatusError)
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.xs))
                        Column {
                            Text("Eliminar archivos de la PC", style = AppTypography.bodySmall, fontWeight = FontWeight.Bold, color = StatusError)
                            Text("Borra permanentemente la carpeta del disco", style = AppTypography.labelSmall, color = TextSecondary)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(deleteFiles) },
                enabled = !isDeleting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (deleteFiles) StatusError else PrimaryIndigo
                )
            ) {
                if (isDeleting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = TextPrimary, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(AppSpacing.xs))
                    Text("Quitando...", style = AppTypography.labelSmall)
                } else {
                    Text(if (deleteFiles) "Eliminar permanentemente" else "Quitar de IDUPI", style = AppTypography.labelSmall)
                }
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
fun AddProjectDialog(
    viewModel: ProjectsViewModel,
    isAdding: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, path: String) -> Unit
) {
    val browseData by viewModel.browseData.collectAsState()
    val isBrowsing by viewModel.isBrowsing.collectAsState()
    val browseError by viewModel.browseError.collectAsState()

    var projectName by remember { mutableStateOf("") }
    var manualPath by remember { mutableStateOf("") }
    var isManualMode by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.openDirectoryBrowser()
    }

    LaunchedEffect(browseData?.currentPath) {
        val cur = browseData?.currentPath ?: ""
        if (cur.isNotBlank()) {
            val folderName = cur.replace('\\', '/').split('/').filter { it.isNotBlank() }.lastOrNull() ?: ""
            if (folderName.isNotEmpty() && !folderName.contains(':')) {
                projectName = folderName
            }
            manualPath = cur
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SlateCard,
        modifier = Modifier.fillMaxWidth(0.95f),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.FolderSpecial, contentDescription = null, tint = PrimaryIndigo)
                    Spacer(modifier = Modifier.width(AppSpacing.sm))
                    Text("Explorador de PC", style = AppTypography.titleMedium, color = TextPrimary)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = TextSecondary)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (isManualMode) "Modo Manual" else "Navega por tus discos y carpetas:",
                        style = AppTypography.bodySmall,
                        color = TextSecondary
                    )
                    TextButton(
                        onClick = { isManualMode = !isManualMode },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            if (isManualMode) "📁 Explorador" else "⌨️ Escribir Ruta",
                            style = AppTypography.labelSmall,
                            color = AccentPurple
                        )
                    }
                }

                if (!isManualMode) {
                    // 1. Atajos Rápidos (Discos y Carpetas clave)
                    val shortcuts = browseData?.shortcuts ?: emptyList()
                    if (shortcuts.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(shortcuts) { shortcut ->
                                val isSelected = browseData?.currentPath.equals(shortcut.path, ignoreCase = true)
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { viewModel.loadBrowsePath(shortcut.path) },
                                    label = {
                                        Text(
                                            shortcut.name,
                                            style = AppTypography.labelSmall,
                                            color = if (isSelected) TextPrimary else TextSecondary
                                        )
                                    },
                                    leadingIcon = {
                                        val icon = when {
                                            shortcut.path.length <= 3 && shortcut.path.contains(":") -> Icons.Default.Storage
                                            shortcut.isProject -> Icons.Default.Star
                                            else -> Icons.Default.Folder
                                        }
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                            tint = if (isSelected) PrimaryIndigo else TextSecondary
                                        )
                                    }
                                )
                            }
                        }
                    }

                    // 2. Barra de Ruta Actual (Breadcrumb) + Botón Subir Nivel
                    val currentPath = browseData?.currentPath ?: ""
                    val parentPath = browseData?.parentPath

                    Surface(
                        color = SlateBg,
                        shape = AppShapes.small,
                        border = BorderStroke(1.dp, SlateBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (parentPath != null || currentPath.isNotBlank()) {
                                IconButton(
                                    onClick = { viewModel.navigateBrowseUp() },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.ArrowUpward,
                                        contentDescription = "Subir de nivel",
                                        tint = PrimaryIndigo,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(AppSpacing.xs))
                            } else {
                                Icon(
                                    Icons.Default.Computer,
                                    contentDescription = null,
                                    tint = TextSecondary,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .padding(start = 4.dp)
                                )
                                Spacer(modifier = Modifier.width(AppSpacing.sm))
                            }

                            Text(
                                text = if (currentPath.isNotBlank()) currentPath else "Discos de tu PC",
                                style = AppTypography.codeMono,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // 3. Lista Desplazable de Carpetas / Discos
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(AppShapes.small)
                            .background(SlateBg.copy(alpha = 0.5f))
                            .border(1.dp, SlateBorder, AppShapes.small)
                    ) {
                        if (isBrowsing) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp), color = PrimaryIndigo)
                            }
                        } else {
                            val dirs = browseData?.directories ?: emptyList()
                            if (dirs.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(AppSpacing.md),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("No se encontraron carpetas accesibles aquí.", style = AppTypography.bodySmall, color = TextSecondary)
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(AppSpacing.xs),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    items(dirs) { dir ->
                                        val isDrive = dir.path.length <= 3 && dir.path.contains(":")
                                        Surface(
                                            color = SlateCard,
                                            shape = AppShapes.small,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { viewModel.loadBrowsePath(dir.path) }
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = if (isDrive) Icons.Default.Storage else Icons.Default.Folder,
                                                    contentDescription = null,
                                                    tint = if (dir.isProject) StatusConnected else if (isDrive) PrimaryIndigo else AccentPurple,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Spacer(modifier = Modifier.width(AppSpacing.sm))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = dir.name,
                                                        style = AppTypography.bodySmall,
                                                        fontWeight = if (dir.isProject) FontWeight.Bold else FontWeight.Normal,
                                                        color = TextPrimary,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    if (dir.projectType != null) {
                                                        Text(
                                                            text = "⭐ ${dir.projectType}",
                                                            style = AppTypography.labelSmall,
                                                            color = StatusConnected
                                                        )
                                                    }
                                                }
                                                Icon(
                                                    Icons.Default.ChevronRight,
                                                    contentDescription = "Abrir",
                                                    tint = TextSecondary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = manualPath,
                        onValueChange = { manualPath = it },
                        label = { Text("Ruta en PC (ej. C:\\dev\\MiApp)") },
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

                // 4. Nombre del Proyecto a Registrar
                OutlinedTextField(
                    value = projectName,
                    onValueChange = { projectName = it },
                    label = { Text("Nombre del Proyecto") },
                    placeholder = { Text("ej. Mi Web") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = PrimaryIndigo,
                        unfocusedBorderColor = SlateBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (errorMessage != null || browseError != null) {
                    val err = errorMessage ?: browseError ?: ""
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(AppShapes.small)
                            .background(StatusError.copy(alpha = 0.15f))
                            .border(1.dp, StatusError.copy(alpha = 0.4f), AppShapes.small)
                            .padding(AppSpacing.xs)
                    ) {
                        Text(err, style = AppTypography.labelSmall, color = StatusError)
                    }
                }
            }
        },
        confirmButton = {
            val activePath = if (isManualMode) manualPath.trim() else (browseData?.currentPath ?: manualPath).trim()
            val canSubmit = activePath.isNotBlank() && projectName.isNotBlank() && !isAdding

            Button(
                onClick = {
                    if (canSubmit) {
                        onConfirm(projectName.trim(), activePath)
                    }
                },
                enabled = canSubmit,
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo)
            ) {
                if (isAdding) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = TextPrimary, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(AppSpacing.xs))
                    Text("Registrando...", style = AppTypography.labelSmall)
                } else {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(AppSpacing.xs))
                    Text("Registrar Proyecto", style = AppTypography.labelSmall)
                }
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancelar", style = AppTypography.labelSmall, color = TextSecondary)
            }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProjectCard(
    project: Project,
    isActive: Boolean,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onActivate: () -> Unit,
    onToggleSelect: () -> Unit,
    onLongClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (isSelectionMode) onToggleSelect() else onActivate()
                },
                onLongClick = onLongClick
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                PrimaryIndigo.copy(alpha = 0.2f)
            } else if (isActive) {
                PrimaryIndigo.copy(alpha = 0.1f)
            } else {
                SlateCard
            }
        ),
        shape = AppShapes.card,
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) PrimaryIndigo else if (isActive) PrimaryIndigo.copy(alpha = 0.5f) else SlateBorder
        )
    ) {
        Row(
            modifier = Modifier.padding(AppSpacing.cardPadding).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                    colors = CheckboxDefaults.colors(checkedColor = PrimaryIndigo)
                )
                Spacer(modifier = Modifier.width(AppSpacing.xs))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = project.name,
                    style = AppTypography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    color = if (isActive) PrimaryIndigo else TextPrimary
                )
                Spacer(modifier = Modifier.height(AppSpacing.xs))
                Text(
                    text = project.path,
                    style = AppTypography.bodySmall,
                    color = TextSecondary
                )
            }

            if (!isSelectionMode) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                ) {
                    if (isActive) {
                        Box(
                            modifier = Modifier
                                .clip(AppShapes.small)
                                .background(StatusConnected.copy(alpha = 0.1f))
                                .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs)
                        ) {
                            Text("ACTIVO", style = AppTypography.labelSmall, color = StatusConnected)
                        }
                    } else {
                        Text(
                            text = "Activar",
                            style = AppTypography.labelSmall,
                            color = PrimaryIndigo,
                            modifier = Modifier
                                .clickable { onActivate() }
                                .padding(AppSpacing.sm)
                        )
                    }

                    IconButton(
                        onClick = onDeleteClick,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "Quitar proyecto",
                            tint = TextSecondary.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FileTreeNodeView(
    node: FileNode,
    level: Int,
    onNodeClick: (FileNode) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (node.isDirectory) {
                        isExpanded = !isExpanded
                    } else {
                        onNodeClick(node)
                    }
                }
                .padding(vertical = AppSpacing.xs)
                .padding(start = (level * 16).dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (node.isDirectory) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(AppSpacing.xs))
                Icon(
                    imageVector = if (isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                    contentDescription = null,
                    tint = AccentPurple,
                    modifier = Modifier.size(18.dp)
                )
            } else {
                Spacer(modifier = Modifier.width(16.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(AppSpacing.sm))

            Text(
                text = node.name,
                style = AppTypography.bodySmall,
                color = if (node.isDirectory) TextPrimary else TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (node.isDirectory && isExpanded && node.children.isNotEmpty()) {
            node.children.forEach { child ->
                FileTreeNodeView(
                    node = child,
                    level = level + 1,
                    onNodeClick = onNodeClick
                )
            }
        }
    }
}
