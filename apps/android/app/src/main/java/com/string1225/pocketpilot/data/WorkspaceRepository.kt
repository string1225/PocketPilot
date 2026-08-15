package com.string1225.pocketpilot.data

import com.string1225.pocketpilot.model.Checkpoint
import com.string1225.pocketpilot.model.CheckpointSource
import com.string1225.pocketpilot.model.WorkspaceEntry
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

class WorkspaceRepository(
    private val projectsRoot: File,
    private val projectExists: (String) -> Boolean,
    private val checkpoints: CheckpointRepository,
    private val touchProject: (String) -> Unit,
) {
    fun list(projectId: String): List<WorkspaceEntry> {
        val workspace = workspace(projectId)
        if (!workspace.exists()) return emptyList()
        return workspace.walkTopDown()
            .onEnter { directory ->
                directory == workspace || (
                    !Files.isSymbolicLink(directory.toPath()) &&
                        !isInternalPath(directory.relativeTo(workspace).invariantSeparatorsPath)
                    )
            }
            .filter {
                it.isFile &&
                    !Files.isSymbolicLink(it.toPath()) &&
                    !isInternalPath(it.relativeTo(workspace).invariantSeparatorsPath)
            }
            .map { file ->
                WorkspaceEntry(
                    path = file.relativeTo(workspace).invariantSeparatorsPath,
                    size = file.length(),
                    modifiedAt = file.lastModified(),
                )
            }
            .sortedBy { it.path.lowercase() }
            .toList()
    }

    fun read(projectId: String, path: String): String {
        val file = file(projectId, path)
        require(file.isFile) { "File does not exist" }
        require(file.length() <= CheckpointRepository.MAX_TEXT_FILE_BYTES) { "File is too large" }
        val bytes = file.readBytes()
        require(bytes.none { it == 0.toByte() }) { "Binary files are not supported" }
        return bytes.toString(StandardCharsets.UTF_8)
    }

    fun create(
        projectId: String,
        path: String,
        content: String = "",
        source: CheckpointSource = CheckpointSource.USER,
    ): Checkpoint = write(projectId, path, content, createOnly = true, source = source, action = "创建 $path")

    fun write(
        projectId: String,
        path: String,
        content: String,
        createOnly: Boolean = false,
        source: CheckpointSource = CheckpointSource.USER,
        action: String = "修改 $path",
    ): Checkpoint {
        validateContent(content)
        val destination = file(projectId, path)
        require(!createOnly || !destination.exists()) { "File already exists" }
        require(!destination.exists() || destination.isFile) { "Path is not a file" }
        val previousContent = destination.takeIf(File::isFile)?.readBytes()
        destination.parentFile?.let { check(it.mkdirs() || it.isDirectory) { "Unable to create parent directory" } }
        atomicWrite(destination, content)
        return checkpointAfterMutation(projectId, source, action) {
            if (previousContent == null) {
                check(!destination.exists() || destination.delete()) { "Unable to roll back created file" }
                removeEmptyParents(destination.parentFile, workspace(projectId))
            } else {
                atomicWrite(destination, previousContent)
            }
        }
    }

    fun delete(
        projectId: String,
        path: String,
        source: CheckpointSource = CheckpointSource.USER,
    ): Checkpoint {
        val target = file(projectId, path)
        require(target.isFile) { "File does not exist" }
        val previousContent = target.readBytes()
        check(target.delete()) { "Unable to delete file" }
        removeEmptyParents(target.parentFile, workspace(projectId))
        return checkpointAfterMutation(projectId, source, "删除 $path") {
            target.parentFile?.let { check(it.mkdirs() || it.isDirectory) { "Unable to restore parent directory" } }
            atomicWrite(target, previousContent)
        }
    }

    fun move(
        projectId: String,
        from: String,
        to: String,
        source: CheckpointSource = CheckpointSource.USER,
    ): Checkpoint {
        val sourceFile = file(projectId, from)
        val destination = file(projectId, to)
        require(sourceFile.isFile) { "Source file does not exist" }
        require(!destination.exists()) { "Destination already exists" }
        destination.parentFile?.let { check(it.mkdirs() || it.isDirectory) { "Unable to create parent directory" } }
        moveFile(sourceFile, destination)
        removeEmptyParents(sourceFile.parentFile, workspace(projectId))
        return checkpointAfterMutation(projectId, source, "移动 $from 到 $to") {
            sourceFile.parentFile?.let { check(it.mkdirs() || it.isDirectory) { "Unable to restore parent directory" } }
            moveFile(destination, sourceFile)
            removeEmptyParents(destination.parentFile, workspace(projectId))
        }
    }

    fun search(projectId: String, query: String, limit: Int = DEFAULT_SEARCH_LIMIT): List<SearchMatch> {
        val needle = query.trim()
        require(needle.isNotEmpty()) { "Search query must not be empty" }
        val boundedLimit = limit.coerceIn(1, MAX_SEARCH_LIMIT)
        val matches = mutableListOf<SearchMatch>()
        for (entry in list(projectId)) {
            if (entry.size > CheckpointRepository.MAX_TEXT_FILE_BYTES) continue
            val content = runCatching { read(projectId, entry.path) }.getOrNull() ?: continue
            content.lineSequence().forEachIndexed { index, line ->
                if (line.contains(needle, ignoreCase = true) && matches.size < boundedLimit) {
                    matches += SearchMatch(entry.path, index + 1, line.take(MAX_SEARCH_LINE_LENGTH))
                }
            }
            if (matches.size >= boundedLimit) break
        }
        return matches
    }

    fun patch(
        projectId: String,
        path: String,
        oldText: String,
        newText: String,
        replaceAll: Boolean = false,
        source: CheckpointSource = CheckpointSource.AGENT,
    ): Checkpoint {
        require(oldText.isNotEmpty()) { "oldText must not be empty" }
        val content = read(projectId, path)
        val occurrences = countOccurrences(content, oldText)
        require(occurrences > 0) { "Patch context was not found" }
        require(replaceAll || occurrences == 1) { "Patch context is ambiguous; set replaceAll to replace every match" }
        val updated = if (replaceAll) content.replace(oldText, newText) else content.replaceFirst(oldText, newText)
        return write(
            projectId = projectId,
            path = path,
            content = updated,
            source = source,
            action = "Patch $path",
        )
    }

    private fun workspace(projectId: String): File {
        require(projectExists(projectId)) { "Project does not exist" }
        val workspace = ProjectPaths.workspaceDirectory(projectsRoot, projectId)
        check(workspace.mkdirs() || workspace.isDirectory) { "Unable to access project workspace" }
        return workspace
    }

    private fun file(projectId: String, path: String): File = WorkspacePath.resolve(workspace(projectId), path)

    private fun validateContent(content: String) {
        val size = content.toByteArray(StandardCharsets.UTF_8).size
        require(size <= CheckpointRepository.MAX_TEXT_FILE_BYTES) { "Text file exceeds the 1 MiB MVP limit" }
        require(!content.contains('\u0000')) { "Binary content is not supported" }
    }

    private fun atomicWrite(destination: File, content: String) {
        atomicWrite(destination, content.toByteArray(StandardCharsets.UTF_8))
    }

    private fun atomicWrite(destination: File, content: ByteArray) {
        val temporary = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.tmp")
        temporary.writeBytes(content)
        try {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    private fun moveFile(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath())
        }
    }

    private inline fun checkpointAfterMutation(
        projectId: String,
        source: CheckpointSource,
        action: String,
        rollback: () -> Unit,
    ): Checkpoint {
        try {
            touchProject(projectId)
            return checkpoints.create(projectId, source, action)
        } catch (checkpointError: Throwable) {
            try {
                rollback()
            } catch (rollbackError: Throwable) {
                val combined = IllegalStateException(
                    "Checkpoint failed and the Workspace change could not be rolled back",
                    checkpointError,
                )
                combined.addSuppressed(rollbackError)
                throw combined
            }
            throw checkpointError
        }
    }

    private fun removeEmptyParents(start: File?, root: File) {
        var directory = start
        while (directory != null && directory != root && directory.listFiles()?.isEmpty() == true) {
            if (!directory.delete()) break
            directory = directory.parentFile
        }
    }

    private fun countOccurrences(content: String, needle: String): Int {
        var count = 0
        var offset = 0
        while (true) {
            val match = content.indexOf(needle, offset)
            if (match < 0) return count
            count += 1
            offset = match + needle.length
        }
    }

    private fun isInternalPath(path: String): Boolean = path.split('/').any {
        it.equals(".git", ignoreCase = true) ||
            it.equals(".pocketpilot", ignoreCase = true) ||
            it.equals(".agentdock", ignoreCase = true)
    }

    data class SearchMatch(val path: String, val line: Int, val preview: String)

    companion object {
        private const val DEFAULT_SEARCH_LIMIT = 100
        private const val MAX_SEARCH_LIMIT = 500
        private const val MAX_SEARCH_LINE_LENGTH = 240
    }
}
