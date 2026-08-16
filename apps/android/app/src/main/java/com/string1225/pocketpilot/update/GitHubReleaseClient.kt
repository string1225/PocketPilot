package com.string1225.pocketpilot.update

import com.string1225.pocketpilot.integrations.http.PublicOnlyDns
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal data class DownloadedUpdate(
    val file: File,
    val expectedSha256: String,
    val sizeBytes: Long,
)

internal interface GitHubReleaseSource {
    suspend fun latestRelease(): ResolvedUpdateRelease

    suspend fun download(
        release: ResolvedUpdateRelease,
        destination: File,
        onProgress: (bytesDownloaded: Long, totalBytes: Long?) -> Unit,
    ): DownloadedUpdate
}

internal class OkHttpGitHubReleaseSource(
    private val client: OkHttpClient = secureClient(),
) : GitHubReleaseSource {
    override suspend fun latestRelease(): ResolvedUpdateRelease {
        val url = GitHubReleasePolicy.LATEST_RELEASE_URL.toHttpUrl()
        GitHubReleasePolicy.requireApiUrl(url)
        val request = request(url, isApi = true)
        val bytes = execute(request, redirectsAllowed = false, timeoutMillis = METADATA_TIMEOUT_MILLIS) { response ->
            requireSuccessful(response, "GitHub release check")
            requireIdentityEncoding(response)
            val mediaType = response.body.contentType()
            if (mediaType != null) {
                require(mediaType.type == "application" && mediaType.subtype.contains("json")) {
                    "GitHub release response is not JSON"
                }
            }
            readBounded(response, GitHubReleasePolicy.MAX_RELEASE_JSON_BYTES)
        }
        return try {
            GitHubReleasePolicy.parseLatestRelease(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    override suspend fun download(
        release: ResolvedUpdateRelease,
        destination: File,
        onProgress: (bytesDownloaded: Long, totalBytes: Long?) -> Unit,
    ): DownloadedUpdate {
        val checksumBytes = downloadSmallAsset(release.checksumAsset, release.public.tagName)
        val expectedSha256 = try {
            GitHubReleasePolicy.parseChecksum(checksumBytes, release.apkAsset.name)
        } finally {
            checksumBytes.fill(0)
        }
        check(destination.parentFile?.isDirectory == true) { "Update cache directory is unavailable" }
        val temporary = File(destination.parentFile, "${destination.name}.part")
        require(temporary.parentFile?.canonicalFile == destination.parentFile?.canonicalFile) {
            "Update cache path is invalid"
        }
        temporary.delete()
        destination.delete()
        try {
            val apkUrl = GitHubReleasePolicy.requireReleaseAssetUrl(
                release.apkAsset.downloadUrl,
                release.public.tagName,
                release.apkAsset.name,
            )
            val apkRequest = request(apkUrl, isApi = false)
            val downloadedSize = execute(
                apkRequest,
                redirectsAllowed = true,
                timeoutMillis = APK_TIMEOUT_MILLIS,
            ) { response ->
                requireSuccessful(response, "Release APK download")
                requireIdentityEncoding(response)
                streamApk(
                    response = response,
                    destination = temporary,
                    expectedBytes = release.apkAsset.sizeBytes,
                    onProgress = onProgress,
                )
            }
            require(downloadedSize == release.apkAsset.sizeBytes) { "Downloaded APK size does not match release metadata" }
            check(temporary.renameTo(destination)) { "Unable to finalize downloaded update" }
            return DownloadedUpdate(destination, expectedSha256, downloadedSize)
        } catch (error: Throwable) {
            temporary.delete()
            destination.delete()
            throw error
        }
    }

    private suspend fun downloadSmallAsset(asset: UpdateAsset, tag: String): ByteArray {
        val url = GitHubReleasePolicy.requireReleaseAssetUrl(asset.downloadUrl, tag, asset.name)
        val request = request(url, isApi = false)
        val bytes = execute(request, redirectsAllowed = true, timeoutMillis = CHECKSUM_TIMEOUT_MILLIS) { response ->
            requireSuccessful(response, "Release checksum download")
            requireIdentityEncoding(response)
            readBounded(response, GitHubReleasePolicy.MAX_CHECKSUM_BYTES)
        }
        require(bytes.size.toLong() == asset.sizeBytes) { "Downloaded checksum size does not match release metadata" }
        return bytes
    }

    private suspend fun <T> execute(
        initialRequest: Request,
        redirectsAllowed: Boolean,
        timeoutMillis: Long,
        consume: (Response) -> T,
    ): T = suspendCancellableCoroutine { continuation ->
        val activeCall = AtomicReference<Call?>()
        continuation.invokeOnCancellation { activeCall.getAndSet(null)?.cancel() }

        lateinit var follow: (Request, Int) -> Unit
        follow = { request, redirects ->
            if (continuation.isActive) {
                val call = client.newCall(request)
                call.timeout().timeout(timeoutMillis, TimeUnit.MILLISECONDS)
                activeCall.set(call)
                if (!continuation.isActive) {
                    activeCall.getAndSet(null)?.cancel()
                } else {
                    call.enqueue(
                        object : Callback {
                            override fun onFailure(call: Call, e: IOException) {
                                activeCall.compareAndSet(call, null)
                                if (continuation.isActive) {
                                    continuation.resumeWithException(
                                        UpdateException("NETWORK_ERROR", "Unable to contact GitHub", e),
                                    )
                                }
                            }

                            override fun onResponse(call: Call, response: Response) {
                                if (!continuation.isActive) {
                                    activeCall.compareAndSet(call, null)
                                    response.close()
                                    return
                                }
                                var redirectRequest: Request? = null
                                try {
                                    response.use {
                                        if (response.code in REDIRECT_STATUS_CODES) {
                                            require(redirectsAllowed) { "Unexpected redirect from GitHub API" }
                                            require(redirects < GitHubReleasePolicy.MAX_REDIRECTS) {
                                                "Too many release download redirects"
                                            }
                                            val locations = response.headers.values("Location")
                                            require(locations.size == 1) { "Release redirect is missing or ambiguous" }
                                            val nextUrl = GitHubReleasePolicy.resolveAssetRedirect(
                                                request.url,
                                                locations.single(),
                                            )
                                            redirectRequest = request.newBuilder().url(nextUrl).build()
                                        } else {
                                            val result = consume(response)
                                            if (continuation.isActive) continuation.resume(result)
                                        }
                                    }
                                    redirectRequest?.let { next -> follow(next, redirects + 1) }
                                } catch (error: Throwable) {
                                    if (continuation.isActive) continuation.resumeWithException(error)
                                } finally {
                                    activeCall.compareAndSet(call, null)
                                }
                            }
                        },
                    )
                }
            }
        }
        follow(initialRequest, 0)
    }

    private fun streamApk(
        response: Response,
        destination: File,
        expectedBytes: Long,
        onProgress: (Long, Long?) -> Unit,
    ): Long {
        require(expectedBytes in 1..GitHubReleasePolicy.MAX_APK_BYTES) { "Release APK size is invalid" }
        val contentLength = response.body.contentLength()
        require(contentLength == -1L || contentLength == expectedBytes) {
            "Release APK Content-Length does not match release metadata"
        }
        val totalForProgress = contentLength.takeIf { it >= 0L } ?: expectedBytes
        var total = 0L
        var lastReported = 0L
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        try {
            response.body.byteStream().use { input ->
                FileOutputStream(destination).use { output ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= expectedBytes && total <= GitHubReleasePolicy.MAX_APK_BYTES) {
                            "Release APK exceeds its declared size"
                        }
                        output.write(buffer, 0, count)
                        if (total - lastReported >= PROGRESS_STEP_BYTES || total == expectedBytes) {
                            onProgress(total, totalForProgress)
                            lastReported = total
                        }
                    }
                    output.fd.sync()
                }
            }
        } finally {
            buffer.fill(0)
        }
        if (lastReported != total) onProgress(total, totalForProgress)
        return total
    }

    private fun readBounded(response: Response, maximumBytes: Int): ByteArray {
        val contentLength = response.body.contentLength()
        require(contentLength == -1L || contentLength in 0..maximumBytes.toLong()) {
            "GitHub response is too large"
        }
        response.body.byteStream().use { input ->
            val output = ByteArrayOutputStream(minOf(maximumBytes, 16 * 1024))
            val buffer = ByteArray(8 * 1024)
            var total = 0
            try {
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= maximumBytes) { "GitHub response is too large" }
                    output.write(buffer, 0, count)
                }
                return output.toByteArray()
            } finally {
                buffer.fill(0)
            }
        }
    }

    private fun request(url: HttpUrl, isApi: Boolean): Request = Request.Builder()
        .url(url)
        .get()
        .header("Accept-Encoding", "identity")
        .header("User-Agent", USER_AGENT)
        .apply {
            if (isApi) {
                header("Accept", "application/vnd.github+json")
                header("X-GitHub-Api-Version", GITHUB_API_VERSION)
            } else {
                header("Accept", "application/octet-stream")
            }
        }
        .build()

    private fun requireSuccessful(response: Response, operation: String) {
        if (response.code !in 200..299) {
            throw UpdateException("HTTP_${response.code}", "$operation failed with HTTP ${response.code}")
        }
    }

    private fun requireIdentityEncoding(response: Response) {
        val encodings = response.headers.values("Content-Encoding")
        require(encodings.isEmpty() || (encodings.size == 1 && encodings.single().equals("identity", true))) {
            "Compressed update responses are not accepted"
        }
    }

    companion object {
        private const val USER_AGENT = "PocketPilot-Android-Updater/1"
        private const val GITHUB_API_VERSION = "2022-11-28"
        private const val COPY_BUFFER_BYTES = 32 * 1024
        private const val PROGRESS_STEP_BYTES = 256 * 1024L
        internal const val METADATA_TIMEOUT_MILLIS = 30_000L
        internal const val CHECKSUM_TIMEOUT_MILLIS = 60_000L
        internal const val APK_TIMEOUT_MILLIS = 10L * 60L * 1_000L
        private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)

        internal fun secureClient(): OkHttpClient = OkHttpClient.Builder()
            .dns(PublicOnlyDns())
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .build()
    }
}
