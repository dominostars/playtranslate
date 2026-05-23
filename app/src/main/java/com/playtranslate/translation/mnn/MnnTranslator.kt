package com.playtranslate.translation.mnn

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.playtranslate.mnn.InferenceEngine
import com.playtranslate.mnn.MnnChat
import com.playtranslate.mnn.isModelLoaded
import com.playtranslate.translation.llm.PromptStyle
import com.playtranslate.translation.llm.languageDisplayName
import com.playtranslate.translation.qwen.QwenChatTemplate
import com.playtranslate.translation.qwen.QwenModel
import com.playtranslate.translation.translategemma.LlamaTranslator
import com.playtranslate.translation.translategemma.TranslateGemmaTransientException
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/**
 * Process-singleton wrapper around the [com.playtranslate.mnn.MnnChat] inference
 * engine. Mirrors [LlamaTranslator]'s shape — singleton, [Mutex]-serialized
 * translate() sequences, cached `(source, target)` to skip re-prefilling the
 * system prompt — but routes through the :mnn JNI bridge instead of :llama.
 *
 * **Why a singleton.** Same rationale as :llama's: the underlying engine has
 * shared native state (`g_llm`, `g_system_prompt_position` in `mnn_chat.cpp`)
 * and a single-threaded JNI dispatcher. Two `MnnTranslator` instances with
 * independent mutexes would let two backends interleave engine calls and
 * corrupt KV/cached-system-pair state.
 *
 * **Current scope.** Only the MNN-backed Qwen 2.5 1.5B tier ([QwenMnnBackend])
 * routes through here. The Gemma3Prefix path is implemented for InferenceEngine
 * parity with :llama, but TG-on-MNN is blocked upstream by alibaba/MNN#4463
 * and there's no production caller yet.
 *
 * **Dual-engine memory coordination.** After this translator successfully
 * loads its model, it unloads the legacy Qwen GGUF in :llama (but NOT TG) —
 * see [ensureLoaded]. Mirrors the inverse coordination in
 * [LlamaTranslator.ensureLoaded].
 */
class MnnTranslator private constructor(private val context: Context) {

    private val engine: InferenceEngine by lazy { MnnChat.getInferenceEngine(context.applicationContext) }
    private val mutex = Mutex()

    @Volatile private var loadedModelPath: String? = null

    // Same cache discipline as :llama — when [translate] is called with the
    // same (source, target) pair, skip the setSystemPrompt re-decode and just
    // erase-history back to "after system" via [InferenceEngine.resetForNextPrompt].
    private var systemPair: Pair<String, String>? = null

    /**
     * Read-only view of the currently loaded model path. Used by the
     * cross-engine memory coordinator in [LlamaTranslator.ensureLoaded] to
     * decide whether to unload us before loading the legacy GGUF Qwen.
     */
    val currentlyLoadedModelPath: String? get() = loadedModelPath

    /**
     * Translate [text] from [source] to [target] using the MNN model at
     * [modelPath] (a *directory* — MNN's `Llm::createLLM` takes the model dir's
     * `config.json` path, the JNI [com.playtranslate.mnn.InferenceEngine.loadModel]
     * computes that from the dir). [promptStyle] is required for the same
     * reasons as :llama's translator — defaulting it would silently route a
     * model through the wrong template and produce garbage rather than an error.
     */
    suspend fun translate(
        text: String,
        source: String,
        target: String,
        modelPath: String,
        promptStyle: PromptStyle,
        availMemFloorBytes: Long = DEFAULT_AVAIL_MEM_FLOOR_BYTES,
    ): String {
        val result = mutex.withLock {
            translateLocked(text, source, target, modelPath, promptStyle, availMemFloorBytes)
        }
        // Cross-engine memory coordination runs OUTSIDE our mutex so the
        // acquire-order between our lock and `LlamaTranslator.mutex` is
        // single-directional (release MNN → acquire Llama), eliminating the
        // AB-BA deadlock that the inverse path in `LlamaTranslator.translate`
        // would otherwise create when two coroutines simultaneously switch
        // engines. Codex adversarial review (May 22 2026) [high].
        //
        // Cost: a brief window where both engines stay loaded between our
        // translate completing and the Llama unload finishing (~100–300 ms).
        // Net win vs. the previous pin-both-engines-forever-after-transient-
        // fallthrough behavior.
        coordinateLlamaUnload()
        return result
    }

    /**
     * The mutex-protected body of [translate]. Returns the decoded
     * translation; cross-engine coordination is the caller's job.
     */
    private suspend fun translateLocked(
        text: String,
        source: String,
        target: String,
        modelPath: String,
        promptStyle: PromptStyle,
        availMemFloorBytes: Long,
    ): String {
        val didReload = ensureLoaded(modelPath, availMemFloorBytes)
        val pair = source to target
        val sb = StringBuilder()
        when (promptStyle) {
            PromptStyle.Gemma3Prefix -> {
                if (didReload || systemPair != pair) {
                    engine.processRawPrefix(buildGemma3Prefix(source, target))
                    systemPair = pair
                } else {
                    engine.resetForNextPrompt()
                }
                engine.sendRawSuffix(buildGemma3Suffix(text), predictLength = 256)
                    .collect { token -> sb.append(token) }
            }
            PromptStyle.StandardChat -> {
                if (didReload || systemPair != pair) {
                    // *Critical*: feed the full <|im_start|>system…<|im_end|>
                    // envelope, NOT just the prose. The engine runs with
                    // `use_template:false` (see mnn_chat.cpp prepare()), so we
                    // hand-build the chat envelope ourselves. Without these
                    // role markers the model treats the system prompt as
                    // generic context and continues it like a completion —
                    // echoing the input + generating "Translation:" boilerplate
                    // up to max_new_tokens. Matches the spike's
                    // `build_prefix` (mnn-spike/MNN/.../demo/llm_demo.cpp).
                    engine.setSystemPrompt(QwenChatTemplate.systemBlock(source, target))
                    systemPair = pair
                } else {
                    engine.resetForNextPrompt()
                }
                engine.sendUserPrompt(
                    QwenChatTemplate.userBlock(text, source, target),
                    predictLength = 256,
                ).collect { token -> sb.append(token) }
            }
        }
        // Defensive cleanup: a clean run terminates at <|im_end|> via
        // Llm::is_stop and the decoded text won't include the marker, but
        // strip any leaked tokens to be safe (e.g. if a future model variant
        // emits the marker as text instead of as a special-token id).
        return sb.toString()
            .substringBefore("<|im_end|>")
            .substringBefore("<|endoftext|>")
            .trim()
    }

    /**
     * Best-effort unload of the sibling `:llama` engine when it's currently
     * holding the *legacy* Qwen GGUF — we're about to (or just did) serve
     * Qwen from MNN, so the GGUF working set is wasted RAM. Runs OUTSIDE
     * [mutex] so we can safely acquire [LlamaTranslator.mutex] without
     * stacking locks (see [translate] for the deadlock rationale).
     *
     * Does nothing when `:llama` is serving TG (priority 25, the other
     * waterfall tier) — TG's working set is disjoint from Qwen's and
     * dropping it would just delay the next TG translation.
     *
     * Race tolerated: another coroutine may load/unload the legacy Qwen
     * between our `currentlyLoadedModelPath` read and the
     * [LlamaTranslator.unloadModel] call. The worst case is an unnecessary
     * unload cycle on the next call; no correctness issue.
     */
    private suspend fun coordinateLlamaUnload() {
        runCatching {
            val llama = LlamaTranslator.getInstance(context)
            val llamaPath = llama.currentlyLoadedModelPath
            val legacyQwenPath = QwenModel.file(context).absolutePath
            if (llamaPath == legacyQwenPath) {
                Log.i(TAG, "Coordinating unload of legacy Qwen GGUF (MNN active for Qwen)")
                llama.unloadModel()
            }
        }.onFailure { Log.w(TAG, "Cross-engine unload coordination failed (ignored): $it") }
    }

    /**
     * Drop the loaded MNN model and release native state, **without**
     * destroying the engine itself. Mutex-serialized so it can't race with
     * an in-flight translate(); the [LlamaTranslator.ensureLoaded] coordinator
     * calls this from its own locked region. Different mutex — no deadlock.
     */
    suspend fun unloadModel() = mutex.withLock {
        val state = engine.state.value
        if (state.isModelLoaded || state is InferenceEngine.State.Error) {
            Log.i(TAG, "Unloading MNN model (was in ${state.javaClass.simpleName})")
            runCatching { engine.cleanUp() }
                .onFailure { Log.w(TAG, "unloadModel() encountered $it (ignored)") }
        }
        loadedModelPath = null
        systemPair = null
    }

    /** Best-effort full teardown. Safe to call at app teardown. */
    fun close() {
        runCatching {
            if (engine.state.value.isModelLoaded) engine.cleanUp()
            engine.destroy()
        }.onFailure { Log.w(TAG, "close() encountered $it (ignored)") }
    }

    /**
     * @return `true` if the model was (re)loaded as part of this call.
     */
    private suspend fun ensureLoaded(modelPath: String, availMemFloorBytes: Long): Boolean {
        if (loadedModelPath == modelPath && engine.state.value.isModelLoaded) return false
        preflightMemory(availMemFloorBytes)

        // Recovery path — same as :llama's. State::Error from a prior translate()
        // (e.g. JNI returned non-zero) must be cleared via cleanUp() before we
        // can re-load; without this branch the sibling backends would all keep
        // failing after a single transient error.
        if (engine.state.value is InferenceEngine.State.Error) {
            Log.w(TAG, "Engine in Error state; running cleanUp to recover before reload")
            engine.cleanUp()
            loadedModelPath = null
            systemPair = null
        } else if (engine.state.value.isModelLoaded) {
            Log.i(TAG, "Switching model: cleanUp existing then load $modelPath")
            engine.cleanUp()
        }

        // Wait for engine.Initialized — same shape as :llama's.
        if (engine.state.value !is InferenceEngine.State.Initialized) {
            Log.i(TAG, "Awaiting engine.Initialized before loadModel...")
            engine.state.firstOrNull { it is InferenceEngine.State.Initialized || it is InferenceEngine.State.Error }
            val s = engine.state.value
            if (s is InferenceEngine.State.Error) {
                throw IllegalStateException("MNN engine failed to initialize: ${s.exception.message}", s.exception)
            }
        }
        engine.loadModel(modelPath)
        loadedModelPath = modelPath
        systemPair = null
        // Cross-engine memory coordination used to fire here but moved to
        // [translate]'s post-mutex coordinator — see [coordinateLlamaUnload].
        // Doing it inside this locked region risked an AB-BA deadlock with
        // the symmetric path in `LlamaTranslator.ensureLoaded`.
        return true
    }

    private fun preflightMemory(availMemFloorBytes: Long) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        if (mi.availMem < availMemFloorBytes) {
            // Reuse :llama's transient exception so the registry waterfall's
            // existing TranslateGemmaTransientException catch handles MNN
            // transient memory failures identically (fall through to next
            // backend, don't surface to the user).
            throw TranslateGemmaTransientException(
                "Low memory (${mi.availMem / 1_000_000} MB available, need ${availMemFloorBytes / 1_000_000} MB); " +
                    "falling through to next backend"
            )
        }
    }

    // Gemma3 prompting (kept for InferenceEngine parity; no production caller
    // yet — TG-on-MNN is blocked by alibaba/MNN#4463). Verbatim copy of
    // :llama's helpers since the chat template is engine-agnostic; if the
    // strings ever change in one place they should change in both, but we
    // accept the duplication for now rather than introduce a third shared
    // file just for these two strings.
    private fun buildGemma3Prefix(source: String, target: String): String {
        val src = source.lowercase(Locale.ROOT)
        val tgt = target.lowercase(Locale.ROOT)
        val srcName = languageDisplayName(src)
        val tgtName = languageDisplayName(tgt)
        return "<start_of_turn>user\n" +
            "You are a professional $srcName ($src) to $tgtName ($tgt) translator. " +
            "Your goal is to accurately convey the meaning and nuances of the original $srcName text " +
            "while adhering to $tgtName grammar, vocabulary, and cultural sensitivities.\n\n" +
            "Produce only the $tgtName translation, without any additional explanations or commentary.\n\n" +
            "Please translate the following $srcName text into $tgtName:\n\n"
    }

    private fun buildGemma3Suffix(text: String): String {
        return "$text<end_of_turn>\n<start_of_turn>model\n"
    }

    companion object {
        private const val TAG = "MnnTranslator"

        /** Below ~1.5 GB available, loading + KV + scratch is at risk of OOM
         *  kill for the Qwen-MNN 1.5B working set. Same floor as the legacy
         *  GGUF Qwen — same model, same ballpark RAM use. The TG-class 4 GB
         *  floor doesn't apply since we don't (yet) drive TG-4B through MNN. */
        const val DEFAULT_AVAIL_MEM_FLOOR_BYTES = 1_500_000_000L

        @Volatile private var INSTANCE: MnnTranslator? = null

        fun getInstance(context: Context): MnnTranslator =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: MnnTranslator(context.applicationContext).also { INSTANCE = it }
            }
    }
}
