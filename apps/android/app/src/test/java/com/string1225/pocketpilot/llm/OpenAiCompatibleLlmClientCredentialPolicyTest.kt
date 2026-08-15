package com.string1225.pocketpilot.llm

import com.string1225.pocketpilot.security.CredentialIds
import com.string1225.pocketpilot.security.SecureCredentialStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OpenAiCompatibleLlmClientCredentialPolicyTest {
    @Test
    fun `does not read git or ssh credentials`() {
        val credentials = RecordingCredentialStore()
        val client = OpenAiCompatibleLlmClient(credentials)

        listOf(CredentialIds.DEFAULT_GIT_TOKEN, "ssh.server-1.password").forEach { credentialId ->
            val error = assertFailsWith<LlmProtocolException> {
                client.complete(
                    LlmCompletionRequest(
                        config = LlmEndpointConfig(credentialId = credentialId),
                        messages = listOf(LlmMessage("user", "test")),
                        tools = emptyList(),
                    ),
                )
            }
            assertEquals("LLM_CREDENTIAL_REFERENCE_FORBIDDEN", error.code)
        }

        assertEquals(emptyList(), credentials.readIds)
    }

    private class RecordingCredentialStore : SecureCredentialStore {
        val readIds = mutableListOf<String>()

        override fun put(credentialId: String, secret: CharArray) = Unit

        override fun get(credentialId: String): CharArray? {
            readIds += credentialId
            return null
        }

        override fun contains(credentialId: String): Boolean = false

        override fun remove(credentialId: String) = Unit
    }
}
