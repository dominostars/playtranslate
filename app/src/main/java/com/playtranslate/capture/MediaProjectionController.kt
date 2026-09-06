package com.playtranslate.capture

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Display
import com.playtranslate.CaptureService
import com.playtranslate.DetectionLog
import com.playtranslate.PlayTranslateTileService
import com.playtranslate.Prefs
import com.playtranslate.displaySizePx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import androidx.core.graphics.createBitmap

private const val TAG = "MediaProjectionCtl"

/**
 * What the MediaProjection stream contains — resolved once per consent
 * session by [MediaProjectionController.resolveStreamKind].
 *
 * [CLEAN]: the user picked "a single app" in the API 34+ consent dialog. The
 * stream mirrors only that task's surface subtree — this app's overlay
 * windows, system UI, and every other app are structurally absent (never
 * composited into the output). [CONTAMINATED]: whole-display capture — the
 * stream shows everything, including our own overlays; the world every
 * pre-34 session lives in. [UNKNOWN]: not resolved this session (no consent
 * yet, or an API ≤ 33 device where nothing ever asks). Consumers must treat
 * UNKNOWN exactly like CONTAMINATED — CLEAN is the only value that changes
 * behavior.
 */
enum class StreamKind { UNKNOWN, CONTAMINATED, CLEAN }

/**
 * Owns the MediaProjection session — the consent token, the [MediaProjection],
 * a per-resolution [VirtualDisplay], and the [ImageReader] frames are pulled
 * from. One instance per [CaptureService].
 *
 * Consent is secured up front via [ensureConsent] — by `startLive()` before
 * the live-mode loop exists, by the one-shot capture path
 * ([MediaProjectionCaptureSource.requestClean]), or by the Settings /
 * Quick-Settings activate path — never lazily from inside a capture.
 * [captureFrame] requires consent to
 * already be held and returns null otherwise; it never launches the dialog. (A
 * prompt mid-loop has its Cancel tap caught by the live-mode touch sentinel as
 * game input, restarting the loop and re-prompting in a cycle.) Once granted,
 * the session is kept warm for the process lifetime — MediaProjection tokens
 * can't be persisted, so a process restart or a user revoke needs fresh
 * consent.
 *
 * MediaProjection captures the display the projection was authorized for —
 * always the default display ([projectedDisplayId]); it can't target an
 * arbitrary display the way the accessibility backend's `takeScreenshot` can.
 * [captureFrame] always captures [projectedDisplayId].
 */
class MediaProjectionController(private val service: CaptureService) {

    private val mainHandler = Handler(Looper.getMainLooper())

    // Session fields are touched from the consent-result callback, the
    // suspend capture path, and the projection teardown callback — @Volatile
    // gives every reader (notably hasConsent, polled off-main through
    // CaptureLifecycle) the latest write. Visibility only; no compound update.
    @Volatile private var resultCode: Int = Activity.RESULT_CANCELED
    @Volatile private var resultData: Intent? = null
    @Volatile private var projection: MediaProjection? = null
    @Volatile private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var imageReader: ImageReader? = null
    @Volatile private var readerW = 0
    @Volatile private var readerH = 0

    /** Non-null while a consent dialog is in flight; every concurrent
     *  [captureFrame] awaits the same gate so only one dialog shows. */
    @Volatile private var consentGate: CompletableDeferred<Boolean>? = null

    // ── Frame stream: delivery seq + latest-frame latch ──────────────────
    //
    // The mirrored VirtualDisplay composites a frame into the ImageReader only
    // when the display content changes. An OnImageAvailableListener (on a
    // dedicated HandlerThread) latches the newest Image and advances a
    // monotonic delivery seq. Captures then serve the latched frame directly:
    // "latched frame + delivery silence since" IS the current screen, which
    // retires acquireBitmap's delay(64)/delay(48) freshness dance for raw
    // captures and gives clean captures a provable post-blank frame.
    //
    // Threading: the listener is the ONLY caller of acquireLatestImage (the
    // single-consumer rule — a second consumer would race it for frames).
    // Image lifetime is reference-counted (see [LatchedFrame]): the latch
    // holds one reference, each in-flight decode holds another, and the
    // Image closes exactly when the count reaches zero. Consumers PEEK the
    // latch (non-destructive) rather than claiming it.

    /** A delivered frame plus its reference count. The latch itself holds
     *  one reference from the moment the frame is latched; every consumer
     *  that peeks it holds another for the duration of its decode. The Image
     *  closes exactly when the count reaches zero — displacement by a newer
     *  delivery (or teardown) drops the latch's reference, and an in-flight
     *  decode keeps the buffer alive until its own release.
     *
     *  This makes reads NON-DESTRUCTIVE: the newest frame stays servable to
     *  every consumer until a newer delivery replaces it. The prior design
     *  (getAndSet(null) claims) let a one-shot clean capture steal the only
     *  frame a parked live cycle had been woken for — on a static screen no
     *  replacement ever came, and live retried into an empty latch until the
     *  next real screen change (2026-07-10 review finding). */
    private class LatchedFrame(val image: Image, val seq: Long) {
        private val refs = java.util.concurrent.atomic.AtomicInteger(1)

        /** Take a reference. Fails only when the count already hit zero —
         *  the frame was displaced and closed between the caller's
         *  latch.get() and this call; re-read the latch for its successor. */
        fun acquire(): Boolean {
            while (true) {
                val r = refs.get()
                if (r <= 0) return false
                if (refs.compareAndSet(r, r + 1)) return true
            }
        }

        fun release() {
            if (refs.decrementAndGet() == 0) {
                try { image.close() } catch (_: Exception) {}
            }
        }
    }

    /** Run [block] with this (already-acquired) frame, releasing the
     *  reference in a finally — cancellation or exceptions inside [block],
     *  including the suspending decode, can never leak it. This bracket is
     *  the ONLY sanctioned way to hold a frame across a suspension point:
     *  the drain loop this replaced held an acquired reference in a local
     *  across an awaited flow, and every budget-timeout cancellation leaked
     *  one of the reader's three Image slots permanently. */
    private suspend fun <T> LatchedFrame.use(block: suspend (LatchedFrame) -> T): T =
        try {
            block(this)
        } finally {
            release()
        }

    private val latch = AtomicReference<LatchedFrame?>(null)

    /** Monotonic delivery counter, doubling as the wake signal: collectors of
     *  the flow wake on every advance. Advanced by the listener per delivered
     *  frame, and once by [teardown] so a consumer parked in awaitSeqAfter
     *  re-checks its world instead of sleeping on a dead stream. */
    private val deliverySeq = MutableStateFlow(0L)

    /** Seq of the frame most recently served to a RAW capture caller. Clean
     *  captures deliberately don't advance this — see [DeliverySignal]. */
    @Volatile private var lastServedSeq = 0L

    @Volatile private var frameThread: HandlerThread? = null
    @Volatile private var frameHandler: Handler? = null

    // Step-0 characterization counters. Written on the frame thread
    // (deliveredTotal) and the capture path (served counts, serialized by the
    // capture source's mutex); read for the 5s summary on the frame thread.
    @Volatile private var deliveredTotal = 0L
    @Volatile private var rawServedCount = 0L
    @Volatile private var cleanServedCount = 0L
    @Volatile private var rawFailedCount = 0L
    @Volatile private var cleanFailedCount = 0L
    @Volatile private var lastFailReason = ""
    private var summaryLastDelivered = 0L
    private var summaryMsSinceLog = 0L

    /** The delivery-signal surface handed to [MediaProjectionCaptureSource]. */
    val deliverySignal: DeliverySignal = object : DeliverySignal {
        override fun seqNow(): Long = deliverySeq.value
        override val lastServedSeq: Long
            get() = this@MediaProjectionController.lastServedSeq
        override suspend fun awaitSeqAfter(seq: Long) {
            deliverySeq.first { it > seq }
        }
    }

    /** Current delivery seq — the pre-blank marker clean captures pass to
     *  [captureFrameNewerThan]. */
    val deliverySeqNow: Long get() = deliverySeq.value

    private val frameListener = ImageReader.OnImageAvailableListener { reader ->
        val img = try {
            reader.acquireLatestImage()
        } catch (e: IllegalStateException) {
            // Two documented causes, both transient here: the reader was
            // closed under us (teardown race), or maxImages is momentarily
            // exhausted (one claimed in-flight + one latched + this one).
            // Drop the frame WITHOUT advancing the seq — a frame nobody could
            // observe must not count as a delivery.
            null
        } ?: return@OnImageAvailableListener
        val seq = deliverySeq.updateAndGet { it + 1 }
        deliveredTotal++
        latch.getAndSet(LatchedFrame(img, seq))?.release()
    }

    private fun ensureFrameHandler(): Handler {
        frameHandler?.let { return it }
        val t = HandlerThread("PtCaptureFrames").also { it.start() }
        frameThread = t
        val h = Handler(t.looper)
        frameHandler = h
        h.postDelayed(summaryRunnable, SUMMARY_INTERVAL_MS)
        return h
    }

    /** 5s delivery-rate summary while the stream is alive, debug-gated. Runs
     *  on the frame thread; the DetectionLog write is posted to main because
     *  its ring buffer is only ever touched from there. */
    private val summaryRunnable = object : Runnable {
        override fun run() {
            val total = deliveredTotal
            val delta = total - summaryLastDelivered
            summaryLastDelivered = total
            // Log when something happened this window, or once per minute as
            // a heartbeat proving the stream is alive-but-silent.
            summaryMsSinceLog += SUMMARY_INTERVAL_MS
            val heartbeat = summaryMsSinceLog >= 60_000L
            if ((delta > 0 || heartbeat) && Prefs(service).debugLiveMode) {
                summaryMsSinceLog = 0
                val failed = rawFailedCount + cleanFailedCount
                val failSuffix =
                    if (failed > 0) " failed=$rawFailedCount/$cleanFailedCount last=\"$lastFailReason\""
                    else ""
                val line = "MP stream: +$delta deliveries/${SUMMARY_INTERVAL_MS / 1000}s " +
                    "(total=$total rawServed=$rawServedCount cleanServed=$cleanServedCount$failSuffix)"
                mainHandler.post { DetectionLog.log(line) }
            }
            frameHandler?.postDelayed(this, SUMMARY_INTERVAL_MS)
        }
    }

    /** True once the user has granted a token still valid for this process. */
    val hasConsent: Boolean get() = resultData != null

    /** The display this backend can capture. MediaProjection's
     *  `createScreenCaptureIntent()` only ever projects the default display,
     *  so capture, OCR, and overlays under this backend all stay on it — there
     *  is no API to mirror a secondary display. */
    val projectedDisplayId: Int = Display.DEFAULT_DISPLAY

    // ── Stream kind + captured-content state (API 34+ single-app capture) ─

    /** What this session's stream contains. Resolved by [resolveStreamKind];
     *  UNKNOWN until then and again after [teardown] (the choice is per
     *  consent). Consumers treat UNKNOWN like CONTAMINATED. */
    @Volatile var streamKind: StreamKind = StreamKind.UNKNOWN
        private set

    private val _contentVisible = MutableStateFlow(true)
    /** Whether the captured content is currently visible. Only a single-app
     *  session ever flips this: the platform hides the mirrored surface (the
     *  stream goes black, not frozen) when the captured task leaves the
     *  foreground, and `onCapturedContentVisibilityChanged` reports it at the
     *  transition. Whole-display capture never becomes invisible. Reset to
     *  true on teardown. */
    val contentVisible: StateFlow<Boolean> get() = _contentVisible

    private val _contentSize = MutableStateFlow<Point?>(null)
    /** Size of the captured region in px, from `onCapturedContentResize` —
     *  task bounds (`WindowMetrics#getBounds()` semantics) in a single-app
     *  session, display bounds for whole-display capture. Null until the
     *  first callback (which fires right after capture begins on API 34+;
     *  never on API ≤ 33). A value equal to the display size means the frame
     *  maps 1:1 onto screen coordinates; anything else means the content is
     *  letterboxed into the frame and its on-screen offset is unknowable
     *  (no public API), so consumers must pause rather than misplace. */
    val contentSize: StateFlow<Point?> get() = _contentSize

    /** Observers notified right after a teardown drops the held consent — a
     *  runtime transition no pref watcher could see. The Settings sheet
     *  registers one to re-render rows that reflect consent state. */
    private val teardownListeners = mutableListOf<() -> Unit>()

    fun addTeardownListener(listener: () -> Unit) { teardownListeners += listener }
    fun removeTeardownListener(listener: () -> Unit) { teardownListeners -= listener }

    /** Delivered by [MediaProjectionConsentActivity]. Completes any pending
     *  [consentGate] so suspended [captureFrame] calls resume.
     *
     *  A grant marks the backend activated HERE — the one choke point all
     *  grants flow through — but ONLY while the MediaProjection backend is
     *  the active one. On the MP backend, granting the dialog IS the user
     *  saying "on": the lazy prompts (in-app Translate button / live toggle,
     *  via requestClean / startLive) must flip the lifecycle state exactly
     *  as they did when "active" was the consent token itself, or the tile
     *  and Settings would report Off while a just-granted session captures
     *  (adversarial-review round 2). On the accessibility backend the same
     *  dialog means something narrower — "borrow the MP stream for this
     *  live session" ([CaptureService.startLive]'s wantMpStreamConsent) —
     *  and must NOT write MP-backend lifecycle state that nothing on that
     *  backend reads or clears (round 3: the stale flag would resurface as
     *  a phantom "on" after a later swap into MP). Together with
     *  [CaptureBackendResolver.reresolve] destroying any borrowed session
     *  at the swap boundary, the invariant is per-backend: on the MP
     *  backend, hasConsent ⇒ activated; the converse — activated without
     *  consent — is the deliberate post-revoke state. Set BEFORE the gate
     *  completes so resumed callers observe it. */
    fun onConsentResult(resultCode: Int, data: Intent?) {
        val granted = resultCode == Activity.RESULT_OK && data != null
        if (granted) {
            this.resultCode = resultCode
            this.resultData = data
            if (!CaptureBackendResolver.active().requiresAccessibilityService) {
                service.mediaProjectionActivated = true
            }
        }
        val gate = consentGate
        consentGate = null
        gate?.complete(granted)
        // Every consent grant flows through here — MP-backend Turn On AND the
        // accessibility live-start borrow — so this is the one push-point that
        // covers "consent arrived" for the game-audio recorder.
        if (granted) service.reconcileGameAudio()
    }

    /**
     * Ensure a MediaProjection consent token is held, prompting the user via
     * [MediaProjectionConsentActivity] when it isn't. Returns true once consent
     * is granted. Safe to call with consent already held — returns true with no
     * prompt; concurrent callers share the single in-flight dialog.
     */
    suspend fun ensureConsent(): Boolean {
        if (hasConsent) return true
        return requestConsent()
    }

    /**
     * Capture one frame of the projected display ([projectedDisplayId]) at its
     * current resolution. Lazily establishes the projection + virtual display,
     * but NOT consent — consent must already be held (see [ensureConsent]);
     * returns null without prompting if it isn't. Returns null on any capture
     * failure too. Call on the main thread — the heavy pixel copy is moved off
     * it internally.
     *
     * Serves the latched frame when one exists — the most recent composition
     * the mirror delivered, which delivery silence proves is still current —
     * with no freshness delay. Falls back to awaiting the first delivery
     * (bounded by [FRESHNESS_BUDGET_MS]) right after VirtualDisplay creation.
     */
    suspend fun captureFrame(): Bitmap? =
        captureNewerThan(minSeq = 0L, advanceCursor = true)?.first

    /**
     * Clean-capture variant: serve the NEWEST latched frame once one exists
     * with seq > [minSeq] — the seq the caller observed BEFORE blanking its
     * overlays, so the blank's own repaint always qualifies (anchoring after
     * the blank starved static screens; 2026-07-10 review finding).
     *
     * Take-newest, no quiescence: an earlier revision "drained" toward
     * stream silence to prove the blank's repaint had been delivered, but a
     * quiet window never arrives on continuously-animating content — games,
     * this app's entire domain — so the budget expired holding a perfectly
     * good frame and every hold over live gameplay failed (2026-07-10
     * incident). The newest frame above the anchor is served as-is. The
     * residual risk is the shipped app's own: a frame composited pre-blank
     * but delivered post-anchor can win when the blank's repaint delivery
     * lags the caller's vsync wait — rare, and self-healing at the next
     * attempt, where the drain's failure mode was deterministic on exactly
     * the content users hold over.
     *
     * On budget expiry with nothing above the anchor this fails (null)
     * rather than serving a frame that provably predates the blank.
     * Callers with nothing blanked should use [captureFrameUngated]
     * instead of paying the gate at all.
     *
     * Does not advance the raw-consumer cursor (see [DeliverySignal]).
     */
    suspend fun captureFrameNewerThan(minSeq: Long): Bitmap? =
        captureNewerThan(minSeq, advanceCursor = false)?.first

    /** Current frame with no freshness gate and no raw-cursor advance — the
     *  clean-capture path for "nothing was blanked": the frame provably
     *  cannot contain this backend's overlays, and no blank repaint is
     *  coming, so gating would only burn the budget. Distinct from
     *  [captureFrame], which advances the live delivery-gate cursor and
     *  would eat a parked live cycle's pending wake. */
    suspend fun captureFrameUngated(): Bitmap? =
        captureFrameUngatedWithSeq()?.first

    /** [captureFrameUngated] plus the served frame's delivery seq — stamped
     *  in the same latch swap that published the frame. Freshness proofs
     *  MUST compare against this, never correlate [deliverySeqNow] with a
     *  separate capture read: the frame listener advances the seq before it
     *  publishes the latch, so two racing reads can flag a stale frame as
     *  fresh — absence evidence the probe would trust (round-23 review
     *  finding). */
    suspend fun captureFrameUngatedWithSeq(): Pair<Bitmap, Long>? =
        captureNewerThan(minSeq = 0L, advanceCursor = false)

    private suspend fun captureNewerThan(
        minSeq: Long,
        advanceCursor: Boolean,
    ): Pair<Bitmap, Long>? {
        val clean = !advanceCursor
        if (!ensureProjection()) return noteFailure(clean, "no projection (consent lost?)")
        val (w, h) = captureSize(projectedDisplayId)
            ?: return noteFailure(clean, "display size unavailable")
        ensureVirtualDisplay(w, h) ?: return noteFailure(clean, "virtual display unavailable")
        lastPeekRefusal = null

        // Deadline-as-decision, not deadline-as-abort: each pass serves the
        // newest qualifying frame the instant one exists; the budget only
        // bounds how long we wait for a delivery when none does. An acquired
        // reference never crosses a suspension point outside [use] — the
        // await below runs with no reference held.
        val deadline = android.os.SystemClock.uptimeMillis() + FRESHNESS_BUDGET_MS
        while (true) {
            // The wake baseline is read BEFORE the latch peek. A delivery
            // landing between the peek and the await must still satisfy the
            // await's current-value check (StateFlow.first serves the current
            // value), so the loop re-peeks and serves it. Reading the baseline
            // after the peek let that delivery raise the baseline past itself
            // — permanently invisible — and on a screen that goes quiet right
            // after (the settled screen every one-shot targets, with the
            // blank's own repaint as the last delivery) the budget expired
            // with the qualifying frame sitting in the latch (Thor
            // 2026-07-20: "no delivery above anchor=N … latest=N+1").
            val observed = deliverySeq.value
            val f = peekLatest(w, h)
            if (f != null && f.seq > minSeq) {
                return f.use { lf -> decode(lf, w, h, advanceCursor)?.let { it to lf.seq } }
            }
            f?.release() // at-or-below the anchor — leave latched for others
            val remaining = deadline - android.os.SystemClock.uptimeMillis()
            if (remaining <= 0) {
                return noteFailure(
                    clean,
                    "no delivery above anchor=$minSeq in ${FRESHNESS_BUDGET_MS}ms " +
                        "(latest=${deliverySeq.value} latched=${latch.get()?.seq} " +
                        "refused=$lastPeekRefusal)"
                )
            }
            // A timed-out await falls through to one more peek instead of
            // failing directly: every 2026-07-21 field failure showed the
            // awaited delivery landing AT the deadline (latched == latest ==
            // anchor+1 in the failure line) — on a static screen the panel's
            // idle-refresh collapse quantizes the blank's repaint to ~100ms
            // ticks, so the timeout can lose a photo-finish to the very
            // delivery it was waiting for. The deadline check above is the
            // sole exit; after a timeout the recomputed remaining is ≤ 0
            // (timeouts never fire early), so this cannot spin.
            withTimeoutOrNull(remaining) { deliverySeq.first { it > observed } }
        }
    }

    /** Serializes the decode step across ALL callers. The latch is
     *  non-destructive, so several holders can decode the SAME [Image] —
     *  and `Image.Plane.getBuffer()` hands every holder one cached
     *  ByteBuffer whose position a concurrent rewind()/copy would race
     *  (corrupted bitmaps or a thrown copy). This lives HERE, with the
     *  owner of the shared state, deliberately: the capture source's
     *  captureMutex serializes whole capture SEQUENCES (blank → read →
     *  restore) and remains required for that, but an unserialized caller
     *  — the stream-kind probe was one (2026-07-10 review finding) — must
     *  be structurally unable to corrupt a concurrent decode. Held only
     *  for the ~ms buffer copy, never across delivery waits. */
    private val decodeMutex = Mutex()

    /** Decode [frame] to a Bitmap and account the serve. Callers must hold
     *  the frame via [LatchedFrame.use]. */
    private suspend fun decode(
        frame: LatchedFrame,
        w: Int,
        h: Int,
        advanceCursor: Boolean,
    ): Bitmap? {
        beginDecode()
        return try {
            decodeMutex.withLock {
                withContext(Dispatchers.Default) { imageToBitmap(frame.image, w, h) }
            }.also {
                if (advanceCursor) {
                    lastServedSeq = maxOf(lastServedSeq, frame.seq)
                    rawServedCount++
                } else {
                    cleanServedCount++
                }
            }
        } catch (e: CancellationException) {
            // MUST rethrow, never count: live modes cancel their cycle jobs
            // mid-capture by design (input kick, visibility hide). Swallowing
            // the cancellation here let the cancelled cycle return normally
            // and launch its successor — forking a second, untracked cycle
            // chain next to the canceller's replacement, alive until mode
            // stop — and logged every cancel as a capture failure in the
            // counters that exist to catch REAL silent failures.
            throw e
        } catch (e: Exception) {
            noteFailure(clean = !advanceCursor, reason = "decode: ${e.message}")
        } finally {
            endDecode()
        }
    }

    /** Count and log a capture failure. The counters surface in the 5s
     *  debug summary — a capture layer that fails silently costs days
     *  (2026-07-10: every hold over animated content failed for a day with
     *  `cleanServed=1` as the only trace). Always returns null. */
    private fun noteFailure(clean: Boolean, reason: String): Nothing? {
        if (clean) cleanFailedCount++ else rawFailedCount++
        lastFailReason = reason
        Log.w(TAG, "capture failed (${if (clean) "clean" else "raw"}): $reason")
        return null
    }

    // ── Decode-vs-close serialization (review finding) ───────────────────
    //
    // imageToBitmap copies the claimed Image's buffer on Dispatchers.Default.
    // Captures hold the source's captureMutex, but teardown() (projection
    // revoke posts onProjectionLost to main) does not — ImageReader.close()
    // while the copy is mid-buffer is a native use-after-free, not a
    // catchable exception. Reader closes therefore defer while a decode is
    // in flight and complete on the decoding thread when it finishes. The
    // resize-swap close can't actually race (it runs under the same mutex as
    // every decode) but routes through the same guard for uniformity.

    private val decodeLock = Any()
    private var decodesInFlight = 0
    private val deferredReaderCloses = mutableListOf<ImageReader>()

    private fun beginDecode() {
        synchronized(decodeLock) { decodesInFlight++ }
    }

    private fun endDecode() {
        var toClose: List<ImageReader>? = null
        synchronized(decodeLock) {
            decodesInFlight--
            if (decodesInFlight == 0 && deferredReaderCloses.isNotEmpty()) {
                toClose = deferredReaderCloses.toList()
                deferredReaderCloses.clear()
            }
        }
        toClose?.forEach { it.close() }
    }

    /** Close [reader] now, or after the in-flight decode finishes. */
    private fun closeReaderSafely(reader: ImageReader?) {
        reader ?: return
        val closeNow: Boolean
        synchronized(decodeLock) {
            closeNow = decodesInFlight == 0
            if (!closeNow) deferredReaderCloses.add(reader)
        }
        if (closeNow) reader.close()
    }

    /** Peek the latched frame if it matches the current capture size, taking
     *  a reference the caller MUST [LatchedFrame.release]. Non-destructive:
     *  the frame stays latched for other consumers (live + one-shot share
     *  the newest frame). A mismatched frame (pre-resize straggler) is left
     *  for the next delivery to displace; a frame whose reader was closed
     *  under it (late straggler swept by a swap) throws on the size read and
     *  reads as no-frame. The retry bound covers acquire() losing a race to
     *  displacement — the re-read observes the successor frame. */
    private fun peekLatest(w: Int, h: Int): LatchedFrame? {
        repeat(4) {
            val f = latch.get() ?: return null
            if (!f.acquire()) return@repeat // displaced under us — re-read
            // The Image reports the BUFFER's dimensions, which are not
            // guaranteed to match the reader's configured size: the mirror's
            // producer (SurfaceFlinger) can emit buffers at the mirrored
            // display's live geometry, e.g. during Thor's rotation blips,
            // where WindowMetrics and the SF layer stack disagree about
            // display 0 (2026-07-21 field failures: every frame refused).
            // -1×-1 = the size read threw (reader closed under a swap).
            val (iw, ih) = try {
                f.image.width to f.image.height
            } catch (e: IllegalStateException) {
                -1 to -1
            }
            if (iw == w && ih == h) return f
            lastPeekRefusal = "seq=${f.seq} image=${iw}x$ih want=${w}x$h"
            f.release()
            return null
        }
        return null
    }

    /** The most recent size-refusal [peekLatest] made, surfaced in the
     *  budget-expiry failure line — the datum that separates "delivery never
     *  came" from "deliveries came but none were servable at the expected
     *  geometry". Best-effort forensics; cleared at each gated-capture
     *  entry so a stale refusal can't masquerade as this capture's. */
    @Volatile private var lastPeekRefusal: String? = null

    /** Game-audio hook ([GameAudioRecorder]): turn the held consent into a
     *  live projection — no VirtualDisplay involved — and return it so an
     *  AudioPlaybackCapture AudioRecord can attach to the same session screen
     *  capture uses. Promotes the FGS type first via [ensureProjection], same
     *  contract as the frame path. Returns null when consent isn't held or
     *  the token is dead; never prompts. The caller must not stop() the
     *  returned projection — the session stays owned by this controller. */
    fun projectionForAudioCapture(): MediaProjection? =
        if (ensureProjection()) projection else null

    private fun ensureProjection(): Boolean {
        if (projection != null) return true
        // captureFrame never prompts — consent is secured up front by
        // ensureConsent() (startLive / the activate path). A loop reaching
        // here without consent means a mid-session revoke; fail so the
        // caller's checkConsentLost stops live mode, rather than the dialog
        // re-appearing every frame.
        if (!hasConsent) return false
        // API 34+: the foreground service must already carry the
        // mediaProjection type before getMediaProjection() is called.
        service.ensureMediaProjectionForegroundType()
        val mgr = service.applicationContext
            .getSystemService(MediaProjectionManager::class.java) ?: return false
        val data = resultData ?: return false
        val proj = try {
            mgr.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed: ${e.message}")
            null
        }
        if (proj == null) {
            // The held consent token couldn't be turned into a session and is
            // now useless. Drop it (and refresh the UI) so the next capture
            // re-prompts, instead of looping forever on a dead token that
            // still reads as hasConsent == true. getMediaProjection returns
            // nullable on API 35+ (the signature was annotated nullable in
            // compileSdk 35); pre-35 it only signaled failure via exception.
            onProjectionLost()
            return false
        }
        // The callback must be registered before createVirtualDisplay. The
        // content callbacks exist since API 34 and are never invoked below
        // it; they run on [mainHandler], so the StateFlow writes and the
        // DetectionLog ring buffer are both touched from main only.
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { onProjectionLost() }
            override fun onCapturedContentResize(width: Int, height: Int) {
                _contentSize.value = Point(width, height)
                DetectionLog.log("MP content resize: ${width}x$height")
            }
            override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                _contentVisible.value = isVisible
                DetectionLog.log("MP content visible: $isVisible")
            }
        }, mainHandler)
        projection = proj
        return true
    }

    /**
     * Resolve what this session's stream contains, once per consent. On API
     * ≤ 33 the consent dialog has no single-app option, so the answer is
     * CONTAMINATED by construction (no probe). On 34+ there is no public API
     * exposing the user's choice, so it is measured empirically by
     * [StreamKindProbe]: a small full-alpha window is drawn and the mirror
     * checked for it — present ⇒ whole-display (CONTAMINATED), absent across
     * a pattern swap ⇒ task capture (CLEAN). Every ambiguous outcome resolves
     * to UNKNOWN — which is never cached, and which a live-mode start settles
     * terminally instead of running on ([CaptureService.settleUnknownStreamKind]:
     * accessibility-backend sessions drop the grant and fall back to
     * accessibility capture; MP-only sessions ask the user, who knows the
     * answer, via [assertStreamKind]). One-shot paths keep the conservative
     * treat-as-contaminated default and re-measure per attempt. Requires consent to
     * be held; returns UNKNOWN (without caching) when it isn't. A measured
     * verdict is cached until [teardown] — the choice can differ on every
     * fresh consent — and only while the consent that was measured is still
     * alive: a teardown DURING the probe resets [streamKind] to UNKNOWN,
     * and caching the stale result over that reset would poison the next
     * session (adversarial-review finding).
     */
    suspend fun resolveStreamKind(
        probeSurface: StreamKindProbe.ProbeSurface? = null,
    ): StreamKind {
        streamKind.takeIf { it != StreamKind.UNKNOWN }?.let { return it }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            streamKind = StreamKind.CONTAMINATED
            return streamKind
        }
        if (!hasConsent) return StreamKind.UNKNOWN
        // Exactly one probe at a time. Two resolvers can race here (startLive
        // vs a clean capture right after consent), and concurrent probes are
        // NOT independent: both windows sit at the same coordinates, so on a
        // contaminated stream the occluded one reads the topmost one's
        // checker — and a cadence alignment that shows it the same phase two
        // rounds running scores as absence evidence and caches a false CLEAN
        // (the sign-agnostic ledger retired the old "probe twice and agree"
        // reasoning). Waiters re-check under the lock and adopt the winner's
        // cached verdict without probing again.
        return streamKindMutex.withLock {
            streamKind.takeIf { it != StreamKind.UNKNOWN }?.let { return@withLock it }
            if (!hasConsent) return@withLock StreamKind.UNKNOWN
            var kind = StreamKindProbe.measure(this, probeSurface)
            if (kind == StreamKind.UNKNOWN && hasConsent) {
                // One retry: the probe is the SOLE classifier, so a transient
                // abort (layout/draw timing, buffer contention) would otherwise
                // consign the whole session to the degraded tier. The log line
                // is the field abort-rate signal — this app has no telemetry,
                // so exported logs are the only place the rate can ever show up.
                // A reused surface re-anchors itself in armPattern — the
                // retry must not trust the first call's add-time anchors.
                val second = StreamKindProbe.measure(this, probeSurface)
                DetectionLog.log(
                    "MP stream kind: retry after UNKNOWN → $second"
                )
                kind = second
            }
            if (kind == StreamKind.UNKNOWN || !hasConsent) {
                if (kind != StreamKind.UNKNOWN) {
                    DetectionLog.log("MP stream kind: $kind discarded — consent died mid-probe")
                }
                return@withLock StreamKind.UNKNOWN
            }
            streamKind = kind
            // A verdict can be measured MID-SESSION (a one-shot's clean
            // capture resolving after startLive settled UNKNOWN). Route the
            // change through the same rebuild diff as a verdict reset, so a
            // task stream doesn't keep the pinhole tier until the user
            // restarts live mode (review finding).
            service.onStreamKindChanged()
            kind
        }
    }

    /**
     * Record the USER'S OWN ANSWER to the capture-scope question — the
     * terminal fallback when [resolveStreamKind] could not measure and the
     * MediaProjection stream is the session's only pixel source (see
     * [CaptureService.settleUnknownStreamKind]). The user knows what they
     * picked; there is no API. Same trust model and lifetime as a measured
     * verdict: session-scoped, dies with the consent in
     * [resetSessionVerdict]. A wrong answer reproduces exactly the
     * corresponding probe misverdict — pinhole flap for a false
     * CONTAMINATED, self-echo churn for a false CLEAN — with the same
     * recovery (restart live mode → fresh probe), so no new failure class
     * enters the system; the `user-asserted` provenance in the log line is
     * what lets an exported log tell which classifier was wrong.
     *
     * Validated under the probe's own mutex: a MEASURED verdict that landed
     * while the dialog was up wins (measurement outranks recollection), and
     * an answer arriving after the consent died is discarded — a stale
     * assertion must not poison the next session (the same mid-probe
     * teardown discipline [resolveStreamKind] applies). Returns the verdict
     * now in effect; UNKNOWN means the assertion was discarded.
     */
    suspend fun assertStreamKind(kind: StreamKind): StreamKind {
        if (kind == StreamKind.UNKNOWN) return streamKind
        return streamKindMutex.withLock {
            streamKind.takeIf { it != StreamKind.UNKNOWN }?.let { return@withLock it }
            if (!hasConsent) return@withLock StreamKind.UNKNOWN
            streamKind = kind
            DetectionLog.log("MP stream kind: $kind (user-asserted)")
            service.onStreamKindChanged()
            kind
        }
    }

    /** Serializes [resolveStreamKind]'s measure+cache — see the comment at
     *  its lock site for why concurrent probes can poison each other. */
    private val streamKindMutex = Mutex()

    /**
     * CLEAN streams only: is the captured task PROVEN to fill the frame 1:1?
     * Generators of `false`, enumerated (each means box/lookup coordinates
     * CANNOT be trusted):
     *  1. The resize callback has not been delivered yet — UNPROVEN is not
     *     identity; it must never be assumed (it fires at projection start
     *     on measured devices, but that ordering is platform behavior, not
     *     a contract).
     *  2. The task is letterboxed / split-screen / freeform — its on-screen
     *     offset has no public API, so mapping is impossible, not merely
     *     unimplemented.
     *  3. A mid-transition resize (rotation, windowing change).
     * Non-CLEAN streams always return true: a display mirror IS the display.
     */
    fun frameGeometryProven(): Boolean {
        if (streamKind != StreamKind.CLEAN) return true
        val cs = _contentSize.value ?: return false
        val size = captureSize(projectedDisplayId) ?: return false
        return cs.x == size.first && cs.y == size.second
    }

    private suspend fun requestConsent(): Boolean {
        consentGate?.let { return it.await() }
        val gate = CompletableDeferred<Boolean>()
        consentGate = gate
        // If startActivity throws (e.g. a future BAL tightening blocks the
        // launch, or some OEM-specific restriction kicks in), the exception
        // would otherwise leave consentGate set on a never-completed gate,
        // wedging every subsequent ensureConsent caller on it.await(). Clear
        // the field and complete the gate=false so concurrent waiters return
        // cleanly and the NEXT activate attempt can install a fresh gate.
        try {
            MediaProjectionConsentActivity.launch(service)
        } catch (e: Exception) {
            Log.e(TAG, "MediaProjectionConsentActivity launch failed: ${e.message}")
            consentGate = null
            gate.complete(false)
            return false
        }
        return gate.await()
    }

    private fun ensureVirtualDisplay(w: Int, h: Int): ImageReader? {
        val proj = projection ?: return null
        imageReader?.let { if (readerW == w && readerH == h) return it }
        val dpi = service.resources.displayMetrics.densityDpi
        // maxImages = 3: one latched + one claimed in-flight by a capture +
        // one for the listener's acquireLatestImage swap moment. At 2 the
        // producer stalls (or the listener throws) whenever a capture holds a
        // claimed frame while a new delivery lands.
        val newReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        // Register the frame listener before the reader's surface is wired
        // into the VirtualDisplay so the very first composited frame is
        // latched rather than lost.
        newReader.setOnImageAvailableListener(frameListener, ensureFrameHandler())
        val oldReader = imageReader
        val vd = virtualDisplay
        // Android 15 (targetSdk ≥ 35) enforces stricter MediaProjection token
        // staleness — a token that getMediaProjection succeeded on can still
        // throw at createVirtualDisplay time, and the resize/setSurface
        // reuse branch can throw IllegalStateException / IllegalArgumentException
        // on a VirtualDisplay the platform has released out from under us.
        // Mirror the getMediaProjection catch above: broad Exception, log,
        // tear down so the next attempt re-prompts cleanly instead of
        // looping on a dead session.
        try {
            if (vd == null) {
                // First use of this projection — build the VirtualDisplay around
                // the new ImageReader's surface.
                virtualDisplay = proj.createVirtualDisplay(
                    "PlayTranslateCapture", w, h, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    newReader.surface, null, mainHandler,
                )
            } else {
                // Resolution changed (rotation / reconfig). API 34+ allows a
                // MediaProjection to create only ONE VirtualDisplay per token —
                // a second proj.createVirtualDisplay throws SecurityException
                // ("Cannot create more than one VirtualDisplay"). So reuse the
                // existing VirtualDisplay: resize it and swap its output Surface
                // to the new reader. setSurface first, then close the old reader
                // so the VD never targets a closed surface.
                vd.resize(w, h, dpi)
                vd.setSurface(newReader.surface)
            }
        } catch (e: Exception) {
            Log.e(TAG, "VirtualDisplay creation/update failed: ${e.message}")
            // newReader was allocated before the try block and never installed
            // — close it explicitly so a failed setup doesn't leak the reader.
            newReader.close()
            onProjectionLost()
            return null
        }
        imageReader = newReader
        readerW = w
        readerH = h
        // Straggler discipline on the swap (review finding): detach the old
        // reader's listener so late old-reader frames can't re-latch, close
        // the reader, and only THEN sweep the latch — a straggler latched
        // between a sweep and the close would otherwise survive as an
        // invalid Image. (No-op on first create.)
        oldReader?.setOnImageAvailableListener(null, null)
        closeReaderSafely(oldReader)
        latch.getAndSet(null)?.release()
        return newReader
    }

    /** Pixel size of [displayId] in its current rotation — the resolution the
     *  capture [VirtualDisplay] + [ImageReader] are built at.
     *
     *  Sourced from [displaySizePx], the same window-context `WindowMetrics`
     *  query the overlays size off — so the captured frame and the overlay
     *  coordinate space are identical by construction. The pinhole detector
     *  ([com.playtranslate.FrameCoordinates]) assumes that identity scale.
     *  `displaySizePx` already reports post-rotation bounds, so no manual
     *  rotation adjustment is needed here. */
    private fun captureSize(displayId: Int): Pair<Int, Int>? {
        val dm = service.getSystemService(DisplayManager::class.java) ?: return null
        val display = dm.getDisplay(displayId) ?: return null
        val size = service.createDisplayContext(display).displaySizePx()
        return if (size.x > 0 && size.y > 0) size.x to size.y else null
    }

    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        // A row-padded buffer needs a wider bitmap; crop back to width after.
        val padded = createBitmap(
            width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888,
        )
        // Frames are shared (non-destructive latch) and may be decoded more
        // than once; copyPixelsFromBuffer advances the buffer position, so
        // rewind first. No concurrent position race: decodes are serialized
        // by [decodeMutex] regardless of caller.
        val buf = plane.buffer
        buf.rewind()
        padded.copyPixelsFromBuffer(buf)
        return if (rowPadding == 0) padded
        else Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
    }

    private fun teardown() {
        val hadConsent = resultData != null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.setOnImageAvailableListener(null, null)
        closeReaderSafely(imageReader)
        imageReader = null
        readerW = 0
        readerH = 0
        // Latch cleanup AFTER the reader closes: a listener invocation racing
        // this teardown can re-latch right up until the reader is closed, so
        // clearing first could leak that late frame. Closing an Image whose
        // reader is already closed can itself throw on some builds — the
        // buffers are freed with the reader either way, so swallow it.
        latch.getAndSet(null)?.release()
        // Advance the seq once so anything suspended in awaitSeqAfter wakes,
        // re-checks its capture source, and discovers the session is gone —
        // instead of sleeping forever on a stream that will never deliver.
        deliverySeq.updateAndGet { it + 1 }
        frameThread?.quitSafely()
        frameThread = null
        frameHandler = null
        projection?.let { try { it.stop() } catch (_: Exception) {} }
        projection = null
        // The token is single-use on API 34+ — once the projection stops, the
        // next capture must re-prompt for consent.
        resetSessionVerdict()
        resultCode = Activity.RESULT_CANCELED
        resultData = null
        // Notify observers once consent is actually gone (resultData cleared),
        // so a listener that re-reads hasConsent sees false. Snapshot the list
        // — a listener may unregister itself as it runs.
        if (hadConsent) teardownListeners.toList().forEach { it() }
    }

    /** Drop the consent token without tearing down any live projection /
     *  VirtualDisplay / ImageReader. Used by the foreground-service-type
     *  catch in [CaptureService.enterForeground] when the platform rejects
     *  the mediaProjection FGS type: the consent that claimed the type is
     *  invalid (and the catch fires before getMediaProjection, so no live
     *  projection exists yet to tear down), so [hasConsent] should reflect
     *  that and the next capture attempt re-prompts cleanly. */
    fun invalidateConsent() {
        val hadConsent = resultData != null
        resetSessionVerdict()
        resultCode = Activity.RESULT_CANCELED
        resultData = null
        if (hadConsent) teardownListeners.toList().forEach { it() }
    }

    /** Session-scoped classifier state dies with the consent that measured
     *  it — EVERY consent-ending path ([teardown], [invalidateConsent]) must
     *  run this. The probe is the SOLE classifier: a stale CLEAN surviving
     *  into a new whole-display session would route overlay modes onto a
     *  contaminated stream with no runtime nets left to catch it
     *  (adversarial-review round 13). The next consent can make a different
     *  single-app/whole-display choice, so the verdict and the
     *  captured-content state must both re-measure. */
    private fun resetSessionVerdict() {
        streamKind = StreamKind.UNKNOWN
        _contentVisible.value = true
        _contentSize.value = null
    }

    /** The projection is gone — stopped by the system or the user (a
     *  status-bar-chip revoke, an Android 15 lock-screen auto-stop), or
     *  [getMediaProjection] / createVirtualDisplay failed to turn a held
     *  consent token into a session. Not our own [destroy]. Tear the session
     *  down and drop its overlays — hideAll, because a drag/lookup in flight
     *  at the moment of loss can otherwise leave the magnifier lens, region
     *  indicator, translation boxes, or floating menu orphaned on screen —
     *  then reconcile the floating controls back up: projection loss is NOT
     *  deactivation. [CaptureService.mediaProjectionActivated] is only
     *  cleared by an explicit Turn Off, so the icon survives the revoke and
     *  the next capture-requiring action re-prompts for the now-burned
     *  single-use consent (requestClean and startLive both route through
     *  [ensureConsent]; a running live mode is stopped by its loop's
     *  checkConsentLost). The reinstall re-runs updateForegroundState, which
     *  drops the mediaProjection FGS type back to SPECIAL_USE now that
     *  consent is gone. Same hideAll → reconcile idiom as a backend swap
     *  ([CaptureBackendResolver.reresolve]). Always invoked on the main
     *  thread (the projection callback posts to [mainHandler]; the failure
     *  path is the capture path), so the main-thread-only overlay work is
     *  safe. */
    private fun onProjectionLost() {
        teardown()
        CaptureBackendResolver.activeOverlayUi?.apply {
            hideAll()
            // freshAppearance=false: the icon was just swept and comes
            // straight back — to the user it never left, so no sonar-ping
            // replay and no game-audio consent prompt (this is how a
            // status-bar-chip stop lands; re-asking would nag right after a
            // deliberate no).
            reconcileFloatingIcons(freshAppearance = false)
        }
        PlayTranslateTileService.TileSync.refresh(service.applicationContext)
    }

    /** Release the projection and virtual display. */
    fun destroy() = teardown()

    private companion object {
        /** Bound on waiting for a delivery when the latch is empty (first
         *  capture after VD creation) or a clean capture awaits its post-blank
         *  frame. Deadline-as-decision: frames serve the instant they land, so
         *  raising this costs nothing on the common path — it only bounds the
         *  failure case. 250ms, not the legacy 112ms (inherited from the old
         *  delay(64)+delay(48) poll dance — a history, not a measurement): the
         *  2026-07-21 Thor field failures showed the blank's repaint delivery
         *  arriving right AT 112ms on static screens — idle-refresh panel
         *  collapse quantizes composition to ~100ms ticks — so the budget must
         *  clear a worst-case idle tick plus scheduling margin. */
        const val FRESHNESS_BUDGET_MS = 250L

        /** Cadence of the debug delivery-rate summary. */
        const val SUMMARY_INTERVAL_MS = 5_000L
    }
}
