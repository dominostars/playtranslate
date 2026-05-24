package com.playtranslate.translation.mnn

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.playtranslate.mnn.InferenceEngine
import com.playtranslate.mnn.MnnChat
import com.playtranslate.mnn.isModelLoaded
import com.playtranslate.translation.gemma.GemmaE2BChatTemplate
import com.playtranslate.translation.llm.OnDeviceLlmTransientException
import com.playtranslate.translation.llm.PromptStyle
import com.playtranslate.translation.qwen.QwenChatTemplate
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-singleton wrapper around the [com.playtranslate.mnn.MnnChat] inference
 * engine. Single on-device translator after the :llama strip — drives every
 * on-device backend through one engine, one mutex, one cached
 * `(source, target)` system block.
 *
 * **Why a singleton.** The underlying engine has shared native state
 * (`g_llm`, `g_system_prompt_position` in `mnn_chat.cpp`) and a
 * single-threaded JNI dispatcher. Two independent instances with separate
 * mutexes would let two backends interleave engine calls and corrupt
 * KV / cached-system-pair state.
 *
 * **Cross-backend model swaps.** [QwenMnnBackend] and [GemmaE2BMnnBackend]
 * both call [translate] with their own `modelPath`. When the path changes
 * we cleanUp the prior model *before* preflight (so its working set is
 * freed before we check `availMem` against the new floor — without that
 * ordering, swapping from Qwen-MNN's 1.5 GB floor to E2B's 3.5 GB floor on
 * a tight device would throw transient even though there'd be plenty of
 * memory after unload).
 */
class MnnTranslator private constructor(private val context: Context) {

    private val engine: InferenceEngine by lazy { MnnChat.getInferenceEngine(context.applicationContext) }
    private val mutex = Mutex()

    @Volatile private var loadedModelPath: String? = null

    // Same cache discipline as the prior :llama translator: when [translate]
    // is called with the same (source, target) pair, skip the setSystemPrompt
    // re-decode and just erase-history back to "after system" via
    // [InferenceEngine.resetForNextPrompt].
    private var systemPair: Pair<String, String>? = null

    /**
     * Read-only view of the currently loaded model path. Public for callers
     * that need to gate behavior on which backend is resident (e.g., the
     * `onTrimMemory` hook in [com.playtranslate.PlayTranslateApplication]).
     */
    val currentlyLoadedModelPath: String? get() = loadedModelPath

    /**
     * Translate [text] from [source] to [target] using the MNN model at
     * [modelPath] (a *directory* — MNN's `Llm::createLLM` takes the model dir's
     * `config.json` path; the JNI [com.playtranslate.mnn.InferenceEngine.loadModel]
     * computes that from the dir). [promptStyle] is required for the same
     * reason it always was — defaulting it would silently route a model
     * through the wrong template and produce garbage rather than an error.
     */
    suspend fun translate(
        text: String,
        source: String,
        target: String,
        modelPath: String,
        promptStyle: PromptStyle,
        availMemFloorBytes: Long = DEFAULT_AVAIL_MEM_FLOOR_BYTES,
    ): String {
        return mutex.withLock {
            translateLocked(text, source, target, modelPath, promptStyle, availMemFloorBytes)
        }
    }

    /**
     * The mutex-protected body of [translate]. Returns the decoded translation.
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
            PromptStyle.StandardChat -> {
                if (didReload || systemPair != pair) {
                    // *Critical*: feed the full <|im_start|>system…<|im_end|>
                    // envelope, NOT just the prose. The engine runs with
                    // `use_template:false` (see mnn_chat.cpp prepare()), so we
                    // hand-build the chat envelope ourselves. Without these
                    // role markers the model treats the system prompt as
                    // generic context and continues it like a completion —
                    // echoing the input + generating "Translation:" boilerplate
                    // up to max_new_tokens.
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
            PromptStyle.Gemma4Chat -> {
                if (didReload || systemPair != pair) {
                    // Gemma 4 uses <|turn>{role}…<turn|> markers per
                    // llmexport.py:108-113. Same JNI path as StandardChat
                    // (true system role), different envelope strings. `<bos>`
                    // is inside [GemmaE2BChatTemplate.systemBlock] — it lands
                    // exactly once because the system block is cached per
                    // (source, target) pair and only re-prefilled here when
                    // the pair changes.
                    engine.setSystemPrompt(GemmaE2BChatTemplate.systemBlock(source, target))
                    systemPair = pair
                } else {
                    engine.resetForNextPrompt()
                }
                engine.sendUserPrompt(
                    GemmaE2BChatTemplate.userBlock(text, source, target),
                    predictLength = 256,
                ).collect { token -> sb.append(token) }
            }
        }
        // Defensive cleanup: a clean run terminates at the model's EOS marker
        // via Llm::is_stop and the decoded text won't include the marker, but
        // strip any leaked tokens to be safe (e.g. if a future model variant
        // emits the marker as text instead of as a special-token id). All
        // three are listed so the strip is engine-agnostic.
        return sb.toString()
            .substringBefore("<|im_end|>")
            .substringBefore("<turn|>")
            .substringBefore("<|endoftext|>")
            .trim()
    }

    /**
     * Drop the loaded MNN model and release native state, **without**
     * destroying the engine itself. Mutex-serialized so it can't race with
     * an in-flight translate(); the [com.playtranslate.PlayTranslateApplication]
     * `onTrimMemory` hook calls this from its own coroutine scope.
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

        // **Swap-cleanup runs BEFORE preflight.** If a different model is
        // loaded, free its working set first so the preflight memory check
        // sees the post-unload state. Without this ordering, swapping from
        // a low-floor backend (Qwen-MNN ~1.5 GB) to a high-floor one
        // (E2B ~3.5 GB) on a tight 6 GB device would trip the floor check
        // even though there'd be plenty of memory after the prior unload.
        if (engine.state.value.isModelLoaded && loadedModelPath != modelPath) {
            Log.i(TAG, "Swap-cleanup: unloading $loadedModelPath before loading $modelPath")
            runCatching { engine.cleanUp() }
                .onFailure { Log.w(TAG, "swap-cleanup encountered $it (ignored)") }
            loadedModelPath = null
            systemPair = null
        }

        preflightMemory(availMemFloorBytes)

        // Error recovery — distinct from swap-cleanup. State::Error from a
        // prior translate() (e.g. JNI returned non-zero) must be cleared via
        // cleanUp() before we can re-load; without this branch sibling
        // backends would all keep failing after a single transient error.
        if (engine.state.value is InferenceEngine.State.Error) {
            Log.w(TAG, "Engine in Error state; running cleanUp to recover before reload")
            runCatching { engine.cleanUp() }
                .onFailure { Log.w(TAG, "error-recovery cleanUp encountered $it (ignored)") }
            loadedModelPath = null
            systemPair = null
        }

        // Wait for engine.Initialized.
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
        return true
    }

    private fun preflightMemory(availMemFloorBytes: Long) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        if (mi.availMem < availMemFloorBytes) {
            throw OnDeviceLlmTransientException(
                "Low memory (${mi.availMem / 1_000_000} MB available, need ${availMemFloorBytes / 1_000_000} MB); " +
                    "falling through to next backend"
            )
        }
    }

    companion object {
        private const val TAG = "MnnTranslator"

        /** Conservative default for callers that don't supply their own floor.
         *  Production backends override via [OnDeviceLlmBackend.availMemFloorBytes]. */
        const val DEFAULT_AVAIL_MEM_FLOOR_BYTES = 1_500_000_000L

        @Volatile private var INSTANCE: MnnTranslator? = null

        fun getInstance(context: Context): MnnTranslator =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: MnnTranslator(context.applicationContext).also { INSTANCE = it }
            }
    }
}
