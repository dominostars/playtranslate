package com.playtranslate.ui

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.widget.FrameLayout
import com.playtranslate.OcrManager
import com.playtranslate.OneShotOverlayData
import com.playtranslate.OverlayMode
import com.playtranslate.OverlayToolkit
import com.playtranslate.Prefs
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageEngines
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one thing that paints a capture's presentation over a game display:
 * translation chips, the reading hint (furigana or pinyin), or nothing, by
 * [mode], from the capture's own data ([OneShotOverlayData]: the translation
 * boxes and the OCR groups they came from), so a change of mode repaints in
 * place with no second read. Hosted inside the capture sheet's window on one
 * screen, and as a window of its own on the game display of two, where the
 * app's results page is the panel. Draws through one [TranslationOverlayView],
 * the view the live tiers draw with, rebuilt when the render settings change.
 *
 * Translation chips paint synchronously ([hasBoxes] is true at once when the
 * data has chips, as skeletons or with text); readings are built off the
 * main thread ([pending] until they land, then [onBoxesSettled] says whether
 * anything was drawn). [expectsBoxes] says before any paint whether [mode]
 * has something to build from, which is what a parked sheet stands on.
 * Definitions arrives with its word-lookup session; until then it paints
 * nothing and is not in [supportedModes]. Main thread only.
 */
internal class CaptureOverlayLayer(
    ctx: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
) : FrameLayout(ctx) {

    private val chipsCtx = android.view.ContextThemeWrapper(ctx, android.R.style.Theme_DeviceDefault)

    /** The reading boxes for the groups of a capture read in [SourceLangId],
     *  in the groups' coordinates. Annotates through the language's engine,
     *  as the live furigana tier does; tests swap it. */
    internal var readingBoxes: suspend (List<OcrManager.OcrGroup>, SourceLangId) -> List<TextBox> =
        { groups, src ->
            val engine = SourceLanguageEngines.get(ctx, src)
            val paint = FuriganaMetrics.measuringPaint(ctx.resources.displayMetrics.density)
            OverlayToolkit.buildFuriganaBoxesByGroup(groups, engine, paint).flatMap { it.boxes }
        }

    var mode: OverlayMode = OverlayMode.NONE
        private set
    var data: OneShotOverlayData? = null
        private set
    private var sourceLangId: SourceLangId? = null

    /** Something is drawn right now. */
    var hasBoxes: Boolean = false
        private set

    /** A reading build is in flight; [hasBoxes] settles when it lands. */
    var pending: Boolean = false
        private set

    /** Every layout of the drawn boxes (a rebuild, a fill): the host
     *  re-places anything it keeps clear of them. */
    var onBoxesLaidOut: (() -> Unit)? = null

    /** An asynchronous build landed, with whether it drew anything. */
    var onBoxesSettled: ((hasBoxes: Boolean) -> Unit)? = null

    private var chipsView: TranslationOverlayView? = null
    private var buildJob: Job? = null

    /** The modes this layer can paint today. */
    val supportedModes: Set<OverlayMode> =
        setOf(OverlayMode.TRANSLATION, OverlayMode.FURIGANA, OverlayMode.NONE)

    /** Whether [mode] has something to build from in [data]: chips for
     *  Translation, groups for the reading hint, never for None. True before
     *  an asynchronous paint lands, so a host can park on it. */
    val expectsBoxes: Boolean
        get() = when (mode) {
            OverlayMode.TRANSLATION -> data?.hasChips == true
            OverlayMode.FURIGANA -> data?.groups?.isNotEmpty() == true && sourceLangId != null
            OverlayMode.DEFINITIONS, OverlayMode.NONE -> false
        }

    /** Take [data] (read in [sourceLangId]) and paint it in [mode]. */
    fun bind(data: OneShotOverlayData?, sourceLangId: SourceLangId?, mode: OverlayMode) {
        this.data = data
        this.sourceLangId = sourceLangId
        this.mode = mode
        repaint()
    }

    /** More of the same capture translated: the chips fill in place. The
     *  reading hint is built from the groups, which a fill never changes. */
    fun update(data: OneShotOverlayData) {
        this.data = data
        if (mode == OverlayMode.TRANSLATION) repaint()
    }

    /** Repaint the bound data in [mode]. */
    fun setMode(mode: OverlayMode) {
        if (this.mode == mode) return
        this.mode = mode
        repaint()
    }

    /** Nothing to paint any more (a status replaced the result, an edit
     *  orphaned the boxes): the boxes fade and the data goes. */
    fun clear() {
        data = null
        cancelBuild()
        hideView(animated = true)
    }

    /** The host's window is going. */
    fun release() {
        cancelBuild()
        chipsView = null
        removeAllViews()
    }

    /** The drawn footprints of the boxes, in screen coordinates; empty
     *  while nothing is drawn. */
    fun drawnBoxRects(): List<Rect> =
        if (hasBoxes) chipsView?.getChildScreenRects().orEmpty() else emptyList()

    private fun repaint() {
        cancelBuild()
        val d = data
        val src = sourceLangId
        when (mode) {
            OverlayMode.TRANSLATION ->
                if (d != null && d.hasChips) show(d.boxes, d, d.growthLimits) else hideView(animated = false)
            OverlayMode.FURIGANA ->
                if (d != null && d.groups.isNotEmpty() && src != null) buildReadings(d, src)
                else hideView(animated = false)
            // Definitions paints through its word-lookup session, which is
            // not hosted here yet; None paints nothing by definition.
            OverlayMode.DEFINITIONS, OverlayMode.NONE -> hideView(animated = false)
        }
    }

    /** A replaced build is cancelled ([cancelBuild]); cancellation is prompt,
     *  so a build that lands after its replacement throws on the way back
     *  and never paints. */
    private fun buildReadings(d: OneShotOverlayData, src: SourceLangId) {
        pending = true
        buildJob = scope.launch {
            val boxes = try {
                withContext(Dispatchers.Default) { readingBoxes(d.groups, src) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "reading boxes failed: ${e.javaClass.simpleName}")
                emptyList()
            }
            pending = false
            // Readings are placed by the annotation; the minimum-text-size
            // growth skips them, so no limits apply.
            if (boxes.isNotEmpty()) show(boxes, d, GrowthLimits.NONE) else hideView(animated = false)
            onBoxesSettled?.invoke(hasBoxes)
        }
    }

    private fun cancelBuild() {
        buildJob?.cancel()
        buildJob = null
        pending = false
    }

    private fun show(boxes: List<TextBox>, d: OneShotOverlayData, limits: GrowthLimits) {
        val v = viewFor(OverlayRenderConfig.from(prefs))
        v.animate().cancel()
        v.alpha = 1f
        v.setBoxes(boxes, d.cropLeft, d.cropTop, d.screenshotW, d.screenshotH, growthLimits = limits)
        hasBoxes = true
    }

    /** The boxes fade (or vanish) but the view stays attached for a cheap
     *  re-show; it dies with the host. */
    private fun hideView(animated: Boolean) {
        hasBoxes = false
        val v = chipsView ?: return
        v.animate().cancel()
        if (animated) v.animate().alpha(0f).setDuration(FADE_MS).start() else v.alpha = 0f
    }

    /** The view for [config]. Built once, but its render config is fixed at
     *  construction: a setting changed since (the settings screen can sit
     *  beside the sheet on another display) replaces it, as the live
     *  overlay's reuse guard does. */
    private fun viewFor(config: OverlayRenderConfig): TranslationOverlayView {
        chipsView?.takeIf { it.renderConfig != config }?.let { stale ->
            removeView(stale)
            chipsView = null
        }
        return chipsView ?: TranslationOverlayView(chipsCtx, oneShot = true, renderConfig = config).also {
            chipsView = it
            it.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> onBoxesLaidOut?.invoke() }
            addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
    }

    companion object {
        private const val TAG = "CaptureOverlayLayer"
        const val FADE_MS = 150L
    }
}
