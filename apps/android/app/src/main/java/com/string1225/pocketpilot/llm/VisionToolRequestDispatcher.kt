package com.string1225.pocketpilot.llm

import com.string1225.pocketpilot.data.SettingsRepository
import com.string1225.pocketpilot.integrations.vision.AttachmentImageStore
import com.string1225.pocketpilot.model.GLM_IMAGE_BASE_URL
import com.string1225.pocketpilot.model.LlmProviderPreference
import com.string1225.pocketpilot.runtime.ActiveRunRegistry
import com.string1225.pocketpilot.runtime.NativeToolRequest
import com.string1225.pocketpilot.runtime.NativeToolResult
import com.string1225.pocketpilot.runtime.ToolDispatchException
import com.string1225.pocketpilot.runtime.ToolRequestDispatcher
import com.string1225.pocketpilot.security.CredentialIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject

class VisionToolRequestDispatcher(
    private val client: OpenAiCompatibleVisionClient,
    private val attachments: AttachmentImageStore,
    private val settings: SettingsRepository,
    private val activeRuns: ActiveRunRegistry,
    private val fallback: ToolRequestDispatcher? = null,
) : ToolRequestDispatcher {
    override suspend fun dispatch(request: NativeToolRequest): NativeToolResult {
        if (request.name != TOOL_NAME) {
            return fallback?.dispatch(request)
                ?: throw ToolDispatchException("UNKNOWN_TOOL", "Unknown Native Tool: ${request.name}")
        }
        if (!activeRuns.authorizes(request.runId, request.projectId)) {
            throw ToolDispatchException("UNAUTHORIZED_RUN", "Image analysis is not authorized for this Agent Run")
        }
        return try {
            val input = decodeInput(request.argumentsJson)
            val attachment = attachments.get(request.projectId, input.attachmentId)
                ?: return failure("IMAGE_NOT_FOUND", "The selected image is unavailable", false)
            val connection = visionConnection(settings.load())
            val imageBytes = attachment.file.readBytes()
            try {
                val description = runInterruptible(Dispatchers.IO) {
                    client.analyze(
                        LlmVisionRequest(
                            config = connection,
                            prompt = input.prompt,
                            mimeType = attachment.mimeType,
                            imageBytes = imageBytes,
                        ),
                    )
                }
                NativeToolResult(
                    JSONObject()
                        .put("success", true)
                        .put(
                            "data",
                            JSONObject()
                                .put("description", description)
                                .put("model", connection.model),
                        )
                        .toString(),
                )
            } finally {
                imageBytes.fill(0)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: LlmProtocolException) {
            failure(error.code, error.message, error.retryable)
        } catch (error: IllegalArgumentException) {
            failure("IMAGE_INVALID_REQUEST", error.message ?: "Image analysis request is invalid", false)
        } catch (_: Exception) {
            failure("IMAGE_ANALYSIS_FAILED", "Native image analysis failed", true)
        }
    }

    private fun visionConnection(settings: com.string1225.pocketpilot.model.PocketPilotSettings): LlmEndpointConfig {
        val (baseUrl, model) = when (settings.llmProvider) {
            LlmProviderPreference.GLM -> GLM_IMAGE_BASE_URL to settings.llmProvider.defaultImageModel
            LlmProviderPreference.OPENAI_CHAT -> settings.llmBaseUrl to settings.imageModelName
        }
        return LlmEndpointConfig(
            protocol = LlmProtocol.CHAT_COMPLETIONS,
            baseUrl = baseUrl,
            model = model,
            credentialId = CredentialIds.DEFAULT_LLM,
        ).also { LlmEndpointPolicy.resolve(it) }
    }

    private fun decodeInput(argumentsJson: String): Input {
        require(argumentsJson.toByteArray(Charsets.UTF_8).size <= MAX_INPUT_BYTES) {
            "Image analysis arguments are too large"
        }
        val root = JSONObject(argumentsJson)
        require(root.keys().asSequence().all { it == "attachmentId" || it == "prompt" }) {
            "Image analysis contains unsupported arguments"
        }
        val attachmentId = root.optString("attachmentId")
        val prompt = root.optString("prompt")
        require(attachmentId.isNotBlank() && attachmentId.length <= 64) { "Attachment id is invalid" }
        require(prompt.isNotBlank() && prompt.length <= 8_192) { "Image prompt is invalid" }
        return Input(attachmentId, prompt)
    }

    private fun failure(code: String, message: String, retryable: Boolean): NativeToolResult = NativeToolResult(
        JSONObject()
            .put("success", false)
            .put(
                "error",
                JSONObject()
                    .put("code", code)
                    .put("message", message)
                    .put("retryable", retryable),
            )
            .toString(),
    )

    private data class Input(val attachmentId: String, val prompt: String)

    companion object {
        const val TOOL_NAME = "image.analyze"
        private const val MAX_INPUT_BYTES = 16 * 1024
    }
}
