package com.playtranslate.translation.mnn

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import android.util.Log
import com.playtranslate.mnn.InferenceEngine
import com.playtranslate.mnn.MMAP_CACHE_DIR_NAME
import com.playtranslate.mnn.MnnChat
import com.playtranslate.mnn.isModelLoaded
import com.playtranslate.translation.gemma.GemmaE2BChatTemplate
import com.playtranslate.translation.hymt.HyMtChatTemplate
import com.playtranslate.translation.llm.OnDeviceLlmTransientException
import com.playtranslate.translation.llm.PromptStyle
import com.playtranslate.translation.qwen.QwenChatTemplate
import java.io.File
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
        val inferStartNs = System.nanoTime()
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
            PromptStyle.Qwen35Chat -> {
                // Qwen 3.5: same ChatML system block as StandardChat (Qwen
                // template), but the user turn opens the assistant role with the
                // non-thinking marker so the hybrid-reasoning model skips the
                // <think> pass. The system-boundary linear-state snapshot
                // (mnn_chat.cpp) keeps KV-reuse correct for Qwen 3.5's mixed
                // attention; no Kotlin change needed here beyond the no-think
                // user block.
                if (didReload || systemPair != pair) {
                    engine.setSystemPrompt(QwenChatTemplate.systemBlock(source, target))
                    systemPair = pair
                } else {
                    engine.resetForNextPrompt()
                }
                engine.sendUserPrompt(
                    QwenChatTemplate.userBlockNoThink(text, source, target),
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
            PromptStyle.HyMtChat -> {
                if (didReload || systemPair != pair) {
                    // Hunyuan-MT 1.5 has no system role per the model card;
                    // the "system block" we set here is the invariant
                    // instruction prefix (`<bos><｜hy_User｜>Translate the
                    // following text into …\n\n`). The per-sentence body
                    // and `<｜hy_Assistant｜>` open marker come from
                    // [HyMtChatTemplate.userBlock]. The model sees one user
                    // turn at inference — the cache split is invisible
                    // because `use_template:false` in mnn_chat.cpp
                    // concatenates the two strings verbatim. Matches the
                    // spike's `benchmark_reuse()` exactly (sysLen=23
                    // tokens cached; see mnn-spike/HYMT_SPIKE_REPORT.md).
                    engine.setSystemPrompt(HyMtChatTemplate.systemBlock(source, target))
                    systemPair = pair
                } else {
                    engine.resetForNextPrompt()
                }
                engine.sendUserPrompt(
                    HyMtChatTemplate.userBlock(text, source, target),
                    predictLength = 256,
                ).collect { token -> sb.append(token) }
            }
        }
        Log.i(
            TAG,
            "MNN-TIMING inference: firstAfterLoad=$didReload style=$promptStyle " +
                "elapsed=${(System.nanoTime() - inferStartNs) / 1_000_000}ms chars=${text.length}",
        )
        // Defensive cleanup: a clean run terminates at the model's EOS marker
        // via Llm::is_stop and the decoded text won't include the marker, but
        // strip any leaked tokens to be safe (e.g. if a future model variant
        // emits the marker as text instead of as a special-token id). All
        // four are listed so the strip is engine-agnostic.
        return sb.toString()
            .substringBefore("<|im_end|>")
            .substringBefore("<turn|>")
            .substringBefore("<|endoftext|>")
            .substringBefore("<｜hy_place▁holder▁no▁2｜>")
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

        // **Swap-cleanup runs BEFORE the availMem read.** If a different model
        // is loaded, free its working set first so the mmap-vs-anon decision
        // sees the post-unload state. Without this ordering, swapping from a
        // low-floor backend (Qwen-MNN ~1.5 GB) to a high-floor one (E2B ~3.5 GB)
        // on a tight 6 GB device would needlessly pick mmap even though there'd
        // be plenty of memory after the prior unload.
        if (engine.state.value.isModelLoaded && loadedModelPath != modelPath) {
            Log.i(TAG, "Swap-cleanup: unloading $loadedModelPath before loading $modelPath")
            runCatching { engine.cleanUp() }
                .onFailure { Log.w(TAG, "swap-cleanup encountered $it (ignored)") }
            loadedModelPath = null
            systemPair = null
        }

        // Decide mmap-vs-anonymous at load ("initialization") time from live
        // availMem. Below the model's resident floor → load weights as
        // reclaimable, file-backed mmap pages: slower (~16%), but the kernel can
        // page them out under pressure instead of OOM-killing us, and the load
        // proceeds where the anon path would have. Above it → the faster anon
        // path. We no longer skip-and-fall-through on low memory — we adapt.
        // (availMem under-counts reclaimable cached-process RAM, so this errs
        // toward mmap on busy high-RAM devices: a safe, conservative default.)
        val useMmap = lowOnMemory(availMemFloorBytes)

        // Low memory means we need mmap (reclaimable weights). If its on-disk
        // cache can't fit, falling back to the anon path would load the full
        // weights into already-tight RAM and risk an OOM-kill — so instead fail
        // out here and let the waterfall fall through to a lighter backend
        // (online / ML Kit / Bergamot). The check is sized to the real cache
        // footprint, so we never start a write we can't finish.
        if (useMmap && !mmapCacheCanFit(modelPath)) {
            throw OnDeviceLlmTransientException(
                "Low memory and insufficient disk for the mmap weight cache; falling through to next backend"
            )
        }

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
        engine.loadModel(modelPath, useMmap)
        loadedModelPath = modelPath
        systemPair = null
        return true
    }

    /** Live availMem < the model's resident floor — the signal to load in mmap
     *  (reclaimable) mode rather than anonymous. Read at load time, so the
     *  loaded instance keeps whichever mode the conditions chose until it is
     *  reloaded. */
    private fun lowOnMemory(availMemFloorBytes: Long): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.availMem < availMemFloorBytes
    }

    /** True if the mmap weight cache for the model at [modelPath] is already on
     *  disk (warm — no write needed), or there's room to write it (cold). The
     *  cache is a rearranged copy of the model's externalized weights, so we
     *  require free space >= the sum of its weight files plus headroom.
     *  Deliberately conservative: it counts every `*.weight` / `*.bin`, even
     *  ones that may not externalize, so it can occasionally fall through when
     *  the cache would just fit — preferable to a half-written cache when the
     *  disk fills mid-load (not a catchable failure). */
    private fun mmapCacheCanFit(modelPath: String): Boolean {
        val cacheDir = File(modelPath, MMAP_CACHE_DIR_NAME)
        if (cacheDir.listFiles()?.any { it.name.endsWith("sync.static") } == true) return true
        val weightBytes = File(modelPath).listFiles()
            ?.filter { it.isFile && (it.name.endsWith(".weight") || it.name.endsWith(".bin")) }
            ?.sumOf { it.length() } ?: 0L
        if (weightBytes <= 0L) return false
        val free = try {
            StatFs(modelPath).availableBytes
        } catch (e: Exception) {
            Log.w(TAG, "StatFs failed for $modelPath: ${e.message}; treating mmap cache as not fitting")
            return false
        }
        if (free < weightBytes + 100L * 1024 * 1024) return false
        // Disk room is there; ensure the cache dir itself is creatable too, so a
        // mkdir failure also falls through to a lighter backend rather than
        // letting resolveMmapDir() return "" and silently downgrade to anon.
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            Log.w(TAG, "mmap cache dir can't be created: $cacheDir; treating as not fitting")
            return false
        }
        return true
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
