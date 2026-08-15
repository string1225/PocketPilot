package com.string1225.pocketpilot.data

import com.string1225.pocketpilot.runtime.NativeToolResult
import com.string1225.pocketpilot.runtime.ToolDispatchException
import org.json.JSONArray
import org.json.JSONObject

/** Bounds collection results by both entry count and their actual JSON-escaped size. */
internal class SerializedJsonBudget(
    private val maxEntries: Int = MAX_TOOL_COLLECTION_ENTRIES,
    private val maxEncodedValueChars: Int = MAX_TOOL_COLLECTION_JSON_CHARS,
) {
    var acceptedEntries: Int = 0
        private set
    var encodedValueChars: Int = 0
        private set
    var truncated: Boolean = false
        private set

    init {
        require(maxEntries >= 0) { "maxEntries must not be negative" }
        require(maxEncodedValueChars >= 0) { "maxEncodedValueChars must not be negative" }
    }

    fun tryPut(array: JSONArray, value: JSONObject): Boolean =
        tryPutEncoded(array, value, value.toString().length)

    fun tryPut(array: JSONArray, value: String): Boolean =
        tryPutEncoded(array, value, JSONObject.quote(value).length)

    private fun tryPutEncoded(array: JSONArray, value: Any, encodedLength: Int): Boolean {
        if (truncated) return false
        val separatorLength = if (array.length() == 0) 0 else 1
        if (
            acceptedEntries >= maxEntries ||
            encodedValueChars.toLong() + separatorLength + encodedLength > maxEncodedValueChars
        ) {
            truncated = true
            return false
        }
        array.put(value)
        acceptedEntries += 1
        encodedValueChars += separatorLength + encodedLength
        return true
    }
}

internal fun boundedNativeToolSuccess(data: JSONObject): NativeToolResult {
    val payload = JSONObject().put("success", true).put("data", data).toString()
    // The raw result object is embedded in a Runtime envelope and that full
    // envelope is quoted as a JavaScript string. Count this second escaping
    // pass, not only the intermediate JSON text.
    if (JSONObject.quote(payload).length > MAX_BRIDGE_ENCODED_TOOL_RESULT_CHARS) {
        throw ToolDispatchException(
            "TOOL_RESULT_TOO_LARGE",
            "Native Tool result exceeded the bridge size limit",
        )
    }
    return NativeToolResult(payload)
}

internal const val MAX_TOOL_COLLECTION_ENTRIES: Int = 2_048
internal const val MAX_TOOL_COLLECTION_JSON_CHARS: Int = 4 * 1_048_576
internal const val MAX_BRIDGE_ENCODED_TOOL_RESULT_CHARS: Int = 7 * 1_048_576
