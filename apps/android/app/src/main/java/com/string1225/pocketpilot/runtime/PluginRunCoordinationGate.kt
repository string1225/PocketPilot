package com.string1225.pocketpilot.runtime

/**
 * Serializes global runtime mutations (plugins and model connection settings)
 * with Agent Run lifecycle boundaries.
 *
 * A mutation holds the same monitor used to begin a Run. Once a Run has begun,
 * plugin mutations fail closed until its finally block releases the lease.
 * If a mutation wins first, a concurrent Run waits and then observes the new
 * complete plugin state.
 */
class PluginRunCoordinationGate {
    private val lock = Any()
    private val activeRunIds = mutableSetOf<String>()

    fun beginRun(runId: String) {
        require(runId.isNotBlank()) { "Run id must not be blank." }
        synchronized(lock) {
            check(activeRunIds.add(runId)) { "Plugin gate already tracks Run: $runId" }
        }
    }

    fun finishRun(runId: String) {
        synchronized(lock) { activeRunIds.remove(runId) }
    }

    fun <T> mutate(block: () -> T): T = synchronized(lock) {
        check(activeRunIds.isEmpty()) {
            "Stop all Agent runs before changing global runtime state."
        }
        block()
    }
}
