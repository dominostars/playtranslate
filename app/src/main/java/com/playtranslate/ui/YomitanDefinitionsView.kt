package com.playtranslate.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.playtranslate.yomitan.YomitanDataStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt

/**
 * The styled-definitions surface: one WebView hosting the persistent
 * [DefinitionsDocument] shell, content swapped per lookup via the page's
 * `ptSwap`. This is the app's first (and only) WebView — its network story
 * is therefore explicit: [shouldInterceptRequest] serves dictionary media
 * from [YomitanDataStore] for the one allowed origin and answers EVERYTHING
 * else with an empty response, so no dictionary CSS `url()`, font, or
 * beacon can reach the network (the shell's CSP is the second fence;
 * OkHttp's HTTPS-only enforcement never sees a WebView, so the guarantee
 * has to live here). Navigations are swallowed wholesale.
 *
 * Failure is always downgrade, never absence: construction failure (no
 * WebView provider), a dead render process ([onRenderProcessGone] — the
 * view marks itself broken and tells the host), or a stale engine just
 * mean the host keeps/returns to its flat-text renderer.
 *
 * Height flows OUT asynchronously: the page reports
 * `documentElement.scrollHeight` after each swap (two frames, so images
 * with declared aspect-ratios have taken space) through [Bridge], and the
 * host sizes this view — the lens keeps its outer ScrollView as the
 * scroller (its stick-scroll repeater and card-height fitting stay
 * intact), so this view must always be given its FULL content height and
 * never scroll itself.
 */
internal class YomitanDefinitionsView(
    context: Context,
    tokens: DefinitionsDocument.Tokens,
) : FrameLayout(context) {

    /** Content height in VIEW pixels, delivered on the main thread after
     *  each swap and on resize. */
    var onContentHeight: ((Int) -> Unit)? = null

    /** The render process died — this instance is dead ([isUsable] false);
     *  the host should fall back to its flat renderer. */
    var onRendererGone: (() -> Unit)? = null

    private var webView: WebView? = null
    private var pageReady = false
    private val pendingJs = mutableListOf<String>()
    private var mediaLanguage: String = "ja"

    /** Render generation, bumped by every [setContent] and echoed back by
     *  the page with each height report. Written and compared on the main
     *  thread only. */
    private var renderSeq = 0

    init {
        @SuppressLint("SetJavaScriptEnabled")
        try {
            val wv = WebView(context)
            wv.setBackgroundColor(Color.TRANSPARENT)
            wv.isVerticalScrollBarEnabled = false
            wv.isHorizontalScrollBarEnabled = false
            with(wv.settings) {
                javaScriptEnabled = true // the shell's swap/scoping script
                domStorageEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                setSupportZoom(false)
                displayZoomControls = false
                // Respect the system font scale the way TextViews do.
                textZoom = (context.resources.configuration.fontScale * 100).roundToInt()
            }
            wv.webViewClient = Client()
            wv.addJavascriptInterface(Bridge(), "PTBridge")
            wv.loadDataWithBaseURL(
                YomitanContentHtml.MEDIA_ORIGIN + "/",
                DefinitionsDocument.shellHtml(tokens),
                "text/html",
                "utf-8",
                null,
            )
            // MATCH_PARENT both ways: a WebView's own wrap-content measure is
            // unreliable pre-render — the HOST sizes this wrapper from the
            // page's height report, and the WebView fills it.
            addView(wv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            webView = wv
        } catch (e: Exception) {
            // No WebView provider / provider update in flight — stay a
            // plain empty FrameLayout; the host checks isUsable.
            Log.w(TAG, "WebView unavailable", e)
            webView = null
        }
    }

    fun isUsable(): Boolean = webView != null

    /**
     * Swaps in a lookup's content. [contentHtml] comes from
     * [DefinitionsDocument.contentHtml]; [dictCss] maps dict id → raw
     * styles.css for every dictionary appearing in the content (the page
     * applies each once, scoped); [sourceLanguage] routes media requests.
     */
    fun setContent(contentHtml: String, dictCss: Map<String, String>, sourceLanguage: String) {
        mediaLanguage = sourceLanguage
        for ((dictId, css) in dictCss) {
            exec("ptApplyDictCss(${quote(dictId)},${quote(css)})")
        }
        // The generation rides into ptSwap and back with every height
        // report, so a report from a superseded swap can never surface as
        // this one's — see [acceptsHeightReport].
        renderSeq++
        exec("ptSwap(${quote(contentHtml)},$renderSeq)")
    }

    /** Whether a page height report stamped [gen] belongs to the LATEST
     *  [setContent]. Stale reports (an earlier swap's two-frame callback
     *  arriving after a rapid rebind, or a resize report racing an
     *  in-flight swap) must be dropped: a host that treats any report as
     *  "the current bind painted" would reveal the PREVIOUS lookup's
     *  content under the new word. Extracted for the regression test. */
    internal fun acceptsHeightReport(gen: Int): Boolean = gen == renderSeq

    fun destroy() {
        (webView?.parent as? ViewGroup)?.removeView(webView)
        webView?.destroy()
        webView = null
    }

    private fun exec(js: String) {
        val wv = webView ?: return
        if (pageReady) wv.evaluateJavascript(js, null) else pendingJs += js
    }

    /** JSON string literal, additionally escaping the two codepoints legal
     *  in JSON but not in JS source (U+2028/U+2029 — real risk: they occur
     *  in CJK-adjacent dictionary text). */
    private fun quote(s: String): String =
        JSONObject.quote(s).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

    private inner class Bridge {
        @JavascriptInterface
        fun onHeight(cssPx: Int, gen: Int) {
            // CSS px → view px: initial-scale=1 makes 1 CSS px = 1 dp.
            val px = (cssPx * resources.displayMetrics.density).roundToInt()
            // The generation check runs in the post{} — on the main thread,
            // where renderSeq is written — not here on the JS bridge thread.
            post {
                if (!acceptsHeightReport(gen)) return@post
                onContentHeight?.invoke(px)
            }
        }
    }

    private inner class Client : WebViewClient() {

        override fun onPageFinished(view: WebView?, url: String?) {
            pageReady = true
            pendingJs.forEach { view?.evaluateJavascript(it, null) }
            pendingJs.clear()
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
            true // no navigation, ever

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?,
        ): WebResourceResponse? {
            val url = request?.url ?: return blocked()
            // The one allowed shape: https://pt-media.internal/media/<dictId>/<path>
            if ("${url.scheme}://${url.host}" == YomitanContentHtml.MEDIA_ORIGIN) {
                val segments = url.pathSegments
                if (segments.size >= 3 && segments[0] == "media") {
                    val dictId = segments[1]
                    val path = segments.drop(2).joinToString("/")
                    val blob = runBlocking {
                        YomitanDataStore.mediaBlob(context, mediaLanguage, dictId, path)
                    }
                    if (blob != null) {
                        return WebResourceResponse(
                            mimeForPath(path), null, ByteArrayInputStream(blob),
                        )
                    }
                }
            }
            return blocked()
        }

        override fun onRenderProcessGone(
            view: WebView?,
            detail: RenderProcessGoneDetail?,
        ): Boolean {
            // Handled: never let a dead dictionary panel kill the app
            // process (the default). The instance is unusable from here.
            Log.w(TAG, "render process gone (crash=${detail?.didCrash()})")
            destroy()
            onRendererGone?.invoke()
            return true
        }
    }

    companion object {
        private const val TAG = "YomitanDefsView"

        @Volatile
        private var warmed = false

        /** Loads the WebView provider ahead of the first real panel — the
         *  first WebView in a process pays the provider init (~100-250ms);
         *  every later one is cheap. Call from the main thread at
         *  capture-session start, gated on styling actually being live
         *  ([YomitanDataStore.stylingFor]). */
        fun warmUp(context: Context) {
            if (warmed) return
            warmed = true
            try {
                WebView(context.applicationContext).destroy()
            } catch (e: Exception) {
                Log.w(TAG, "warmUp failed (WebView unavailable)", e)
            }
        }

        private fun mimeForPath(path: String): String =
            when (path.substringAfterLast('.', "").lowercase()) {
                "avif" -> "image/avif"
                "apng" -> "image/apng"
                "bmp" -> "image/bmp"
                "gif" -> "image/gif"
                "ico", "cur" -> "image/x-icon"
                "jpg", "jpeg", "jfif", "pjpeg", "pjp" -> "image/jpeg"
                "png" -> "image/png"
                "svg" -> "image/svg+xml"
                "tif", "tiff" -> "image/tiff"
                "webp" -> "image/webp"
                else -> "application/octet-stream"
            }

        private fun blocked(): WebResourceResponse =
            WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
    }
}
