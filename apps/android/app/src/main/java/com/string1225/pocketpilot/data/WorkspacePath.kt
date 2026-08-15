package com.string1225.pocketpilot.data

import java.io.File

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
        require(segments.none {
            it.equals(".git", ignoreCase = true) ||
                it.equals(".pocketpilot", ignoreCase = true) ||
                it.equals(".agentdock", ignoreCase = true)
        }) {
            "Internal repository metadata is not part of the editable workspace"
        }
        require(segments.none { it.contains('\u0000') }) { "Path contains a null byte" }
        return segments.joinToString("/")
    }

    fun resolve(root: File, input: String): File {
        val normalized = normalize(input)
        val canonicalRoot = root.canonicalFile
        val target = File(canonicalRoot, normalized).canonicalFile
        val rootPrefix = canonicalRoot.path + File.separator
        require(target.path.startsWith(rootPrefix)) { "Path escapes the project workspace" }
        return target
    }
}
