package com.pixelagents.jcef

import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefSchemeHandlerFactory
import org.cef.handler.CefResourceHandler
import org.cef.callback.CefCallback
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.io.InputStream
import java.net.URI

class PixelAgentsSchemeHandlerFactory : CefSchemeHandlerFactory {
    override fun create(
        browser: CefBrowser?,
        frame: CefFrame?,
        schemeName: String?,
        request: CefRequest?
    ): CefResourceHandler? {
        return request?.let { PixelAgentsResourceHandler(it) }
    }
}

private class PixelAgentsResourceHandler(private val request: CefRequest) : CefResourceHandler {
    private var inputStream: InputStream? = null
    private var mimeType: String = "text/html"
    private var responseLength: Int = 0
    private var offset: Int = 0
    private var data: ByteArray? = null

    override fun processRequest(request: CefRequest, callback: CefCallback): Boolean {
        val url = request.url ?: return false
        val uri = URI(url)

        // Map URL path to classpath resource under /webview-dist/
        var path = uri.path
        if (path.isNullOrEmpty() || path == "/") {
            path = "/index.html"
        }

        val resourcePath = "/webview-dist$path"
        mimeType = getMimeType(path)

        val stream = javaClass.getResourceAsStream(resourcePath)
        if (stream != null) {
            data = stream.readBytes()
            stream.close()
            responseLength = data!!.size
            offset = 0
            callback.Continue()
            return true
        }

        return false
    }

    override fun getResponseHeaders(response: CefResponse, responseLength: IntRef, redirectUrl: StringRef?) {
        response.mimeType = mimeType
        response.status = 200
        responseLength.set(this.responseLength)
    }

    override fun readResponse(dataOut: ByteArray, bytesToRead: Int, bytesRead: IntRef, callback: CefCallback): Boolean {
        val bytes = data ?: return false
        if (offset >= bytes.size) return false

        val available = minOf(bytesToRead, bytes.size - offset)
        System.arraycopy(bytes, offset, dataOut, 0, available)
        offset += available
        bytesRead.set(available)
        return true
    }

    override fun cancel() {
        inputStream?.close()
        inputStream = null
        data = null
    }

    private fun getMimeType(path: String): String {
        return when {
            path.endsWith(".html") -> "text/html"
            path.endsWith(".js") -> "application/javascript"
            path.endsWith(".css") -> "text/css"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            path.endsWith(".svg") -> "image/svg+xml"
            path.endsWith(".json") -> "application/json"
            path.endsWith(".woff") -> "font/woff"
            path.endsWith(".woff2") -> "font/woff2"
            path.endsWith(".ttf") -> "font/ttf"
            path.endsWith(".otf") -> "font/otf"
            path.endsWith(".ico") -> "image/x-icon"
            path.endsWith(".webp") -> "image/webp"
            path.endsWith(".mp3") -> "audio/mpeg"
            path.endsWith(".wav") -> "audio/wav"
            else -> "application/octet-stream"
        }
    }
}
