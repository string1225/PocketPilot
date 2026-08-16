package com.string1225.pocketpilot.ui

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PluginBundleImportTest {
    @Test
    fun readsBoundedUtf8() {
        val text = "{\"manifest\":{},\"source\":\"你好\"}"

        assertEquals(
            text,
            readBoundedUtf8(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)), 100),
        )
    }

    @Test
    fun rejectsOversizeAndMalformedUtf8() {
        assertFailsWith<IllegalArgumentException> {
            readBoundedUtf8(ByteArrayInputStream(ByteArray(11)), 10)
        }
        assertFailsWith<Exception> {
            readBoundedUtf8(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)), 10)
        }
    }
}
