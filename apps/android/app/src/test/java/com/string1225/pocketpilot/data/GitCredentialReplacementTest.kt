package com.string1225.pocketpilot.data

import com.string1225.pocketpilot.security.SecureCredentialStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class GitCredentialReplacementTest {
    @Test
    fun `write then throw restores the previous token`() {
        val store = WriteThenThrowCredentialStore(initial = "old-token", failingPutCalls = setOf(1))
        val replacement = GitCredentialReplacement(store, CREDENTIAL_ID)
        val failure = assertFailsWith<IllegalStateException> {
            replacement.replace("new-token".toCharArray())
        }

        replacement.rollbackAfter(failure)
        replacement.close()

        assertEquals("old-token", store.peek(CREDENTIAL_ID))
    }

    @Test
    fun `write then throw removes a replacement when there was no previous token`() {
        val store = WriteThenThrowCredentialStore(initial = null, failingPutCalls = setOf(1))
        val replacement = GitCredentialReplacement(store, CREDENTIAL_ID)
        val failure = assertFailsWith<IllegalStateException> {
            replacement.replace("new-token".toCharArray())
        }

        replacement.rollbackAfter(failure)
        replacement.close()

        assertFalse(store.contains(CREDENTIAL_ID))
    }

    @Test
    fun `failed restore removes an uncertain credential fail closed`() {
        val store = WriteThenThrowCredentialStore(initial = "old-token", failingPutCalls = setOf(1, 2))
        val replacement = GitCredentialReplacement(store, CREDENTIAL_ID)
        val failure = assertFailsWith<IllegalStateException> {
            replacement.replace("new-token".toCharArray())
        }

        replacement.rollbackAfter(failure)
        replacement.close()

        assertFalse(store.contains(CREDENTIAL_ID))
        assertEquals(1, failure.suppressed.size)
    }

    private class WriteThenThrowCredentialStore(
        initial: String?,
        private val failingPutCalls: Set<Int>,
    ) : SecureCredentialStore {
        private val values = mutableMapOf<String, CharArray>()
        private var putCalls = 0

        init {
            initial?.let { values[CREDENTIAL_ID] = it.toCharArray() }
        }

        override fun put(credentialId: String, secret: CharArray) {
            putCalls += 1
            values[credentialId]?.fill('\u0000')
            values[credentialId] = secret.copyOf()
            if (putCalls in failingPutCalls) throw IllegalStateException("persisted but reported failure")
        }

        override fun get(credentialId: String): CharArray? = values[credentialId]?.copyOf()

        override fun contains(credentialId: String): Boolean = credentialId in values

        override fun remove(credentialId: String) {
            values.remove(credentialId)?.fill('\u0000')
        }

        fun peek(credentialId: String): String? = values[credentialId]?.concatToString()
    }

    private companion object {
        const val CREDENTIAL_ID = "git.test.token"
    }
}
