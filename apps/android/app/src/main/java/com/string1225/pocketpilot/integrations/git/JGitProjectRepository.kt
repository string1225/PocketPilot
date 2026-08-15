package com.string1225.pocketpilot.integrations.git

import java.io.File
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.CancellationException
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.TransportConfigCallback
import org.eclipse.jgit.api.errors.NoHeadException
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.transport.Transport

/**
 * A JGit-backed repository permanently scoped to one project workspace.
 *
 * Callers cannot pass filesystem paths to individual operations. The repository refuses linked
 * worktrees and external git directories so no Git command can be redirected outside the bound
 * workspace.
 */
class JGitProjectRepository(
    workspace: File,
    private val credentialResolver: GitCredentialResolver? = null,
    private val cancellationCheck: () -> Boolean = { false },
) {
    private val guard = GitWorkspaceGuard(workspace)
    private val progressMonitor = CancellationProgressMonitor(cancellationCheck)

    fun init(initialBranch: String = "main"): GitInitResult {
        GitInputPolicy.requireBranch(initialBranch)
        require(!File(guard.root, ".git").exists()) { "Project workspace is already a Git repository" }
        checkCancellation()

        Git.init()
            .setDirectory(guard.root)
            .setInitialBranch(initialBranch)
            .call()
            .use { git ->
                guard.verifyOpenedRepository(git.repository)
                disableRepositoryHooks(git.repository)
            }
        return GitInitResult(branch = initialBranch)
    }

    fun clone(
        remoteUrl: String,
        branch: String? = null,
        credential: GitHttpsCredentialRef? = null,
        timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    ): GitCloneResult {
        val target = GitRemoteTarget.fromHttpsUrl(remoteUrl)
        branch?.let(GitInputPolicy::requireBranch)
        GitInputPolicy.requireTimeoutSeconds(timeoutSeconds)
        guard.requireEmptyForClone()
        credential?.requireAllows(listOf(target))
        checkCancellation()

        return runCancellable {
            withCredentials(credential) { credentialsProvider ->
                    val command = Git.cloneRepository()
                        .setURI(remoteUrl)
                        .setDirectory(guard.root)
                        .setCloneSubmodules(false)
                        .setTimeout(timeoutSeconds)
                        .setProgressMonitor(progressMonitor)
                        .setTransportConfigCallback(ApprovedRemoteTransportGuard(listOf(target)))
                    branch?.let(command::setBranch)
                    credentialsProvider?.let(command::setCredentialsProvider)

                    command.call().use { git ->
                        guard.verifyOpenedRepository(git.repository)
                        disableRepositoryHooks(git.repository)
                        GitCloneResult(
                            branch = currentBranch(git.repository),
                            headObjectId = git.repository.resolve(Constants.HEAD)?.name,
                        )
                    }
            }
        }
    }

    /** Clears partial clone output without following worktree symlinks. */
    internal fun cleanupFailedCloneOutput() = guard.cleanupFailedCloneOutput()

    fun status(): GitStatusResult = runCancellable {
        openGit().use { git ->
            val status = git.status().call()
            GitStatusResult(
                clean = status.isClean,
                added = status.added.toSortedSet(),
                changed = status.changed.toSortedSet(),
                conflicting = status.conflicting.toSortedSet(),
                ignoredNotInIndex = status.ignoredNotInIndex.toSortedSet(),
                missing = status.missing.toSortedSet(),
                modified = status.modified.toSortedSet(),
                removed = status.removed.toSortedSet(),
                uncommittedChanges = status.uncommittedChanges.toSortedSet(),
                untracked = status.untracked.toSortedSet(),
                untrackedFolders = status.untrackedFolders.toSortedSet(),
            )
        }
    }

    /** Returns separate index-to-HEAD and worktree-to-index patches, each capped at [maxBytes]. */
    fun diff(maxBytes: Int = DEFAULT_DIFF_BYTES): GitDiffResult {
        GitInputPolicy.requireDiffLimit(maxBytes)
        return runCancellable {
            openGit().use { git ->
                GitDiffResult(
                    staged = renderDiff(git, cached = true, maxBytes = maxBytes),
                    unstaged = renderDiff(git, cached = false, maxBytes = maxBytes),
                )
            }
        }
    }

    /**
     * Captures the complete path scope and bounded staged/unstaged patches that are shown before
     * [commit] stages the whole worktree. Calling this again after approval detects scope changes.
     */
    fun commitPreview(maxDiffBytes: Int = DEFAULT_COMMIT_PREVIEW_DIFF_BYTES): GitCommitPreview {
        GitInputPolicy.requireDiffLimit(maxDiffBytes)
        val status = status()
        val candidates = (
            status.uncommittedChanges +
                status.added +
                status.changed +
                status.conflicting +
                status.missing +
                status.modified +
                status.removed +
                status.untracked
            ).toSortedSet()
        val conflicting = status.conflicting.toSortedSet()
        val added = (status.added + status.untracked)
            .minus(conflicting)
            .toSortedSet()
        val deleted = (status.removed + status.missing)
            .minus(conflicting)
            .minus(added)
            .toSortedSet()
        val modified = candidates
            .minus(conflicting)
            .minus(added)
            .minus(deleted)
            .toSortedSet()
        return GitCommitPreview(
            clean = status.clean,
            added = added,
            modified = modified,
            deleted = deleted,
            conflicting = conflicting,
            diff = diff(maxDiffBytes),
        )
    }

    /** Stages all additions, modifications and deletions before creating the commit. */
    fun commit(
        message: String,
        authorName: String,
        authorEmail: String,
    ): GitCommitResult {
        GitInputPolicy.requireCommit(message, authorName, authorEmail)
        checkCancellation()

        return openGit().use { git ->
            disableRepositoryHooks(git.repository)
            git.add().addFilepattern(".").call()
            git.add().setUpdate(true).addFilepattern(".").call()
            val commit = git.commit()
                .setMessage(message)
                .setAuthor(authorName, authorEmail)
                .setCommitter(authorName, authorEmail)
                .setNoVerify(true)
                .setSign(false)
                .call()
            GitCommitResult(commit.name, commit.shortMessage)
        }
    }

    fun pull(
        remote: String = "origin",
        branch: String? = null,
        credential: GitHttpsCredentialRef? = null,
        timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    ): GitPullResult {
        GitInputPolicy.requireRemoteName(remote)
        branch?.let(GitInputPolicy::requireBranch)
        GitInputPolicy.requireTimeoutSeconds(timeoutSeconds)

        return runCancellable {
            openGit().use { git ->
                val targets = configuredRemoteTargets(git.repository, remote, pushing = false)
                credential?.requireAllows(targets)
                disableRepositoryHooks(git.repository)
                withCredentials(credential) { credentialsProvider ->
                    val command = git.pull()
                        .setRemote(remote)
                        .setRebase(false)
                        .setTimeout(timeoutSeconds)
                        .setProgressMonitor(progressMonitor)
                        .setTransportConfigCallback(ApprovedRemoteTransportGuard(targets))
                    branch?.let(command::setRemoteBranchName)
                    credentialsProvider?.let(command::setCredentialsProvider)
                    val result = command.call()
                    GitPullResult(
                        successful = result.isSuccessful,
                        fetchMessages = result.fetchResult?.messages?.boundedMessage(),
                        mergeStatus = result.mergeResult?.mergeStatus?.toString(),
                        rebaseStatus = result.rebaseResult?.status?.toString(),
                    )
                }
            }
        }
    }

    fun push(
        remote: String = "origin",
        credential: GitHttpsCredentialRef? = null,
        timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    ): GitPushResult {
        GitInputPolicy.requireRemoteName(remote)
        GitInputPolicy.requireTimeoutSeconds(timeoutSeconds)

        return runCancellable {
            openGit().use { git ->
                val targets = configuredRemoteTargets(git.repository, remote, pushing = true)
                credential?.requireAllows(targets)
                disableRepositoryHooks(git.repository)
                withCredentials(credential) { credentialsProvider ->
                    val command = git.push()
                        .setRemote(remote)
                        .setTimeout(timeoutSeconds)
                        .setProgressMonitor(progressMonitor)
                        .setTransportConfigCallback(ApprovedRemoteTransportGuard(targets))
                    credentialsProvider?.let(command::setCredentialsProvider)
                    val results = command.call().toList()
                    GitPushResult(
                        updates = results.flatMap { result ->
                            result.remoteUpdates.map { update ->
                                GitPushUpdate(
                                    remoteRef = update.remoteName,
                                    status = update.status.toString(),
                                    message = update.message?.boundedMessage(),
                                )
                            }
                        },
                        messages = results.mapNotNull { it.messages?.boundedMessage()?.takeIf(String::isNotBlank) },
                    )
                }
            }
        }
    }

    /** Reads and validates the effective transport targets without resolving any credential. */
    fun configuredRemoteTargets(remote: String = "origin", pushing: Boolean = false): List<GitRemoteTarget> {
        GitInputPolicy.requireRemoteName(remote)
        return runCancellable {
            openGit().use { git -> configuredRemoteTargets(git.repository, remote, pushing) }
        }
    }

    private fun openGit(): Git {
        val gitDirectory = guard.requireRepositoryDirectory()
        val repository = FileRepositoryBuilder()
            .setGitDir(gitDirectory)
            .setWorkTree(guard.root)
            .setMustExist(true)
            .build()
        try {
            guard.verifyOpenedRepository(repository)
            return Git(repository)
        } catch (failure: Throwable) {
            repository.close()
            throw failure
        }
    }

    private fun configuredRemoteTargets(
        repository: Repository,
        remote: String,
        pushing: Boolean,
    ): List<GitRemoteTarget> {
        val fetchUrls = repository.config.getStringList(
            ConfigConstants.CONFIG_REMOTE_SECTION,
            remote,
            ConfigConstants.CONFIG_KEY_URL,
        )
        require(fetchUrls.isNotEmpty()) { "Configured Git remote does not exist" }
        require(fetchUrls.size <= MAX_REMOTE_URLS) { "Configured Git remote has too many URLs" }
        fetchUrls.forEach(GitRemoteTarget::fromHttpsUrl)
        val effectiveUrls = if (pushing) {
            val pushUrls = repository.config.getStringList(
                ConfigConstants.CONFIG_REMOTE_SECTION,
                remote,
                "pushurl",
            )
            pushUrls.takeIf { it.isNotEmpty() } ?: fetchUrls
        } else {
            fetchUrls
        }
        require(effectiveUrls.size <= MAX_REMOTE_URLS) { "Configured Git remote has too many URLs" }
        return effectiveUrls.map(GitRemoteTarget::fromHttpsUrl)
    }

    private fun disableRepositoryHooks(repository: Repository) {
        // A regular file is deliberately used as hooksPath, making every child hook path absent.
        val blocker = File(repository.directory, HOOK_BLOCKER_NAME)
        require(!blocker.exists() || (blocker.isFile && !Files.isSymbolicLink(blocker.toPath()))) {
            "Repository hook blocker is unsafe"
        }
        if (!blocker.exists()) {
            check(blocker.createNewFile()) { "Unable to disable repository hooks" }
        }
        val config = repository.config
        val safeHooksPath = ".git/$HOOK_BLOCKER_NAME"
        if (config.getString(ConfigConstants.CONFIG_CORE_SECTION, null, ConfigConstants.CONFIG_KEY_HOOKS_PATH) != safeHooksPath) {
            config.setString(
                ConfigConstants.CONFIG_CORE_SECTION,
                null,
                ConfigConstants.CONFIG_KEY_HOOKS_PATH,
                safeHooksPath,
            )
            config.save()
        }
    }

    private fun renderDiff(git: Git, cached: Boolean, maxBytes: Int): GitPatch {
        val output = BoundedGitOutputStream(maxBytes)
        try {
            git.diff().setCached(cached).setOutputStream(output).call()
        } catch (_: NoHeadException) {
            // An unborn branch has no HEAD tree; status still exposes all untracked/index entries.
        }
        return GitPatch(output.asUtf8(), output.truncated)
    }

    private inline fun <T> withCredentials(
        reference: GitHttpsCredentialRef?,
        block: (CredentialsProvider?) -> T,
    ): T {
        if (reference == null) return block(null)
        val resolver = requireNotNull(credentialResolver) { "No Git credential resolver is configured" }
        return resolver.resolveToken(reference.tokenCredentialId).use { token ->
            HostBoundCredentialsProvider(reference, token.copyValue()).use(block)
        }
    }

    private fun checkCancellation() {
        if (cancellationCheck()) throw CancellationException("Git operation was cancelled")
    }

    private inline fun <T> runCancellable(block: () -> T): T {
        checkCancellation()
        return try {
            block().also { checkCancellation() }
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            if (cancellationCheck()) {
                throw CancellationException("Git operation was cancelled").also { it.initCause(failure) }
            }
            throw failure
        }
    }

    private fun currentBranch(repository: Repository): String? =
        runCatching { repository.branch }.getOrNull()

    private fun String.boundedMessage(): String = take(MAX_RESULT_MESSAGE_CHARS)

    private companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 60
        const val DEFAULT_DIFF_BYTES = 512 * 1024
        const val DEFAULT_COMMIT_PREVIEW_DIFF_BYTES = 64 * 1024
        const val MAX_RESULT_MESSAGE_CHARS = 64 * 1024
        const val MAX_REMOTE_URLS = 8
        const val HOOK_BLOCKER_NAME = "pocketpilot-hooks-disabled"
    }
}

internal class CancellationProgressMonitor(
    private val cancellationCheck: () -> Boolean,
) : ProgressMonitor {
    override fun start(totalTasks: Int) = Unit
    override fun beginTask(title: String?, totalWork: Int) = Unit
    override fun update(completed: Int) = Unit
    override fun endTask() = Unit
    override fun isCancelled(): Boolean = cancellationCheck()
}

/** Prevents redirects or repository reconfiguration from releasing a token to another endpoint. */
internal class HostBoundCredentialsProvider(
    private val reference: GitHttpsCredentialRef,
    token: CharArray,
) : UsernamePasswordCredentialsProvider(reference.username, token), AutoCloseable {
    override fun get(uri: URIish, vararg items: CredentialItem): Boolean {
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        val endpoint = GitRemoteEndpoint(host, if (uri.port == -1) 443 else uri.port)
        if (!uri.scheme.equals("https", ignoreCase = true) || endpoint !in reference.allowedEndpoints) {
            return false
        }
        return super.get(uri, *items)
    }

    override fun close() = clear()
}

/** Rejects JGit `insteadOf` rewrites and any transport target not shown in the approval. */
internal class ApprovedRemoteTransportGuard(
    private val approvedTargets: Collection<GitRemoteTarget>,
) : TransportConfigCallback {
    override fun configure(transport: Transport) {
        val actual = try {
            GitRemoteTarget.fromHttpsUrl(transport.uri.toString())
        } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException("Git transport target differs from the approved HTTPS remote", failure)
        }
        require(approvedTargets.any { it.sameLocation(actual) }) {
            "Git transport target differs from the approved HTTPS remote"
        }
    }
}

internal class BoundedGitOutputStream(private val limit: Int) : OutputStream() {
    private val bytes = ByteArray(limit)
    private var count = 0
    var truncated: Boolean = false
        private set

    override fun write(value: Int) {
        if (count < limit) {
            bytes[count++] = value.toByte()
        } else {
            truncated = true
        }
    }

    override fun write(source: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= source.size)
        val writable = minOf(length, limit - count)
        if (writable > 0) {
            source.copyInto(bytes, destinationOffset = count, startIndex = offset, endIndex = offset + writable)
            count += writable
        }
        if (writable < length) truncated = true
    }

    fun asUtf8(): String = String(bytes, 0, count, StandardCharsets.UTF_8)
}
