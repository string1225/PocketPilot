package com.string1225.pocketpilot.llm

import com.string1225.pocketpilot.security.CredentialIds
import java.net.URI

object LlmEndpointPolicy {
    private const val MAX_URL_LENGTH = 2_048

    fun resolve(config: LlmEndpointConfig): URI {
        require(config.baseUrl.length in 1..MAX_URL_LENGTH) { "LLM base URL is invalid" }
        require(config.model.isNotBlank() && config.model.length <= 128) { "LLM model is invalid" }
        if (config.credentialId != CredentialIds.DEFAULT_LLM) {
            throw LlmProtocolException(
                "LLM_CREDENTIAL_REFERENCE_FORBIDDEN",
                "Only the app's default LLM credential reference is permitted",
            )
        }
        val base = try {
            URI(config.baseUrl)
        } catch (error: Exception) {
            throw IllegalArgumentException("LLM base URL is invalid", error)
        }
        require(base.scheme.equals("https", ignoreCase = true)) {
            "LLM endpoint must use HTTPS so credentials are never sent in cleartext"
        }
        require(base.host?.isNotBlank() == true) { "LLM endpoint host is required" }
        require(base.port == -1 || base.port in 1..65_535) { "LLM endpoint port is invalid" }
        require(base.rawUserInfo == null && base.rawQuery == null && base.rawFragment == null) {
            "LLM endpoint must not contain credentials, query parameters, or fragments"
        }

        val normalizedPath = base.path.orEmpty().trimEnd('/')
        val expectedSuffix = "/${config.protocol.endpointPath}"
        val endpointPath = if (normalizedPath.endsWith(expectedSuffix)) {
            normalizedPath
        } else {
            "$normalizedPath$expectedSuffix"
        }
        return URI(
            "https",
            null,
            base.host,
            base.port,
            endpointPath,
            null,
            null,
        )
    }
}
