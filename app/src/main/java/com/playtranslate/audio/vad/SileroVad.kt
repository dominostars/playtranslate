package com.playtranslate.audio.vad

import android.content.Context
import com.playtranslate.mnn.MnnInterpreter
import com.playtranslate.mnn.MnnModule
import java.io.Closeable
import java.io.File

/**
 * Silero VAD (v6.0 `silero_vad_16k_op15.onnx`, MIT — see
 * `assets/vad/SILERO_LICENSE.txt`) converted to MNN with the pinned 3.5.0
 * converter and run through [MnnModule] (its LSTMs lower to While subgraphs,
 * which the Session API can't execute; host parity vs onnxruntime: max prob
 * diff 8.5e-7 over 100 state-carried chunks).
 *
 * The 16k-only export: 512-sample chunks with a 64-sample carried context
 * prepended (the reference wrapper's contract), LSTM state `[2,1,128]`
 * carried between chunks, `sr` a scalar int input pinned to 16000. One
 * output probability per chunk = one per [FRAME_MS] of audio.
 *
 * Stateful and NOT thread-safe — one instance per scoring pass; [close] when
 * done. Model bytes are copied from assets to a versioned file on first use
 * (Module::load wants a path).
 */
internal class SileroVad private constructor(private val module: MnnModule) : Closeable {

    /** Speech probability per [FRAME_MS] frame of [samples16k] (mono float
     *  PCM in -1..1 at 16 kHz). The trailing partial chunk is zero-padded,
     *  matching the reference wrapper. */
    fun probabilities(samples16k: FloatArray): FloatArray {
        var state = FloatArray(2 * 1 * 128)
        val context = FloatArray(CONTEXT)
        val input = FloatArray(CONTEXT + CHUNK)
        val nChunks = (samples16k.size + CHUNK - 1) / CHUNK
        val probs = FloatArray(nChunks)
        for (c in 0 until nChunks) {
            System.arraycopy(context, 0, input, 0, CONTEXT)
            val from = c * CHUNK
            val n = minOf(CHUNK, samples16k.size - from)
            System.arraycopy(samples16k, from, input, CONTEXT, n)
            if (n < CHUNK) input.fill(0f, CONTEXT + n, CONTEXT + CHUNK)
            val out = module.forward(
                listOf(
                    MnnInterpreter.NamedTensor(
                        "input", intArrayOf(1, CONTEXT + CHUNK),
                        MnnInterpreter.TensorData.Floats(input.copyOf()),
                    ),
                    MnnInterpreter.NamedTensor(
                        "state", intArrayOf(2, 1, 128),
                        MnnInterpreter.TensorData.Floats(state),
                    ),
                    MnnInterpreter.NamedTensor(
                        "sr", intArrayOf(),
                        MnnInterpreter.TensorData.Ints(intArrayOf(SAMPLE_RATE)),
                    ),
                ),
            )
            probs[c] = (out[0].data as MnnInterpreter.TensorData.Floats).data[0]
            state = (out[1].data as MnnInterpreter.TensorData.Floats).data
            System.arraycopy(input, input.size - CONTEXT, context, 0, CONTEXT)
        }
        return probs
    }

    override fun close() = module.close()

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHUNK = 512
        private const val CONTEXT = 64

        /** Audio covered by one probability frame: 512 / 16000 s. */
        const val FRAME_MS = 32L

        private const val ASSET = "vad/silero_vad_16k.mnn"

        /** Versioned cache name — bump alongside the asset so an updated
         *  model can't be shadowed by a stale copy. */
        private const val CACHED = "silero_vad_16k_v6.mnn"

        fun open(ctx: Context): SileroVad {
            val dir = File(ctx.filesDir, "vad").apply { mkdirs() }
            val model = File(dir, CACHED)
            if (!model.exists()) {
                val tmp = File(dir, "$CACHED.tmp")
                ctx.assets.open(ASSET).use { ins ->
                    tmp.outputStream().use { ins.copyTo(it) }
                }
                if (!tmp.renameTo(model)) {
                    tmp.delete()
                    check(model.exists()) { "VAD model copy failed" }
                }
            }
            return SileroVad(
                MnnModule.fromFile(
                    model.absolutePath,
                    inputNames = listOf("input", "state", "sr"),
                    outputNames = listOf("output", "stateN"),
                ),
            )
        }
    }
}
