package com.playtranslate.language

/**
 * Lookup-only orthographic FOLDING for Arabic — tolerates casual / variant
 * spellings (hamza dropped, ة written as ه, ى as ي) when matching against
 * dictionary headwords.
 *
 * Distinct from [ArabicNormalize] by INTENT, not by re-implementing it: [fold]
 * is the composition `letterFold ∘ ArabicNormalize.normalize`, so the
 * diacritic/tatweel strip set has exactly one source of truth. The extra step
 * folds the letter identities [ArabicNormalize] deliberately PRESERVES (because
 * the normalized form is also the displayed lemma, and folding would corrupt
 * the shown spelling):
 *
 *   أ إ آ ٱ → ا   ·   ة → ه   ·   ى → ي
 *
 * (Lucene `ArabicNormalizer`'s letter set, plus alef wasla.)
 *
 * Used ONLY as a separate internal lookup key: the pack stores folded forms as
 * `position = 3` headword rows, and [WiktionaryDictionaryManager.lookup] tries
 * the folded key as a fallback AFTER the canonical surface/stem queries miss —
 * so a folded match never overrides a correct canonical hit and never changes
 * the displayed (un-folded) lemma.
 *
 * Applied IDENTICALLY at build time (Python — `scripts/arabic_text.py`
 * `arabic_fold`) and here; the two MUST agree character-for-character or folded
 * lookups silently miss. Pinned by a shared golden fixture (`ArabicFoldTest` on
 * the JVM side, `_assert_arabic_fold` on the Python side).
 */
object ArabicFold {

    fun fold(s: String): String {
        val normalized = ArabicNormalize.normalize(s)
        val sb = StringBuilder(normalized.length)
        for (c in normalized) {
            sb.append(
                when (c) {
                    'أ', 'إ', 'آ', 'ٱ' -> 'ا'  // alef variants → bare alef
                    'ة' -> 'ه'                  // taa marbuta → heh
                    'ى' -> 'ي'                  // alef maksura → yeh
                    else -> c
                }
            )
        }
        return sb.toString()
    }
}
