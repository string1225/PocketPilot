package com.string1225.pocketpilot.data

import com.string1225.pocketpilot.integrations.git.GitRemoteTarget
import com.string1225.pocketpilot.security.SecureCredentialStore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class GitSetupCredentialBindingTest {
    @Test
    fun `new token is resolved from memory without touching durable storage`() {
        val store = RecordingCredentialStore(failOnAccess = true)
        val source = "new-personal-access-token".toCharArray()
        try {
            val binding = GitSetupCredentialBinding(
                credentials = store,
                username = "pilot",
                target = GitRemoteTarget.fromHttpsUrl("https://github.com/example/project.git"),
                useStoredCredential = true,
                newToken = source,
            )

            assertEquals(
                GitSetupCredentialBinding.EPHEMERAL_CREDENTIAL_ID,
                binding.reference?.tokenCredentialId,
            )
            val resolved = binding.resolveToken(checkNotNull(binding.reference).tokenCredentialId).use { token ->
                token.copyValue()
            }
            try {
                assertContentEquals(source, resolved)
            } finally {
                resolved.fill('\u0000')
            }
            assertEquals(emptyList(), store.operations)
        } finally {
            source.fill('\u0000')
        }
    }

    @Test
    fun `stored token path resolves only the approved durable credential`() {
        val store = RecordingCredentialStore(initial = "stored-token")
        val binding = GitSetupCredentialBinding(
            credentials = store,
            username = "pilot",
            target = GitRemoteTarget.fromHttpsUrl("https://github.com/example/project.git"),
            useStoredCredential = true,
            newToken = null,
        )

        binding.resolveToken(checkNotNull(binding.reference).tokenCredentialId).use { token ->
            val resolved = token.copyValue()
            try {
                assertContentEquals("stored-token".toCharArray(), resolved)
            } finally {
                resolved.fill('\u0000')
            }
        }

        assertEquals(listOf("contains", "get"), store.operations)
    }

    private class RecordingCredentialStore(
        initial: String? = null,
        private val failOnAccess: Boolean = false,
    ) : SecureCredentialStore {
        private var value = initial?.toCharArray()
        val operations = mutableListOf<String>()

        override fun put(credentialId: String, secret: CharArray) {
            record("put")
            value?.fill('\u0000')
            value = secret.copyOf()
        }

        override fun get(credentialId: String): CharArray? {
            record("get")
            return value?.copyOf()
        }

        override fun contains(credentialId: String): Boolean {
            record("contains")
            return value != null
        }

        override fun remove(credentialId: String) {
            record("remove")
            value?.fill('\u0000')
            value = null
        }

        private fun record(operation: String) {
            operations += operation
            check(!failOnAccess) { "Durable credential store must not be accessed" }
        }
    }
}
