package com.string1225.pocketpilot.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.string1225.pocketpilot.model.ChatImageAttachment
import com.string1225.pocketpilot.model.ConversationMessageRole
import com.string1225.pocketpilot.model.TokenUsage
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationMessageMetadataTest {
    @Test
    fun versionFourMessagesTableMigratesWithoutLosingRows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "message-migration-${UUID.randomUUID()}.db"
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { legacy ->
            legacy.execSQL(
                """
                CREATE TABLE messages (
                    id TEXT PRIMARY KEY NOT NULL,
                    conversation_id TEXT NOT NULL,
                    role TEXT NOT NULL,
                    title TEXT NOT NULL,
                    content TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    run_id TEXT,
                    is_error INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            legacy.execSQL(
                "INSERT INTO messages(id, conversation_id, role, title, content, created_at) " +
                    "VALUES('m1', 'c1', 'assistant', '', 'kept', 1)",
            )
            legacy.version = 4
        }

        val upgraded = PocketPilotDatabase(context, databaseName)
        try {
            val columns = mutableSetOf<String>()
            upgraded.readableDatabase.rawQuery("PRAGMA table_info(messages)", null).use { cursor ->
                while (cursor.moveToNext()) columns += cursor.getString(1)
            }
            assertTrue(
                columns.containsAll(
                    setOf(
                        "status",
                        "prompt_tokens",
                        "completion_tokens",
                        "total_tokens",
                        "attachments_json",
                    ),
                ),
            )
            upgraded.readableDatabase.rawQuery("SELECT content, attachments_json FROM messages", null)
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("kept", cursor.getString(0))
                    assertEquals("[]", cursor.getString(1))
                }
        } finally {
            upgraded.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun finalMessageUpsertPersistsStatusUsageAndPrivateAttachmentMetadata() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "message-metadata-${UUID.randomUUID()}.db"
        val database = PocketPilotDatabase(context, databaseName)
        val projectRoot = File(context.cacheDir, "message-metadata-${UUID.randomUUID()}")
        try {
            val projects = ProjectRepository(database, projectRoot)
            projects.checkpoints = CheckpointRepository(database, projectRoot, projects::exists)
            val project = projects.create("Test")
            val repository = ConversationRepository(database)
            val conversation = repository.create(project.id, "Vision")
            val messageId = UUID.randomUUID().toString()
            val attachment = ChatImageAttachment(
                id = UUID.randomUUID().toString(),
                projectId = project.id,
                displayName = "screen.png",
                mimeType = "image/png",
                sizeBytes = 512,
                previewUri = "content://com.string1225.pocketpilot.fileprovider/attachments/safe",
            )

            repository.appendMessage(
                conversationId = conversation.id,
                role = ConversationMessageRole.ASSISTANT,
                title = "",
                content = "Part",
                messageId = messageId,
                status = "running",
                attachments = listOf(attachment),
            )
            repository.appendMessage(
                conversationId = conversation.id,
                role = ConversationMessageRole.ASSISTANT,
                title = "",
                content = "Finished",
                messageId = messageId,
                status = "completed",
                tokenUsage = TokenUsage(10, 4, 14),
                attachments = listOf(attachment),
            )

            val messages = repository.listMessages(conversation.id)
            assertEquals(1, messages.size)
            assertEquals("Finished", messages.single().content)
            assertEquals("completed", messages.single().status)
            assertEquals(TokenUsage(10, 4, 14), messages.single().tokenUsage)
            assertEquals(listOf(attachment), messages.single().attachments)
            assertNotNull(messages.single().attachments.single().previewUri)
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun sentUserMessageIsAcceptedAndRestored() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "message-sent-${UUID.randomUUID()}.db"
        val database = PocketPilotDatabase(context, databaseName)
        val projectRoot = File(context.cacheDir, "message-sent-${UUID.randomUUID()}")
        try {
            val projects = ProjectRepository(database, projectRoot)
            projects.checkpoints = CheckpointRepository(database, projectRoot, projects::exists)
            val project = projects.create("Test")
            val repository = ConversationRepository(database)
            val conversation = repository.create(project.id, "Queue")

            repository.appendMessage(
                conversationId = conversation.id,
                role = ConversationMessageRole.USER,
                title = "You",
                content = "Queued, then sent",
                status = "sent",
            )

            assertEquals("sent", repository.listMessages(conversation.id).single().status)
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
            projectRoot.deleteRecursively()
        }
    }
}
