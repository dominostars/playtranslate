package com.playtranslate.translation

import android.content.Context
import com.playtranslate.R
import com.playtranslate.translation.hymt.HyMtModel
import com.playtranslate.translation.llm.OnDeviceLlmBackend
import com.playtranslate.translation.llm.PromptStyle
import com.playtranslate.translation.llm.StatusStringIds

/**
 * MNN-backed Tencent Hunyuan-MT 1.5 1.8B — the **retired** translation-
 * specialist on-device tier (Tencent HY Community License, restricted to
 * outside EU/UK/SK). Routed through the same MNN runtime (`:mnn` Gradle
 * module) as [QwenMnnBackend] and [GemmaE2BMnnBackend]; the
 * [HyMtChatTemplate] supplies Hunyuan's single-user-turn envelope
 * ([PromptStyle.HyMtChat]).
 *
 * **Retired in favour of [HyMt2Backend]** — Hy-MT2 is the same model shape
 * retrained, under Apache-2.0 instead of a licence whose Territory excludes
 * the EU/UK/KR. Its catalog entry carries `"deprecated": true`, so the
 * Settings row is shown **only while the model is fully installed**: nobody
 * can start a fresh download, existing installs keep working with a
 * DEPRECATED badge, and a launch-time sweep drops any half-finished download.
 * Nothing here reclaims disk — a user who keeps the model deletes it from the
 * row when they choose to. The region gate below stays for exactly as long as
 * this backend does; it is what keeps an already-installed model from being
 * *used* after a move into a restricted territory, which hiding a row cannot
 * do.
 *
 * Slots into the waterfall at [PRIORITY] = 26 — unchanged, so a user who
 * keeps 1.5 and never installs Hy-MT2 gets exactly the routing they had:
 * above [QwenMnnBackend] (29) and Qwen 3.5 (27) so Hunyuan's stronger
 * translation quality (LLM-judge mean 4.50 vs Qwen 2.5's 4.02 per the
 * 500-sentence spike) still wins when both are enabled. [HyMt2Backend] (25)
 * and Gemma 4 E2B (24) rank above it.
 *
 * **Region gating** — this backend is only registered after passing the
 * [com.playtranslate.region.RegionPolicy.isHunyuanRestricted] check in
 * [com.playtranslate.ui.SettingsRenderer]. A user in a restricted region
 * never sees the catalog row, never gets the legal-attestation dialog, and
 * never downloads the model. Default-off (the toggle starts false and never
 * flips on until the user explicitly accepts the legal dialog and the
 * download completes); see [com.playtranslate.Prefs.hyMtEnabled] and
 * [com.playtranslate.Prefs.hyMtLegalAccepted].
 *
 * Catalog entry: `engine-hunyuan-mt1-5-1-8b-mnn` in
 * `app/src/main/assets/langpack_catalog.json`. Distribution: a single zip
 * (`extract: true` triggers [com.playtranslate.translation.llm.OnDeviceLlmDownloader]'s
 * ZipExtract commit strategy).
 *
 * **Spike-measured numbers** (Thor / Snapdragon 8 Gen 2, 4 threads,
 * `benchmark_reuse()` harness over 500 Persona 5 ja→en sentences — see
 * `mnn-spike/HYMT_SPIKE_REPORT.md`): 580 ms median wall-time, 0%
 * catastrophic failure rate, 0.8% cultural-term preservation (not failure),
 * ~2.07 GB peak resident RAM, 965 MB on-device disk footprint.
 */
class HyMtBackend(
    context: Context,
    enabledProvider: () -> Boolean,
) : OnDeviceLlmBackend(context, enabledProvider) {

    override val id: BackendId = "hymt_mnn"
    override val displayName: String = context.getString(R.string.hymt_display_name)
    override val priority: Int = PRIORITY
    override val qualityStars: StarRating = 3.5f
    override val speedStars: StarRating = 3.0f
    override val modelHelper = HyMtModel
    override val promptStyle = PromptStyle.HyMtChat

    /** Spike-measured peak resident RAM: ~2.07 GB. Per-call floor at 2.5 GB
     *  gives ~400 MB headroom above the steady-state working set for prefill
     *  activations and decode buffers. */
    override val availMemFloorBytes: Long = 2_500_000_000L

    /** Device-level gate: 5 GB total RAM minimum. Bumped from the Qwen-MNN
     *  pattern's 4 GB because Hunyuan-MT's peak working set is larger; on a
     *  6 GB device with Hunyuan-MT active, headroom for the OS + foreground
     *  app + sibling backgrounded apps stays above LMK-kill territory. */
    override val totalMemFloorBytes: Long = 5_000_000_000L

    override val statusStringIds = StatusStringIds(
        notDownloaded = R.string.hymt_status_not_downloaded,
        disabled = R.string.hymt_status_downloaded_disabled,
        ready = R.string.hymt_status_ready,
    )

    companion object {
        /** Below [HyMt2Backend.PRIORITY] (25, the replacement) and above
         *  [Qwen35Mnn2bBackend.PRIORITY] (27). Deliberately unchanged by the
         *  Hy-MT2 rollout. See the class kdoc for the rationale. */
        const val PRIORITY = 26
    }
}
