package com.string1225.pocketpilot.data

import android.content.ContentValues
import com.string1225.pocketpilot.model.Conversation
import com.string1225.pocketpilot.model.ChatImageAttachment
import com.string1225.pocketpilot.model.ConversationMessage
import com.string1225.pocketpilot.model.ConversationMessageRole
import com.string1225.pocketpilot.model.TokenUsage
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

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
                    status = cursor.takeUnless { it.isNull(8) }?.getString(8),
                    tokenUsage = decodeTokenUsage(
                        promptTokens = cursor.takeUnless { it.isNull(9) }?.getLong(9),
                        completionTokens = cursor.takeUnless { it.isNull(10) }?.getLong(10),
                        totalTokens = cursor.takeUnless { it.isNull(11) }?.getLong(11),
                    ),
                    attachments = decodeAttachments(cursor.getString(12)),
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
        status: String? = null,
        tokenUsage: TokenUsage? = null,
        attachments: List<ChatImageAttachment> = emptyList(),
    ): ConversationMessage {
        require(conversationId.isNotBlank()) { "Conversation id must not be empty" }
        require(content.length <= MAX_MESSAGE_LENGTH) { "Message is too long" }
        require(status == null || status in ALLOWED_STATUSES) { "Message status is invalid" }
        validateTokenUsage(tokenUsage)
        require(attachments.size <= MAX_ATTACHMENTS) { "Too many message attachments" }
        require(attachments.map(ChatImageAttachment::id).distinct().size == attachments.size) {
            "Message attachments must be unique"
        }
        val attachmentsJson = encodeAttachments(attachments)
        val message = ConversationMessage(
            id = messageId ?: UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = role,
            title = title.trim().take(MAX_TITLE_LENGTH),
            content = content,
            createdAt = createdAt,
            runId = runId,
            isError = isError,
            status = status,
            tokenUsage = tokenUsage,
            attachments = attachments,
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
                if (message.status == null) putNull("status") else put("status", message.status)
                putNullableLong("prompt_tokens", message.tokenUsage?.promptTokens)
                putNullableLong("completion_tokens", message.tokenUsage?.completionTokens)
                putNullableLong("total_tokens", message.tokenUsage?.totalTokens)
                put("attachments_json", attachmentsJson)
            }
            val updated = db.update(
                "messages",
                values,
                "id = ? AND conversation_id = ?",
                arrayOf(message.id, message.conversationId),
            )
            if (updated == 0) db.insertOrThrow("messages", null, values)
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
            "status",
            "prompt_tokens",
            "completion_tokens",
            "total_tokens",
            "attachments_json",
        )

        private const val MAX_ATTACHMENTS = 16
        private const val MAX_ATTACHMENTS_JSON_BYTES = 256 * 1024
        private val ALLOWED_STATUSES = setOf(
            "queued",
            "sent",
            "running",
            "completed",
            "failed",
            "cancelled",
        )

        private fun validateTokenUsage(usage: TokenUsage?) {
            if (usage == null) return
            val values = listOfNotNull(usage.promptTokens, usage.completionTokens, usage.totalTokens)
            require(values.all { it >= 0L }) { "Token usage cannot be negative" }
            if (usage.totalTokens != null && usage.promptTokens != null && usage.completionTokens != null) {
                require(usage.totalTokens >= usage.promptTokens + usage.completionTokens) {
                    "Total token usage is invalid"
                }
            }
        }

        private fun decodeTokenUsage(
            promptTokens: Long?,
            completionTokens: Long?,
            totalTokens: Long?,
        ): TokenUsage? {
            if (promptTokens == null && completionTokens == null && totalTokens == null) return null
            return TokenUsage(promptTokens, completionTokens, totalTokens).also(::validateTokenUsage)
        }

        internal fun encodeAttachments(attachments: List<ChatImageAttachment>): String {
            require(attachments.size <= MAX_ATTACHMENTS) { "Too many message attachments" }
            val value = JSONArray().apply {
                attachments.forEach { attachment ->
                    require(attachment.id.length in 1..64) { "Attachment id is invalid" }
                    require(attachment.projectId.length in 1..512) { "Attachment project is invalid" }
                    require(attachment.displayName.length in 1..128) { "Attachment name is invalid" }
                    require(attachment.mimeType in ALLOWED_IMAGE_MIME_TYPES) { "Attachment MIME type is invalid" }
                    require(attachment.sizeBytes in 1..MAX_ATTACHMENT_BYTES) { "Attachment size is invalid" }
                    require(
                        attachment.previewUri == null ||
                            (attachment.previewUri.startsWith("content://") && attachment.previewUri.length <= 2_048),
                    ) { "Attachment preview URI is invalid" }
                    put(
                        JSONObject()
                            .put("id", attachment.id)
                            .put("projectId", attachment.projectId)
                            .put("displayName", attachment.displayName)
                            .put("mimeType", attachment.mimeType)
                            .put("sizeBytes", attachment.sizeBytes)
                            .apply { attachment.previewUri?.let { put("previewUri", it) } },
                    )
                }
            }.toString()
            require(value.toByteArray(Charsets.UTF_8).size <= MAX_ATTACHMENTS_JSON_BYTES) {
                "Attachment metadata is too large"
            }
            return value
        }

        internal fun decodeAttachments(value: String): List<ChatImageAttachment> {
            require(value.toByteArray(Charsets.UTF_8).size <= MAX_ATTACHMENTS_JSON_BYTES) {
                "Attachment metadata is too large"
            }
            val array = JSONArray(value)
            require(array.length() <= MAX_ATTACHMENTS) { "Too many message attachments" }
            val ids = mutableSetOf<String>()
            return buildList(array.length()) {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index)
                        ?: throw IllegalArgumentException("Attachment metadata is invalid")
                    val allowed = setOf("id", "projectId", "displayName", "mimeType", "sizeBytes", "previewUri")
                    require(item.keys().asSequence().all { it in allowed }) { "Attachment metadata is invalid" }
                    val attachment = ChatImageAttachment(
                        id = item.getString("id"),
                        projectId = item.getString("projectId"),
                        displayName = item.getString("displayName"),
                        mimeType = item.getString("mimeType"),
                        sizeBytes = item.getLong("sizeBytes"),
                        previewUri = item.optString("previewUri").takeIf(String::isNotBlank),
                    )
                    require(ids.add(attachment.id)) { "Message attachments must be unique" }
                    // Reuse the strict encoder validation for each decoded item.
                    encodeAttachments(listOf(attachment))
                    add(attachment)
                }
            }
        }

        private const val MAX_ATTACHMENT_BYTES = 8L * 1024L * 1024L
        private val ALLOWED_IMAGE_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
    }
}

private fun ContentValues.putNullableLong(key: String, value: Long?) {
    if (value == null) putNull(key) else put(key, value)
}
