package com.string1225.pocketpilot.runtime

import android.os.SystemClock
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeSandboxCspWebViewTest {
    @Test
    fun runtimeResponseHeaderBlocksEvalAndAllowsBlobWorker() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bridgeError = AtomicReference<RuntimeBridgeError?>()
        val webView = AtomicReference<WebView>()
        instrumentation.runOnMainSync {
            webView.set(
                WebView(instrumentation.targetContext).also { view ->
                    configureRuntimeWebView(view) { error -> bridgeError.set(error) }
                    view.loadUrl(RuntimeAssetUrlPolicy.urlFor("pocketpilot-runtime.html"))
                },
            )
        }

        try {
            assertTrue(
                "PocketPilot runtime did not become ready",
                pollJavascript(webView.get(), "typeof PocketPilotRuntime === 'object'") == "true",
            )
            val workerSource = "self.onmessage=function(){self.postMessage('ok')};"
            evaluateJavascript(
                webView.get(),
                """
                (() => {
                  window.__pocketpilotCspSmoke = { evalBlocked: false, worker: "pending" };
                  try { new Function("return 1")(); }
                  catch (_) { window.__pocketpilotCspSmoke.evalBlocked = true; }
                  const url = URL.createObjectURL(new Blob([${JSONObject.quote(workerSource)}], { type: "text/javascript" }));
                  const worker = new Worker(url);
                  worker.onmessage = (event) => {
                    window.__pocketpilotCspSmoke.worker = event.data;
                    worker.terminate();
                    URL.revokeObjectURL(url);
                  };
                  worker.onerror = () => {
                    window.__pocketpilotCspSmoke.worker = "error";
                    worker.terminate();
                    URL.revokeObjectURL(url);
                  };
                  worker.postMessage(null);
                })();
                """.trimIndent(),
            )

            assertEquals(
                "Runtime HTML response did not enforce script-src without unsafe-eval",
                "true",
                pollJavascript(
                    webView.get(),
                    "window.__pocketpilotCspSmoke && window.__pocketpilotCspSmoke.evalBlocked === true",
                ),
            )
            assertEquals(
                "Runtime CSP did not allow its isolated Blob Worker",
                "\"ok\"",
                pollJavascript(
                    webView.get(),
                    "window.__pocketpilotCspSmoke && window.__pocketpilotCspSmoke.worker",
                ),
            )
            assertNull(bridgeError.get())
        } finally {
            instrumentation.runOnMainSync { webView.get().destroy() }
        }
    }

    private fun pollJavascript(webView: WebView, script: String): String? {
        repeat(100) {
            val value = evaluateJavascript(webView, script)
            if (value != "null" && value != "false" && value != "\"pending\"") return value
            SystemClock.sleep(100)
        }
        return null
    }

    private fun evaluateJavascript(webView: WebView, script: String): String {
        val value = AtomicReference<String>()
        val completed = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webView.evaluateJavascript(script) { result ->
                value.set(result)
                completed.countDown()
            }
        }
        assertTrue("evaluateJavascript timed out", completed.await(2, TimeUnit.SECONDS))
        return value.get()
    }
}
