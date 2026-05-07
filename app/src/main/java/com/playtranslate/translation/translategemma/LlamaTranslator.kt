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
 * Thin wrapper over [com.arm.aichat.AiChat] for the TranslateGemma translation use case.
 *
 * Responsibilities:
 *  - Lazy-load the GGUF model the first time [translate] is called.
 *  - Reset KV cache and chat history between calls so each translation is independent
 *    (calls `setSystemPrompt` per request — see InferenceEngineImpl edit).
 *  - Aggregate the streamed `Flow<String>` token output into one string.
 *  - Pre-flight check available memory at load time and surface a transient exception
 *    that the waterfall can fall through.
 *
 * Concurrency: a [Mutex] serializes calls — `InferenceEngineImpl` already runs on a
 * single-threaded dispatcher, but the mutex prevents Kotlin-side re-entry while a
 * translation is in flight. PT's translation waterfall is one-call-at-a-time so this
 * is not a bottleneck; if a future caller needs parallelism, they go via the registry
 * which already serializes too.
 */
class LlamaTranslator(private val context: Context) {

    private val engine: InferenceEngine by lazy { AiChat.getInferenceEngine(context.applicationContext) }
    private val mutex = Mutex()

    @Volatile private var loadedModelPath: String? = null

    // Cached pair for which the engine's KV cache currently holds a live system prompt.
    // When [translate] is called with the same (source, target) again, we skip the
    // setSystemPrompt re-decode and just trim KV/chat-history back to "after system".
    private var systemPair: Pair<String, String>? = null

    /**
     * Translate [text] from [source] to [target] using the model at [modelPath].
     * Suspends while the model loads on first call (~2–10s on a flagship phone).
     * Throws [TranslateGemmaTransientException] if memory pressure precludes loading;
     * the registry's waterfall treats this as a fall-through to ML Kit.
     */
    suspend fun translate(
        text: String,
        source: String,
        target: String,
        modelPath: String,
    ): String = mutex.withLock {
        val didReload = ensureLoaded(modelPath)
        val pair = source to target
        if (didReload || systemPair != pair) {
            // First call after model (re)load, or pair changed: re-establish system prompt.
            engine.setSystemPrompt(systemPromptFor(source, target))
            systemPair = pair
        } else {
            // System prompt KV is still live; just clear the prior turn's chat + KV.
            engine.resetForNextPrompt()
        }
        val sb = StringBuilder()
        engine.sendUserPrompt(buildUserMessage(text, source, target), predictLength = 256)
            .collect { token -> sb.append(token) }
        sb.toString().trim()
    }

    /** Best-effort cleanup. Safe to call at app teardown. */
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
    private suspend fun ensureLoaded(modelPath: String): Boolean {
        if (loadedModelPath == modelPath && engine.state.value.isModelLoaded) return false
        preflightMemory()
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

    private fun preflightMemory() {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        if (mi.availMem < AVAIL_MEM_FLOOR_BYTES) {
            throw TranslateGemmaTransientException(
                "Low memory (${mi.availMem / 1_000_000} MB available, need ${AVAIL_MEM_FLOOR_BYTES / 1_000_000} MB); " +
                    "falling through to ML Kit"
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
        // Below ~4 GB available, loading + KV cache + scratch is at risk of OOM kill.
        // This is the *transient* check at load time (per-attempt), not a permanent device gate.
        private const val AVAIL_MEM_FLOOR_BYTES = 4_000_000_000L
    }
}

/**
 * Transient (retry-later) failure to load or run TranslateGemma.
 *
 * The waterfall in [com.playtranslate.translation.TranslationBackendRegistry.translate]
 * catches this and falls through to the next backend (typically ML Kit). Throwing this
 * does NOT disable TG — the next translate() call may succeed if memory pressure relaxes.
 */
class TranslateGemmaTransientException(message: String) : RuntimeException(message)
