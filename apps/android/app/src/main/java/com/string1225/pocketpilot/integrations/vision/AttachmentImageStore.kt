package com.string1225.pocketpilot.integrations.vision

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.util.Comparator
import java.util.UUID
import org.json.JSONObject

data class StoredImageAttachment(
    val id: String,
    val projectId: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val previewUri: String,
    /** Private app file; never serialize this path into WebView messages. */
    val file: File,
)

interface AttachmentImageStore {
    fun importImage(
        projectId: String,
        sourceUri: Uri,
        displayName: String,
        mimeType: String,
        sizeBytes: Long,
    ): StoredImageAttachment

    fun get(projectId: String, attachmentId: String): StoredImageAttachment?

    fun delete(projectId: String, attachmentId: String)

    /** Removes every private image owned by a project after the project is deleted. */
    fun deleteProject(projectId: String)
}

/**
 * Copies picker content into private app storage before it can be referenced by
 * a Run. Both declared metadata and file signatures are checked, and image
 * dimensions are decoded without allocating the bitmap.
 */
class FileAttachmentImageStore(
    private val context: Context,
    private val root: File = File(context.filesDir, "attachments"),
) : AttachmentImageStore {
    override fun importImage(
        projectId: String,
        sourceUri: Uri,
        displayName: String,
        mimeType: String,
        sizeBytes: Long,
    ): StoredImageAttachment {
        require(projectId.isNotBlank() && projectId.length <= MAX_PROJECT_ID_LENGTH) {
            "Project id is invalid"
        }
        require(sourceUri.scheme == "content") { "Only Android picker content is accepted" }
        require(sizeBytes in 1..MAX_IMAGE_BYTES) { "Image exceeds the 8 MiB limit" }
        val declaredMime = normalizeMime(mimeType)
        require(declaredMime in ALLOWED_MIME_TYPES) { "Unsupported image type" }
        val safeName = sanitizeDisplayName(displayName)
        val id = UUID.randomUUID().toString()
        val directory = projectDirectory(projectId).apply { mkdirs() }
        check(directory.isDirectory) { "Unable to create private attachment storage" }
        val temporary = File(directory, "$id.part")
        val dataFile = File(directory, "$id.image")
        val metadataFile = File(directory, "$id.json")
        try {
            val actualSize = context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var total = 0L
                    try {
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_IMAGE_BYTES) { "Image exceeds the 8 MiB limit" }
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                        total
                    } finally {
                        buffer.fill(0)
                    }
                }
            } ?: throw IllegalArgumentException("Unable to read the selected image")
            require(actualSize > 0L) { "Selected image is empty" }
            val detectedMime = detectMime(temporary)
            require(detectedMime == declaredMime) { "Image content does not match its MIME type" }
            validateDimensions(temporary)
            check(temporary.renameTo(dataFile)) { "Unable to finalize private attachment" }
            writeMetadata(
                metadataFile,
                JSONObject()
                    .put("id", id)
                    .put("projectHash", projectHash(projectId))
                    .put("displayName", safeName)
                    .put("mimeType", detectedMime)
                    .put("sizeBytes", actualSize),
            )
            return stored(projectId, id, safeName, detectedMime, actualSize, dataFile)
        } catch (error: Throwable) {
            temporary.delete()
            dataFile.delete()
            metadataFile.delete()
            throw error
        }
    }

    override fun get(projectId: String, attachmentId: String): StoredImageAttachment? {
        requireValidAttachmentId(attachmentId)
        val directory = projectDirectory(projectId)
        val dataFile = File(directory, "$attachmentId.image")
        val metadataFile = File(directory, "$attachmentId.json")
        if (!dataFile.isFile || !metadataFile.isFile) return null
        val metadata = runCatching {
            val bytes = metadataFile.readBytes()
            require(bytes.size <= MAX_METADATA_BYTES) { "Attachment metadata is too large" }
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            bytes.fill(0)
            requireOnlyKeys(root, "id", "projectHash", "displayName", "mimeType", "sizeBytes")
            root
        }.getOrElse { return null }
        if (
            metadata.optString("id") != attachmentId ||
            metadata.optString("projectHash") != projectHash(projectId)
        ) return null
        val mime = runCatching { normalizeMime(metadata.getString("mimeType")) }.getOrElse { return null }
        val size = metadata.optLong("sizeBytes", -1L)
        val name = runCatching { sanitizeDisplayName(metadata.getString("displayName")) }.getOrElse { return null }
        if (mime !in ALLOWED_MIME_TYPES || size !in 1..MAX_IMAGE_BYTES || dataFile.length() != size) return null
        if (runCatching { detectMime(dataFile) }.getOrNull() != mime) return null
        return stored(projectId, attachmentId, name, mime, size, dataFile)
    }

    override fun delete(projectId: String, attachmentId: String) {
        requireValidAttachmentId(attachmentId)
        val directory = projectDirectory(projectId)
        File(directory, "$attachmentId.image").delete()
        File(directory, "$attachmentId.json").delete()
    }

    override fun deleteProject(projectId: String) {
        val directory = projectDirectory(projectId).canonicalFile
        if (!directory.exists()) return
        Files.walk(directory.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun stored(
        projectId: String,
        id: String,
        displayName: String,
        mimeType: String,
        sizeBytes: Long,
        file: File,
    ): StoredImageAttachment = StoredImageAttachment(
        id = id,
        projectId = projectId,
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        previewUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        ).toString(),
        file = file,
    )

    private fun projectDirectory(projectId: String): File {
        val candidate = File(root, projectHash(projectId))
        val canonicalRoot = root.canonicalFile
        val canonicalCandidate = candidate.canonicalFile
        require(!Files.isSymbolicLink(candidate.toPath())) { "Attachment project directory must not be a symbolic link" }
        require(canonicalCandidate.parentFile == canonicalRoot) { "Attachment path escaped private storage" }
        return canonicalCandidate
    }

    private fun projectHash(projectId: String): String {
        val bytes = projectId.toByteArray(Charsets.UTF_8)
        val digest = try {
            MessageDigest.getInstance("SHA-256").digest(bytes)
        } finally {
            bytes.fill(0)
        }
        return try {
            digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        } finally {
            digest.fill(0)
        }
    }

    private fun writeMetadata(file: File, value: JSONObject) {
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_METADATA_BYTES) { "Attachment metadata is too large" }
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        } finally {
            bytes.fill(0)
        }
    }

    private fun validateDimensions(file: File) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        require(options.outWidth in 1..MAX_DIMENSION && options.outHeight in 1..MAX_DIMENSION) {
            "Image dimensions are unsupported"
        }
        require(options.outWidth.toLong() * options.outHeight.toLong() <= MAX_PIXELS) {
            "Image contains too many pixels"
        }
    }

    companion object {
        const val MAX_IMAGE_BYTES: Long = 8L * 1024L * 1024L
        private const val MAX_PROJECT_ID_LENGTH = 512
        private const val MAX_METADATA_BYTES = 4 * 1024
        private const val COPY_BUFFER_BYTES = 16 * 1024
        private const val MAX_DIMENSION = 8_192
        private const val MAX_PIXELS = 40_000_000L
        private val ATTACHMENT_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        private val ALLOWED_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")

        internal fun sanitizeDisplayName(value: String): String {
            val clean = value.replace(Regex("[\\p{Cc}\\p{Cf}]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(128)
            return clean.ifBlank { "image" }
        }

        internal fun normalizeMime(value: String): String = when (value.trim().lowercase()) {
            "image/jpg" -> "image/jpeg"
            else -> value.trim().lowercase()
        }

        internal fun detectMime(file: File): String {
            val header = ByteArray(12)
            val count = file.inputStream().use { it.read(header) }
            return try {
                when {
                    count >= 3 && header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() &&
                        header[2] == 0xff.toByte() -> "image/jpeg"
                    count >= 8 && header.copyOfRange(0, 8).contentEquals(
                        byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
                    ) -> "image/png"
                    count >= 12 && header.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
                        header.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> "image/webp"
                    else -> throw IllegalArgumentException("Unsupported or malformed image")
                }
            } finally {
                header.fill(0)
            }
        }

        private fun requireValidAttachmentId(value: String) {
            require(ATTACHMENT_ID.matches(value)) { "Attachment id is invalid" }
        }

        private fun requireOnlyKeys(value: JSONObject, vararg keys: String) {
            val allowed = keys.toHashSet()
            require(value.keys().asSequence().all { it in allowed }) { "Attachment metadata is invalid" }
        }
    }
}
