package com.playtranslate.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [DictionaryEntry.isKanaOnly] and [DictionaryEntry.headwordDisplay],
 * the two pieces that decide whether to render the kanji or kana form of an
 * entry. Regression-driven: 決まる / 何故 / 此処 / 沢山 / 駄目 are the canonical
 * cases that the implementation has to get right.
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
        // Production sets this once in DictionaryManager.buildEntry; the
        // helper mirrors it so the rule tests below read like entries.
        isKanaOnly = kanaOnlyFrom(senses),
    )

    // ── isKanaOnly ─────────────────────────────────────────────────────

    @Test fun `isKanaOnly is false when no sense carries uk`() {
        val e = entry(
            headwords = listOf(Headword("食べる", "たべる", hasPriority = true)),
            senses = listOf(plainSense("to eat"), plainSense("to drink")),
        )
        assertFalse(e.isKanaOnly)
    }

    @Test fun `isKanaOnly is true when the first sense is uk-tagged`() {
        // 何故 (entry 1577120) — single uk-tagged sense, no ke_pri on the kanji.
        val e = entry(
            headwords = listOf(Headword("何故", "なぜ", hasPriority = false)),
            senses = listOf(ukSense("why", "how")),
        )
        assertTrue(e.isKanaOnly)
    }

    @Test fun `isKanaOnly is true when every sense is uk even though the kanji carries a priority tag`() {
        // 沢山 (entry 1415870) — three uk-tagged senses; the kanji form carries
        // ichi1+news2+nf43, the SAME tags as the reading たくさん. ke_pri is
        // word frequency stamped on both elements, not a spelling signal. The
        // retired priority veto rendered and Anki-saved this word as 沢山.
        val e = takusan()
        assertTrue("Priority on the kanji must not veto kana display", e.isKanaOnly)
    }

    @Test fun `isKanaOnly is true when only the first sense is uk and the kanji has priority`() {
        // 駄目 — senses 1-3 uk, sense 4 (the go term) not, sense 5 uk; the
        // kanji carries ichi1+news1. Sense 1 is what a reader most likely met.
        val e = entry(
            headwords = listOf(Headword("駄目", "だめ", hasPriority = true)),
            senses = listOf(
                ukSense("no good"), ukSense("hopeless"), ukSense("cannot"),
                plainSense("neutral point (go)"), ukSense("no!"),
            ),
        )
        assertTrue(e.isKanaOnly)
    }

    @Test fun `isKanaOnly is false when only a later sense is uk-tagged`() {
        // 決まる (entry 1591420) — six everyday senses written in kanji + one
        // slang uk-tagged sense. Sense ORDER keeps this in kanji; the kanji's
        // priority tag is irrelevant, asserted both ways.
        val senses = listOf(
            plainSense("to be decided"),
            plainSense("to be unchanging"),
            plainSense("to be a fixed rule"),
            plainSense("to be well executed"),
            plainSense("to look good"),
            plainSense("to be struck and held"),
            ukSense("to get high (on drugs)"),
        )
        assertFalse(entry(listOf(Headword("決まる", "きまる", hasPriority = true)), senses).isKanaOnly)
        assertFalse(entry(listOf(Headword("決まる", "きまる", hasPriority = false)), senses).isKanaOnly)
    }

    @Test fun `isKanaOnly ignores the priority of secondary kanji headwords`() {
        // 決まる + 極まる variants — only the first carries priority. Neither
        // matters: the first sense is plain, so the entry stays kanji.
        val e = entry(
            headwords = listOf(
                Headword("決まる", "きまる", hasPriority = true),
                Headword("極まる", "きまる", hasPriority = false),
            ),
            senses = listOf(plainSense("to be decided"), ukSense("slang")),
        )
        assertFalse(e.isKanaOnly)
    }

    @Test fun `isKanaOnly is true for a uk entry whose kanji has no priority`() {
        // 此処 (entry 1288810) — kanji form has no priority, uk-tagged.
        val e = entry(
            headwords = listOf(Headword("此処", "ここ", hasPriority = false)),
            senses = listOf(ukSense("here")),
        )
        assertTrue(e.isKanaOnly)
    }

    @Test fun `isKanaOnly is false for an entry with no senses`() {
        val e = entry(
            headwords = listOf(Headword("沢山", "たくさん", hasPriority = true)),
            senses = emptyList(),
        )
        assertFalse(e.isKanaOnly)
    }

    @Test fun `kana-only verdict survives the senses being stripped`() {
        // Yomitan single-dictionary mode hands every surface the pack entry
        // with senses = emptyList() when an imported group wins (see
        // YomitanEnrichment.mergeImportedTerms). The verdict is carried on the
        // entry, so the lens pill still collapses: それとも, not 其れとも.
        val stripped = takusan().copy(senses = emptyList())
        assertTrue(stripped.isKanaOnly)
        val display = stripped.headwordDisplay(
            stripped.selectHeadword("たくさん", "たくさん", "たくさん"), "たくさん",
        )
        assertEquals("たくさん", display.written)
        assertNull(display.reading)
    }

    // ── ke_inf: search-only and rare spellings (ja-v5 packs) ───────────

    @Test fun `kana lookup skips a search-only spelling listed first`() {
        // JMdict lists 141 entries with a search-only form FIRST. Its kana
        // must land on the everyday spelling, not the lookup-key stub.
        val e = entry(
            headwords = listOf(
                Headword("其れから", "それから", isSearchOnly = true),
                Headword("其から", "それから"),
            ),
            senses = listOf(plainSense("and then")),
        )
        assertEquals("其から", e.headwordFor("それから")?.written)
        assertEquals("其から", e.selectHeadword("それから", "それから", "それから")?.written)
        assertEquals("其から", e.headwords.preferDisplayable()?.written)
    }

    @Test fun `a search-only spelling the user actually saw still displays`() {
        // 10ten shows a search-only match with a note rather than swapping the
        // word out from under the pointer; the seen spelling wins by written match.
        val e = entry(
            headwords = listOf(
                Headword("其から", "それから"),
                Headword("其れから", "それから", isSearchOnly = true),
            ),
            senses = listOf(plainSense("and then")),
        )
        assertEquals("其れから", e.selectHeadword("其れから", "其れから", "それから")?.written)
    }

    @Test fun `kana lookup prefers the everyday spelling over a rare one listed first`() {
        // 361 common entries list a rare form first; a mixed entry must pick
        // the non-rare form for a kana lookup.
        val e = entry(
            headwords = listOf(
                Headword("彼処", "あそこ", isRareForm = true),
                Headword("彼所", "あそこ"),
            ),
            senses = listOf(plainSense("there")),
        )
        assertEquals("彼所", e.headwordFor("あそこ")?.written)
        assertEquals("彼所", e.headwords.preferDisplayable()?.written)
    }

    @Test fun `rare beats search-only when nothing better exists`() {
        val e = entry(
            headwords = listOf(
                Headword("A", "x", isSearchOnly = true),
                Headword("B", "x", isRareForm = true),
            ),
            senses = listOf(plainSense("def")),
        )
        assertEquals("B", e.headwords.preferDisplayable()?.written)
        // All search-only: still never empty — the first is the anchor.
        val allSk = entry(
            headwords = listOf(Headword("A", "x", isSearchOnly = true)),
            senses = listOf(plainSense("def")),
        )
        assertEquals("A", allSk.headwords.preferDisplayable()?.written)
    }

    // ── per-reading uk scope (reading.uk_applicable on the Headword) ───

    /** 新: さら is the usually-kana reading (sense 1 uk, stagr さら); にい is a
     *  kanji reading the uk sense does not cover. Entry verdict: kana-only. */
    private fun sara() = entry(
        headwords = listOf(
            Headword("新", "さら", ukApplicable = true),
            Headword("新", "にい", ukApplicable = false),
        ),
        senses = listOf(ukSense("new"), plainSense("new (prefix)")),
    )

    @Test fun `a reading the uk sense covers collapses to kana`() {
        val e = sara()
        val display = e.headwordDisplay(e.selectHeadword("さら", "さら", "さら"), "さら")
        assertEquals("さら", display.written)
        assertNull(display.reading)
    }

    @Test fun `a reading the uk sense excludes keeps the kanji`() {
        // The Codex adversarial find on the entry-wide verdict: にい was
        // collapsing because the entry as a whole is kana-only.
        val e = sara()
        val display = e.headwordDisplay(e.selectHeadword("にい", "にい", "にい"), "にい")
        assertEquals("新", display.written)
        assertEquals("にい", display.reading)
    }

    @Test fun `packs without the column keep the entry-wide collapse`() {
        // Headword.ukApplicable defaults true: ja-v4 behaviour is unchanged.
        val e = entry(
            headwords = listOf(Headword("新", "さら"), Headword("新", "にい")),
            senses = listOf(ukSense("new")),
        )
        assertEquals("にい", e.headwordDisplay(e.selectHeadword("にい", "にい", "にい"), "にい").written)
    }

    // ── headwordDisplay: the reported たくさん case ─────────────────────

    /** 沢山 (entry 1415870) as the ja-v2+ pack builds it: priority kanji, all uk. */
    private fun takusan() = entry(
        headwords = listOf(Headword("沢山", "たくさん", hasPriority = true)),
        senses = listOf(ukSense("a lot"), ukSense("enough"), ukSense("too many")),
    )

    @Test fun `pure-kana surface for a priority-kanji uk entry collapses to kana`() {
        // OCR'd たくさん resolved to 沢山. Display, and the Anki Expression that
        // every send site takes from this display, are たくさん.
        val e = takusan()
        val display = e.headwordDisplay(e.selectHeadword("たくさん", "たくさん", "たくさん"), "たくさん")
        assertEquals("たくさん", display.written)
        assertNull(display.reading)
    }

    @Test fun `kanji surface for a priority-kanji uk entry keeps the kanji`() {
        // A game that prints 沢山 in kanji still shows 沢山 with the kana beneath.
        val e = takusan()
        val display = e.headwordDisplay(e.selectHeadword("沢山", "沢山", "たくさん"), "沢山")
        assertEquals("沢山", display.written)
        assertEquals("たくさん", display.reading)
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

    // ── headwordForReading / selectHeadword: occurrence reading ────────

    /** 明日 after single-kanji expansion: one headword per reading. */
    private fun ashita() = entry(
        headwords = listOf(
            Headword("明日", "あした"),
            Headword("明日", "あす"),
            Headword("明日", "みょうにち"),
        ),
        senses = listOf(plainSense("tomorrow")),
    )

    @Test fun `headwordForReading picks the headword matching surface AND reading`() {
        val e = ashita()
        assertEquals("あす", e.headwordForReading("明日", "あす")?.reading)
        assertEquals("あした", e.headwordForReading("明日", "あした")?.reading)
    }

    @Test fun `headwordForReading is null when the reading is empty or absent`() {
        val e = ashita()
        assertNull(e.headwordForReading("明日", null))
        assertNull(e.headwordForReading("明日", ""))
    }

    @Test fun `headwordForReading is null when the entry does not list the reading`() {
        // Tokenizer misreading: あさひ is not a reading of 明日 → no injection.
        assertNull(ashita().headwordForReading("明日", "あさひ"))
    }

    @Test fun `headwordForReading does not cross to a sibling written form`() {
        // The dropped reading-only fallback would have returned B/y for surface A;
        // strict matching keeps it null so selectHeadword shows the seen surface.
        val e = entry(
            headwords = listOf(Headword("A", "x"), Headword("B", "y")),
            senses = listOf(plainSense("def")),
        )
        assertNull(e.headwordForReading("A", "y"))
        assertEquals("A", e.headwordDisplay(e.selectHeadword("A", "A", "y"), "A").written)
    }

    @Test fun `selectHeadword honors the occurrence reading`() {
        val display = ashita().let { it.headwordDisplay(it.selectHeadword("明日", "明日", "あす"), "明日") }
        assertEquals("明日", display.written)
        assertEquals("あす", display.reading)
    }

    @Test fun `selectHeadword falls back to the primary headword with no reading`() {
        val display = ashita().let { it.headwordDisplay(it.selectHeadword("明日", "明日", null), "明日") }
        assertEquals("あした", display.reading)
    }

    @Test fun `selectHeadword falls back to the primary on an unlisted reading`() {
        val display = ashita().let { it.headwordDisplay(it.selectHeadword("明日", "明日", "あさひ"), "明日") }
        assertEquals("あした", display.reading)
    }

    @Test fun `selectHeadword falls through an inflected surface to the lemma headword`() {
        // 走った surface, stem reading はしっ — matches no dictionary-form headword,
        // so it falls through surface → lemma (走る) → the lemma's reading.
        val e = entry(
            headwords = listOf(Headword("走る", "はしる")),
            senses = listOf(plainSense("to run")),
        )
        assertEquals("はしる", e.headwordDisplay(e.selectHeadword("走った", "走る", "はしっ"), "走った").reading)
    }

    @Test fun `kana-only sibling headword resolves a lookup by that reading`() {
        // 彼 after single-kanji expansion: 彼/かれ plus the re_nokanji あれ kept as
        // a kana-only headword. A lookup of あれ must resolve to あれ, not fall
        // back to 彼/かれ — the reason re_nokanji readings are preserved.
        val e = entry(
            headwords = listOf(Headword("彼", "かれ"), Headword(null, "あれ")),
            senses = listOf(plainSense("he / that")),
        )
        val display = e.headwordDisplay(e.selectHeadword("あれ", "あれ", "あれ"), "あれ")
        assertEquals("あれ", display.written)
        assertNull(display.reading)
    }
}
