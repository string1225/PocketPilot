package com.string1225.pocketpilot.integrations.git

import java.net.URI
import java.util.Arrays
import java.util.Locale

data class GitRemoteEndpoint(
    val host: String,
    val port: Int,
) {
    init {
        require(host.isNotBlank() && host == host.lowercase(Locale.ROOT)) { "Git remote host is invalid" }
        require(port in 1..65535) { "Git remote port is invalid" }
    }

    companion object {
        internal fun from(uri: URI): GitRemoteEndpoint = GitRemoteEndpoint(
            host = requireNotNull(uri.host).lowercase(Locale.ROOT),
            port = if (uri.port == -1) 443 else uri.port,
        )
    }
}

/** A validated, credential-free HTTPS remote and the endpoint credentials may be released to. */
class GitRemoteTarget internal constructor(
    val url: String,
    val endpoint: GitRemoteEndpoint,
    private val rawPath: String,
) {
    internal fun sameLocation(other: GitRemoteTarget): Boolean =
        endpoint == other.endpoint && rawPath == other.rawPath

    companion object {
        fun fromHttpsUrl(remoteUrl: String): GitRemoteTarget {
            val uri = GitInputPolicy.requireHttpsRemote(remoteUrl)
            return GitRemoteTarget(remoteUrl, GitRemoteEndpoint.from(uri), requireNotNull(uri.rawPath))
        }
    }
}

/** A reference to a token stored by the application's credential store. */
data class GitHttpsCredentialRef(
    val username: String,
    val tokenCredentialId: String,
    val allowedRemoteUrls: Set<String>,
) {
    val allowedEndpoints: Set<GitRemoteEndpoint>

    init {
        requireSafeGitField("username", username, 1, 256)
        requireSafeGitField("tokenCredentialId", tokenCredentialId, 1, 512)
        require(allowedRemoteUrls.isNotEmpty() && allowedRemoteUrls.size <= 8) {
            "Git credential must be bound to 1..8 approved remote URLs"
        }
        allowedEndpoints = allowedRemoteUrls.map { GitRemoteTarget.fromHttpsUrl(it).endpoint }.toSet()
    }

    internal fun requireAllows(targets: Collection<GitRemoteTarget>) {
        require(targets.isNotEmpty() && targets.all {
            it.url in allowedRemoteUrls && it.endpoint in allowedEndpoints
        }) {
            "Git credential is not authorized for the configured remote URL"
        }
    }
}

/**
 * Resolves a credential just-in-time. Implementations should read from an encrypted store and
 * must not log either the returned value or failures containing it.
 */
fun interface GitCredentialResolver {
    fun resolveToken(credentialId: String): GitTokenCredential
}

/** A closeable token copy whose backing array is erased after use. */
class GitTokenCredential(token: CharArray) : AutoCloseable {
    private var value: CharArray? = token.copyOf()

    internal fun copyValue(): CharArray =
        checkNotNull(value) { "Credential has already been closed" }.copyOf()

    override fun close() {
        value?.let { Arrays.fill(it, '\u0000') }
        value = null
    }

    override fun toString(): String = "GitTokenCredential([REDACTED])"
}

data class GitStatusResult(
    val clean: Boolean,
    val added: Set<String>,
    val changed: Set<String>,
    val conflicting: Set<String>,
    val ignoredNotInIndex: Set<String>,
    val missing: Set<String>,
    val modified: Set<String>,
    val removed: Set<String>,
    val uncommittedChanges: Set<String>,
    val untracked: Set<String>,
    val untrackedFolders: Set<String>,
)

data class GitPatch(
    val text: String,
    val truncated: Boolean,
)

data class GitDiffResult(
    val staged: GitPatch,
    val unstaged: GitPatch,
)

/** Immutable, bounded snapshot of the paths and diffs a stage-all commit would include. */
data class GitCommitPreview(
    val clean: Boolean,
    val added: Set<String>,
    val modified: Set<String>,
    val deleted: Set<String>,
    val conflicting: Set<String>,
    val diff: GitDiffResult,
) {
    val changedPaths: Set<String>
        get() = added + modified + deleted + conflicting
}

data class GitInitResult(
    val branch: String,
)

data class GitCloneResult(
    val branch: String?,
    val headObjectId: String?,
)

data class GitCommitResult(
    val objectId: String,
    val shortMessage: String,
)

data class GitPullResult(
    val successful: Boolean,
    val fetchMessages: String?,
    val mergeStatus: String?,
    val rebaseStatus: String?,
)

data class GitPushUpdate(
    val remoteRef: String,
    val status: String,
    val message: String?,
)

data class GitPushResult(
    val updates: List<GitPushUpdate>,
    val messages: List<String>,
) {
    val successful: Boolean
        get() = updates.isNotEmpty() && updates.all { it.status == "OK" || it.status == "UP_TO_DATE" }
}

internal fun requireSafeGitField(name: String, value: String, minimum: Int, maximum: Int) {
    require(value.length in minimum..maximum) { "$name length must be in $minimum..$maximum" }
    require(value.none { it.isUnsafeApprovalCharacter() }) {
        "$name contains control characters"
    }
}

internal fun Char.isUnsafeApprovalCharacter(): Boolean =
    isISOControl() ||
        code == 0x061c ||
        code == 0x200e ||
        code == 0x200f ||
        code in 0x202a..0x202e ||
        code in 0x2066..0x2069
