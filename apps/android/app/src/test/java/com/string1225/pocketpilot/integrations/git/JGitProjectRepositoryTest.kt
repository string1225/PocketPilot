package com.string1225.pocketpilot.integrations.git

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ConfigConstants

class JGitProjectRepositoryTest {
    @Test
    fun `init status diff and commit operate on bound workspace`() = withWorkspace { workspace ->
        val repository = JGitProjectRepository(workspace)
        assertEquals("main", repository.init().branch)

        val source = File(workspace, "hello.txt")
        source.writeText("one\n")
        assertEquals(setOf("hello.txt"), repository.status().untracked)

        val first = repository.commit("initial", "Pocket Pilot", "pilot@example.com")
        assertEquals(40, first.objectId.length)
        assertTrue(repository.status().clean)

        source.writeText("two\n")
        val diff = repository.diff()
        assertTrue(diff.unstaged.text.contains("-one"))
        assertTrue(diff.unstaged.text.contains("+two"))
        assertFalse(diff.unstaged.truncated)

        repository.commit("update", "Pocket Pilot", "pilot@example.com")
        assertTrue(repository.status().clean)
        source.delete()
        assertEquals(setOf("hello.txt"), repository.status().missing)
        repository.commit("delete", "Pocket Pilot", "pilot@example.com")
        assertTrue(repository.status().clean)
    }

    @Test
    fun `diff output is capped`() = withWorkspace { workspace ->
        val repository = JGitProjectRepository(workspace)
        repository.init()
        val source = File(workspace, "large.txt")
        source.writeText("baseline\n")
        repository.commit("baseline", "Pocket Pilot", "pilot@example.com")
        source.writeText("x".repeat(16_384))

        val patch = repository.diff(maxBytes = 128).unstaged
        assertTrue(patch.truncated)
        assertTrue(patch.text.toByteArray().size <= 128)
    }

    @Test
    fun `commit preview classifies complete stage-all scope and rejects an empty follow-up`() = withWorkspace { workspace ->
        val repository = JGitProjectRepository(workspace)
        repository.init()
        val modified = File(workspace, "modified.txt").apply { writeText("before\n") }
        val deleted = File(workspace, "deleted.txt").apply { writeText("delete me\n") }
        repository.commit("baseline", "Pocket Pilot", "pilot@example.com")

        modified.writeText("after\n")
        assertTrue(deleted.delete())
        File(workspace, "added.txt").writeText("new\n")

        val preview = repository.commitPreview(maxDiffBytes = 4096)
        assertFalse(preview.clean)
        assertEquals(setOf("added.txt"), preview.added)
        assertEquals(setOf("modified.txt"), preview.modified)
        assertEquals(setOf("deleted.txt"), preview.deleted)
        assertTrue(preview.conflicting.isEmpty())
        assertTrue(preview.diff.unstaged.text.contains("modified.txt"))

        repository.commit("all changes", "Pocket Pilot", "pilot@example.com")
        val empty = repository.commitPreview(maxDiffBytes = 4096)
        assertTrue(empty.clean)
        assertTrue(empty.changedPaths.isEmpty())
    }

    @Test
    fun `commit disables repository hooks through an in-workspace blocker`() = withWorkspace { workspace ->
        val repository = JGitProjectRepository(workspace)
        repository.init()
        File(workspace, "safe.txt").writeText("safe")
        repository.commit("safe", "Pocket Pilot", "pilot@example.com")

        Git.open(workspace).use { git ->
            assertEquals(
                ".git/pocketpilot-hooks-disabled",
                git.repository.config.getString(
                    ConfigConstants.CONFIG_CORE_SECTION,
                    null,
                    ConfigConstants.CONFIG_KEY_HOOKS_PATH,
                ),
            )
            assertTrue(File(workspace, ".git/pocketpilot-hooks-disabled").isFile)
        }
    }

    @Test
    fun `pull and push reject a configured non-HTTPS remote before transport`() = withWorkspace { workspace ->
        val repository = JGitProjectRepository(workspace)
        repository.init()
        Git.open(workspace).use { git ->
            git.repository.config.apply {
                setString("remote", "origin", "url", "file:///outside/repository")
                save()
            }
        }

        assertFailsWith<IllegalArgumentException> { repository.pull() }
        assertFailsWith<IllegalArgumentException> { repository.push() }
    }

    @Test
    fun `clone validates destination before starting network transport`() = withWorkspace { workspace ->
        File(workspace, "keep.txt").writeText("do not overwrite")
        val repository = JGitProjectRepository(workspace)

        assertFailsWith<IllegalArgumentException> {
            repository.clone("https://example.com/repository.git")
        }
        assertEquals("do not overwrite", File(workspace, "keep.txt").readText())
    }

    @Test
    fun `clone rejects a credential approved for another URL before resolution or transport`() = withWorkspace { workspace ->
        var resolved = false
        val repository = JGitProjectRepository(
            workspace,
            credentialResolver = GitCredentialResolver {
                resolved = true
                GitTokenCredential("unused".toCharArray())
            },
        )
        val credential = GitHttpsCredentialRef(
            username = "git",
            tokenCredentialId = "token",
            allowedRemoteUrls = setOf("https://example.com/approved.git"),
        )

        assertFailsWith<IllegalArgumentException> {
            repository.clone("https://example.com/not-approved.git", credential = credential)
        }
        assertFalse(resolved)
        assertTrue(workspace.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `failed clone cleanup empties workspace without following symlinks`() = withWorkspace { workspace ->
        val repository = JGitProjectRepository(workspace)
        File(workspace, ".git/objects").apply { mkdirs() }
        File(workspace, ".git/objects/partial").writeText("partial")
        File(workspace, "checked-out.txt").writeText("partial")
        val external = Files.createTempDirectory("pocketpilot-clone-cleanup-external").toFile()
        val externalFile = File(external, "keep.txt").apply { writeText("keep") }
        val link = File(workspace, "external-link")
        try {
            runCatching { Files.createSymbolicLink(link.toPath(), external.toPath()) }

            repository.cleanupFailedCloneOutput()

            assertTrue(workspace.listFiles().orEmpty().isEmpty())
            assertEquals("keep", externalFile.readText())
        } finally {
            Files.deleteIfExists(link.toPath())
            external.deleteRecursively()
        }
    }

    @Test
    fun `configured push target uses validated pushurl`() = withWorkspace { workspace ->
        val repository = JGitProjectRepository(workspace)
        repository.init()
        Git.open(workspace).use { git ->
            git.repository.config.apply {
                setString("remote", "origin", "url", "https://fetch.example/repository.git")
                setString("remote", "origin", "pushurl", "https://push.example/repository.git")
                save()
            }
        }

        assertEquals(
            "https://push.example/repository.git",
            repository.configuredRemoteTargets("origin", pushing = true).single().url,
        )
    }

    @Test
    fun `pre-cancelled operation stops before mutating workspace`() = withWorkspace { workspace ->
        val repository = JGitProjectRepository(workspace, cancellationCheck = { true })

        assertFailsWith<CancellationException> { repository.init() }
        assertFalse(File(workspace, ".git").exists())
    }

    @Test
    fun `push result treats rejection and empty reports as failures`() {
        assertTrue(
            GitPushResult(
                updates = listOf(GitPushUpdate("refs/heads/main", "UP_TO_DATE", null)),
                messages = emptyList(),
            ).successful,
        )
        assertFalse(
            GitPushResult(
                updates = listOf(GitPushUpdate("refs/heads/main", "REJECTED_NONFASTFORWARD", "rejected")),
                messages = emptyList(),
            ).successful,
        )
        assertFalse(GitPushResult(emptyList(), emptyList()).successful)
    }

    private inline fun withWorkspace(block: (File) -> Unit) {
        val workspace = Files.createTempDirectory("pocketpilot-jgit-test").toFile()
        try {
            block(workspace)
        } finally {
            workspace.deleteRecursively()
        }
    }
}
