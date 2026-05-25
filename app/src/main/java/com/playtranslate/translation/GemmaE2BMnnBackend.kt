package com.playtranslate.translation

import android.content.Context
import com.playtranslate.R
import com.playtranslate.translation.gemma.GemmaE2BMnnModel
import com.playtranslate.translation.llm.OnDeviceLlmBackend
import com.playtranslate.translation.llm.PromptStyle
import com.playtranslate.translation.llm.StatusStringIds

/**
 * MNN-backed Gemma 4 E2B Instruct tier — the premium-quality manual-lookup
 * backend, replacing the legacy `TranslateGemmaBackend` (Gemma 3 4B via
 * llama.cpp). Slots at priority [PRIORITY] = 25, where TG used to live.
 *
 * Per the Gemma-4 follow-up spike (`mnn-spike/SPIKE_REPORT.md`), measured on
 * Thor (Snapdragon 8 Gen 2 / 16 GB) against the Q4_K_M TG baseline on the
 * same 500-sentence p5_500_ja corpus:
 * - **1.48× faster median latency** (991 ms vs TG's 1471 ms)
 * - **Identical 0.4% combined failure rate** (1 cjk-echo + 1 options-dump)
 * - **Quality tied** on a 20-sample stratified A/B (TG wins 4/20 on idiomatic
 *   naturalness for multi-clause sentences, E2B wins 4/20 on accuracy and
 *   subject preservation for short fragments, 9/20 comparable)
 * - **~1.9 GB less RAM** in the production app process (~3.5 GB vs ~5.6 GB)
 * - **Apache 2.0 license** (vs Gemma 3's custom Gemma TOS)
 *
 * E4B was rejected (5% options-dump failure rate + 1.55× slower than TG).
 *
 * No `translate(...)` override — the [OnDeviceLlmBackend] parent's default
 * routes through [com.playtranslate.translation.mnn.MnnTranslator] now (after
 * the :llama strip), so the dispatch is automatic.
 *
 * Disk footprint depends on which bundle ships: Phase 1 testing uses the
 * multimodal taobao-mnn bundle (3.5 GB on disk, ~3.3 GB resident in MNN's
 * anonymous heap); Phase 2 aims for the text-only conversion (~2.7 GB).
 * Memory floors set for the multimodal worst case; revisit when Phase 2 lands.
 */
class GemmaE2BMnnBackend(
    context: Context,
    enabledProvider: () -> Boolean,
) : OnDeviceLlmBackend(context, enabledProvider) {

    override val id: BackendId = "gemma_e2b_mnn"
    override val displayName: String = context.getString(R.string.gemma_e2b_mnn_display_name)
    override val priority: Int = PRIORITY
    override val qualityStars: StarRating = 4.0f
    override val speedStars: StarRating = 2.0f
    override val modelHelper = GemmaE2BMnnModel
    override val promptStyle = PromptStyle.Gemma4Chat

    /** ~3.3 GB resident RAM measured on Thor for the multimodal E2B in
     *  standalone llm_demo; in-app process adds ~200-500 MB baseline +
     *  ML Kit OCR + native lib overhead. 3.5 GB floor leaves enough headroom
     *  for prefill activations on top of the resident set. Revisit once
     *  Phase 2 text-only conversion is measured. */
    override val availMemFloorBytes: Long = 3_500_000_000L

    /** Gates lower-end devices off this backend entirely — 6 GB total RAM
     *  is the realistic floor for E2B's ~3.5 GB working set plus an active
     *  Android app's other allocations. */
    override val totalMemFloorBytes: Long = 6_000_000_000L

    override val statusStringIds = StatusStringIds(
        notDownloaded = R.string.gemma_e2b_mnn_status_not_downloaded,
        disabled = R.string.gemma_e2b_mnn_status_downloaded_disabled,
        ready = R.string.gemma_e2b_mnn_status_ready,
    )

    companion object {
        /** Where TranslateGemma used to sit — the premium-quality manual-lookup
         *  slot. Above [QwenMnnBackend.PRIORITY] (26, live-mode tier). */
        const val PRIORITY = 25
    }
}
