package com.string1225.pocketpilot.data

import android.content.ContentValues
import com.string1225.pocketpilot.model.CheckpointSource
import com.string1225.pocketpilot.model.Project
import java.io.File
import java.util.UUID

class ProjectRepository(
    private val database: PocketPilotDatabase,
    private val projectsRoot: File,
) {
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

    fun create(rawName: String): Project {
        val name = rawName.trim()
        require(name.isNotEmpty()) { "Project name must not be empty" }
        require(name.length <= 80) { "Project name is too long" }

        val now = System.currentTimeMillis()
        val project = Project(UUID.randomUUID().toString(), name, now, now)
        val workspace = ProjectPaths.workspaceDirectory(projectsRoot, project.id)
        check(workspace.mkdirs() || workspace.isDirectory) { "Unable to create project workspace" }

        val values = ContentValues().apply {
            put("id", project.id)
            put("name", project.name)
            put("created_at", project.createdAt)
            put("updated_at", project.updatedAt)
        }
        try {
            check(database.writableDatabase.insertOrThrow("projects", null, values) != -1L)
            checkpoints.create(project.id, CheckpointSource.USER, "项目初始状态")
        } catch (error: Throwable) {
            database.writableDatabase.delete("projects", "id = ?", arrayOf(project.id))
            workspace.parentFile?.deleteRecursively()
            throw error
        }
        return project
    }

    fun delete(projectId: String) {
        require(exists(projectId)) { "Project does not exist" }
        val directory = ProjectPaths.projectDirectory(projectsRoot, projectId)
        database.writableDatabase.delete("projects", "id = ?", arrayOf(projectId))
        check(!directory.exists() || directory.deleteRecursively()) { "Unable to remove project files" }
    }

    fun exists(projectId: String): Boolean {
        return database.readableDatabase.rawQuery(
            "SELECT 1 FROM projects WHERE id = ? LIMIT 1",
            arrayOf(projectId),
        ).use { it.moveToFirst() }
    }

    fun touch(projectId: String, updatedAt: Long = System.currentTimeMillis()) {
        val values = ContentValues().apply { put("updated_at", updatedAt) }
        database.writableDatabase.update("projects", values, "id = ?", arrayOf(projectId))
    }
}
