package com.string1225.pocketpilot.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.string1225.pocketpilot.integrations.git.GitHttpsCredentialRef
import com.string1225.pocketpilot.integrations.git.GitInputPolicy
import com.string1225.pocketpilot.integrations.git.GitRemoteTarget
import com.string1225.pocketpilot.integrations.git.JGitProjectRepository
import com.string1225.pocketpilot.model.ProjectGitBinding
import com.string1225.pocketpilot.security.CredentialIds
import com.string1225.pocketpilot.security.SecureCredentialStore

/** Persists one host-bound HTTPS Git configuration and one encrypted token per project. */
class GitBindingRepository(
    private val database: PocketPilotDatabase,
    private val projects: ProjectRepository,
    private val credentials: SecureCredentialStore,
) {
    fun get(projectId: String): ProjectGitBinding? {
        require(projects.exists(projectId)) { "Project does not exist" }
        return database.readableDatabase.query(
            "git_config",
            arrayOf("remote_name", "remote_url", "branch", "username", "credential_id"),
            "project_id = ?",
            arrayOf(projectId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val remoteUrl = cursor.getString(1)?.trim().orEmpty()
            if (remoteUrl.isEmpty()) return@use null
            val credentialId = cursor.getString(4)?.takeIf(String::isNotBlank)
            ProjectGitBinding(
                projectId = projectId,
                remoteName = cursor.getString(0)?.takeIf(String::isNotBlank) ?: DEFAULT_REMOTE_NAME,
                remoteUrl = remoteUrl,
                branch = cursor.getString(2)?.takeIf(String::isNotBlank),
                username = cursor.getString(3)?.takeIf(String::isNotBlank) ?: DEFAULT_USERNAME,
                credentialId = credentialId,
                hasCredential = credentialId?.let(credentials::contains) == true,
            )
        }
    }

    /**
     * Binds the repository configuration before publishing the SQLite row. A token write is not
     * usable by the Agent unless that row exists and its exact URL matches the effective remote.
     */
    fun save(
        projectId: String,
        remoteName: String,
        remoteUrl: String,
        branch: String?,
        username: String = DEFAULT_USERNAME,
        newToken: CharArray? = null,
    ): ProjectGitBinding {
        projects.requireProvisioned(projectId)
        val normalizedName = remoteName.trim().also(GitInputPolicy::requireRemoteName)
        val target = GitRemoteTarget.fromHttpsUrl(remoteUrl.trim())
        val normalizedBranch = branch?.trim()?.takeIf(String::isNotEmpty)
            ?.also(GitInputPolicy::requireBranch)
        val normalizedUsername = username.trim().ifBlank { DEFAULT_USERNAME }
        GitHttpsCredentialRef(
            username = normalizedUsername,
            tokenCredentialId = CredentialIds.projectGitToken(projectId),
            allowedRemoteUrls = setOf(target.url),
        )
        require(newToken == null || newToken.isNotEmpty()) { "Personal access token must not be empty" }

        val previous = get(projectId)
        val credentialId = CredentialIds.projectGitToken(projectId)
        val replacement = newToken?.let { GitCredentialReplacement(credentials, credentialId) }
        val repository = JGitProjectRepository(projects.workspaceDirectory(projectId))
        try {
            newToken?.let { replacement?.replace(it) }
            repository.bindRemote(
                remoteName = normalizedName,
                remoteUrl = target.url,
                initialBranch = normalizedBranch ?: DEFAULT_INITIAL_BRANCH,
            )
            if (previous != null && previous.remoteName != normalizedName) {
                repository.removeRemote(previous.remoteName)
            }

            val values = ContentValues().apply {
                put("project_id", projectId)
                put("remote_name", normalizedName)
                put("remote_url", target.url)
                if (normalizedBranch == null) putNull("branch") else put("branch", normalizedBranch)
                put("username", normalizedUsername)
                if (newToken != null || previous?.hasCredential == true) {
                    put("credential_id", credentialId)
                } else {
                    putNull("credential_id")
                }
            }
            database.writableDatabase.transaction {
                insertWithOnConflict(
                    "git_config",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE,
                ).also { rowId -> check(rowId != -1L) { "Git binding could not be saved" } }
                projects.touch(projectId)
            }
        } catch (failure: Throwable) {
            replacement?.rollbackAfter(failure)
            rollbackRemote(repository, previous, normalizedName, failure)
            throw failure
        } finally {
            replacement?.close()
        }
        return requireNotNull(get(projectId)) { "Git binding could not be loaded" }
    }

    /** Stops PocketPilot from releasing the token; the local .git history is intentionally kept. */
    fun remove(projectId: String) {
        val binding = get(projectId) ?: return
        binding.credentialId?.let(credentials::remove)
        database.writableDatabase.delete("git_config", "project_id = ?", arrayOf(projectId))
        projects.touch(projectId)
    }

    fun removeCredential(projectId: String): ProjectGitBinding? {
        val binding = get(projectId) ?: return null
        binding.credentialId?.let(credentials::remove)
        database.writableDatabase.transaction {
            val values = ContentValues().apply { putNull("credential_id") }
            update("git_config", values, "project_id = ?", arrayOf(projectId))
        }
        return get(projectId)
    }

    fun deleteCredentialForProject(projectId: String) {
        val credentialId = runCatching { CredentialIds.projectGitToken(projectId) }.getOrNull() ?: return
        if (credentials.contains(credentialId)) credentials.remove(credentialId)
    }

    /** Returns a credential only when every effective Git URL is the exact bound repository URL. */
    fun credentialFor(
        projectId: String,
        targets: Collection<GitRemoteTarget>,
    ): GitHttpsCredentialRef? {
        val binding = get(projectId) ?: return null
        val credentialId = binding.credentialId?.takeIf { binding.hasCredential } ?: return null
        val boundTarget = GitRemoteTarget.fromHttpsUrl(binding.remoteUrl)
        if (targets.isEmpty() || targets.any { !boundTarget.sameLocation(it) || it.url != boundTarget.url }) {
            return null
        }
        return GitHttpsCredentialRef(
            username = binding.username,
            tokenCredentialId = credentialId,
            allowedRemoteUrls = setOf(binding.remoteUrl),
        )
    }

    private fun rollbackRemote(
        repository: JGitProjectRepository,
        previous: ProjectGitBinding?,
        attemptedRemoteName: String,
        operationFailure: Throwable,
    ) {
        runCatching {
            if (previous == null) {
                repository.removeRemote(attemptedRemoteName)
            } else {
                repository.bindRemote(
                    remoteName = previous.remoteName,
                    remoteUrl = previous.remoteUrl,
                    initialBranch = previous.branch ?: DEFAULT_INITIAL_BRANCH,
                )
                if (previous.remoteName != attemptedRemoteName) {
                    runCatching { repository.removeRemote(attemptedRemoteName) }
                }
            }
        }.exceptionOrNull()?.let(operationFailure::addSuppressed)
    }

    private inline fun <T> SQLiteDatabase.transaction(block: SQLiteDatabase.() -> T): T {
        beginTransaction()
        return try {
            block().also { setTransactionSuccessful() }
        } finally {
            endTransaction()
        }
    }

    companion object {
        const val DEFAULT_REMOTE_NAME = "origin"
        const val DEFAULT_USERNAME = "git"
        private const val DEFAULT_INITIAL_BRANCH = "main"
    }
}
