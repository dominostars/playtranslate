package com.playtranslate.translation

import android.content.Context
import com.playtranslate.R
import com.playtranslate.translation.llm.OnDeviceLlmBackend
import com.playtranslate.translation.llm.PromptStyle
import com.playtranslate.translation.llm.StatusStringIds
import com.playtranslate.translation.mnn.MnnTranslator
import com.playtranslate.translation.qwen.QwenMnnModel

/**
 * MNN-backed Qwen 2.5 1.5B Instruct tier. Same model as [QwenBackend], but
 * routed through Alibaba's MNN runtime (`:mnn` Gradle module) instead of
 * llama.cpp — the spike (`mnn-spike/SPIKE_REPORT.md`) measured 1.45× faster
 * translations on Thor (Snapdragon 8 Gen 2) than the legacy GGUF path with
 * marginally better quality (0.00% vs 0.20% catastrophic).
 *
 * Slots into the waterfall at priority [PRIORITY] = 26 — between TranslateGemma
 * (25, premium-quality slow tier) and the legacy [QwenBackend] (27, slower
 * GGUF Qwen). So when both Qwen tiers are enabled the MNN tier is tried first;
 * a transient memory failure falls through to the legacy Qwen rather than
 * directly to ML Kit. The override of [translate] re-routes the
 * [OnDeviceLlmBackend] base's default (which calls [LlamaTranslator]) through
 * [MnnTranslator] instead.
 *
 * Catalog entry: `engine-qwen-1-5b-mnn` in `app/src/main/assets/langpack_catalog.json`.
 * Distribution: a single zip on HuggingFace (`extract: true` in the catalog
 * triggers the [com.playtranslate.translation.llm.OnDeviceLlmDownloader]'s
 * ZipExtract commit strategy). The catalog's size/sha256 are placeholders
 * until the upload completes — see TODO inline in the catalog JSON.
 *
 * Default-off: [com.playtranslate.Prefs.qwenMnnEnabled] starts false; Settings
 * flips it on after a successful download or when the user enables an
 * already-extracted install.
 */
class QwenMnnBackend(
    context: Context,
    enabledProvider: () -> Boolean,
) : OnDeviceLlmBackend(context, enabledProvider) {

    override val id: BackendId = "qwen_mnn"
    override val displayName: String = context.getString(R.string.qwen_mnn_display_name)
    override val priority: Int = PRIORITY
    override val quality: BackendQuality = BackendQuality.Okay
    override val speed: BackendSpeed = BackendSpeed.Moderate
    override val modelHelper = QwenMnnModel
    override val promptStyle = PromptStyle.StandardChat

    // Same working-set ballpark as the legacy GGUF Qwen — same underlying
    // model, just a different runtime. Per the spike's RAM measurements,
    // MNN's working set sits within ~10% of llama.cpp's for Qwen 1.5B.
    override val availMemFloorBytes: Long = 1_500_000_000L
    override val totalMemFloorBytes: Long = 4_000_000_000L

    override val statusStringIds = StatusStringIds(
        notDownloaded = R.string.qwen_mnn_status_not_downloaded,
        disabled = R.string.qwen_mnn_status_downloaded_disabled,
        ready = R.string.qwen_mnn_status_ready,
    )

    /**
     * Engine-specific override of [OnDeviceLlmBackend.translate]: dispatch
     * through [MnnTranslator] instead of the base class's default
     * [com.playtranslate.translation.translategemma.LlamaTranslator]. This is
     * why the base method had its `final` modifier removed in the
     * shared-infrastructure refactor — every other on-device backend keeps
     * the default; only this one re-routes.
     */
    override suspend fun translate(text: String, source: String, target: String): String =
        MnnTranslator.getInstance(context).translate(
            text = text,
            source = source,
            target = target,
            modelPath = modelHelper.file(context).absolutePath,
            promptStyle = promptStyle,
            availMemFloorBytes = availMemFloorBytes,
        )

    companion object {
        /** Just above the legacy Qwen (27); both below TG (25). */
        const val PRIORITY = 26
    }
}
