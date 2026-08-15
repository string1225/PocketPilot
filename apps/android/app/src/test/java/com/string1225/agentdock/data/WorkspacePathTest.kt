package com.string1225.agentdock.data

import java.io.File
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
        assertFailsWith<IllegalArgumentException> { WorkspacePath.normalize(".AGENTDOCK/state.json") }
    }

    @Test
    fun resolvesOnlyBelowRoot() {
        val root = File(System.getProperty("java.io.tmpdir"), "agentdock-path-test")
        val target = WorkspacePath.resolve(root, "notes/todo.md")
        assertTrue(target.path.startsWith(root.canonicalPath + File.separator))
    }
}
