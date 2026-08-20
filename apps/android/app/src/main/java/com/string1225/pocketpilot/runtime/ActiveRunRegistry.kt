package com.string1225.pocketpilot.runtime

import java.util.concurrent.ConcurrentHashMap

/** Native-side capability binding for every active TypeScript Agent Run. */
class ActiveRunRegistry {
    private val projectsByRun = ConcurrentHashMap<String, String>()

    fun register(runId: String, projectId: String) {
        require(runId.isNotBlank()) { "Run id must not be blank" }
        require(projectId.isNotBlank()) { "Project id must not be blank" }
        check(projectsByRun.putIfAbsent(runId, projectId) == null) {
            "Run is already active: $runId"
        }
    }

    fun authorizes(runId: String, projectId: String): Boolean =
        projectsByRun[runId] == projectId

    fun projectId(runId: String): String? = projectsByRun[runId]

    fun revoke(runId: String, projectId: String): Boolean =
        projectsByRun.remove(runId, projectId)

    fun revoke(runId: String): String? = projectsByRun.remove(runId)
}
