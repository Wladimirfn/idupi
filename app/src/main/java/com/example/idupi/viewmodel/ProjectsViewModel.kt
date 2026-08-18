package com.example.idupi.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.idupi.data.IduPiClientProvider
import com.example.idupi.domain.model.FileNode
import com.example.idupi.domain.model.Project
import com.example.idupi.domain.repository.IduPiClientSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ProjectsViewModel(
    private val clientSource: IduPiClientSource = IduPiClientProvider
) : ViewModel() {

    private val client get() = clientSource.client

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects = _projects.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    // File Tree Explorer states
    private val _projectFiles = MutableStateFlow<List<FileNode>>(emptyList())
    val projectFiles = _projectFiles.asStateFlow()

    private val _activeProjectId = MutableStateFlow<String>("idupi")
    val activeProjectId = _activeProjectId.asStateFlow()

    private val _selectedFileContent = MutableStateFlow<String?>(null)
    val selectedFileContent = _selectedFileContent.asStateFlow()

    private val _selectedFileName = MutableStateFlow<String?>(null)
    val selectedFileName = _selectedFileName.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun clearError() {
        _errorMessage.value = null
    }

    init {
        refreshProjects()
        loadProjectFiles("idupi")
    }

    private val _addProjectError = MutableStateFlow<String?>(null)
    val addProjectError = _addProjectError.asStateFlow()

    private val _isAddingProject = MutableStateFlow(false)
    val isAddingProject = _isAddingProject.asStateFlow()

    // Remote Directory Browser states
    private val _browseData = MutableStateFlow<com.example.idupi.domain.model.DirectoryBrowseResponse?>(null)
    val browseData = _browseData.asStateFlow()

    private val _isBrowsing = MutableStateFlow(false)
    val isBrowsing = _isBrowsing.asStateFlow()

    private val _browseError = MutableStateFlow<String?>(null)
    val browseError = _browseError.asStateFlow()

    fun openDirectoryBrowser(initialPath: String? = null) {
        loadBrowsePath(initialPath)
    }

    fun loadBrowsePath(path: String? = null) {
        viewModelScope.launch {
            _isBrowsing.value = true
            _browseError.value = null
            try {
                _browseData.value = client.browseDirectory(path)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to browse directory path: $path", e)
                _browseError.value = "No se pudo explorar la carpeta: ${e.localizedMessage}"
            } finally {
                _isBrowsing.value = false
            }
        }
    }

    fun navigateBrowseUp() {
        val parent = _browseData.value?.parentPath
        loadBrowsePath(parent)
    }

    fun addNewProject(name: String, path: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _isAddingProject.value = true
            _addProjectError.value = null
            try {
                client.addProject(name, path)
                _isAddingProject.value = false
                refreshProjects()
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to add project $name", e)
                _isAddingProject.value = false
                _addProjectError.value = "No se pudo agregar el proyecto: ${e.localizedMessage}"
            }
        }
    }

    // Multi-selection & Project Deletion states
    private val _selectedProjectIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedProjectIds = _selectedProjectIds.asStateFlow()

    private val _isSelectionMode = MutableStateFlow(false)
    val isSelectionMode = _isSelectionMode.asStateFlow()

    private val _isRemovingProjects = MutableStateFlow(false)
    val isRemovingProjects = _isRemovingProjects.asStateFlow()

    fun toggleProjectSelection(projectId: String) {
        val current = _selectedProjectIds.value.toMutableSet()
        if (current.contains(projectId)) {
            current.remove(projectId)
            if (current.isEmpty()) {
                _isSelectionMode.value = false
            }
        } else {
            current.add(projectId)
            _isSelectionMode.value = true
        }
        _selectedProjectIds.value = current
    }

    fun selectAllProjects() {
        val allIds = _projects.value.map { it.id }.toSet()
        _selectedProjectIds.value = allIds
        if (allIds.isNotEmpty()) {
            _isSelectionMode.value = true
        }
    }

    fun clearSelection() {
        _selectedProjectIds.value = emptySet()
        _isSelectionMode.value = false
    }

    fun enterSelectionMode(initialId: String? = null) {
        _isSelectionMode.value = true
        if (initialId != null) {
            _selectedProjectIds.value = setOf(initialId)
        }
    }

    fun exitSelectionMode() {
        clearSelection()
    }

    fun removeProjects(projectIds: List<String>, deleteFiles: Boolean = false, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            _isRemovingProjects.value = true
            try {
                val ok = client.removeProjects(projectIds, deleteFiles)
                if (ok) {
                    clearSelection()
                    refreshProjects()
                    onSuccess()
                } else {
                    _errorMessage.value = "No se pudieron eliminar los proyectos seleccionados"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove projects $projectIds", e)
                _errorMessage.value = "Error al eliminar proyecto: ${e.localizedMessage}"
            } finally {
                _isRemovingProjects.value = false
            }
        }
    }

    fun clearAddProjectError() {
        _addProjectError.value = null
    }

    fun refreshProjects() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _projects.value = client.getProjects()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load projects", e)
                _errorMessage.value = "No se pudieron cargar los proyectos: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadProjectFiles(projectId: String) {
        viewModelScope.launch {
            try {
                _projectFiles.value = client.getProjectFiles(projectId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load project files for $projectId", e)
                _projectFiles.value = emptyList()
                _errorMessage.value = "No se pudieron cargar los archivos del proyecto: ${e.localizedMessage}"
            }
        }
    }

    fun selectProject(projectId: String) {
        viewModelScope.launch {
            _activeProjectId.value = projectId
            try {
                client.selectProject(projectId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to select project $projectId", e)
                _errorMessage.value = "No se pudo seleccionar el proyecto: ${e.localizedMessage}"
            }
            refreshProjects()
            loadProjectFiles(projectId)
        }
    }

    fun openFile(fileName: String, path: String) {
        viewModelScope.launch {
            _selectedFileName.value = fileName
            _selectedFileContent.value = "Cargando archivo desde tu PC..."
            try {
                _selectedFileContent.value = client.getFileContent(_activeProjectId.value, path)
            } catch (e: Exception) {
                _selectedFileContent.value = "// Error al cargar archivo: ${e.localizedMessage}"
            }
        }
    }

    fun closeFile() {
        _selectedFileContent.value = null
        _selectedFileName.value = null
    }

    private fun getMockCodeContent(fileName: String, path: String): String {
        return when (fileName) {
            "index.ts" -> """
// IDUPI - Active Bridge Server Core Entrypoint
import { AgentRouter } from "./agent-router";
import { localServer } from "./local-server";

console.log("[SYSTEM] Starting IDUPI active controller...");

const port = process.env.PORT || 8787;
localServer.listen(port, () => {
    console.log(`[SYSTEM] Server listening securely on port ${"$"}{port}`);
});
            """.trimIndent()
            
            "database.ts" -> """
// RCM Database Connection Pool
import { Pool } from "pg";

console.log("[DB] Connecting to PostgreSQL on port 5432...");

export const dbPool = new Pool({
    host: "localhost",
    user: "rcm_user",
    database: "rcm_metrics",
    password: process.env.DB_PASSWORD,
    port: 5432,
    max: 20
});
            """.trimIndent()
            
            "auth.ts" -> """
// JWT Handshake Verification Middleware
import { Request, Response, NextFunction } from "express";

export function secureHandshake(req: Request, res: Response, next: NextFunction) {
    const authHeader = req.headers.authorization;
    if (!authHeader || !authHeader.startsWith("Bearer ")) {
        return res.status(401).json({ error: "Unauthorized handshakes rejected." });
    }
    next();
}
            """.trimIndent()
            
            "rcm.test.ts" -> """
// RCM Unit Tests Suite
import { dbPool } from "../src/database";

describe("RCM Metrics Connection Integrity", () => {
    it("should handshake cleanly with postgres pool", async () => {
        const client = await dbPool.connect();
        const res = await client.query("SELECT NOW()");
        expect(res.rows[0]).toBeDefined();
        client.release();
    });
});
            """.trimIndent()
            
            "server.js" -> """
// IndustrialHub Express Server Gateway
const express = require('express');
const app = express();

app.get('/api/v1/status', (req, res) => {
    res.json({ status: "Connected", agents: ["IndustrialGateway"] });
});

app.listen(8080);
            """.trimIndent()

            "main.py" -> """
# Python IDUBOX Core Engine
import sys
import time

print("Loading IDUBOX components...")
time.sleep(0.5)
print("Ready.")
            """.trimIndent()

            "config.json" -> """
{
  "project": "IDUBOX",
  "version": "2.4.0",
  "local_ai": {
    "enabled": true,
    "model": "Gemma-2b-int4",
    "threads": 4
  }
}
            """.trimIndent()

            else -> """
// Context file: $fileName
// Location: $path

console.log("Viewing mock content for active file $fileName.");
            """.trimIndent()
        }
    }

    companion object {
        private const val TAG = "ProjectsViewModel"
    }
}
