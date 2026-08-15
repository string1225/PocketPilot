package com.string1225.agentdock.runtime

import android.webkit.JavascriptInterface

/** The single JavaScript-to-Native surface installed in the runtime WebView. */
class AgentDockNativeBridge internal constructor(
    private val receiver: (String) -> Unit,
) {
    @JavascriptInterface
    fun postMessage(envelopeJson: String) {
        receiver(envelopeJson)
    }
}
