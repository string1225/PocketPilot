package com.string1225.pocketpilot.runtime

import android.webkit.JavascriptInterface

/** The single JavaScript-to-Native surface installed in the runtime WebView. */
class PocketPilotNativeBridge internal constructor(
    private val receiver: (String) -> Unit,
) {
    @JavascriptInterface
    fun postMessage(envelopeJson: String) {
        receiver(envelopeJson)
    }
}
