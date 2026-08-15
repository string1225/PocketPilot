package com.string1225.pocketpilot.data

import java.io.File
import java.nio.file.Files

object WorkspacePath {
    private val windowsDrive = Regex("^[A-Za-z]:")

    fun normalize(input: String): String {
        val candidate = input.trim().replace('\\', '/')
        require(candidate.isNotEmpty()) { "Path must not be empty" }
        require(!candidate.startsWith('/')) { "Absolute paths are not allowed" }
        require(!windowsDrive.containsMatchIn(candidate)) { "Absolute paths are not allowed" }

        val segments = candidate.split('/')
            .filter { it.isNotEmpty() && it != "." }
        require(segments.isNotEmpty()) { "Path must identify a file" }
        require(segments.none { it == ".." }) { "Path traversal is not allowed" }
        require(segments.none(::isInternalSegment)) {
            "Internal repository metadata is not part of the editable workspace"
        }
        require(segments.none { it.contains('\u0000') }) { "Path contains a null byte" }
        return segments.joinToString("/")
    }

    fun resolve(root: File, input: String): File {
        val normalized = normalize(input)
        require(!Files.isSymbolicLink(root.toPath())) { "Workspace root must not be a symbolic link" }
        val canonicalRoot = root.canonicalFile
        require(canonicalRoot == root.absoluteFile.normalize()) { "Workspace root must not redirect through a link" }

        var unresolved = canonicalRoot
        normalized.split('/').forEach { segment ->
            unresolved = File(unresolved, segment)
            require(!Files.isSymbolicLink(unresolved.toPath())) {
                "Workspace paths must not contain symbolic links"
            }
        }

        val target = unresolved.canonicalFile
        val rootPath = canonicalRoot.toPath()
        val targetPath = target.toPath()
        require(targetPath != rootPath && targetPath.startsWith(rootPath)) { "Path escapes the project workspace" }
        require(rootPath.relativize(targetPath).none { isInternalSegment(it.toString()) }) {
            "Resolved path targets internal repository metadata"
        }
        return target
    }

    private fun isInternalSegment(segment: String): Boolean =
        segment.equals(".git", ignoreCase = true) ||
            segment.equals(".pocketpilot", ignoreCase = true) ||
            segment.equals(".agentdock", ignoreCase = true)
}
