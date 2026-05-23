package com.playtranslate.translation.translategemma

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.isModelLoaded
import com.playtranslate.translation.llm.PromptStyle
import com.playtranslate.translation.llm.languageDisplayName
import com.playtranslate.translation.qwen.QwenChatTemplate
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

// PromptStyle was moved to `translation/llm/PromptStyle.kt` when the :mnn
// module joined :llama as a sibling on-device engine — both translators now
// reference the same enum without cross-engine package dependencies.

/**
 * Process-singleton wrapper around the [com.arm.aichat.AiChat] inference engine.
 *
 * **Why a singleton.** The underlying engine is itself a process-wide singleton with
 * shared native state in `ai_chat.cpp` (g_context, chat_msgs, system_prompt_position).
 * Two `LlamaTranslator` instances with independent Kotlin mutexes would let two
 * backends interleave engine calls and corrupt KV/chat state — the single-threaded
 * `llamaDispatcher` only protects individual JNI calls, not multi-step sequences.
 * The singleton's mutex is the right scope for "atomic translate() sequence."
 *
 * **Backend swaps.** Multiple [com.playtranslate.translation.TranslationBackend]s
 * may share this singleton (TranslateGemma, Qwen, ...), each calling [translate]
 * with its own [modelPath] and [PromptStyle]. When the requested path differs from
 * the loaded one, [ensureLoaded] performs a clean cleanUp + loadModel cycle (~2-10 s).
 *
 * **Concurrency.** Per-call [Mutex] serializes the entire prepare + decode + collect
 * sequence within the singleton. PT's translation waterfall is one-call-at-a-time
 * per request anyway; parallel translateGroupsSeparately fan-out simply queues here.
 */
class LlamaTranslator private constructor(private val context: Context) {

    private val engine: InferenceEngine by lazy { AiChat.getInferenceEngine(context.applicationContext) }
    private val mutex = Mutex()

    @Volatile private var loadedModelPath: String? = null

    // Cached pair for which the engine's KV cache currently holds a live system prompt.
    // When [translate] is called with the same (source, target) again, we skip the
    // setSystemPrompt re-decode and just trim KV/chat-history back to "after system".
    private var systemPair: Pair<String, String>? = null

    /**
     * Read-only view of the currently loaded model path. Used by the
     * cross-engine memory coordinator in
     * [com.playtranslate.translation.mnn.MnnTranslator.ensureLoaded] to decide
     * whether to unload us before loading the MNN Qwen.
     */
    val currentlyLoadedModelPath: String? get() = loadedModelPath

    /**
     * Translate [text] from [source] to [target] using the model at [modelPath].
     *
     * [promptStyle] is required — the caller declares which prompting flow matches
     * its model. Defaulting it would silently mis-route a model through the wrong
     * chat path (TG fed through StandardChat → wrong template → garbage output, not
     * an error). The whole reason the prior filename-substring detection was
     * removed is to make this contract typed.
     *
     * [availMemFloorBytes] is checked against [ActivityManager.MemoryInfo.availMem]
     * before any load. Below the floor, throws [TranslateGemmaTransientException]
     * which the registry's waterfall treats as fall-through to the next backend.
     * Default 4 GB suits a TG-4B-class working set; smaller models can pass a
     * lower floor (e.g. 1.5 GB for Qwen 1.5B).
     *
     * Suspends while the model loads on first call (~2-10 s on a flagship phone).
     */
    suspend fun translate(
        text: String,
        source: String,
        target: String,
        modelPath: String,
        promptStyle: PromptStyle,
        availMemFloorBytes: Long = DEFAULT_AVAIL_MEM_FLOOR_BYTES,
    ): String {
        // Pre-lock cross-engine coordination — frees MNN's working set
        // (~1.2 GB on Thor) BEFORE our `preflightMemory` floor runs inside
        // [translateLocked]. Symmetric to the same move on the MNN side
        // (see `MnnTranslator.translate`). Only fires when [modelPath] is
        // the legacy Qwen tier; TG (the other route through this translator)
        // has a disjoint working set and leaves MNN alone. Codex review
        // (2026-05-22 [P2]) — the post-lock placement that previously
        // lived after `mutex.withLock` could never run when the preflight
        // failed, leaving the MNN tier unreachable on memory-tight devices.
        coordinateMnnUnload(modelPath)
        return mutex.withLock {
            translateLocked(text, source, target, modelPath, promptStyle, availMemFloorBytes)
        }
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
                    engine.setSystemPrompt(QwenChatTemplate.systemPrompt(source, target))
                    systemPair = pair
                } else {
                    engine.resetForNextPrompt()
                }
                engine.sendUserPrompt(QwenChatTemplate.userMessage(text, source, target), predictLength = 256)
                    .collect { token -> sb.append(token) }
            }
        }
        return sb.toString().trim()
    }

    /**
     * Pre-lock unload of the sibling MNN engine when the upcoming load is
     * the *legacy* Qwen GGUF. Symmetric to
     * [com.playtranslate.translation.mnn.MnnTranslator.coordinateLlamaUnload].
     *
     * Gating uses the INCOMING [modelPath] (what we're about to load),
     * not [loadedModelPath] (which still reflects the previous load):
     * pre-lock placement means we need to decide based on what's coming,
     * not what's there. TG modelPath → skip (working sets disjoint).
     *
     * Lock ordering: see [translate]'s rationale — we don't hold our own
     * [mutex] when this runs, [MnnTranslator.unloadModel] acquires and
     * releases its own mutex synchronously, so we never hold both.
     */
    private suspend fun coordinateMnnUnload(modelPath: String) {
        val legacyQwenPath = com.playtranslate.translation.qwen.QwenModel.file(context).absolutePath
        if (modelPath != legacyQwenPath) return
        runCatching {
            val mnn = com.playtranslate.translation.mnn.MnnTranslator.getInstance(context)
            if (mnn.currentlyLoadedModelPath != null) {
                Log.i(TAG, "Coordinating unload of MNN Qwen (loading legacy GGUF)")
                mnn.unloadModel()
            }
        }.onFailure { Log.w(TAG, "Cross-engine unload coordination failed (ignored): $it") }
    }

    private fun buildGemma3Prefix(source: String, target: String): String {
        val src = source.lowercase(Locale.ROOT)
        val tgt = target.lowercase(Locale.ROOT)
        val srcName = languageDisplayName(src)
        val tgtName = languageDisplayName(tgt)
        // Mirrors the chat-template output we observed in logs, minus the variable
        // text. Ends with the blank line after "into English:" so the suffix can
        // start directly with the user's text.
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

    /**
     * Drop the loaded GGUF and release native KV cache + scratch, **without**
     * destroying the engine itself. After this call, the next [translate] will
     * re-issue [InferenceEngine.loadModel] from scratch.
     *
     * Mutex-serialized so it can't race with an in-flight translate(). Callers
     * launch from a coroutine; the mutex waits for any active translation to
     * finish before unloading.
     *
     * Used by:
     *  - Settings toggle-off (when both TG and Qwen become disabled — frees
     *    ~300-400 MB of KV/scratch + lets mmap pages be reclaimed),
     *  - "Delete model" disable-dialog branch (forces a fresh load on
     *    re-download instead of serving stale weights from the previous mmap),
     *  - `Application.onTrimMemory` at TRIM_MEMORY_COMPLETE (defensive backstop
     *    when the system is about to kill us anyway).
     */
    suspend fun unloadModel() = mutex.withLock {
        val state = engine.state.value
        if (state.isModelLoaded || state is InferenceEngine.State.Error) {
            Log.i(TAG, "Unloading model (was in ${state.javaClass.simpleName})")
            runCatching { engine.cleanUp() }
                .onFailure { Log.w(TAG, "unloadModel() encountered $it (ignored)") }
        }
        loadedModelPath = null
        systemPair = null
    }

    /** Best-effort full teardown. Safe to call at app teardown. Tears down both
     *  the loaded model AND the engine itself; after this, getInstance() would
     *  need to be re-created (which the singleton-via-companion currently
     *  doesn't support — only call at process exit). */
    fun close() {
        runCatching {
            if (engine.state.value.isModelLoaded) engine.cleanUp()
            engine.destroy()
        }.onFailure { Log.w(TAG, "close() encountered $it (ignored)") }
    }

    /**
     * @return `true` if the model was (re)loaded as part of this call. The caller uses
     * this to decide whether the system prompt needs to be re-established (KV cache is
     * empty after a load) vs. just reset back to "after system".
     */
    private suspend fun ensureLoaded(modelPath: String, availMemFloorBytes: Long): Boolean {
        if (loadedModelPath == modelPath && engine.state.value.isModelLoaded) return false
        preflightMemory(availMemFloorBytes)

        // Recovery path. A prior translate() may have left the engine in Error
        // (e.g. native llama_decode failed under memory pressure, or a malformed
        // prompt tripped a JNI assertion). [InferenceEngineImpl.cleanUp] accepts
        // Error as input and resets to Initialized — that's the only path back
        // to a usable engine without restarting the process. Without this, the
        // sibling on-device backends (TG + Qwen) would *both* keep failing after
        // a single transient error since they share the singleton, with the
        // waterfall silently masking the brick by falling through to ML Kit.
        // This branch must come BEFORE the isModelLoaded check below — Error is
        // not a "loaded" state, so isModelLoaded is false and the existing
        // model-switch cleanUp wouldn't catch it.
        if (engine.state.value is InferenceEngine.State.Error) {
            Log.w(TAG, "Engine in Error state; running cleanUp to recover before reload")
            engine.cleanUp()
            loadedModelPath = null
            systemPair = null
        } else if (engine.state.value.isModelLoaded) {
            Log.i(TAG, "Switching model: cleanUp existing then load $modelPath")
            engine.cleanUp()
        }

        // The engine's init coroutine flips state Uninitialized → Initializing → Initialized
        // asynchronously after construction. loadModel requires Initialized state, so wait
        // for it before calling. If the engine reaches Error here it's a startup-level
        // init failure (separate from the runtime Error path handled above), which is
        // not recoverable by another cleanUp — surface it.
        if (engine.state.value !is InferenceEngine.State.Initialized) {
            Log.i(TAG, "Awaiting engine.Initialized before loadModel...")
            engine.state.firstOrNull { it is InferenceEngine.State.Initialized || it is InferenceEngine.State.Error }
            val s = engine.state.value
            if (s is InferenceEngine.State.Error) {
                throw IllegalStateException("Inference engine failed to initialize: ${s.exception.message}", s.exception)
            }
        }
        engine.loadModel(modelPath)
        loadedModelPath = modelPath
        systemPair = null

        // Cross-engine memory coordination used to fire here but moved to
        // [translate]'s post-mutex coordinator — see [coordinateMnnUnload].
        // Doing it inside this locked region risked an AB-BA deadlock with
        // the symmetric path in `MnnTranslator.ensureLoaded`.
        return true
    }

    private fun preflightMemory(availMemFloorBytes: Long) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        if (mi.availMem < availMemFloorBytes) {
            throw TranslateGemmaTransientException(
                "Low memory (${mi.availMem / 1_000_000} MB available, need ${availMemFloorBytes / 1_000_000} MB); " +
                    "falling through to next backend"
            )
        }
    }

    // The Qwen2.5 chat envelope (systemPrompt / userMessage) lives in
    // [com.playtranslate.translation.qwen.QwenChatTemplate] so the MNN-backed
    // translator can call the same helpers without copy-pasting the strings.
    // languageDisplayName moved to `translation/llm/LanguageDisplay.kt` for
    // the same reason (Gemma3Prefix uses it too).

    companion object {
        private const val TAG = "LlamaTranslator"

        // Below ~4 GB available, loading + KV cache + scratch is at risk of OOM kill
        // for a TG-4B-class model. Smaller models (Qwen 1.5B) can pass a lower floor
        // explicitly to translate(). This is the *transient* check at load time
        // (per-attempt), not a permanent device gate — see the install-time
        // total-RAM check inside [com.playtranslate.translation.llm.OnDeviceLlmDownloader].
        const val DEFAULT_AVAIL_MEM_FLOOR_BYTES = 4_000_000_000L

        @Volatile private var INSTANCE: LlamaTranslator? = null

        /** Process-wide singleton. Both TG and Qwen backends route through this one
         *  instance so they share the underlying engine's mutex and `loadedModelPath`
         *  state. */
        fun getInstance(context: Context): LlamaTranslator =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: LlamaTranslator(context.applicationContext).also { INSTANCE = it }
            }
    }
}

/**
 * Transient (retry-later) failure to load or run an on-device LLM backend.
 *
 * The waterfall in [com.playtranslate.translation.TranslationBackendRegistry.translate]
 * catches this and falls through to the next backend (typically ML Kit or a smaller
 * on-device model). Throwing this does NOT disable the backend — the next translate()
 * call may succeed if memory pressure relaxes.
 *
 * Despite the name, this exception applies to any LlamaTranslator-driven backend,
 * not only TranslateGemma. The class is kept here for now to avoid renaming during
 * Phase 0; Phase 1 will move both this and the singleton into a shared `llm/` package.
 */
class TranslateGemmaTransientException(message: String) : RuntimeException(message)
