package com.playtranslate.audio

import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * In-place loudness normalization for 16-bit little-endian PCM.
 *
 * Human recordings (e.g. Wikimedia Commons / Lingua Libre) have no level
 * standard and are often very quiet, so they're inaudible on quieter outputs
 * and inconsistent clip-to-clip. We boost each clip toward a consistent speech
 * RMS, with a `tanh` soft peak limiter so the boost can exceed the available
 * peak headroom without hard clipping. We only ever boost — clips already at or
 * above target are left untouched. RMS is a deliberately lightweight loudness
 * proxy (no K-weighting); it's plenty for short word clips and is device- and
 * routing-independent, which is what makes playback predictable everywhere.
 */
internal object Loudness {

    /** ~ -18 dBFS RMS — a clearly-audible speech level. */
    private const val TARGET_RMS = 0.126
    /** +24 dB ceiling on boost, so a near-silent clip can't amplify its noise floor to garbage. */
    private const val MAX_GAIN = 15.85
    /** ~ -0.45 dBFS soft-limit ceiling. */
    private const val CEILING = 0.95
    private const val FULL = 32768.0

    fun normalize(data: ByteArray) {
        val n = data.size / 2
        if (n == 0) return

        var sumSq = 0.0
        var peak = 0
        for (i in 0 until n) {
            val s = sampleAt(data, i)
            val a = if (s < 0) -s else s
            if (a > peak) peak = a
            sumSq += s.toDouble() * s.toDouble()
        }
        if (peak == 0) return // pure silence

        val rmsNorm = sqrt(sumSq / n) / FULL
        if (rmsNorm <= 0.0) return
        val gain = (TARGET_RMS / rmsNorm).coerceAtMost(MAX_GAIN)
        if (gain <= 1.0) return // already loud enough; never attenuate

        for (i in 0 until n) {
            val x = sampleAt(data, i) / FULL * gain
            val limited = CEILING * tanh(x / CEILING)
            val out = (limited * FULL).toInt().coerceIn(-32768, 32767)
            data[2 * i] = (out and 0xFF).toByte()
            data[2 * i + 1] = ((out shr 8) and 0xFF).toByte()
        }
    }

    /** Signed 16-bit LE sample at frame [i]. */
    private fun sampleAt(data: ByteArray, i: Int): Int =
        (data[2 * i + 1].toInt() shl 8) or (data[2 * i].toInt() and 0xFF)
}
