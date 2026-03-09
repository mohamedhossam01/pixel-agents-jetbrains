package com.pixelagents.jcef

import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.pixelagents.util.JsonUtils
import org.cef.browser.CefBrowser
import org.cef.handler.CefLoadHandler
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bidirectional message bridge between Kotlin host and JCEF webview.
 *
 * JS -> Kotlin: via JBCefJSQuery, injected as window.__pixelAgentsBridge.postMessage(json)
 * Kotlin -> JS: via cefBrowser.executeJavaScript(), dispatches synthetic MessageEvent
 *
 * Messages are queued until webviewReady is received.
 */
class MessageBridge(
    private val browser: JBCefBrowser,
    private val onMessage: (type: String, data: Map<String, Any?>) -> Unit,
) {
    private val log = Logger.getInstance(MessageBridge::class.java)
    private val jsQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)
    private val ready = AtomicBoolean(false)
    private val messageQueue = ConcurrentLinkedQueue<String>()

    init {
        // Register JS -> Kotlin callback
        jsQuery.addHandler { jsonStr ->
            try {
                val data = JsonUtils.toMap(jsonStr)
                val type = data["type"] as? String ?: return@addHandler null
                if (type == "webviewReady") {
                    ready.set(true)
                    flushQueue()
                }
                onMessage(type, data)
            } catch (e: Exception) {
                log.warn("Failed to parse message from webview: $jsonStr", e)
            }
            null
        }

        // Inject bridge script when page loads
        browser.jbCefClient.addLoadHandler(object : CefLoadHandler {
            override fun onLoadingStateChange(
                browser: CefBrowser, isLoading: Boolean, canGoBack: Boolean, canGoForward: Boolean
            ) {}

            override fun onLoadStart(browser: CefBrowser, frame: org.cef.browser.CefFrame, transitionType: org.cef.network.CefRequest.TransitionType) {}

            override fun onLoadEnd(browser: CefBrowser, frame: org.cef.browser.CefFrame, httpStatusCode: Int) {
                if (frame.isMain) {
                    injectBridge(browser)
                }
            }

            override fun onLoadError(
                browser: CefBrowser, frame: org.cef.browser.CefFrame,
                errorCode: CefLoadHandler.ErrorCode, errorText: String, failedUrl: String
            ) {
                log.warn("JCEF load error: $errorCode $errorText for $failedUrl")
            }
        }, browser.cefBrowser)
    }

    private fun injectBridge(cefBrowser: CefBrowser) {
        val injection = jsQuery.inject("msg")
        val script = """
            (function() {
                window.__pixelAgentsBridge = {
                    postMessage: function(msg) {
                        $injection
                    }
                };
                window.__onHostMessage = function(json) {
                    try {
                        var data = JSON.parse(json);
                        window.dispatchEvent(new MessageEvent('message', { data: data }));
                    } catch(e) {
                        console.error('[PixelAgents] Failed to parse host message:', e);
                    }
                };
                // Signal that bridge is ready
                window.__pixelAgentsBridge.postMessage(JSON.stringify({ type: 'webviewReady' }));
            })();
        """.trimIndent()
        cefBrowser.executeJavaScript(script, cefBrowser.url, 0)
    }

    /**
     * Send a message from Kotlin host to JS webview.
     * If webview is not ready yet, the message is queued.
     */
    fun postMessage(data: Map<String, Any?>) {
        val json = JsonUtils.toJson(data)
        postMessageRaw(json)
    }

    fun postMessage(type: String, vararg pairs: Pair<String, Any?>) {
        val map = mutableMapOf<String, Any?>("type" to type)
        pairs.forEach { map[it.first] = it.second }
        postMessage(map)
    }

    private fun postMessageRaw(json: String) {
        if (ready.get()) {
            executeOnBrowser(json)
        } else {
            messageQueue.add(json)
        }
    }

    private fun flushQueue() {
        while (true) {
            val msg = messageQueue.poll() ?: break
            executeOnBrowser(msg)
        }
    }

    private fun executeOnBrowser(json: String) {
        // Escape for JS string literal
        val escaped = json
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        browser.cefBrowser.executeJavaScript(
            "window.__onHostMessage('$escaped');",
            browser.cefBrowser.url,
            0
        )
    }

    fun dispose() {
        jsQuery.dispose()
    }
}
