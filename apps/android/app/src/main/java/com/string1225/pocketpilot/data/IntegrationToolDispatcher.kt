package com.string1225.pocketpilot.data

import com.string1225.pocketpilot.integrations.git.GitCredentialResolver
import com.string1225.pocketpilot.integrations.git.GitCommitApprovalFormatter
import com.string1225.pocketpilot.integrations.git.GitHttpsCredentialRef
import com.string1225.pocketpilot.integrations.git.GitInputPolicy
import com.string1225.pocketpilot.integrations.git.GitRemoteTarget
import com.string1225.pocketpilot.integrations.git.GitTokenCredential
import com.string1225.pocketpilot.integrations.git.JGitProjectRepository
import com.string1225.pocketpilot.integrations.ssh.CredentialResolver
import com.string1225.pocketpilot.integrations.ssh.SshCommandRequest
import com.string1225.pocketpilot.integrations.ssh.SshCommandTimeoutException
import com.string1225.pocketpilot.integrations.ssh.SshApprovalFormatter
import com.string1225.pocketpilot.integrations.ssh.SshCredentialRef
import com.string1225.pocketpilot.integrations.ssh.SshHostKeyPolicy
import com.string1225.pocketpilot.integrations.ssh.SshPasswordCredential
import com.string1225.pocketpilot.integrations.ssh.SshPrivateKeyCredential
import com.string1225.pocketpilot.integrations.ssh.SshServer
import com.string1225.pocketpilot.integrations.ssh.SshStoredCredentialCodec
import com.string1225.pocketpilot.integrations.ssh.SshjCommandExecutor
import com.string1225.pocketpilot.model.Checkpoint
import com.string1225.pocketpilot.model.CheckpointSource
import com.string1225.pocketpilot.model.SshAuthType
import com.string1225.pocketpilot.model.ToolApprovalRequest
import com.string1225.pocketpilot.runtime.ActiveRunRegistry
import com.string1225.pocketpilot.runtime.NativeToolRequest
import com.string1225.pocketpilot.runtime.NativeToolResult
import com.string1225.pocketpilot.runtime.ToolApprovalCoordinator
import com.string1225.pocketpilot.runtime.ToolDispatchException
import com.string1225.pocketpilot.runtime.ToolRequestDispatcher
import com.string1225.pocketpilot.security.SecureCredentialStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Native execution boundary for real Git and SSH tools. */
class IntegrationToolDispatcher(
    private val projectsRoot: File,
    private val checkpoints: CheckpointRepository,
    private val sshServers: SshServerRepository,
    private val credentials: SecureCredentialStore,
    private val gitBindings: GitBindingRepository,
    private val activeRuns: ActiveRunRegistry,
    private val approvals: ToolApprovalCoordinator,
    private val fallback: ToolRequestDispatcher,
) : ToolRequestDispatcher {
    override suspend fun dispatch(request: NativeToolRequest): NativeToolResult {
        if (!request.name.startsWith("git.") && request.name != "ssh.execute") {
            return fallback.dispatch(request)
        }
        requireAuthorized(request)
        if (request.argumentsJson.length > MAX_ARGUMENT_JSON_CHARS) {
            throw ToolDispatchException("INVALID_ARGUMENTS", "Tool arguments are too large")
        }
        val arguments = try {
            JSONObject(request.argumentsJson)
        } catch (error: JSONException) {
            throw ToolDispatchException("INVALID_ARGUMENTS", "Tool arguments must be a JSON object", error)
        }
        return if (request.name == "ssh.execute") {
            executeSsh(request, arguments)
        } else {
            executeGit(request, arguments)
        }
    }

    private suspend fun executeGit(
        request: NativeToolRequest,
        arguments: JSONObject,
    ): NativeToolResult = withContext(Dispatchers.IO) {
        try {
            val operationContext = currentCoroutineContext()
            val repository = JGitProjectRepository(
                workspace = ProjectPaths.workspaceDirectory(projectsRoot, request.projectId),
                credentialResolver = GitCredentialResolver { credentialId ->
                    val secret = credentials.get(credentialId)
                        ?: throw IllegalStateException("Configured Git credential is unavailable")
                    try {
                        GitTokenCredential(secret)
                    } finally {
                        secret.fill('\u0000')
                    }
                },
                cancellationCheck = {
                    !operationContext.isActive || Thread.currentThread().isInterrupted
                },
            )
            val data = when (request.name) {
                "git.init" -> {
                    arguments.requireOnlyKeys("initialBranch")
                    val initialBranch = arguments.stringOrDefault("initialBranch", "main")
                    GitInputPolicy.requireBranch(initialBranch)
                    approve(request, "Allow Agent to initialize Git?", "Initial branch: $initialBranch")
                    repository.init(initialBranch).let { JSONObject().put("branch", it.branch) }
                }

                "git.clone" -> {
                    arguments.requireOnlyKeys("remoteUrl", "branch", "useCredential", "username", "timeoutMillis")
                    val target = GitRemoteTarget.fromHttpsUrl(arguments.requiredString("remoteUrl"))
                    val branch = arguments.optionalString("branch")?.also(GitInputPolicy::requireBranch)
                    val timeoutMillis = arguments.gitTimeoutMillis()
                    val credential = gitCredential(request.projectId, arguments, listOf(target))
                    approve(
                        request,
                        "Allow Agent to clone this repository?",
                        gitNetworkApproval(targets = listOf(target), credential, timeoutMillis, branch),
                    )
                    val before = createGitCheckpoint(request.projectId, "Before Git clone")
                    val (result, checkpoint) = try {
                        val cloneResult = repository.clone(
                            remoteUrl = target.url,
                            branch = branch,
                            credential = credential,
                            timeoutSeconds = timeoutMillis.toInt() / 1000,
                        )
                        val finalCheckpoint = createPostMutationCheckpoint(
                            request.projectId,
                            "Cloned Git repository",
                            before,
                        )
                        cloneResult to finalCheckpoint
                    } catch (cancelled: CancellationException) {
                        runCatching(repository::cleanupFailedCloneOutput)
                            .exceptionOrNull()
                            ?.let(cancelled::addSuppressed)
                        throw cancelled
                    } catch (failure: Exception) {
                        val cleanupFailure = runCatching(repository::cleanupFailedCloneOutput).exceptionOrNull()
                        cleanupFailure?.let(failure::addSuppressed)
                        throw ToolDispatchException(
                            if (cleanupFailure == null) "GIT_CLONE_FAILED" else "GIT_CLONE_PARTIAL",
                            if (cleanupFailure == null) {
                                "Git clone failed and its partial workspace output was removed"
                            } else {
                                "Git clone failed and its partial workspace output could not be fully removed"
                            },
                            failure,
                        )
                    }
                    JSONObject()
                        .put("branch", result.branch)
                        .put("headObjectId", result.headObjectId)
                        .put("checkpointId", checkpoint.id)
                }

                "git.status" -> {
                    arguments.requireOnlyKeys()
                    repository.status().let { result ->
                        val collections = listOf(
                            "added" to result.added,
                            "changed" to result.changed,
                            "conflicting" to result.conflicting,
                            "ignoredNotInIndex" to result.ignoredNotInIndex,
                            "missing" to result.missing,
                            "modified" to result.modified,
                            "removed" to result.removed,
                            "uncommittedChanges" to result.uncommittedChanges,
                            "untracked" to result.untracked,
                            "untrackedFolders" to result.untrackedFolders,
                        )
                        val totalEntries = collections.sumOf { (_, values) -> values.size.toLong() }
                        val budget = SerializedJsonBudget()
                        val status = JSONObject().put("clean", result.clean)
                        for ((name, values) in collections) {
                            val array = JSONArray()
                            if (!budget.truncated) {
                                for (value in values) {
                                    if (!budget.tryPut(array, value)) break
                                }
                            }
                            status.put(name, array)
                        }
                        status
                            .put("totalEntries", totalEntries)
                            .put("returnedEntries", budget.acceptedEntries)
                            .put("truncated", budget.acceptedEntries.toLong() < totalEntries)
                    }
                }

                "git.diff" -> {
                    arguments.requireOnlyKeys("maxBytes")
                    val maxBytes = arguments.intOrDefault("maxBytes", DEFAULT_GIT_DIFF_BYTES)
                    GitInputPolicy.requireDiffLimit(maxBytes)
                    repository.diff(maxBytes).let { result ->
                        JSONObject()
                            .put(
                                "staged",
                                JSONObject().put("text", result.staged.text).put("truncated", result.staged.truncated),
                            )
                            .put(
                                "unstaged",
                                JSONObject().put("text", result.unstaged.text).put("truncated", result.unstaged.truncated),
                            )
                    }
                }

                "git.commit" -> {
                    arguments.requireOnlyKeys("message", "authorName", "authorEmail")
                    val message = arguments.requiredString("message")
                    val authorName = arguments.requiredString("authorName")
                    val authorEmail = arguments.requiredString("authorEmail")
                    GitInputPolicy.requireCommit(message, authorName, authorEmail)
                    val preview = repository.commitPreview()
                    if (preview.conflicting.isNotEmpty()) {
                        throw ToolDispatchException(
                            "GIT_COMMIT_CONFLICTS",
                            "Resolve conflicting files before committing: " +
                                GitCommitApprovalFormatter.escapedPathList(
                                    preview.conflicting,
                                    MAX_COMMIT_CONFLICT_SUMMARY_CHARS,
                                ),
                        )
                    }
                    if (preview.clean || preview.changedPaths.isEmpty()) {
                        throw ToolDispatchException("GIT_COMMIT_EMPTY", "There are no changes to commit")
                    }
                    val approvalDetail = try {
                        GitCommitApprovalFormatter.format(
                            preview = preview,
                            message = message,
                            authorName = authorName,
                            authorEmail = authorEmail,
                            maximumDetailCharacters = MAX_APPROVAL_DETAIL_CHARS,
                        )
                    } catch (failure: IllegalArgumentException) {
                        throw ToolDispatchException(
                            "GIT_COMMIT_PREVIEW_UNSAFE",
                            failure.message ?: "Commit scope cannot be displayed safely",
                            failure,
                        )
                    }
                    approve(
                        request,
                        "Allow Agent to create a Git commit?",
                        approvalDetail,
                    )
                    if (repository.commitPreview() != preview) {
                        throw ToolDispatchException(
                            "GIT_COMMIT_SCOPE_CHANGED",
                            "Workspace or index changed while commit approval was pending; review the commit again",
                        )
                    }
                    repository.commit(message, authorName, authorEmail).let { result ->
                        JSONObject().put("objectId", result.objectId).put("shortMessage", result.shortMessage)
                    }
                }

                "git.pull" -> {
                    arguments.requireOnlyKeys("remote", "branch", "useCredential", "username", "timeoutMillis")
                    val remote = arguments.stringOrDefault("remote", "origin")
                        .also(GitInputPolicy::requireRemoteName)
                    val branch = arguments.optionalString("branch")?.also(GitInputPolicy::requireBranch)
                    val timeoutMillis = arguments.gitTimeoutMillis()
                    val targets = repository.configuredRemoteTargets(remote, pushing = false)
                    val credential = gitCredential(request.projectId, arguments, targets)
                    approve(
                        request,
                        "Allow Agent to pull Git changes?",
                        "Remote name: $remote\n${gitNetworkApproval(targets, credential, timeoutMillis, branch)}",
                    )
                    val before = createGitCheckpoint(request.projectId, "Before Git pull")
                    val result = try {
                        repository.pull(
                            remote = remote,
                            branch = branch,
                            credential = credential,
                            timeoutSeconds = timeoutMillis.toInt() / 1000,
                        )
                    } catch (cancelled: CancellationException) {
                        checkpointAfterFailedPull(request.projectId, before, cancelled)
                        throw cancelled
                    } catch (failure: Exception) {
                        checkpointAfterFailedPull(request.projectId, before, failure)
                        throw ToolDispatchException(
                            "GIT_PULL_FAILED",
                            "Git pull failed; pre-pull checkpoint ${before.id} remains available",
                            failure,
                        )
                    }
                    val checkpoint = createPostMutationCheckpoint(
                        request.projectId,
                        "Pulled Git changes",
                        before,
                    )
                    if (!result.successful) {
                        throw ToolDispatchException(
                            "GIT_PULL_REJECTED",
                            "Git pull did not complete; pre-pull checkpoint ${before.id} remains available",
                        )
                    }
                    JSONObject()
                        .put("successful", true)
                        .put("fetchMessages", result.fetchMessages)
                        .put("mergeStatus", result.mergeStatus)
                        .put("rebaseStatus", result.rebaseStatus)
                        .put("checkpointId", checkpoint.id)
                }

                "git.push" -> {
                    arguments.requireOnlyKeys("remote", "useCredential", "username", "timeoutMillis")
                    val remote = arguments.stringOrDefault("remote", "origin")
                        .also(GitInputPolicy::requireRemoteName)
                    val timeoutMillis = arguments.gitTimeoutMillis()
                    val targets = repository.configuredRemoteTargets(remote, pushing = true)
                    val credential = gitCredential(request.projectId, arguments, targets)
                    approve(
                        request,
                        "Allow Agent to push Git changes?",
                        "Remote name: $remote\n${gitNetworkApproval(targets, credential, timeoutMillis)}",
                    )
                    val result = repository.push(
                        remote = remote,
                        credential = credential,
                        timeoutSeconds = timeoutMillis.toInt() / 1000,
                    )
                    if (!result.successful) {
                        val rejection = result.updates.joinToString(separator = ", ") { update ->
                            "${update.remoteRef}: ${update.status}${update.message?.let { " ($it)" }.orEmpty()}"
                        }.take(MAX_GIT_REJECTION_CHARS)
                        throw ToolDispatchException(
                            "GIT_PUSH_REJECTED",
                            "Git push was not accepted by every remote ref: $rejection",
                        )
                    }
                    val budget = SerializedJsonBudget()
                    val updates = JSONArray()
                    for (update in result.updates) {
                        if (
                            !budget.tryPut(
                                updates,
                                JSONObject()
                                    .put("remoteRef", update.remoteRef)
                                    .put("status", update.status)
                                    .put("message", update.message),
                            )
                        ) {
                            break
                        }
                    }
                    val messages = JSONArray()
                    if (!budget.truncated) {
                        for (message in result.messages) {
                            if (!budget.tryPut(messages, message)) break
                        }
                    }
                    val totalEntries = result.updates.size.toLong() + result.messages.size
                    JSONObject()
                        .put("successful", true)
                        .put("updates", updates)
                        .put("messages", messages)
                        .put("totalResultEntries", totalEntries)
                        .put("returnedResultEntries", budget.acceptedEntries)
                        .put("truncated", budget.acceptedEntries.toLong() < totalEntries)
                }

                else -> throw ToolDispatchException("UNKNOWN_TOOL", "Unknown Native Tool: ${request.name}")
            }
            success(data)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ToolDispatchException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw ToolDispatchException("GIT_INVALID_ARGUMENTS", error.message ?: "Git arguments are invalid", error)
        } catch (error: Exception) {
            throw ToolDispatchException("GIT_OPERATION_FAILED", "Git operation failed", error)
        }
    }

    private suspend fun executeSsh(
        request: NativeToolRequest,
        arguments: JSONObject,
    ): NativeToolResult {
        try {
            arguments.requireOnlyKeys("server", "command", "timeoutMillis", "timeout", "maxOutputBytes")
            val idOrName = arguments.requiredString("server").also {
                require(it.length <= 512) { "server is too long" }
            }
            val profile = sshServers.find(idOrName)
                ?: throw ToolDispatchException("SSH_SERVER_NOT_FOUND", "Configured SSH server was not found")
            val credentialRef = when (profile.authType) {
                SshAuthType.PASSWORD -> SshCredentialRef.Password(profile.credentialId)
                SshAuthType.PRIVATE_KEY -> SshCredentialRef.PrivateKey(profile.credentialId)
            }
            val server = SshServer(
                id = profile.id,
                name = profile.name,
                host = profile.host,
                port = profile.port,
                username = profile.username,
                credential = credentialRef,
                hostKeyPolicy = SshHostKeyPolicy.Sha256Fingerprint(profile.hostKeyFingerprint),
                description = profile.description,
            )
            val commandRequest = SshCommandRequest(
                command = arguments.requiredString("command"),
                timeoutMillis = arguments.sshTimeoutMillis(),
                maxOutputBytes = arguments.intOrDefault("maxOutputBytes", DEFAULT_SSH_OUTPUT_BYTES),
            )
            val approvalHeader = buildString {
                append("Server: ${server.username}@${server.host}:${server.port}\n")
                append("Host key: ${profile.hostKeyFingerprint}\n")
                append("Timeout: ${commandRequest.timeoutMillis} ms; output limit: ${commandRequest.maxOutputBytes} bytes\n")
                append("Command (${commandRequest.command.length} chars, escaped):\n")
            }
            val approvalCommand = SshApprovalFormatter.escapeCommand(
                commandRequest.command,
                MAX_APPROVAL_DETAIL_CHARS - approvalHeader.length,
            )
            approve(
                request,
                title = "Allow Agent to run this SSH command?",
                detail = approvalHeader + approvalCommand,
            )

            val executor = SshjCommandExecutor(
                CredentialResolver { reference ->
                    val secret = credentials.get(reference.credentialId)
                        ?: throw IllegalStateException("Configured SSH credential is unavailable")
                    try {
                        when (reference) {
                            is SshCredentialRef.Password -> SshPasswordCredential(secret)
                            is SshCredentialRef.PrivateKey -> {
                                val privateKey = SshStoredCredentialCodec.decodePrivateKey(secret)
                                try {
                                    SshPrivateKeyCredential(privateKey)
                                } finally {
                                    privateKey.fill('\u0000')
                                }
                            }
                        }
                    } finally {
                        secret.fill('\u0000')
                    }
                },
            )
            val result = runInterruptible(Dispatchers.IO) { executor.execute(server, commandRequest) }
            return success(
                JSONObject()
                    .put("stdout", result.stdout)
                    .put("stderr", result.stderr)
                    .put("exitCode", result.exitCode)
                    .put("stdoutTruncated", result.stdoutTruncated)
                    .put("stderrTruncated", result.stderrTruncated)
                    .put("durationMillis", result.durationMillis),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ToolDispatchException) {
            throw error
        } catch (timeout: SshCommandTimeoutException) {
            currentCoroutineContext().ensureActive()
            throw ToolDispatchException("SSH_TIMEOUT", timeout.message ?: "SSH command timed out", timeout)
        } catch (error: IllegalArgumentException) {
            currentCoroutineContext().ensureActive()
            throw ToolDispatchException("SSH_INVALID_ARGUMENTS", error.message ?: "SSH arguments are invalid", error)
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            throw ToolDispatchException("SSH_EXECUTION_FAILED", "SSH command failed", error)
        }
    }

    private fun createGitCheckpoint(projectId: String, description: String): Checkpoint = try {
        checkpoints.create(projectId, CheckpointSource.GIT, description)
    } catch (failure: Exception) {
        throw ToolDispatchException(
            "GIT_CHECKPOINT_FAILED",
            "Git operation was not started because its safety checkpoint could not be created",
            failure,
        )
    }

    private fun createPostMutationCheckpoint(
        projectId: String,
        description: String,
        before: Checkpoint,
    ): Checkpoint = try {
        checkpoints.create(projectId, CheckpointSource.GIT, description)
    } catch (failure: Exception) {
        throw ToolDispatchException(
            "GIT_CHECKPOINT_FAILED",
            "Git operation completed but its final checkpoint failed; checkpoint ${before.id} can restore the prior workspace",
            failure,
        )
    }

    private fun checkpointAfterFailedPull(projectId: String, before: Checkpoint, failure: Throwable) {
        runCatching {
            checkpoints.create(
                projectId,
                CheckpointSource.GIT,
                "Git pull interrupted or failed after ${before.id.take(8)}",
            )
        }.exceptionOrNull()?.let(failure::addSuppressed)
    }

    private suspend fun approve(request: NativeToolRequest, title: String, detail: String) {
        require(title.length <= MAX_APPROVAL_TITLE_CHARS && detail.length <= MAX_APPROVAL_DETAIL_CHARS) {
            "Approval request is too large"
        }
        val approved = approvals.awaitDecision(
            ToolApprovalRequest(
                id = request.id,
                runId = request.runId,
                projectId = request.projectId,
                toolName = request.name,
                title = title,
                detail = detail,
            ),
        )
        if (!approved) throw ToolDispatchException("PERMISSION_DENIED", "User rejected ${request.name}")
        requireAuthorized(request)
    }

    private fun requireAuthorized(request: NativeToolRequest) {
        if (!activeRuns.authorizes(request.runId, request.projectId)) {
            throw ToolDispatchException(
                "UNAUTHORIZED_RUN",
                "Tool request is not authorized for this active Agent Run",
            )
        }
    }

    private fun gitCredential(
        projectId: String,
        arguments: JSONObject,
        targets: Collection<GitRemoteTarget>,
    ): GitHttpsCredentialRef? {
        val useCredential = arguments.booleanOrDefault("useCredential", false)
        if (!useCredential) return null
        gitBindings.credentialFor(projectId, targets)?.let { return it }
        val binding = gitBindings.get(projectId)
            ?: throw ToolDispatchException(
                "GIT_BINDING_MISSING",
                "Bind this project to its Git HTTPS repository before releasing a credential",
            )
        if (!binding.hasCredential) {
            throw ToolDispatchException("GIT_CREDENTIAL_MISSING", "This project's Personal access token is missing")
        }
        throw ToolDispatchException(
            "GIT_CREDENTIAL_TARGET_MISMATCH",
            "This project's Personal access token is bound to ${binding.remoteUrl} and cannot be sent elsewhere",
        )
    }

    private fun gitNetworkApproval(
        targets: Collection<GitRemoteTarget>,
        credential: GitHttpsCredentialRef?,
        timeoutMillis: Long,
        branch: String? = null,
    ): String = buildString {
        append("HTTPS target${if (targets.size == 1) "" else "s"}:\n")
        targets.forEach { append("- ${it.url}\n") }
        branch?.let { append("Branch: $it\n") }
        append("Timeout: $timeoutMillis ms (${timeoutMillis / 1000} s)\n")
        if (credential == null) {
            append("Stored credential release: no")
        } else {
            append("Stored credential release: yes, only to ")
            append(credential.allowedEndpoints.sortedWith(compareBy({ it.host }, { it.port })).joinToString { "${it.host}:${it.port}" })
        }
    }

    private fun success(data: JSONObject): NativeToolResult = boundedNativeToolSuccess(data)

    private fun JSONObject.requireOnlyKeys(vararg allowed: String) {
        val allowedSet = allowed.toSet()
        val iterator = keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            require(key in allowedSet) { "Unknown argument: $key" }
        }
    }

    private fun JSONObject.requiredString(key: String): String {
        require(has(key) && !isNull(key)) { "$key is required" }
        val value = get(key)
        require(value is String) { "$key must be a string" }
        require(value.isNotBlank()) { "$key must not be blank" }
        return value
    }

    private fun JSONObject.optionalString(key: String): String? {
        if (!has(key)) return null
        require(!isNull(key)) { "$key must be a string" }
        return requiredString(key)
    }

    private fun JSONObject.stringOrDefault(key: String, default: String): String =
        if (has(key)) requiredString(key) else default

    private fun JSONObject.booleanOrDefault(key: String, default: Boolean): Boolean {
        if (!has(key)) return default
        require(!isNull(key) && get(key) is Boolean) { "$key must be a boolean" }
        return getBoolean(key)
    }

    private fun JSONObject.longOrDefault(key: String, default: Long): Long {
        if (!has(key)) return default
        require(!isNull(key)) { "$key must be an integer" }
        return when (val value = get(key)) {
            is Byte -> value.toLong()
            is Short -> value.toLong()
            is Int -> value.toLong()
            is Long -> value
            else -> throw IllegalArgumentException("$key must be an integer")
        }
    }

    private fun JSONObject.intOrDefault(key: String, default: Int): Int {
        val value = longOrDefault(key, default.toLong())
        require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "$key is outside integer range" }
        return value.toInt()
    }

    private fun JSONObject.gitTimeoutMillis(): Long = longOrDefault("timeoutMillis", DEFAULT_TIMEOUT_MILLIS).also {
        require(it in 1_000L..MAX_TIMEOUT_MILLIS && it % 1_000L == 0L) {
            "timeoutMillis must be 1000..3600000 in whole-second increments"
        }
    }

    private fun JSONObject.sshTimeoutMillis(): Long {
        require(!(has("timeoutMillis") && has("timeout"))) {
            "Specify timeoutMillis only"
        }
        val key = if (has("timeoutMillis")) "timeoutMillis" else if (has("timeout")) "timeout" else null
        val value = key?.let { longOrDefault(it, DEFAULT_TIMEOUT_MILLIS) } ?: DEFAULT_TIMEOUT_MILLIS
        require(value in 1L..MAX_TIMEOUT_MILLIS) { "timeoutMillis must be in 1..3600000" }
        return value
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 60_000L
        const val MAX_TIMEOUT_MILLIS = 3_600_000L
        const val DEFAULT_GIT_DIFF_BYTES = 512 * 1024
        const val DEFAULT_SSH_OUTPUT_BYTES = 512 * 1024
        const val MAX_ARGUMENT_JSON_CHARS = 512 * 1024
        const val MAX_APPROVAL_TITLE_CHARS = 240
        const val MAX_APPROVAL_DETAIL_CHARS = 8 * 1024
        const val MAX_COMMIT_CONFLICT_SUMMARY_CHARS = 2 * 1024
        const val MAX_GIT_REJECTION_CHARS = 2 * 1024
    }
}
