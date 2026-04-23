package com.playtranslate.language

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [KoreanEngine.normalizeWithOffsets]. No Android,
 * no KOMORAN — the function is a self-contained string/offset mapper
 * and its invariants drive whether Korean tap spans land correctly on
 * the text the user sees.
 *
 * The contract we pin here:
 *  1. NFC-normalized output is returned as the first pair element.
 *  2. For every NFC index `i` the map entry `nfcToOrig[i]` is the start
 *     offset in the ORIGINAL string of the sequence that produced the
 *     NFC character at position `i`.
 *  3. `nfcToOrig[normalized.length]` == `text.length` (sentinel — lets
 *     callers do `orig.substring(nfcToOrig[begin], nfcToOrig[end])`).
 *  4. For any NFC token `[b, e)`, the original substring
 *     `orig.substring(nfcToOrig[b], nfcToOrig[e])` NFC-normalizes back
 *     to the NFC substring `normalized.substring(b, e)`. This is what
 *     KoreanEngine uses downstream to emit `TokenSpan.surface` that is
 *     a substring of what's actually on screen.
 */
class KoreanEngineNormalizationTest {

    @Test fun `already-NFC Hangul returns identity map`() {
        val text = "안녕하세요"
        val (nfc, map) = KoreanEngine.normalizeWithOffsets(text)
        assertEquals("NFC output should equal input when already NFC", text, nfc)
        assertArrayEquals(
            "Identity map expected for NFC input",
            intArrayOf(0, 1, 2, 3, 4, 5),
            map,
        )
    }

    @Test fun `ASCII-only input returns identity map`() {
        val text = "hello world"
        val (nfc, map) = KoreanEngine.normalizeWithOffsets(text)
        assertEquals(text, nfc)
        assertEquals(text.length + 1, map.size)
        for (i in map.indices) assertEquals("Identity at $i", i, map[i])
    }

    @Test fun `decomposed Hangul composes to precomposed syllables with correct span offsets`() {
        // NFD representation of 안녕 = ㅇ(U+110B) ㅏ(U+1161) ㄴ(U+11AB)
        //                            ㄴ(U+1102) ㅕ(U+1167) ㅇ(U+11BC) → 6 chars
        // NFC composition                                              → 안 녕  = 2 chars
        val text = "\u110B\u1161\u11AB\u1102\u1167\u11BC"
        val (nfc, map) = KoreanEngine.normalizeWithOffsets(text)
        assertEquals("NFC composes L+V+T to precomposed syllables", "안녕", nfc)
        assertArrayEquals(
            "Each NFC syllable starts at its L-jamo position in the original",
            intArrayOf(0, 3, 6),
            map,
        )
        // Round-trip invariant: NFC substring of the ORIGINAL (pointed at
        // by the map) must normalize back to the same NFC substring.
        val firstOrigSubstr = text.substring(map[0], map[1])
        assertEquals(
            "Original substring covering NFC[0..1] must NFC-normalize to NFC[0..1]",
            nfc.substring(0, 1),
            java.text.Normalizer.normalize(firstOrigSubstr, java.text.Normalizer.Form.NFC),
        )
    }

    @Test fun `Latin NFD accents compose without losing span alignment`() {
        // "café" as NFD: c + a + f + e + U+0301 (combining acute)  → 5 chars
        // NFC:                                             café    → 4 chars
        val text = "cafe\u0301"
        val (nfc, map) = KoreanEngine.normalizeWithOffsets(text)
        assertEquals("café", nfc)
        assertEquals(nfc.length + 1, map.size)
        // Plain ASCII chars map 1-to-1.
        assertEquals(0, map[0])
        assertEquals(1, map[1])
        assertEquals(2, map[2])
        // The composed é starts at position 3 in the original (where 'e' is);
        // the combining accent U+0301 at position 4 is absorbed.
        assertEquals(3, map[3])
        assertEquals("Sentinel equals original length", text.length, map[4])
        // A token span covering just 'é' in NFC yields the original 2-char
        // sequence "e\u0301" in the user-visible string.
        assertEquals("e\u0301", text.substring(map[3], map[4]))
    }

    @Test fun `mixed ASCII and decomposed Hangul input maps each segment correctly`() {
        // "Hi " + NFD(안녕) — 3 ASCII chars + 6 jamo chars = 9 originals,
        // normalized to "Hi 안녕" = 5 NFC chars.
        val text = "Hi \u110B\u1161\u11AB\u1102\u1167\u11BC"
        val (nfc, map) = KoreanEngine.normalizeWithOffsets(text)
        assertEquals("Hi 안녕", nfc)
        assertArrayEquals(
            intArrayOf(0, 1, 2, 3, 6, 9),
            map,
        )
    }

    @Test fun `empty input returns empty output with sentinel-only map`() {
        val (nfc, map) = KoreanEngine.normalizeWithOffsets("")
        assertEquals("", nfc)
        assertArrayEquals(intArrayOf(0), map)
    }

    @Test fun `arbitrary NFC token spans round-trip through the map`() {
        // Blanket property check: for every possible [begin, end) in NFC
        // space, the original substring the map points at must normalize
        // back to the NFC substring. This is the guarantee that keeps
        // `displayedText.indexOf(TokenSpan.surface)` alignment correct.
        val cases = listOf(
            "공부했습니다",                       // all-NFC, common verb
            "안녕하세요.",                         // trailing punctuation
            "\u110B\u1161\u11AB\u1102\u1167\u11BC",  // NFD 안녕
            "Hi 안녕",                             // mixed-script NFC
            "cafe\u0301",                          // Latin NFD
        )
        for (text in cases) {
            val (nfc, map) = KoreanEngine.normalizeWithOffsets(text)
            assertEquals("Sentinel wrong for \"$text\"", text.length, map[nfc.length])
            for (begin in 0..nfc.length) {
                for (end in begin..nfc.length) {
                    val nfcSub = nfc.substring(begin, end)
                    val origSub = text.substring(map[begin], map[end])
                    val origSubNfc =
                        java.text.Normalizer.normalize(origSub, java.text.Normalizer.Form.NFC)
                    assertEquals(
                        "Round-trip failed for \"$text\" span [$begin,$end): " +
                            "orig=\"$origSub\" nfc=\"$origSubNfc\" expected=\"$nfcSub\"",
                        nfcSub,
                        origSubNfc,
                    )
                }
            }
        }
    }

    @Test fun `decomposed input produces surfaces that are literal substrings of the original`() {
        // The downstream invariant in the UI: whatever surface string the
        // engine emits MUST be findable in the original via indexOf. This
        // test verifies that property for every NFC-substring position in
        // a decomposed-Hangul input, since that's the case the NFC map
        // was introduced to fix.
        val text = "\u110B\u1161\u11AB\u1102\u1167\u11BC"  // NFD 안녕
        val (nfc, map) = KoreanEngine.normalizeWithOffsets(text)
        for (begin in 0..nfc.length) {
            for (end in begin..nfc.length) {
                if (begin == end) continue
                val surface = text.substring(map[begin], map[end])
                assertTrue(
                    "Surface \"$surface\" from NFC [$begin,$end) must be " +
                        "a substring of original \"$text\" (indexOf would fail otherwise)",
                    text.indexOf(surface) >= 0,
                )
            }
        }
    }
}
