package com.string1225.pocketpilot.data

import android.content.ContentValues
import com.string1225.pocketpilot.model.Conversation
import com.string1225.pocketpilot.model.ConversationMessage
import com.string1225.pocketpilot.model.ConversationMessageRole
import java.util.UUID

class ConversationRepository(
    private val database: PocketPilotDatabase,
) {
    fun list(projectId: String? = null): List<Conversation> {
        val result = mutableListOf<Conversation>()
        database.readableDatabase.query(
            "conversations",
            CONVERSATION_COLUMNS,
            projectId?.let { "project_id = ?" },
            projectId?.let { arrayOf(it) },
            null,
            null,
            "updated_at DESC, created_at DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += Conversation(
                    id = cursor.getString(0),
                    projectId = cursor.getString(1),
                    title = cursor.getString(2),
                    createdAt = cursor.getLong(3),
                    updatedAt = cursor.getLong(4),
                )
            }
        }
        return result
    }

    fun create(projectId: String, rawTitle: String): Conversation {
        require(projectId.isNotBlank()) { "Project id must not be empty" }
        val title = rawTitle.trim().takeIf { it.isNotEmpty() } ?: "New chat"
        require(title.length <= MAX_TITLE_LENGTH) { "Conversation title is too long" }
        val now = System.currentTimeMillis()
        val conversation = Conversation(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            title = title,
            createdAt = now,
            updatedAt = now,
        )
        val values = ContentValues().apply {
            put("id", conversation.id)
            put("project_id", conversation.projectId)
            put("title", conversation.title)
            put("created_at", conversation.createdAt)
            put("updated_at", conversation.updatedAt)
        }
        database.writableDatabase.insertOrThrow("conversations", null, values)
        return conversation
    }

    fun rename(conversationId: String, rawTitle: String) {
        val title = rawTitle.trim()
        require(title.isNotEmpty()) { "Conversation title must not be empty" }
        require(title.length <= MAX_TITLE_LENGTH) { "Conversation title is too long" }
        val values = ContentValues().apply {
            put("title", title)
            put("updated_at", System.currentTimeMillis())
        }
        check(database.writableDatabase.update("conversations", values, "id = ?", arrayOf(conversationId)) == 1) {
            "Conversation does not exist"
        }
    }

    fun delete(conversationId: String) {
        database.writableDatabase.delete("conversations", "id = ?", arrayOf(conversationId))
    }

    fun listMessages(conversationId: String): List<ConversationMessage> {
        val result = mutableListOf<ConversationMessage>()
        database.readableDatabase.query(
            "messages",
            MESSAGE_COLUMNS,
            "conversation_id = ?",
            arrayOf(conversationId),
            null,
            null,
            "created_at ASC, rowid ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += ConversationMessage(
                    id = cursor.getString(0),
                    conversationId = cursor.getString(1),
                    role = ConversationMessageRole.fromValue(cursor.getString(2)),
                    title = cursor.getString(3),
                    content = cursor.getString(4),
                    createdAt = cursor.getLong(5),
                    runId = cursor.takeUnless { it.isNull(6) }?.getString(6),
                    isError = cursor.getInt(7) != 0,
                )
            }
        }
        return result
    }

    fun appendMessage(
        conversationId: String,
        role: ConversationMessageRole,
        title: String,
        content: String,
        runId: String? = null,
        isError: Boolean = false,
        createdAt: Long = System.currentTimeMillis(),
        messageId: String? = null,
    ): ConversationMessage {
        require(conversationId.isNotBlank()) { "Conversation id must not be empty" }
        require(content.length <= MAX_MESSAGE_LENGTH) { "Message is too long" }
        val message = ConversationMessage(
            id = messageId ?: UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = role,
            title = title.trim().take(MAX_TITLE_LENGTH),
            content = content,
            createdAt = createdAt,
            runId = runId,
            isError = isError,
        )
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("id", message.id)
                put("conversation_id", message.conversationId)
                put("role", message.role.value)
                put("title", message.title)
                put("content", message.content)
                put("created_at", message.createdAt)
                if (message.runId == null) putNull("run_id") else put("run_id", message.runId)
                put("is_error", if (message.isError) 1 else 0)
            }
            db.insertWithOnConflict(
                "messages",
                null,
                values,
                android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE,
            )
            db.execSQL(
                "UPDATE conversations SET updated_at = MAX(updated_at, ?) WHERE id = ?",
                arrayOf<Any>(message.createdAt, conversationId),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return message
    }

    companion object {
        private const val MAX_TITLE_LENGTH = 120
        private const val MAX_MESSAGE_LENGTH = 8 * 1_048_576
        private val CONVERSATION_COLUMNS = arrayOf("id", "project_id", "title", "created_at", "updated_at")
        private val MESSAGE_COLUMNS = arrayOf(
            "id",
            "conversation_id",
            "role",
            "title",
            "content",
            "created_at",
            "run_id",
            "is_error",
        )
    }
}
