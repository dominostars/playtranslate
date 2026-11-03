package com.playtranslate.ui

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.view.doOnNextLayout
import com.playtranslate.OcrManager
import com.playtranslate.OneShotOverlayData
import com.playtranslate.OverlayMode
import com.playtranslate.OverlayToolkit
import com.playtranslate.Prefs
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.language.TokenSpan
import com.playtranslate.overlay.OverlayHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one thing that paints a capture's presentation over a game display:
 * translation chips, the reading hint (furigana or pinyin), the word boxes
 * of Definitions, or nothing, by [mode], from the capture's own data
 * ([OneShotOverlayData]: the translation boxes and the OCR groups they came
 * from), so a change of mode repaints in place with no second read. Hosted
 * inside the capture sheet's window on one screen, and as a window of its
 * own on the game display of two, where the app's results page is the
 * panel. Chips and readings draw through one [TranslationOverlayView], the
 * view the live tiers draw with, rebuilt when the render settings change;
 * Definitions draws through [WordBoxesView], driven by [WordLookupSession],
 * which needs a [LookupHost] for its card's windows (a host without one
 * does not offer Definitions). Every child reads the capture's frame as
 * filling this layer: the chips view maps it onto its own size, and the
 * words are boxed in this layer's coordinates ([screenLines]), which are
 * the display's, both hosts filling the display from its origin.
 *
 * Translation chips paint synchronously ([hasBoxes] is true at once when the
 * data has chips, as skeletons or with text); readings and word boxes are
 * built off the main thread ([pending] until they land, then
 * [onBoxesSettled] says whether anything was drawn). [expectsBoxes] says
 * before any paint whether [mode] has something to build from, which is
 * what a parked sheet stands on. Main thread only.
 */
internal class CaptureOverlayLayer(
    ctx: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val lookupHost: LookupHost? = null,
) : FrameLayout(ctx) {

    /** What the Definitions card needs to put its windows up on the game
     *  display: the display's context and window manager, the overlay host
     *  and the display. */
    data class LookupHost(
        val displayCtx: Context,
        val wm: WindowManager,
        val overlayHost: OverlayHost,
        val displayId: Int,
    )

    private val chipsCtx = android.view.ContextThemeWrapper(ctx, android.R.style.Theme_DeviceDefault)
    private val density = ctx.resources.displayMetrics.density

    /** The reading boxes for the groups of a capture read in [SourceLangId],
     *  in the groups' coordinates. Annotates through the language's engine,
     *  as the live furigana tier does; tests swap it. */
    internal var readingBoxes: suspend (List<OcrManager.OcrGroup>, SourceLangId) -> List<TextBox> =
        { groups, src ->
            val engine = SourceLanguageEngines.get(ctx, src)
            val paint = FuriganaMetrics.measuringPaint(ctx.resources.displayMetrics.density)
            OverlayToolkit.buildFuriganaBoxesByGroup(groups, engine, paint).flatMap { it.boxes }
        }

    /** The tokens of a line read in [SourceLangId]: the drag lens's lookup
     *  tokens, through the language's engine; tests swap it. */
    internal var tokenizeLine: suspend (String, SourceLangId) -> List<TokenSpan> =
        { text, src -> SourceLanguageEngines.get(ctx, src).tokenize(text) }

    var mode: OverlayMode = OverlayMode.NONE
        private set
    var data: OneShotOverlayData? = null
        private set
    private var sourceLangId: SourceLangId? = null
    private var screenshotPath: String? = null
    private var capturedAtMs = 0L

    /** Something is drawn right now. */
    val hasBoxes: Boolean
        get() = if (mode == OverlayMode.DEFINITIONS) words?.hasBoxes == true else chipsPaint != null

    /** A build is in flight (readings, word boxes); [hasBoxes] settles when
     *  it lands. */
    val pending: Boolean
        get() = if (mode == OverlayMode.DEFINITIONS) words?.pending == true else readingBuild != null

    /** Every layout of the drawn boxes (a rebuild, a fill): the host
     *  re-places anything it keeps clear of them. */
    var onBoxesLaidOut: (() -> Unit)? = null

    /** An asynchronous build landed, with whether it drew anything. */
    var onBoxesSettled: ((hasBoxes: Boolean) -> Unit)? = null

    /** The Definitions card ended by its own means: the host closes as the
     *  user would ([WordLookupSession]'s class doc). */
    var onSessionEnded: (() -> Unit)? = null

    /** Where Definitions' dimming ([WordBoxesView.spotlight]) goes when the
     *  host keeps it apart from the boxes: the sheet keeps it beneath its
     *  panel, so a parked strip is never dimmed while the boxes sit over it.
     *  Null keeps it inside the boxes view, beneath the boxes. Set before
     *  the first paint. */
    var scrimHolder: android.view.ViewGroup? = null

    private var chipsView: TranslationOverlayView? = null
    /** The mode whose paint the chips view shows: Translations' chips or
     *  the reading hint's readings; null while it is hidden. */
    private var chipsPaint: OverlayMode? = null
    private var readingBuild: Job? = null

    private var wordsView: WordBoxesView? = null
    private var words: WordLookupSession? = null
    private var wordTouch = false

    /** The modes this layer can paint: [SUPPORTED_MODES], and Definitions
     *  with a [LookupHost]. */
    val supportedModes: Set<OverlayMode>
        get() = if (lookupHost != null) SUPPORTED_MODES + OverlayMode.DEFINITIONS else SUPPORTED_MODES

    /** Whether [mode] has something to build from in [data]: chips for
     *  Translation, groups for the reading hint and for Definitions, never
     *  for None. True before an asynchronous paint lands, so a host can park
     *  on it. */
    val expectsBoxes: Boolean
        get() = when (mode) {
            OverlayMode.TRANSLATION -> data?.hasChips == true
            OverlayMode.FURIGANA -> data?.groups?.isNotEmpty() == true && sourceLangId != null
            OverlayMode.DEFINITIONS ->
                lookupHost != null && data?.groups?.isNotEmpty() == true && sourceLangId != null
            OverlayMode.NONE -> false
        }

    /** Definitions holds key focus for Back and the controller cursor. */
    val wantsFocus: Boolean get() = mode == OverlayMode.DEFINITIONS && lookupHost != null

    /** A Definitions card is up. */
    val hasCard: Boolean get() = words?.hasCard == true

    /** Take [data] (read in [sourceLangId]; its frame saved at
     *  [screenshotPath] at [capturedAtMs], for a card's pages) and paint it
     *  in [mode]. */
    fun bind(
        data: OneShotOverlayData?,
        sourceLangId: SourceLangId?,
        mode: OverlayMode,
        screenshotPath: String? = this.screenshotPath,
        capturedAtMs: Long = this.capturedAtMs,
    ) {
        this.data = data
        this.sourceLangId = sourceLangId
        this.screenshotPath = screenshotPath
        this.capturedAtMs = capturedAtMs
        this.mode = mode
        repaint()
    }

    /** More of the same capture translated: the chips fill in place. The
     *  reading hint and the word boxes are built from the groups, which a
     *  fill never changes. */
    fun update(data: OneShotOverlayData) {
        this.data = data
        if (mode == OverlayMode.TRANSLATION) repaint()
    }

    /** The frame's saved path and time arrived after the boxes went up. */
    fun setFrame(screenshotPath: String?, capturedAtMs: Long) {
        this.screenshotPath = screenshotPath
        this.capturedAtMs = capturedAtMs
        words?.setFrame(screenshotPath, capturedAtMs)
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
        cancelReadingBuild()
        releaseWords()
        hideChipsView(animated = true)
    }

    /** The host's window is going. */
    fun release() {
        cancelReadingBuild()
        releaseWords()
        chipsView = null
        removeAllViews()
    }

    /** The drawn footprints of the boxes, in screen coordinates; empty
     *  while nothing is drawn. */
    fun drawnBoxRects(): List<Rect> = when {
        !hasBoxes -> emptyList()
        mode == OverlayMode.DEFINITIONS -> words?.drawnRects().orEmpty()
        else -> chipsView?.getChildScreenRects().orEmpty()
    }

    // ── Definitions: touches and keys ────────────────────────────────────

    /** True when a word of Definitions, or its tap buffer, lies under
     *  ([x], [y]) (the host's coordinates, the display's). */
    fun wordAt(x: Float, y: Float): Boolean = words?.wordAt(x, y) == true

    /** A touch the host offers (the whole DOWN..UP stream): claimed by
     *  Definitions when its DOWN lands on a word, or closes a card. Returns
     *  true while the sequence is this layer's. */
    fun onTouch(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            wordTouch = words?.onTapAt(ev.x, ev.y) == true
        }
        val mine = wordTouch
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            wordTouch = false
        }
        return mine
    }

    /** A key the host offers: Definitions' controller cursor. */
    fun onKey(ev: KeyEvent): Boolean = words?.onKey(ev) == true

    /** Back: a Definitions card closes (true); otherwise the host's. */
    fun onBack(): Boolean = words?.onBack() == true

    fun closeCard() {
        words?.closeCard()
    }

    // ── Painting ─────────────────────────────────────────────────────────

    private fun repaint() {
        cancelReadingBuild()
        val d = data
        val src = sourceLangId
        if (mode != OverlayMode.DEFINITIONS) releaseWords()
        when (mode) {
            OverlayMode.TRANSLATION ->
                if (d != null && d.hasChips) showChips(d.boxes, d, d.growthLimits, OverlayMode.TRANSLATION)
                else hideChipsView(animated = false)
            OverlayMode.FURIGANA ->
                if (d != null && d.groups.isNotEmpty() && src != null) {
                    // The readings build off the main thread. Chips of
                    // another paint (a switch from Translations) never show
                    // under the reading mode meanwhile; readings already up
                    // (the sheet binds the same capture again when its
                    // result lands) stay until the rebuild replaces them.
                    if (chipsPaint != OverlayMode.FURIGANA) hideChipsView(animated = false)
                    buildReadings(d, src)
                } else hideChipsView(animated = false)
            OverlayMode.DEFINITIONS -> {
                hideChipsView(animated = false)
                val host = lookupHost
                if (host != null && d != null && d.groups.isNotEmpty() && src != null) buildWords(host, d, src)
                else releaseWords()
            }
            OverlayMode.NONE -> hideChipsView(animated = false)
        }
    }

    /** A replaced build is cancelled ([cancelReadingBuild]); cancellation is
     *  prompt, so a build that lands after its replacement throws on the way
     *  back and never paints. */
    private fun buildReadings(d: OneShotOverlayData, src: SourceLangId) {
        readingBuild = scope.launch {
            val boxes = try {
                withContext(Dispatchers.Default) { readingBoxes(d.groups, src) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "reading boxes failed: ${e.javaClass.simpleName}")
                emptyList()
            }
            readingBuild = null
            // Readings are placed by the annotation; the minimum-text-size
            // growth skips them, so no limits apply.
            if (boxes.isNotEmpty()) showChips(boxes, d, GrowthLimits.NONE, OverlayMode.FURIGANA)
            else hideChipsView(animated = false)
            onBoxesSettled?.invoke(hasBoxes)
        }
    }

    private fun cancelReadingBuild() {
        readingBuild?.cancel()
        readingBuild = null
    }

    /** Show [boxes] in the chips view as [paint]'s ([chipsPaint]). */
    private fun showChips(boxes: List<TextBox>, d: OneShotOverlayData, limits: GrowthLimits, paint: OverlayMode) {
        val v = chipsViewFor(OverlayRenderConfig.from(prefs))
        v.animate().cancel()
        v.alpha = 1f
        v.setBoxes(boxes, d.cropLeft, d.cropTop, d.screenshotW, d.screenshotH, growthLimits = limits)
        chipsPaint = paint
    }

    /** The chips fade (or vanish) but the view stays attached for a cheap
     *  re-show; it dies with the host. */
    private fun hideChipsView(animated: Boolean) {
        chipsPaint = null
        val v = chipsView ?: return
        v.animate().cancel()
        if (animated) v.animate().alpha(0f).setDuration(FADE_MS).start() else v.alpha = 0f
    }

    /** The chips view for [config]. Built once, but its render config is
     *  fixed at construction: a setting changed since (the settings screen
     *  can sit beside the sheet on another display) replaces it, as the live
     *  overlay's reuse guard does. */
    private fun chipsViewFor(config: OverlayRenderConfig): TranslationOverlayView {
        chipsView?.takeIf { it.renderConfig != config }?.let { stale ->
            removeView(stale)
            chipsView = null
        }
        return chipsView ?: TranslationOverlayView(chipsCtx, oneShot = true, renderConfig = config).also {
            chipsView = it
            it.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> onBoxesLaidOut?.invoke() }
            addView(it, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
    }

    /** Box every word of the capture's lines, in this layer's coordinates
     *  ([screenLines]). A layer not laid out yet (the two-screen window
     *  binds before its window is added) boxes them at its first layout,
     *  the session pending until then. They are boxed once against that
     *  size: both hosts end the presentation when the display's geometry
     *  changes (the sheet's reconfigured-panel rule, the window's
     *  size check). A session already up (a re-bind of the same capture)
     *  loads the lines again and keeps its card where the word survives. */
    private fun buildWords(host: LookupHost, d: OneShotOverlayData, src: SourceLangId) {
        val view = wordsView ?: WordBoxesView(context).also {
            wordsView = it
            addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            scrimHolder?.let { holder ->
                it.removeView(it.spotlight)
                holder.addView(it.spotlight, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            }
        }
        val session = words ?: WordLookupSession(
            // The card acts in the app's context, as the drag lens's acts in
            // its service's: a page or Anki activity it starts is not tied
            // to the game display. Its windows are the display's.
            context = host.displayCtx.applicationContext,
            displayCtx = host.displayCtx,
            wm = host.wm,
            overlayHost = host.overlayHost,
            displayId = host.displayId,
            density = density,
            view = view,
            scope = scope,
            tokenize = { text -> tokenizeLine(text, src) },
            onEnded = { onSessionEnded?.invoke() },
            onBoxesSettled = { has -> onBoxesLaidOut?.invoke(); onBoxesSettled?.invoke(has) },
        ).also { words = it }
        val load = { if (words === session) session.load(screenLines(d), screenshotPath, capturedAtMs) }
        if (isLaidOut && width > 0 && height > 0) load() else doOnNextLayout { load() }
    }

    private fun releaseWords() {
        words?.release()
        words = null
        wordsView?.let { v ->
            // The dimming goes with the boxes, wherever the host keeps it.
            (v.spotlight.parent as? android.view.ViewGroup)?.takeIf { it !== v }?.removeView(v.spotlight)
            removeView(v)
        }
        wordsView = null
        wordTouch = false
    }

    /** The capture's lines as the lens reads them, in this layer's
     *  coordinates: the groups' lines, offset by the crop and scaled from
     *  the frame onto the layer's size, the mapping the chips view makes
     *  onto its own (never the display metrics, which leave out the system
     *  bars the frame and the layer both cover), bounds, symbols and
     *  oriented dims through one transform (a non-uniform scale fails soft
     *  to an upright line, as the camera's projection does). Needs the
     *  layer laid out ([buildWords]). */
    private fun screenLines(d: OneShotOverlayData): List<OcrManager.OcrLine> {
        val sx = if (d.screenshotW > 0) width.toFloat() / d.screenshotW else 1f
        val sy = if (d.screenshotH > 0) height.toFloat() / d.screenshotH else 1f
        fun project(r: Rect) = Rect(
            Math.round((r.left + d.cropLeft) * sx), Math.round((r.top + d.cropTop) * sy),
            Math.round((r.right + d.cropLeft) * sx), Math.round((r.bottom + d.cropTop) * sy),
        )
        val similar = kotlin.math.abs(sx - sy) <= 0.04f * maxOf(sx, sy)
        return d.groups.flatMapIndexed { gi, group ->
            group.lines.map { line ->
                val ang = if (similar) line.angleDeg else 0f
                OcrManager.OcrLine(
                    text = line.text,
                    bounds = project(line.bounds),
                    groupIndex = gi,
                    groupText = group.text,
                    symbols = line.symbols.map { s -> s.copy(bounds = project(s.bounds)) },
                    orientation = line.orientation,
                    angleDeg = ang,
                    orientedWidth = if (ang != 0f) line.orientedWidth * sx else 0f,
                    orientedHeight = if (ang != 0f) line.orientedHeight * sy else 0f,
                )
            }
        }
    }

    companion object {
        private const val TAG = "CaptureOverlayLayer"
        const val FADE_MS = 150L

        /** The modes every layer paints; Definitions joins with a [LookupHost]. */
        val SUPPORTED_MODES: Set<OverlayMode> =
            setOf(OverlayMode.TRANSLATION, OverlayMode.FURIGANA, OverlayMode.NONE)

        /** The modes a layer with a [LookupHost] paints: the game-display
         *  hosts (the sheet, the dual-screen window), so what their controls
         *  offer. */
        val HOSTED_MODES: Set<OverlayMode> = SUPPORTED_MODES + OverlayMode.DEFINITIONS
    }
}
