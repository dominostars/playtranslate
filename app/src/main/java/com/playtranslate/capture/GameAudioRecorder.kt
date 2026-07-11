package com.playtranslate.capture

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.playtranslate.CaptureService
import com.playtranslate.PlayTranslateApplication
import com.playtranslate.Prefs
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
 *  - the capture session is active ([CaptureLifecycle.isActive]),
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
 * manually-trimmed buffer that is visible but harmless. Our own playback
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
            "GameAudioTrimActivity",
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val lock = Any()
    private var ring = ShortArray(0)
    private var writePos = 0
    private var framesWritten = 0L
    @Volatile private var record: AudioRecord? = null
    private var readerThread: Thread? = null
    @Volatile private var shouldRun = false
    private var teardownRegistered = false

    @Volatile var running = false
        private set

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
        if (Looper.myLooper() === Looper.getMainLooper()) reconcileOnMain()
        else mainHandler.post { reconcileOnMain() }
    }

    /** Last logged gate verdict — the verdict line is the primary field
     *  diagnostic for "why isn't it recording", so it logs on every change
     *  (not every reconcile — the activity push-point fires constantly). */
    private var lastVerdict: String? = null

    private fun reconcileOnMain() {
        val ctx = service.applicationContext
        val pref = Prefs(ctx).recordGameAudio
        val active = CaptureLifecycle.isActive(ctx)
        val consent = controller.hasConsent
        val perm = hasRecordPermission()
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
    }

    private fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Main-thread only (via [reconcileOnMain]). The permission is checked by
     *  the gate; the SuppressLint covers AudioRecord.Builder's lint contract. */
    @SuppressLint("MissingPermission")
    private fun start() {
        val projection = controller.projectionForAudioCapture() ?: run {
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
        // Periodic level line: distinguishes "capturing real audio" from
        // "running but the game's audio is opted out of capture" (silence)
        // without any UI. One line per ~15 s.
        var windowPeak = 0
        var windowFrames = 0
        while (shouldRun) {
            val n = rec.read(chunk, 0, chunk.size)
            if (n <= 0) {
                // 0 = stall, negative = error. A normal stop() unblocks the
                // read via rec.stop() and clears shouldRun first.
                if (shouldRun) stop("read returned $n")
                return
            }
            for (i in 0 until n) {
                val a = abs(chunk[i].toInt())
                if (a > windowPeak) windowPeak = a
            }
            synchronized(lock) {
                if (!shouldRun) return
                var p = writePos
                for (i in 0 until n) {
                    ring[p] = chunk[i]
                    p++
                    if (p == ring.size) p = 0
                }
                writePos = p
                framesWritten += n
            }
            windowFrames += n
            if (windowFrames >= SAMPLE_RATE * 15) {
                val db =
                    if (windowPeak == 0) Double.NEGATIVE_INFINITY
                    else 20 * log10(windowPeak / 32768.0)
                Log.i(
                    TAG,
                    "capturing: buffered=${minOf(framesWritten, ring.size.toLong()) / SAMPLE_RATE}s " +
                        "peak15s=${"%.0f".format(db)}dB",
                )
                windowPeak = 0
                windowFrames = 0
            }
        }
    }

    /**
     * Freeze the ring's current contents into [GameAudioSnapshot.file] as a
     * mono PCM16 WAV — the buffer a card flow trims from. Works while paused
     * or stopped (the ring survives). Returns the file, or null when less
     * than half a second has been captured. Blocking; call on Dispatchers.IO.
     */
    fun snapshotToFile(): File? {
        val pcm: ShortArray
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
        }
        val out = GameAudioSnapshot.file(service)
        return try {
            out.parentFile?.mkdirs()
            writeWav(pcm, out)
            Log.i(TAG, "snapshot: ${pcm.size / SAMPLE_RATE}s → ${out.name}")
            out
        } catch (e: Exception) {
            Log.e(TAG, "snapshot write failed", e)
            out.delete()
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
