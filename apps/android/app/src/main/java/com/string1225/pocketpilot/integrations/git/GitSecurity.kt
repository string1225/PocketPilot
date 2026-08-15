package com.string1225.pocketpilot.integrations.git

import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import org.eclipse.jgit.lib.Repository

internal class GitWorkspaceGuard(workspace: File) {
    val root: File

    init {
        require(workspace.exists() && workspace.isDirectory) { "Project workspace must be an existing directory" }
        require(!Files.isSymbolicLink(workspace.toPath())) { "Project workspace must not be a symbolic link" }
        root = workspace.canonicalFile
        require(root == workspace.absoluteFile.normalize()) { "Project workspace must not redirect through a link" }
    }

    fun requireEmptyForClone() {
        require(root.listFiles()?.isEmpty() == true) { "Clone destination must be an empty project workspace" }
    }

    /** Removes only entries below the already-bound workspace and never follows a repository link. */
    fun cleanupFailedCloneOutput() {
        root.listFiles().orEmpty().forEach(::deleteCloneEntry)
        check(root.listFiles()?.isEmpty() == true) { "Unable to clean failed Git clone output" }
    }

    fun requireRepositoryDirectory(): File {
        val gitPath = File(root, ".git")
        require(gitPath.exists()) { "Project workspace is not a Git repository" }
        require(gitPath.isDirectory && !Files.isSymbolicLink(gitPath.toPath())) {
            "Only a repository stored directly inside the project workspace is supported"
        }
        val canonical = gitPath.canonicalFile
        require(canonical == gitPath.absoluteFile.normalize()) { "Git directory must not redirect through a link" }
        require(canonical.parentFile == root) { "Git repository escapes the project workspace" }
        return canonical
    }

    fun verifyOpenedRepository(repository: Repository) {
        require(!repository.isBare) { "Bare repositories are not project workspaces" }
        require(repository.workTree.canonicalFile == root) { "Git work tree escapes the project workspace" }
        require(repository.directory.canonicalFile == requireRepositoryDirectory()) {
            "Git directory escapes the project workspace"
        }
    }

    private fun deleteCloneEntry(entry: File) {
        val path = entry.toPath()
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            entry.listFiles().orEmpty().forEach(::deleteCloneEntry)
        }
        Files.deleteIfExists(path)
    }
}

object GitInputPolicy {
    private val remoteNamePattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

    fun requireHttpsRemote(remoteUrl: String): URI {
        require(remoteUrl.length in 1..4096 && remoteUrl.none { it.isWhitespace() || it.isUnsafeApprovalCharacter() }) {
            "Remote URL is malformed"
        }
        val uri = try {
            URI(remoteUrl)
        } catch (_: Exception) {
            throw IllegalArgumentException("Remote URL is malformed")
        }
        require(uri.scheme.equals("https", ignoreCase = true)) { "Only HTTPS Git remotes are supported" }
        require(!uri.host.isNullOrBlank()) { "HTTPS Git remote must contain a host" }
        require(uri.rawUserInfo == null) { "Credentials must be supplied by credential reference, not in the URL" }
        require(uri.rawQuery == null && uri.rawFragment == null) { "Git remote query strings and fragments are not supported" }
        require(!uri.rawPath.isNullOrBlank() && uri.rawPath != "/") { "HTTPS Git remote must contain a repository path" }
        require(uri.port == -1 || uri.port in 1..65535) { "Remote URL port is invalid" }
        return uri
    }

    fun requireRemoteName(remote: String) {
        require(remoteNamePattern.matches(remote)) { "Remote name is invalid" }
    }

    fun requireBranch(branch: String) {
        require(branch.length in 1..256) { "Branch length must be in 1..256" }
        require(Repository.isValidRefName("refs/heads/$branch")) { "Branch name is invalid" }
    }

    fun requireTimeoutSeconds(timeoutSeconds: Int) {
        require(timeoutSeconds in 1..3600) { "Git timeout must be in 1..3600 seconds" }
    }

    fun requireDiffLimit(maxBytes: Int) {
        require(maxBytes in 1..512 * 1024) { "Diff limit must be in 1..524288 bytes" }
    }

    fun requireCommit(message: String, authorName: String, authorEmail: String) {
        require(message.length in 1..64 * 1024) { "Commit message length must be in 1..65536" }
        require(message.any { !it.isWhitespace() }) { "Commit message must not be blank" }
        require(message.none {
            it.isUnsafeApprovalCharacter() && it != '\n' && it != '\t'
        }) { "Commit message contains unsafe control characters" }
        requireSafeGitField("authorName", authorName, 1, 256)
        requireSafeGitField("authorEmail", authorEmail, 3, 320)
        require(authorEmail.contains('@') && !authorEmail.any(Char::isWhitespace)) { "authorEmail is invalid" }
    }
}

/** Produces an unambiguous, fully bounded native approval description for a stage-all commit. */
internal object GitCommitApprovalFormatter {
    fun format(
        preview: GitCommitPreview,
        message: String,
        authorName: String,
        authorEmail: String,
        maximumDetailCharacters: Int,
    ): String {
        require(!preview.clean && preview.changedPaths.isNotEmpty()) { "There are no changes to commit" }
        require(preview.conflicting.isEmpty()) { "Resolve conflicting files before creating a commit" }
        require(preview.changedPaths.size <= MAX_CHANGED_PATHS) {
            "Commit changes too many paths to display safely"
        }
        preview.changedPaths.forEach { path ->
            require(path.length <= MAX_PATH_CHARACTERS) { "Commit path is too long to display safely" }
        }

        val detail = buildString {
            append("Author: \"")
            append(authorName.escapeForApproval())
            append("\" <\"")
            append(authorEmail.escapeForApproval())
            append("\">\nMessage (")
            append(message.length)
            append(" chars, escaped): \"")
            val excerpt = message.take(MAX_MESSAGE_CHARACTERS)
            append(excerpt.escapeForApproval())
            if (excerpt.length != message.length) append("…[truncated]")
            append("\"\n\n")
            appendPaths("Added", "+", preview.added)
            appendPaths("Modified", "~", preview.modified)
            appendPaths("Deleted", "-", preview.deleted)
            append("Conflicting (0): none\n")
            append("Diff summary (bounded, untracked contents are not included by Git diff):\n")
            appendPatchSummary("staged", preview.diff.staged)
            appendPatchSummary("unstaged", preview.diff.unstaged)
        }
        require(detail.length <= maximumDetailCharacters) {
            "Commit approval detail is too large to display safely"
        }
        return detail
    }

    fun escapedPathList(paths: Collection<String>, maximumCharacters: Int): String {
        val rendered = paths.sorted().joinToString(separator = ", ") { "\"${it.escapeForApproval()}\"" }
        return if (rendered.length <= maximumCharacters) rendered else rendered.take(maximumCharacters) + "…[truncated]"
    }

    private fun StringBuilder.appendPaths(label: String, marker: String, paths: Set<String>) {
        append(label)
        append(" (")
        append(paths.size)
        append("):")
        if (paths.isEmpty()) {
            append(" none\n")
            return
        }
        append('\n')
        paths.sorted().forEach { path ->
            append("  ")
            append(marker)
            append(" \"")
            append(path.escapeForApproval())
            append("\"\n")
        }
    }

    private fun StringBuilder.appendPatchSummary(label: String, patch: GitPatch) {
        append("  ")
        append(label)
        append(": ")
        append(patch.text.toByteArray(Charsets.UTF_8).size)
        append(" captured UTF-8 bytes")
        if (patch.truncated) append(", truncated")
        append('\n')
    }

    private fun String.escapeForApproval(): String = buildString(length) {
        this@escapeForApproval.forEach { character ->
            when {
                character == '\\' -> append("\\\\")
                character == '"' -> append("\\\"")
                character.isUnsafeApprovalCharacter() ||
                    Character.getType(character) == Character.FORMAT.toInt() ||
                    character == '\u2028' ||
                    character == '\u2029' -> {
                    append("\\u")
                    append(character.code.toString(16).uppercase().padStart(4, '0'))
                }
                else -> append(character)
            }
        }
    }

    private const val MAX_CHANGED_PATHS = 128
    private const val MAX_PATH_CHARACTERS = 1024
    private const val MAX_MESSAGE_CHARACTERS = 2 * 1024
}
