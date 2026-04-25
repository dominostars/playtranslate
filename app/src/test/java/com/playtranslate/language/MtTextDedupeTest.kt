package com.playtranslate.language

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [dedupeMtCsv]. The post-MT cleanup is the only thing that
 * keeps "água, água, líquido" from reaching the user when ML Kit collapses
 * synonyms on a CSV input — the function's job is small but its behavior
 * has to be predictable enough that we can trust it on every supported
 * target language.
 */
class MtTextDedupeTest {

    @Test fun `unique entries pass through unchanged in original order`() {
        assertEquals(
            "water, fluid, liquid",
            dedupeMtCsv("water, fluid, liquid"),
        )
    }

    @Test fun `exact duplicate is collapsed to first occurrence`() {
        // The motivating real-world case: MT collapses synonyms onto one
        // target lemma. The first form seen wins so the original ordering
        // is preserved as far as possible.
        assertEquals(
            "água, líquido",
            dedupeMtCsv("água, água, líquido"),
        )
    }

    @Test fun `case-insensitive duplicates collapse to first form seen`() {
        // "Água" arrives second so it's dropped; "água" wins because it
        // came first. Folding via Locale.ROOT means this stays stable
        // regardless of device locale.
        assertEquals(
            "água, líquido",
            dedupeMtCsv("água, Água, líquido"),
        )
    }

    @Test fun `trims surrounding whitespace inside each entry`() {
        // ML Kit sometimes emits ", " and sometimes "  ,  " or even tabs
        // depending on the target. The output uses the canonical ", "
        // separator.
        assertEquals(
            "water, fluid",
            dedupeMtCsv("  water  ,  fluid  "),
        )
    }

    @Test fun `empty entries between commas are dropped`() {
        assertEquals(
            "water, fluid",
            dedupeMtCsv("water, , fluid"),
        )
    }

    @Test fun `single entry comes back unchanged`() {
        assertEquals("water", dedupeMtCsv("water"))
    }

    @Test fun `blank input is returned as-is (not coerced to empty)`() {
        // Caller guards against null/blank before invoking; this just
        // pins the behavior so we don't accidentally turn whitespace
        // into an empty TextView mid-render.
        assertEquals("   ", dedupeMtCsv("   "))
    }

    @Test fun `empty input is returned as-is`() {
        assertEquals("", dedupeMtCsv(""))
    }

    @Test fun `everything-blank input is returned as-is`() {
        // No real entries means we have nothing to canonicalize; return
        // the input unchanged rather than collapsing to "".
        assertEquals(", , ,", dedupeMtCsv(", , ,"))
    }

    @Test fun `all duplicates collapse to a single entry`() {
        assertEquals("agua", dedupeMtCsv("agua, AGUA, Agua, agua"))
    }

    @Test fun `non-Latin scripts dedupe correctly`() {
        // Cyrillic / CJK targets work the same way — the function isn't
        // Latin-only. Locale.ROOT lowercase still collapses "Вода" /
        // "вода" via Unicode default mapping.
        assertEquals(
            "вода, жидкость",
            dedupeMtCsv("вода, Вода, жидкость"),
        )
    }

    @Test fun `no-separator input falls through unchanged`() {
        // Single multi-word phrase that happens to contain no separator —
        // we hand it back unchanged rather than guess word boundaries.
        assertEquals(
            "water fluid liquid",
            dedupeMtCsv("water fluid liquid"),
        )
    }

    @Test fun `preserves first-seen casing exactly`() {
        // The contract is "first one wins"; subsequent variants in
        // different cases are dropped without rewriting the survivor.
        assertEquals("Water, Fluid", dedupeMtCsv("Water, water, Fluid, FLUID"))
    }

    // ── CJK list separators ──────────────────────────────────────────────
    // The motivating bug: 激 → Japanese rendered as
    // "刺激する、刺激する、刺激する、刺激する" because the splitter only knew
    // about ASCII commas. Japanese / Chinese / Korean MT output is the
    // primary case where this matters.

    @Test fun `IDEOGRAPHIC COMMA collapses Japanese repeats`() {
        // The exact production case. ML Kit collapsed every English
        // synonym onto 刺激する; the dedupe must recognize `、` and reduce
        // to a single entry.
        assertEquals(
            "刺激する",
            dedupeMtCsv("刺激する、刺激する、刺激する、刺激する"),
        )
    }

    @Test fun `IDEOGRAPHIC COMMA preserves the native separator on rejoin`() {
        // Mixed entries separated by `、` must come back joined by `、`,
        // not the ASCII ", " — Latin punctuation in CJK text reads as
        // a typographic mistake.
        assertEquals(
            "水、火",
            dedupeMtCsv("水、火、水"),
        )
    }

    @Test fun `FULLWIDTH COMMA U+FF0C is recognized for Chinese MT output`() {
        // Some Chinese targets emit `，` instead of `、`. Same dedupe,
        // same rejoin policy — first separator wins.
        assertEquals(
            "水，液体",
            dedupeMtCsv("水，水，液体"),
        )
    }

    @Test fun `FULLWIDTH SEMICOLON U+FF1B is recognized`() {
        assertEquals(
            "水；液体",
            dedupeMtCsv("水；水；液体"),
        )
    }

    @Test fun `ASCII semicolon is treated as a list separator`() {
        // Some translators prefer `; ` over `, ` for English output.
        // Treating it as a separator means dedupe still works on those
        // targets without us having to reprompt ML Kit.
        assertEquals(
            "water; fluid",
            dedupeMtCsv("water; fluid; water"),
        )
    }

    @Test fun `mixed separators rejoin with whichever appeared first`() {
        // First separator seen is `,` so the rejoin uses ", " even
        // though `;` shows up later. Tradeoff: keeps the function pure
        // (one pass) without trying to detect a "dominant" separator.
        assertEquals(
            "water, fluid, ice",
            dedupeMtCsv("water, fluid; ice; water"),
        )
    }

    @Test fun `tight CJK rejoin has no leading or trailing whitespace`() {
        // `、` is written tight in CJK typography; we should NOT add a
        // space the way we do for ASCII `,`. Pin this so future "make
        // join consistent" refactors don't accidentally Latinize CJK.
        val out = dedupeMtCsv("水、火")
        assertEquals("水、火", out)
        assertEquals(false, out.contains(" "))
    }
}
