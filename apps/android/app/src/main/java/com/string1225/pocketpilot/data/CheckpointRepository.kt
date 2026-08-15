package com.string1225.pocketpilot.data

import android.content.ContentValues
import android.database.Cursor
import com.string1225.pocketpilot.model.Checkpoint
import com.string1225.pocketpilot.model.CheckpointSource
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

class CheckpointRepository(
    private val database: PocketPilotDatabase,
    private val projectsRoot: File,
    private val projectExists: (String) -> Boolean,
) {
    fun create(projectId: String, source: CheckpointSource, description: String): Checkpoint {
        require(projectExists(projectId)) { "Project does not exist" }
        val files = snapshotWorkspace(projectId)
        val parent = latestId(projectId)
        val previousHashes = parent?.let(::loadHashes).orEmpty()
        val currentHashes = files.associate { it.path to it.hash }
        val diff = DiffSummary.between(previousHashes, currentHashes)
        val checkpoint = Checkpoint(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            parentId = parent,
            source = source,
            description = description.take(MAX_DESCRIPTION_LENGTH),
            createdAt = System.currentTimeMillis(),
            addedFiles = diff.added,
            modifiedFiles = diff.modified,
            deletedFiles = diff.deleted,
            totalFiles = files.size,
        )

        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val checkpointValues = ContentValues().apply {
                put("id", checkpoint.id)
                put("project_id", checkpoint.projectId)
                put("parent_id", checkpoint.parentId)
                put("source", checkpoint.source.value)
                put("description", checkpoint.description)
                put("created_at", checkpoint.createdAt)
                put("added_files", checkpoint.addedFiles)
                put("modified_files", checkpoint.modifiedFiles)
                put("deleted_files", checkpoint.deletedFiles)
                put("total_files", checkpoint.totalFiles)
            }
            db.insertOrThrow("checkpoints", null, checkpointValues)
            files.forEach { file ->
                val fileValues = ContentValues().apply {
                    put("checkpoint_id", checkpoint.id)
                    put("path", file.path)
                    put("content", file.content)
                    put("content_hash", file.hash)
                    put("size", file.size)
                }
                db.insertOrThrow("checkpoint_files", null, fileValues)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return checkpoint
    }

    fun list(projectId: String): List<Checkpoint> {
        require(projectExists(projectId)) { "Project does not exist" }
        val checkpoints = mutableListOf<Checkpoint>()
        database.readableDatabase.query(
            "checkpoints",
            arrayOf(
                "id", "project_id", "parent_id", "source", "description", "created_at",
                "added_files", "modified_files", "deleted_files", "total_files",
            ),
            "project_id = ?",
            arrayOf(projectId),
            null,
            null,
            "created_at DESC, rowid DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                checkpoints += Checkpoint(
                    id = cursor.getString(0),
                    projectId = cursor.getString(1),
                    parentId = cursor.getString(2),
                    source = CheckpointSource.fromValue(cursor.getString(3)),
                    description = cursor.getString(4),
                    createdAt = cursor.getLong(5),
                    addedFiles = cursor.getInt(6),
                    modifiedFiles = cursor.getInt(7),
                    deletedFiles = cursor.getInt(8),
                    totalFiles = cursor.getInt(9),
                )
            }
        }
        return checkpoints
    }

    fun restore(projectId: String, checkpointId: String): Checkpoint {
        require(projectExists(projectId)) { "Project does not exist" }
        val target = loadFiles(checkpointId, projectId)
        val workspace = ProjectPaths.workspaceDirectory(projectsRoot, projectId)
        workspace.mkdirs()

        // Persist the current state before the destructive part of restore so
        // an interrupted or unwanted restore always has a recovery point.
        create(projectId, CheckpointSource.USER, "恢复前安全快照")

        workspace.walkBottomUp()
            .onEnter { directory ->
                directory == workspace || (
                    !Files.isSymbolicLink(directory.toPath()) &&
                        !isInternalPath(directory.relativeTo(workspace).invariantSeparatorsPath)
                    )
            }
            .filter {
                it != workspace &&
                    !isInternalPath(it.relativeTo(workspace).invariantSeparatorsPath)
            }
            .forEach { file ->
                check(!file.exists() || file.delete()) { "Unable to clear ${file.relativeTo(workspace).invariantSeparatorsPath}" }
            }

        target.forEach { snapshot ->
            val destination = WorkspacePath.resolve(workspace, snapshot.path)
            destination.parentFile?.mkdirs()
            atomicWrite(destination, snapshot.content)
        }
        return create(projectId, CheckpointSource.USER, "恢复到检查点 ${checkpointId.take(8)}")
    }

    private fun latestId(projectId: String): String? = database.readableDatabase.rawQuery(
        "SELECT id FROM checkpoints WHERE project_id = ? ORDER BY created_at DESC, rowid DESC LIMIT 1",
        arrayOf(projectId),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun loadHashes(checkpointId: String): Map<String, String> {
        val hashes = mutableMapOf<String, String>()
        database.readableDatabase.query(
            "checkpoint_files",
            arrayOf("path", "content_hash"),
            "checkpoint_id = ?",
            arrayOf(checkpointId),
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) hashes[cursor.getString(0)] = cursor.getString(1)
        }
        return hashes
    }

    private fun loadFiles(checkpointId: String, projectId: String): List<SnapshotFile> {
        val belongs = database.readableDatabase.rawQuery(
            "SELECT 1 FROM checkpoints WHERE id = ? AND project_id = ? LIMIT 1",
            arrayOf(checkpointId, projectId),
        ).use { it.moveToFirst() }
        require(belongs) { "Checkpoint does not belong to this project" }

        val files = mutableListOf<SnapshotFile>()
        database.readableDatabase.query(
            "checkpoint_files",
            arrayOf("path", "content", "content_hash", "size"),
            "checkpoint_id = ?",
            arrayOf(checkpointId),
            null,
            null,
            "path ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val content = when (cursor.getType(1)) {
                    Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(1)
                    Cursor.FIELD_TYPE_STRING -> cursor.getString(1).toByteArray(Charsets.UTF_8)
                    else -> throw IllegalStateException("Checkpoint file content is invalid")
                }
                val hash = cursor.getString(2)
                val size = cursor.getLong(3)
                require(content.size.toLong() == size && sha256(content) == hash) {
                    "Checkpoint file content is corrupted"
                }
                files += SnapshotFile(cursor.getString(0), content, hash, size)
            }
        }
        return files
    }

    private fun snapshotWorkspace(projectId: String): List<SnapshotFile> {
        val workspace = ProjectPaths.workspaceDirectory(projectsRoot, projectId)
        if (!workspace.exists()) return emptyList()
        val candidates = workspace.walkTopDown()
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
            .sortedBy { it.relativeTo(workspace).invariantSeparatorsPath }
            .toList()
        var totalBytes = 0L
        return candidates.map { file ->
            require(file.length() <= MAX_CHECKPOINT_FILE_BYTES) {
                "File is too large for a checkpoint: ${file.name}"
            }
            val bytes = file.readBytes()
            require(bytes.size.toLong() <= MAX_CHECKPOINT_FILE_BYTES) {
                "File changed while creating checkpoint: ${file.name}"
            }
            totalBytes += bytes.size
            require(totalBytes <= MAX_CHECKPOINT_TOTAL_BYTES) { "Workspace is too large for a checkpoint" }
            SnapshotFile(
                path = file.relativeTo(workspace).invariantSeparatorsPath,
                content = bytes,
                hash = sha256(bytes),
                size = bytes.size.toLong(),
            )
        }
            .sortedBy { it.path }
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

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun isInternalPath(path: String): Boolean = path.split('/').any {
        it.equals(".git", ignoreCase = true) ||
            it.equals(".pocketpilot", ignoreCase = true) ||
            it.equals(".agentdock", ignoreCase = true)
    }

    private data class SnapshotFile(val path: String, val content: ByteArray, val hash: String, val size: Long)

    companion object {
        const val MAX_TEXT_FILE_BYTES = 1_048_576L
        private const val MAX_CHECKPOINT_FILE_BYTES = 32L * 1024 * 1024
        private const val MAX_CHECKPOINT_TOTAL_BYTES = 128L * 1024 * 1024
        private const val MAX_DESCRIPTION_LENGTH = 240
    }
}
