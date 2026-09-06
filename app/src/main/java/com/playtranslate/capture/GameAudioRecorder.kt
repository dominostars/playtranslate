package com.playtranslate.capture

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.playtranslate.CaptureService
import com.playtranslate.PlayTranslateApplication
import com.playtranslate.Prefs
import com.playtranslate.audio.GameAudioClip
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10

private const val TAG = "GameAudioRecorder"

/**
 * Rolling recording of the game's audio mix (AudioPlaybackCapture riding the
 * MediaProjection session) so sentence cards can attach the real voice line.
 * Opt-in via [Prefs.recordGameAudio]; one instance per [CaptureService].
 *
 * Lifecycle is a single [reconcile] entry point, called from the same
 * push-points that drive the rest of the capture system (consent grants,
 * activate/deactivate, backend swaps, the settings toggle, and activity
 * resume/pause). The recorder runs iff ALL of:
 *  - the opt-in pref is on,
 *  - the capture session is active ([CaptureLifecycle.isSessionActive] —
 *    deliberately NOT [CaptureLifecycle.isActive]: that one composes in
 *    floating-icon visibility for control surfaces, and a hidden icon
 *    (post-boot suppression, Hide for Now) must not stop the ring while
 *    hotkey card-making still mines it),
 *  - screen-record consent is held ([MediaProjectionController.hasConsent] —
 *    the recorder never prompts; it consumes consent acquired by the existing
 *    flows, including the accessibility backend's live-start borrow),
 *  - RECORD_AUDIO is granted,
 *  - no card-flow activity is foreground (the ring freezes the moment the
 *    Anki review/trim screens open, so an editing session can't churn the
 *    buffer and evict the very line being trimmed).
 *
 * The ring (180 s, mono 44.1 kHz PCM16 ≈ 15.9 MB) is allocated on the first
 * start after opt-in and SURVIVES pause/stop — a card-open snapshot must work
 * while the reader is paused, and audio captured minutes ago must survive a
 * transient pause (the mining loop can trail a voice line by minutes). The
 * cost is a splice seam in the waveform where a pause happened; for a
 * manually-trimmed buffer that is visible but harmless. Writes pass a
 * [SilenceGate]: contiguous EXACT-ZERO audio beyond 2 s is dropped, so the
 * 180 s of capacity skews toward retained sound — quiet stretches the
 * CARD_FLOW_PAUSE freeze doesn't cover (game paused behind another app,
 * music-less menus) stretch the ring's wall-clock coverage instead of
 * consuming it. Nonzero-but-quiet audio is never dropped, whatever its
 * level (see the gate's kdoc). Our own playback
 * never appears in the ring — the capture config excludes our uid (see
 * [start]; the manifest-level opt-out is off-limits on the Thor).
 */
class GameAudioRecorder(
    private val service: CaptureService,
    private val controller: MediaProjectionController,
) {

    companion object {
        const val SAMPLE_RATE = 44_100
        const val RING_SECONDS = 180

        /** Card-flow activities whose foreground presence pauses the reader.
         *  Simple names, not class refs — matched against
         *  [PlayTranslateApplication.resumedActivitySimpleName]. Deliberately
         *  NOT "any PlayTranslate activity": on dual-screen devices
         *  MainActivity is foreground more or less permanently on the second
         *  display, and pausing on it would kill recording outright. */
        private val CARD_FLOW_PAUSE = setOf(
            "AnkiPermissionActivity",
            "SentenceAnkiReviewActivity",
            "WordAnkiReviewActivity",
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val lock = Any()
    private var ring = ShortArray(0)
    private var writePos = 0
    private var framesWritten = 0L

    /** Wall-clock ↔ ring-frame mapping ([RingClock]); [lock]-guarded except
     *  [RingClock.markGap], which only the reader thread touches. */
    private val clock = RingClock(SAMPLE_RATE, RING_SECONDS * SAMPLE_RATE)
    @Volatile private var record: AudioRecord? = null
    private var readerThread: Thread? = null
    @Volatile private var shouldRun = false
    private var teardownRegistered = false

    @Volatile var running = false
        private set

    /**
     * Health, for the display surfaces ([GameAudioGate.recording]): the gate
     * WANTED the recorder running, a start was attempted, and it is not
     * running. Set at the end of [reconcileOnMain] and by a reader-thread
     * death ([onReaderDied]) until its automatic restart settles. Displays
     * read this rather than [running] so the deliberate card-flow pause never
     * flaps them (a pause makes the run unwanted, which reads healthy), and
     * transient failures heal through the same reconcile that retries them.
     * Closes the 2026-07-22 residual — a dead recorder behind passing gates
     * showed "Recording audio" — which the arrow glyph made too visible to
     * keep (Codex adversarial find 2026-09-05).
     */
    @Volatile var startFailed = false
        private set

    /** Observers of [startFailed] TRANSITIONS — the Settings sheet while it
     *  is resumed, mirroring the teardown listener it holds on the
     *  projection controller for consent loss, so the audio row learns of a
     *  reader death the same push-style way it learns of a revoked token
     *  instead of waiting for its next own refresh (Codex adversarial find
     *  2026-09-05). Main-thread only, like the controller's list; fires only
     *  when the bit actually flips, never per reconcile. The floating icon
     *  does not use this — it rides [CaptureService.syncIconState]. */
    private val healthListeners = mutableListOf<() -> Unit>()
    fun addHealthListener(listener: () -> Unit) { healthListeners += listener }
    fun removeHealthListener(listener: () -> Unit) { healthListeners -= listener }

    /** The ONE write path for [startFailed] (main thread): notifies
     *  [healthListeners] on a change, snapshotting the list since a listener
     *  may unregister itself as it runs. */
    private fun setStartFailed(failed: Boolean) {
        if (startFailed == failed) return
        startFailed = failed
        healthListeners.toList().forEach { it() }
    }

    /** One automatic restart per push-point-initiated run: a reader death
     *  restarts the recorder once; a restart whose reader dies again waits
     *  for the next push-point ([reconcile]) instead of looping against a
     *  dead audio server. Main-thread state. */
    private var autoRetried = false

    /** How [start] obtains the MediaProjection an AudioRecord attaches to —
     *  the controller's audio hook. Replaceable in tests so a start failure
     *  ("no projection could be materialized", the shape of a dead token or
     *  a dead audio path) can be produced without a real projection, which
     *  Robolectric cannot supply: its platform stub rejects the
     *  mediaProjection foreground-service type inside the hook and the
     *  existing catch invalidates the consent, so the gates never stay green
     *  long enough to observe the failure any other way. */
    @VisibleForTesting
    internal var projectionSource: () -> MediaProjection? = { controller.projectionForAudioCapture() }

    private val onProjectionTeardown: () -> Unit = {
        // Fires on main, after the controller has already stopped the
        // projection — the AudioRecord is delivering end-of-stream by now.
        stop("projection ended")
    }

    /** Re-evaluate the run gate and start/stop accordingly. Safe from any
     *  thread ([CaptureLifecycle.deactivate] is documented
     *  safe-from-any-context); the work always runs on main, where the
     *  controller's listener list and consent state live. */
    fun reconcile() {
        if (Looper.myLooper() === Looper.getMainLooper()) reconcileOnMain(fromPushPoint = true)
        else mainHandler.post { reconcileOnMain(fromPushPoint = true) }
    }

    /** Last logged gate verdict — the verdict line is the primary field
     *  diagnostic for "why isn't it recording", so it logs on every change
     *  (not every reconcile — the activity push-point fires constantly). */
    private var lastVerdict: String? = null

    private fun reconcileOnMain(fromPushPoint: Boolean) {
        if (fromPushPoint) autoRetried = false
        val ctx = service.applicationContext
        val pref = Prefs(ctx).recordGameAudio
        val active = CaptureLifecycle.isSessionActive(ctx)
        val consent = controller.hasConsent
        val perm = GameAudioGate.micGranted(ctx)
        val pausedBy = PlayTranslateApplication.resumedActivitySimpleName()
            ?.takeIf { it in CARD_FLOW_PAUSE }
        val wantsRun = pref && active && consent && perm && pausedBy == null
        val verdict = "run=$wantsRun pref=$pref sessionActive=$active " +
            "consent=$consent recordPerm=$perm pausedBy=$pausedBy"
        if (verdict != lastVerdict) {
            lastVerdict = verdict
            Log.i(TAG, "reconcile: $verdict")
        }
        if (wantsRun && !running) start()
        else if (!wantsRun && running) stop("reconcile: gate closed")
        // Health for the displays: wanted, attempted, not running. Computed
        // AFTER start/stop so it reflects this attempt; the card-flow pause
        // reads healthy by construction (pause ⇒ !wantsRun).
        val failedNow = wantsRun && !running
        if (failedNow && !startFailed) Log.w(TAG, "wanted but not running after the start attempt: displays read failed")
        setStartFailed(failedNow)
        // The floating icon's glyph reads the same gate inputs (consent, mic —
        // [GameAudioGate]) plus [startFailed]; push it from here, AFTER
        // start/stop, so a consent the start attempt just invalidated
        // (FGS-type rejection) is already reflected. Every seam that moves
        // those inputs reaches this reconcile, which is what makes the
        // glyph's freshness structural.
        service.syncIconState()
    }

    /** Reader-thread death (a read error such as the audio server dying —
     *  not a [stop], which clears shouldRun first): stop, mark unhealthy so
     *  the displays stop claiming audio, and restart ONCE per push-point run
     *  through the ordinary reconcile — the retry loop this class already
     *  is. A restart whose reader dies again stays failed until the next
     *  push-point rather than spinning. Called on the reader thread; the
     *  bookkeeping hops to main where the rest of the state lives. */
    private fun onReaderDied(reason: String) {
        stop(reason)
        mainHandler.post {
            setStartFailed(true)
            if (!autoRetried) {
                autoRetried = true
                Log.i(TAG, "reader died ($reason): one automatic restart")
                reconcileOnMain(fromPushPoint = false)
            } else {
                Log.w(TAG, "reader died again after the automatic restart ($reason): waiting for the next push-point")
                service.syncIconState()
            }
        }
    }

    /** Main-thread only (via [reconcileOnMain]). The permission is checked by
     *  the gate; the SuppressLint covers AudioRecord.Builder's lint contract. */
    @SuppressLint("MissingPermission")
    private fun start() {
        val projection = projectionSource() ?: run {
            Log.w(TAG, "start skipped: no projection (consent token dead?)")
            return
        }
        // Self-exclusion lives HERE, not in the manifest, and the rule kind
        // is load-bearing (Thor-confirmed 2026-07-11): this capture registers
        // an audio POLICY MIX, and with usage-matching rules + the manifest
        // opt-out our own USAGE_MEDIA tracks were matched by our own mix yet
        // barred from entering it — audioserver routed them nowhere (G db =
        // -inf, silent previews). An exclude-uid mix never matches our
        // tracks at all, so they render normally while everything else
        // capturable (the game: USAGE_GAME/MEDIA) still lands in the ring.
        // Matching and excluding rules can't be combined, so exclude-only;
        // usage-matching kept as fallback if an OEM rejects it — accepting
        // that on Thor-like ROMs that fallback re-silences our previews.
        val config = runCatching {
            AudioPlaybackCaptureConfiguration.Builder(projection)
                .excludeUid(android.os.Process.myUid())
                .build()
        }.getOrElse {
            Log.w(TAG, "excludeUid config rejected (${it.message}); using usage matching")
            AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
        }
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val rec = try {
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(format)
                .setBufferSizeInBytes(SAMPLE_RATE * 2) // 1 s of PCM16 mono
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord build failed: ${e.message}")
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            Log.e(TAG, "AudioRecord failed to initialize")
            return
        }
        try {
            rec.startRecording()
        } catch (e: Exception) {
            rec.release()
            Log.e(TAG, "startRecording failed: ${e.message}")
            return
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release()
            Log.e(TAG, "AudioRecord did not enter RECORDING state")
            return
        }
        synchronized(lock) {
            // Allocated once per opt-in; deliberately NOT reset on restart —
            // see the class kdoc on pause/resume ring survival.
            if (ring.isEmpty()) {
                ring = ShortArray(RING_SECONDS * SAMPLE_RATE)
                writePos = 0
                framesWritten = 0
                clock.reset()
            }
        }
        record = rec
        shouldRun = true
        running = true
        if (!teardownRegistered) {
            controller.addTeardownListener(onProjectionTeardown)
            teardownRegistered = true
        }
        readerThread = Thread({ readerLoop(rec) }, "GameAudioRecorder").also { it.start() }
        Log.i(TAG, "recording started (${RING_SECONDS}s ring)")
    }

    /** Idempotent. Safe from main (reconcile, teardown listener) and from the
     *  reader thread's own error path. The ring is kept — snapshots keep
     *  working while paused/stopped. */
    fun stop(reason: String) {
        val thread: Thread?
        val rec: AudioRecord?
        synchronized(lock) {
            if (!running && record == null) return
            shouldRun = false
            running = false
            thread = readerThread
            readerThread = null
            rec = record
            record = null
        }
        // Unblock a pending read() first, then wait the reader out — unless
        // we ARE the reader (its failure path lands here).
        rec?.let { try { it.stop() } catch (_: Exception) {} }
        if (thread != null && thread !== Thread.currentThread()) thread.join(1000)
        rec?.release()
        // The teardown listener stays registered across pause/resume; it is
        // only detached in destroy(). removeTeardownListener is main-only,
        // and stop() can run on the reader thread — deferring detach to
        // destroy() (always main) avoids the hop entirely.
        Log.i(TAG, "recording stopped: $reason")
    }

    /** Service-teardown path (main thread): stop, detach from the controller,
     *  and free the ring. */
    fun destroy() {
        stop("service destroyed")
        if (teardownRegistered) {
            controller.removeTeardownListener(onProjectionTeardown)
            teardownRegistered = false
        }
        synchronized(lock) { ring = ShortArray(0) }
    }

    private fun readerLoop(rec: AudioRecord) {
        val chunk = ShortArray(SAMPLE_RATE / 10) // 100 ms
        // Write-time silence collapse ([SilenceGate]): per-run state — a
        // fresh run after pause/resume starts a fresh 2 s budget, which is
        // fine because the pause itself already splices the waveform there.
        val gate = SilenceGate(SAMPLE_RATE)
        // Periodic level line: distinguishes "capturing real audio" from
        // "running but the game's audio is opted out of capture" (silence)
        // without any UI. One line per ~15 s.
        var windowPeak = 0
        var windowFrames = 0
        // The pause/stop that ended the previous reader run spliced the
        // timeline; this run's first write re-anchors it.
        clock.markGap()
        while (shouldRun) {
            val n = rec.read(chunk, 0, chunk.size)
            if (n <= 0) {
                // 0 = stall, negative = error. A normal stop() unblocks the
                // read via rec.stop() and clears shouldRun first; anything
                // else is a death the displays must learn about.
                if (shouldRun) onReaderDied("read returned $n")
                return
            }
            var chunkPeak = 0
            for (i in 0 until n) {
                val a = abs(chunk[i].toInt())
                if (a > chunkPeak) chunkPeak = a
            }
            if (chunkPeak > windowPeak) windowPeak = chunkPeak
            val keep = gate.admit(chunkPeak, n)
            if (keep > 0) synchronized(lock) {
                if (!shouldRun) return
                clock.beforeWrite(framesWritten, keep, System.currentTimeMillis())
                var p = writePos
                for (i in 0 until keep) {
                    ring[p] = chunk[i]
                    p++
                    if (p == ring.size) p = 0
                }
                writePos = p
                framesWritten += keep
            }
            // Dropped frames = wall time the ring never saw (a splice): the
            // next admitted chunk must re-anchor the clock.
            if (keep < n) clock.markGap()
            windowFrames += n
            if (windowFrames >= SAMPLE_RATE * 15) {
                val db =
                    if (windowPeak == 0) Double.NEGATIVE_INFINITY
                    else 20 * log10(windowPeak / 32768.0)
                Log.i(
                    TAG,
                    "capturing: buffered=${minOf(framesWritten, ring.size.toLong()) / SAMPLE_RATE}s " +
                        "peak15s=${"%.0f".format(db)}dB " +
                        "droppedSilence=${gate.droppedFrames / SAMPLE_RATE}s",
                )
                windowPeak = 0
                windowFrames = 0
            }
        }
    }

    /**
     * A frozen per-card snapshot: the WAV plus, when the card flow supplied
     * a launch anchor (the sentence's History/result wall time), where that
     * moment sits inside the file. [anchorOffsetMs] is null either because
     * no anchor was requested or because it [anchorMissed] — the anchor
     * predates the ring's oldest retained audio, i.e. the line's audio is
     * provably NOT in this snapshot.
     */
    class RingSnapshot(
        val file: File,
        val anchorOffsetMs: Long?,
        val anchorMissed: Boolean,
    )

    /**
     * Freeze the ring's current contents into a FRESH per-card snapshot file
     * ([GameAudioSnapshot.newFile]) as a mono PCM16 WAV — immutable once
     * written; the calling card flow owns (and deletes) it. Works while
     * paused or stopped (the ring survives). [anchorWallMs] (epoch ms — the
     * launching surface's capture/display moment for the sentence) maps
     * through [RingClock] into an offset within the snapshot. Returns null
     * when less than half a second has been captured. Blocking; call on
     * Dispatchers.IO.
     */
    fun snapshotToFile(anchorWallMs: Long? = null): RingSnapshot? {
        val pcm: ShortArray
        var anchorOffsetMs: Long? = null
        synchronized(lock) {
            val available =
                if (ring.isEmpty()) 0
                else minOf(framesWritten, ring.size.toLong()).toInt()
            if (available < SAMPLE_RATE / 2) return null
            pcm = ShortArray(available)
            val start = (writePos - available).mod(ring.size)
            val firstLen = minOf(available, ring.size - start)
            ring.copyInto(pcm, 0, start, start + firstLen)
            if (firstLen < available) ring.copyInto(pcm, firstLen, 0, available - firstLen)
            if (anchorWallMs != null) {
                val snapStartFrame = framesWritten - available
                anchorOffsetMs = clock.frameFor(anchorWallMs)
                    ?.takeIf { it >= snapStartFrame }
                    ?.let { (it.coerceAtMost(framesWritten) - snapStartFrame) * 1000 / SAMPLE_RATE }
            }
        }
        val anchorMissed = anchorWallMs != null && anchorOffsetMs == null
        var out: File? = null
        return try {
            // Creation sits inside the try: exclusive-create can throw
            // (disk full), and snapshot failure means "no recording", not
            // a crash in the card-open coroutine.
            out = GameAudioSnapshot.newFile(service)
            GameAudioSnapshot.sweepOrphans(service)
            writeWav(pcm, out)
            Log.i(
                TAG,
                "snapshot: ${pcm.size / SAMPLE_RATE}s → ${out.name} " +
                    "peak=${GameAudioClip.peakDbfs(pcm)}dB " +
                    "tail5s=${GameAudioClip.peakDbfs(pcm, pcm.size - 5 * SAMPLE_RATE, pcm.size)}dB " +
                    "anchor=" + when {
                        anchorWallMs == null -> "none"
                        anchorMissed -> "missed(pre-ring)"
                        else -> "${anchorOffsetMs}ms"
                    },
            )
            RingSnapshot(out, anchorOffsetMs, anchorMissed)
        } catch (e: Exception) {
            Log.e(TAG, "snapshot write failed", e)
            out?.delete()
            null
        }
    }

    /** Streamed WAV write (64 KB chunks) — keeps peak memory flat instead of
     *  materializing a second ~16 MB copy next to the PCM. */
    private fun writeWav(pcm: ShortArray, out: File) {
        val dataBytes = pcm.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataBytes)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)                  // PCM fmt chunk size
            putShort(1)                 // PCM
            putShort(1)                 // mono
            putInt(SAMPLE_RATE)
            putInt(SAMPLE_RATE * 2)     // byte rate
            putShort(2)                 // block align
            putShort(16)                // bits per sample
            put("data".toByteArray())
            putInt(dataBytes)
        }
        FileOutputStream(out).use { fos ->
            fos.write(header.array())
            val buf = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
            var pos = 0
            while (pos < pcm.size) {
                buf.clear()
                val n = minOf(buf.capacity() / 2, pcm.size - pos)
                buf.asShortBuffer().put(pcm, pos, n)
                fos.write(buf.array(), 0, n * 2)
                pos += n
            }
        }
    }
}
