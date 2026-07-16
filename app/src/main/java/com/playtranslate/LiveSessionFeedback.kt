package com.playtranslate

import android.graphics.Rect
import android.view.Display
import com.playtranslate.capture.CaptureBackendResolver
import com.playtranslate.capture.LiveCaptureSource
import com.playtranslate.capture.MediaProjectionController
import com.playtranslate.capture.StartupChip
import com.playtranslate.capture.StreamKind
import com.playtranslate.capture.StreamKindProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One live session's feedback machinery: the eager OCR engine warm-up, the
 * startup card (probe checker → warm-up spinner, on screen from live start
 * until the FIRST COMPLETED OCR pass, so the user is never left staring at
 * a dead screen) with its grace and leak-guard timers, the OCR exclusion
 * that keeps the card's own text out of results wherever frames can contain
 * it, the warm-up gate, and the slow-pass busy tracking that drives the
 * floating icons' breathe.
 *
 * Created by [CaptureService.startLive], disposed by stopLive and by the
 * next start — the Region-session idiom: the old session is cancelled and
 * replaced atomically, no field-by-field reset checklist on the
 * process-lived service. Every timer runs in [scope], a child of the
 * service scope, so [dispose] kills all of them at once; and an OCR pass
 * finalizer holds a reference to ITS session object plus a membership
 * token, so a pass that outlives its session (native inference cancels
 * lazily) finalizes into a disposed, inert object — structurally unable to
 * touch the next session's state. (The 2026-07-15 adversarial reviews found
 * three interference bugs of exactly that shape in the loose-field version.)
 *
 * Main-confined, like the live-mode mutators that drive it.
 */
internal class LiveSessionFeedback(
    parentScope: CoroutineScope,
    private val controller: MediaProjectionController,
    sourceLang: String,
    /** Invoked at most once per session, on Main, when a live OCR pass has
     *  been in flight past [OCR_SLOW_PROMPT_MS] — the slow-device signal
     *  the rescue prompt fires on. Receives the displayId of an in-flight
     *  slow pass, so the prompt can render on the screen whose capture is
     *  actually grinding (review finding: multi-display setups must not get
     *  the alert on a display the user isn't watching). Runs in [scope]: a
     *  disposed session can never fire it. */
    private val onSlowPass: (Int) -> Unit = {},
) {

    private val scope = CoroutineScope(
        SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.Main
    )

    /** Eager engine warm-up, started at construction so the Meiki/Paddle
     *  native session load overlaps consent/probe/MP setup instead of
     *  landing inside cycle 1's runOcr. Joined by [awaitFirstCycleClear]. */
    private val warmUpJob: Job = scope.launch {
        OcrManager.instance.warmUpEngine(sourceLang)
    }

    // ── Startup chip ─────────────────────────────────────────────────────

    private var chip: StartupChip? = null
    private var chipHardCap: Job? = null
    private var chipGrace: Job? = null

    /** Show the checker chip ahead of a stream-kind probe. The probe draws
     *  its pattern into it via [probeSurface]. */
    fun showProbeChip() {
        showChip(withPattern = true)
    }

    /** The chip as the probe's pattern host; null (→ the probe's own
     *  ephemeral window) when no chip is on screen. */
    val probeSurface: StreamKindProbe.ProbeSurface?
        get() = chip?.takeIf { !it.isRemoved }

    /** The stream-kind verdict settled (whatever it settled to): the card's
     *  grid slot swaps to the loading spinner. */
    fun onVerdictSettled() {
        chip?.onVerdictSettled()
    }

    /** Hide/re-show around the UNKNOWN stream-kind prompt — an
     *  "Initializing…" chip over a question would wrongly say no action is
     *  needed. */
    fun setChipVisible(visible: Boolean) {
        chip?.setVisible(visible)
    }

    /** Spinner-variant grace chip for starts where no probe runs
     *  (accessibility tier, cached stream kind): engine warm-up is then the
     *  only wait, and chrome should exist only when the wait is real. Armed
     *  once per start, after the probe question is settled — a spinner card
     *  must never collide with a pattern the probe is about to measure. A
     *  warm engine never flashes chrome. */
    fun armChipGrace() {
        if (chip?.isRemoved == false) return // probe chip already up
        chipGrace?.cancel()
        chipGrace = scope.launch {
            delay(CHIP_GRACE_MS)
            if (warmUpJob.isActive) showChip(withPattern = false)
        }
    }

    private fun showChip(withPattern: Boolean) {
        if (chip?.isRemoved == false) return
        chip = StartupChip.show(controller, withPattern)?.also {
            // Leak guard, not a normal-path truncator: generously above the
            // worst-case probe (~3.4s) plus a slow warm-up, for the paths
            // where no first cycle ever runs to remove the chip (an aborted
            // setLiveDisplays, a mode that never starts).
            chipHardCap?.cancel()
            chipHardCap = scope.launch {
                delay(CHIP_HARD_CAP_MS)
                removeChip()
            }
        }
    }

    /** Remove the chip window. Idempotent — called from the first-result
     *  hook, the hard cap, explicit start-aborts, and [dispose]. */
    fun removeChip() {
        chipHardCap?.cancel()
        chipHardCap = null
        chip?.remove()
        chip = null
    }

    /** The card's on-screen rect while it could appear in captured frames —
     *  null on a CLEAN stream (a task mirror structurally never composites
     *  our windows; excluding there would drop REAL game text under the
     *  card), null when no card is up or it is hidden, and null for the one
     *  pass whose frame was grabbed through a successful pre-grab blink
     *  ([blinkCardForFirstGrab] — that frame is proven card-free, and
     *  excluding would drop real text). Everything else — whole-display
     *  mirrors, accessibility screenshots (no consent ⇒ not CLEAN) — sees
     *  the card in its pixels, and the app must never OCR its own chrome.
     *  Queried per pass so the rect always reflects the card's live
     *  position. */
    fun ocrExclusionRect(displayId: Int): Rect? {
        if (displayId != Display.DEFAULT_DISPLAY) return null
        if (lastGrabCardFree) {
            // Consume-and-clear: the flag describes exactly one grabbed
            // frame; the next grab re-evaluates from scratch.
            lastGrabCardFree = false
            return null
        }
        if (controller.streamKind == StreamKind.CLEAN) return null
        return chip?.onScreenRect
    }

    // ── Pre-grab blink (whole-display mirrors) ───────────────────────────

    /** The blink ran this session — at most once. Later pre-completion
     *  grabs (guard-branch retries, black-screen boot loops) fall back to
     *  exclusion rather than blinking the card at the user repeatedly. */
    private var blinkAttempted = false

    /** The LATEST default-display grab served a proven card-free frame.
     *  Consumed (and cleared) by [ocrExclusionRect]; reset by every
     *  default-display grab so it can never describe an older frame than
     *  the one about to be OCR'd. Known residual: a hold-to-translate pass
     *  interleaving in the ms between blink-grab and the cycle's OCR can
     *  consume the flag meant for the cycle — both misdirections degrade
     *  safely (a needless exclusion on a card-free frame, or at worst one
     *  transient hold result containing the card label). */
    private var lastGrabCardFree = false

    /**
     * Bracket the FIRST frame grab that could contain the card with a
     * one-time blink: hide the card, prove (or settle) its absence from
     * the frame, run [grab] — which then serves a card-free frame, giving
     * pass 1 the full screen INCLUDING the text under the card — and
     * re-show. Whole-display mirrors prove freshness by waiting for the
     * hide's own composition to latch (capped: a wedged pipeline degrades
     * to exclusion, never to self-OCR). Sources with no delivery signal —
     * accessibility screenshots, which read the live window state at call
     * time — get a short compositor settle instead: the best assurance
     * available there, and far better than leaning on post-OCR exclusion
     * for the first pass (review finding: exclusion can drop real game
     * text that grouped with or under the card). Re-show is in a finally
     * so a cancelled cycle can never strand the card hidden. CLEAN streams
     * skip (their frames never contain the card); the blink never repeats.
     */
    suspend fun <T> blinkCardForFirstGrab(
        displayId: Int,
        source: LiveCaptureSource?,
        grab: suspend () -> T,
    ): T {
        if (displayId != Display.DEFAULT_DISPLAY) return grab()
        lastGrabCardFree = false
        val c = chip
        if (c == null || c.isRemoved || blinkAttempted || source == null ||
            controller.streamKind == StreamKind.CLEAN
        ) {
            return grab()
        }
        blinkAttempted = true
        val signal = source.deliverySignal
        val seqAtHide = signal?.seqNow() ?: 0L
        c.setVisible(false)
        return try {
            lastGrabCardFree = if (signal != null) {
                withTimeoutOrNull(BLINK_FRESH_CAP_MS) {
                    signal.awaitSeqAfter(seqAtHide)
                } != null
            } else {
                delay(BLINK_A11Y_SETTLE_MS)
                true
            }
            grab()
        } finally {
            c.setVisible(true)
        }
    }

    /** A live OCR pass ran to completion on [displayId] — including the
     *  "nothing to translate" outcome, which is also an answer. The card
     *  lives on the default display: when that display is part of the
     *  session ([cardDisplayLive]), only ITS first pass ends the narration —
     *  a secondary display finishing first must not strand the primary back
     *  in dead air. When it is NOT part of the session (secondary-only
     *  capture; the probe still ran on the projected display), ANY completed
     *  pass clears the card — otherwise nothing ever would, and the
     *  touchable card would squat until the hard cap (review finding).
     *  Frames captured from here on are post-card; the removal composition
     *  itself wakes the delivery-gated tiers, whose next cycle re-reads the
     *  region the card occupied. */
    fun onFirstOcrComplete(displayId: Int, cardDisplayLive: Boolean) {
        if (cardDisplayLive && displayId != Display.DEFAULT_DISPLAY) return
        removeChip()
    }

    // ── Pre-first-cycle gate ─────────────────────────────────────────────

    /**
     * A live cycle may not capture until the engine warm-up SETTLED —
     * otherwise the lazy session load lands mid-cycle and then OCRs a frame
     * that went stale while the model loaded. That is the gate's whole job
     * now: the card no longer has to leave the screen before cycle 1 (it
     * stays up until the first completed pass), because frames that could
     * contain it have its rect excluded from OCR ([ocrExclusionRect]) —
     * CLEAN mirrors never composite it at all. Idempotent and safe to
     * re-enter (loop restarts, input kicks). Runs in the CALLER's scope, so
     * a mode stop cancels it like any parked cycle; the cap is a hang
     * guard, not policy — warmUpEngine always returns.
     */
    suspend fun awaitFirstCycleClear() {
        withTimeoutOrNull(WARMUP_JOIN_CAP_MS) { warmUpJob.join() }
    }

    // ── Slow-pass busy tracking ──────────────────────────────────────────
    // On devices where an OCR pass runs multi-second, live mode shows
    // nothing while it works and users read the silence as "broken". After
    // any pass has been in flight past the grace period, every floating
    // icon breathes until none is. Membership tokens, not a counter: passes
    // overlap (per-display cycles, a hold fan-out over a still-unwinding
    // live pass), and a token identifies its pass — a stale finalizer
    // removes only its own token from its own session, so the books cannot
    // drift and no generation tagging is needed.

    /** One in-flight live OCR pass. Identity is the token; [displayId]
     *  rides along so the slow-pass prompt can target the display whose
     *  capture is actually slow. */
    class OcrPassToken internal constructor(internal val displayId: Int)

    private val busyPasses = mutableSetOf<OcrPassToken>()
    private var busyArmJob: Job? = null

    /** The slow-pass rescue timers, one per in-flight pass: each fires
     *  [onSlowPass] when ITS OWN pass has been in flight past
     *  [OCR_SLOW_PROMPT_MS] — while the user is staring at the wait, not
     *  after it. Per-token, not one shared timer, so overlapping passes
     *  cannot misattribute one pass's age to a younger survivor (review
     *  finding); a pass's finally cancels exactly its own timer. The
     *  session-wide [slowPassFired] latch keeps the callback once-only. */
    private val slowPassJobs = mutableMapOf<OcrPassToken, Job>()
    private var slowPassFired = false

    /** Register an in-flight live OCR pass; hand the token back to
     *  [endOcrPass] from the pass's finally. */
    fun beginOcrPass(displayId: Int): OcrPassToken {
        val token = OcrPassToken(displayId)
        busyPasses.add(token)
        if (busyPasses.size == 1) {
            busyArmJob?.cancel()
            busyArmJob = scope.launch {
                delay(OCR_BUSY_GRACE_MS)
                if (busyPasses.isNotEmpty()) setIconsBusy(true)
            }
        }
        if (!slowPassFired) {
            slowPassJobs[token] = scope.launch {
                delay(OCR_SLOW_PROMPT_MS)
                // Reaching here means THIS pass ran the full threshold
                // (endOcrPass cancels the timer with the pass). The set
                // check is a belt against ordering surprises only.
                if (!slowPassFired && token in busyPasses) {
                    slowPassFired = true
                    onSlowPass(token.displayId)
                }
            }
        }
        return token
    }

    fun endOcrPass(token: OcrPassToken) {
        slowPassJobs.remove(token)?.cancel()
        // A token absent from the set is a pass that outlived its session
        // ([dispose] cleared it) — inert by construction.
        if (!busyPasses.remove(token)) return
        if (busyPasses.isEmpty()) {
            busyArmJob?.cancel()
            busyArmJob = null
            setIconsBusy(false)
        }
    }

    private fun setIconsBusy(busy: Boolean) {
        CaptureBackendResolver.activeOverlayUi?.setIconsBusy(busy)
    }

    // ── Teardown ─────────────────────────────────────────────────────────

    /** Tear the session down atomically: every timer dies with [scope], the
     *  chip leaves the screen, the icons stop breathing, and outstanding
     *  pass tokens become inert. Idempotent. */
    fun dispose() {
        scope.cancel()
        removeChip()
        busyPasses.clear()
        slowPassJobs.clear()
        setIconsBusy(false)
    }

    companion object {
        /** How long a live OCR pass may run before the icons start
         *  breathing. Above typical fast-device cycles (quick passes never
         *  flicker the icon), well under the multi-second passes slow
         *  devices experience — the population the signal exists for. */
        const val OCR_BUSY_GRACE_MS = 700L

        /** No-probe starts show the label-only chip only when warm-up is
         *  still running after this grace. */
        const val CHIP_GRACE_MS = 300L

        /** Chip leak guard — fires only when no completed first pass ever
         *  removed the card. Sized above worst-case probe (~3.4s) + warm-up
         *  join cap (8s) + a slow first pass, which all now legitimately
         *  live inside the card's window. */
        const val CHIP_HARD_CAP_MS = 25_000L

        /** Warm-up join cap — a hang guard, not policy; warmUpEngine always
         *  returns on every healthy path. */
        const val WARMUP_JOIN_CAP_MS = 8_000L

        /** Pre-grab blink freshness cap. The hide composites into the
         *  whole-display mirror, so this resolves in a frame or two; a
         *  timeout falls back to exclusion for that pass. */
        const val BLINK_FRESH_CAP_MS = 600L

        /** Pre-grab settle for sources with no delivery signal
         *  (accessibility screenshots): time for the hide to reach the
         *  compositor before the screenshot reads the window state. */
        const val BLINK_A11Y_SETTLE_MS = 200L

        /** In-flight duration past which a pass counts as SLOW and the
         *  rescue prompt may fire ([onSlowPass]). The gap between fast and
         *  slow devices is seconds-scale bimodal, so the threshold is not
         *  delicate; well above [OCR_BUSY_GRACE_MS] so the breathe always
         *  precedes the prompt. */
        const val OCR_SLOW_PROMPT_MS = 2_000L
    }
}
