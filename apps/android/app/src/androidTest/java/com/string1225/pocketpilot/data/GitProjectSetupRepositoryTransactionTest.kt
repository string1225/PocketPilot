package com.string1225.pocketpilot.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.string1225.pocketpilot.integrations.git.GitHttpsCredentialRef
import com.string1225.pocketpilot.model.CheckpointSource
import com.string1225.pocketpilot.security.CredentialIds
import com.string1225.pocketpilot.security.SecureCredentialStore
import java.io.File
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GitProjectSetupRepositoryTransactionTest {
    @Test
    fun newTokenIsEphemeralUntilCloneCheckpointAndProjectCompletion() =
        withFixture("success") { database, projects, checkpoints, projectsRoot ->
            val credentials = RecordingCredentialStore("old-token")
            val gitBindings = GitBindingRepository(database, projects, credentials)
            val newToken = "new-token".toCharArray()
            try {
                val factory = GitProjectCloneClientFactory { workspace, resolver, _ ->
                    FakeCloneClient(
                        onClone = { credential ->
                            assertTrue(projects.hasProvisioningMarker(checkNotNull(workspace.parentFile).name))
                            assertEquals(emptyList<String>(), credentials.operations)
                            val resolved = resolver.resolveToken(checkNotNull(credential).tokenCredentialId).use {
                                it.copyValue()
                            }
                            try {
                                assertArrayEquals(newToken, resolved)
                            } finally {
                                resolved.fill('\u0000')
                            }
                            File(workspace, "README.md").writeText("ready")
                        },
                    )
                }
                val setup = GitProjectSetupRepository(projects, checkpoints, credentials, gitBindings, factory)
                credentials.beforePut = {
                    val candidate = projects.list().single()
                    assertFalse(projects.hasProvisioningMarker(candidate.id))
                    assertTrue(
                        checkpoints.list(candidate.id).any { checkpoint ->
                            checkpoint.source == CheckpointSource.GIT
                        },
                    )
                }

                val project = setup.clone(
                    projectName = "Cloned project",
                    remoteUrl = "https://github.com/example/project.git",
                    branch = "main",
                    username = "pilot",
                    useStoredCredential = false,
                    newToken = newToken,
                )

                projects.requireProvisioned(project.id)
                assertTrue("put" in credentials.operations)
                assertEquals("old-token", credentials.peek(CredentialIds.DEFAULT_GIT_TOKEN))
                assertEquals("new-token", credentials.peek(CredentialIds.projectGitToken(project.id)))
                assertEquals(project.id, gitBindings.get(project.id)?.projectId)
                assertTrue(File(projectsRoot, "${project.id}/workspace/README.md").isFile)
            } finally {
                newToken.fill('\u0000')
            }
        }

    @Test
    fun failedCloneLeavesDurableTokenAndProjectListUntouched() =
        withFixture("failure") { database, projects, checkpoints, projectsRoot ->
            val credentials = RecordingCredentialStore("old-token")
            val gitBindings = GitBindingRepository(database, projects, credentials)
            var cleanupCalled = false
            val setup = GitProjectSetupRepository(
                projects,
                checkpoints,
                credentials,
                gitBindings,
                GitProjectCloneClientFactory { workspace, _, _ ->
                    FakeCloneClient(
                        onClone = {
                            assertTrue(projects.hasProvisioningMarker(checkNotNull(workspace.parentFile).name))
                            assertEquals(emptyList<String>(), credentials.operations)
                            throw IllegalStateException("simulated clone failure")
                        },
                        onCleanup = { cleanupCalled = true },
                    )
                },
            )
            val token = "candidate-token".toCharArray()
            try {
                val failure = runCatching {
                    setup.clone(
                        projectName = "Failed clone",
                        remoteUrl = "https://github.com/example/project.git",
                        branch = null,
                        username = "pilot",
                        useStoredCredential = false,
                        newToken = token,
                    )
                }.exceptionOrNull()

                assertTrue(failure is IllegalStateException)
                assertTrue(cleanupCalled)
                assertFalse("put" in credentials.operations)
                assertEquals("old-token", credentials.peek(CredentialIds.DEFAULT_GIT_TOKEN))
                assertTrue(projects.list().isEmpty())
                assertTrue(projectsRoot.listFiles().orEmpty().isEmpty())
            } finally {
                token.fill('\u0000')
            }
        }

    private class FakeCloneClient(
        private val onClone: (GitHttpsCredentialRef?) -> Unit,
        private val onCleanup: () -> Unit = {},
    ) : GitProjectCloneClient {
        override fun clone(remoteUrl: String, branch: String?, credential: GitHttpsCredentialRef?) {
            onClone(credential)
        }

        override fun cleanupFailedCloneOutput() = onCleanup()
    }

    private class RecordingCredentialStore(initialToken: String) : SecureCredentialStore {
        private val values = mutableMapOf(
            CredentialIds.DEFAULT_GIT_TOKEN to initialToken.toCharArray(),
        )
        val operations = mutableListOf<String>()
        var beforePut: (() -> Unit)? = null

        override fun put(credentialId: String, secret: CharArray) {
            operations += "put"
            beforePut.also { beforePut = null }?.invoke()
            values.remove(credentialId)?.fill('\u0000')
            values[credentialId] = secret.copyOf()
        }

        override fun get(credentialId: String): CharArray? {
            operations += "get"
            return values[credentialId]?.copyOf()
        }

        override fun contains(credentialId: String): Boolean {
            operations += "contains"
            return credentialId in values
        }

        override fun remove(credentialId: String) {
            operations += "remove"
            values.remove(credentialId)?.fill('\u0000')
        }

        fun peek(credentialId: String): String? = values[credentialId]?.concatToString()
    }

    private fun withFixture(
        label: String,
        block: (PocketPilotDatabase, ProjectRepository, CheckpointRepository, File) -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val unique = UUID.randomUUID().toString()
        val databaseName = "git-project-setup-$label-$unique.db"
        val projectsRoot = File(context.cacheDir, "git-project-setup-$label-$unique").apply {
            check(mkdirs() || isDirectory)
        }
        try {
            PocketPilotDatabase(context, databaseName).use { database ->
                val projects = ProjectRepository(database, projectsRoot)
                val checkpoints = CheckpointRepository(database, projectsRoot, projects::exists)
                projects.checkpoints = checkpoints
                block(database, projects, checkpoints, projectsRoot)
            }
        } finally {
            context.deleteDatabase(databaseName)
            deleteProjectsRootNoFollow(projectsRoot)
        }
    }
}
