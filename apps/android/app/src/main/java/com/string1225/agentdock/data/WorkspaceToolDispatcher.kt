package com.string1225.agentdock.data

import com.string1225.agentdock.model.Checkpoint
import com.string1225.agentdock.model.CheckpointSource
import com.string1225.agentdock.model.ToolApprovalRequest
import com.string1225.agentdock.runtime.ActiveRunRegistry
import com.string1225.agentdock.runtime.NativeToolRequest
import com.string1225.agentdock.runtime.NativeToolResult
import com.string1225.agentdock.runtime.ToolApprovalCoordinator
import com.string1225.agentdock.runtime.ToolDispatchException
import com.string1225.agentdock.runtime.ToolRequestDispatcher
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
) : ToolRequestDispatcher {
    override suspend fun dispatch(request: NativeToolRequest): NativeToolResult = withContext(Dispatchers.IO) {
        try {
            requireAuthorized(request)
            val arguments = JSONObject(request.argumentsJson)
            val data = when (request.name) {
                "workspace.list" -> list(request.projectId, arguments)
                "workspace.read" -> read(request.projectId, arguments)
                "workspace.write" -> write(request.projectId, arguments, createOnly = false)
                "workspace.create" -> write(request.projectId, arguments, createOnly = true)
                "workspace.delete" -> delete(request, arguments)
                "workspace.move" -> move(request.projectId, arguments)
                "workspace.search" -> search(request.projectId, arguments)
                "workspace.patch" -> patch(request.projectId, arguments)
                else -> throw ToolDispatchException("UNKNOWN_TOOL", "Unknown Native Tool: ${request.name}")
            }
            NativeToolResult(
                JSONObject()
                    .put("success", true)
                    .put("data", data)
                    .toString(),
            )
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
    }

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
        val rawPath = arguments.optString("path").trim().replace('\\', '/').trim('/')
        val requestedPath = if (rawPath.isEmpty() || rawPath == ".") "" else WorkspacePath.normalize(rawPath)
        val entries = workspace.list(projectId)
            .filter { requestedPath.isEmpty() || it.path == requestedPath || it.path.startsWith("$requestedPath/") }
        return JSONObject().put(
            "entries",
            JSONArray().apply {
                entries.forEach { entry ->
                    put(
                        JSONObject()
                            .put("path", entry.path)
                            .put("size", entry.size)
                            .put("modifiedAt", entry.modifiedAt),
                    )
                }
            },
        )
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
        val limit = if (arguments.has("limit")) arguments.getInt("limit") else 100
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
        val expectedOccurrences = if (arguments.has("expectedOccurrences")) {
            arguments.getInt("expectedOccurrences")
        } else {
            1
        }
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
}
