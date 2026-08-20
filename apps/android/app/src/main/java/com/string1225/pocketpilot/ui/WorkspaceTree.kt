package com.string1225.pocketpilot.ui

import com.string1225.pocketpilot.model.WorkspaceEntry

internal sealed interface WorkspaceTreeNode {
    val name: String
    val path: String

    data class Directory(
        override val name: String,
        override val path: String,
        val children: List<WorkspaceTreeNode>,
    ) : WorkspaceTreeNode

    data class File(
        val entry: WorkspaceEntry,
        override val name: String,
        override val path: String = entry.path,
    ) : WorkspaceTreeNode
}

internal data class VisibleWorkspaceTreeNode(
    val node: WorkspaceTreeNode,
    val depth: Int,
)

internal fun buildWorkspaceTree(entries: List<WorkspaceEntry>): List<WorkspaceTreeNode> {
    val root = MutableWorkspaceDirectory("", "")
    entries.sortedBy { it.path.lowercase() }.forEach { entry ->
        val parts = entry.path.replace('\\', '/').split('/').filter(String::isNotBlank)
        require(parts.isNotEmpty()) { "Workspace entry path must not be empty" }
        var directory = root
        parts.dropLast(1).forEach { part ->
            val childPath = if (directory.path.isEmpty()) part else "${directory.path}/$part"
            directory = directory.directories.getOrPut(part) {
                MutableWorkspaceDirectory(part, childPath)
            }
        }
        directory.files[parts.last()] = entry
    }
    return root.freezeChildren()
}

internal fun visibleWorkspaceTree(
    roots: List<WorkspaceTreeNode>,
    expandedDirectories: Set<String>,
): List<VisibleWorkspaceTreeNode> {
    val result = mutableListOf<VisibleWorkspaceTreeNode>()
    fun append(nodes: List<WorkspaceTreeNode>, depth: Int) {
        nodes.forEach { node ->
            result += VisibleWorkspaceTreeNode(node, depth)
            if (node is WorkspaceTreeNode.Directory && node.path in expandedDirectories) {
                append(node.children, depth + 1)
            }
        }
    }
    append(roots, 0)
    return result
}

private class MutableWorkspaceDirectory(
    val name: String,
    val path: String,
) {
    val directories = linkedMapOf<String, MutableWorkspaceDirectory>()
    val files = linkedMapOf<String, WorkspaceEntry>()

    fun freezeChildren(): List<WorkspaceTreeNode> = buildList {
        directories.values
            .sortedBy { it.name.lowercase() }
            .forEach { directory ->
                add(
                    WorkspaceTreeNode.Directory(
                        name = directory.name,
                        path = directory.path,
                        children = directory.freezeChildren(),
                    ),
                )
            }
        files.entries
            .sortedBy { it.key.lowercase() }
            .forEach { (name, entry) -> add(WorkspaceTreeNode.File(entry, name)) }
    }
}
