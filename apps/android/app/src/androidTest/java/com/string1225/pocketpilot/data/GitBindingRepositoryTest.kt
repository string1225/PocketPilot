package com.string1225.pocketpilot.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.string1225.pocketpilot.integrations.git.GitRemoteTarget
import com.string1225.pocketpilot.integrations.git.JGitProjectRepository
import com.string1225.pocketpilot.security.CredentialIds
import com.string1225.pocketpilot.security.SecureCredentialStore
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GitBindingRepositoryTest {
    @Test
    fun versionFiveGitConfigMigratesWithoutLosingBinding() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "git-binding-migration-${UUID.randomUUID()}.db"
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { legacy ->
            legacy.execSQL(
                """
                CREATE TABLE projects (
                    id TEXT PRIMARY KEY NOT NULL,
                    name TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            legacy.execSQL(
                """
                CREATE TABLE git_config (
                    project_id TEXT PRIMARY KEY NOT NULL,
                    remote_url TEXT,
                    branch TEXT,
                    credential_id TEXT
                )
                """.trimIndent(),
            )
            legacy.execSQL("INSERT INTO projects VALUES('p1', 'Legacy', 1, 1)")
            legacy.execSQL(
                "INSERT INTO git_config VALUES(" +
                    "'p1', 'https://github.com/example/legacy.git', 'develop', 'legacy-token')",
            )
            legacy.version = 5
        }

        val upgraded = PocketPilotDatabase(context, databaseName)
        try {
            upgraded.readableDatabase.rawQuery(
                "SELECT remote_name, remote_url, branch, username, credential_id " +
                    "FROM git_config WHERE project_id = 'p1'",
                null,
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("origin", cursor.getString(0))
                assertEquals("https://github.com/example/legacy.git", cursor.getString(1))
                assertEquals("develop", cursor.getString(2))
                assertEquals("git", cursor.getString(3))
                assertEquals("legacy-token", cursor.getString(4))
            }
        } finally {
            upgraded.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun bindingInitializesRepositoryAndScopesPatToExactProjectUrl() = withFixture { projects, bindings, secrets ->
        val project = projects.create("Bound project")
        File(projects.workspaceDirectory(project.id), "README.md").writeText("hello")
        val token = "project-token".toCharArray()
        try {
            val saved = bindings.save(
                projectId = project.id,
                remoteName = "origin",
                remoteUrl = "https://github.com/example/repository.git",
                branch = "main",
                newToken = token,
            )

            assertTrue(saved.hasCredential)
            assertEquals(CredentialIds.projectGitToken(project.id), saved.credentialId)
            assertEquals("project-token", secrets.peek(checkNotNull(saved.credentialId)))
            assertTrue(File(projects.workspaceDirectory(project.id), ".git").isDirectory)
            assertEquals(
                "https://github.com/example/repository.git",
                JGitProjectRepository(projects.workspaceDirectory(project.id))
                    .configuredRemoteTargets("origin")
                    .single()
                    .url,
            )
            assertNotNull(
                bindings.credentialFor(
                    project.id,
                    listOf(GitRemoteTarget.fromHttpsUrl(saved.remoteUrl)),
                ),
            )
            assertNull(
                bindings.credentialFor(
                    project.id,
                    listOf(GitRemoteTarget.fromHttpsUrl("https://github.com/attacker/repository.git")),
                ),
            )
        } finally {
            token.fill('\u0000')
        }
    }

    @Test
    fun removingPatOrBindingNeverDeletesLocalRepository() = withFixture { projects, bindings, secrets ->
        val project = projects.create("Local history")
        val token = "project-token".toCharArray()
        try {
            bindings.save(
                projectId = project.id,
                remoteName = "origin",
                remoteUrl = "https://github.com/example/repository.git",
                branch = null,
                newToken = token,
            )
        } finally {
            token.fill('\u0000')
        }

        val withoutCredential = requireNotNull(bindings.removeCredential(project.id))
        assertFalse(withoutCredential.hasCredential)
        assertFalse(secrets.contains(CredentialIds.projectGitToken(project.id)))
        assertTrue(File(projects.workspaceDirectory(project.id), ".git").isDirectory)

        bindings.remove(project.id)
        assertNull(bindings.get(project.id))
        assertTrue(File(projects.workspaceDirectory(project.id), ".git").isDirectory)
    }

    private fun withFixture(
        block: (ProjectRepository, GitBindingRepository, MemoryCredentialStore) -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val unique = UUID.randomUUID().toString()
        val databaseName = "git-binding-$unique.db"
        val projectsRoot = File(context.cacheDir, "git-binding-$unique").apply {
            check(mkdirs() || isDirectory)
        }
        try {
            PocketPilotDatabase(context, databaseName).use { database ->
                val projects = ProjectRepository(database, projectsRoot)
                val checkpoints = CheckpointRepository(database, projectsRoot, projects::exists)
                projects.checkpoints = checkpoints
                val secrets = MemoryCredentialStore()
                block(projects, GitBindingRepository(database, projects, secrets), secrets)
            }
        } finally {
            context.deleteDatabase(databaseName)
            deleteProjectsRootNoFollow(projectsRoot)
        }
    }

    private class MemoryCredentialStore : SecureCredentialStore {
        private val values = mutableMapOf<String, CharArray>()

        override fun put(credentialId: String, secret: CharArray) {
            values.remove(credentialId)?.fill('\u0000')
            values[credentialId] = secret.copyOf()
        }

        override fun get(credentialId: String): CharArray? = values[credentialId]?.copyOf()

        override fun contains(credentialId: String): Boolean = credentialId in values

        override fun remove(credentialId: String) {
            values.remove(credentialId)?.fill('\u0000')
        }

        fun peek(credentialId: String): String? = values[credentialId]?.concatToString()
    }
}
