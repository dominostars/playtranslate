package com.playtranslate.translation

import android.content.Context
import com.playtranslate.R
import com.playtranslate.translation.llm.OnDeviceLlmBackend
import com.playtranslate.translation.llm.StatusStringIds
import com.playtranslate.translation.translategemma.PromptStyle
import com.playtranslate.translation.translategemma.TranslateGemmaModel
import java.util.Locale

/**
 * Downloadable on-device translation backend powered by Google's TranslateGemma 4B
 * (Q4_0 GGUF, ~2.19 GB) running through the bundled `:llama` JNI bridge.
 *
 * Slots into the waterfall at priority [PRIORITY] — between Lingva (online, 20)
 * and ML Kit (offline-degraded, 30). When DeepL/Lingva fail (no internet, quota,
 * etc.), TG handles the request before the ML Kit fallback.
 *
 * v1 keeps the en-pivot pair gate from the pre-abstraction backend
 * (`source == "en" || target == "en"`). TG's training data (WMT24++) is
 * en-↔-X centric; whether non-en-pivot pairs hold quality is unverified.
 * Removal of the gate is parked as a separate behavior-change commit (Phase 3
 * of the abstraction roll-out), bundled with a non-en-pivot quality check.
 */
class TranslateGemmaBackend(
    context: Context,
    enabledProvider: () -> Boolean,
) : OnDeviceLlmBackend(context, enabledProvider) {

    override val id: BackendId = "translategemma"
    override val displayName: String = context.getString(R.string.translategemma_display_name)
    override val priority: Int = PRIORITY
    override val quality: BackendQuality = BackendQuality.Better
    override val modelHelper = TranslateGemmaModel
    override val promptStyle = PromptStyle.Gemma3Prefix

    // Transient floor at LlamaTranslator load time. TG 4B's working set
    // (~2.2 GB GGUF mmap + ~200 MB KV + scratch) needs comfortable headroom
    // on a 6 GB device. Below this, the waterfall falls through to the next
    // backend rather than risking an OOM kill.
    override val availMemFloorBytes: Long = 4_000_000_000L

    override val statusStringIds = StatusStringIds(
        notDownloaded = R.string.translategemma_status_not_downloaded,
        disabled = R.string.translategemma_status_downloaded_disabled,
        ready = R.string.translategemma_status_ready,
    )

    /**
     * Preserved from pre-abstraction TG behavior: en-pivot pairs only.
     * Removal is deferred to a deliberate behavior-change commit so the
     * pure refactor here has zero behavior delta.
     */
    override fun supportsPair(source: String, target: String): Boolean {
        val s = source.lowercase(Locale.ROOT)
        val t = target.lowercase(Locale.ROOT)
        if (s !in TG_SUPPORTED_LANGS) return false
        if (t !in TG_SUPPORTED_LANGS) return false
        return s == "en" || t == "en"
    }

    companion object {
        const val PRIORITY = 25

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
