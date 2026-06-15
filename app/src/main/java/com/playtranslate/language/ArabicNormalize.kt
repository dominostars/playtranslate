package com.playtranslate.language

import java.text.Normalizer

/**
 * Orthographic normalization for matching undiacritized Arabic OCR text against
 * dictionary headwords.
 *
 * Applied IDENTICALLY at dictionary build time (Python — `scripts/build_latin_dict.py`)
 * and at lookup time (here). The two MUST agree character-for-character or lookups
 * silently miss, so this is a small explicit spec — not a call into a library whose
 * internals could drift between the two processes — and it is pinned by a shared
 * golden fixture (`ArabicNormalizeTest` on the JVM side, `run_smoke_test` on the
 * Python side).
 *
 * Rules (after NFKC, which also folds presentation-form ligatures, e.g. ﷲ → الله):
 *  - strip tashkeel / combining marks U+064B..U+065F and superscript alef U+0670
 *  - strip tatweel (kashida) U+0640
 *  - fold alef variants آ أ إ ٱ → ا
 *  - fold alef maqsura ى → ي
 *  - fold taa marbuta ة → ه
 */
object ArabicNormalize {

    fun normalize(s: String): String {
        val nfkc = Normalizer.normalize(s, Normalizer.Form.NFKC)
        val sb = StringBuilder(nfkc.length)
        for (c in nfkc) {
            when (c) {
                in 'ً'..'ٟ', 'ٰ', 'ـ' -> {}                 // diacritics, superscript alef, tatweel
                'آ', 'أ', 'إ', 'ٱ' -> sb.append('ا')   // alef variants → ا
                'ى' -> sb.append('ي')                                 // alef maqsura ى → ي
                'ة' -> sb.append('ه')                                 // taa marbuta ة → ه
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
