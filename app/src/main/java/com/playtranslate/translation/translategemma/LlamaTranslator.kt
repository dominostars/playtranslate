package com.playtranslate.translation.translategemma

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.isModelLoaded
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/**
 * The two on-device LLM prompting flows we support. The caller declares which one
 * applies to the model it's asking [LlamaTranslator] to load — there is no
 * automatic detection from the file path.
 */
sealed interface PromptStyle {
    /**
     * Models whose chat template has a true `system` role (e.g. Qwen 2.5).
     * Drives [InferenceEngine.setSystemPrompt] + [InferenceEngine.sendUserPrompt],
     * with [InferenceEngine.resetForNextPrompt] reusing system-prompt KV across calls.
     */
    object StandardChat : PromptStyle

    /**
     * Models whose chat template emits empty for `role: system` and replays the
     * system content into every user-turn diff (e.g. Gemma 3). The fixed prefix
     * (system block + "Please translate..." scaffolding) is decoded once via
     * [InferenceEngine.processRawPrefix]; per-call only the variable suffix runs
     * through [InferenceEngine.sendRawSuffix].
     */
    object Gemma3Prefix : PromptStyle
}

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
    ): String = mutex.withLock {
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
                    engine.setSystemPrompt(systemPromptFor(source, target))
                    systemPair = pair
                } else {
                    engine.resetForNextPrompt()
                }
                engine.sendUserPrompt(buildUserMessage(text, source, target), predictLength = 256)
                    .collect { token -> sb.append(token) }
            }
        }
        sb.toString().trim()
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

    /** Best-effort cleanup. Safe to call at app teardown. Per-backend [close] is now
     *  a no-op since the singleton outlives any individual backend; explicit teardown
     *  only happens here if someone wants to tear down the engine for memory reasons. */
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
        if (engine.state.value.isModelLoaded) {
            Log.i(TAG, "Switching model: cleanUp existing then load $modelPath")
            engine.cleanUp()
        }
        // The engine's init coroutine flips state Uninitialized → Initializing → Initialized
        // asynchronously after construction. loadModel requires Initialized state, so wait
        // for it before calling.
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

    private fun systemPromptFor(source: String, target: String): String {
        val src = source.lowercase(Locale.ROOT)
        val tgt = target.lowercase(Locale.ROOT)
        val srcName = languageDisplayName(src)
        val tgtName = languageDisplayName(tgt)
        return """You are a professional $srcName ($src) to $tgtName ($tgt) translator. Your goal is to accurately convey the meaning and nuances of the original $srcName text while adhering to $tgtName grammar, vocabulary, and cultural sensitivities.

Produce only the $tgtName translation, without any additional explanations or commentary."""
    }

    private fun buildUserMessage(text: String, source: String, target: String): String {
        val src = source.lowercase(Locale.ROOT)
        val tgt = target.lowercase(Locale.ROOT)
        val srcName = languageDisplayName(src)
        val tgtName = languageDisplayName(tgt)
        return "Please translate the following $srcName text into $tgtName:\n\n$text"
    }

    private fun languageDisplayName(code: String): String {
        // English-locale display name regardless of system locale: "Japanese", not "日本語".
        return Locale(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }
    }

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
