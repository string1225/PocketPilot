package com.string1225.pocketpilot.data

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID

/** Test cleanup that preserves the same no-follow boundary as production project deletion. */
internal fun deleteProjectsRootNoFollow(projectsRoot: File) {
    val rootPath = projectsRoot.toPath()
    if (!Files.exists(rootPath, LinkOption.NOFOLLOW_LINKS)) return
    require(!Files.isSymbolicLink(rootPath)) { "Test projects root must not be a symbolic link" }
    val entries = projectsRoot.listFiles().orEmpty()
    val markers = entries.filter { child ->
        ProjectRepository.projectIdFromProvisioningMarker(child.name) != null
    }
    entries.filter { child ->
        ProjectRepository.projectIdFromProvisioningMarker(child.name) == null
    }.forEach { child ->
        require(runCatching { UUID.fromString(child.name).toString() == child.name }.getOrDefault(false)) {
            "Unexpected entry in test projects root: ${child.name}"
        }
        if (Files.isSymbolicLink(child.toPath())) {
            Files.delete(child.toPath())
        } else {
            ProjectTreeDeleter.delete(projectsRoot, child.name)
        }
    }
    markers.forEach { marker ->
        // Test cleanup may unlink an unsafe marker, but never follows its target.
        Files.delete(marker.toPath())
    }
    Files.delete(rootPath)
}
