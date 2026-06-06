package com.playtranslate.mnn

import com.playtranslate.mnn.InferenceEngine.State
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Mirrors `com.arm.aichat.InferenceEngine` (the :llama module's interface) so
 * the production-side translators (`LlamaTranslator` vs. `MnnTranslator`) stay
 * structurally parallel. The two interfaces are deliberately defined per-module
 * rather than shared — each engine owns its own state machine, log tag, and
 * dispatcher, and the wrappers aren't substitutable at runtime (one routes
 * to llama.cpp's per-token `generateNextToken`, the other to MNN's blocking
 * `Llm::response`).
 *
 * Behavioral differences from the llama path documented inline.
 */
interface InferenceEngine {
    val state: StateFlow<State>

    /**
     * Load an MNN model from the directory at [pathToModelDir]. The directory
     * must contain a `config.json` recognized by `Llm::createLLM` plus the
     * referenced `.mnn` / `.mnn.weight` / `tokenizer.txt` files.
     *
     * @throws UnsupportedArchitectureException if the model's config is not
     *   recognized or its weights fail to load.
     *
     * @param useMmap when true, load the weights as reclaimable, file-backed
     *   mmap pages (survives memory pressure, ~16% slower); when false, the
     *   faster anonymous-resident load. Driven by the per-model "start anyway"
     *   flag — see [com.playtranslate.translation.llm.OnDeviceLlmBackend].
     */
    suspend fun loadModel(pathToModelDir: String, useMmap: Boolean)

    /**
     * Decode the system prompt as a prefill-only pass (max_new_tokens=0) and
     * record the post-prefill KV-cache position so subsequent
     * [resetForNextPrompt] calls can rewind to here.
     */
    suspend fun setSystemPrompt(systemPrompt: String)

    /**
     * Trim the KV cache back to "just after the system prompt" via
     * `Llm::eraseHistory(sys_pos, current)` — the MNN equivalent of llama.cpp's
     * `llama_memory_seq_rm`. Spike-validated (mnn-spike/SPIKE_REPORT.md).
     *
     * Safe to call on [State.ModelReady]. If no system prompt was set, falls
     * back to a full `Llm::reset()`.
     */
    suspend fun resetForNextPrompt()

    /**
     * Raw-prefix variant of [setSystemPrompt] for callers that build their own
     * chat envelope. With `use_template:false` (which we enforce in
     * `prepare()`), this is functionally equivalent to [setSystemPrompt] — kept
     * for InferenceEngine parity with :llama so the Gemma3Prefix path can be
     * ported by symbol if/when alibaba/MNN#4463 lets TG move to MNN.
     */
    suspend fun processRawPrefix(prefix: String)

    /**
     * Raw-suffix variant of [sendUserPrompt]. Same shape, same blocking call;
     * see the note on [sendUserPrompt] for the streaming semantics.
     */
    fun sendRawSuffix(suffix: String, predictLength: Int = DEFAULT_PREDICT_LENGTH): Flow<String>

    /**
     * Send the user prompt and stream the generated text as a [Flow]. Unlike
     * llama.cpp's per-token surface, MNN's `Llm::response` is blocking — the
     * native call runs prefill + the full generation loop synchronously and
     * the entire assistant text is emitted as a single [Flow] item, then the
     * flow completes. Callers that concatenate tokens into a StringBuilder
     * (which is what the translator does) see identical behavior to the
     * :llama path; the difference matters only for live token-by-token UIs.
     */
    fun sendUserPrompt(message: String, predictLength: Int = DEFAULT_PREDICT_LENGTH): Flow<String>

    /**
     * Unload the loaded model + free the native KV cache + scratch buffers,
     * **without** destroying the engine itself. The next [loadModel] call
     * reissues `Llm::createLLM` from scratch.
     */
    fun cleanUp()

    /**
     * Tear down both the loaded model and the engine. Only call at process
     * teardown — the singleton has no rehydrate path.
     */
    fun destroy()

    sealed class State {
        object Uninitialized : State()
        object Initializing : State()
        object Initialized : State()

        object LoadingModel : State()
        object UnloadingModel : State()
        object ModelReady : State()

        object ProcessingSystemPrompt : State()
        object ProcessingUserPrompt : State()

        object Generating : State()

        data class Error(val exception: Exception) : State()
    }

    companion object {
        const val DEFAULT_PREDICT_LENGTH = 1024
    }
}

val State.isUninterruptible
    get() = this is State.Initializing ||
        this is State.LoadingModel ||
        this is State.UnloadingModel ||
        this is State.ProcessingSystemPrompt ||
        this is State.ProcessingUserPrompt

val State.isModelLoaded: Boolean
    get() = this is State.ModelReady ||
        this is State.ProcessingSystemPrompt ||
        this is State.ProcessingUserPrompt ||
        this is State.Generating

class UnsupportedArchitectureException : Exception()
