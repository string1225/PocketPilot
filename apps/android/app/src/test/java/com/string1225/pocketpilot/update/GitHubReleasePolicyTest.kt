package com.string1225.pocketpilot.update

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleasePolicyTest {
    @Test
    fun comparesStrictStableSemanticVersions() {
        assertTrue(GitHubReleasePolicy.isNewer("0.2.0", "0.1.9"))
        assertTrue(GitHubReleasePolicy.isNewer("1.0.0", "not-semver"))
        assertFalse(GitHubReleasePolicy.isNewer("1.2.3", "1.2.3"))
        assertFalse(GitHubReleasePolicy.isNewer("1.2.2", "1.2.3"))
        listOf("v1.2.3", "1.2", "01.2.3", "1.2.3-beta", "1000000000.0.0").forEach { version ->
            assertThrows(version, IllegalArgumentException::class.java) {
                GitHubReleasePolicy.apkAssetName(version)
            }
        }
        assertEquals("pocketpilot-1.2.3.apk", GitHubReleasePolicy.apkAssetName("1.2.3"))
        assertEquals("pocketpilot-1.2.3.apk.sha256", GitHubReleasePolicy.checksumAssetName("1.2.3"))
    }

    @Test
    fun acceptsOnlyTheExpectedRepositoryReleaseAssetUrl() {
        val expected = "pocketpilot-1.2.3.apk"
        val valid = "https://github.com/string1225/PocketPilot/releases/download/v1.2.3/$expected"
        assertEquals("github.com", GitHubReleasePolicy.requireReleaseAssetUrl(valid, "v1.2.3", expected).host)

        listOf(
            "http://github.com/string1225/PocketPilot/releases/download/v1.2.3/$expected",
            "https://user@github.com/string1225/PocketPilot/releases/download/v1.2.3/$expected",
            "https://github.com/string1225/PocketPilot/releases/download/v1.2.3/$expected?token=x",
            "https://github.com/string1225/Other/releases/download/v1.2.3/$expected",
            "https://github.com/String1225/PocketPilot/releases/download/v1.2.3/$expected",
            "https://github.com/string1225/PocketPilot/releases/download/v1.2.4/$expected",
            "https://github.com.evil.example/string1225/PocketPilot/releases/download/v1.2.3/$expected",
        ).forEach { url ->
            assertThrows(url, IllegalArgumentException::class.java) {
                GitHubReleasePolicy.requireReleaseAssetUrl(url, "v1.2.3", expected)
            }
        }
    }

    @Test
    fun redirectsRemainHttpsAndOnAnExplicitHostAllowlist() {
        val current = "https://github.com/string1225/PocketPilot/releases/download/v1.2.3/pocketpilot-1.2.3.apk".toHttpUrl()
        val allowed = GitHubReleasePolicy.resolveAssetRedirect(
            current,
            "https://release-assets.githubusercontent.com/github-production-release-asset/file?sig=secret",
        )
        assertEquals("release-assets.githubusercontent.com", allowed.host)

        listOf(
            "http://release-assets.githubusercontent.com/file",
            "https://release-assets.githubusercontent.com.evil.example/file",
            "https://127.0.0.1/file",
            "https://user@objects.githubusercontent.com/file",
            "ftp://github.com/file",
        ).forEach { location ->
            assertThrows(location, IllegalArgumentException::class.java) {
                GitHubReleasePolicy.resolveAssetRedirect(current, location)
            }
        }
    }

    @Test
    fun checksumMustContainOneDigestForTheExactApkName() {
        val digest = "a".repeat(64)
        val name = "pocketpilot-1.2.3.apk"
        assertEquals(digest, GitHubReleasePolicy.parseChecksum("$digest  $name\n".toByteArray(), name))
        assertEquals(digest, GitHubReleasePolicy.parseChecksum("${digest.uppercase()} *$name".toByteArray(), name))

        listOf(
            "$digest  other.apk\n",
            "$digest  $name\n$digest  $name\n",
            "sha256:$digest  $name",
            "a".repeat(63) + "  $name",
            "$digest  ../$name",
        ).forEach { checksum ->
            assertThrows(IllegalArgumentException::class.java) {
                GitHubReleasePolicy.parseChecksum(checksum.toByteArray(), name)
            }
        }
    }

    @Test
    fun automaticCheckThrottleHandlesIntervalsAndClockRollback() {
        val now = 1_000_000L
        assertTrue(UpdateCheckThrottle.shouldCheck(now, now))
        assertFalse(UpdateCheckThrottle.shouldCheck(now, now + 1_000L))
        assertTrue(
            UpdateCheckThrottle.shouldCheck(
                now,
                now + UpdateCheckThrottle.SUCCESS_INTERVAL_MILLIS + 1L,
            ),
        )
    }

    @Test
    fun releaseHttpClientDisablesAutomaticRedirectsAndUsesFiniteTimeouts() {
        val client = OkHttpGitHubReleaseSource.secureClient()
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertEquals(10_000, client.connectTimeoutMillis)
        assertEquals(60_000, client.readTimeoutMillis)
        assertEquals(600_000, client.callTimeoutMillis)
        assertEquals(30_000L, OkHttpGitHubReleaseSource.METADATA_TIMEOUT_MILLIS)
        assertEquals(60_000L, OkHttpGitHubReleaseSource.CHECKSUM_TIMEOUT_MILLIS)
        assertEquals(600_000L, OkHttpGitHubReleaseSource.APK_TIMEOUT_MILLIS)
    }
}
