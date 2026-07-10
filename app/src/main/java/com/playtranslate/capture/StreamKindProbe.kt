package com.playtranslate.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
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

/**
 * Measures whether the current MediaProjection session mirrors the whole
 * display or a single app's task ([StreamKind]). There is no public API for
 * the user's consent choice, so it is answered empirically: draw a small
 * full-alpha checker window and look for it in the mirror.
 *
 * Whole-display mirror → our windows composite into the stream, and the
 * probe's own commit forces a delivery, so the pattern shows up within a few
 * frames → CONTAMINATED. Task mirror → windows outside the captured task's
 * surface subtree are structurally absent → CLEAN, evidenced either by fresh
 * post-draw frames without the pattern (confirmed across a pattern swap) or
 * by SILENCE sustained across several forced commits — a display mirror
 * cannot ignore repeated probe commits (plus the recording chip ticking
 * ~1Hz), so multi-round no-deliveries is the task-mirror signature. A single
 * silent phase settles nothing (see [Ledger]). Everything that is not a
 * measurement — setup failures (window add, layout timeout), capture-layer
 * failures mid-scan (consent lost, projection/VD dead), mixed evidence past
 * the round cap — resolves to UNKNOWN, which is never cached: this
 * session-start routes to the pinhole tier (operationally fail-closed) and
 * the next start re-measures. The clean mode's echo tripwire and
 * [com.playtranslate.ThrashDetector] backstop any verdict this still gets
 * wrong.
 *
 * The probe window is created from the active backend's
 * [com.playtranslate.overlay.OverlayHost] context (so it carries the right
 * window type on either backend) but is deliberately NOT registered with the
 * host: a registered window can be alpha-blanked by a concurrent clean
 * capture's [com.playtranslate.overlay.OverlayHost.prepareForCleanCapture],
 * which would hide the probe mid-measurement and fake a CLEAN verdict. The
 * probe owns its addView/removeViewImmediate lifecycle directly. Total
 * budget ~1s, once per consent session; two concurrent resolvers (startLive
 * racing a one-shot) at worst probe twice and agree — undeduplicated on
 * purpose, and safe end to end because the controller serializes every
 * frame DECODE internally (its decodeMutex), so concurrent probe/one-shot/
 * live reads of the same latched frame cannot race its shared buffer.
 */
object StreamKindProbe {

    suspend fun measure(controller: MediaProjectionController): StreamKind {
        val host = CaptureBackendResolver.active().overlayHost
            ?: return aborted("no overlay host")
        // The HOST's context, not the capture service's — accessibility
        // overlay window types can only be added from the accessibility
        // service's own context.
        val displayContext = host.displayContextFor(controller.projectedDisplayId)
            ?: return aborted("projected display missing")
        val wm = displayContext.getSystemService(WindowManager::class.java)
            ?: return aborted("no WindowManager")

        val view = ProbeView(displayContext)
        val size = displayContext.displaySizePx()
        // Deliberately TOUCHABLE (no FLAG_NOT_TOUCHABLE): pass-through
        // overlays get their composited opacity clamped by the untrusted-
        // touch rules (~84% measured on the Moto G, 2026-07-10 — enough to
        // flunk an absolute color match), while a window that consumes its
        // own touches is exempt and renders at true full alpha. The cost is
        // a 48px square eating taps for ~1.4s right after the consent
        // dialog closes. NOT_TOUCH_MODAL keeps every other touch flowing.
        val params = WindowManager.LayoutParams(
            SIZE_PX, SIZE_PX,
            host.windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
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
        // Anchor BEFORE the window exists: on a contaminated mirror the add's
        // own composition delivers a frame above this seq — the freshness
        // proof scanForPattern demands.
        val seqAtAdd = controller.deliverySeqNow
        try {
            wm.addView(view, params)
        } catch (e: Exception) {
            return aborted("probe window add failed: ${e.message}")
        }
        try {
            if (!awaitLaidOut(view)) return aborted("probe layout timeout")
            // The laid-out location is the ground truth for where the pattern
            // sits on screen — immune to gravity/inset surprises.
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            val rect = Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)

            // Verdict rounds. Every round commits a pattern phase (round 1 =
            // the add itself; later rounds toggle the checker phase and
            // invalidate — real pixel damage, so a display mirror MUST
            // composite it) and scans only frames delivered after that
            // commit. The [Ledger] pins the criteria:
            //  - FOUND anywhere → CONTAMINATED.
            //  - Two consecutive fresh-frame ABSENTs (opposite phases by
            //    construction) → CLEAN: the pattern is provably not in a
            //    stream that is provably alive.
            //  - Three consecutive SILENT rounds → CLEAN: a display mirror
            //    cannot ignore three forced commits (our own repaints
            //    composite into it — this device's FOUND at 00:47 measured
            //    that mechanism — and the recording chip ticks ~1Hz on top),
            //    so total silence is the task-mirror signature. One silent
            //    phase alone no longer suffices (adversarial-review
            //    hardening); nor does silence fail closed (the
            //    fail-to-CONTAMINATED version flapped pinhole mode over any
            //    single-app capture of resting content).
            //  - FAILED (capture layer broke) or mixed evidence past
            //    [MAX_ROUNDS] → UNKNOWN, uncached: pinhole tier this start,
            //    re-measure next start.
            // Misroute backstops beyond the probe: the clean mode's echo
            // tripwire and its ThrashDetector both demote a wrong CLEAN.
            val ledger = Ledger()
            var round = 0
            while (true) {
                round++
                // Round 1's freshness anchor is [seqAtAdd] — the add's own
                // composition (the delivery that carries pattern A on a
                // display mirror) lands between the add and this loop, and
                // must count. Later rounds anchor at their own toggle.
                val seq = if (round == 1) seqAtAdd else controller.deliverySeqNow
                if (round > 1) {
                    view.swap = !view.swap
                    view.invalidate()
                }
                val scan = scanForPattern(
                    controller, rect, swap = view.swap,
                    budgetMs = if (round == 1) FIRST_ROUND_BUDGET_MS else ROUND_BUDGET_MS,
                    minSeq = seq,
                )
                val settled = ledger.observe(scan) ?: continue
                val reason = when {
                    scan == Scan.FOUND ->
                        "pattern ${if (view.swap) "B" else "A"} visible (round $round)"
                    scan == Scan.FAILED ->
                        "probe aborted: capture failure (round $round)"
                    settled == StreamKind.CLEAN && scan == Scan.NO_FRAMES ->
                        "no deliveries across ${ledger.silentRounds} forced commits — task mirror inferred"
                    settled == StreamKind.CLEAN ->
                        "pattern absent across swap"
                    else ->
                        "not measured: mixed evidence after $round rounds"
                }
                return verdict(settled, reason)
            }
        } finally {
            try { wm.removeViewImmediate(view) } catch (_: Exception) {}
        }
    }

    // ── Scanning ─────────────────────────────────────────────────────────

    /** One round's evidence. [NO_FRAMES] is genuine silence — the capture
     *  pipeline proved itself alive (readable frames existed, none fresh) but
     *  the mirror delivered nothing new. [FAILED] is the capture layer
     *  failing (consent lost, projection/VD dead, decode errors) — it must
     *  never be mistaken for silence, because silence promotes toward CLEAN
     *  and failure must abort the probe uncached (adversarial-review
     *  finding). */
    internal enum class Scan { FOUND, ABSENT, NO_FRAMES, FAILED }

    /**
     * Pure verdict ledger for the round loop, extracted so the CLEAN
     * criteria are JVM-pinned (the adversarial review's regression-test
     * ask): a single silent phase can never select CLEAN — it takes
     * [SILENT_ROUNDS_FOR_CLEAN] consecutive silent rounds (each preceded by
     * a forced commit) or [ABSENT_ROUNDS_FOR_CLEAN] consecutive fresh-frame
     * absents; FOUND settles CONTAMINATED immediately. Streaks reset each
     * other — silence interrupted by a delivery must re-earn its run.
     *
     * Everything that is NOT a measured verdict settles UNKNOWN: a FAILED
     * round (capture layer broke — never confusable with silence), and
     * mixed evidence exhausting [MAX_ROUNDS]. UNKNOWN is deliberately
     * uncacheable ([MediaProjectionController.resolveStreamKind] stores only
     * CLEAN/CONTAMINATED): this session-start routes to the pinhole tier —
     * operationally fail-closed — and the next start re-measures instead of
     * living a whole session on a verdict the probe never earned.
     */
    internal class Ledger {
        private var absents = 0
        private var silents = 0
        private var rounds = 0

        /** Rounds in the current silent streak — for the verdict reason. */
        val silentRounds: Int get() = silents

        /** Feed one round's scan; returns the settled verdict or null to
         *  keep probing. */
        fun observe(scan: Scan): StreamKind? {
            rounds++
            when (scan) {
                Scan.FOUND -> return StreamKind.CONTAMINATED
                Scan.FAILED -> return StreamKind.UNKNOWN
                Scan.ABSENT -> {
                    absents++
                    silents = 0
                    if (absents >= ABSENT_ROUNDS_FOR_CLEAN) return StreamKind.CLEAN
                }
                Scan.NO_FRAMES -> {
                    silents++
                    absents = 0
                    if (silents >= SILENT_ROUNDS_FOR_CLEAN) return StreamKind.CLEAN
                }
            }
            return if (rounds >= MAX_ROUNDS) StreamKind.UNKNOWN else null
        }
    }

    /** Poll the mirror for up to [budgetMs], looking for the checker in
     *  [rect]. FOUND returns early. Only frames DELIVERED AFTER [minSeq] —
     *  the seq observed before the pattern was drawn/swapped — count as
     *  absence-evidence: the latch can hold a pre-probe frame indefinitely on
     *  a static screen, and 2026-07-10's first false CLEAN on the Moto G was
     *  exactly that — a whole scan spent re-reading one stale frame and
     *  calling it evidence. NO_FRAMES means the mirror delivered nothing at
     *  all after our draw/swap — which the caller reads as a TASK mirror
     *  (see [measure]): a display mirror cannot stay silent past its own
     *  probe commit.
     *
     *  On ABSENT, the last fresh frame's sampled colors at the probe rect
     *  and the best cell-match count are logged — if a misverdict ever
     *  recurs, that line distinguishes a mis-positioned rect (content
     *  pixels) from a color-shifted pattern. */
    private suspend fun scanForPattern(
        controller: MediaProjectionController,
        rect: Rect,
        swap: Boolean,
        budgetMs: Long,
        minSeq: Long,
    ): Scan {
        val deadline = SystemClock.uptimeMillis() + budgetMs
        var freshFrames = 0
        var readableFrames = 0
        var bestMatched = 0
        var lastFreshSample = ""
        while (SystemClock.uptimeMillis() < deadline) {
            // Order matters: read the seq BEFORE claiming the frame — a
            // delivery between the two makes the frame look fresher than
            // proven, the unsafe direction.
            val seqNow = controller.deliverySeqNow
            val bmp = controller.captureFrameUngated()
            if (bmp == null) {
                // Null is the capture layer failing, not the mirror being
                // quiet (a healthy stream always has at least the stale
                // latched frame to serve). A dead session aborts right away;
                // a transient failure just doesn't count toward anything.
                if (!controller.hasConsent) return Scan.FAILED
            } else {
                readableFrames++
                val match = try {
                    if (seqNow > minSeq) {
                        freshFrames++
                        lastFreshSample = sampleLine(bmp, rect)
                        val matched = matchedCells(bmp, rect, swap)
                        if (matched > bestMatched) bestMatched = matched
                        matched >= MATCH_CELLS_MIN
                    } else {
                        false
                    }
                } finally {
                    bmp.recycle()
                }
                if (match) return Scan.FOUND
            }
            delay(FRAME_POLL_MS)
        }
        return when {
            freshFrames > 0 -> {
                DetectionLog.log(
                    "MP probe: pattern absent in $freshFrames fresh frames; " +
                        "best=$bestMatched/${(SIZE_PX / CELL_PX) * (SIZE_PX / CELL_PX)} " +
                        "rect=$rect sampled=$lastFreshSample"
                )
                Scan.ABSENT
            }
            // Stale reads prove the pipeline works end to end — the silence
            // is the mirror's, and counts as task-mirror evidence.
            readableFrames > 0 -> Scan.NO_FRAMES
            // Nothing but failures: silence cannot be claimed at all.
            else -> Scan.FAILED
        }
    }

    /** Corner + center cell colors of the probe rect, hex, for the
     *  absent-verdict diagnostic line. */
    private fun sampleLine(bmp: Bitmap, rect: Rect): String {
        if (rect.left < 0 || rect.top < 0 ||
            rect.right > bmp.width || rect.bottom > bmp.height
        ) return "rect-out-of-bounds"
        val half = CELL_PX / 2
        val points = listOf(
            rect.left + half to rect.top + half,
            rect.right - half to rect.top + half,
            rect.centerX() to rect.centerY(),
            rect.left + half to rect.bottom - half,
            rect.right - half to rect.bottom - half,
        )
        return points.joinToString(",") { (x, y) ->
            "%08X".format(bmp.getPixel(x, y))
        }
    }

    /** Sample each checker cell's center and classify it by HUE DOMINANCE
     *  against the expected phase — magenta = red and blue both exceed green
     *  by [HUE_MARGIN]; green = green exceeds both. Deliberately NOT an
     *  absolute color comparison: the mirror composites our window through
     *  whatever the device does to overlays — the Moto G's opacity clamp
     *  delivered the pattern at ~84% intensity (measured 2026-07-10,
     *  `FFD60AD6`/`FF0AD60A`), and absolute tolerance against the pure
     *  colors flunked enough cells to false-CLEAN. Hue dominance is
     *  invariant under any uniform attenuation (alpha clamps, screen dim,
     *  tone mapping) down to ~×0.17 brightness. ≥[MATCH_CELLS_MIN] of the
     *  36 cells must classify correctly — game pixels forming a phase-
     *  correct alternating magenta/green checker by chance is not a real
     *  risk, and a rare false FOUND resolves in the safe direction
     *  (CONTAMINATED = today's shipped world). */
    private fun patternMatches(bmp: Bitmap, rect: Rect, swap: Boolean): Boolean =
        matchedCells(bmp, rect, swap) >= MATCH_CELLS_MIN

    /** Count of hue-correct cells; also feeds the absent-verdict log. */
    private fun matchedCells(bmp: Bitmap, rect: Rect, swap: Boolean): Int {
        if (rect.left < 0 || rect.top < 0 ||
            rect.right > bmp.width || rect.bottom > bmp.height
        ) return 0
        val cells = SIZE_PX / CELL_PX
        var matched = 0
        for (r in 0 until cells) {
            for (c in 0 until cells) {
                val px = rect.left + c * CELL_PX + CELL_PX / 2
                val py = rect.top + r * CELL_PX + CELL_PX / 2
                if (cellHueMatches(cellIsA(r, c, swap), bmp.getPixel(px, py))) matched++
            }
        }
        return matched
    }

    /** Hue classification of one sampled pixel against the expected cell
     *  color. Internal for the JVM test. */
    internal fun cellHueMatches(expectMagenta: Boolean, pixel: Int): Boolean {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return if (expectMagenta) {
            r - g >= HUE_MARGIN && b - g >= HUE_MARGIN
        } else {
            g - r >= HUE_MARGIN && g - b >= HUE_MARGIN
        }
    }

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

    /** Probe abort: no measurement happened, so nothing may be cached.
     *  [MediaProjectionController.resolveStreamKind] stores only measured
     *  verdicts; an UNKNOWN routes this session-start to the pinhole tier
     *  (fail-closed) and re-measures on the next start. */
    private fun aborted(reason: String): StreamKind =
        verdict(StreamKind.UNKNOWN, "probe aborted: $reason")

    private suspend fun awaitLaidOut(view: View): Boolean =
        withTimeoutOrNull(LAYOUT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                view.doOnLayout { if (cont.isActive) cont.resume(Unit) }
            }
        } != null

    private const val SIZE_PX = 48
    private const val CELL_PX = 8
    private const val COLOR_A = 0xFFFF00FF.toInt() // magenta
    private const val COLOR_B = 0xFF00FF00.toInt() // green
    /** Minimum per-channel dominance for hue classification. Survives
     *  uniform attenuation down to ~×0.17 of the drawn intensity. */
    private const val HUE_MARGIN = 40

    /** Hue-correct cells (of 36) required for FOUND — 75%. */
    private const val MATCH_CELLS_MIN = 27

    /** Consecutive fresh-frame ABSENT rounds that settle CLEAN — opposite
     *  checker phases by construction (each round toggles). */
    internal const val ABSENT_ROUNDS_FOR_CLEAN = 2

    /** Consecutive silent rounds that settle CLEAN — each preceded by a
     *  forced commit a display mirror could not have ignored. */
    internal const val SILENT_ROUNDS_FOR_CLEAN = 3

    /** Round cap; mixed evidence exhausting it fails closed. */
    internal const val MAX_ROUNDS = 6

    /** Round 1 waits longer — it also absorbs the window's first commit. */
    private const val FIRST_ROUND_BUDGET_MS = 900L
    private const val ROUND_BUDGET_MS = 500L
    private const val FRAME_POLL_MS = 33L
    private const val LAYOUT_TIMEOUT_MS = 500L
}
