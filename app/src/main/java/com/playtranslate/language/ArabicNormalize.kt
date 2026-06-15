package com.playtranslate.language

import java.text.Normalizer

/**
 * Orthographic normalization for matching undiacritized Arabic text (OCR output
 * or user queries) against dictionary headwords.
 *
 * Applied IDENTICALLY at dictionary build time (Python — `scripts/build_latin_dict.py`)
 * and at lookup time (here). The two MUST agree character-for-character or lookups
 * silently miss, so this is a small explicit spec pinned by a shared golden fixture
 * (`ArabicNormalizeTest` on the JVM side, `_assert_arabic_normalize` on the Python
 * side).
 *
 * Rules:
 *  - NFKC (folds presentation-form LIGATURES back to their letters, e.g. ﷲ → الله)
 *  - strip tashkeel / combining marks U+064B..U+065F and superscript alef U+0670
 *  - strip tatweel (kashida) U+0640
 *
 * It deliberately does NOT fold letter identities (ة→ه, ى→ي, أ/إ/آ/ٱ→ا). The
 * normalized form is ALSO the displayed position-0 headword in the dictionary
 * pack, so folding corrupts the shown lemma spelling (مدرسة→مدرسه, فتوى→فتوي,
 * أنا→انا). Variant-spelling tolerance (folding as a *separate* internal lookup
 * key, not the display form) is deferred to the dictionary morphology-augmentation
 * work.
 */
object ArabicNormalize {

    fun normalize(s: String): String {
        val nfkc = Normalizer.normalize(s, Normalizer.Form.NFKC)
        val sb = StringBuilder(nfkc.length)
        for (c in nfkc) {
            when (c) {
                in 'ً'..'ٟ', 'ٰ', 'ـ' -> {}  // tashkeel/diacritics, superscript alef, tatweel
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
