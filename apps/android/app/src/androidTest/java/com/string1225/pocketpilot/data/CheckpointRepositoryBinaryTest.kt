package com.string1225.pocketpilot.data

import android.content.ContentValues
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.string1225.pocketpilot.model.CheckpointSource
import java.io.File
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CheckpointRepositoryBinaryTest {
    @Test
    fun binaryFileRoundTripsWithoutUtf8Conversion() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(DATABASE_NAME)
        val database = PocketPilotDatabase(context)
        val projectsRoot = File(context.cacheDir, "checkpoint-binary-${System.nanoTime()}")
        val projectId = UUID.randomUUID().toString()
        val original = byteArrayOf(0, 1, 2, 0xff.toByte(), 0xfe.toByte(), 0x41)
        try {
            database.writableDatabase.insertOrThrow(
                "projects",
                null,
                ContentValues().apply {
                    put("id", projectId)
                    put("name", "Binary test")
                    put("created_at", 1L)
                    put("updated_at", 1L)
                },
            )
            val workspace = ProjectPaths.workspaceDirectory(projectsRoot, projectId).apply { mkdirs() }
            val binary = File(workspace, "fixture.bin")
            binary.writeBytes(original)
            val repository = CheckpointRepository(database, projectsRoot) { it == projectId }
            val checkpoint = repository.create(projectId, CheckpointSource.USER, "binary")

            binary.writeBytes(byteArrayOf(9, 8, 7))
            repository.restore(projectId, checkpoint.id)

            assertArrayEquals(original, binary.readBytes())
        } finally {
            database.close()
            context.deleteDatabase(DATABASE_NAME)
            projectsRoot.deleteRecursively()
            original.fill(0)
        }
    }

    private companion object {
        const val DATABASE_NAME = "pocketpilot.db"
    }
}
