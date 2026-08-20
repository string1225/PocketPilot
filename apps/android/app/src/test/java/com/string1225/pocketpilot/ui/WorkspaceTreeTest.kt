package com.string1225.pocketpilot.ui

import com.string1225.pocketpilot.model.WorkspaceEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WorkspaceTreeTest {
    @Test
    fun `builds sorted nested directories instead of a flat path list`() {
        val tree = buildWorkspaceTree(
            listOf(
                entry("src/main/App.kt"),
                entry("README.md"),
                entry("src/test/AppTest.kt"),
                entry("assets/logo.png"),
            ),
        )

        assertEquals(listOf("assets", "src", "README.md"), tree.map { it.name })
        val src = assertIs<WorkspaceTreeNode.Directory>(tree[1])
        assertEquals(listOf("main", "test"), src.children.map { it.name })
        val main = assertIs<WorkspaceTreeNode.Directory>(src.children[0])
        assertEquals("src/main/App.kt", assertIs<WorkspaceTreeNode.File>(main.children.single()).path)
    }

    @Test
    fun `visible rows expand only the selected directory branches`() {
        val tree = buildWorkspaceTree(
            listOf(entry("src/main/App.kt"), entry("src/test/AppTest.kt"), entry("README.md")),
        )

        assertEquals(listOf("src", "README.md"), visibleWorkspaceTree(tree, emptySet()).map { it.node.name })
        val expanded = visibleWorkspaceTree(tree, setOf("src", "src/main"))
        assertEquals(
            listOf("src" to 0, "main" to 1, "App.kt" to 2, "test" to 1, "README.md" to 0),
            expanded.map { it.node.name to it.depth },
        )
    }

    private fun entry(path: String) = WorkspaceEntry(path, size = 10, modifiedAt = 1)
}
