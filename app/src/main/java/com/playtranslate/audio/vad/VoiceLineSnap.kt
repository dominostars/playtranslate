package com.playtranslate.audio.vad

import android.content.Context
import android.util.Log
import com.playtranslate.audio.GameAudioClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "VoiceLineSnap"

/**
 * Phase-2 refinement of the anchor-seeded trim default: run [SileroVad] over
 * the SEEDED WINDOW ONLY — the anchor's neighborhood, never the whole 3 min
 * snapshot — and return the voice line the anchor names, in snapshot-ms.
 *
 * Window: [WINDOW_PRE_MS] back from the anchor (bounds the user's
 * line-to-shutter reaction time, which no timestamp can see) plus
 * [WINDOW_POST_MS] forward (a line still playing at the anchor). Best-effort
 * by contract: every failure path returns null and the caller keeps the
 * timestamp-seeded default — a card flow must never break on VAD.
 */
internal object VoiceLineSnap {

    const val WINDOW_PRE_MS = 30_000L
    const val WINDOW_POST_MS = 5_000L

    /** What the scan found, all in snapshot-ms: the [line] the anchor names
     *  (the snap target) plus every speech [segments] region in the scanned
     *  window — the trim view paints those so the user can see where voice
     *  is even after they take the handles over. */
    class Result(
        val line: SpeechSnap.Segment,
        val segments: List<SpeechSnap.Segment>,
    )

    /**
     * Scan the anchor's window in the snapshot [wav] of [durationMs], or
     * null when no speech was found there / anything failed. Heavy (PCM
     * read + ~1k model chunks); call off-main.
     */
    suspend fun snap(
        ctx: Context,
        wav: File,
        anchorOffsetMs: Long,
        durationMs: Long,
    ): Result? = withContext(Dispatchers.Default) {
        // arm64-only (the :mnn .so) — the same gate every MNN-backed tier
        // applies (OnDeviceLlmBackend.supportsRequiredAbi; Bergamot adds a
        // binary-translation check on top, BergamotBackend.supportsNativeRuntime).
        // On the app's 32-bit slice loadLibrary throws UnsatisfiedLinkError,
        // and best-effort decoration must skip, not crash.
        if (!android.os.Process.is64Bit()) return@withContext null
        try {
            val winStart = (anchorOffsetMs - WINDOW_PRE_MS).coerceAtLeast(0)
            val winEnd = (anchorOffsetMs + WINDOW_POST_MS).coerceAtMost(durationMs)
            if (winEnd - winStart < 1_000) return@withContext null
            val rate = GameAudioClip.sampleRate(wav)
            val pcm = GameAudioClip.readPcmRange(wav, winStart, winEnd)
            if (pcm.isEmpty()) return@withContext null
            val samples16k = resampleTo16k(pcm, rate)
            val t0 = android.os.SystemClock.elapsedRealtime()
            val probs = SileroVad.open(ctx).use { it.probabilities(samples16k) }
            val segments = SpeechSnap.segments(probs, SileroVad.FRAME_MS, winEnd - winStart)
            val snapped = SpeechSnap.snap(segments, anchorOffsetMs - winStart)
            Log.i(
                TAG,
                "window=${winStart}..${winEnd}ms anchor=${anchorOffsetMs}ms " +
                    "segments=${segments.size} " +
                    "snap=${snapped?.let { "${winStart + it.startMs}..${winStart + it.endMs}ms" } ?: "none"} " +
                    "in ${android.os.SystemClock.elapsedRealtime() - t0}ms",
            )
            snapped?.let { s ->
                Result(
                    line = SpeechSnap.Segment(winStart + s.startMs, winStart + s.endMs),
                    segments = segments.map {
                        SpeechSnap.Segment(winStart + it.startMs, winStart + it.endMs)
                    },
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "snap failed (keeping seeded default): ${e.message}")
            null
        } catch (e: LinkageError) {
            // A native-load failure the ABI gate didn't predict (corrupt or
            // missing .so, partial install). Same contract as any other
            // failure here: keep the seeded default, never crash the card.
            Log.w(TAG, "snap failed (native runtime unavailable): ${e.message}")
            null
        }
    }

    /** Background-scan block size. Independent fresh-state passes — Silero
     *  warms within a couple of frames, and per-block independence is what
     *  lets the scan walk in viewer-priority order and emit as it goes. */
    private const val BLOCK_MS = 30_000L

    /** Blocks shorter than this aren't worth a pass (sub-line slivers). */
    private const val MIN_BLOCK_MS = 500L

    /**
     * Scan everything OUTSIDE `[excludedStartMs, excludedEndMs)` (the window
     * [snap] already covered — pass `[durationMs, durationMs)` to scan the
     * whole file, tail-first) in [BLOCK_MS] blocks, nearest-to-the-window
     * first: backward toward 0, then forward to the end. Emits each block's
     * speech segments (snapshot-ms) via [onSegments] as it lands — the
     * caller accumulates with [SpeechSnap.merge]. Highlight-only by
     * contract: this never picks or moves a selection. Best-effort like
     * [snap]: failures log and stop, keeping whatever was already emitted.
     * Cancellation is cooperative between blocks (scope cancel on card
     * close stops the scan within one block).
     */
    suspend fun scanRemainder(
        ctx: Context,
        wav: File,
        durationMs: Long,
        excludedStartMs: Long,
        excludedEndMs: Long,
        onSegments: suspend (List<SpeechSnap.Segment>) -> Unit,
    ) = withContext(Dispatchers.Default) {
        // Same gate as [snap] — the unanchored path arrives here directly.
        if (!android.os.Process.is64Bit()) return@withContext
        val blocks = ArrayList<Pair<Long, Long>>()
        var back = excludedStartMs.coerceIn(0, durationMs)
        while (back > 0) {
            val start = (back - BLOCK_MS).coerceAtLeast(0)
            blocks.add(start to back)
            back = start
        }
        var fwd = excludedEndMs.coerceIn(0, durationMs)
        while (fwd < durationMs) {
            val end = (fwd + BLOCK_MS).coerceAtMost(durationMs)
            blocks.add(fwd to end)
            fwd = end
        }
        if (blocks.isEmpty()) return@withContext
        val t0 = android.os.SystemClock.elapsedRealtime()
        var scannedBlocks = 0
        var totalSegments = 0
        try {
            val rate = GameAudioClip.sampleRate(wav)
            SileroVad.open(ctx).use { vad ->
                for ((blockStart, blockEnd) in blocks) {
                    kotlinx.coroutines.yield()
                    if (blockEnd - blockStart < MIN_BLOCK_MS) continue
                    val pcm = GameAudioClip.readPcmRange(wav, blockStart, blockEnd)
                    if (pcm.isEmpty()) continue
                    val probs = vad.probabilities(resampleTo16k(pcm, rate))
                    val segments = SpeechSnap
                        .segments(probs, SileroVad.FRAME_MS, blockEnd - blockStart)
                        .map { SpeechSnap.Segment(blockStart + it.startMs, blockStart + it.endMs) }
                    scannedBlocks++
                    totalSegments += segments.size
                    if (segments.isNotEmpty()) onSegments(segments)
                }
            }
            Log.i(
                TAG,
                "remainder scan: blocks=$scannedBlocks segments=$totalSegments " +
                    "excluded=${excludedStartMs}..${excludedEndMs}ms of ${durationMs}ms " +
                    "in ${android.os.SystemClock.elapsedRealtime() - t0}ms",
            )
        } catch (e: Exception) {
            Log.w(TAG, "remainder scan stopped after $scannedBlocks blocks: ${e.message}")
        } catch (e: LinkageError) {
            Log.w(TAG, "remainder scan unavailable (native runtime): ${e.message}")
        }
    }

    /** Linear-interpolation resample to 16 kHz mono float in -1..1 — VAD
     *  input, never played back, so interpolation quality is a non-issue. */
    private fun resampleTo16k(pcm: ShortArray, srcRate: Int): FloatArray {
        if (srcRate == SileroVad.SAMPLE_RATE) {
            return FloatArray(pcm.size) { pcm[it] / 32768f }
        }
        val n = (pcm.size.toLong() * SileroVad.SAMPLE_RATE / srcRate).toInt()
        val ratio = srcRate.toDouble() / SileroVad.SAMPLE_RATE
        return FloatArray(n) { i ->
            val src = i * ratio
            val i0 = src.toInt().coerceAtMost(pcm.size - 1)
            val i1 = (i0 + 1).coerceAtMost(pcm.size - 1)
            val frac = (src - i0).toFloat()
            (pcm[i0] * (1f - frac) + pcm[i1] * frac) / 32768f
        }
    }
}
