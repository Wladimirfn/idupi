package com.idupi.app.ui.components

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Kotlin -> WebView command surface for the vendored xterm.js page.
 *
 * Every call is posted onto the WebView's own thread, so callers may invoke it
 * from compose effects, bridge callbacks, or coroutines without thinking about
 * threading. The bearer token is deliberately absent from this API: auth stays
 * inside the Ktor client, and JavaScript only ever receives terminal bytes.
 */
class PtyTerminalWebController {

    internal var webView: WebView? = null

    /** Feeds one base64 chunk of raw VT bytes into xterm. */
    fun writeBase64(base64: String) = evaluate("window.idupi.write('$base64')")

    fun setFontSize(px: Int) = evaluate("window.idupi.setFontSize($px)")

    fun setModifiers(ctrl: Boolean, alt: Boolean) =
        evaluate("window.idupi.setModifiers($ctrl, $alt)")

    private fun evaluate(js: String) {
        val view = webView ?: return
        view.post {
            if (view === webView) view.evaluateJavascript(js, null)
        }
    }
}

/**
 * `@JavascriptInterface` methods are invoked on the WebView's private bridge
 * thread, which is why [PtyTerminalView] marshals each callback to the main
 * thread before it reaches Compose state or the ViewModel.
 */
class PtyTerminalBridge(
    private val readyHandler: (cols: Int, rows: Int) -> Unit,
    private val inputHandler: (data: String) -> Unit,
    private val resizeHandler: (cols: Int, rows: Int) -> Unit,
    private val modifiersConsumedHandler: () -> Unit
) {
    @JavascriptInterface
    fun onReady(cols: Int, rows: Int) = readyHandler(cols, rows)

    @JavascriptInterface
    fun onInput(data: String) = inputHandler(data)

    @JavascriptInterface
    fun onResize(cols: Int, rows: Int) = resizeHandler(cols, rows)

    @JavascriptInterface
    fun onModifiersConsumed() = modifiersConsumedHandler()
}

/**
 * Hosts the offline xterm.js page inside a WebView.
 *
 * Hardening: JavaScript is required (xterm), but DOM storage, file-system and
 * content access are off. `file:///android_asset` still resolves with file
 * access disabled -- the WebView's own asset loader serves it -- and the page
 * never navigates anywhere else. The token never reaches the page; bytes flow
 * Kotlin -> [PtyTerminalWebController.writeBase64] and keystrokes come back
 * through [PtyTerminalBridge].
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PtyTerminalView(
    controller: PtyTerminalWebController,
    onReady: (cols: Int, rows: Int) -> Unit,
    onInput: (data: String) -> Unit,
    onResize: (cols: Int, rows: Int) -> Unit,
    onModifiersConsumed: () -> Unit,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            // The bridge callback thread is NOT the UI thread; everything the
            // UI owns (Compose state, ViewModel fields read on Main) is only
            // touched through this handler.
            val mainHandler = Handler(Looper.getMainLooper())
            val bridge = PtyTerminalBridge(
                readyHandler = { cols, rows -> mainHandler.post { onReady(cols, rows) } },
                inputHandler = { data -> mainHandler.post { onInput(data) } },
                resizeHandler = { cols, rows -> mainHandler.post { onResize(cols, rows) } },
                modifiersConsumedHandler = { mainHandler.post { onModifiersConsumed() } }
            )
            WebView(context).apply {
                setBackgroundColor(android.graphics.Color.parseColor("#020617"))
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                addJavascriptInterface(bridge, BRIDGE_NAME)
                // Only the packaged asset is ever a valid destination; anything
                // else (a stray link in terminal output) must not navigate.
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean = request.url.scheme != "file"
                }
                loadUrl("file:///android_asset/terminal/index.html")
                controller.webView = this
            }
        },
        onRelease = { view ->
            controller.webView = null
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        }
    )
}

private const val BRIDGE_NAME = "AndroidBridge"
