package com.string1225.pocketpilot.update

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Locale
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

internal object GitHubReleasePolicy {
    const val OWNER = "string1225"
    const val REPOSITORY = "PocketPilot"
    const val LATEST_RELEASE_URL = "https://api.github.com/repos/$OWNER/$REPOSITORY/releases/latest"
    const val MAX_RELEASE_JSON_BYTES = 512 * 1024
    const val MAX_CHECKSUM_BYTES = 4 * 1024
    const val MAX_APK_BYTES = 256L * 1024L * 1024L
    const val MAX_REDIRECTS = 5
    const val MAX_URL_CHARS = 4_096

    private const val MAX_ASSETS = 100
    private const val MAX_RELEASE_TITLE_CHARS = 256
    private const val MAX_RELEASE_BODY_CHARS = 16_384
    private const val MAX_PUBLISHED_AT_CHARS = 64
    private val semanticVersion = Regex("^(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})$")
    private val redirectHosts = setOf(
        "github.com",
        "release-assets.githubusercontent.com",
        "objects.githubusercontent.com",
    )

    fun parseLatestRelease(bytes: ByteArray): ResolvedUpdateRelease {
        require(bytes.isNotEmpty() && bytes.size <= MAX_RELEASE_JSON_BYTES) {
            "GitHub release metadata is empty or too large"
        }
        val text = decodeUtf8(bytes, "GitHub release metadata is not valid UTF-8")
        val root = runCatching { JSONObject(text) }
            .getOrElse { throw IllegalArgumentException("GitHub release metadata is not valid JSON", it) }
        require(root.strictBoolean("draft") == false) { "Draft releases cannot be installed" }
        require(root.strictBoolean("prerelease") == false) { "Prereleases cannot be installed" }
        val tagName = root.strictString("tag_name", 64)
        require(tagName.startsWith('v')) { "Release tag must start with v" }
        val versionName = tagName.substring(1)
        require(parseVersion(versionName) != null) { "Release tag is not a supported semantic version" }

        val expectedApkName = apkAssetName(versionName)
        val expectedChecksumName = checksumAssetName(versionName)
        val assets = root.opt("assets") as? JSONArray
            ?: throw IllegalArgumentException("Release assets are missing")
        require(assets.length() in 1..MAX_ASSETS) { "Release contains an invalid number of assets" }
        var apkAsset: UpdateAsset? = null
        var checksumAsset: UpdateAsset? = null
        val seenNames = hashSetOf<String>()
        for (index in 0 until assets.length()) {
            val assetObject = assets.opt(index) as? JSONObject
                ?: throw IllegalArgumentException("Release asset is invalid")
            val name = assetObject.strictString("name", 160)
            require(seenNames.add(name)) { "Release contains duplicate asset names" }
            if (name != expectedApkName && name != expectedChecksumName) continue
            val state = assetObject.strictString("state", 16)
            require(state == "uploaded") { "Release asset is not fully uploaded" }
            val size = assetObject.strictLong("size")
            val maximum = if (name == expectedApkName) MAX_APK_BYTES else MAX_CHECKSUM_BYTES.toLong()
            require(size in 1..maximum) { "Release asset has an invalid size" }
            val url = assetObject.strictString("browser_download_url", MAX_URL_CHARS)
            requireReleaseAssetUrl(url, tagName, name)
            val parsed = UpdateAsset(name, size, url)
            if (name == expectedApkName) apkAsset = parsed else checksumAsset = parsed
        }
        val resolvedApk = requireNotNull(apkAsset) { "Release APK asset is missing" }
        val resolvedChecksum = requireNotNull(checksumAsset) { "Release checksum asset is missing" }
        val publishedAt = root.strictString("published_at", MAX_PUBLISHED_AT_CHARS)
        runCatching { Instant.parse(publishedAt) }
            .getOrElse { throw IllegalArgumentException("Release publish time is invalid", it) }
        val title = root.optionalString("name", MAX_RELEASE_TITLE_CHARS).orEmpty().ifBlank { tagName }
        val body = root.optionalString("body", MAX_RELEASE_BODY_CHARS).orEmpty()
        return ResolvedUpdateRelease(
            public = UpdateRelease(
                tagName = tagName,
                versionName = versionName,
                title = title,
                body = body,
                publishedAt = publishedAt,
                apkSizeBytes = resolvedApk.sizeBytes,
            ),
            apkAsset = resolvedApk,
            checksumAsset = resolvedChecksum,
        )
    }

    fun isNewer(releaseVersion: String, currentVersion: String): Boolean {
        val release = requireNotNull(parseVersion(releaseVersion)) { "Release version is invalid" }
        val current = parseVersion(currentVersion) ?: return true
        for (index in release.indices) {
            if (release[index] != current[index]) return release[index] > current[index]
        }
        return false
    }

    fun parseChecksum(bytes: ByteArray, expectedApkName: String): String {
        require(bytes.isNotEmpty() && bytes.size <= MAX_CHECKSUM_BYTES) {
            "Release checksum is empty or too large"
        }
        val text = decodeUtf8(bytes, "Release checksum is not valid UTF-8")
        require(text.none { it.isISOControl() && it != '\r' && it != '\n' && it != '\t' }) {
            "Release checksum contains control characters"
        }
        val match = Regex("^([0-9a-fA-F]{64})[ \\t]+\\*?([^\\r\\n]+)\\r?\\n?$").matchEntire(text)
            ?: throw IllegalArgumentException("Release checksum has an invalid format")
        require(match.groupValues[2] == expectedApkName) { "Release checksum names a different APK" }
        return match.groupValues[1].lowercase(Locale.ROOT)
    }

    fun requireReleaseAssetUrl(rawUrl: String, tagName: String, assetName: String): HttpUrl {
        require(rawUrl.isNotBlank() && rawUrl == rawUrl.trim() && rawUrl.length <= MAX_URL_CHARS) {
            "Release asset URL is invalid"
        }
        require(rawUrl.none { it == '\\' || it.isISOControl() }) { "Release asset URL is unsafe" }
        val url = rawUrl.toHttpUrlOrNull() ?: throw IllegalArgumentException("Release asset URL is invalid")
        requireSecureUrl(url)
        require(url.host == "github.com") { "Release asset must be hosted by GitHub" }
        require(url.query == null) { "Initial release asset URL must not contain a query" }
        require(
            url.pathSegments == listOf(OWNER, REPOSITORY, "releases", "download", tagName, assetName),
        ) { "Release asset URL does not match the configured repository" }
        return url
    }

    fun resolveAssetRedirect(current: HttpUrl, location: String): HttpUrl {
        require(location.isNotBlank() && location.length <= MAX_URL_CHARS) { "Release redirect is invalid" }
        require(location.none { it == '\\' || it.isISOControl() }) { "Release redirect is unsafe" }
        val target = current.resolve(location) ?: throw IllegalArgumentException("Release redirect is invalid")
        requireSecureUrl(target)
        require(target.host in redirectHosts) { "Release redirect host is not trusted" }
        return target
    }

    fun requireApiUrl(url: HttpUrl) {
        require(url.toString() == LATEST_RELEASE_URL) { "Unexpected GitHub API URL" }
    }

    fun apkAssetName(versionName: String): String {
        require(parseVersion(versionName) != null) { "Version is invalid" }
        return "pocketpilot-$versionName.apk"
    }

    fun checksumAssetName(versionName: String): String = "${apkAssetName(versionName)}.sha256"

    private fun requireSecureUrl(url: HttpUrl) {
        require(url.scheme == "https" && url.port == 443) { "Release URL must use HTTPS" }
        require(url.username.isEmpty() && url.password.isEmpty()) { "Release URL must not contain userinfo" }
        require(url.fragment == null) { "Release URL must not contain a fragment" }
        require(url.toString().length <= MAX_URL_CHARS) { "Release URL is too long" }
    }

    private fun parseVersion(value: String): LongArray? {
        val match = semanticVersion.matchEntire(value) ?: return null
        return LongArray(3) { index -> match.groupValues[index + 1].toLong() }
    }

    private fun decodeUtf8(bytes: ByteArray, message: String): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: Exception) {
        throw IllegalArgumentException(message, error)
    }

    private fun JSONObject.strictBoolean(key: String): Boolean =
        (opt(key) as? Boolean) ?: throw IllegalArgumentException("Release $key is invalid")

    private fun JSONObject.strictLong(key: String): Long {
        val value = opt(key) as? Number ?: throw IllegalArgumentException("Release $key is invalid")
        val long = value.toLong()
        require(value.toDouble().isFinite() && value.toDouble() == long.toDouble()) { "Release $key is invalid" }
        return long
    }

    private fun JSONObject.strictString(key: String, maxChars: Int): String {
        val value = opt(key) as? String ?: throw IllegalArgumentException("Release $key is invalid")
        require(value.isNotBlank() && value.length <= maxChars && value.none(Char::isISOControl)) {
            "Release $key is invalid"
        }
        return value
    }

    private fun JSONObject.optionalString(key: String, maxChars: Int): String? {
        val raw = opt(key)
        if (raw == null || raw === JSONObject.NULL) return null
        val value = raw as? String ?: throw IllegalArgumentException("Release $key is invalid")
        require(value.none { it.isISOControl() && it != '\r' && it != '\n' && it != '\t' }) {
            "Release $key is invalid"
        }
        return value.take(maxChars)
    }
}

internal object UpdateCheckThrottle {
    const val SUCCESS_INTERVAL_MILLIS = 24L * 60L * 60L * 1_000L
    const val FAILURE_RETRY_MILLIS = 60L * 60L * 1_000L

    fun shouldCheck(nowMillis: Long, nextCheckAtMillis: Long): Boolean {
        if (nextCheckAtMillis <= nowMillis) return true
        // A large future value generally means the device clock moved backwards.
        return nextCheckAtMillis - nowMillis > SUCCESS_INTERVAL_MILLIS
    }
}
