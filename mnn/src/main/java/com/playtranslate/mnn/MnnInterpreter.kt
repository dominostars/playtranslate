package com.playtranslate.mnn

import android.util.Log
import java.io.Closeable

/**
 * Thin Kotlin handle over MNN's general Session-API inference (see
 * `mnn/src/main/cpp/mnn_infer.cpp`). The non-LLM counterpart to [MnnChat]:
 * where [MnnChat] drives autoregressive text generation, this loads a
 * fixed-graph model (CNN / CRNN) and runs one float tensor through it.
 *
 * Deliberately minimal and OCR-agnostic — it knows nothing about detection,
 * recognition, CTC, or images. Callers (currently only the PaddleOCR spike
 * harness, `app/src/androidTest/.../ocr/paddle/PaddleOcrSession.kt`) own all
 * pre/post-processing and feed/read raw NCHW float tensors. This keeps the
 * native surface tiny and reusable: if the spike greenlights PaddleOCR, this
 * class is the production inference foundation; if not, it is dead code with no
 * dependents and can be deleted with the spike.
 *
 * **Single input / single output only.** PP-OCRv5 det and rec each have one
 * input and one output; the native layer resolves them via
 * `getSessionInput/Output(.., nullptr)`. Multi-IO models are out of scope.
 *
 * **Not thread-safe.** One MNN Session cannot run concurrently. A given
 * [MnnInterpreter] instance must not have [run] called re-entrantly; serialize
 * externally (the harness runs configs sequentially). Separate instances
 * (separate models) are independent.
 *
 * Native methods are instance methods (matching `MnnChatImpl`'s style) so the
 * JNI symbols are `Java_com_playtranslate_mnn_MnnInterpreter_native*` — a
 * `@JvmStatic` companion external would instead emit a `…_00024Companion_…`
 * symbol that wouldn't match the C++.
 *
 * arm64-v8a only — like the rest of `:mnn` (the native libs ship arm64 only).
 */
class MnnInterpreter private constructor() : Closeable {

    private var handle: Long = 0L

    /** Result of a forward pass: flat output [data] in NCHW order with its [shape]. */
    class Result(val data: FloatArray, val shape: IntArray)

    /**
     * Run one forward pass. [input] is the model input as a flat NCHW float
     * array; [shape] is the matching dimensions (e.g. `[1,3,h,w]`). The native
     * layer resizes the session to [shape] before running, so variable det
     * sizes / rec crop widths are handled per call.
     *
     * @throws IllegalStateException if the interpreter is closed or the native
     *   call fails (null return — see logcat under the `mnn-chat` tag).
     */
    fun run(input: FloatArray, shape: IntArray): Result {
        check(handle != 0L) { "MnnInterpreter is closed" }
        val out = nativeRun(handle, input, shape)
            ?: error("MNN inference failed (see logcat tag 'mnn-chat')")
        @Suppress("UNCHECKED_CAST")
        val arr = out as Array<Any>
        return Result(arr[0] as FloatArray, arr[1] as IntArray)
    }

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private external fun nativeCreate(path: String, numThread: Int, precision: Int): Long
    private external fun nativeRun(handle: Long, input: FloatArray, shape: IntArray): Any?
    private external fun nativeDestroy(handle: Long)

    companion object {
        private const val TAG = "MnnInterpreter"

        @Volatile private var libLoaded = false

        private fun ensureLib() {
            if (!libLoaded) synchronized(this) {
                if (!libLoaded) {
                    // Same shared lib as the LLM path; loadLibrary is idempotent.
                    // Pulls in libMNN.so / libMNN_Express.so as DT_NEEDED deps.
                    System.loadLibrary("mnn-chat")
                    libLoaded = true
                    Log.i(TAG, "Loaded native library 'mnn-chat' for general inference")
                }
            }
        }

        /**
         * Load an MNN model (`.mnn`) from [path].
         *
         * @param numThread CPU threads for the session (default 4).
         * @param precision 0 = Normal (spike baseline), 1 = High (force fp32),
         *   2 = Low (fp16 on ARM — faster, typical mobile deployment).
         * @throws IllegalStateException if the model fails to load.
         */
        @JvmStatic
        @JvmOverloads
        fun fromFile(path: String, numThread: Int = 4, precision: Int = 0): MnnInterpreter {
            ensureLib()
            val inst = MnnInterpreter()
            inst.handle = inst.nativeCreate(path, numThread, precision)
            check(inst.handle != 0L) { "MNN failed to load model: $path (see logcat tag 'mnn-chat')" }
            return inst
        }
    }
}
