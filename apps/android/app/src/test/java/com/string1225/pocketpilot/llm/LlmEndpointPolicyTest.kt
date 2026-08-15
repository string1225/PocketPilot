package com.string1225.pocketpilot.llm

import com.string1225.pocketpilot.security.CredentialIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LlmEndpointPolicyTest {
    @Test
    fun `resolves the default chat completions endpoint`() {
        assertEquals(
            "https://open.bigmodel.cn/api/coding/paas/v4/chat/completions",
            LlmEndpointPolicy.resolve(
                LlmEndpointConfig(credentialId = CredentialIds.DEFAULT_LLM),
            ).toString(),
        )
    }

    @Test
    fun `resolves the default responses endpoint without duplicating a full path`() {
        assertEquals(
            "https://open.bigmodel.cn/api/v1/responses",
            LlmEndpointPolicy.resolve(
                LlmEndpointConfig(
                    protocol = LlmProtocol.RESPONSES,
                    baseUrl = DEFAULT_RESPONSES_BASE_URL,
                    credentialId = CredentialIds.DEFAULT_LLM,
                ),
            ).toString(),
        )
        assertEquals(
            "https://llm.example.test/v1/responses",
            LlmEndpointPolicy.resolve(
                LlmEndpointConfig(
                    protocol = LlmProtocol.RESPONSES,
                    baseUrl = "https://llm.example.test/v1/responses/",
                    credentialId = CredentialIds.DEFAULT_LLM,
                ),
            ).toString(),
        )
    }

    @Test
    fun `rejects cleartext credentials and endpoint metadata`() {
        assertFailsWith<IllegalArgumentException> {
            LlmEndpointPolicy.resolve(
                LlmEndpointConfig(
                    baseUrl = "http://llm.example.test/v1",
                    credentialId = CredentialIds.DEFAULT_LLM,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            LlmEndpointPolicy.resolve(
                LlmEndpointConfig(
                    baseUrl = "https://user:secret@llm.example.test/v1",
                    credentialId = CredentialIds.DEFAULT_LLM,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            LlmEndpointPolicy.resolve(
                LlmEndpointConfig(
                    baseUrl = "https://llm.example.test/v1?api_key=secret",
                    credentialId = CredentialIds.DEFAULT_LLM,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            LlmEndpointPolicy.resolve(
                LlmEndpointConfig(
                    baseUrl = "https://llm.example.test:0/v1",
                    credentialId = CredentialIds.DEFAULT_LLM,
                ),
            )
        }
    }

    @Test
    fun `rejects credential references outside the dedicated LLM slot`() {
        listOf(CredentialIds.DEFAULT_GIT_TOKEN, "ssh.server-1.password").forEach { credentialId ->
            val error = assertFailsWith<LlmProtocolException> {
                LlmEndpointPolicy.resolve(
                    LlmEndpointConfig(credentialId = credentialId),
                )
            }
            assertEquals("LLM_CREDENTIAL_REFERENCE_FORBIDDEN", error.code)
        }
    }
}
