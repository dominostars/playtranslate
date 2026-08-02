package com.playtranslate.capture

/**
 * Write-time silence gate for the game-audio ring: admits every frame of
 * audible audio and at most [MAX_SILENCE_SECONDS] of each contiguous
 * near-silent stretch, so quiet time (game paused behind another app or our
 * own non-card UI, music-less menus) stretches the ring's coverage instead
 * of evicting real dialogue from it. The retained lead-in keeps a natural
 * gap between lines — and marks line boundaries visibly in the trim
 * waveform.
 *
 * "Silence" is EXACT DIGITAL SILENCE — a chunk whose every sample is zero.
 * Playback capture is a digital path with no microphone: when nothing
 * renders (game paused, app backgrounded, empty menu) the stream is exact
 * zeros, so the zero test captures every real quiet-time scenario. Any
 * nonzero sample keeps the whole chunk, because real audio can sit
 * arbitrarily low in the PCM — in-game and emulator volume sliders
 * attenuate before the mix, so a fixed dB floor can eat genuine soft
 * speech irreversibly, before the user ever reaches trim/review
 * (adversarial-review finding). Structurally-safe beats threshold-tuned:
 * this gate cannot discard nonzero audio by construction; the price is
 * that a noisy "silent" stretch simply never gates (an accepted no-op).
 * Reopening is instant and the reopening chunk is admitted whole, so a
 * voice onset keeps its (≤ one chunk) lead-in and is never clipped.
 *
 * Deliberate consequence: ring/snapshot time is SPLICED, not wall-clock — a
 * collapsed gap plays back as [MAX_SILENCE_SECONDS]. Consumers that address
 * the ring by wall-clock (trim-seed anchoring) survive this because the
 * recorder re-anchors its [RingClock] at every drop point; selection keys
 * stay file-relative.
 */
internal class SilenceGate(
    sampleRate: Int,
    maxSilenceSeconds: Int = MAX_SILENCE_SECONDS,
    private val peakFloor: Int = PEAK_FLOOR,
) {
    private val budgetFrames = maxSilenceSeconds.toLong() * sampleRate

    /** Frames into the current contiguous near-silent stretch. */
    private var silentRun = 0L

    /** Total frames dropped this recording run — diagnostics only. */
    var droppedFrames = 0L
        private set

    /** How many leading frames of a [frames]-long chunk with peak amplitude
     *  [chunkPeak] to write to the ring. Silence within a chunk is fungible,
     *  so trimming a silent chunk from the front keeps the cap sample-exact
     *  across chunk boundaries. */
    fun admit(chunkPeak: Int, frames: Int): Int {
        if (chunkPeak >= peakFloor) {
            silentRun = 0
            return frames
        }
        val keep = (budgetFrames - silentRun).coerceIn(0L, frames.toLong()).toInt()
        silentRun += frames
        droppedFrames += frames - keep
        return keep
    }

    companion object {
        /** Cap on retained contiguous silence. */
        const val MAX_SILENCE_SECONDS = 2

        /** Exact digital silence only: any nonzero sample is audio. Raising
         *  this to a dB-style floor re-opens the eats-quiet-speech hole —
         *  don't, without on-device droppedSilence evidence that nonzero
         *  noise defeats the zero test AND a story for attenuated mixes. */
        const val PEAK_FLOOR = 1
    }
}
