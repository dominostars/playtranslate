package com.playtranslate.translation

import android.content.Context
import com.playtranslate.R
import com.playtranslate.translation.llm.OnDeviceLlmBackend
import com.playtranslate.translation.llm.StatusStringIds
import com.playtranslate.translation.qwen.QwenModel
import com.playtranslate.translation.translategemma.PromptStyle

/**
 * Downloadable on-device translation backend powered by Qwen 2.5 1.5B Instruct
 * (Q4_0 GGUF, ~937 MB) running through the bundled `:llama` JNI bridge.
 *
 * Slots into the waterfall at priority [PRIORITY] — between TranslateGemma
 * (offline LLM, 25) and ML Kit (offline-degraded, 30). When TG isn't installed,
 * disabled, or fails its transient memory preflight, Qwen handles the request
 * before the ML Kit fallback. Smaller (937 MB vs 2.19 GB) and faster (~826ms
 * median vs ~1436ms on Thor) than TG, with a slight quality regression on
 * non-trivial sentences.
 *
 * No `supportsPair` override: Qwen 2.5 is multilingual and we don't gate it
 * pre-launch. Quality on specific pairs is user-discoverable; if a pair returns
 * systematic garbage, the user disables the backend.
 */
class QwenBackend(
    context: Context,
    enabledProvider: () -> Boolean,
) : OnDeviceLlmBackend(context, enabledProvider) {

    override val id: BackendId = "qwen"
    override val displayName: String = context.getString(R.string.qwen_display_name)
    override val priority: Int = PRIORITY
    override val quality: BackendQuality = BackendQuality.Okay
    override val speed: BackendSpeed = BackendSpeed.Slow
    override val modelHelper = QwenModel
    override val promptStyle = PromptStyle.StandardChat

    // Transient floor at LlamaTranslator load time. Qwen 1.5B's Q4_0 working set
    // is ~1.2-1.4 GB on Thor (per spike RAM measurements); 1.5 GB available
    // gives modest headroom without locking out 4 GB devices that legitimately
    // can run the model.
    override val availMemFloorBytes: Long = 1_500_000_000L

    // Permanent device gate. 4 GB total RAM is comfortable for Qwen 1.5B's
    // smaller working set; smaller devices can't run on-device LLMs at all.
    override val totalMemFloorBytes: Long = 4_000_000_000L

    override val statusStringIds = StatusStringIds(
        notDownloaded = R.string.qwen_status_not_downloaded,
        disabled = R.string.qwen_status_downloaded_disabled,
        ready = R.string.qwen_status_ready,
    )

    // Inherits the base `supportsPair` default (any pair where source != target).

    companion object {
        const val PRIORITY = 27
    }
}
