package com.string1225.pocketpilot.integrations.vision

import java.nio.file.Files
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AttachmentImageStorePolicyTest {
    @Test
    fun `display names remove controls and stay bounded`() {
        val sanitized = FileAttachmentImageStore.sanitizeDisplayName("  screen\u0000\n shot.png  ")

        assertEquals("screen shot.png", sanitized)
        assertEquals(128, FileAttachmentImageStore.sanitizeDisplayName("x".repeat(200)).length)
    }

    @Test
    fun `magic bytes determine the accepted MIME type`() {
        val png = Files.createTempFile("pocketpilot-image", ".png")
        val invalid = Files.createTempFile("pocketpilot-image", ".bin")
        try {
            png.writeBytes(
                byteArrayOf(
                    0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
                    0x00, 0x00, 0x00, 0x00,
                ),
            )
            invalid.writeBytes("not an image".toByteArray())

            assertEquals("image/png", FileAttachmentImageStore.detectMime(png.toFile()))
            assertFailsWith<IllegalArgumentException> {
                FileAttachmentImageStore.detectMime(invalid.toFile())
            }
        } finally {
            Files.deleteIfExists(png)
            Files.deleteIfExists(invalid)
        }
    }

    @Test
    fun `jpg alias normalizes to the only stored JPEG MIME`() {
        assertEquals("image/jpeg", FileAttachmentImageStore.normalizeMime(" IMAGE/JPG "))
    }
}
