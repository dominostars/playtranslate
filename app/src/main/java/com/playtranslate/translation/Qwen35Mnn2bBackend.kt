package com.playtranslate.translation

import android.content.Context
import com.playtranslate.R
import com.playtranslate.translation.llm.OnDeviceLlmBackend
import com.playtranslate.translation.llm.PromptStyle
import com.playtranslate.translation.llm.StatusStringIds
import com.playtranslate.translation.qwen.Qwen35Mnn2bModel

/**
 * MNN-backed **Qwen 3.5 2B** — the fast on-device LLM tier, replacing the
 * deprecated [QwenMnnBackend] (Qwen 2.5 1.5B). Per the spike on Thor
 * (Snapdragon 8 Gen 2): ~503 ms median wall-time (fastest of every tier,
 * including the smaller Qwen 2.5, thanks to its linear-attention layers),
 * 0.0% catastrophic, blind-judge quality clearly above Qwen 2.5. Apache-2.0.
 *
 * Priority [PRIORITY] = 27 (the fast-tier slot below Gemma 25 / HyMt 26; above
 * the deprecated Qwen 2.5 at 29 and ML Kit at 30).
 *
 * Catalog entry: `engine-qwen-3-5-2b-mnn`. Prompt style [PromptStyle.Qwen35Chat]
 * (no-think envelope). Mixed-attention KV-reuse correctness relies on the
 * linear-state snapshot in the vendored MNN runtime + mnn_chat.cpp.
 */
class Qwen35Mnn2bBackend(
    context: Context,
    enabledProvider: () -> Boolean,
) : OnDeviceLlmBackend(context, enabledProvider) {

    override val id: BackendId = "qwen35_mnn_2b"
    override val displayName: String = context.getString(R.string.qwen35_2b_mnn_display_name)
    override val priority: Int = PRIORITY
    override val qualityStars: StarRating = 3.0f
    override val speedStars: StarRating = 4.5f
    override val modelHelper = Qwen35Mnn2bModel
    override val promptStyle = PromptStyle.Qwen35Chat

    /** ~1.5 GB resident (RssAnon) measured on Thor, 1.90 GB peak VmHWM. 2.0 GB
     *  floor leaves headroom for prefill activations. */
    override val availMemFloorBytes: Long = 2_000_000_000L
    override val totalMemFloorBytes: Long = 6_000_000_000L

    override val statusStringIds = StatusStringIds(
        notDownloaded = R.string.qwen35_2b_mnn_status_not_downloaded,
        disabled = R.string.qwen35_2b_mnn_status_downloaded_disabled,
        ready = R.string.qwen35_2b_mnn_status_ready,
    )

    companion object {
        /** Fast-tier slot: below Gemma (25) / HyMt (26), above deprecated
         *  Qwen 2.5 (29) and ML Kit (30). */
        const val PRIORITY = 27
    }
}
