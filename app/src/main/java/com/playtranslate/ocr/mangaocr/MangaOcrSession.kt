package com.playtranslate.ocr.mangaocr

import android.util.Log
import com.playtranslate.mnn.MnnInterpreter
import com.playtranslate.mnn.MnnInterpreter.NamedTensor
import com.playtranslate.mnn.MnnInterpreter.TensorData
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.Closeable
import java.io.File

/**
 * manga-ocr pipeline driver: a ViT encoder + an **autoregressive** Transformer
 * decoder (the l0wgear/manga-ocr-2025 ONNX, converted to MNN). Recognition-only —
 * a detector (Meiki or Paddle) supplies crops via the composite.
 *
 * The encoder is single-input/single-output (reuses [MnnInterpreter.run]
 * FloatArray/IntArray); the decoder is multi-IO (int `input_ids` +
 * `encoder_hidden_states` → `logits`) and is driven step-by-step here via
 * [MnnInterpreter.run] (List<NamedTensor>). Greedy decode with
 * `no_repeat_ngram_size = 3`, EOS = [SEP] — a faithful port of the desktop spike
 * (`/tmp/ocr_bakeoff/run_bakeoff.py`, verified char-identical to the reference).
 * No KV cache (the exported decoder has none): each step re-feeds the full id
 * history; cheap at column lengths (~3.9 + 0.13·L ms/step measured on the Thor).
 *
 * Emits **text only** (no char/element boxes) — drag-lookup + furigana fall back
 * to proportional placement. Not thread-safe; serialized by the composite mutex.
 */
class MangaOcrSession private constructor(
    private val encoder: MnnInterpreter,
    private val decoder: MnnInterpreter,
    private val vocab: List<String>,
) : Closeable {

    /** Recognize one crop ([cropBgr], any 3-channel Mat — grayscaled internally). */
    fun recognize(cropBgr: Mat): String {
        if (cropBgr.cols() < 2 || cropBgr.rows() < 2) return ""
        val enc = encoder.run(preprocess(cropBgr), intArrayOf(1, 3, IMG, IMG))
        val encData = enc.data  // [1, ENC_SEQ, ENC_DIM] flattened

        val ids = ArrayList<Int>(MAX_LEN + 1)
        ids += START
        for (step in 0 until MAX_LEN) {
            val outs = decoder.run(listOf(
                NamedTensor("input_ids", intArrayOf(1, ids.size), TensorData.Ints(ids.toIntArray())),
                NamedTensor("encoder_hidden_states", intArrayOf(1, ENC_SEQ, ENC_DIM), TensorData.Floats(encData)),
            ))
            val logits = (outs.first { it.data is TensorData.Floats }.data as TensorData.Floats).data
            val vocabSize = logits.size / ids.size            // [1, L, vocab] → vocab
            val base = (ids.size - 1) * vocabSize             // last timestep
            val next = nextToken(ids, logits, base, vocabSize)
            if (next == EOS) break
            ids += next
        }

        val sb = StringBuilder()
        for (i in 1 until ids.size) {                         // skip START/[CLS]
            val tok = vocab.getOrNull(ids[i]) ?: continue
            if (tok in SPECIALS) continue
            sb.append(tok)
        }
        return sb.toString().replace(" ", "")
    }

    /** Grayscale → RGB (3 identical channels), 224×224, normalized to [-1,1]. */
    private fun preprocess(cropBgr: Mat): FloatArray {
        val gray = Mat(); Imgproc.cvtColor(cropBgr, gray, Imgproc.COLOR_BGR2GRAY)
        val resized = Mat()
        Imgproc.resize(gray, resized, Size(IMG.toDouble(), IMG.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        gray.release()
        val plane = IMG * IMG
        val buf = ByteArray(plane)
        resized.get(0, 0, buf); resized.release()
        val out = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val v = ((buf[i].toInt() and 0xff) / 255f - 0.5f) / 0.5f
            out[i] = v; out[plane + i] = v; out[2 * plane + i] = v
        }
        return out
    }

    override fun close() {
        encoder.close(); decoder.close()
    }

    companion object {
        private const val TAG = "MangaOcrSession"
        private const val IMG = 224          // encoder input HxW
        private const val ENC_SEQ = 197      // encoder output sequence (196 patches + CLS)
        private const val ENC_DIM = 192      // DeiT-tiny hidden
        private const val START = 2          // [CLS]
        private const val EOS = 3            // [SEP]
        private const val MAX_LEN = 64
        private val SPECIALS = setOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]")

        /** Greedy argmax over the vocab slice at [base], skipping tokens that
         *  would form a repeated 3-gram (`no_repeat_ngram_size = 3`); falls back
         *  to the global argmax if every candidate is banned. Pure (no model
         *  state) — unit-tested in MangaOcrDecodeTest. */
        internal fun nextToken(ids: List<Int>, logits: FloatArray, base: Int, vocabSize: Int): Int {
            val banned = HashSet<Int>()
            if (ids.size >= 2) {
                val p0 = ids[ids.size - 2]; val p1 = ids[ids.size - 1]
                for (i in 0 until ids.size - 2) {
                    if (ids[i] == p0 && ids[i + 1] == p1) banned += ids[i + 2]
                }
            }
            var best = -1; var bestV = Float.NEGATIVE_INFINITY
            var fallback = 0; var fallbackV = Float.NEGATIVE_INFINITY
            for (c in 0 until vocabSize) {
                val v = logits[base + c]
                if (v > fallbackV) { fallbackV = v; fallback = c }
                if (c !in banned && v > bestV) { bestV = v; best = c }
            }
            return if (best >= 0) best else fallback
        }

        @Volatile private var cvLoaded = false
        private fun ensureOpenCv() {
            if (!cvLoaded) synchronized(this) {
                if (!cvLoaded) {
                    check(OpenCVLoader.initLocal()) { "OpenCV initLocal() failed" }
                    cvLoaded = true
                    Log.i(TAG, "OpenCV initialized: ${Core.VERSION}")
                }
            }
        }

        fun create(
            encoderPath: String,
            decoderPath: String,
            vocabPath: String,
            numThread: Int = 4,
            precision: Int = 0,
        ): MangaOcrSession {
            ensureOpenCv()
            val vocab = File(vocabPath).readLines()
            val encoder = MnnInterpreter.fromFile(encoderPath, numThread, precision)
            val decoder = MnnInterpreter.fromFile(decoderPath, numThread, precision)
            Log.i(TAG, "MangaOcrSession: enc=$encoderPath dec=$decoderPath vocab=${vocab.size}")
            return MangaOcrSession(encoder, decoder, vocab)
        }
    }
}
