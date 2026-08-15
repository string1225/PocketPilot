package com.string1225.pocketpilot.data

import java.io.File
import java.util.UUID
import java.nio.file.Files
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProjectPathsTest {
    @Test
    fun acceptsUuidAndKeepsProjectBelowRoot() {
        val root = File(System.getProperty("java.io.tmpdir"), "pocketpilot-projects")
        val id = UUID.randomUUID().toString()
        val project = ProjectPaths.projectDirectory(root, id)
        assertTrue(project.path.startsWith(root.canonicalPath + File.separator))
    }

    @Test
    fun rejectsUntrustedProjectIds() {
        val root = File(System.getProperty("java.io.tmpdir"), "pocketpilot-projects")
        assertFailsWith<IllegalArgumentException> { ProjectPaths.projectDirectory(root, "../outside") }
    }

    @Test
    fun rejectsWorkspaceSymlinkEvenWhenTargetIsInsideProjectRoot() {
        val root = Files.createTempDirectory("pocketpilot-project-paths").toFile()
        val external = Files.createTempDirectory("pocketpilot-workspace-target").toFile()
        val id = UUID.randomUUID().toString()
        val project = File(root, id).apply { mkdirs() }
        val workspaceLink = File(project, "workspace")
        try {
            val created = runCatching {
                Files.createSymbolicLink(workspaceLink.toPath(), external.toPath())
            }.isSuccess
            assumeTrue("Symbolic links are unavailable on this test host", created)

            assertFailsWith<IllegalArgumentException> {
                ProjectPaths.workspaceDirectory(root, id)
            }
        } finally {
            Files.deleteIfExists(workspaceLink.toPath())
            root.deleteRecursively()
            external.deleteRecursively()
        }
    }
}
