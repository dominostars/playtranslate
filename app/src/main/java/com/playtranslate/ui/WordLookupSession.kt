package com.playtranslate.ui

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import com.playtranslate.OcrManager
import com.playtranslate.language.TextOrientation
import com.playtranslate.language.TokenSpan
import com.playtranslate.overlay.OverlayHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Definitions presentation of a capture: a box around every word the
 * lens can look up in the lines the capture read, drawn by [view]; a tap on
 * a box, or within [BUFFER_DP] of one (the closest then), fills it and opens
 * the drag lens's definition card on it ([DragLookupController.lookUpWord]),
 * moving a card that is up for another word; a tap on the word whose card
 * is up closes that card; a tap farther from every word closes the card
 * when one is up and is the host's otherwise ([onTapAt] returns false, and
 * the host closes as the user would). The controller drives the boxes the
 * whole time (the card never takes it, [MagnifierLens] built with
 * takesControllerFocus = false): the D-pad moves a cursor over the boxes,
 * left and right along the cursor word's row and up and down across rows,
 * A on the cursor's word opens its card, A on the word whose card is up
 * closes it, A on another word moves the card there; Back with a card up
 * closes the card ([onBack] true), with none it is the host's.
 *
 * A card that ends by its own means (it opened a page, an Activity, a touch
 * outside its window, a lost window) ends the presentation with it
 * ([onEnded]): the host closes as the user would, so the boxes never sit
 * over what the card opened (a page it opened has already cancelled the
 * host's restart through the lens's own rule). Main thread only.
 */
internal class WordLookupSession(
    context: Context,
    displayCtx: Context,
    wm: WindowManager,
    overlayHost: OverlayHost,
    displayId: Int,
    private val density: Float,
    private val view: WordBoxesView,
    private val scope: CoroutineScope,
    /** The source language's tokenization, the drag lens's lookup tokens. */
    private val tokenize: suspend (String) -> List<TokenSpan>,
    private val onEnded: () -> Unit,
    private val onBoxesSettled: (hasBoxes: Boolean) -> Unit,
) {
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

    private var lines: List<OcrManager.OcrLine> = emptyList()
    private var lineTokens: List<List<TokenSpan>> = emptyList()
    private var boxes: List<TapLookupGeometry.WordBox> = emptyList()
    private var job: Job? = null
    private var closed = false

    /** No boxes have settled yet: the first [load] has not come (its host
     *  boxes the words once it has a size) or a tokenization is in flight.
     *  The boxes land when it is done. */
    var pending = true
        private set

    val hasBoxes: Boolean get() = boxes.isNotEmpty()

    /** The box whose card is up or on its way; null with none. */
    private var selected: Int? = null

    /** The box the controller cursor is on; null before the first press. */
    private var cursor: Int? = null

    val hasCard: Boolean get() = selected != null

    /** The box the spotlight leaves undimmed: the cursor's, else the
     *  selected one. */
    private val highlight: Int? get() = cursor ?: selected

    init {
        magnifier.onOffCardDown = { x, y -> onTapAt(x, y) }
        lookup.onSettled = { onCardGone() }
    }

    /** Box every token of [lines] (screen coordinates), the frame they were
     *  read from saved at [screenshotPath] at [capturedAtMs] (the card's to
     *  hold, [DragLookupController.setFrame]). A card up for a box that
     *  survives (the same lines read again) stays. */
    fun load(lines: List<OcrManager.OcrLine>, screenshotPath: String?, capturedAtMs: Long) {
        job?.cancel()
        this.lines = lines
        lookup.setFrame(screenshotPath, capturedAtMs)
        pending = true
        job = scope.launch {
            val tokens = try {
                withContext(Dispatchers.Default) {
                    lines.map { if (it.text.isEmpty()) emptyList() else tokenize(it.text) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "tokenizing the lines failed: ${e.javaClass.simpleName}")
                lines.map { emptyList<TokenSpan>() }
            }
            if (closed) return@launch
            lineTokens = tokens
            boxes = lines.indices.flatMap { i ->
                TapLookupGeometry.wordBoxes(
                    lines[i], i, tokens[i].map { it.surface },
                    padPx = BOX_PAD_DP * density, gapPx = BOX_GAP_DP * density,
                )
            }
            if (selected?.let { it >= boxes.size } == true) selected = null
            if (cursor?.let { it >= boxes.size } == true) cursor = null
            pending = false
            view.setBoxes(boxes)
            view.setHighlight(highlight)
            onBoxesSettled(boxes.isNotEmpty())
        }
    }

    /** The frame's saved path and time arrived after the lines (a capture
     *  whose result lands after its boxes went up): a card already up cites
     *  it from now on, as every later one does. */
    fun setFrame(screenshotPath: String?, capturedAtMs: Long) {
        lookup.setFrame(screenshotPath, capturedAtMs)
    }

    /** The drawn footprints of the boxes, in screen coordinates. */
    fun drawnRects(): List<Rect> = boxes.map { b -> Rect().also { b.screenBounds().roundOut(it) } }

    /** True when a word, or its buffer, lies under ([rawX], [rawY]). */
    fun wordAt(rawX: Float, rawY: Float): Boolean =
        TapLookupGeometry.hitTest(boxes, rawX, rawY, BUFFER_DP * density) != null

    /** Every tap, from the host or from the card's off-card hook. True when
     *  the session acted on it (a word, or a card to close); false when
     *  nothing was hit and no card was up, which is the host's. */
    fun onTapAt(rawX: Float, rawY: Float): Boolean {
        if (closed) return false
        val hit = TapLookupGeometry.hitTest(boxes, rawX, rawY, BUFFER_DP * density)
        when {
            hit == null && selected == null -> return false
            hit == null || hit == selected -> closeCard()
            else -> select(hit)
        }
        return true
    }

    /** Highlight the box at [index] and open its card there, moving a card
     *  that is up for another box. */
    private fun select(index: Int) {
        val box = boxes.getOrNull(index) ?: return
        val line = lines.getOrNull(box.lineIndex) ?: return
        val token = lineTokens.getOrNull(box.lineIndex)?.getOrNull(box.tokenIndex) ?: return
        selected = index
        if (cursor != null) cursor = index
        view.setHighlight(highlight)
        val anchor = Rect().also { box.screenBounds().roundOut(it) }
        lookup.lookUpWord(
            lines,
            DragLookupController.TokenAt(line, token, box.charOffset, box.surface),
            anchor,
        )
    }

    /** Close the card ourselves (a tap off it, A on its word, Back): the
     *  selection goes first, so the card's end ([onCardGone]) leaves the
     *  boxes up. */
    fun closeCard() {
        selected = null
        view.setHighlight(highlight)
        lookup.dismiss()
    }

    /** The card ended. With the selection still set it ended by its own
     *  means, and the presentation ends too. */
    private fun onCardGone() {
        if (closed || selected == null) return
        onEnded()
    }

    /** Back: a card up, or on its way, closes (true); with none, the
     *  host's (false). */
    fun onBack(): Boolean {
        if (closed || selected == null) return false
        closeCard()
        return true
    }

    /**
     * The controller cursor: the first D-pad or A press puts it on the
     * highlighted box, or the first box (topmost, then leftmost) with none,
     * and does nothing else. The D-pad then moves it, the spotlight with
     * it: along the cursor word's row to the next word that way, staying
     * put at the row's end ([TapLookupGeometry.nextInRow]: left and right
     * on a horizontal line, up and down on a vertical column), and across
     * rows to the nearest box that way ([SheetNavGeometry.nextInDirection]).
     * A on the cursor's box opens its card, or closes it when it is that
     * box's card that is up. Acts on the DOWN and consumes the UP; keys it
     * does not drive pass on.
     */
    fun onKey(ev: KeyEvent): Boolean {
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
            moveCursor(selected ?: SheetNavGeometry.firstItem(rects))
            return true
        }
        if (dir != null) {
            val vertical = lines.getOrNull(boxes[current].lineIndex)?.orientation == TextOrientation.VERTICAL
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

    /** Put the controller cursor on the box at [index] (in [drawnRects]'
     *  order), the spotlight with it, as a D-pad move does: the sheet's own
     *  cursor handing the controller to the words. The D-pad moves it on
     *  from there. */
    fun focus(index: Int) {
        if (closed || index !in boxes.indices) return
        moveCursor(index)
    }

    private fun moveCursor(index: Int?) {
        cursor = index
        view.setHighlight(highlight)
    }

    /** The presentation is over: the tokenization, the card and its windows go. */
    fun release() {
        if (closed) return
        closed = true
        job?.cancel()
        pending = false
        lookup.destroy()
    }

    companion object {
        private const val TAG = "WordLookup"
        /** A box's growth past the word's extent on every side. */
        const val BOX_PAD_DP = 3f
        /** The least gap between neighbouring boxes along a line. */
        const val BOX_GAP_DP = 2f
        /** How far from a box a tap still picks it, the closest when several. */
        const val BUFFER_DP = 24f
    }
}
