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
 * the next start re-measures.
 *
 * The verdict is trusted for the whole session — this probe is the SOLE
 * classifier, with no runtime backstops. That is sound because a projection
 * token's scope is immutable: a task mirror can never start compositing our
 * windows mid-session, so a correct verdict cannot rot. A WRONG CLEAN (a
 * probe misfire) manifests as visible overlay churn, and the recovery is
 * restarting live mode, which re-probes. The design burden that used to be
 * spread across runtime tripwires therefore lives HERE, as detection that
 * refuses to trust what it cannot verify:
 *
 *  - **Transform-invariant pattern matching.** Hue dominance is the fast
 *    path, but grayscale modes (bedtime/wind-down), accessibility inversion,
 *    and OEM force-dark can strip or remap color BEFORE the buffer we
 *    capture. No transform maps a checkerboard to FLAT, so the second
 *    detector is luma parity separation: the mean-luma gap between the two
 *    checker parities (~110 as drawn). The verdict signal is the gap's SIGN
 *    flipping in lockstep with the commanded phase swaps — sign-agnostic on
 *    purpose, because inversion pipelines negate luma and a fixed
 *    expectation would misread our own inverted pattern as absent. Game
 *    content cannot flip in lockstep with commands it cannot see.
 *  - **Draw confirmation.** ABSENT and SILENT rounds count as evidence only
 *    after the probe view provably re-rendered its phase (onDraw observed) —
 *    a window that never drew proves nothing, and reading its silence as
 *    task-mirror evidence was the exact false-CLEAN shape a broken add
 *    would produce. No draw → abort UNKNOWN.
 *  - **Geometry-verified frames.** A frame whose dimensions differ from the
 *    probed display cannot be scanned at laid-out coordinates; such frames
 *    are invalid, never absence-evidence, and an all-invalid round FAILs.
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
            // Frames are scanned at these laid-out screen coordinates, which
            // is only meaningful on a display-sized frame (the VD is created
            // at display size). Anything else is unscannable, not absent.
            val expectedW = size.x
            val expectedH = size.y

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
            //  - A strong luma checker whose parity sign FLIPS across two
            //    consecutive swapped rounds → CONTAMINATED: our pattern read
            //    through a color-stripping/inverting pipeline. Constant sign
            //    = environmental checker = absence-grade evidence.
            //  - FAILED (capture layer broke) or mixed evidence past
            //    [MAX_ROUNDS] → UNKNOWN, uncached: pinhole tier this start,
            //    re-measure next start.
            val ledger = Ledger()
            var round = 0
            while (true) {
                round++
                // Round 1's freshness anchor is [seqAtAdd] — the add's own
                // composition (the delivery that carries pattern A on a
                // display mirror) lands between the add and this loop, and
                // must count. Later rounds anchor at their own toggle.
                val seq = if (round == 1) seqAtAdd else controller.deliverySeqNow
                val drawsBefore = if (round == 1) 0 else view.drawCount
                if (round > 1) {
                    view.swap = !view.swap
                    view.invalidate()
                }
                // Evidence-validity precondition: this round's phase provably
                // rendered. A window that never draws proves nothing — its
                // silence must not be read as a task mirror.
                if (!awaitDrawAfter(view, drawsBefore)) {
                    return aborted("window never drew (round $round)")
                }
                val scan = scanForPattern(
                    controller, rect, swap = view.swap,
                    budgetMs = if (round == 1) FIRST_ROUND_BUDGET_MS else ROUND_BUDGET_MS,
                    minSeq = seq,
                    expectedW = expectedW, expectedH = expectedH,
                )
                val settled = ledger.observe(scan) ?: continue
                val reason = when {
                    scan == Scan.FOUND ->
                        "pattern ${if (view.swap) "B" else "A"} visible (round $round)"
                    scan == Scan.CHECKER_POS || scan == Scan.CHECKER_NEG ->
                        "luma checker flipped with commanded phase (round $round)"
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

    /** One round's evidence. [CHECKER_POS]/[CHECKER_NEG] are the
     *  transform-survivors: no hue, but a strong luma checker at the probe
     *  rect, tagged with its fixed-parity SIGN. Possibly our pattern read
     *  through a color-stripping (or inverting) pipeline — the [Ledger]
     *  believes it only when the sign flips across two consecutive rounds,
     *  in lockstep with the commanded phase swap; a constant sign is static
     *  content that merely looks checkered and counts toward absence.
     *  [NO_FRAMES] is genuine silence — the capture pipeline proved itself
     *  alive (readable frames existed, none fresh) but the mirror delivered
     *  nothing new. [FAILED] is the capture layer failing (consent lost,
     *  projection/VD dead, decode errors, geometry-invalid frames) — it must
     *  never be mistaken for silence, because silence promotes toward CLEAN
     *  and failure must abort the probe uncached (adversarial-review
     *  finding). */
    internal enum class Scan { FOUND, CHECKER_POS, CHECKER_NEG, ABSENT, NO_FRAMES, FAILED }

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
        private var lastCheckerRound = 0
        private var lastCheckerSign = 0

        /** Rounds in the current silent streak — for the verdict reason. */
        val silentRounds: Int get() = silents

        /** Feed one round's scan; returns the settled verdict or null to
         *  keep probing. */
        fun observe(scan: Scan): StreamKind? {
            rounds++
            when (scan) {
                Scan.FOUND -> return StreamKind.CONTAMINATED
                Scan.FAILED -> return StreamKind.UNKNOWN
                Scan.CHECKER_POS, Scan.CHECKER_NEG -> {
                    val sign = if (scan == Scan.CHECKER_POS) 1 else -1
                    val consecutive = lastCheckerRound == rounds - 1
                    if (consecutive && lastCheckerSign == -sign) {
                        // Flipped exactly when we swapped — ours, read
                        // through a color-stripping/inverting pipeline.
                        return StreamKind.CONTAMINATED
                    }
                    if (consecutive && lastCheckerSign == sign) {
                        // Same sign across our swap: static content that
                        // happens to checker. Our pattern is provably not
                        // what's visible there — absence-grade evidence.
                        absents++
                        if (absents >= ABSENT_ROUNDS_FOR_CLEAN) {
                            return StreamKind.CLEAN
                        }
                    } else {
                        // First checker sighting (or non-consecutive): could
                        // be ours becoming visible — neutral, wait for the
                        // flip round.
                        absents = 0
                    }
                    silents = 0
                    lastCheckerRound = rounds
                    lastCheckerSign = sign
                }
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
        expectedW: Int,
        expectedH: Int,
    ): Scan {
        val deadline = SystemClock.uptimeMillis() + budgetMs
        var freshFrames = 0
        var readableFrames = 0
        var invalidFrames = 0
        var bestMatched = 0
        var bestSeparation = 0
        var lastFreshSample = ""
        var lastInvalidDims = ""
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
                val outcome = try {
                    if (bmp.width != expectedW || bmp.height != expectedH) {
                        // Unscannable at laid-out coords — invalid, NEVER
                        // absence-evidence (a mis-scaled frame contains our
                        // pattern somewhere we are not looking).
                        invalidFrames++
                        lastInvalidDims = "${bmp.width}x${bmp.height}"
                        null
                    } else if (seqNow > minSeq) {
                        readableFrames++
                        freshFrames++
                        lastFreshSample = sampleLine(bmp, rect)
                        val pixels = sampleCellPixels(bmp, rect)
                        if (pixels == null) {
                            invalidFrames++
                            freshFrames--
                            readableFrames--
                            null
                        } else {
                            val reading = readCells(pixels, swap)
                            if (reading.hueMatched > bestMatched) {
                                bestMatched = reading.hueMatched
                            }
                            val sep = reading.lumaSeparation
                            if (kotlin.math.abs(sep) > kotlin.math.abs(bestSeparation)) {
                                bestSeparation = sep
                            }
                            when {
                                reading.hueMatched >= MATCH_CELLS_MIN -> Scan.FOUND
                                sep >= LUMA_SEPARATION_MIN -> Scan.CHECKER_POS
                                sep <= -LUMA_SEPARATION_MIN -> Scan.CHECKER_NEG
                                else -> null
                            }
                        }
                    } else {
                        readableFrames++
                        null
                    }
                } finally {
                    bmp.recycle()
                }
                if (outcome != null) return outcome
            }
            delay(FRAME_POLL_MS)
        }
        return when {
            freshFrames > 0 -> {
                DetectionLog.log(
                    "MP probe: pattern absent in $freshFrames fresh frames; " +
                        "hue=$bestMatched/${(SIZE_PX / CELL_PX) * (SIZE_PX / CELL_PX)} " +
                        "lumaSep=$bestSeparation rect=$rect sampled=$lastFreshSample"
                )
                Scan.ABSENT
            }
            // Stale reads prove the pipeline works end to end — the silence
            // is the mirror's, and counts as task-mirror evidence.
            readableFrames > 0 -> Scan.NO_FRAMES
            invalidFrames > 0 -> {
                DetectionLog.log(
                    "MP probe: only geometry-invalid frames ($lastInvalidDims " +
                        "vs ${expectedW}x$expectedH) — cannot measure"
                )
                Scan.FAILED
            }
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

    /** Center pixel of each checker cell, row-major, or null when the rect
     *  falls outside the bitmap (defensive — the geometry guard should have
     *  rejected such frames as invalid already). */
    private fun sampleCellPixels(bmp: Bitmap, rect: Rect): IntArray? {
        if (rect.left < 0 || rect.top < 0 ||
            rect.right > bmp.width || rect.bottom > bmp.height
        ) return null
        val cells = SIZE_PX / CELL_PX
        val out = IntArray(cells * cells)
        for (r in 0 until cells) {
            for (c in 0 until cells) {
                val px = rect.left + c * CELL_PX + CELL_PX / 2
                val py = rect.top + r * CELL_PX + CELL_PX / 2
                out[r * cells + c] = bmp.getPixel(px, py)
            }
        }
        return out
    }

    /** Both detectors' evidence from one frame's 36 cell-center pixels. */
    internal class CellReading(val hueMatched: Int, val lumaSeparation: Int)

    /**
     * Classify one frame's cell pixels (row-major, from [sampleCellPixels])
     * against the commanded phase. Two independent detectors:
     *
     * **Hue dominance** (fast path): magenta = red and blue both exceed
     * green by [HUE_MARGIN]; green = green exceeds both. Deliberately NOT an
     * absolute color comparison: the mirror composites our window through
     * whatever the device does to overlays — the Moto G's opacity clamp
     * delivered the pattern at ~84% intensity (measured 2026-07-10,
     * `FFD60AD6`/`FF0AD60A`), and absolute tolerance against the pure colors
     * flunked enough cells to false-CLEAN. Hue dominance is invariant under
     * any uniform attenuation (alpha clamps, screen dim, tone mapping) down
     * to ~×0.17 brightness — but it is blind whenever the pipeline strips or
     * remaps COLOR (grayscale bedtime modes, accessibility inversion, OEM
     * force-dark), which is exactly a false-CLEAN generator if hue is the
     * only detector.
     *
     * **Luma parity separation** (transform-invariant path): mean luma of
     * even-parity cells minus odd-parity cells, FIXED parity indexing (not
     * phase-relative). The drawn pattern separates by ~110 (magenta ≈72 vs
     * green ≈182), and the SIGN alternates with the commanded phase swap.
     * No color transform maps a checkerboard to flat — grayscale preserves
     * the gap verbatim, inversion negates it (the alternation survives,
     * which is why the [Ledger]'s flip test is sign-agnostic), dimming
     * scales it. Static game content that happens to checker cannot follow
     * the flip.
     */
    internal fun readCells(pixels: IntArray, swap: Boolean): CellReading {
        val cells = SIZE_PX / CELL_PX
        var matched = 0
        var sumEven = 0
        var sumOdd = 0
        for (i in pixels.indices) {
            val r = i / cells
            val c = i % cells
            if (cellHueMatches(cellIsA(r, c, swap), pixels[i])) matched++
            val luma = lumaOf(pixels[i])
            if ((r + c) % 2 == 0) sumEven += luma else sumOdd += luma
        }
        val perParity = pixels.size / 2
        return CellReading(matched, sumEven / perParity - sumOdd / perParity)
    }

    /** Integer Rec.709-ish luma of an ARGB pixel. */
    internal fun lumaOf(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (54 * r + 183 * g + 19 * b) shr 8
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
        /** Draws observed — the evidence-validity signal ([awaitDrawAfter]).
         *  Main-thread only, like all View state. */
        var drawCount = 0
        private val paint = Paint()
        override fun onDraw(canvas: Canvas) {
            drawCount++
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

    /** Suspend until the probe view has drawn again (count above
     *  [sinceCount]) or [DRAW_TIMEOUT_MS] passes. Both this poll and onDraw
     *  run on the main thread — delay() suspends, letting the draw happen. */
    private suspend fun awaitDrawAfter(view: ProbeView, sinceCount: Int): Boolean =
        withTimeoutOrNull(DRAW_TIMEOUT_MS) {
            while (view.drawCount <= sinceCount) delay(16)
            true
        } != null

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

    /** Minimum |luma parity separation| to call a checker present. The
     *  pattern separates by ~110 as drawn (~92 through the measured ×0.84
     *  clamp); random game content at the probe rect stays near 0 because a
     *  parity mean interleaves cells from the whole rect. */
    internal const val LUMA_SEPARATION_MIN = 30

    /** How long a commanded phase may take to provably render before the
     *  probe gives up on its own window (→ UNKNOWN). */
    private const val DRAW_TIMEOUT_MS = 400L

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
