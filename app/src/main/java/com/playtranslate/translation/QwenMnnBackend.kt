package com.playtranslate.translation

import android.content.Context
import com.playtranslate.R
import com.playtranslate.translation.llm.OnDeviceLlmBackend
import com.playtranslate.translation.llm.PromptStyle
import com.playtranslate.translation.llm.StatusStringIds
import com.playtranslate.translation.qwen.QwenMnnModel

/**
 * MNN-backed Qwen 2.5 1.5B Instruct — the live-mode on-device LLM tier.
 * Routed through Alibaba's MNN runtime (`:mnn` Gradle module). Per the
 * spike (`mnn-spike/SPIKE_REPORT.md`) on Thor (Snapdragon 8 Gen 2):
 * ~513 ms median wall-time, 0.0% catastrophic failure rate.
 *
 * Slots into the waterfall at priority [PRIORITY] = 26 — below the
 * premium-quality [GemmaE2BMnnBackend] at 25 and above ML Kit at 30. So
 * when both on-device tiers are enabled the manual-lookup tier (E2B) wins
 * first, with Qwen-MNN as the lower-cost fallback before ML Kit.
 *
 * Catalog entry: `engine-qwen-1-5b-mnn` in `app/src/main/assets/langpack_catalog.json`.
 * Distribution: a single zip on HuggingFace (`extract: true` triggers
 * [com.playtranslate.translation.llm.OnDeviceLlmDownloader]'s ZipExtract
 * commit strategy).
 *
 * Default-off: [com.playtranslate.Prefs.qwenMnnEnabled] starts false; Settings
 * flips it on after a successful download or when the user enables an
 * already-extracted install. Dispatch is inherited from
 * [OnDeviceLlmBackend.translate] (routes through MnnTranslator after the
 * :llama strip — no override needed).
 */
class QwenMnnBackend(
    context: Context,
    enabledProvider: () -> Boolean,
) : OnDeviceLlmBackend(context, enabledProvider) {

    override val id: BackendId = "qwen_mnn"
    override val displayName: String = context.getString(R.string.qwen_mnn_display_name)
    override val priority: Int = PRIORITY
    override val quality: BackendQuality = BackendQuality.Okay
    override val speed: BackendSpeed = BackendSpeed.Okay
    override val modelHelper = QwenMnnModel
    override val promptStyle = PromptStyle.StandardChat

    /** ~990 MB resident measured on Thor. 1.5 GB floor leaves headroom for
     *  prefill activations on top. */
    override val availMemFloorBytes: Long = 1_500_000_000L
    override val totalMemFloorBytes: Long = 4_000_000_000L

    override val statusStringIds = StatusStringIds(
        notDownloaded = R.string.qwen_mnn_status_not_downloaded,
        disabled = R.string.qwen_mnn_status_downloaded_disabled,
        ready = R.string.qwen_mnn_status_ready,
    )

    companion object {
        /** Below [GemmaE2BMnnBackend.PRIORITY] (25, manual-lookup tier),
         *  above ML Kit (30). */
        const val PRIORITY = 26
    }
}
