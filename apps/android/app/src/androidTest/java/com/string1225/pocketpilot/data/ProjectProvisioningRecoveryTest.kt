package com.string1225.pocketpilot.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectProvisioningRecoveryTest {
    @Test
    fun interruptedProvisioningIsRemovedBeforeFreshInstallInference() =
        withDatabaseFixture("interrupted") { context, databaseName, projectsRoot ->
            lateinit var interruptedProjectId: String
            PocketPilotDatabase(context, databaseName).use { database ->
                val projects = projectRepository(database, projectsRoot)
                val settings = SettingsRepository(database)
                settings.initializeOnboardingState(existingProjects = false)

                val project = projects.beginProvisioning("Interrupted clone")
                interruptedProjectId = project.id
                assertTrue(projects.exists(project.id))
                assertTrue(projects.hasProvisioningMarker(project.id))
                assertTrue(
                    runCatching {
                        settings.markOnboardingProjectConfigured(project.id, projects)
                    }.exceptionOrNull() is IllegalArgumentException,
                )
                assertFalse(settings.isOnboardingProjectConfigured())
            }

            PocketPilotDatabase(context, databaseName).use { reopened ->
                val projects = projectRepository(reopened, projectsRoot)
                val recovered = projects.recoverInterruptedProvisioning()
                val settings = SettingsRepository(reopened)
                // Mirrors Application startup: recovery is complete before this inference.
                settings.initializeOnboardingState(existingProjects = projects.list().isNotEmpty())

                assertEquals(1, recovered)
                assertFalse(projects.exists(interruptedProjectId))
                assertFalse(File(projectsRoot, interruptedProjectId).exists())
                assertFalse(settings.isOnboardingCompleted())
                assertFalse(settings.isOnboardingProjectConfigured())
            }
        }

    @Test
    fun completedProjectClearsMarkerAndSurvivesRecovery() =
        withDatabaseFixture("completed") { context, databaseName, projectsRoot ->
            PocketPilotDatabase(context, databaseName).use { database ->
                val projects = projectRepository(database, projectsRoot)
                val project = projects.beginProvisioning("Completed clone")

                projects.completeProvisioning(project.id)

                assertFalse(projects.hasProvisioningMarker(project.id))
                assertEquals(0, projects.recoverInterruptedProvisioning())
                assertTrue(projects.exists(project.id))
                projects.requireProvisioned(project.id)
            }
        }

    @Test
    fun orphanMarkerCreatedBeforeDatabaseInsertIsRecovered() =
        withDatabaseFixture("orphan") { context, databaseName, projectsRoot ->
            PocketPilotDatabase(context, databaseName).use { database ->
                val projects = projectRepository(database, projectsRoot)
                val orphanId = UUID.randomUUID().toString()
                val orphanDirectory = File(projectsRoot, orphanId)
                val orphanMarker = File(
                    projectsRoot,
                    "${ProjectRepository.PROVISIONING_MARKER_PREFIX}$orphanId",
                )
                assertTrue(orphanMarker.createNewFile())
                assertFalse(orphanDirectory.exists())

                assertEquals(1, projects.recoverInterruptedProvisioning())
                assertFalse(orphanDirectory.exists())
                assertFalse(orphanMarker.exists())
                assertTrue(projects.list().isEmpty())
            }
        }

    @Test
    fun recoveryInterruptedAfterTreeDeletionIsCompletedByNextRecovery() =
        withDatabaseFixture("retry") { context, databaseName, projectsRoot ->
            PocketPilotDatabase(context, databaseName).use { database ->
                var interruptOnce = true
                val projects = projectRepository(
                    database,
                    projectsRoot,
                    ProjectProvisioningRecoveryHook {
                        if (interruptOnce) {
                            interruptOnce = false
                            throw IllegalStateException("simulated process interruption")
                        }
                    },
                )
                val project = projects.beginProvisioning("Interrupted recovery")
                File(projects.workspaceDirectory(project.id), "partial.txt").writeText("partial")

                val firstFailure = runCatching {
                    projects.recoverInterruptedProvisioning()
                }.exceptionOrNull()

                assertTrue(firstFailure is IllegalStateException)
                assertFalse(File(projectsRoot, project.id).exists())
                assertTrue(projects.exists(project.id))
                assertTrue(projects.hasProvisioningMarker(project.id))

                val retryingProjects = projectRepository(database, projectsRoot)
                assertEquals(1, retryingProjects.recoverInterruptedProvisioning())
                assertFalse(retryingProjects.exists(project.id))
                assertFalse(retryingProjects.hasProvisioningMarker(project.id))
                assertFalse(File(projectsRoot, project.id).exists())
            }
        }

    private fun projectRepository(
        database: PocketPilotDatabase,
        projectsRoot: File,
        recoveryHook: ProjectProvisioningRecoveryHook? = null,
    ): ProjectRepository = (recoveryHook?.let {
        ProjectRepository(database, projectsRoot, it)
    } ?: ProjectRepository(database, projectsRoot)).also { projects ->
        projects.checkpoints = CheckpointRepository(database, projectsRoot, projects::exists)
    }

    private fun withDatabaseFixture(
        label: String,
        block: (Context, String, File) -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val unique = UUID.randomUUID().toString()
        val databaseName = "project-provisioning-$label-$unique.db"
        val projectsRoot = File(context.cacheDir, "project-provisioning-$label-$unique").apply {
            check(mkdirs() || isDirectory)
        }
        try {
            block(context, databaseName, projectsRoot)
        } finally {
            context.deleteDatabase(databaseName)
            deleteProjectsRootNoFollow(projectsRoot)
        }
    }
}
