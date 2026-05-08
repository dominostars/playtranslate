package com.playtranslate.translation

import android.content.Context
import com.playtranslate.R
import com.playtranslate.translation.translategemma.LlamaTranslator
import com.playtranslate.translation.translategemma.PromptStyle
import com.playtranslate.translation.translategemma.TranslateGemmaModel
import java.util.Locale

/**
 * Downloadable on-device translation backend powered by Google's TranslateGemma 4B
 * (Q4_0 GGUF, ~2.19 GB) running through the bundled `:llama` JNI bridge.
 *
 * Slots into the waterfall at priority [PRIORITY] — between Lingva (online, 20)
 * and ML Kit (offline-degraded, 30). When DeepL/Lingva fail (no internet, quota,
 * etc.), TG handles the request before the ML Kit fallback. When the user's pair
 * isn't in TG's supported set, [isUsable] returns false and ML Kit handles it.
 *
 * v1 only allows en-pivot pairs (`source == "en" || target == "en"`). TG's
 * training data (WMT24++) is en-↔-X centric; whether non-en-pivot pairs hold
 * quality is unverified. Expand after Phase F validates a non-en-pivot sample.
 */
class TranslateGemmaBackend(
    private val context: Context,
    private val enabledProvider: () -> Boolean,
) : TranslationBackend {

    override val id: BackendId = "translategemma"
    override val displayName: String = context.getString(R.string.translategemma_display_name)
    override val priority: Int = PRIORITY
    override val requiresInternet: Boolean = false
    override val isDegradedFallback: Boolean = false
    override val quality: BackendQuality = BackendQuality.Better

    override fun isUsable(source: String, target: String): Boolean {
        if (!enabledProvider()) return false
        if (!TranslateGemmaModel.isInstalled(context)) return false
        return supportsPair(source, target)
    }

    override suspend fun translate(text: String, source: String, target: String): String {
        val modelPath = TranslateGemmaModel.file(context).absolutePath
        return LlamaTranslator.getInstance(context).translate(
            text = text,
            source = source,
            target = target,
            modelPath = modelPath,
            promptStyle = PromptStyle.Gemma3Prefix,
            availMemFloorBytes = AVAIL_MEM_FLOOR_BYTES,
        )
    }

    // No-op: the underlying LlamaTranslator is a process-singleton shared with any
    // sibling on-device backend (e.g. Qwen). Closing it from one backend's close()
    // would tear down the engine for the still-active sibling. Engine teardown
    // happens via process death; explicit teardown is via LlamaTranslator.close().
    override fun close() {}

    override val status: BackendStatus
        get() {
            val sizeStr = TranslateGemmaModel.humanSize(context)
            return when {
                !TranslateGemmaModel.isInstalled(context) ->
                    BackendStatus.Info(
                        context.getString(R.string.translategemma_status_not_downloaded, sizeStr),
                        Tone.Neutral,
                    )
                !enabledProvider() ->
                    BackendStatus.Info(
                        context.getString(R.string.translategemma_status_downloaded_disabled, sizeStr),
                        Tone.Neutral,
                    )
                else ->
                    BackendStatus.Info(
                        context.getString(R.string.translategemma_status_ready, sizeStr),
                        Tone.Accent,
                    )
            }
        }

    private fun supportsPair(source: String, target: String): Boolean {
        val s = source.lowercase(Locale.ROOT)
        val t = target.lowercase(Locale.ROOT)
        if (s !in TG_SUPPORTED_LANGS) return false
        if (t !in TG_SUPPORTED_LANGS) return false
        if (s == t) return false
        // V1: en-pivot only. ja→fr et al. fall through to ML Kit.
        return s == "en" || t == "en"
    }

    companion object {
        const val PRIORITY = 25

        // Transient floor at LlamaTranslator load time. TG 4B's working set
        // (~2.2 GB GGUF mmap + ~200 MB KV + scratch) needs comfortable headroom
        // on a 6 GB device. Below this, the waterfall falls through to the next
        // backend rather than risking an OOM kill.
        private const val AVAIL_MEM_FLOOR_BYTES = 4_000_000_000L

        /**
         * The 51 languages TranslateGemma 4B is trained on (WMT24++ benchmark coverage).
         * Source: https://huggingface.co/datasets/google/wmt24pp — the 55 entries
         * deduplicated by primary subtag (e.g. `pt_BR` and `pt_PT` both → `pt`).
         */
        private val TG_SUPPORTED_LANGS = setOf(
            "ar", "bg", "bn", "ca", "cs", "da", "de", "el", "en", "es",
            "et", "fa", "fi", "fil", "fr", "gu", "he", "hi", "hr", "hu",
            "id", "is", "it", "ja", "kn", "ko", "lt", "lv", "ml", "mr",
            "nl", "no", "pa", "pl", "pt", "ro", "ru", "sk", "sl", "sr",
            "sv", "sw", "ta", "te", "th", "tr", "uk", "ur", "vi", "zh", "zu",
        )
    }
}
