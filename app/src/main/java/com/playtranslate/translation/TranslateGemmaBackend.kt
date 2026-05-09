package com.playtranslate.translation

import android.content.Context
import com.playtranslate.R
import com.playtranslate.translation.llm.OnDeviceLlmBackend
import com.playtranslate.translation.llm.StatusStringIds
import com.playtranslate.translation.translategemma.PromptStyle
import com.playtranslate.translation.translategemma.TranslateGemmaModel

/**
 * Downloadable on-device translation backend powered by Google's TranslateGemma 4B
 * (Q4_0 GGUF, ~2.19 GB) running through the bundled `:llama` JNI bridge.
 *
 * Slots into the waterfall at priority [PRIORITY] — between Lingva (online, 20)
 * and Qwen / ML Kit (27, 30). When DeepL/Lingva fail (no internet, quota, etc.),
 * TG handles the request before the lighter Qwen and degraded ML Kit fallbacks.
 *
 * No language gate: TG was trained on a broad multilingual mix (WMT24++ covers
 * 51 codes; the model itself reaches further via Gemma 3's pre-training). The
 * earlier en-pivot whitelist was a conservative pre-launch hedge; once Qwen
 * shipped alongside it, real-world quality on non-en pairs becomes the source
 * of truth. If a specific pair returns systematic garbage, file an issue and
 * we can re-add a per-pair filter — but the default is "let it try."
 */
class TranslateGemmaBackend(
    context: Context,
    enabledProvider: () -> Boolean,
) : OnDeviceLlmBackend(context, enabledProvider) {

    override val id: BackendId = "translategemma"
    override val displayName: String = context.getString(R.string.translategemma_display_name)
    override val priority: Int = PRIORITY
    override val quality: BackendQuality = BackendQuality.Better
    override val speed: BackendSpeed = BackendSpeed.VerySlow
    override val modelHelper = TranslateGemmaModel
    override val promptStyle = PromptStyle.Gemma3Prefix

    // Transient floor at LlamaTranslator load time. TG 4B's working set
    // (~2.2 GB GGUF mmap + ~200 MB KV + scratch) needs comfortable headroom
    // on a 6 GB device. Below this, the waterfall falls through to the next
    // backend rather than risking an OOM kill.
    override val availMemFloorBytes: Long = 4_000_000_000L

    // Permanent device gate. Below 6 GB total RAM, TG 4B would run too close
    // to the OOM-killer threshold even at peak available-memory. Surfaced
    // both to the Settings row (which disables itself when the device falls
    // short) and to the downloader (which refuses to fetch the 2 GB GGUF on
    // a device that can't run it).
    override val totalMemFloorBytes: Long = 6_000_000_000L

    override val statusStringIds = StatusStringIds(
        notDownloaded = R.string.translategemma_status_not_downloaded,
        disabled = R.string.translategemma_status_downloaded_disabled,
        ready = R.string.translategemma_status_ready,
    )

    // Inherits the base `supportsPair` default (any pair where source != target).

    companion object {
        const val PRIORITY = 25
    }
}
