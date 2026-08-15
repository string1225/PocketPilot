package com.string1225.pocketpilot.security

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreCredentialStoreTest {
    @Test
    fun encryptsRoundTripsAndDeletesCredential() {
        val id = "instrumented-test-${System.nanoTime()}"
        val store = AndroidKeystoreCredentialStore(
            context = ApplicationProvider.getApplicationContext(),
            keyAlias = "pocketpilot.test.llm.${System.nanoTime()}",
            preferencesName = "pocketpilot.test.llm.${System.nanoTime()}",
        )
        val input = "test-only-key-material".toCharArray()
        try {
            assertFalse(store.contains(id))
            store.put(id, input)
            assertTrue(store.contains(id))
            val output = store.get(id)
            try {
                assertArrayEquals(input.toTypedArray(), output?.toTypedArray())
            } finally {
                output?.fill('\u0000')
            }
            store.remove(id)
            assertNull(store.get(id))
        } finally {
            input.fill('\u0000')
            runCatching { store.remove(id) }
        }
    }
}
