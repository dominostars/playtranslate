package com.playtranslate.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [DictionaryEntry.isKanaOnly] and [DictionaryEntry.headwordDisplay],
 * the two pieces that decide whether to render the kanji or kana form of an
 * entry. Regression-driven: 決まる / 何故 / 此処 / 茜 are the canonical cases
 * that the implementation has to get right.
 *
 * Pure JUnit — no SQLite or Android dependency.
 */
class HeadwordDisplayTest {

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun ukSense(vararg defs: String) = Sense(
        targetDefinitions = defs.toList(),
        partsOfSpeech = emptyList(),
        tags = emptyList(),
        restrictions = emptyList(),
        info = emptyList(),
        misc = listOf("Kana only"),
    )

    private fun plainSense(vararg defs: String) = Sense(
        targetDefinitions = defs.toList(),
        partsOfSpeech = emptyList(),
        tags = emptyList(),
        restrictions = emptyList(),
        info = emptyList(),
        misc = emptyList(),
    )

    private fun entry(
        headwords: List<Headword>,
        senses: List<Sense>,
        slug: String = headwords.firstOrNull()?.written
            ?: headwords.firstOrNull()?.reading
            ?: "?",
    ) = DictionaryEntry(
        slug = slug,
        isCommon = null,
        tags = emptyList(),
        jlpt = emptyList(),
        headwords = headwords,
        senses = senses,
        freqScore = 0,
    )

    // ── isKanaOnly ─────────────────────────────────────────────────────

    @Test fun `isKanaOnly is false when no sense carries uk`() {
        val e = entry(
            headwords = listOf(Headword("食べる", "たべる", hasPriority = true)),
            senses = listOf(plainSense("to eat"), plainSense("to drink")),
        )
        assertFalse(e.isKanaOnly)
    }

    @Test fun `isKanaOnly is true when kanji has no priority and a sense is uk-tagged`() {
        // 何故 (entry 1577120) — single uk-tagged sense, no ke_pri on the kanji.
        val e = entry(
            headwords = listOf(Headword("何故", "なぜ", hasPriority = false)),
            senses = listOf(ukSense("why", "how")),
        )
        assertTrue(e.isKanaOnly)
    }

    @Test fun `isKanaOnly is false when kanji has priority even if a minor sense is uk-tagged`() {
        // 決まる (entry 1591420) — six normal senses + one slang uk-tagged sense.
        // The kanji form carries ichi1+news1+nf14, so the natural display is
        // kanji. Old `senses.any { uk }` mis-classified this as kana-only.
        val e = entry(
            headwords = listOf(Headword("決まる", "きまる", hasPriority = true)),
            senses = listOf(
                plainSense("to be decided"),
                plainSense("to be unchanging"),
                plainSense("to be a fixed rule"),
                plainSense("to be well executed"),
                plainSense("to look good"),
                plainSense("to be struck and held"),
                ukSense("to get high (on drugs)"),
            ),
        )
        assertFalse(
            "Priority kanji + minor uk sense should NOT be kana-only",
            e.isKanaOnly,
        )
    }

    @Test fun `isKanaOnly is false when at least one of several kanji headwords has priority`() {
        // 決まる + 極まる variants — only the first carries priority. The check
        // is `any` because high-priority secondary kanji are still kanji.
        val e = entry(
            headwords = listOf(
                Headword("決まる", "きまる", hasPriority = true),
                Headword("極まる", "きまる", hasPriority = false),
            ),
            senses = listOf(plainSense("to be decided"), ukSense("slang")),
        )
        assertFalse(e.isKanaOnly)
    }

    @Test fun `isKanaOnly is true when no kanji headword has priority and any sense is uk`() {
        // 此処 (entry 1288810) — kanji form has no priority, uk-tagged.
        val e = entry(
            headwords = listOf(Headword("此処", "ここ", hasPriority = false)),
            senses = listOf(ukSense("here")),
        )
        assertTrue(e.isKanaOnly)
    }

    // ── headwordDisplay: surface detection ─────────────────────────────

    @Test fun `inflected verb surface with kanji preserves kanji form`() {
        // User OCRs 決まっている (inflected). Even if this entry were classified
        // as kana-only (it isn't post-fix, but cover both paths), the surface
        // contains the kanji 決 from the headword and the kanji should win.
        val e = entry(
            headwords = listOf(Headword("決まる", "きまる", hasPriority = false)),
            senses = listOf(ukSense("hypothetical uk")),
        )
        val display = e.headwordDisplay(e.headwords.first(), surface = "決まっている")
        assertEquals("決まる", display.written)
        assertEquals("きまる", display.reading)
    }

    @Test fun `pure-kana surface for a uk-tagged entry collapses to kana`() {
        // 何故 entry, user OCRs なぜ. No ideograph in the surface → kana branch.
        val e = entry(
            headwords = listOf(Headword("何故", "なぜ", hasPriority = false)),
            senses = listOf(ukSense("why")),
        )
        val display = e.headwordDisplay(e.headwords.first(), surface = "なぜ")
        assertEquals("なぜ", display.written)
        assertNull("Reading suppressed when written already shows the kana", display.reading)
    }

    @Test fun `exact kanji surface for a uk-tagged entry preserves kanji`() {
        // 何故 entry, user OCRs 何故 (not inflected). Pre-fix path still works.
        val e = entry(
            headwords = listOf(Headword("何故", "なぜ", hasPriority = false)),
            senses = listOf(ukSense("why")),
        )
        val display = e.headwordDisplay(e.headwords.first(), surface = "何故")
        assertEquals("何故", display.written)
        assertEquals("なぜ", display.reading)
    }

    @Test fun `kanji entry without uk sense displays kanji regardless of surface`() {
        // 食べる — no uk anywhere, priority or not. Always kanji.
        val e = entry(
            headwords = listOf(Headword("食べる", "たべる", hasPriority = true)),
            senses = listOf(plainSense("to eat")),
        )
        val display = e.headwordDisplay(e.headwords.first(), surface = "食べている")
        assertEquals("食べる", display.written)
        assertEquals("たべる", display.reading)
    }

    @Test fun `surfaceIsKanji is char-level, not exact-match`() {
        // Multi-kanji-form entry: surface 極まっ matches 極 in the 極まる
        // variant even though the primary headword is 決まる.
        val e = entry(
            headwords = listOf(
                Headword("決まる", "きまる", hasPriority = false),
                Headword("極まる", "きまる", hasPriority = false),
            ),
            senses = listOf(ukSense("hypothetical uk")),
        )
        val display = e.headwordDisplay(e.headwords.first(), surface = "極まっ")
        // Surface has kanji from a headword → preserve kanji from the resolved form.
        assertEquals("決まる", display.written)
        assertEquals("きまる", display.reading)
    }

    @Test fun `no-surface path falls back to kana-only override`() {
        // When the caller can't supply a surface (e.g. drag-lens fallback),
        // surfaceIsKanji is unconditionally false — kana wins for kana-only.
        val e = entry(
            headwords = listOf(Headword("何故", "なぜ", hasPriority = false)),
            senses = listOf(ukSense("why")),
        )
        val display = e.headwordDisplay(e.headwords.first(), surface = null)
        assertEquals("なぜ", display.written)
        assertNull(display.reading)
    }

    // ── headwordDisplay: pure-kana entries ─────────────────────────────

    @Test fun `pure-kana entry displays kana with no reading`() {
        val e = entry(
            headwords = listOf(Headword(null, "ありがとう")),
            senses = listOf(plainSense("thank you")),
        )
        val display = e.headwordDisplay(e.headwords.first(), surface = "ありがとう")
        assertEquals("ありがとう", display.written)
        assertNull(display.reading)
    }
}
