package com.string1225.agentdock.data

import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProjectPathsTest {
    @Test
    fun acceptsUuidAndKeepsProjectBelowRoot() {
        val root = File(System.getProperty("java.io.tmpdir"), "agentdock-projects")
        val id = UUID.randomUUID().toString()
        val project = ProjectPaths.projectDirectory(root, id)
        assertTrue(project.path.startsWith(root.canonicalPath + File.separator))
    }

    @Test
    fun rejectsUntrustedProjectIds() {
        val root = File(System.getProperty("java.io.tmpdir"), "agentdock-projects")
        assertFailsWith<IllegalArgumentException> { ProjectPaths.projectDirectory(root, "../outside") }
    }
}
