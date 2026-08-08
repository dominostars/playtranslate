package com.playtranslate.camera

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import com.playtranslate.OcrManager
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.ui.DragLookupController
import com.playtranslate.ui.MagnifierLens
import com.playtranslate.ui.TtsAlertTarget
import com.playtranslate.ui.WordLookupPopup
import com.playtranslate.ui.showAnkiNotInstalledDialog
import kotlin.math.abs

/**
 * Word lookup on the frozen snapshot itself: taps and drags on the
 * screenshot (outside the result sheet) drive the floating icon's
 * drag-lookup machinery — magnified frame under the finger, word readout,
 * dwell definitions, release lookup, speak/Anki/open-detail chips — against
 * the ALREADY-recognised scene: no capture, no OCR; the session's display
 * caches are the source, so a region-filtered snapshot looks up only the
 * region's text.
 *
 * A tap (release inside touch slop, before the hold delay) never shows the
 * zoom lens: the reveal happens at release, straight into the lens's
 * loading/definitions presentation — the tap-a-word "definition popup". A
 * drag past slop (or a hold) reveals the magnifier and behaves exactly like
 * the floating-icon flow.
 *
 * The scene lines and the magnifier bitmap are handed over in VIEW space —
 * [CameraCoordinates]-projected — because the whole drag-lookup stack
 * assumes bitmap coordinates == screen coordinates.
 *
 * Main thread only. Every surface is an activity window
 * (TYPE_APPLICATION_PANEL); no overlay permission involved.
 */
class CameraWordLookup(
    private val activity: Activity,
    /** The frozen scene currently owning the display, or null when none —
     *  read at gesture time. The camera passes the session's
     *  frozenLookupScene; the import tool its own session's. */
    private val scene: () -> com.playtranslate.camera.render.FrozenLookupScene?,
    /** Current frozen frame (AU space), or null when none — read at gesture
     *  time so re-snapshots are picked up automatically. */
    private val frozenBitmap: () -> Bitmap?,
    /** AU frame → view-size screen-space projection (the host controller's
     *  renderer, in ITS coordinate mode). The returned bitmap's ownership
     *  transfers to the drag controller, which recycles it per gesture. */
    private val renderViewSpace: (Bitmap, Int, Int) -> Bitmap?,
    /** The full-bleed host's dims — the space [CameraCoordinates] maps to. */
    private val hostSize: () -> Pair<Int, Int>,
    /** The host's on-screen origin. This whole flow equates raw event
     *  coordinates with host-local ones (the drag stack is screen-space by
     *  design), which is only true while the host sits at the display
     *  origin — fullscreen, both tools' only real configuration. */
    private val hostOrigin: () -> Pair<Int, Int>,
    /** How the AU frame maps onto the host — must match the flow's image
     *  scale type ([CameraCoordinates.FitMode.FILL] for the camera's
     *  centerCrop freeze frame, FIT for the import review's letterboxed
     *  image) or line hit-tests land beside the text. */
    private val fitMode: CameraCoordinates.FitMode = CameraCoordinates.FitMode.FILL,
    /** The review zoom's view transform at gesture time, or null at fit
     *  (the default and the pre-zoom behavior). Composed AFTER the fit
     *  projection so line hit-tests land on the zoomed glyph positions —
     *  the zoomed TAP path routes through this machine; the drag-magnifier
     *  itself only runs at fit. */
    private val viewTransform: () -> android.graphics.Matrix? = { null },
) {
    private val magnifier = MagnifierLens(
        activity, activity.windowManager, Display.DEFAULT_DISPLAY,
        overlayHost = null,
    )
    private val popup = WordLookupPopup(activity, activity.windowManager)
    private val controller = DragLookupController(
        activity, Display.DEFAULT_DISPLAY, popup, magnifier,
        // Dead in this configuration: every consumer that would touch it
        // (speak-chip alerts, the Anki-not-installed dialog) is overridden
        // with an activity surface below. Constructed only to satisfy the
        // shared signature, same as the snapshot panel's host.
        overlayHost = OverlayHost(activity, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY),
        ttsAlertTarget = TtsAlertTarget.InActivity(activity),
        showAnkiNotInstalled = { showAnkiNotInstalledDialog(activity) },
    )

    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
    private val density = activity.resources.displayMetrics.density

    private var tracking = false
    private var revealed = false
    private var downX = 0f
    private var downY = 0f

    /** This gesture's view-space lines, kept for the tap-release text check
     *  (a quick tap on bare background ends quietly instead of flashing the
     *  lens). Overwritten each gesture. */
    private var gestureLines: List<OcrManager.OcrLine> = emptyList()

    /** Holding still without crossing slop still means "I want the lens" —
     *  reveal it before the controller's dwell timer can fire definitions
     *  into a hidden window. Taps release before this. */
    private val holdReveal = Runnable { if (tracking && !revealed) reveal() }

    /** Sheet-routed outside gesture stream. Returns false on a DOWN with no
     *  text near the finger — the sheet keeps its default outside behavior
     *  for that gesture. */
    fun onOutsideTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val scene = scene() ?: return false
                val bmp = frozenBitmap()?.takeIf { !it.isRecycled } ?: return false
                val (w, h) = hostSize()
                if (w <= 0 || h <= 0) return false
                // Enforce the raw==host-local assumption instead of holding
                // it silently: off-origin means multi-window/freeform, where
                // every hit-test would be offset by the window position (and
                // the lens bounds math reads display size). Decline — the
                // lookup is absent there rather than wrong (Codex finding).
                val (ox, oy) = hostOrigin()
                if (ox != 0 || oy != 0) return false
                val coords = CameraCoordinates(scene.auWidth, scene.auHeight, w, h, fitMode)
                val zoomMatrix = viewTransform()
                fun project(r: Rect): Rect {
                    val v = coords.auToView(r)
                    val z = zoomMatrix ?: return v
                    val rf = android.graphics.RectF(v)
                    z.mapRect(rf)
                    return Rect(
                        Math.round(rf.left), Math.round(rf.top),
                        Math.round(rf.right), Math.round(rf.bottom),
                    )
                }
                val viewLines = scene.lines.map { line ->
                    val pb = project(line.bounds)
                    // The oriented dims must ride the SAME transform as the
                    // bounds — a bare copy would pair view-space bounds with
                    // AU-space dims. The chain here is fit-scale + pinch
                    // (scale+translate), so the effective scales fall out of
                    // the projected AABB; should they ever disagree (a
                    // non-similarity transform), fail soft to an upright
                    // line — AABB hit-testing, never wrong geometry.
                    val sx = if (line.bounds.width() > 0) pb.width().toFloat() / line.bounds.width() else 1f
                    val sy = if (line.bounds.height() > 0) pb.height().toFloat() / line.bounds.height() else 1f
                    val similar = kotlin.math.abs(sx - sy) <= 0.04f * maxOf(sx, sy)
                    val ang = if (similar) line.angleDeg else 0f
                    line.copy(
                        bounds = pb,
                        symbols = line.symbols.map { s -> s.copy(bounds = project(s.bounds)) },
                        angleDeg = ang,
                        orientedWidth = if (ang != 0f) line.orientedWidth * sx else 0f,
                        orientedHeight = if (ang != 0f) line.orientedHeight * sy else 0f,
                    )
                }
                // No near-text gate on the DOWN: a hold or drag ANYWHERE on
                // the frozen frame should raise the magnifier (the floating
                // icon behaves the same over empty areas); the text check
                // moved to the tap release below. The camera's outside-tap
                // default is consume-and-ignore, so claiming costs nothing.
                val projection = renderViewSpace(bmp, w, h) ?: return false
                gestureLines = viewLines
                controller.onDragStartWithScene(projection, scene.screenshotPath, viewLines)
                tracking = true
                revealed = false
                downX = ev.rawX
                downY = ev.rawY
                controller.onDragMove(ev.rawX, ev.rawY)
                handler.postDelayed(holdReveal, HOLD_REVEAL_MS)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger is a pinch (or a pinch ATTEMPT on a
                // surface with no zoom range) — never a lookup. Cancel the
                // flow outright: on no-zoom content a lens appearing under
                // a pinch reads as broken zoom, not as a feature.
                if (!tracking) return false
                endTracking()
                controller.cancelDrag()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                if (!revealed &&
                    (abs(ev.rawX - downX) > touchSlop || abs(ev.rawY - downY) > touchSlop) &&
                    ev.eventTime - ev.downTime >= HOLD_REVEAL_MS
                ) {
                    // Slop alone used to reveal immediately — but a pinch's
                    // first finger crosses slop in milliseconds, before the
                    // second finger has landed, flashing the lens under a
                    // zoom gesture. The reveal now waits out the same grace
                    // the hold path uses; a genuine drag just sees its lens
                    // ~a tenth of a second later, and the POINTER_DOWN
                    // branch above cancels quietly inside the window.
                    reveal()
                }
                controller.onDragMove(ev.rawX, ev.rawY)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!tracking) return false
                endTracking()
                // Quick tap on bare background: end quietly — revealing
                // would flash a lens that the release lookup immediately
                // dismisses for want of a hit.
                if (!revealed && !nearText(gestureLines, ev.rawX, ev.rawY)) {
                    controller.cancelDrag()
                    return true
                }
                // Tap on text: surface the lens at release — the release
                // lookup flips it to loading/definitions before the frame
                // draws, so the zoom presentation never flashes.
                if (!revealed) reveal()
                controller.onDragEnd()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!tracking) return false
                endTracking()
                controller.cancelDrag()
                return true
            }
        }
        return tracking
    }

    /** Claim gate: only begin a lookup gesture when the finger lands near a
     *  recognised line — a bare-background tap stays with the sheet's own
     *  outside handling. Slack sized past the controller's loosest hit tier
     *  so a claimable gesture can't resolve to nothing purely on the gate.
     *  Slanted lines keep the AABB test deliberately: the AABB contains the
     *  oriented footprint, so the gate stays strictly looser than the
     *  controller's oriented hit-test — the ordering the gate exists for. */
    private fun nearText(lines: List<OcrManager.OcrLine>, x: Float, y: Float): Boolean {
        val slack = (CLAIM_SLACK_DP * density).toInt()
        val px = x.toInt()
        val py = y.toInt()
        return lines.any { line ->
            Rect(line.bounds).apply { inset(-slack, -slack) }.contains(px, py)
        }
    }

    private fun endTracking() {
        tracking = false
        handler.removeCallbacks(holdReveal)
    }

    private fun reveal() {
        revealed = true
        controller.revealLens()
    }

    /** Tear down any active lookup surface — unfreeze, crop mode, snapshot
     *  re-run: the scene it describes is going away. */
    fun dismiss() {
        endTracking()
        revealed = false
        controller.dismiss()
    }

    fun destroy() {
        endTracking()
        controller.destroy()
    }

    private companion object {
        /** The lens-reveal floor: the hold-still delay AND the earliest a
         *  slop-crossing drag may reveal. One window for both paths — it
         *  doubles as the multi-touch grace period, so a pinch's second
         *  finger (which lands well inside it) cancels the flow before
         *  anything shows. Comfortably under the controller's 1 s dwell
         *  timer; taps are unaffected (they resolve at release). */
        const val HOLD_REVEAL_MS = 160L

        /** Gesture-claim slack around line bounds (see [nearText]). */
        const val CLAIM_SLACK_DP = 32f
    }
}
