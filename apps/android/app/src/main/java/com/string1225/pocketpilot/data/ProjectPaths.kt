package com.string1225.pocketpilot.data

import java.io.File
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumSet
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

/** Deletes exactly one direct project child without ever following links in its tree. */
internal object ProjectTreeDeleter {
    fun delete(projectsRoot: File, projectId: String) {
        val root = projectsRoot.canonicalFile
        require(root.isDirectory) { "Projects root is unavailable" }
        val projectDirectory = ProjectPaths.projectDirectory(root, projectId)
        val projectPath = projectDirectory.toPath()
        if (!Files.exists(projectPath, LinkOption.NOFOLLOW_LINKS)) return

        // ProjectPaths performs the canonical/direct-child checks. Repeat the link check at the
        // deletion boundary so a raced replacement can never redirect traversal outside root.
        require(!Files.isSymbolicLink(projectPath)) { "Project directory must not be a symbolic link" }
        require(projectDirectory.parentFile == root) { "Project path escapes storage root" }

        Files.walkFileTree(
            projectPath,
            EnumSet.noneOf(FileVisitOption::class.java),
            Int.MAX_VALUE,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    // With FOLLOW_LINKS absent, directory and file links arrive here and only the
                    // directory entry itself is deleted.
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, error: java.io.IOException): FileVisitResult {
                    throw error
                }

                override fun postVisitDirectory(
                    directory: Path,
                    error: java.io.IOException?,
                ): FileVisitResult {
                    if (error != null) throw error
                    Files.delete(directory)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }
}
