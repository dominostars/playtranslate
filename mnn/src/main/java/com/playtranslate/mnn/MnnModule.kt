package com.playtranslate.mnn

import android.util.Log
import java.io.Closeable

/**
 * Kotlin handle over MNN's Express-Module inference (see the Module shim in
 * `mnn/src/main/cpp/mnn_infer.cpp`) — the runner for converted graphs that
 * carry SUBGRAPHS (control flow), which [MnnInterpreter]'s Session API cannot
 * execute. First consumer: the Silero VAD model, whose LSTMs the converter
 * lowers to While subgraphs.
 *
 * Inputs and outputs are fixed at load time by name; [forward] is POSITIONAL
 * in that declared order (the Module API's own contract). Reuses
 * [MnnInterpreter.NamedTensor]/[MnnInterpreter.TensorData] as the payload
 * types; names on forward inputs are validated against the declared order so
 * a reordered call site fails loudly instead of feeding the wrong tensor.
 * A shape of `intArrayOf()` is a scalar (e.g. Silero's `sr` input).
 *
 * **Not thread-safe** — same rule as [MnnInterpreter]: serialize calls per
 * instance. arm64-v8a only, like the rest of `:mnn`.
 */
class MnnModule private constructor(
    private val inputNames: List<String>,
    private val outputNames: List<String>,
) : Closeable {

    private var handle: Long = 0L

    /** One forward pass. [inputs] must match the load-time input names in
     *  order. Returns the load-time outputs, in order, as [MnnInterpreter.NamedTensor]s. */
    fun forward(inputs: List<MnnInterpreter.NamedTensor>): List<MnnInterpreter.NamedTensor> {
        check(handle != 0L) { "MnnModule is closed" }
        require(inputs.map { it.name } == inputNames) {
            "forward inputs ${inputs.map { it.name }} != declared $inputNames"
        }
        val n = inputs.size
        val shapes = Array(n) { inputs[it].shape }
        val dtypes = IntArray(n) {
            if (inputs[it].data is MnnInterpreter.TensorData.Ints) 1 else 0
        }
        val data = Array<Any>(n) {
            when (val d = inputs[it].data) {
                is MnnInterpreter.TensorData.Floats -> d.data
                is MnnInterpreter.TensorData.Ints -> d.data
            }
        }
        val out = nativeForward(handle, shapes, dtypes, data)
            ?: error("MNN module inference failed (see logcat tag 'mnn-chat')")
        @Suppress("UNCHECKED_CAST")
        val arr = out as Array<Any>
        val outDtypes = arr[0] as IntArray
        val outShapes = arr[1] as Array<*>
        val outData = arr[2] as Array<*>
        return List(outputNames.size) { i ->
            val td = if (outDtypes[i] == 1) {
                MnnInterpreter.TensorData.Ints(outData[i] as IntArray)
            } else {
                MnnInterpreter.TensorData.Floats(outData[i] as FloatArray)
            }
            MnnInterpreter.NamedTensor(outputNames[i], outShapes[i] as IntArray, td)
        }
    }

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private external fun nativeCreate(
        path: String, inputNames: Array<String>, outputNames: Array<String>,
    ): Long

    private external fun nativeForward(
        handle: Long, shapes: Array<IntArray>, dtypes: IntArray, data: Array<Any>,
    ): Any?

    private external fun nativeDestroy(handle: Long)

    companion object {
        private const val TAG = "MnnModule"

        /** Load a `.mnn` model as an Express Module with the given IO names.
         *  @throws IllegalStateException if the model fails to load. */
        @JvmStatic
        fun fromFile(path: String, inputNames: List<String>, outputNames: List<String>): MnnModule {
            // Same shared lib as the Session/LLM paths; loadLibrary is idempotent.
            System.loadLibrary("mnn-chat")
            val inst = MnnModule(inputNames, outputNames)
            inst.handle = inst.nativeCreate(
                path, inputNames.toTypedArray(), outputNames.toTypedArray(),
            )
            check(inst.handle != 0L) { "MNN failed to load module: $path (see logcat tag 'mnn-chat')" }
            Log.i(TAG, "loaded $path (${inputNames.size} in, ${outputNames.size} out)")
            return inst
        }
    }
}
