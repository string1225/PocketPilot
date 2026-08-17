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
class OnboardingSettingsPersistenceTest {
    @Test
    fun freshMarkerRemainsPendingAfterDefaultProjectCreationAndDatabaseReopen() =
        withDatabaseFixture("fresh") { context, databaseName, projectRoot ->
            PocketPilotDatabase(context, databaseName).use { database ->
                val settings = SettingsRepository(database)
                val projects = projectRepository(database, projectRoot)

                settings.initializeOnboardingState(existingProjects = projects.list().isNotEmpty())
                assertEquals("0", settings.get(SettingsRepository.KEY_ONBOARDING_VERSION))
                assertFalse(settings.isOnboardingCompleted())

                projects.ensureDefaultProject()
                assertEquals(1, projects.list().size)
            }

            PocketPilotDatabase(context, databaseName).use { reopened ->
                val settings = SettingsRepository(reopened)
                val projects = projectRepository(reopened, projectRoot)
                // Application repeats the one-time initialization call, but the
                // existing marker—not the newly-created default project—wins.
                settings.initializeOnboardingState(existingProjects = projects.list().isNotEmpty())
                assertEquals("0", settings.get(SettingsRepository.KEY_ONBOARDING_VERSION))
                assertFalse(settings.isOnboardingCompleted())
            }
        }

    @Test
    fun legacyProjectWithoutMarkerIsInitializedAsCompleted() =
        withDatabaseFixture("legacy") { context, databaseName, projectRoot ->
            PocketPilotDatabase(context, databaseName).use { database ->
                val projects = projectRepository(database, projectRoot)
                projects.create("Existing project")
                val settings = SettingsRepository(database)

                settings.initializeOnboardingState(existingProjects = projects.list().isNotEmpty())

                assertTrue(settings.isOnboardingCompleted())
                assertEquals(
                    SettingsRepository.CURRENT_ONBOARDING_VERSION.toString(),
                    settings.get(SettingsRepository.KEY_ONBOARDING_VERSION),
                )
            }
        }

    @Test
    fun projectConfiguredMarkerSurvivesColdDatabaseReopen() =
        withDatabaseFixture("project-step") { context, databaseName, projectRoot ->
            PocketPilotDatabase(context, databaseName).use { database ->
                val settings = SettingsRepository(database)
                val projects = projectRepository(database, projectRoot)
                val project = projects.create("Configured project")
                settings.initializeOnboardingState(existingProjects = false)
                settings.markOnboardingProjectConfigured(project.id, projects)
                assertTrue(settings.isOnboardingProjectConfigured())
            }

            PocketPilotDatabase(context, databaseName).use { reopened ->
                val settings = SettingsRepository(reopened)
                assertTrue(settings.isOnboardingProjectConfigured())
                assertFalse(settings.isOnboardingCompleted())
            }
        }

    private fun projectRepository(database: PocketPilotDatabase, projectRoot: File): ProjectRepository =
        ProjectRepository(database, projectRoot).also { projects ->
            projects.checkpoints = CheckpointRepository(database, projectRoot, projects::exists)
        }

    private fun withDatabaseFixture(
        label: String,
        block: (Context, String, File) -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val unique = UUID.randomUUID().toString()
        val databaseName = "onboarding-$label-$unique.db"
        val projectRoot = File(context.cacheDir, "onboarding-$label-$unique")
        try {
            block(context, databaseName, projectRoot)
        } finally {
            context.deleteDatabase(databaseName)
            deleteProjectsRootNoFollow(projectRoot)
        }
    }
}
