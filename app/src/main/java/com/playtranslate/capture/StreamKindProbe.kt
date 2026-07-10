package com.playtranslate.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.view.doOnLayout
import com.playtranslate.DetectionLog
import com.playtranslate.displaySizePx
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * Measures whether the current MediaProjection session mirrors the whole
 * display or a single app's task ([StreamKind]). There is no public API for
 * the user's consent choice, so it is answered empirically: draw a small
 * full-alpha checker window and look for it in the mirror.
 *
 * Whole-display mirror → our windows composite into the stream, and the
 * probe's own commit forces a delivery, so the pattern shows up within a few
 * frames → CONTAMINATED. Task mirror → windows outside the captured task's
 * surface subtree are structurally absent; the pattern never appears →
 * CLEAN, confirmed across a pattern swap so the verdict rests on two
 * independent absences (a static screen cannot fake both). Every ambiguous
 * outcome — window add failure, layout timeout, no readable frames — resolves
 * to CONTAMINATED, the world every shipped session already lives in, and
 * logs why.
 *
 * The probe window is created from the active backend's
 * [com.playtranslate.overlay.OverlayHost] context (so it carries the right
 * window type on either backend) but is deliberately NOT registered with the
 * host: a registered window can be alpha-blanked by a concurrent clean
 * capture's [com.playtranslate.overlay.OverlayHost.prepareForCleanCapture],
 * which would hide the probe mid-measurement and fake a CLEAN verdict. The
 * probe owns its addView/removeViewImmediate lifecycle directly. Total
 * budget ~1s, once per consent session; two concurrent resolvers (startLive
 * racing a one-shot) at worst probe twice and agree — benign, undeduplicated
 * on purpose.
 */
object StreamKindProbe {

    suspend fun measure(controller: MediaProjectionController): StreamKind {
        val host = CaptureBackendResolver.active().overlayHost
            ?: return contaminated("no overlay host")
        // The HOST's context, not the capture service's — accessibility
        // overlay window types can only be added from the accessibility
        // service's own context.
        val displayContext = host.displayContextFor(controller.projectedDisplayId)
            ?: return contaminated("projected display missing")
        val wm = displayContext.getSystemService(WindowManager::class.java)
            ?: return contaminated("no WindowManager")

        val view = ProbeView(displayContext)
        val size = displayContext.displaySizePx()
        val params = WindowManager.LayoutParams(
            SIZE_PX, SIZE_PX,
            host.windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Quarter-point: clear of the status bar, the floating icon
            // (right edge), and any single-app letterbox edge.
            x = size.x / 4
            y = size.y / 4
            // Position in full-display coordinates, not inside system-bar
            // insets — the frame is display-sized, and the pattern test uses
            // screen coords. (The probe only runs on API 34+, but the guard
            // keeps the compiler honest about the API 30 method.)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
        }
        try {
            wm.addView(view, params)
        } catch (e: Exception) {
            return contaminated("probe window add failed: ${e.message}")
        }
        try {
            if (!awaitLaidOut(view)) return contaminated("probe layout timeout")
            // The laid-out location is the ground truth for where the pattern
            // sits on screen — immune to gravity/inset surprises.
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            val rect = Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
            // Give the freshly-added window its commit + composition before
            // burning scan budget on frames that predate it.
            waitVsync(2)

            when (scanForPattern(controller, rect, swap = false, budgetMs = PHASE_A_BUDGET_MS)) {
                Scan.FOUND -> return verdict(StreamKind.CONTAMINATED, "pattern A visible")
                Scan.NO_FRAMES -> return contaminated("no frames during phase A")
                Scan.ABSENT -> Unit
            }
            view.swap = true
            view.invalidate()
            waitVsync(2)
            return when (scanForPattern(controller, rect, swap = true, budgetMs = PHASE_B_BUDGET_MS)) {
                Scan.FOUND -> verdict(StreamKind.CONTAMINATED, "pattern B visible")
                Scan.NO_FRAMES -> contaminated("no frames during phase B")
                Scan.ABSENT -> verdict(StreamKind.CLEAN, "pattern absent across swap")
            }
        } finally {
            try { wm.removeViewImmediate(view) } catch (_: Exception) {}
        }
    }

    // ── Scanning ─────────────────────────────────────────────────────────

    private enum class Scan { FOUND, ABSENT, NO_FRAMES }

    /** Poll the latched mirror for up to [budgetMs], looking for the checker
     *  in [rect]. FOUND returns early; otherwise ABSENT if at least one frame
     *  was readable (evidence of absence) and NO_FRAMES if none was
     *  (absence of evidence — the caller fails safe). */
    private suspend fun scanForPattern(
        controller: MediaProjectionController,
        rect: Rect,
        swap: Boolean,
        budgetMs: Long,
    ): Scan {
        val deadline = SystemClock.uptimeMillis() + budgetMs
        var framesSeen = 0
        while (SystemClock.uptimeMillis() < deadline) {
            val bmp = controller.captureFrameUngated()
            if (bmp != null) {
                framesSeen++
                val match = try {
                    patternMatches(bmp, rect, swap)
                } finally {
                    bmp.recycle()
                }
                if (match) return Scan.FOUND
            }
            delay(FRAME_POLL_MS)
        }
        return if (framesSeen > 0) Scan.ABSENT else Scan.NO_FRAMES
    }

    /** Sample each checker cell's center and compare against the expected
     *  color. The tolerance absorbs color-management shifts; the two
     *  saturated colors are ~255 levels apart per channel, so the match is
     *  unambiguous. ≥90% of cells must agree. */
    private fun patternMatches(bmp: Bitmap, rect: Rect, swap: Boolean): Boolean {
        if (rect.left < 0 || rect.top < 0 ||
            rect.right > bmp.width || rect.bottom > bmp.height
        ) return false
        val cells = SIZE_PX / CELL_PX
        var matched = 0
        for (r in 0 until cells) {
            for (c in 0 until cells) {
                val px = rect.left + c * CELL_PX + CELL_PX / 2
                val py = rect.top + r * CELL_PX + CELL_PX / 2
                val expected = if (cellIsA(r, c, swap)) COLOR_A else COLOR_B
                if (channelsClose(expected, bmp.getPixel(px, py))) matched++
            }
        }
        val total = cells * cells
        return matched * 10 >= total * 9
    }

    private fun channelsClose(a: Int, b: Int): Boolean =
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) <= TOLERANCE &&
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) <= TOLERANCE &&
            abs((a and 0xFF) - (b and 0xFF)) <= TOLERANCE

    private fun cellIsA(row: Int, col: Int, swap: Boolean): Boolean =
        ((row + col) % 2 == 0) != swap

    // ── Probe window ─────────────────────────────────────────────────────

    private class ProbeView(context: Context) : View(context) {
        /** Inverts the checker phase; set + invalidate for the second pass. */
        var swap = false
        private val paint = Paint()
        override fun onDraw(canvas: Canvas) {
            val cells = SIZE_PX / CELL_PX
            for (r in 0 until cells) {
                for (c in 0 until cells) {
                    paint.color = if (cellIsA(r, c, swap)) COLOR_A else COLOR_B
                    canvas.drawRect(
                        (c * CELL_PX).toFloat(), (r * CELL_PX).toFloat(),
                        ((c + 1) * CELL_PX).toFloat(), ((r + 1) * CELL_PX).toFloat(),
                        paint,
                    )
                }
            }
        }
    }

    // ── Plumbing ─────────────────────────────────────────────────────────

    private fun verdict(kind: StreamKind, why: String): StreamKind {
        DetectionLog.log("MP stream kind: $kind ($why)")
        return kind
    }

    private fun contaminated(reason: String): StreamKind =
        verdict(StreamKind.CONTAMINATED, "ambiguous: $reason")

    private suspend fun awaitLaidOut(view: View): Boolean =
        withTimeoutOrNull(LAYOUT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                view.doOnLayout { if (cont.isActive) cont.resume(Unit) }
            }
        } != null

    private suspend fun waitVsync(frames: Int) {
        repeat(frames) {
            suspendCancellableCoroutine<Unit> { cont ->
                Choreographer.getInstance().postFrameCallback {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
    }

    private const val SIZE_PX = 48
    private const val CELL_PX = 8
    private const val COLOR_A = 0xFFFF00FF.toInt() // magenta
    private const val COLOR_B = 0xFF00FF00.toInt() // green
    private const val TOLERANCE = 48
    private const val PHASE_A_BUDGET_MS = 600L
    private const val PHASE_B_BUDGET_MS = 400L
    private const val FRAME_POLL_MS = 33L
    private const val LAYOUT_TIMEOUT_MS = 500L
}
