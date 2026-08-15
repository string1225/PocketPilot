package com.string1225.pocketpilot.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.string1225.pocketpilot.runtime.ToolDispatchException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SerializedJsonBudgetTest {
    @Test
    fun countsActualJsonEscapingAndStopsPermanentlyAtBudget() {
        val array = JSONArray()
        val escapedValue = "\u0001"
        val encodedLength = org.json.JSONObject.quote(escapedValue).length
        val budget = SerializedJsonBudget(maxEntries = 10, maxEncodedValueChars = encodedLength)

        assertTrue(budget.tryPut(array, escapedValue))
        assertFalse(budget.tryPut(array, "x"))
        assertFalse(budget.tryPut(JSONArray(), ""))
        assertTrue(budget.truncated)
        assertEquals(1, budget.acceptedEntries)
        assertEquals(encodedLength, budget.encodedValueChars)
    }

    @Test
    fun enforcesEntryCountIndependentlyOfCharacterBudget() {
        val array = JSONArray()
        val budget = SerializedJsonBudget(maxEntries = 2, maxEncodedValueChars = 1_000)

        assertTrue(budget.tryPut(array, "one"))
        assertTrue(budget.tryPut(array, "two"))
        assertFalse(budget.tryPut(array, "three"))
        assertEquals(2, array.length())
    }

    @Test
    fun successResultCapsTheFinalJavaScriptQuotedSize() {
        boundedNativeToolSuccess(JSONObject().put("value", "\u0001".repeat(900_000)))

        val error = assertThrows(ToolDispatchException::class.java) {
            boundedNativeToolSuccess(JSONObject().put("value", "\u0001".repeat(1_100_000)))
        }
        assertEquals("TOOL_RESULT_TOO_LARGE", error.code)
    }
}
