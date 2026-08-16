package com.string1225.pocketpilot.update

import org.junit.Assert.assertThrows
import org.junit.Test

class ApkUpdatePolicyTest {
    private val current = InstalledAppIdentity(
        packageName = "com.string1225.pocketpilot",
        versionName = "1.0.0",
        versionCode = 10L,
        signingCertificateSha256 = setOf("certificate-a"),
    )

    @Test
    fun acceptsOnlyANewerApkWithTheSamePackageAndSignerSet() {
        ApkUpdatePolicy.requireSafeUpgrade(
            current,
            current.copy(versionName = "1.1.0", versionCode = 11L),
            expectedVersionName = "1.1.0",
        )

        listOf(
            current.copy(packageName = "com.attacker.app", versionCode = 11L),
            current.copy(versionCode = 10L),
            current.copy(versionCode = 9L),
            current.copy(versionCode = 11L, signingCertificateSha256 = setOf("certificate-b")),
            current.copy(versionCode = 11L, signingCertificateSha256 = setOf("certificate-a", "certificate-b")),
        ).forEach { candidate ->
            assertThrows(IllegalArgumentException::class.java) {
                ApkUpdatePolicy.requireSafeUpgrade(current, candidate, candidate.versionName)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ApkUpdatePolicy.requireSafeUpgrade(
                current,
                current.copy(versionName = "1.1.0", versionCode = 11L),
                expectedVersionName = "1.1.1",
            )
        }
    }

    @Test
    fun refusesVerificationWhenInstalledCertificateIsUnavailable() {
        assertThrows(IllegalArgumentException::class.java) {
            ApkUpdatePolicy.requireSafeUpgrade(
                current.copy(signingCertificateSha256 = emptySet()),
                current.copy(versionCode = 11L),
                expectedVersionName = current.versionName,
            )
        }
    }
}
