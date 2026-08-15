package com.string1225.pocketpilot.integrations.git

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.URIish

class GitSecurityTest {
    @Test
    fun `remote policy accepts only credential-free HTTPS repository URLs`() {
        assertEquals("github.com", GitInputPolicy.requireHttpsRemote("https://github.com/example/repo.git").host)

        listOf(
            "http://github.com/example/repo.git",
            "ssh://git@github.com/example/repo.git",
            "git@github.com:example/repo.git",
            "file:///tmp/repo",
            "../repo",
            "https://user:token@github.com/example/repo.git",
            "https://github.com/example/repo.git?token=secret",
            "https://github.com/example/repo.git#main",
            "https://github.com/example/\u202erepo.git",
        ).forEach { remote ->
            assertFailsWith<IllegalArgumentException>(remote) {
                GitInputPolicy.requireHttpsRemote(remote)
            }
        }
    }

    @Test
    fun `workspace guard rejects gitdir indirection`() {
        val workspace = Files.createTempDirectory("pocketpilot-git-guard").toFile()
        val external = Files.createTempDirectory("pocketpilot-external-git").toFile()
        try {
            File(workspace, ".git").writeText("gitdir: ${external.absolutePath}")

            assertFailsWith<IllegalArgumentException> {
                GitWorkspaceGuard(workspace).requireRepositoryDirectory()
            }
        } finally {
            workspace.deleteRecursively()
            external.deleteRecursively()
        }
    }

    @Test
    fun `clone guard requires an empty authorized workspace`() {
        val workspace = Files.createTempDirectory("pocketpilot-git-nonempty").toFile()
        try {
            File(workspace, "user-file.txt").writeText("keep")
            assertFailsWith<IllegalArgumentException> {
                GitWorkspaceGuard(workspace).requireEmptyForClone()
            }
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `token credential is defensive redacted and closeable`() {
        val source = "super-secret-token".toCharArray()
        val credential = GitTokenCredential(source)
        source.fill('x')

        assertEquals("super-secret-token", String(credential.copyValue()))
        assertFalse(credential.toString().contains("super-secret-token"))
        credential.close()
        assertFailsWith<IllegalStateException> { credential.copyValue() }
    }

    @Test
    fun `credential release is bound to approved URL and transport endpoint`() {
        val approved = GitRemoteTarget.fromHttpsUrl("https://github.com/example/approved.git")
        val reference = GitHttpsCredentialRef(
            username = "pilot",
            tokenCredentialId = "git-token",
            allowedRemoteUrls = setOf(approved.url),
        )

        reference.requireAllows(listOf(approved))
        assertFailsWith<IllegalArgumentException> {
            reference.requireAllows(
                listOf(GitRemoteTarget.fromHttpsUrl("https://github.com/example/not-approved.git")),
            )
        }

        HostBoundCredentialsProvider(reference, "secret-token".toCharArray()).use { provider ->
            val username = CredentialItem.Username()
            val password = CredentialItem.Password()
            assertTrue(provider.get(URIish(approved.url), username, password))
            assertEquals("pilot", username.value)
            assertContentEquals("secret-token".toCharArray(), password.value)

            val redirectedPassword = CredentialItem.Password()
            assertFalse(
                provider.get(
                    URIish("https://attacker.example/repository.git"),
                    CredentialItem.Username(),
                    redirectedPassword,
                ),
            )
            assertTrue(redirectedPassword.value == null)
        }
    }

    @Test
    fun `approved transport identity includes repository path`() {
        val approved = GitRemoteTarget.fromHttpsUrl("https://github.com/example/approved.git")
        assertTrue(approved.sameLocation(GitRemoteTarget.fromHttpsUrl("https://github.com:443/example/approved.git")))
        assertFalse(approved.sameLocation(GitRemoteTarget.fromHttpsUrl("https://github.com/example/other.git")))
        assertFalse(approved.sameLocation(GitRemoteTarget.fromHttpsUrl("https://mirror.example/example/approved.git")))
    }

    @Test
    fun `branch remote timeout and diff arguments are bounded`() {
        GitInputPolicy.requireBranch("feature/safe-name")
        GitInputPolicy.requireRemoteName("origin-2")
        GitInputPolicy.requireTimeoutSeconds(60)
        GitInputPolicy.requireDiffLimit(1024)

        assertFailsWith<IllegalArgumentException> { GitInputPolicy.requireBranch("../escape") }
        assertFailsWith<IllegalArgumentException> { GitInputPolicy.requireRemoteName("../../remote") }
        assertFailsWith<IllegalArgumentException> { GitInputPolicy.requireTimeoutSeconds(0) }
        assertFailsWith<IllegalArgumentException> { GitInputPolicy.requireDiffLimit(Int.MAX_VALUE) }
        assertFailsWith<IllegalArgumentException> { GitInputPolicy.requireDiffLimit(512 * 1024 + 1) }
    }

    @Test
    fun `bounded diff stream keeps prefix and reports truncation`() {
        val output = BoundedGitOutputStream(4)
        output.write("abcdef".toByteArray())

        assertEquals("abcd", output.asUtf8())
        assertTrue(output.truncated)
    }

    @Test
    fun `progress monitor exposes cancellation without side effects`() {
        var cancelled = false
        val monitor = CancellationProgressMonitor { cancelled }
        assertFalse(monitor.isCancelled)
        cancelled = true
        assertTrue(monitor.isCancelled)
    }

    @Test
    fun `commit approval lists every category and escapes deceptive paths`() {
        val preview = GitCommitPreview(
            clean = false,
            added = setOf("src/new.kt"),
            modified = setOf("evil\nDeleted (99):\u202e.kt"),
            deleted = setOf("old.kt"),
            conflicting = emptySet(),
            diff = GitDiffResult(
                staged = GitPatch("staged", truncated = false),
                unstaged = GitPatch("unstaged", truncated = true),
            ),
        )

        val detail = GitCommitApprovalFormatter.format(
            preview = preview,
            message = "safe\nmessage",
            authorName = "Pocket Pilot",
            authorEmail = "pilot@example.com",
            maximumDetailCharacters = 8 * 1024,
        )

        assertTrue(detail.contains("Added (1):"))
        assertTrue(detail.contains("Modified (1):"))
        assertTrue(detail.contains("Deleted (1):"))
        assertTrue(detail.contains("Conflicting (0): none"))
        assertTrue(detail.contains("safe\\u000Amessage"))
        assertTrue(detail.contains("evil\\u000ADeleted (99):\\u202E.kt"))
        assertFalse(detail.contains("evil\nDeleted (99)"))
        assertTrue(detail.contains("unstaged: 8 captured UTF-8 bytes, truncated"))
    }

    @Test
    fun `commit approval rejects conflicts empty commits and unbounded path lists`() {
        fun preview(clean: Boolean = false, conflicts: Set<String> = emptySet(), added: Set<String> = setOf("a")) =
            GitCommitPreview(
                clean = clean,
                added = added,
                modified = emptySet(),
                deleted = emptySet(),
                conflicting = conflicts,
                diff = GitDiffResult(GitPatch("", false), GitPatch("", false)),
            )

        assertFailsWith<IllegalArgumentException> {
            GitCommitApprovalFormatter.format(preview(clean = true, added = emptySet()), "m", "a", "a@b", 8192)
        }
        assertFailsWith<IllegalArgumentException> {
            GitCommitApprovalFormatter.format(preview(conflicts = setOf("conflict")), "m", "a", "a@b", 8192)
        }
        assertFailsWith<IllegalArgumentException> {
            GitCommitApprovalFormatter.format(
                preview(added = (0..128).map { "file-$it" }.toSet()),
                "m",
                "a",
                "a@b",
                8192,
            )
        }
    }
}
