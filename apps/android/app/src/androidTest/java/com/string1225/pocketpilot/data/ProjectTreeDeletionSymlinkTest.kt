package com.string1225.pocketpilot.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectTreeDeletionSymlinkTest {
    @Test
    fun projectDeleteUnlinksWorkspaceWithoutDeletingExternalTarget() =
        withSymlinkFixture("delete") { projects, projectsRoot, externalSentinel ->
            val project = projects.create("Linked workspace")
            val projectDirectory = ProjectPaths.projectDirectory(projectsRoot, project.id)
            installWorkspaceLinkOrSkip(
                projectsRoot,
                project.id,
                checkNotNull(externalSentinel.parentFile),
            )

            projects.delete(project.id)

            assertFalse(projects.exists(project.id))
            assertFalse(Files.exists(projectDirectory.toPath(), LinkOption.NOFOLLOW_LINKS))
            assertTrue(externalSentinel.isFile)
            assertEquals("keep", externalSentinel.readText())
        }

    @Test
    fun provisioningRecoveryUnlinksWorkspaceWithoutDeletingExternalTarget() =
        withSymlinkFixture("recovery") { projects, projectsRoot, externalSentinel ->
            val project = projects.beginProvisioning("Interrupted linked clone")
            installWorkspaceLinkOrSkip(projectsRoot, project.id, checkNotNull(externalSentinel.parentFile))

            assertEquals(1, projects.recoverInterruptedProvisioning())

            assertFalse(projects.exists(project.id))
            assertFalse(
                Files.exists(
                    File(projectsRoot, project.id).toPath(),
                    LinkOption.NOFOLLOW_LINKS,
                ),
            )
            assertTrue(externalSentinel.isFile)
            assertEquals("keep", externalSentinel.readText())
        }

    private fun installWorkspaceLinkOrSkip(
        projectsRoot: File,
        projectId: String,
        externalTarget: File,
    ) {
        val workspace = ProjectPaths.workspaceDirectory(projectsRoot, projectId)
        check(workspace.delete()) { "Test workspace must start empty" }
        try {
            Files.createSymbolicLink(workspace.toPath(), externalTarget.toPath())
        } catch (error: Exception) {
            assumeNoException("Symbolic links are unavailable on this device", error)
        }
        assertTrue(Files.isSymbolicLink(workspace.toPath()))
    }

    private fun withSymlinkFixture(
        label: String,
        block: (ProjectRepository, File, File) -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val unique = UUID.randomUUID().toString()
        val databaseName = "project-tree-symlink-$label-$unique.db"
        val projectsRoot = File(context.cacheDir, "project-tree-symlink-$label-$unique").apply {
            check(mkdirs() || isDirectory)
        }
        val externalDirectory = File(context.cacheDir, "project-tree-external-$label-$unique").apply {
            check(mkdirs() || isDirectory)
        }
        val externalSentinel = File(externalDirectory, "keep.txt").apply { writeText("keep") }
        try {
            PocketPilotDatabase(context, databaseName).use { database ->
                val projects = ProjectRepository(database, projectsRoot)
                val checkpoints = CheckpointRepository(database, projectsRoot, projects::exists)
                projects.checkpoints = checkpoints
                block(projects, projectsRoot, externalSentinel)
            }
        } finally {
            context.deleteDatabase(databaseName)
            deleteProjectsRootNoFollow(projectsRoot)
            externalSentinel.delete()
            externalDirectory.delete()
        }
    }
}
