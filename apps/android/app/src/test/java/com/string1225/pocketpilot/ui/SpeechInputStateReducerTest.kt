package com.string1225.pocketpilot.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeechInputStateReducerTest {
    @Test
    fun `normal recognition lifecycle returns to idle`() {
        var state = SpeechInputState()

        state = reduceSpeechInputState(state, SpeechInputEvent.StartRequested)
        assertEquals(SpeechInputPhase.STARTING, state.phase)
        assertTrue(state.isActive)

        state = reduceSpeechInputState(state, SpeechInputEvent.Ready)
        assertEquals(SpeechInputPhase.LISTENING, state.phase)

        state = reduceSpeechInputState(state, SpeechInputEvent.EndOfSpeech)
        assertEquals(SpeechInputPhase.PROCESSING, state.phase)

        state = reduceSpeechInputState(state, SpeechInputEvent.Finished)
        assertEquals(SpeechInputPhase.IDLE, state.phase)
        assertFalse(state.isActive)
    }

    @Test
    fun `failure is recoverable by a new start`() {
        val failed = reduceSpeechInputState(
            SpeechInputState(SpeechInputPhase.LISTENING),
            SpeechInputEvent.Failed(SpeechInputFailure.NETWORK),
        )
        assertEquals(SpeechInputPhase.ERROR, failed.phase)
        assertEquals(SpeechInputFailure.NETWORK, failed.failure)
        assertFalse(failed.isActive)

        val restarted = reduceSpeechInputState(failed, SpeechInputEvent.StartRequested)
        assertEquals(SpeechInputPhase.STARTING, restarted.phase)
        assertEquals(null, restarted.failure)
        assertTrue(restarted.isActive)
    }

    @Test
    fun `late callbacks cannot reactivate an idle controller`() {
        val idle = reduceSpeechInputState(
            SpeechInputState(SpeechInputPhase.LISTENING),
            SpeechInputEvent.Cancelled,
        )

        assertEquals(idle, reduceSpeechInputState(idle, SpeechInputEvent.Ready))
        assertEquals(idle, reduceSpeechInputState(idle, SpeechInputEvent.EndOfSpeech))
    }
}
