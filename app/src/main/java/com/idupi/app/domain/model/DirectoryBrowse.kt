package com.idupi.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class RemoteDirectoryItem(
    val name: String,
    val path: String,
    val isProject: Boolean = false,
    val projectType: String? = null
)

@Serializable
data class DirectoryBrowseResponse(
    val currentPath: String = "",
    val parentPath: String? = null,
    val shortcuts: List<RemoteDirectoryItem> = emptyList(),
    val directories: List<RemoteDirectoryItem> = emptyList()
)
