package com.string1225.pocketpilot.runtime

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes Workspace and Git operations that share one project filesystem. */
class ProjectToolExecutionGate {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withProject(projectId: String, block: suspend () -> T): T {
        require(projectId.isNotBlank()) { "Project id must not be blank" }
        return locks.computeIfAbsent(projectId) { Mutex() }.withLock { block() }
    }
}
