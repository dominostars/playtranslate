package com.playtranslate.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioral tests for [MaximalMatchThaiSegmenter] (the `newmm` port) over a
 * fixed fixture wordlist. Pure JVM. Validates maximal matching (fewest words),
 * the verbatim-substring contract, single-syllable survival (the LatinEngine
 * `length < 2` trap the engine deliberately avoids), and non-dictionary runs.
 */
class ThaiSegmenterTest {

    private val seg = MaximalMatchThaiSegmenter(
        ThaiWordTrie.of(
            listOf(
                "ฉัน", "รัก", "เธอ", "ความ", "สุข", "ความสุข",
                "น้ำ", "แม่", "แม่น้ำ", "กิน", "ข้าว", "ไป", "มา", "คน",
            ),
        ),
    )

    @Test fun `segments a sentence into dictionary words`() {
        assertEquals(listOf("ฉัน", "รัก", "เธอ"), seg.segment("ฉันรักเธอ"))
    }

    @Test fun `maximal matching prefers the longer compound`() {
        // แม่น้ำ / ความสุข are in the dict, so fewest-words picks the compound
        // over แม่+น้ำ / ความ+สุข.
        assertEquals(listOf("แม่น้ำ"), seg.segment("แม่น้ำ"))
        assertEquals(listOf("ความสุข"), seg.segment("ความสุข"))
    }

    @Test fun `single-syllable words survive`() {
        assertEquals(listOf("ไป"), seg.segment("ไป"))
        assertEquals(listOf("มา"), seg.segment("มา"))
        assertEquals(listOf("น้ำ"), seg.segment("น้ำ"))
        // and they carry a Thai char, so ThaiEngine.isLookupWorthy keeps them.
        assertTrue("ไป".any { it in THAI_RANGE })
        assertTrue("น้ำ".any { it in THAI_RANGE })
    }

    @Test fun `non-dictionary latin and number runs become single tokens`() {
        assertEquals(listOf("ฉัน", "abc"), seg.segment("ฉันabc"))
        assertEquals(listOf("ไป", "123"), seg.segment("ไป123"))
        // a non-Thai run carries no Thai char → dropped by isLookupWorthy.
        assertTrue("abc".none { it in THAI_RANGE })
    }

    @Test fun `output reconstructs the input verbatim`() {
        // Holds for ANY input by construction (consecutive verbatim substrings);
        // exercises dict words, compounds, unknown Thai runs, spaces, and mixed
        // scripts — a gap/overlap bug in the port would fail here.
        val inputs = listOf(
            "ฉันรักเธอ", "แม่น้ำ", "ความสุข", "กินข้าว",
            "ฉันabc123", "ไป มา", "คนที่ไม่รู้จัก",
        )
        for (s in inputs) {
            assertEquals("reconstruct '$s'", s, seg.segment(s).joinToString(""))
        }
    }

    @Test fun `empty input yields empty list`() {
        assertEquals(emptyList<String>(), seg.segment(""))
    }
}
