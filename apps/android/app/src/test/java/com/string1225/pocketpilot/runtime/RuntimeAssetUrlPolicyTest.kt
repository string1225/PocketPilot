package com.string1225.pocketpilot.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RuntimeAssetUrlPolicyTest {
    @Test
    fun `creates and resolves local asset urls`() {
        val url = RuntimeAssetUrlPolicy.urlFor("runtime/index.html")

        assertEquals(
            "https://appassets.androidplatform.net/assets/runtime/index.html",
            url,
        )
        assertEquals("runtime/index.html", RuntimeAssetUrlPolicy.assetPath(url))
    }

    @Test
    fun `rejects external origins and non https schemes`() {
        assertNull(RuntimeAssetUrlPolicy.assetPath("https://example.com/assets/runtime/index.html"))
        assertNull(RuntimeAssetUrlPolicy.assetPath("file:///android_asset/runtime/index.html"))
        assertNull(RuntimeAssetUrlPolicy.assetPath("content://provider/runtime/index.html"))
    }

    @Test
    fun `rejects traversal and encoded paths`() {
        assertNull(RuntimeAssetUrlPolicy.assetPath("https://appassets.androidplatform.net/assets/../secret"))
        assertNull(RuntimeAssetUrlPolicy.assetPath("https://appassets.androidplatform.net/assets/%2e%2e/secret"))
        assertFailsWith<IllegalArgumentException> {
            RuntimeAssetUrlPolicy.urlFor("runtime/../secret")
        }
    }
}
