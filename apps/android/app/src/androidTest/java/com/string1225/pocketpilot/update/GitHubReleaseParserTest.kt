package com.string1225.pocketpilot.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GitHubReleaseParserTest {
    @Test
    fun parsesTheTwoExactUploadedAssetsAndBoundsPublicMetadata() {
        val parsed = GitHubReleasePolicy.parseLatestRelease(releaseJson().toString().toByteArray())
        assertEquals("v1.2.3", parsed.public.tagName)
        assertEquals("1.2.3", parsed.public.versionName)
        assertEquals("PocketPilot 1.2.3", parsed.public.title)
        assertEquals(123_456L, parsed.public.apkSizeBytes)
        assertEquals("pocketpilot-1.2.3.apk", parsed.apkAsset.name)
        assertEquals("pocketpilot-1.2.3.apk.sha256", parsed.checksumAsset.name)
    }

    @Test
    fun rejectsDraftPrereleaseDuplicateAndCrossRepositoryAssets() {
        val draft = releaseJson().put("draft", true)
        assertThrows(IllegalArgumentException::class.java) {
            GitHubReleasePolicy.parseLatestRelease(draft.toString().toByteArray())
        }

        val prerelease = releaseJson().put("prerelease", true)
        assertThrows(IllegalArgumentException::class.java) {
            GitHubReleasePolicy.parseLatestRelease(prerelease.toString().toByteArray())
        }

        val duplicate = releaseJson()
        duplicate.getJSONArray("assets").put(duplicate.getJSONArray("assets").getJSONObject(0))
        assertThrows(IllegalArgumentException::class.java) {
            GitHubReleasePolicy.parseLatestRelease(duplicate.toString().toByteArray())
        }

        val crossRepository = releaseJson()
        crossRepository.getJSONArray("assets").getJSONObject(0).put(
            "browser_download_url",
            "https://github.com/attacker/PocketPilot/releases/download/v1.2.3/pocketpilot-1.2.3.apk",
        )
        assertThrows(IllegalArgumentException::class.java) {
            GitHubReleasePolicy.parseLatestRelease(crossRepository.toString().toByteArray())
        }

        val oversized = releaseJson()
        oversized.getJSONArray("assets").getJSONObject(0)
            .put("size", GitHubReleasePolicy.MAX_APK_BYTES + 1L)
        assertThrows(IllegalArgumentException::class.java) {
            GitHubReleasePolicy.parseLatestRelease(oversized.toString().toByteArray())
        }

        val missingChecksum = releaseJson()
        missingChecksum.put("assets", JSONArray().put(missingChecksum.getJSONArray("assets").getJSONObject(0)))
        assertThrows(IllegalArgumentException::class.java) {
            GitHubReleasePolicy.parseLatestRelease(missingChecksum.toString().toByteArray())
        }
    }

    private fun releaseJson(): JSONObject = JSONObject()
        .put("tag_name", "v1.2.3")
        .put("name", "PocketPilot 1.2.3")
        .put("body", "Security and stability update")
        .put("published_at", "2026-08-16T12:00:00Z")
        .put("draft", false)
        .put("prerelease", false)
        .put(
            "assets",
            JSONArray()
                .put(asset("pocketpilot-1.2.3.apk", 123_456L))
                .put(asset("pocketpilot-1.2.3.apk.sha256", 89L)),
        )

    private fun asset(name: String, size: Long): JSONObject = JSONObject()
        .put("name", name)
        .put("state", "uploaded")
        .put("size", size)
        .put(
            "browser_download_url",
            "https://github.com/string1225/PocketPilot/releases/download/v1.2.3/$name",
        )
}
