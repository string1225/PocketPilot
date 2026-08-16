package com.string1225.pocketpilot.llm

import java.io.BufferedReader
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Strict, bounded SSE framing used by Chat Completions streaming responses. */
internal class ServerSentEventReader(
    input: InputStream,
    maximumBytes: Int,
    private val maximumEventCharacters: Int = DEFAULT_MAX_EVENT_CHARACTERS,
) {
    private val boundedInput = BoundedInputStream(input, maximumBytes)

    init {
        require(maximumBytes > 0) { "SSE byte limit must be positive" }
        require(maximumEventCharacters > 0) { "SSE event limit must be positive" }
    }

    /**
     * Returns true when the canonical OpenAI `[DONE]` sentinel was received.
     * A clean EOF is accepted for compatible servers that omit the sentinel.
     */
    fun read(onData: (String) -> Unit): Boolean {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val data = StringBuilder()
        var done = false
        try {
            BufferedReader(InputStreamReader(boundedInput, decoder), BUFFER_CHARACTERS).use { reader ->
                while (!done) {
                    val line = reader.readLine()
                    if (line == null) {
                        if (data.isNotEmpty()) done = dispatch(data, onData)
                        break
                    }
                    if (line.isEmpty()) {
                        if (data.isNotEmpty()) done = dispatch(data, onData)
                        continue
                    }
                    if (line.startsWith(':')) continue
                    val separator = line.indexOf(':')
                    val field = if (separator < 0) line else line.substring(0, separator)
                    if (field != "data") continue
                    var value = if (separator < 0) "" else line.substring(separator + 1)
                    if (value.startsWith(' ')) value = value.substring(1)
                    if (data.isNotEmpty()) appendBounded(data, "\n")
                    appendBounded(data, value)
                }
            }
        } catch (error: LlmProtocolException) {
            throw error
        } catch (error: java.nio.charset.CharacterCodingException) {
            throw LlmProtocolException(
                "LLM_INVALID_RESPONSE",
                "LLM stream is not valid UTF-8",
                cause = error,
            )
        }
        return done
    }

    private fun dispatch(data: StringBuilder, onData: (String) -> Unit): Boolean {
        val value = data.toString().removePrefix("\uFEFF").trim()
        data.setLength(0)
        if (value == "[DONE]") return true
        if (value.isNotEmpty()) onData(value)
        return false
    }

    private fun appendBounded(target: StringBuilder, value: String) {
        if (target.length + value.length > maximumEventCharacters) {
            throw LlmProtocolException(
                "LLM_RESPONSE_TOO_LARGE",
                "One LLM stream event exceeded the size limit",
            )
        }
        target.append(value)
    }

    private class BoundedInputStream(
        input: InputStream,
        private val maximumBytes: Int,
    ) : FilterInputStream(input) {
        private var consumedBytes = 0

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) addConsumed(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = super.read(buffer, offset, length)
            if (count > 0) addConsumed(count)
            return count
        }

        private fun addConsumed(count: Int) {
            consumedBytes += count
            if (consumedBytes > maximumBytes) {
                throw LlmProtocolException(
                    "LLM_RESPONSE_TOO_LARGE",
                    "LLM response exceeded the size limit",
                )
            }
        }
    }

    private companion object {
        const val BUFFER_CHARACTERS = 8 * 1024
        const val DEFAULT_MAX_EVENT_CHARACTERS = 1 * 1024 * 1024
    }
}
