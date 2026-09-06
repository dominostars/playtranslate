package com.playtranslate.translation

import android.content.Context
import com.playtranslate.R
import com.playtranslate.translation.hymt.HyMt2Model
import com.playtranslate.translation.llm.OnDeviceLlmBackend
import com.playtranslate.translation.llm.PromptStyle
import com.playtranslate.translation.llm.StatusStringIds

/**
 * MNN-backed Tencent **Hy-MT2 1.8B** — the translation-specialist on-device
 * tier, replacing the retired [HyMtBackend] (Hunyuan-MT 1.5). Same MNN runtime
 * (`:mnn` Gradle module) as [QwenMnnBackend] / [GemmaE2BMnnBackend] and the
 * same prompt envelope: Hy-MT2's chat template and its model card's default
 * translation prompt are byte-identical to 1.5's, so this backend reuses
 * [PromptStyle.HyMtChat] and
 * [com.playtranslate.translation.hymt.HyMtChatTemplate] unchanged.
 *
 * **Why it replaces 1.5** — Hy-MT2 (released 2026-05-21) is Apache-2.0, where
 * 1.5 is under the Tencent HY Community License whose Territory excludes the
 * EU, UK and South Korea. So this tier ships with **no region gate and no
 * legal attestation**; those two gates exist only on the 1.5 path and go away
 * with it. Model shape is identical (`hunyuan_v1_dense`, hidden 2048, 32
 * layers, 16/4 heads, vocab 120818, tied embeddings) and at our 4-bit block-64
 * quantization the weight file is byte-for-byte the same size as 1.5's, so
 * disk and RAM are unchanged; it is retrained weights in the same skeleton,
 * not a bigger model.
 *
 * Slots into the waterfall at [PRIORITY] = 25, directly below
 * [GemmaE2BMnnBackend] (24, still the absolute quality leader per the spike's
 * judge mean 4.71) and directly above [HyMtBackend] (26). Gemma moved 25 -> 24
 * to open this slot: every relative order in the offline band is unchanged, so
 * a user who keeps 1.5 and never installs Hy-MT2 sees exactly the routing they
 * had before, while a user with both installed gets Hy-MT2.
 *
 * Catalog entry: `engine-hy-mt2-1-8b-mnn` in
 * `app/src/main/assets/langpack_catalog.json` (MultiFile — individual files
 * fetched from the conversion repo, same strategy as 1.5).
 *
 * **Memory floors and star ratings are carried over from the 1.5 spike**
 * (`docs/hy-mt1.5-offline-model-evaluation.md`: ~2.07 GB peak resident, 965 MB
 * on disk) because the tensor shapes are identical.
 *
 * First device numbers (Thor, 2026-09-06, 78 in-app translations alongside
 * live Meiki OCR, anonymous-weights path): cold load 1.76 s, and latency fits
 * ~134 ms fixed + ~30 ms per source character (0.2-0.4 s for short lines,
 * ~0.9 s at 20-40 characters), i.e. an effective ~33 tokens/s, the expected
 * band for a 1.8B 4-bit model on this SoC. These are NOT comparable to the
 * spike's 580 ms median for 1.5, which came from a benchmark loop with no OCR
 * or UI in-process over a corpus we no longer have; treat "faster or slower
 * than 1.5" as unmeasured, and Tencent's ~1.5x claim as unverified. Quality is
 * likewise unjudged.
 *
 * The per-pair prompt-prefix reuse DOES hold here even though this export has
 * a different graph shape from 1.5's (internal KV, 4 inputs, vs wangjazz's
 * external-KV 5-input export): `MnnChatImpl.processSystemPrompt` pins the
 * boundary with `Llm::getCurrentHistory()` and `resetForNextPrompt` rewinds
 * with `Llm::eraseHistory`, both of which read and write `mMeta->previous` in
 * MNN's runtime and never branch on the graph's declared inputs. So the fixed
 * cost above is the model's own floor, not a re-prefill of the instruction
 * prefix on every call.
 */
class HyMt2Backend(
    context: Context,
    enabledProvider: () -> Boolean,
) : OnDeviceLlmBackend(context, enabledProvider) {

    override val id: BackendId = "hymt2_mnn"
    override val displayName: String = context.getString(R.string.hymt2_display_name)
    override val priority: Int = PRIORITY
    override val qualityStars: StarRating = 3.5f
    override val speedStars: StarRating = 3.0f
    override val modelHelper = HyMt2Model
    override val promptStyle = PromptStyle.HyMtChat

    /** Carried from the 1.5 spike's ~2.07 GB peak resident measurement (same
     *  tensor shapes, same quantization): a 2.5 GB per-call floor leaves
     *  ~400 MB above the steady-state working set for prefill activations and
     *  decode buffers. */
    override val availMemFloorBytes: Long = 2_500_000_000L

    /** Device-level gate: 5 GB total RAM minimum, same as 1.5. */
    override val totalMemFloorBytes: Long = 5_000_000_000L

    override val statusStringIds = StatusStringIds(
        notDownloaded = R.string.hymt2_status_not_downloaded,
        disabled = R.string.hymt2_status_downloaded_disabled,
        ready = R.string.hymt2_status_ready,
    )

    companion object {
        /** Specialist-translator slot: below [GemmaE2BMnnBackend.PRIORITY]
         *  (24) and above the retired [HyMtBackend.PRIORITY] (26). */
        const val PRIORITY = 25
    }
}
