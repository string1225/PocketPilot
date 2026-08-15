package com.string1225.pocketpilot.data

import java.io.File
import java.util.UUID

object ProjectPaths {
    fun projectDirectory(projectsRoot: File, projectId: String): File {
        require(UUID.fromString(projectId).toString() == projectId.lowercase()) { "Invalid project id" }
        val root = projectsRoot.canonicalFile
        val directory = File(root, projectId).canonicalFile
        require(directory.path.startsWith(root.path + File.separator)) { "Project path escapes storage root" }
        return directory
    }

    fun workspaceDirectory(projectsRoot: File, projectId: String): File =
        File(projectDirectory(projectsRoot, projectId), "workspace").canonicalFile
}
