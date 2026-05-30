package com.playtranslate.translation

import android.content.Context
import com.playtranslate.R
import com.playtranslate.translation.llm.OnDeviceLlmBackend
import com.playtranslate.translation.llm.PromptStyle
import com.playtranslate.translation.llm.StatusStringIds
import com.playtranslate.translation.qwen.Qwen35Mnn4bModel

/**
 * MNN-backed **Qwen 3.5 4B** — the Apache-2.0 high-quality on-device LLM tier.
 * Per the spike on Thor (Snapdragon 8 Gen 2) it ties Gemma 4 E2B at the top of
 * the blind judge (12.17 vs 12.06 / 15 — within noise) and is clearly ahead of
 * the WMT-specialist HyMt 1.5, at 0.0% catastrophic / 0.0% CJK-echo. Being
 * Apache-2.0 it carries no geo-restriction (unlike HyMt's Tencent license).
 * Cost vs Gemma: ~30% slower (~1.28 s median, the slowest tier — a tap-and-wait
 * option); RAM is the same class. See `mnn-spike/QWEN35_SPIKE_REPORT.md`.
 *
 * Priority [PRIORITY] = 24 — above Gemma (25), as the new quality leader.
 *
 * Catalog entry: `engine-qwen-3-5-4b-mnn`. Prompt style [PromptStyle.Qwen35Chat]
 * (no-think envelope). Mixed-attention KV-reuse correctness relies on the
 * linear-state snapshot in the vendored MNN runtime + mnn_chat.cpp.
 */
class Qwen35Mnn4bBackend(
    context: Context,
    enabledProvider: () -> Boolean,
) : OnDeviceLlmBackend(context, enabledProvider) {

    override val id: BackendId = "qwen35_mnn_4b"
    override val displayName: String = context.getString(R.string.qwen35_4b_mnn_display_name)
    override val priority: Int = PRIORITY
    override val qualityStars: StarRating = 4.0f
    override val speedStars: StarRating = 1.5f
    override val modelHelper = Qwen35Mnn4bModel
    override val promptStyle = PromptStyle.Qwen35Chat

    /** ~3.1 GB resident (RssAnon) measured on Thor, 3.44 GB peak VmHWM. 3.5 GB
     *  floor leaves headroom for prefill activations. */
    override val availMemFloorBytes: Long = 3_500_000_000L
    override val totalMemFloorBytes: Long = 8_000_000_000L

    override val statusStringIds = StatusStringIds(
        notDownloaded = R.string.qwen35_4b_mnn_status_not_downloaded,
        disabled = R.string.qwen35_4b_mnn_status_downloaded_disabled,
        ready = R.string.qwen35_4b_mnn_status_ready,
    )

    companion object {
        /** Quality-leader slot: above Gemma (25). */
        const val PRIORITY = 24
    }
}
