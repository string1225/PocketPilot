package com.string1225.pocketpilot.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnboardingVersionPolicyTest {
    @Test
    fun `fresh installs remain pending while upgraded installs skip newly added onboarding`() {
        assertEquals(0, OnboardingVersionPolicy.initialVersion(existingProjects = false))
        assertEquals(
            OnboardingVersionPolicy.CURRENT_VERSION,
            OnboardingVersionPolicy.initialVersion(existingProjects = true),
        )
    }

    @Test
    fun `completion marker fails closed for missing malformed and old versions`() {
        assertFalse(OnboardingVersionPolicy.isCompleted(null))
        assertFalse(OnboardingVersionPolicy.isCompleted("not-a-number"))
        assertFalse(OnboardingVersionPolicy.isCompleted("0"))
        assertTrue(OnboardingVersionPolicy.isCompleted(OnboardingVersionPolicy.CURRENT_VERSION.toString()))
        assertTrue(OnboardingVersionPolicy.isCompleted((OnboardingVersionPolicy.CURRENT_VERSION + 1).toString()))
    }
}
