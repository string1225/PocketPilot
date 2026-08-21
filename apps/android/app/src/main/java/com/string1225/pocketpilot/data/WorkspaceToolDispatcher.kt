package com.string1225.pocketpilot.data

import com.string1225.pocketpilot.model.Checkpoint
import com.string1225.pocketpilot.model.CheckpointSource
import com.string1225.pocketpilot.model.ToolApprovalRequest
import com.string1225.pocketpilot.runtime.ActiveRunRegistry
import com.string1225.pocketpilot.runtime.NativeToolRequest
import com.string1225.pocketpilot.runtime.NativeToolResult
import com.string1225.pocketpilot.runtime.ProjectToolExecutionGate
import com.string1225.pocketpilot.runtime.ToolApprovalCoordinator
import com.string1225.pocketpilot.runtime.ToolDispatchException
import com.string1225.pocketpilot.runtime.ToolRequestDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class WorkspaceToolDispatcher(
    private val workspace: WorkspaceRepository,
    private val activeRuns: ActiveRunRegistry,
    private val approvals: ToolApprovalCoordinator,
    private val projectGate: ProjectToolExecutionGate = ProjectToolExecutionGate(),
) : ToolRequestDispatcher {
    override suspend fun dispatch(request: NativeToolRequest): NativeToolResult =
        projectGate.withProject(request.projectId) { withContext(Dispatchers.IO) {
        try {
            requireAuthorized(request)
            val arguments = JSONObject(request.argumentsJson)
            val data = when (request.name) {
                "workspace.list" -> arguments.withOnlyKeys("path") { list(request.projectId, this) }
                "workspace.read" -> arguments.withOnlyKeys("path") { read(request.projectId, this) }
                "workspace.write" -> arguments.withOnlyKeys("path", "content") {
                    write(request.projectId, this, createOnly = false)
                }
                "workspace.create" -> arguments.withOnlyKeys("path", "content") {
                    write(request.projectId, this, createOnly = true)
                }
                "workspace.delete" -> arguments.withOnlyKeys("path") { delete(request, this) }
                "workspace.move" -> arguments.withOnlyKeys("from", "to") { move(request.projectId, this) }
                "workspace.search" -> arguments.withOnlyKeys("query", "limit") { search(request.projectId, this) }
                "workspace.patch" -> arguments.withOnlyKeys(
                    "path",
                    "oldText",
                    "newText",
                    "expectedOccurrences",
                ) { patch(request.projectId, this) }
                else -> throw ToolDispatchException("UNKNOWN_TOOL", "Unknown Native Tool: ${request.name}")
            }
            boundedNativeToolSuccess(data)
        } catch (error: ToolDispatchException) {
            throw error
        } catch (error: JSONException) {
            throw ToolDispatchException("INVALID_ARGUMENTS", "Tool arguments must be a JSON object", error)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IllegalArgumentException) {
            throw ToolDispatchException("INVALID_ARGUMENTS", error.message ?: "Invalid Tool arguments", error)
        } catch (error: Exception) {
            throw ToolDispatchException("WORKSPACE_OPERATION_FAILED", error.message ?: "Workspace operation failed", error)
        }
        } }

    private suspend fun delete(request: NativeToolRequest, arguments: JSONObject): JSONObject {
        val path = WorkspacePath.normalize(arguments.requiredString("path"))
        val approved = approvals.awaitDecision(
            ToolApprovalRequest(
                id = request.id,
                runId = request.runId,
                projectId = request.projectId,
                toolName = request.name,
                title = "允许 Agent 删除文件？",
                detail = path,
            ),
        )
        if (!approved) {
            throw ToolDispatchException("PERMISSION_DENIED", "用户拒绝了删除 $path")
        }
        requireAuthorized(request)
        val checkpoint = workspace.delete(request.projectId, path, CheckpointSource.AGENT)
        return checkpointResult(path, checkpoint)
    }

    private fun requireAuthorized(request: NativeToolRequest) {
        if (!activeRuns.authorizes(request.runId, request.projectId)) {
            throw ToolDispatchException(
                code = "UNAUTHORIZED_RUN",
                message = "Tool request is not authorized for this active Agent Run",
            )
        }
    }

    private fun list(projectId: String, arguments: JSONObject): JSONObject {
        val rawPath = if (arguments.has("path")) {
            arguments.requiredString("path", allowEmpty = true)
        } else {
            ""
        }.trim().replace('\\', '/').trim('/')
        val requestedPath = if (rawPath.isEmpty() || rawPath == ".") "" else WorkspacePath.normalize(rawPath)
        val entries = workspace.list(projectId)
            .filter { requestedPath.isEmpty() || it.path == requestedPath || it.path.startsWith("$requestedPath/") }
        val budget = SerializedJsonBudget()
        val encodedEntries = JSONArray()
        for (entry in entries) {
            if (
                !budget.tryPut(
                    encodedEntries,
                    JSONObject()
                        .put("path", entry.path)
                        .put("size", entry.size)
                        .put("modifiedAt", entry.modifiedAt),
                )
            ) {
                break
            }
        }
        return JSONObject()
            .put("entries", encodedEntries)
            .put("totalEntries", entries.size)
            .put("returnedEntries", budget.acceptedEntries)
            .put("truncated", budget.acceptedEntries < entries.size)
    }

    private fun read(projectId: String, arguments: JSONObject): JSONObject {
        val path = arguments.requiredString("path")
        return JSONObject()
            .put("path", WorkspacePath.normalize(path))
            .put("content", workspace.read(projectId, path))
    }

    private fun write(projectId: String, arguments: JSONObject, createOnly: Boolean): JSONObject {
        val path = arguments.requiredString("path")
        val content = arguments.requiredString("content", allowEmpty = true)
        val checkpoint = if (createOnly) {
            workspace.create(projectId, path, content, CheckpointSource.AGENT)
        } else {
            workspace.write(projectId, path, content, source = CheckpointSource.AGENT)
        }
        return checkpointResult(path, checkpoint)
    }

    private fun move(projectId: String, arguments: JSONObject): JSONObject {
        val from = arguments.requiredString("from")
        val to = arguments.requiredString("to")
        val checkpoint = workspace.move(projectId, from, to, CheckpointSource.AGENT)
        return checkpointResult(to, checkpoint).put("from", WorkspacePath.normalize(from))
    }

    private fun search(projectId: String, arguments: JSONObject): JSONObject {
        val query = arguments.requiredString("query")
        val limit = arguments.intOrDefault("limit", 100)
        require(limit in 1..500) { "limit must be in 1..500" }
        val matches = workspace.search(projectId, query, limit)
        return JSONObject().put(
            "matches",
            JSONArray().apply {
                matches.forEach { match ->
                    put(
                        JSONObject()
                            .put("path", match.path)
                            .put("line", match.line)
                            .put("preview", match.preview),
                    )
                }
            },
        )
    }

    private fun patch(projectId: String, arguments: JSONObject): JSONObject {
        val path = arguments.requiredString("path")
        val oldText = arguments.requiredString("oldText")
        val newText = arguments.requiredString("newText", allowEmpty = true)
        val expectedOccurrences = arguments.intOrDefault("expectedOccurrences", 1)
        require(expectedOccurrences == 1) { "MVP patch requires expectedOccurrences = 1" }
        val checkpoint = workspace.patch(
            projectId = projectId,
            path = path,
            oldText = oldText,
            newText = newText,
            replaceAll = false,
            source = CheckpointSource.AGENT,
        )
        return checkpointResult(path, checkpoint)
    }

    private fun checkpointResult(path: String, checkpoint: Checkpoint): JSONObject = JSONObject()
        .put("path", WorkspacePath.normalize(path))
        .put("checkpointId", checkpoint.id)
        .put("description", checkpoint.description)

    private fun JSONObject.requiredString(key: String, allowEmpty: Boolean = false): String {
        require(has(key) && !isNull(key)) { "$key is required" }
        val value = get(key)
        require(value is String) { "$key must be a string" }
        require(allowEmpty || value.isNotEmpty()) { "$key must not be empty" }
        return value
    }

    private inline fun <T> JSONObject.withOnlyKeys(vararg allowed: String, block: JSONObject.() -> T): T {
        val allowedSet = allowed.toSet()
        val keys = keys()
        while (keys.hasNext()) {
            require(keys.next() in allowedSet) { "Unknown argument" }
        }
        return block()
    }

    private fun JSONObject.intOrDefault(key: String, default: Int): Int {
        if (!has(key)) return default
        require(!isNull(key)) { "$key must be an integer" }
        return when (val value = get(key)) {
            is Byte -> value.toInt()
            is Short -> value.toInt()
            is Int -> value
            is Long -> {
                require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "$key is outside integer range" }
                value.toInt()
            }
            else -> throw IllegalArgumentException("$key must be an integer")
        }
    }
}
