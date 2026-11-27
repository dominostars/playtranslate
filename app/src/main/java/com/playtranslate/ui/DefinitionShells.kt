package com.playtranslate.ui

import android.annotation.SuppressLint
import androidx.annotation.VisibleForTesting
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlin.math.roundToInt

/**
 * One loaded definitions page: a WebView holding the document shell
 * ([DefinitionsDocument.documentShellHtml]) and the plumbing between the
 * page and whoever owns it ([listener]). Not a View of the host's: a
 * [DefinitionDocumentView] shows it, and a shell that no surface has taken
 * yet is the spare [DefinitionShells] keeps.
 *
 * It is built on a [MutableContextWrapper] over the application context and
 * moved onto its host's context by [adopt], so a shell loaded before anyone
 * needs it serves whichever surface asks first. Nothing is read from the
 * construction context afterwards: the text zoom is set in [adopt], and the
 * owner converts the page's CSS px with its own density.
 *
 * Main thread only, except the bridge, which the page calls on its own
 * thread and which posts to the main one.
 */
internal class DefinitionShell private constructor(
    private val base: MutableContextWrapper,
    val webView: WebView,
) {
    interface Listener {
        /** The page laid out row set [gen]: its height in CSS px, its
         *  targets, the word heads that pin, the rows it replaced since its
         *  last report and the word rows' labels (see
         *  [DefinitionsDocument.documentShellHtml]). */
        fun onLayout(gen: Int, heightCssPx: Int, targetsJson: String, headsJson: String, redrawnJson: String, labelsJson: String)

        /** A tap on [act] in the row whose DOM id is [domId]. */
        fun onAction(domId: String, act: String)

        /** A tap on the text of a row of row set [gen] that reports them
         *  (`data-words`): [text] is the run it fell in and [offset] the
         *  tapped character's place in it, [lang] the language the markup
         *  says that text is in and [dictId] the dictionary it is from,
         *  each "" where the markup says nothing. [tapId] names the run to
         *  `ptMark`. Default: a host with no such rows hears of none. */
        fun onText(gen: Int, domId: String, tapId: Int, lang: String, dictId: String, text: String, offset: Int) {}

        /** The page's reply to `ptMark` for [tapId]: the box of the marked
         *  part's first line, in CSS px from the root's corner; a box of no
         *  [width] when that run's text is no longer in the page, which is
         *  also how a painted mark is reported gone. */
        fun onMark(tapId: Int, left: Int, top: Int, width: Int, height: Int) {}

        /** The render process died; this shell is destroyed. Every shell of
         *  the app dies with it, since they share that process. */
        fun onRendererGone()
    }

    var listener: Listener? = null

    /** Routes the page's media requests ([DefinitionMedia.respond]). */
    @Volatile
    var mediaLanguage: String = ""

    private var pageReady = false
    private val pendingJs = ArrayList<String>()

    /** True once the WebView is gone: destroyed, or its renderer died. */
    var isDead = false
        private set

    fun isPageReady(): Boolean = pageReady && !isDead

    /** Moves the shell onto [host]'s context: its resources, its display,
     *  its font scale (as TextViews respect it). */
    fun adopt(host: Context) {
        base.baseContext = host
        webView.settings.textZoom = (host.resources.configuration.fontScale * 100).roundToInt()
    }

    /** Runs [js] in the page, or as soon as the shell has loaded. */
    fun exec(js: String) {
        if (isDead) return
        if (pageReady) webView.evaluateJavascript(js, null) else pendingJs += js
    }

    fun destroy() {
        if (isDead) return
        isDead = true
        listener = null
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onLayout(gen: Int, heightCssPx: Int, targetsJson: String, headsJson: String, redrawnJson: String, labelsJson: String) {
            MAIN.post { listener?.onLayout(gen, heightCssPx, targetsJson, headsJson, redrawnJson, labelsJson) }
        }

        @JavascriptInterface
        fun onAction(domId: String, act: String) {
            MAIN.post { listener?.onAction(domId, act) }
        }

        @JavascriptInterface
        fun onText(gen: Int, domId: String, tapId: Int, lang: String, dictId: String, text: String, offset: Int) {
            MAIN.post { listener?.onText(gen, domId, tapId, lang, dictId, text, offset) }
        }

        @JavascriptInterface
        fun onMark(tapId: Int, left: Int, top: Int, width: Int, height: Int) {
            MAIN.post { listener?.onMark(tapId, left, top, width, height) }
        }
    }

    private inner class Client : WebViewClient() {

        override fun onPageFinished(view: WebView?, url: String?) {
            pageReady = true
            pendingJs.forEach { view?.evaluateJavascript(it, null) }
            pendingJs.clear()
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            // The page never navigates. The only navigation source is a tap
            // on an external link in a dictionary's content: that goes to
            // the system browser, anything else dies here.
            val url = request?.url
            if (url != null && DefinitionMedia.isExternalLink(url)) {
                DefinitionMedia.openInBrowser(base, url)
            }
            return true
        }

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?,
        ): WebResourceResponse? =
            DefinitionMedia.respond(base.applicationContext, mediaLanguage, request)

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            // Handled: a dead page must never take the app process with it
            // (the default). The owner decides what the surface shows now.
            Log.w(TAG, "render process gone (crash=${detail?.didCrash()})")
            val owner = listener
            destroy()
            // Before the owner is told: it will ask for a shell to recover
            // on, and must not be handed one that died in the same breath.
            DefinitionShells.onRendererGone()
            owner?.onRendererGone()
            return true
        }
    }

    companion object {
        private const val TAG = "DefinitionShell"
        private val MAIN = Handler(Looper.getMainLooper())

        /**
         * A new shell, its page loading. Null when the device has no usable
         * WebView (none installed, or its package is mid-update): the
         * constructor throws then, and the caller shows the notice.
         *
         * The first WebView of a process loads the provider and blocks the
         * calling thread for it (about 0.1 s on a fast handheld, 0.5 to
         * 0.9 s on a budget phone), which is why nothing calls this before a
         * definition has been asked for.
         */
        fun create(app: Context): DefinitionShell? = try {
            val base = MutableContextWrapper(app.applicationContext)
            val wv = WebView(base)
            val shell = DefinitionShell(base, wv)
            wv.setBackgroundColor(Color.TRANSPARENT)
            wv.isVerticalScrollBarEnabled = false
            wv.isHorizontalScrollBarEnabled = false
            // Never a focus stop of its own: its DefinitionDocumentView owns
            // focus and the D-pad, and a focused WebView would take the keys.
            wv.isFocusable = false
            wv.isFocusableInTouchMode = false
            wv.isLongClickable = false
            shell.configure(wv)
            wv.loadDataWithBaseURL(
                YomitanContentHtml.MEDIA_ORIGIN + "/",
                DefinitionsDocument.documentShellHtml(),
                "text/html",
                "utf-8",
                null,
            )
            shell
        } catch (e: Exception) {
            Log.w(TAG, "WebView unavailable", e)
            null
        }
    }

    // The page's own script (set, append, replace, the layout report) is the
    // whole reason the page exists; it loads nothing from outside.
    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(wv: WebView) {
        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
            displayZoomControls = false
        }
        wv.webViewClient = Client()
        wv.addJavascriptInterface(Bridge(), "PTBridge")
    }
}

/**
 * The app's definition shells: where a surface gets its [DefinitionShell],
 * and the one spare kept loaded while definitions are in use.
 *
 * Nothing here runs until a surface is about to draw a definition. A lot of
 * people use the app only to translate, and a WebView costs them memory and
 * a stall at its first load, so the first [retain] of a process is the first
 * moment the provider loads. From then on, while at least one surface holds
 * definitions, one extra shell is kept loaded and unattached, so the next
 * surface to open (a lens over the results, a word's page over a list) does
 * not wait for a page load; when the last surface lets go the spare is
 * destroyed, and with it the last claim on the render process.
 *
 * Main thread only.
 */
internal object DefinitionShells {

    private var spare: DefinitionShell? = null
    private var holders = 0

    /** A surface starts holding definitions (it has been asked for one):
     *  makes sure a shell is loading for it. Paired with [release]. */
    fun retain(ctx: Context) {
        holders++
        ensureSpare(ctx)
    }

    /** A surface lets go. The last one out takes the spare with it. */
    fun release() {
        if (holders > 0) holders--
        if (holders == 0) {
            spare?.destroy()
            spare = null
        }
    }

    /**
     * The render process died. Every shell shared it, so the spare is dead
     * too, whether or not its own callback has come yet (the platform tells
     * each WebView in turn, in no promised order). It goes now: a surface
     * recovering from this death must get a new shell, or the spare's late
     * callback would count as that surface's second death.
     */
    fun onRendererGone() {
        spare?.destroy()
        spare = null
    }

    /** The spare, for a test that plays a renderer death. */
    @get:VisibleForTesting
    val spareForTest: DefinitionShell? get() = spare

    /**
     * A shell for [ctx]'s surface: the spare when it is alive (never a dead
     * one: [onRendererGone] drops it with the process), a new
     * one otherwise. Null when the device has no usable WebView. A
     * replacement spare is built once the main thread is idle, so it never
     * delays the content the caller is about to bind.
     */
    fun obtain(ctx: Context): DefinitionShell? {
        val shell = spare?.takeUnless { it.isDead } ?: DefinitionShell.create(ctx)
        spare = null
        shell?.adopt(ctx)
        if (shell != null) {
            val app = ctx.applicationContext
            Looper.myQueue().addIdleHandler {
                if (holders > 0) ensureSpare(app)
                false
            }
        }
        return shell
    }

    private fun ensureSpare(ctx: Context) {
        if (spare?.isDead == false) return
        spare = DefinitionShell.create(ctx)
    }

    /** Back to the state of a new process: no spare, no holders. Called from
     *  [com.playtranslate.PlayTranslateApplication.onCreate], where it is a
     *  no-op in production and what keeps one Robolectric test's shells out
     *  of the next. */
    fun reset() {
        spare?.destroy()
        spare = null
        holders = 0
    }
}
