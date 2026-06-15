package com.playtranslate.language

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Golden fixture for [ArabicFold] — the SAME (input → output) pairs are asserted
 * on the Python build side (`scripts/arabic_text.py` `_assert_arabic_fold`) so the
 * pack's position-3 fold keys match what lookup folds queries to. Folding is
 * intentionally lossy (it changes letter identity), so it is ONLY a lookup key,
 * never a display form — [ArabicNormalize] keeps the displayed lemma intact.
 */
class ArabicFoldTest {

    @Test fun foldsAlefVariantsToBareAlef() {
        assertEquals("انا", ArabicFold.fold("أنا"))   // hamza-above dropped
        assertEquals("ان", ArabicFold.fold("إن"))     // hamza-below dropped
        assertEquals("امن", ArabicFold.fold("آمن"))   // madda dropped
        assertEquals("الله", ArabicFold.fold("ٱلله")) // alef wasla → alef
    }

    @Test fun foldsTaaMarbutaAndAlefMaksura() {
        assertEquals("مدرسه", ArabicFold.fold("مدرسة")) // ة → ه
        assertEquals("فتوي", ArabicFold.fold("فتوى"))   // ى → ي
    }

    @Test fun composesWithNormalize_stripsDiacriticsBeforeFolding() {
        assertEquals("كتاب", ArabicFold.fold("كِتَاب"))
    }

    @Test fun partiallyFoldedInputNormalizesToTheFoldKey() {
        // انثى: hamza already dropped by the user, alef-maksura kept — fold maps
        // ى → ي so it reaches the same key as fold("أنثى"). This is the case the
        // runtime fold fallback exists for (canonical surface misses it).
        assertEquals("انثي", ArabicFold.fold("انثى"))
    }

    @Test fun leavesBareLettersAndNonArabicUnchanged() {
        assertEquals("كتاب", ArabicFold.fold("كتاب"))
        assertEquals("100", ArabicFold.fold("100"))
        assertEquals("abc", ArabicFold.fold("abc"))
    }
}
