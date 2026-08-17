package com.string1225.pocketpilot.data

import com.string1225.pocketpilot.integrations.git.GitCredentialResolver
import com.string1225.pocketpilot.integrations.git.GitHttpsCredentialRef
import com.string1225.pocketpilot.integrations.git.GitInputPolicy
import com.string1225.pocketpilot.integrations.git.GitRemoteTarget
import com.string1225.pocketpilot.integrations.git.GitTokenCredential
import com.string1225.pocketpilot.integrations.git.JGitProjectRepository
import com.string1225.pocketpilot.model.CheckpointSource
import com.string1225.pocketpilot.model.Project
import com.string1225.pocketpilot.security.CredentialIds
import com.string1225.pocketpilot.security.SecureCredentialStore
import java.io.File

/**
 * Performs the explicit, user-initiated Git setup used by first-run onboarding.
 *
 * The remote is validated before any local mutation. A newly entered PAT stays
 * in memory for the long-running clone and is persisted only after clone plus
 * checkpoint succeeds. Project creation, clone, checkpointing, and a replacement
 * credential are rolled back together when setup fails.
 */
class GitProjectSetupRepository internal constructor(
    private val projects: ProjectRepository,
    private val checkpoints: CheckpointRepository,
    private val credentials: SecureCredentialStore,
    private val repositoryFactory: GitProjectCloneClientFactory,
) {
    constructor(
        projects: ProjectRepository,
        checkpoints: CheckpointRepository,
        credentials: SecureCredentialStore,
    ) : this(
        projects = projects,
        checkpoints = checkpoints,
        credentials = credentials,
        repositoryFactory = GitProjectCloneClientFactory { workspace, resolver, cancellationCheck ->
            JGitProjectCloneClient(
                JGitProjectRepository(
                    workspace = workspace,
                    credentialResolver = resolver,
                    cancellationCheck = cancellationCheck,
                ),
            )
        },
    )

    fun clone(
        projectName: String,
        remoteUrl: String,
        branch: String?,
        username: String,
        useStoredCredential: Boolean,
        newToken: CharArray?,
    ): Project {
        val target = GitRemoteTarget.fromHttpsUrl(remoteUrl)
        val normalizedBranch = branch?.trim()?.takeIf(String::isNotEmpty)
            ?.also(GitInputPolicy::requireBranch)
        val normalizedUsername = username.trim().ifBlank { DEFAULT_GIT_USERNAME }
        val credentialBinding = GitSetupCredentialBinding(
            credentials = credentials,
            username = normalizedUsername,
            target = target,
            useStoredCredential = useStoredCredential,
            newToken = newToken,
        )
        var project: Project? = null
        var repository: GitProjectCloneClient? = null
        var credentialReplacement: GitCredentialReplacement? = null

        try {
            project = projects.beginProvisioning(projectName)
            repository = repositoryFactory.create(
                workspace = projects.workspaceDirectory(project.id),
                credentialResolver = GitCredentialResolver(credentialBinding::resolveToken),
                cancellationCheck = { Thread.currentThread().isInterrupted },
            )
            repository.clone(
                remoteUrl = target.url,
                branch = normalizedBranch,
                credential = credentialBinding.reference,
            )
            checkpoints.create(
                projectId = project.id,
                source = CheckpointSource.GIT,
                description = "从 Git 克隆项目",
            )
            projects.touch(project.id)
            // Clearing the marker commits project validity. Only then may a newly entered
            // process-wide credential reach durable storage.
            projects.completeProvisioning(project.id)
            if (newToken != null) {
                credentialReplacement = GitCredentialReplacement(
                    credentials,
                    CredentialIds.DEFAULT_GIT_TOKEN,
                )
                credentialReplacement.replace(newToken)
            }
            return projects.list().first { it.id == project.id }
        } catch (failure: Throwable) {
            repository?.let { cloneRepository ->
                runCatching { cloneRepository.cleanupFailedCloneOutput() }
                    .exceptionOrNull()
                    ?.let(failure::addSuppressed)
            }
            project?.takeIf { projects.exists(it.id) }?.let { createdProject ->
                runCatching { projects.delete(createdProject.id) }
                    .exceptionOrNull()
                    ?.let(failure::addSuppressed)
            }
            credentialReplacement?.rollbackAfter(failure)
            throw failure
        } finally {
            credentialReplacement?.close()
        }
    }

    private companion object {
        const val DEFAULT_GIT_USERNAME = "git"
    }
}

internal interface GitProjectCloneClient {
    fun clone(remoteUrl: String, branch: String?, credential: GitHttpsCredentialRef?)
    fun cleanupFailedCloneOutput()
}

internal fun interface GitProjectCloneClientFactory {
    fun create(
        workspace: File,
        credentialResolver: GitCredentialResolver,
        cancellationCheck: () -> Boolean,
    ): GitProjectCloneClient
}

private class JGitProjectCloneClient(
    private val repository: JGitProjectRepository,
) : GitProjectCloneClient {
    override fun clone(remoteUrl: String, branch: String?, credential: GitHttpsCredentialRef?) {
        repository.clone(remoteUrl = remoteUrl, branch = branch, credential = credential)
    }

    override fun cleanupFailedCloneOutput() = repository.cleanupFailedCloneOutput()
}

/**
 * Chooses either the stored Git credential or the caller-owned, in-memory token for one setup
 * operation. The ephemeral identifier is intentionally never written to [SecureCredentialStore].
 */
internal class GitSetupCredentialBinding(
    private val credentials: SecureCredentialStore,
    username: String,
    target: GitRemoteTarget,
    useStoredCredential: Boolean,
    private val newToken: CharArray?,
) {
    val reference: GitHttpsCredentialRef?

    init {
        require(newToken == null || newToken.isNotEmpty()) { "Git HTTPS token must not be empty" }
        reference = when {
            newToken != null -> GitHttpsCredentialRef(
                username = username,
                tokenCredentialId = EPHEMERAL_CREDENTIAL_ID,
                allowedRemoteUrls = setOf(target.url),
            )

            useStoredCredential -> {
                require(credentials.contains(CredentialIds.DEFAULT_GIT_TOKEN)) {
                    "A Git HTTPS token is required for credentialed clone"
                }
                GitHttpsCredentialRef(
                    username = username,
                    tokenCredentialId = CredentialIds.DEFAULT_GIT_TOKEN,
                    allowedRemoteUrls = setOf(target.url),
                )
            }

            else -> null
        }
    }

    fun resolveToken(credentialId: String): GitTokenCredential {
        if (credentialId == EPHEMERAL_CREDENTIAL_ID) {
            return GitTokenCredential(checkNotNull(newToken) { "Ephemeral Git credential is unavailable" })
        }
        require(credentialId == CredentialIds.DEFAULT_GIT_TOKEN) {
            "Git credential identifier is not authorized for setup"
        }
        val token = credentials.get(credentialId)
            ?: throw IllegalStateException("Configured Git credential is unavailable")
        return try {
            GitTokenCredential(token)
        } finally {
            token.fill('\u0000')
        }
    }

    internal companion object {
        const val EPHEMERAL_CREDENTIAL_ID = "git.onboarding.ephemeral"
    }
}

/** Transactional guard for replacing a process-wide Git credential. */
internal class GitCredentialReplacement(
    private val credentials: SecureCredentialStore,
    private val credentialId: String,
) : AutoCloseable {
    private val previousToken = credentials.get(credentialId)
    private var mutationAttempted = false

    fun replace(newToken: CharArray) {
        // A durable store can update its backing state and still report failure.
        // Mark the attempt first so that throwing `put` calls are rolled back too.
        mutationAttempted = true
        credentials.put(credentialId, newToken)
    }

    fun rollbackAfter(operationFailure: Throwable) {
        if (!mutationAttempted) return
        val restoreFailure = runCatching {
            if (previousToken == null) {
                credentials.remove(credentialId)
            } else {
                credentials.put(credentialId, previousToken)
            }
        }.exceptionOrNull()
        if (restoreFailure != null) {
            operationFailure.addSuppressed(restoreFailure)
            // If restoring the old token failed, never leave an unknown
            // replacement usable as the process-wide Git credential.
            runCatching { credentials.remove(credentialId) }
                .exceptionOrNull()
                ?.let(operationFailure::addSuppressed)
        }
    }

    override fun close() {
        previousToken?.fill('\u0000')
    }
}
