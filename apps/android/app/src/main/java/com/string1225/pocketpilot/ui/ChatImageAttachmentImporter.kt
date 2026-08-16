package com.string1225.pocketpilot.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.string1225.pocketpilot.integrations.vision.AttachmentImageStore
import com.string1225.pocketpilot.model.ChatImageAttachment

/** Resolves picker metadata and copies the image into app-private storage. */
class ChatImageAttachmentImporter(
    context: Context,
    private val store: AttachmentImageStore,
) {
    private val resolver = context.applicationContext.contentResolver

    fun import(projectId: String, sourceUri: Uri): ChatImageAttachment {
        require(sourceUri.scheme == "content") { "Only Android picker content is accepted" }
        val mimeType = resolver.getType(sourceUri)
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("The selected image has no MIME type")
        var displayName: String? = null
        var sizeBytes: Long? = null
        resolver.query(
            sourceUri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) displayName = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) sizeBytes = cursor.getLong(sizeIndex)
            }
        }
        val resolvedSize = sizeBytes?.takeIf { it > 0L }
            ?: resolver.openAssetFileDescriptor(sourceUri, "r")?.use { it.length.takeIf { length -> length > 0L } }
            ?: throw IllegalArgumentException("Unable to determine the selected image size")
        val stored = store.importImage(
            projectId = projectId,
            sourceUri = sourceUri,
            displayName = displayName.orEmpty().ifBlank { "image" },
            mimeType = mimeType,
            sizeBytes = resolvedSize,
        )
        return ChatImageAttachment(
            id = stored.id,
            projectId = stored.projectId,
            displayName = stored.displayName,
            mimeType = stored.mimeType,
            sizeBytes = stored.sizeBytes,
            previewUri = stored.previewUri,
        )
    }

    fun delete(projectId: String, attachmentId: String) = store.delete(projectId, attachmentId)

    fun deleteProject(projectId: String) = store.deleteProject(projectId)
}
