package com.string1225.pocketpilot.data

import java.io.File
import java.nio.file.Files
import java.util.UUID

object ProjectPaths {
    fun projectDirectory(projectsRoot: File, projectId: String): File {
        require(UUID.fromString(projectId).toString() == projectId.lowercase()) { "Invalid project id" }
        val root = projectsRoot.canonicalFile
        val unresolved = File(root, projectId)
        require(!Files.isSymbolicLink(unresolved.toPath())) { "Project directory must not be a symbolic link" }
        val directory = unresolved.canonicalFile
        require(directory == unresolved.absoluteFile.normalize()) { "Project directory must not redirect through a link" }
        require(directory.parentFile == root) { "Project path escapes storage root" }
        return directory
    }

    fun workspaceDirectory(projectsRoot: File, projectId: String): File {
        val project = projectDirectory(projectsRoot, projectId)
        val unresolved = File(project, "workspace")
        require(!Files.isSymbolicLink(unresolved.toPath())) { "Project workspace must not be a symbolic link" }
        val workspace = unresolved.canonicalFile
        require(workspace == unresolved.absoluteFile.normalize()) { "Project workspace must not redirect through a link" }
        require(workspace.parentFile == project) { "Project workspace escapes the project directory" }
        require(!workspace.exists() || workspace.isDirectory) { "Project workspace must be a directory" }
        return workspace
    }
}
