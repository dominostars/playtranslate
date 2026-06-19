package com.playtranslate.language

/**
 * Splits Thai text (which has no inter-word spaces) into in-order word
 * surfaces.
 *
 * **Verbatim-substring contract:** every returned surface is a verbatim
 * substring of the input, so downstream tap-to-lookup can re-locate it via
 * `text.indexOf(surface)` (the offsetless [TokenSpan] contract — see
 * `DragLookupController`). Implementations must NOT normalize, reorder, or
 * otherwise rewrite tokens; all normalization belongs in the dictionary lookup
 * key. `segment(s).joinToString("") == s` always holds.
 *
 * The v1 implementation is [MaximalMatchThaiSegmenter] (a faithful port of
 * PyThaiNLP's `newmm` dictionary maximal-matcher over our own wordlist). This
 * interface exists so the algorithm can later be swapped (e.g. a
 * frequency/unigram model) without touching [ThaiEngine].
 */
interface ThaiSegmenter {
    fun segment(text: String): List<String>
}
