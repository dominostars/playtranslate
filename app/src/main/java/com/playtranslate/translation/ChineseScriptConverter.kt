package com.playtranslate.translation

import com.github.houbb.opencc4j.util.ZhConverterUtil
import com.github.houbb.opencc4j.util.ZhHkConverterUtil
import com.github.houbb.opencc4j.util.ZhTwConverterUtil
import com.playtranslate.language.ChineseScriptVariant
import java.util.concurrent.ConcurrentHashMap

/**
 * Render-time Chinese script conversion (OpenCC via opencc4j). Almost always
 * Simplified→Traditional (every translation/gloss producer emits Simplified),
 * but also Traditional→Simplified for the one reverse case: a ZH_HANT source
 * (Traditional OCR) translated to a Simplified target via the same-language
 * passthrough.
 *
 * Phase 0 spike finding: opencc4j delivers full phrase-level VOCABULARY
 * localization for Taiwan (软件→軟體, 鼠标→滑鼠, 互联网→網際網路 — not just
 * glyphs), and glyph-level conversion for generic Traditional and Hong Kong.
 * OpenCC upstream defines no `s2hkp` phrase config, so HK is glyph-level by
 * nature — which matches HK formal written Chinese (it uses mainland-like
 * vocabulary with HK glyph variants such as 裏 vs 裡).
 *
 * Every translation/gloss producer emits Simplified; conversion is applied
 * strictly at display/return time, never before a translation-cache or
 * gloss-FST write — those store Simplified and are shared across all variants
 * (keyed on [ChineseScriptVariant.BACKEND_CODE]). The opencc4j util classes are
 * static and self-cache their dictionaries, so [convert] is stateless and safe
 * to call concurrently from background dispatchers after warm-up.
 */
class ChineseScriptConverter private constructor(
    private val op: (String) -> String,
) {
    fun convert(text: String): String = if (text.isBlank()) text else op(text)

    companion object {
        private val cache = ConcurrentHashMap<Pair<ChineseScriptVariant, Boolean>, ChineseScriptConverter>()

        /**
         * Converter that renders text into [variant]'s script, or null when no
         * conversion is needed (the input is already in the target script).
         *
         * Input is Simplified unless [inputIsTraditional] — true only for a
         * ZH_HANT-source same-language passthrough, where the OCR text is already
         * Traditional. Direction is chosen from (input script, [variant]):
         * Simplified→Traditional/TW/HK via s2t/s2tw/s2hk, and Traditional→Simplified
         * via t2s (what a ZH_HANT source + Simplified target needs). Same-script
         * pairs return null. Traditional→TW/HK regional glyph normalization isn't
         * cleanly supported by opencc4j, so it's a no-op — the text is already
         * Traditional.
         */
        fun forVariant(
            variant: ChineseScriptVariant,
            inputIsTraditional: Boolean = false,
        ): ChineseScriptConverter? {
            val op: ((String) -> String)? = when (variant) {
                // Simplified target: convert only when the input is Traditional (t2s).
                ChineseScriptVariant.SIMPLIFIED ->
                    if (inputIsTraditional) ZhConverterUtil::toSimple else null
                // Traditional targets: convert only when the input is Simplified;
                // already-Traditional input is left as-is.
                ChineseScriptVariant.TRADITIONAL ->
                    if (inputIsTraditional) null else ZhConverterUtil::toTraditional
                ChineseScriptVariant.TRADITIONAL_TW ->
                    if (inputIsTraditional) null else ZhTwConverterUtil::toTraditional
                ChineseScriptVariant.TRADITIONAL_HK ->
                    if (inputIsTraditional) null else ZhHkConverterUtil::toTraditional
            }
            return op?.let { fn ->
                cache.getOrPut(variant to inputIsTraditional) { ChineseScriptConverter(fn) }
            }
        }

        /**
         * Converter for a `(targetLang, variant)` pair, or null when [targetLang]
         * isn't Chinese (so a stale [variant] under a non-Chinese target can't
         * fire). The single guarded entry point for both the sentence and gloss
         * paths.
         */
        fun forTarget(
            targetLang: String,
            variant: ChineseScriptVariant,
            inputIsTraditional: Boolean = false,
        ): ChineseScriptConverter? =
            if (ChineseScriptVariant.isChineseTarget(targetLang))
                forVariant(variant, inputIsTraditional)
            else null
    }
}
