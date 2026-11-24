package com.playtranslate.yomitan

import com.playtranslate.language.PinyinFormatter
import com.playtranslate.model.ImportedSense
import com.playtranslate.model.ImportedSenseGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TermMergeTest {

    private val order = listOf("dictA" to "Dict A", "dictB" to "Dict B")

    private fun row(dict: String, reading: String, score: Double, vararg defs: String) =
        TermMerge.Row(dict, reading, score, defs.toList())

    private fun YomitanDataStore.TermLookup.definitionsOf(group: Int): List<String> =
        groups[group].senses.map { it.definition }

    private fun merge(
        rows: List<TermMerge.Row>,
        normalizedReading: String?,
        normalizedTerm: String = "端",
        singleDictionary: Boolean = false,
    ) = mergeReadings(rows, normalizedReading?.let(::setOf), normalizedTerm, singleDictionary)

    private fun mergeReadings(
        rows: List<TermMerge.Row>,
        normalizedReadings: Set<String>?,
        normalizedTerm: String = "端",
        singleDictionary: Boolean = false,
    ) = TermMerge.merge(rows, order, normalizedReadings, normalizedTerm, singleDictionary)

    @Test
    fun `groups follow section order regardless of row order`() {
        val result = merge(
            rows = listOf(row("dictB", "ねこ", 0.0, "b-def"), row("dictA", "ねこ", 0.0, "a-def")),
            normalizedReading = null,
        )
        assertEquals(
            listOf(
                ImportedSenseGroup("Dict A", listOf(ImportedSense("a-def")), dictId = "dictA"),
                ImportedSenseGroup("Dict B", listOf(ImportedSense("b-def")), dictId = "dictB"),
            ),
            result.groups,
        )
    }

    @Test
    fun `score sorts within a dict, descending, ties keep bank order`() {
        val result = merge(
            rows = listOf(
                row("dictA", "ねこ", 10.0, "low"),
                row("dictA", "ねこ", 50.0, "high"),
                row("dictA", "ねこ", 50.0, "high-second"),
            ),
            normalizedReading = null,
        )
        assertEquals(listOf("high", "high-second", "low"), result.definitionsOf(0))
    }

    @Test
    fun `one entry's glossary items join as one sense`() {
        val result = merge(
            rows = listOf(row("dictA", "ねこ", 0.0, "cat", "feline")),
            normalizedReading = null,
        )
        assertEquals(listOf("cat; feline"), result.definitionsOf(0))
    }

    @Test
    fun `pos tag names join for display`() {
        val result = merge(
            rows = listOf(TermMerge.Row("dictA", "ねこ", 0.0, listOf("cat"), pos = "n exp")),
            normalizedReading = null,
        )
        assertEquals(listOf(ImportedSense("cat", pos = "n, exp")), result.groups.single().senses)
    }

    @Test
    fun `matching reading narrows rows`() {
        val result = merge(
            rows = listOf(row("dictA", "はし", 0.0, "edge"), row("dictA", "はじ", 0.0, "shame")),
            normalizedReading = "はし",
        )
        assertEquals(listOf("edge"), result.definitionsOf(0))
        assertEquals("はし", result.resolvedReading)
    }

    @Test
    fun `unmatched reading yields nothing - homographs never cross`() {
        val result = merge(
            rows = listOf(row("dictA", "はじ", 0.0, "shame")),
            normalizedReading = "はし",
        )
        assertEquals(emptyList<ImportedSenseGroup>(), result.groups)
    }

    @Test
    fun `every reading in the set narrows in - the pack's own breadth`() {
        // 拘る: the pack couldn't narrow by the tap's reading and returned
        // BOTH homographs, so the imported groups must show both too.
        val result = mergeReadings(
            rows = listOf(
                row("dictA", "こだわる", 99.0, "to be obsessive"),
                row("dictA", "かかわる", 98.0, "to be involved"),
                row("dictA", "かかずらう", 1.0, "yomitan-only reading"),
            ),
            normalizedReadings = setOf("こだわる", "かかわる"),
            normalizedTerm = "拘る",
        )
        assertEquals(listOf("to be obsessive", "to be involved"), result.definitionsOf(0))
    }

    @Test
    fun `a reading set leaves the resolved reading to the surviving rows`() {
        // No single supplied reading to echo back, so the first surviving
        // row in section order answers — as it does for a null disambiguator.
        val result = mergeReadings(
            rows = listOf(row("dictA", "こだわる", 0.0, "def")),
            normalizedReadings = setOf("こだわる", "かかわる"),
            normalizedTerm = "拘る",
        )
        assertEquals("こだわる", result.resolvedReading)
    }

    @Test
    fun `an empty reading set narrows nothing`() {
        val result = mergeReadings(
            rows = listOf(row("dictA", "はし", 0.0, "edge"), row("dictA", "はじ", 0.0, "shame")),
            normalizedReadings = emptySet(),
        )
        assertEquals(listOf("edge", "shame"), result.definitionsOf(0))
        assertEquals("はし", result.resolvedReading)
    }

    @Test
    fun `blank-reading sentinel rows match any supplied reading`() {
        // A sloppy conversion stored no reading for the kanji term — the
        // ingest sentinel is reading == term. It must not go silent under
        // hard disambiguation.
        val result = merge(
            rows = listOf(row("dictA", "端", 0.0, "undisambiguated def")),
            normalizedReading = "はし",
            normalizedTerm = "端",
        )
        assertEquals(listOf("undisambiguated def"), result.definitionsOf(0))
    }

    @Test
    fun `null reading resolves from the first dict in section order`() {
        val result = merge(
            rows = listOf(row("dictB", "よみビー", 0.0, "b"), row("dictA", "よみえー", 0.0, "a")),
            normalizedReading = null,
        )
        assertEquals("よみえー", result.resolvedReading)
    }

    // ── The Built-in dictionary row: place and shown-rule ───────────────

    private fun group(label: String, below: Boolean = false) = ImportedSenseGroup(
        label, listOf(ImportedSense("def of $label")), dictId = label, belowBuiltIn = below,
    )

    @Test
    fun `the row's default place is below every dictionary`() {
        val result = merge(
            rows = listOf(row("dictA", "ねこ", 0.0, "a-def"), row("dictB", "ねこ", 0.0, "b-def")),
            normalizedReading = null,
        )
        assertEquals(listOf(false, false), result.groups.map { it.belowBuiltIn })
    }

    @Test
    fun `a dictionary at or past the row's slot is marked below it`() {
        val rows = listOf(row("dictA", "ねこ", 0.0, "a-def"), row("dictB", "ねこ", 0.0, "b-def"))
        fun marks(slot: Int) =
            TermMerge.merge(rows, order, null, "猫", builtInSlot = slot).groups.map { it.belowBuiltIn }
        assertEquals(listOf(true, true), marks(0))
        assertEquals(listOf(false, true), marks(1))
        assertEquals(listOf(false, false), marks(2))
    }

    @Test
    fun `the merge keeps every dictionary's group and hands both switches on`() {
        // Which groups are shown is the caller's, through shown(): it is the
        // one that knows whether the built-in dictionary has the word.
        val result = TermMerge.merge(
            rows = listOf(row("dictB", "ねこ", 0.0, "b-def"), row("dictA", "ねこ", 0.0, "a-def")),
            dictOrder = order,
            normalizedReadings = null,
            normalizedTerm = "猫",
            singleDictionary = true,
            builtInEnabled = false,
        )
        assertEquals(listOf("Dict A", "Dict B"), result.groups.map { it.source })
        assertEquals(true, result.singleDictionary)
        assertEquals(false, result.builtInEnabled)
    }

    @Test
    fun `with no imported dictionary having the word the built-in one is shown, whatever the switches`() {
        for (single in listOf(false, true)) for (enabled in listOf(false, true)) for (has in listOf(false, true)) {
            val shown = TermMerge.shown(emptyList(), single, enabled, has)
            assertEquals(emptyList<ImportedSenseGroup>(), shown.groups)
            assertEquals("single=$single enabled=$enabled has=$has", false, shown.builtInWithheld)
        }
    }

    @Test
    fun `switched on, every dictionary is shown beside the built-in one`() {
        val groups = listOf(group("A"), group("B", below = true))
        for (has in listOf(false, true)) {
            val shown = TermMerge.shown(groups, singleDictionary = false, builtInEnabled = true, builtInHasWord = has)
            assertEquals(groups, shown.groups)
            assertEquals(false, shown.builtInWithheld)
        }
    }

    @Test
    fun `switched off, the built-in one is withheld from a word another dictionary has`() {
        // Wherever its row is: above every group here.
        val groups = listOf(group("A", below = true), group("B", below = true))
        val shown = TermMerge.shown(groups, singleDictionary = false, builtInEnabled = false, builtInHasWord = true)
        assertEquals(groups, shown.groups)
        assertEquals(true, shown.builtInWithheld)
    }

    @Test
    fun `one-dictionary mode gives the word to the built-in one when its row is above every dictionary that has it`() {
        val shown = TermMerge.shown(
            listOf(group("A", below = true), group("B", below = true)),
            singleDictionary = true, builtInEnabled = true, builtInHasWord = true,
        )
        assertEquals(emptyList<ImportedSenseGroup>(), shown.groups)
        assertEquals(false, shown.builtInWithheld)
    }

    @Test
    fun `one-dictionary mode gives the word to the first imported dictionary above the built-in row`() {
        val shown = TermMerge.shown(
            listOf(group("A"), group("B", below = true)),
            singleDictionary = true, builtInEnabled = true, builtInHasWord = true,
        )
        assertEquals(listOf("A"), shown.groups.map { it.source })
        assertEquals(true, shown.builtInWithheld)
    }

    @Test
    fun `one-dictionary mode passes over a built-in row on top that lacks the word`() {
        val shown = TermMerge.shown(
            listOf(group("A", below = true), group("B", below = true)),
            singleDictionary = true, builtInEnabled = true, builtInHasWord = false,
        )
        assertEquals(listOf("A"), shown.groups.map { it.source })
        assertEquals(true, shown.builtInWithheld)
    }

    @Test
    fun `one-dictionary mode never picks a switched-off built-in row over a dictionary that has the word`() {
        for (has in listOf(false, true)) {
            val shown = TermMerge.shown(
                listOf(group("A", below = true), group("B", below = true)),
                singleDictionary = true, builtInEnabled = false, builtInHasWord = has,
            )
            assertEquals(listOf("A"), shown.groups.map { it.source })
            assertEquals(true, shown.builtInWithheld)
        }
    }

    @Test
    fun `one-dictionary mode falls through dictionaries without results`() {
        // dictA has rows, but reading narrowing removes them all — dictB is
        // the first dict WITH results and must win, not an empty lookup.
        val result = merge(
            rows = listOf(row("dictA", "はじ", 0.0, "shame"), row("dictB", "はし", 0.0, "edge")),
            normalizedReading = "はし",
            singleDictionary = true,
        )
        assertEquals(
            listOf(ImportedSenseGroup("Dict B", listOf(ImportedSense("edge")), dictId = "dictB")),
            result.shown(builtInHasWord = true).groups,
        )
    }

    @Test
    fun `one-dictionary mode with no surviving group leaves the built-in dictionary alone`() {
        // With every imported row narrowed away the built-in dictionary IS
        // the one with results.
        val result = merge(
            rows = listOf(row("dictA", "はじ", 0.0, "shame")),
            normalizedReading = "はし",
            singleDictionary = true,
        )
        assertEquals(false, result.shown(builtInHasWord = true).builtInWithheld)
    }

    @Test
    fun `scRowid rides each sense through the merge`() {
        val result = merge(
            rows = listOf(
                TermMerge.Row("dictA", "ねこ", 5.0, listOf("styled"), scRowid = 42L),
                TermMerge.Row("dictA", "ねこ", 1.0, listOf("flat")),
            ),
            normalizedReading = null,
        )
        assertEquals(
            listOf(42L, null),
            result.groups.single().senses.map { it.scRowid },
        )
    }

    @Test
    fun `dicts not in the section order are dropped`() {
        val result = merge(
            rows = listOf(row("ghost", "ねこ", 0.0, "stale")),
            normalizedReading = null,
        )
        assertEquals(emptyList<ImportedSenseGroup>(), result.groups)
        assertNull(result.resolvedReading)
    }

    @Test
    fun `resolving to the first reading keeps one reading's senses, in that reading`() {
        // The imported lookup's pack-miss retry: it could not narrow, so the
        // term's first row picks the reading and only its rows survive; the
        // synthesized entry (one headword) then reads as what it defines
        // (Codex review, 2026-10-22). In memory, no second query.
        val result = TermMerge.merge(
            listOf(row("dictA", "なま", 0.0, "raw"), row("dictA", "せい", 0.0, "life")),
            order, normalizedReadings = null, normalizedTerm = "生", resolveToFirstReading = true,
        )
        assertEquals(listOf("raw"), result.groups.single().senses.map { it.definition })
        assertEquals("なま", result.resolvedReading)
    }

    @Test
    fun `an unnarrowed lookup without that resolution keeps every reading`() {
        // Null-reading callers whose readings are not Yomitan keys
        // (ChineseEngine: pinyin heteronyms) rely on every row surviving.
        val result = merge(
            rows = listOf(row("dictA", "なま", 0.0, "raw"), row("dictA", "せい", 0.0, "life")),
            normalizedReading = null,
            normalizedTerm = "生",
        )
        assertEquals(listOf("raw", "life"), result.groups.single().senses.map { it.definition })
        assertEquals("なま", result.resolvedReading)
    }

    @Test
    fun `a reading key narrows imported rows written in another pinyin format`() {
        // The pack reads 东西 "dōng xī" for the alternative; the import wrote
        // its rows numbered. Only the matching reading's row survives, and the
        // resolved reading stays the caller's own string.
        val rows = listOf(row("dictA", "dong1 xi1", 0.0, "east and west"), row("dictA", "dong1 xi5", 0.0, "thing"))
        val result = TermMerge.merge(
            rows, order, normalizedReadings = setOf("dōng xī"), normalizedTerm = "东西",
            readingKey = PinyinFormatter::canonicalPinyin,
        )
        assertEquals(listOf("east and west"), result.groups.single().senses.map { it.definition })
        assertEquals("dōng xī", result.resolvedReading)
    }

    @Test
    fun `without a reading key the readings compare as stored`() {
        val rows = listOf(row("dictA", "dong1 xi1", 0.0, "east and west"), row("dictA", "dong1 xi5", 0.0, "thing"))
        val result = TermMerge.merge(rows, order, normalizedReadings = setOf("dōng xī"), normalizedTerm = "东西")
        assertEquals(emptyList<ImportedSenseGroup>(), result.groups)
    }

    @Test
    fun `Chinese readings compare canonicalized and every other language's as stored`() {
        assertEquals("dōngxī", YomitanDataStore.readingKeyFor("zh")("dong1 xi1"))
        assertEquals("ナマ", YomitanDataStore.readingKeyFor("ja")("ナマ"))
        assertEquals("dong1 xi1", YomitanDataStore.readingKeyFor("ko")("dong1 xi1"))
    }

    @Test
    fun `rows another entry's reading claims are left out and every other row stays`() {
        // 东西: the default entry reads dōng xi. "east and west" belongs to
        // the dōng xī entry; a row in another convention and a row with no
        // reading (the term as its own reading) are claimed by nobody and stay.
        val rows = listOf(
            row("dictA", "dong1 xi1", 0.0, "east and west"),
            row("dictA", "dong1 xi5", 0.0, "thing"),
            row("dictA", "dong1 xi3", 0.0, "odd convention"),
            row("dictA", "东西", 0.0, "unread"),
        )
        val result = TermMerge.merge(
            rows, order, normalizedReadings = null, normalizedTerm = "东西",
            readingKey = PinyinFormatter::canonicalPinyin, excludedReadings = setOf("dōng xī"),
        )
        assertEquals(listOf("thing", "odd convention", "unread"), result.groups.single().senses.map { it.definition })
    }

    @Test
    fun `an exclusion that claims every row leaves no group and the pack's senses stand`() {
        val rows = listOf(row("dictA", "dong1 xi1", 0.0, "east and west"))
        val result = TermMerge.merge(
            rows, order, normalizedReadings = null, normalizedTerm = "东西", singleDictionary = true,
            readingKey = PinyinFormatter::canonicalPinyin, excludedReadings = setOf("dōng xī"),
        )
        assertEquals(emptyList<ImportedSenseGroup>(), result.groups)
        assertEquals(false, result.shown(builtInHasWord = true).builtInWithheld)
    }

    @Test
    fun `excluded readings compare through the same key as the narrowing`() {
        // Without the pinyin key the numbered row is not the tone-marked
        // reading, so nothing is claimed.
        val rows = listOf(row("dictA", "dong1 xi1", 0.0, "east and west"), row("dictA", "dong1 xi5", 0.0, "thing"))
        val stored = TermMerge.merge(
            rows, order, normalizedReadings = null, normalizedTerm = "东西", excludedReadings = setOf("dōng xī"),
        )
        assertEquals(listOf("east and west", "thing"), stored.groups.single().senses.map { it.definition })
    }
}
