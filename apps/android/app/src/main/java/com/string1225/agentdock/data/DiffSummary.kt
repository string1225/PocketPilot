package com.string1225.agentdock.data

data class SnapshotDiff(
    val added: Int,
    val modified: Int,
    val deleted: Int,
)

object DiffSummary {
    fun between(previous: Map<String, String>, current: Map<String, String>): SnapshotDiff {
        val added = current.keys.count { it !in previous }
        val deleted = previous.keys.count { it !in current }
        val modified = current.keys.count { path -> previous[path]?.let { it != current[path] } == true }
        return SnapshotDiff(added = added, modified = modified, deleted = deleted)
    }
}
