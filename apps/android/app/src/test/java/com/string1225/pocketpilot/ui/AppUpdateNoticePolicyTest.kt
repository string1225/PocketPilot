package com.string1225.pocketpilot.ui

import com.string1225.pocketpilot.update.UpdatePhase
import com.string1225.pocketpilot.update.UpdateRelease
import com.string1225.pocketpilot.update.UpdateState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateNoticePolicyTest {
    @Test
    fun notifiesOnceWhenACompletedCheckFindsANewVersion() {
        val available = availableState("0.2.0")

        assertTrue(shouldNotifyAvailableUpdate(UpdatePhase.CHECKING, available, null))
        assertTrue(shouldNotifyAvailableUpdate(UpdatePhase.IDLE, available, null))
        assertFalse(shouldNotifyAvailableUpdate(UpdatePhase.AVAILABLE, available, null))
        assertFalse(shouldNotifyAvailableUpdate(UpdatePhase.CHECKING, available, "0.2.0"))
        assertTrue(shouldNotifyAvailableUpdate(UpdatePhase.CHECKING, availableState("0.3.0"), "0.2.0"))
    }

    private fun availableState(version: String): UpdateState = UpdateState(
        phase = UpdatePhase.AVAILABLE,
        currentVersionName = "0.1.0",
        currentVersionCode = 1L,
        release = UpdateRelease(
            tagName = "v$version",
            versionName = version,
            title = "PocketPilot $version",
            body = "",
            publishedAt = "2026-08-16T00:00:00Z",
            apkSizeBytes = 1L,
        ),
    )
}
