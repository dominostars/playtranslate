package com.playtranslate.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import com.playtranslate.LookupPause
import com.playtranslate.OcrManager
import com.playtranslate.Prefs
import com.playtranslate.displayGeometryOf
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.language.TokenSpan
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.overlay.OverlayPlacement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Enable tap-to-lookup": one capture of a display, read once, with a box
 * around every word the lens can look up ([TapLookupOverlay]). Every tap is
 * judged here, whichever window it reached ([onTapAt]: the boxes' own, or
 * the card's off-card hook, [MagnifierLens.onOffCardDown]):
 *  - the X, over the floating icon, ends the session, card or not;
 *  - a tap on a box, or within [BUFFER_DP] of one (the closest then), fills
 *    it and opens the drag lens's definition card on it
 *    ([DragLookupController.lookUpWord]), moving a card that is up for
 *    another word; on the word whose card is up, it closes that card;
 *  - a tap farther from every word closes the card when one is up, the boxes
 *    staying, and ends the session when none is; a tap on the card is the
 *    card's.
 * The controller drives the boxes the whole time (the card never takes it,
 * [MagnifierLens] built with takesControllerFocus = false): the D-pad moves a
 * ring over the boxes (left and right along the ringed word's row, up and
 * down across rows), A on the ringed word opens its card, A on the word
 * whose card is up closes it, and A on another word moves the card there;
 * Back with a card up closes the card, with none ends the session.
 *
 * The session decides nothing about what it paused ([pause]): when it ends
 * itself it tells its owner ([onEnded]), and the owner, which also ends it
 * for its own reasons ([close]), settles, drops or hands the record over by
 * why it ended ([com.playtranslate.OverlayUiController]).
 *
 * Which box holds the card is [selected], and it also says what a card's
 * end means: the session clears it before it closes a card itself (Back),
 * so a card that ends with it still set ended by its own means (it opened a
 * page, an Activity, the speak chip's no-engine action, a touch outside the
 * window, a lost window), and the session ends with it, so the boxes never
 * sit over what the card opened.
 *
 * Main thread only.
 */
internal class TapLookupSession(
    /** The owner's context (the capture backend's service). */
    private val context: Context,
    display: Display,
    private val displayCtx: Context,
    private val wm: WindowManager,
    private val overlayHost: OverlayHost,
    /** What this session paused while it is up. */
    val pause: LookupPause,
    private val reader: Reader,
    /** The session ended itself; [noText] when the read found no word to
     *  box. The owner detaches it, settles [pause] and [close]s it. */
    private val onEnded: (session: TapLookupSession, noText: Boolean) -> Unit,
) {
    /** Where a session's scene comes from: a clean frame of the display,
     *  the lines read in it, each line's tokens, and the frame saved for
     *  the card's Anki and detail pages. */
    interface Reader {
        suspend fun capture(displayId: Int): Bitmap?
        /** Called directly in the session's job, never inside a scoped
         *  builder ([LookupCapture.readLines]). */
        suspend fun readLines(bitmap: Bitmap, displayId: Int): List<OcrManager.OcrLine>?
        suspend fun tokenize(text: String): List<TokenSpan>
        /** Off the main thread, as the reader sees fit. */
        suspend fun saveScreenshot(bitmap: Bitmap): String?
    }

    /** The device's reader: the lookups' shared capture and read
     *  ([LookupCapture]) and the source language's engine, whose
     *  tokenization is the drag lens's lookup tokens. */
    class DeviceReader(private val context: Context) : Reader {
        override suspend fun capture(displayId: Int): Bitmap? = LookupCapture.cleanFrame(context, displayId)

        override suspend fun readLines(bitmap: Bitmap, displayId: Int): List<OcrManager.OcrLine>? =
            LookupCapture.readLines(context, bitmap, "tap-lookup-d$displayId")

        override suspend fun tokenize(text: String): List<TokenSpan> =
            SourceLanguageEngines.get(context, Prefs(context).sourceLangId).tokenize(text)

        override suspend fun saveScreenshot(bitmap: Bitmap): String? =
            withContext(Dispatchers.IO) { LookupCapture.saveScreenshot(context, bitmap, SCREENSHOT_FILE) }
    }

    val displayId: Int = display.displayId

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private val density = displayCtx.resources.displayMetrics.density

    /** The display's geometry when the session began: its frame, lines and
     *  boxes belong to that layout ([isStale]). */
    private val launchGeometry = context.displayGeometryOf(displayId)

    private var job: Job? = null
    private var closed = false

    private var overlay: TapLookupOverlay? = null
    private var params: WindowManager.LayoutParams? = null

    private var lines: List<OcrManager.OcrLine> = emptyList()
    private var lineTokens: List<List<TokenSpan>> = emptyList()
    private var boxes: List<TapLookupGeometry.WordBox> = emptyList()
    private var screenshotPath: String? = null
    private var capturedAtMs = 0L

    /** The box whose card is up or on its way; null with none (see the
     *  class kdoc for what it decides). */
    private var selected: Int? = null
    /** The box the controller cursor rings; null before the first
     *  controller press. */
    private var cursor: Int? = null

    private val magnifier = MagnifierLens(
        displayCtx, wm, displayId, overlayHost, takesControllerFocus = false,
    )
    private val lookup = DragLookupController(
        context = context,
        displayId = displayId,
        popup = WordLookupPopup(displayCtx, wm, displayId, overlayHost),
        magnifier = magnifier,
        overlayHost = overlayHost,
    )

    init {
        magnifier.onOffCardDown = { x, y -> onTapAt(x, y) }
        lookup.onSettled = { onCardGone() }
    }

    /** True once the session's display no longer has the geometry its
     *  scene was captured in. */
    fun isStale(): Boolean = context.displayGeometryOf(displayId) != launchGeometry

    /**
     * Capture the display, then put the window up with its spinner (after
     * the capture, so it is not in the frame), save the frame, read it, and
     * box every token of every line. Ends itself when the capture fails or
     * comes back for another geometry, when a step throws, and with
     * [onEnded]'s noText when no line has a token. The frame is recycled
     * once the job is over, after any reader work still holding it.
     */
    fun start() {
        val thisJob = scope.launch {
            // Every step runs inside the try: this scope has no exception
            // handler, so an exception leaving the job (a capture backend's
            // included) would crash the app instead of ending the session.
            try {
                val frame = reader.capture(displayId)
                if (closed) {
                    frame?.recycle()
                    return@launch
                }
                if (frame == null || isStale()) {
                    Log.w(TAG, "No frame for display $displayId (captured=${frame != null})")
                    frame?.recycle()
                    end(noText = false)
                    return@launch
                }
                capturedAtMs = System.currentTimeMillis()
                coroutineContext[Job]?.invokeOnCompletion { handler.post { if (!frame.isRecycled) frame.recycle() } }
                if (!showWindow()) {
                    end(noText = false)
                    return@launch
                }
                screenshotPath = reader.saveScreenshot(frame)
                val read = reader.readLines(frame, displayId).orEmpty()
                val tokens = read.map { if (it.text.isEmpty()) emptyList() else reader.tokenize(it.text) }
                if (closed) return@launch
                lines = read
                lineTokens = tokens
                boxes = read.indices.flatMap { i ->
                    TapLookupGeometry.wordBoxes(
                        read[i], i, tokens[i].map { it.surface },
                        padPx = BOX_PAD_DP * density, gapPx = BOX_GAP_DP * density,
                    )
                }
                if (boxes.isEmpty()) {
                    end(noText = true)
                    return@launch
                }
                overlay?.setBoxes(boxes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Capturing or reading the frame failed", e)
                end(noText = false)
            }
        }
        job = thisJob
    }

    /** Take the session down: its job, its card and its window. Reports
     *  nothing and leaves [pause] alone: the owner decides what becomes of
     *  that by why it closes the session. Idempotent. */
    fun close() {
        if (closed) return
        closed = true
        job?.cancel()
        lookup.destroy()
        val view = overlay
        val lp = params
        overlay = null
        params = null
        if (view != null && lp != null) {
            view.remove(wm, lp) { overlayHost.removeOverlayWindow(view) }
        }
        scope.cancel()
    }

    private fun showWindow(): Boolean {
        val view = TapLookupOverlay(
            displayCtx,
            closeDock = { iconDock() },
            onDown = { x, y -> onTapAt(x, y) },
            onKey = { onKey(it) },
            onBack = { onBack() },
        )
        // Touchable and focusable: modal over the game, holding key focus so
        // Back and the controller reach it; ALT_FOCUSABLE_IM keeps it from
        // becoming an IME target, as the capture sheet does.
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayHost.windowType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            windowAnimations = 0
        }
        if (!overlayHost.addOverlayWindow(view, wm, lp, displayId, OverlayPlacement.CaptureSpace)) return false
        overlay = view
        params = lp
        return true
    }

    /** Where the floating icon rests on this display, whether or not it is
     *  shown: its saved slot ([Prefs.iconPositionForDisplay]) placed against
     *  the display as the host reads it now, by the icon's own dock geometry
     *  ([IconDock]), so the X lands on the icon. */
    private fun iconDock(): TapLookupOverlay.Dock {
        val slot = Prefs(context).iconPositionForDisplay(displayId)
        val edgeLeft = slot.edge == FloatingOverlayIcon.Edge.LEFT.ordinal
        val env = overlayHost.environment(displayId)
        val view = overlay
        val width = env?.geometry?.width ?: view?.width ?: 0
        val height = env?.geometry?.height ?: view?.height ?: 0
        val insets = env?.covered ?: com.playtranslate.overlay.Sides.NONE
        val gestureInset = (IconDock.GESTURE_INSET_DP * density).toInt()
        return TapLookupOverlay.Dock(
            centreX = IconDock.dockX(width, insets, edgeLeft).toFloat(),
            centreY = IconDock.centreY(height, insets, gestureInset, slot.fraction).toFloat(),
            edgeLeft = edgeLeft,
        )
    }

    /** Every tap, from this window or from the card's off-card hook (see the
     *  class kdoc for the rules). */
    private fun onTapAt(rawX: Float, rawY: Float) {
        if (closed) return
        val view = overlay ?: return
        if (view.closeHit(rawX, rawY)) {
            end(noText = false)
            return
        }
        val hit = TapLookupGeometry.hitTest(boxes, rawX, rawY, BUFFER_DP * density)
        when {
            hit == null && selected == null -> end(noText = false)
            hit == null || hit == selected -> closeCard()
            else -> select(hit)
        }
    }

    /** Fill the box at [index] and open its card there, moving a card that
     *  is up for another box. */
    private fun select(index: Int) {
        val box = boxes.getOrNull(index) ?: return
        val line = lines.getOrNull(box.lineIndex) ?: return
        val token = lineTokens.getOrNull(box.lineIndex)?.getOrNull(box.tokenIndex) ?: return
        selected = index
        overlay?.setSelected(index)
        val anchor = Rect().also { box.screenBounds().roundOut(it) }
        lookup.lookUpWord(
            lines, screenshotPath, capturedAtMs,
            DragLookupController.TokenAt(line, token, box.charOffset, box.surface),
            anchor,
        )
    }

    /** Close the card ourselves (a tap off it, A on its word, Back): the
     *  selection goes first, so the card's end ([onCardGone]) leaves the
     *  boxes up. */
    private fun closeCard() {
        selected = null
        overlay?.setSelected(null)
        lookup.dismiss()
    }

    /** The card ended. With the selection still set it ended by its own
     *  means, and the session ends too. */
    private fun onCardGone() {
        if (closed || selected == null) return
        end(noText = false)
    }

    /** Back: a card up, or on its way, closes; with none, the session
     *  ends. */
    private fun onBack() {
        if (closed) return
        if (selected != null) closeCard() else end(noText = false)
    }

    /**
     * The controller cursor, as on the capture sheet: the first D-pad or A
     * press rings the first box (topmost, then leftmost) and does nothing
     * else. The D-pad then moves the ring: along the ringed word's row to
     * the next word that way, staying put at the row's end
     * ([TapLookupGeometry.nextInRow]: left and right on a horizontal line, up
     * and down on a vertical column), and across rows to the nearest box that
     * way ([SheetNavGeometry.nextInDirection]). A on the ringed box opens its
     * card, or closes it when it is that box's card that is up. Acts on the
     * DOWN and consumes the UP; keys it does not drive pass on.
     */
    private fun onKey(ev: KeyEvent): Boolean {
        val dir = ControllerKeys.direction(ev.keyCode)
        val activate = ControllerKeys.isActivate(ev.keyCode)
        if (dir == null && !activate) return false
        if (ev.action != KeyEvent.ACTION_DOWN || closed || boxes.isEmpty()) return true
        val rects = boxes.map { b ->
            val r = b.screenBounds()
            SheetNavGeometry.NavRect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
        }
        val current = cursor
        if (current == null) {
            moveCursor(SheetNavGeometry.firstItem(rects))
            return true
        }
        if (dir != null) {
            val vertical = lines.getOrNull(boxes[current].lineIndex)?.orientation ==
                com.playtranslate.language.TextOrientation.VERTICAL
            val alongRow = if (vertical) {
                dir == SheetNavGeometry.Dir.UP || dir == SheetNavGeometry.Dir.DOWN
            } else {
                dir == SheetNavGeometry.Dir.LEFT || dir == SheetNavGeometry.Dir.RIGHT
            }
            val next = if (alongRow) {
                TapLookupGeometry.nextInRow(boxes.map { it.screenBounds() }, current, dir)
            } else {
                SheetNavGeometry.nextInDirection(rects[current], rects, dir)
            }
            next?.let { moveCursor(it) }
            return true
        }
        if (ev.repeatCount == 0) {
            if (current == selected) closeCard() else select(current)
        }
        return true
    }

    private fun moveCursor(index: Int?) {
        cursor = index
        overlay?.setCursor(index)
    }

    /** The session ends itself, once. */
    private fun end(noText: Boolean) {
        if (closed) return
        onEnded(this, noText)
    }

    private companion object {
        const val TAG = "TapLookup"
        /** This surface's screenshot in the screenshots cache. */
        const val SCREENSHOT_FILE = "tap_lookup.jpg"
        /** A box's margin around its word's glyphs. */
        const val BOX_PAD_DP = 3f
        /** The least space between the strokes of two words that touch. */
        const val BOX_GAP_DP = 2f
        /** The buffer around every word: a tap that misses a box by less than
         *  this picks the closest word instead of ending the session. */
        const val BUFFER_DP = 24f
    }
}
