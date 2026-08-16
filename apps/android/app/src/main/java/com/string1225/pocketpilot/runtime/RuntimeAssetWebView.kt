package com.string1225.pocketpilot.runtime

import android.content.res.AssetManager
import android.net.http.SslError
import android.os.Message
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.util.Locale

/** URL policy for the private, Native-served WebView asset origin. */
object RuntimeAssetUrlPolicy {
    const val ORIGIN: String = "https://appassets.androidplatform.net"
    private const val ASSET_PREFIX: String = "/assets/"
    private val safeSegment = Regex("[A-Za-z0-9._-]+")

    fun urlFor(assetPath: String): String {
        val normalized = normalizeAssetPath(assetPath)
        return "$ORIGIN$ASSET_PREFIX$normalized"
    }

    fun assetPath(url: String): String? {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return null
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (!uri.host.equals("appassets.androidplatform.net", ignoreCase = true)) return null
        if (uri.port != -1 || uri.userInfo != null || uri.query != null || uri.fragment != null) return null
        val rawPath = uri.rawPath ?: return null
        val path = uri.path ?: return null
        if (rawPath.contains('%') || !path.startsWith(ASSET_PREFIX)) return null
        return runCatching { normalizeAssetPath(path.removePrefix(ASSET_PREFIX)) }.getOrNull()
    }

    private fun normalizeAssetPath(assetPath: String): String {
        require(assetPath.isNotBlank()) { "Runtime asset path must not be blank" }
        require(!assetPath.startsWith('/') && !assetPath.contains('\\')) {
            "Runtime asset path must be relative"
        }
        val segments = assetPath.split('/')
        require(segments.all { it.isNotEmpty() && it != "." && it != ".." && safeSegment.matches(it) }) {
            "Runtime asset path contains an unsafe segment"
        }
        return segments.joinToString("/")
    }
}

internal class RuntimeAssetWebViewClient(
    private val assets: AssetManager,
    private val onBridgeError: (RuntimeBridgeError) -> Unit,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        RuntimeAssetUrlPolicy.assetPath(request.url.toString()) == null

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
        RuntimeAssetUrlPolicy.assetPath(url) == null

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse {
        if (!request.method.equals("GET", ignoreCase = true)) return forbidden()
        return assetResponse(request.url.toString())
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse =
        assetResponse(url)

    override fun onReceivedSslError(
        view: WebView,
        handler: SslErrorHandler,
        error: SslError,
    ) {
        handler.cancel()
        onBridgeError(
            RuntimeBridgeError(
                code = "runtime_ssl_error",
                message = "Runtime page SSL validation failed: ${error.primaryError}",
            ),
        )
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        if (!request.isForMainFrame) return
        onBridgeError(
            RuntimeBridgeError(
                code = "runtime_page_load_failed",
                message = "Runtime page failed to load (${error.errorCode}): ${error.description}",
            ),
        )
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse,
    ) {
        if (!request.isForMainFrame) return
        onBridgeError(
            RuntimeBridgeError(
                code = "runtime_page_load_failed",
                message = "Runtime page returned HTTP ${errorResponse.statusCode} ${errorResponse.reasonPhrase}",
            ),
        )
    }

    override fun onRenderProcessGone(
        view: WebView,
        detail: RenderProcessGoneDetail,
    ): Boolean {
        onBridgeError(
            RuntimeBridgeError(
                code = "runtime_renderer_gone",
                message = if (detail.didCrash()) {
                    "Runtime WebView renderer crashed"
                } else {
                    "Runtime WebView renderer was terminated by the system"
                },
            ),
        )
        return true
    }

    private fun assetResponse(url: String): WebResourceResponse {
        val assetPath = RuntimeAssetUrlPolicy.assetPath(url) ?: return forbidden()
        return try {
            val media = mediaType(assetPath)
            WebResourceResponse(
                media.mimeType,
                media.encoding,
                200,
                "OK",
                responseHeaders(assetPath),
                assets.open(assetPath, AssetManager.ACCESS_STREAMING),
            )
        } catch (_: IOException) {
            notFound()
        }
    }

    private fun responseHeaders(assetPath: String): Map<String, String> = buildMap {
        put("Cache-Control", "no-store")
        put("X-Content-Type-Options", "nosniff")
        if (assetPath.endsWith(".html", ignoreCase = true)) {
            put(
                "Content-Security-Policy",
                "default-src 'self'; connect-src 'none'; img-src 'self' data:; " +
                    "style-src 'self' 'unsafe-inline'; script-src 'self'; " +
                    "worker-src blob:; child-src blob:; object-src 'none'; " +
                    "frame-src 'none'; base-uri 'none'; form-action 'none'",
            )
        }
    }

    private fun forbidden(): WebResourceResponse = errorResponse(403, "Forbidden")

    private fun notFound(): WebResourceResponse = errorResponse(404, "Not Found")

    private fun errorResponse(statusCode: Int, reason: String): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "UTF-8",
            statusCode,
            reason,
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(ByteArray(0)),
        )

    private fun mediaType(path: String): AssetMediaType {
        val extension = path.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.ROOT)
        return when (extension) {
            "html", "htm" -> AssetMediaType("text/html", "UTF-8")
            "js", "mjs" -> AssetMediaType("application/javascript", "UTF-8")
            "css" -> AssetMediaType("text/css", "UTF-8")
            "json", "map" -> AssetMediaType("application/json", "UTF-8")
            "svg" -> AssetMediaType("image/svg+xml", "UTF-8")
            "txt" -> AssetMediaType("text/plain", "UTF-8")
            "wasm" -> AssetMediaType("application/wasm", null)
            "png" -> AssetMediaType("image/png", null)
            "jpg", "jpeg" -> AssetMediaType("image/jpeg", null)
            "webp" -> AssetMediaType("image/webp", null)
            else -> AssetMediaType("application/octet-stream", null)
        }
    }
}

internal class NoPopupWebChromeClient : WebChromeClient() {
    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message,
    ): Boolean = false
}

internal data class AssetMediaType(
    val mimeType: String,
    val encoding: String?,
)

@Suppress("DEPRECATION", "SetJavaScriptEnabled")
internal fun configureRuntimeWebView(
    webView: WebView,
    onBridgeError: (RuntimeBridgeError) -> Unit = {},
) {
    webView.settings.apply {
        javaScriptEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        allowFileAccessFromFileURLs = false
        allowUniversalAccessFromFileURLs = false
        blockNetworkLoads = true
        blockNetworkImage = true
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        domStorageEnabled = false
        databaseEnabled = false
        cacheMode = WebSettings.LOAD_NO_CACHE
        mediaPlaybackRequiresUserGesture = true
        safeBrowsingEnabled = true
    }
    webView.removeJavascriptInterface("searchBoxJavaBridge_")
    webView.removeJavascriptInterface("accessibility")
    webView.removeJavascriptInterface("accessibilityTraversal")
    webView.webViewClient = RuntimeAssetWebViewClient(webView.context.assets, onBridgeError)
    webView.webChromeClient = NoPopupWebChromeClient()
}
