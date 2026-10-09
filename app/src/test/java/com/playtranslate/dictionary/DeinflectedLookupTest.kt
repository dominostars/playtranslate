package com.playtranslate.dictionary

import com.playtranslate.dictionary.DictionaryManager.Companion.DeinflectedHit
import com.playtranslate.language.InflectionTag.CONTINUATIVE
import com.playtranslate.language.InflectionTag.KANSAI_BEN_TARA
import com.playtranslate.language.InflectionTag.MASU
import com.playtranslate.language.InflectionTag.NEGATIVE
import com.playtranslate.language.InflectionTag.POTENTIAL
import com.playtranslate.language.InflectionTag.TA
import com.playtranslate.language.InflectionTag.TARA
import com.playtranslate.language.InflectionTag.TE
import com.playtranslate.language.YomitanEnrichment
import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.DictionaryResponse
import com.playtranslate.model.Headword
import com.playtranslate.model.ImportedSense
import com.playtranslate.model.ImportedSenseGroup
import com.playtranslate.yomitan.YomitanDataStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DictionaryManager.firstAcceptedDeinflection], the deinflection stage that
 * lookup and lookupReadingsOnly share, run on the real
 * [com.playtranslate.dictionary.deinflect.JapaneseDeinflector] candidates
 * against a stand-in pack. Unless a test says otherwise, the stand-in holds
 * every candidate text the local JA pack (local/packs-v3/packs/ja, packVersion
 * 4) has an entry for, with that pack's ids in its ranked-query order and each
 * entry's distinct sense.pos tokens, so each verdict is the one the app
 * reaches.
 */
class DeinflectedLookupTest {

    /** Text to entry ids (the ranked query's order) and entry id to its senses' POS tokens. */
    private class Pack(private val idsByText: Map<String, List<Long>>, private val posById: Map<Long, List<String>>) {
        fun resolve(word: String): DeinflectedHit? =
            DictionaryManager.firstAcceptedDeinflection(
                word,
                { text -> idsByText[text].orEmpty() },
                { ids -> ids.filter { it in posById }.associateWith { posById.getValue(it) } },
            )
    }

    private val oyogu = 1174340L to listOf("Godan verb with 'gu' ending", "intransitive verb")
    private val taberu = 1358280L to listOf("Ichidan verb", "transitive verb")
    private val arawaru = 2830694L to listOf("Nidan verb (lower class) with 'ru' ending (archaic)", "intransitive verb")
    private val arawareru = 1263510L to listOf("Ichidan verb", "intransitive verb")
    private val kau = 1473740L to listOf("Godan verb with 'u' ending", "transitive verb")
    private val ireru = 1465610L to listOf("Ichidan verb", "transitive verb")
    private val hairu = 1465590L to listOf("Godan verb with 'ru' ending", "intransitive verb")
    private val iru = 1465580L to listOf("Godan verb with 'ru' ending", "intransitive verb", "Suffix")
    private val kuru = 1547720L to listOf("Kuru verb", "intransitive verb", "Aux. verb")
    private val kitaru = 1591270L to listOf("Pre-noun adj.", "Godan verb with 'ru' ending", "intransitive verb")

    /** Every candidate text of しなかった the pack has: nouns, a suffix, a Godan 知る, and する (one Suru, four Godan). */
    private val shinakattaPack = Pack(
        mapOf(
            "しない" to listOf(1422340L, 1308640L),
            "しな" to listOf(1583470L, 2836179L, 1638310L, 2728200L),
            "しる" to listOf(1420470L, 1335520L),
            "する" to listOf(1157170L, 1595910L, 1567440L, 1298670L, 1581900L),
        ),
        mapOf(
            1422340L to listOf("Noun"),
            1308640L to listOf("Noun", "nouns which may take the genitive case particle 'no'"),
            1583470L to listOf("Noun"),
            2836179L to listOf("Noun"),
            1638310L to listOf("Noun"),
            2728200L to listOf("Suffix"),
            1420470L to listOf("Godan verb with 'ru' ending", "transitive verb"),
            1335520L to listOf("Noun", "Noun suffix"),
            1157170L to listOf("Suru verb", "intransitive verb", "transitive verb", "Suffix", "Aux. verb"),
            1595910L to listOf("Godan verb with 'ru' ending", "transitive verb"),
            1567440L to listOf("Godan verb with 'ru' ending", "transitive verb"),
            1298670L to listOf("Godan verb with 'ru' ending", "transitive verb"),
            1581900L to listOf("Godan verb with 'ru' ending", "transitive verb"),
        ),
    )

    @Test
    fun `potential form resolves to its godan verb`() {
        val pack = Pack(mapOf("泳ぐ" to listOf(oyogu.first)), mapOf(oyogu))
        assertEquals(DeinflectedHit(listOf(oyogu.first), listOf(POTENTIAL)), pack.resolve("泳げる"))
    }

    @Test
    fun `polite negative past resolves to the ichidan verb with the whole chain`() {
        val pack = Pack(mapOf("食べる" to listOf(taberu.first)), mapOf(taberu))
        assertEquals(DeinflectedHit(listOf(taberu.first), listOf(MASU, NEGATIVE, TA)), pack.resolve("食べませんでした"))
    }

    @Test
    fun `a nidan-only entry rejects the candidate and the next candidate wins`() {
        // 現れ's first candidate is 現る (imperative, v5); the pack's only 現る is
        // the archaic Nidan verb, which names no condition. 現れる (continuative) is next.
        val pack = Pack(
            mapOf("現る" to listOf(arawaru.first), "現れる" to listOf(arawareru.first)),
            mapOf(arawaru, arawareru),
        )
        assertEquals(DeinflectedHit(listOf(arawareru.first), listOf(CONTINUATIVE)), pack.resolve("現れ"))
    }

    @Test
    fun `candidates whose entries all reject them are skipped and the search goes on`() {
        // しない (adj-i) has only nouns, しな only nouns and a suffix, しる (v1) a
        // Godan verb and a noun: each is skipped. する (vs) keeps 為る alone, not
        // its four Godan homographs.
        assertEquals(DeinflectedHit(listOf(1157170L), listOf(NEGATIVE, TA)), shinakattaPack.resolve("しなかった"))
    }

    @Test
    fun `a repeated text is judged per candidate conditions`() {
        // 来た yields 来る twice: as v1 (-た), which neither 来る entry accepts,
        // then as vk, which the Kuru verb accepts and 来る (きたる, Godan) does not.
        val pack = Pack(mapOf("来る" to listOf(kuru.first, kitaru.first)), mapOf(kuru, kitaru))
        assertEquals(DeinflectedHit(listOf(kuru.first), listOf(TA)), pack.resolve("来た"))
    }

    @Test
    fun `the shorter chain wins when two candidates resolve`() {
        // 入れて: 入れる (-て) precedes 入る (potential, -て), and both have entries that accept them.
        val pack = Pack(
            mapOf("入れる" to listOf(ireru.first), "入る" to listOf(hairu.first, iru.first)),
            mapOf(ireru, hairu, iru),
        )
        assertEquals(DeinflectedHit(listOf(ireru.first), listOf(TE)), pack.resolve("入れて"))
    }

    @Test
    fun `kansai-ben past conditional reaches the godan verb`() {
        val pack = Pack(mapOf("買う" to listOf(kau.first)), mapOf(kau))
        assertEquals(DeinflectedHit(listOf(kau.first), listOf(TARA, KANSAI_BEN_TARA)), pack.resolve("買うたら"))
    }

    @Test
    fun `a conditions-0 candidate accepts an entry with no recognized class`() {
        // Synthetic: the pack has no 買ったら. kansai-ben -たら names no
        // conditionsOut, so its candidate 買ったら carries conditions 0 and
        // accepts an entry whose parts of speech map to no flag; it precedes 買う.
        val kattara = 9_000_001L to listOf("expressions (phrases, clauses, etc.)")
        val pack = Pack(
            mapOf("買ったら" to listOf(kattara.first), "買う" to listOf(kau.first)),
            mapOf(kattara, kau),
        )
        assertEquals(DeinflectedHit(listOf(kattara.first), listOf(KANSAI_BEN_TARA)), pack.resolve("買うたら"))
    }

    @Test
    fun `nothing resolves when no candidate has an accepting entry`() {
        // Stand-ins that leave out entries the pack has: an empty one, and one
        // holding the Nidan-only 現る without 現れる.
        assertNull(Pack(emptyMap(), emptyMap()).resolve("泳げる"))
        assertNull(Pack(mapOf("現る" to listOf(arawaru.first)), mapOf(arawaru)).resolve("現れ"))
    }

    @Test
    fun `the chain survives the imported-dictionary merge on a pack hit`() {
        val entry = DictionaryEntry(
            slug = "泳ぐ",
            packId = oyogu.first,
            isCommon = null,
            tags = emptyList(),
            jlpt = emptyList(),
            headwords = listOf(Headword(written = "泳ぐ", reading = "およぐ")),
            senses = emptyList(),
        )
        val pack = DictionaryResponse(listOf(entry), deinflection = listOf(POTENTIAL))
        val groups = listOf(ImportedSenseGroup(source = "J", senses = listOf(ImportedSense("to swim"))))
        for (suppress in listOf(false, true)) {
            val merged = YomitanEnrichment.mergeImportedTerms(
                pack, "泳げる", YomitanDataStore.TermLookup(groups, null, suppress), "泳げる",
            )
            assertEquals(listOf(POTENTIAL), merged?.deinflection)
        }
        // A pack miss synthesized from an imported dictionary carries no chain.
        val synthesized = YomitanEnrichment.mergeImportedTerms(
            null, "泳げる", YomitanDataStore.TermLookup(groups, null), "泳ぐ",
        )
        assertTrue(synthesized!!.deinflection.isEmpty())
    }
}
