package com.string1225.pocketpilot.data

import java.io.File
import java.nio.file.Files
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkspacePathTest {
    @Test
    fun normalizesSeparatorsAndDots() {
        assertEquals("src/main.ts", WorkspacePath.normalize("./src\\main.ts"))
    }

    @Test
    fun rejectsTraversalAndAbsolutePaths() {
        assertFailsWith<IllegalArgumentException> { WorkspacePath.normalize("../secret.txt") }
        assertFailsWith<IllegalArgumentException> { WorkspacePath.normalize("/etc/passwd") }
        assertFailsWith<IllegalArgumentException> { WorkspacePath.normalize("C:\\secret.txt") }
        assertFailsWith<IllegalArgumentException> { WorkspacePath.normalize(".git/config") }
        assertFailsWith<IllegalArgumentException> { WorkspacePath.normalize(".POCKETPILOT/state.json") }
        assertFailsWith<IllegalArgumentException> { WorkspacePath.normalize(".AGENTDOCK/state.json") }
    }

    @Test
    fun resolvesOnlyBelowRoot() {
        val root = Files.createTempDirectory("pocketpilot-path-test").toFile()
        try {
            val target = WorkspacePath.resolve(root, "notes/todo.md")
            assertTrue(target.path.startsWith(root.canonicalPath + File.separator))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsSymbolicLinksAtEveryExistingPathSegment() {
        val root = Files.createTempDirectory("pocketpilot-path-links").toFile()
        val realDirectory = File(root, "real").apply { mkdirs() }
        val realFile = File(realDirectory, "file.txt").apply { writeText("safe") }
        val directoryLink = File(root, "directory-link")
        val fileLink = File(root, "file-link")
        try {
            val linksCreated = runCatching {
                Files.createSymbolicLink(directoryLink.toPath(), realDirectory.toPath())
                Files.createSymbolicLink(fileLink.toPath(), realFile.toPath())
            }.isSuccess
            assumeTrue("Symbolic links are unavailable on this test host", linksCreated)

            assertFailsWith<IllegalArgumentException> {
                WorkspacePath.resolve(root, "directory-link/created.txt")
            }
            assertFailsWith<IllegalArgumentException> {
                WorkspacePath.resolve(root, "file-link")
            }
        } finally {
            Files.deleteIfExists(directoryLink.toPath())
            Files.deleteIfExists(fileLink.toPath())
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsSymbolicLinkThatAliasesInternalMetadata() {
        val root = Files.createTempDirectory("pocketpilot-path-internal-link").toFile()
        val gitDirectory = File(root, ".git").apply { mkdirs() }
        val config = File(gitDirectory, "config").apply { writeText("safe") }
        val alias = File(root, "apparently-safe.txt")
        try {
            val created = runCatching { Files.createSymbolicLink(alias.toPath(), config.toPath()) }.isSuccess
            assumeTrue("Symbolic links are unavailable on this test host", created)

            assertFailsWith<IllegalArgumentException> {
                WorkspacePath.resolve(root, "apparently-safe.txt")
            }
        } finally {
            Files.deleteIfExists(alias.toPath())
            root.deleteRecursively()
        }
    }
}
