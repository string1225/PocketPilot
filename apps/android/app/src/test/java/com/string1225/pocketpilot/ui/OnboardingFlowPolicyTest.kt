package com.string1225.pocketpilot.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull

class OnboardingFlowPolicyTest {
    @Test
    fun `model step requires a stored verified connection and no active test`() {
        assertFalse(OnboardingFlowPolicy.canContinueModel(credentialConfigured = false, testInProgress = false))
        assertFalse(OnboardingFlowPolicy.canContinueModel(credentialConfigured = true, testInProgress = true))
        assertTrue(OnboardingFlowPolicy.canContinueModel(credentialConfigured = true, testInProgress = false))
    }

    @Test
    fun `optional setup continues only after an explicit skip or successful setup`() {
        assertFalse(OnboardingFlowPolicy.canContinueOptionalSetup(decision = null, setupSucceeded = false))
        assertFalse(OnboardingFlowPolicy.canContinueOptionalSetup(decision = true, setupSucceeded = false))
        assertTrue(OnboardingFlowPolicy.canContinueOptionalSetup(decision = true, setupSucceeded = true))
        assertTrue(OnboardingFlowPolicy.canContinueOptionalSetup(decision = false, setupSucceeded = false))
    }

    @Test
    fun `cold start restores an affirmative decision for a persisted project setup`() {
        assertTrue(OnboardingFlowPolicy.restoredProjectDecision(projectConfigured = true) == true)
        assertNull(OnboardingFlowPolicy.restoredProjectDecision(projectConfigured = false))
    }
}
