package com.string1225.pocketpilot.data

import android.content.ContentValues
import com.string1225.pocketpilot.model.CheckpointSource
import com.string1225.pocketpilot.model.Project
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID

internal fun interface ProjectProvisioningRecoveryHook {
    /** Test seam invoked after tree deletion but before the database row and marker are removed. */
    fun afterProjectTreeDeleted(projectId: String)
}

class ProjectRepository internal constructor(
    private val database: PocketPilotDatabase,
    private val projectsRoot: File,
    private val recoveryHook: ProjectProvisioningRecoveryHook,
) {
    constructor(database: PocketPilotDatabase, projectsRoot: File) : this(
        database,
        projectsRoot,
        ProjectProvisioningRecoveryHook {},
    )

    lateinit var checkpoints: CheckpointRepository

    init {
        projectsRoot.mkdirs()
    }

    fun ensureDefaultProject(): Project = list().firstOrNull() ?: create("个人项目")

    fun list(): List<Project> {
        val projects = mutableListOf<Project>()
        database.readableDatabase.query(
            "projects",
            arrayOf("id", "name", "created_at", "updated_at"),
            null,
            null,
            null,
            null,
            "updated_at DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                projects += Project(
                    id = cursor.getString(0),
                    name = cursor.getString(1),
                    createdAt = cursor.getLong(2),
                    updatedAt = cursor.getLong(3),
                )
            }
        }
        return projects
    }

    fun create(rawName: String): Project = create(rawName, retainProvisioningMarker = false)

    /**
     * Creates the database row and empty workspace while retaining a durable marker until the
     * caller has finished its own provisioning work (for example, clone plus checkpoint).
     */
    fun beginProvisioning(rawName: String): Project =
        create(rawName, retainProvisioningMarker = true)

    /**
     * Makes a provisioned project visible to onboarding only after its workspace has at least one
     * checkpoint. The marker lives outside the workspace so a Git clone cannot overwrite it.
     */
    fun completeProvisioning(projectId: String) {
        require(exists(projectId)) { "Project does not exist" }
        val workspace = ProjectPaths.workspaceDirectory(projectsRoot, projectId)
        require(workspace.isDirectory) { "Project workspace is unavailable" }
        require(checkpoints.list(projectId).isNotEmpty()) { "Project checkpoint is unavailable" }
        deleteProvisioningMarker(provisioningMarker(projectId), requireExisting = true)
    }

    /** Rejects projects that were inserted but did not finish their provisioning transaction. */
    fun requireProvisioned(projectId: String) {
        require(exists(projectId)) { "Project does not exist" }
        require(!hasProvisioningMarker(projectId)) { "Project provisioning is incomplete" }
        require(ProjectPaths.workspaceDirectory(projectsRoot, projectId).isDirectory) {
            "Project workspace is unavailable"
        }
        require(checkpoints.list(projectId).isNotEmpty()) { "Project checkpoint is unavailable" }
    }

    /**
     * Removes projects left behind by process death during provisioning. This runs before the
     * one-time onboarding inference, so an interrupted row can never make a fresh install look
     * like an upgraded installation.
     */
    fun recoverInterruptedProvisioning(): Int {
        val interruptedProjects = projectsRoot.listFiles().orEmpty()
            .mapNotNull { marker ->
                val projectId = projectIdFromProvisioningMarker(marker.name) ?: return@mapNotNull null
                require(
                    Files.exists(marker.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                        !Files.isSymbolicLink(marker.toPath()) &&
                        marker.isFile,
                ) { "Project provisioning marker is unsafe" }
                projectId to marker
            }
            .sortedBy { (projectId, _) -> projectId }
        interruptedProjects.forEach { (projectId, marker) ->
            // The marker is outside the project tree and deliberately removed last. A process
            // death at any earlier point leaves the same operation discoverable and retryable.
            ProjectTreeDeleter.delete(projectsRoot, projectId)
            recoveryHook.afterProjectTreeDeleted(projectId)
            database.writableDatabase.delete("projects", "id = ?", arrayOf(projectId))
            deleteProvisioningMarker(marker, requireExisting = true)
        }
        return interruptedProjects.size
    }

    private fun create(rawName: String, retainProvisioningMarker: Boolean): Project {
        val name = rawName.trim()
        require(name.isNotEmpty()) { "Project name must not be empty" }
        require(name.length <= 80) { "Project name is too long" }

        val now = System.currentTimeMillis()
        val project = Project(UUID.randomUUID().toString(), name, now, now)
        val projectDirectory = ProjectPaths.projectDirectory(projectsRoot, project.id)
        val workspace = ProjectPaths.workspaceDirectory(projectsRoot, project.id)
        val marker = provisioningMarker(project.id)
        var markerCreated = false
        var projectDirectoryCreated = false
        val values = ContentValues().apply {
            put("id", project.id)
            put("name", project.name)
            put("created_at", project.createdAt)
            put("updated_at", project.updatedAt)
        }
        try {
            check(marker.createNewFile()) { "Unable to mark project provisioning" }
            markerCreated = true
            check(projectDirectory.mkdir()) { "Unable to create project directory" }
            projectDirectoryCreated = true
            check(workspace.mkdir()) { "Unable to create project workspace" }
            check(database.writableDatabase.insertOrThrow("projects", null, values) != -1L)
            checkpoints.create(project.id, CheckpointSource.USER, "项目初始状态")
            if (!retainProvisioningMarker) completeProvisioning(project.id)
        } catch (error: Throwable) {
            rollbackFailedCreate(
                projectId = project.id,
                projectDirectoryCreated = projectDirectoryCreated,
                markerCreated = markerCreated,
                operationFailure = error,
            )
            throw error
        }
        return project
    }

    fun delete(projectId: String) {
        require(exists(projectId)) { "Project does not exist" }
        ProjectPaths.projectDirectory(projectsRoot, projectId)
        database.writableDatabase.delete("projects", "id = ?", arrayOf(projectId))
        ProjectTreeDeleter.delete(projectsRoot, projectId)
        if (hasProvisioningMarker(projectId)) {
            deleteProvisioningMarker(provisioningMarker(projectId), requireExisting = true)
        }
    }

    fun exists(projectId: String): Boolean {
        return database.readableDatabase.rawQuery(
            "SELECT 1 FROM projects WHERE id = ? LIMIT 1",
            arrayOf(projectId),
        ).use { it.moveToFirst() }
    }

    fun workspaceDirectory(projectId: String): File {
        require(exists(projectId)) { "Project does not exist" }
        return ProjectPaths.workspaceDirectory(projectsRoot, projectId).also { workspace ->
            check(workspace.mkdirs() || workspace.isDirectory) { "Unable to access project workspace" }
        }
    }

    fun touch(projectId: String, updatedAt: Long = System.currentTimeMillis()) {
        val values = ContentValues().apply { put("updated_at", updatedAt) }
        database.writableDatabase.update("projects", values, "id = ?", arrayOf(projectId))
    }

    internal fun hasProvisioningMarker(projectId: String): Boolean =
        Files.exists(provisioningMarker(projectId).toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun provisioningMarker(projectId: String): File {
        require(isProjectId(projectId)) { "Invalid project id" }
        val root = projectsRoot.canonicalFile
        return File(root, "$PROVISIONING_MARKER_PREFIX$projectId").absoluteFile.normalize().also { marker ->
            require(marker.parentFile == root) { "Project provisioning marker escapes storage root" }
        }
    }

    private fun deleteProvisioningMarker(marker: File, requireExisting: Boolean) {
        val path = marker.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            require(!requireExisting) { "Project provisioning marker is unavailable" }
            return
        }
        require(!Files.isSymbolicLink(path) && marker.isFile) {
            "Project provisioning marker is unsafe"
        }
        Files.delete(path)
    }

    private fun rollbackFailedCreate(
        projectId: String,
        projectDirectoryCreated: Boolean,
        markerCreated: Boolean,
        operationFailure: Throwable,
    ) {
        if (projectDirectoryCreated) {
            val treeFailure = runCatching { ProjectTreeDeleter.delete(projectsRoot, projectId) }
                .exceptionOrNull()
            if (treeFailure != null) {
                operationFailure.addSuppressed(treeFailure)
                return
            }
        }
        val databaseFailure = runCatching {
            database.writableDatabase.delete("projects", "id = ?", arrayOf(projectId))
        }.exceptionOrNull()
        if (databaseFailure != null) {
            operationFailure.addSuppressed(databaseFailure)
            return
        }
        if (markerCreated) {
            runCatching {
                deleteProvisioningMarker(provisioningMarker(projectId), requireExisting = true)
            }.exceptionOrNull()?.let(operationFailure::addSuppressed)
        }
    }

    private fun isProjectId(value: String): Boolean =
        runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

    companion object {
        internal const val PROVISIONING_MARKER_PREFIX = ".pocketpilot-provisioning-"

        internal fun projectIdFromProvisioningMarker(name: String): String? {
            if (!name.startsWith(PROVISIONING_MARKER_PREFIX)) return null
            val projectId = name.removePrefix(PROVISIONING_MARKER_PREFIX)
            return projectId.takeIf {
                runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false)
            }
        }
    }
}
