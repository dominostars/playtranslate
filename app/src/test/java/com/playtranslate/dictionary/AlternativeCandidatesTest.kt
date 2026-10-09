package com.playtranslate.dictionary

import com.playtranslate.dictionary.DictionaryManager.Companion.AlternativeKey
import com.playtranslate.language.InflectionTag
import com.playtranslate.language.InflectionTag.POTENTIAL
import com.playtranslate.language.InflectionTag.TA
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [DictionaryManager.alternativeCandidates], the other entries a tapped word
 * could be, run on the real
 * [com.playtranslate.dictionary.deinflect.JapaneseDeinflector] candidates
 * against a stand-in pack. The stand-in holds every candidate text of the
 * words under test that the local JA pack (local/packs-v3/packs/ja,
 * packVersion 4) has an entry for, with that pack's ids in its ranked-query
 * order, each entry's kanji forms and readings in position order (the
 * reading-narrowed query and the primary forms read them) and its senses'
 * distinct part-of-speech tokens.
 */
class AlternativeCandidatesTest {

    private class Entry(val written: List<String>, val readings: List<String>, val pos: List<String>)

    private class Pack(private val idsByText: Map<String, List<Long>>, private val entries: Map<Long, Entry>) {
        fun alternatives(
            surface: String,
            lookupForm: String,
            tokenTags: List<InflectionTag>,
            primaryIds: Set<Long>,
            cap: Int = DictionaryManager.MAX_ALTERNATIVES,
        ): List<AlternativeKey> =
            DictionaryManager.alternativeCandidates(
                surface, lookupForm, tokenTags, primaryIds,
                entryIdsFor = { text -> idsByText[text].orEmpty() },
                // The narrowed query joins a kanji headword to a reading of the same entry.
                entryIdsWithReadingFor = { text, reading ->
                    idsByText[text].orEmpty().filter { id ->
                        val e = entries.getValue(id)
                        text in e.written && reading in e.readings
                    }
                },
                posTokensFor = { ids -> ids.associateWith { entries.getValue(it).pos } },
                primaryFormsFor = { id -> entries.getValue(id).let { it.written.firstOrNull() to it.readings.firstOrNull() } },
                cap = cap,
            )
    }

    private val godanKu = listOf("Godan verb with 'ku' ending", "transitive verb")

    /** 弾ける (はじける) and both 弾く (ひく, はじく): the field report's word. */
    private val hajikuPack = Pack(
        mapOf(
            "弾ける" to listOf(1419380L),
            "弾く" to listOf(1419370L, 1419360L),
        ),
        mapOf(
            1419380L to Entry(listOf("弾ける"), listOf("はじける"), listOf("Ichidan verb", "intransitive verb")),
            1419370L to Entry(listOf("弾く"), listOf("ひく"), godanKu),
            1419360L to Entry(listOf("弾く"), listOf("はじく"), godanKu),
        ),
    )

    @Test
    fun `弾けた offers both 弾く entries in rank order, each once, potential then -た`() {
        // Pass 2 (弾ける deinflects to 弾く, potential, then the token's -た) and
        // pass 3 (弾けた deinflects to 弾く, potential « -た) reach the same two
        // entries; each is offered once.
        assertEquals(
            listOf(
                AlternativeKey("弾く", "ひく", listOf(POTENTIAL, TA), 1419370L),
                AlternativeKey("弾く", "はじく", listOf(POTENTIAL, TA), 1419360L),
            ),
            hajikuPack.alternatives("弾けた", "弾ける", listOf(TA), primaryIds = setOf(1419380L)),
        )
    }

    @Test
    fun `an entry two passes reach keeps the first pass's chain`() {
        // Synthetic token chain: with no tags on the token, pass 2 reads
        // potential alone and pass 3 would read potential « -た; the first wins.
        assertEquals(
            listOf(
                AlternativeKey("弾く", "ひく", listOf(POTENTIAL), 1419370L),
                AlternativeKey("弾く", "はじく", listOf(POTENTIAL), 1419360L),
            ),
            hajikuPack.alternatives("弾けた", "弾ける", emptyList(), primaryIds = setOf(1419380L)),
        )
    }

    @Test
    fun `a tapped dictionary form offers its homograph under the homograph's own reading`() {
        assertEquals(
            listOf(AlternativeKey("弾く", "はじく", emptyList(), 1419360L)),
            hajikuPack.alternatives("弾く", "弾く", emptyList(), primaryIds = setOf(1419370L)),
        )
    }

    @Test
    fun `a conjugated tap offers the homograph its surface conjugates from, with that chain`() {
        assertEquals(
            listOf(AlternativeKey("弾く", "はじく", listOf(TA), 1419360L)),
            hajikuPack.alternatives("弾いた", "弾く", listOf(TA), primaryIds = setOf(1419370L)),
        )
    }

    @Test
    fun `the primary entries are never offered`() {
        assertEquals(
            listOf(AlternativeKey("弾く", "はじく", listOf(POTENTIAL, TA), 1419360L)),
            hajikuPack.alternatives("弾けた", "弾ける", listOf(TA), primaryIds = setOf(1419380L, 1419370L)),
        )
        assertEquals(
            emptyList<AlternativeKey>(),
            hajikuPack.alternatives("弾く", "弾く", emptyList(), primaryIds = setOf(1419370L, 1419360L)),
        )
    }

    @Test
    fun `the cap keeps the first alternatives`() {
        assertEquals(
            listOf(AlternativeKey("弾く", "ひく", listOf(POTENTIAL, TA), 1419370L)),
            hajikuPack.alternatives("弾けた", "弾ける", listOf(TA), primaryIds = setOf(1419380L), cap = 1),
        )
    }

    @Test
    fun `a homograph whose class cannot conjugate to the surface is not offered`() {
        // 来た looked up as 来る (くる): the pack's other 来る (きたる) is a Godan
        // verb and pre-noun adjective, which no -た rule from 来た accepts.
        val pack = Pack(
            mapOf("来る" to listOf(1547720L, 1591270L)),
            mapOf(
                1547720L to Entry(listOf("来る", "來る"), listOf("くる", "クる"), listOf("Kuru verb", "intransitive verb", "Aux. verb")),
                1591270L to Entry(
                    listOf("来る", "来たる"), listOf("きたる"),
                    listOf("Pre-noun adj.", "Godan verb with 'ru' ending", "intransitive verb"),
                ),
            ),
        )
        assertEquals(emptyList<AlternativeKey>(), pack.alternatives("来た", "来る", listOf(TA), primaryIds = setOf(1547720L)))
    }

    @Test
    fun `a nidan-only candidate is rejected`() {
        // 現れる's potential candidate 現る has only the archaic Nidan verb,
        // which names no condition.
        val pack = Pack(
            mapOf("現れる" to listOf(1263510L), "現る" to listOf(2830694L)),
            mapOf(
                1263510L to Entry(
                    listOf("現れる", "現われる", "表れる", "顕れる", "表われる", "顕われる"), listOf("あらわれる"),
                    listOf("Ichidan verb", "intransitive verb"),
                ),
                2830694L to Entry(
                    listOf("現る", "表る", "顕る"), listOf("あらわる"),
                    listOf("Nidan verb (lower class) with 'ru' ending (archaic)", "intransitive verb"),
                ),
            ),
        )
        assertEquals(emptyList<AlternativeKey>(), pack.alternatives("現れた", "現れる", listOf(TA), primaryIds = setOf(1263510L)))
    }

    /** Every candidate text of いった and いく the pack has (いっる has none). */
    private val ittaPack = run {
        val noun = listOf("Noun")
        val taru = listOf("'taru' adjective", "adverb taking the 'to' particle")
        val godanRu = "Godan verb with 'ru' ending"
        val ichidan = "Ichidan verb"
        Pack(
            mapOf(
                "いく" to listOf(1578850L, 1219950L, 2856718L, 2783550L, 2416400L, 1157500L),
                "いい" to listOf(2672300L, 2820690L, 2846386L, 2846378L, 2672330L, 2672320L, 2672310L, 2571360L),
                "いう" to listOf(1587040L, 1254600L),
                "いつ" to listOf(1188760L, 2843147L, 2837668L, 1585390L, 1268060L),
                "いる" to listOf(1546640L, 1577980L, 1587780L, 1391500L, 1322180L, 2729170L, 1465580L, 2851106L),
                "言う" to listOf(1587040L),
                "結う" to listOf(1254600L),
                "要る" to listOf(1546640L),
                "炒る" to listOf(1391500L),
            ),
            mapOf(
                1578850L to Entry(listOf("行く", "往く"), listOf("いく", "ゆく", "イク"), listOf("Godan verb (iku)", "intransitive verb", "Aux. verb")),
                1219950L to Entry(listOf("幾"), listOf("いく"), listOf("Prefix")),
                2856718L to Entry(listOf("逝く"), listOf("いく", "ゆく"), listOf("Godan verb (iku)", "intransitive verb")),
                2783550L to Entry(
                    listOf("生く", "活く"), listOf("いく"),
                    listOf(
                        "Nidan verb (upper class) with 'ku' ending (archaic)", "Yodan verb with 'ku' ending (archaic)",
                        "intransitive verb", "Nidan verb (lower class) with 'ku' ending (archaic)", "transitive verb",
                    ),
                ),
                2416400L to Entry(listOf("生"), listOf("いく"), listOf("Prefix")),
                1157500L to Entry(listOf("畏懼"), listOf("いく"), listOf("Noun", "noun or participle which takes the aux. verb suru")),
                2672300L to Entry(listOf("謂"), listOf("いい"), noun),
                2820690L to Entry(emptyList(), listOf("いい"), listOf("I-adjective (ii)")),
                2846386L to Entry(listOf("依々", "依依"), listOf("いい"), taru),
                2846378L to Entry(listOf("委蛇", "逶迤", "逶迱"), listOf("いい", "いだ"), taru),
                2672330L to Entry(listOf("唯々", "唯唯"), listOf("いい"), taru),
                2672320L to Entry(listOf("易々", "易易"), listOf("いい"), taru),
                2672310L to Entry(listOf("飯"), listOf("いい"), noun),
                2571360L to Entry(listOf("怡々", "怡怡"), listOf("いい"), taru),
                1587040L to Entry(listOf("言う", "云う", "謂う"), listOf("いう", "ゆう"), listOf("Godan verb with 'u' ending", "transitive verb", "intransitive verb")),
                1254600L to Entry(listOf("結う"), listOf("ゆう", "いう"), listOf("Godan verb with 'u' ending", "transitive verb")),
                1188760L to Entry(listOf("何時"), listOf("いつ"), listOf("Pronoun")),
                2843147L to Entry(listOf("一"), listOf("いつ"), listOf("Numeric", "Noun")),
                2837668L to Entry(listOf("凍つ", "冱つ"), listOf("いつ"), listOf("Nidan verb (lower class) with 'tsu' ending (archaic)", "intransitive verb")),
                1585390L to Entry(listOf("佚"), listOf("いつ"), noun),
                1268060L to Entry(listOf("五", "５", "伍"), listOf("ご", "いつ", "い"), listOf("Numeric")),
                1546640L to Entry(listOf("要る"), listOf("いる"), listOf(godanRu, "intransitive verb")),
                1577980L to Entry(listOf("居る"), listOf("いる"), listOf(ichidan, "intransitive verb", "Aux. verb")),
                1587780L to Entry(listOf("鋳る", "鑄る"), listOf("いる"), listOf(ichidan, "transitive verb")),
                1391500L to Entry(listOf("炒る", "煎る", "熬る"), listOf("いる"), listOf(godanRu, "transitive verb")),
                1322180L to Entry(listOf("射る"), listOf("いる"), listOf(ichidan, "transitive verb")),
                2729170L to Entry(listOf("癒る"), listOf("いる"), listOf(ichidan, "intransitive verb")),
                1465580L to Entry(listOf("入る"), listOf("いる"), listOf(godanRu, "intransitive verb", "Suffix")),
                2851106L to Entry(listOf("率る", "将る"), listOf("いる"), listOf(ichidan, "transitive verb")),
            ),
        )
    }

    /** The kana いく's own lookup returns all six of its entries. */
    private val ikuIds = setOf(1578850L, 1219950L, 2856718L, 2783550L, 2416400L, 1157500L)

    @Test
    fun `a kana candidate's entry is keyed so the lookup ranks it first`() {
        // いった looked up as いく (-た). Pass 2's いい (-く) entry, the
        // kana-only I-adjective, is not offered: the lookup of いい ranks 謂
        // first. Pass 3's いう keeps its text for 言う, which the kana lookup
        // ranks first, and keys 結う by its written form read いう, the
        // reading the surface reached it through (its first reading ゆう
        // conjugates to ゆった); いる's Godan 要る is first under the kana.
        // 居る (Ichidan) and いつ's entries (a pronoun, nouns, numerals, a
        // Nidan verb) reject -た. Each chain is the surface's own, the
        // token's -た not added twice.
        assertEquals(
            listOf(
                AlternativeKey("いう", "いう", listOf(TA), 1587040L),
                AlternativeKey("結う", "いう", listOf(TA), 1254600L),
                AlternativeKey("いる", "いる", listOf(TA), 1546640L),
            ),
            ittaPack.alternatives("いった", "いく", listOf(TA), primaryIds = ikuIds),
        )
        // A fourth slot reaches 炒る, which the kana lookup ranks below 要る.
        assertEquals(
            AlternativeKey("炒る", "いる", listOf(TA), 1391500L),
            ittaPack.alternatives("いった", "いく", listOf(TA), primaryIds = ikuIds, cap = 4).last(),
        )
    }

    /** The kana ゆう's lookup (the first eight of the pack's thirteen
     *  entries read ゆう, in ranked order, 言う and 結う among them) and the
     *  kanji lookups of 結う and 言う. 結う reads ゆう then いう, 言う reads
     *  いう then ゆう. Unlike the other stand-ins it holds none of ゆう's
     *  deinflection candidates: the test's cap is filled in pass 1, before
     *  pass 2 looks one up. */
    private val yuuPack = Pack(
        mapOf(
            "ゆう" to listOf(2834885L, 1540940L, 1254600L, 1587040L, 2844332L, 2844201L, 2836662L, 2813860L),
            "結う" to listOf(1254600L),
            "言う" to listOf(1587040L),
        ),
        mapOf(
            2834885L to Entry(listOf("夕"), listOf("ゆう"), listOf("Noun", "Adverb")),
            1540940L to Entry(listOf("有"), listOf("ゆう"), listOf("Noun", "Noun prefix")),
            1254600L to Entry(listOf("結う"), listOf("ゆう", "いう"), listOf("Godan verb with 'u' ending", "transitive verb")),
            1587040L to Entry(
                listOf("言う", "云う", "謂う"), listOf("いう", "ゆう"),
                listOf("Godan verb with 'u' ending", "transitive verb", "intransitive verb"),
            ),
            2844332L to Entry(listOf("友"), listOf("ゆう"), listOf("Noun", "Na-adjective")),
            2844201L to Entry(listOf("悠"), listOf("ゆう"), listOf("archaic/formal form of na-adjective")),
            2836662L to Entry(listOf("雄"), listOf("ゆう"), listOf("Noun")),
            2813860L to Entry(listOf("尤"), listOf("ゆう"), listOf("archaic/formal form of na-adjective")),
        ),
    )

    @Test
    fun `a kana tap offers each entry under the reading it was tapped as`() {
        // ゆう tapped as itself. Its own lookup returns all eight entries, so
        // the primary set here is the two that share its top score, 夕 and
        // 有, which leaves 言う to be offered. Both verbs are reached through
        // ゆう and carry it: 結う its first reading, 言う its second, not its
        // first いう. A cap of two stops the walk before 友, whose own kanji
        // lookup ranks an entry the stand-in leaves out (友 read とも) first.
        assertEquals(
            listOf(
                AlternativeKey("結う", "ゆう", emptyList(), 1254600L),
                AlternativeKey("言う", "ゆう", emptyList(), 1587040L),
            ),
            yuuPack.alternatives("ゆう", "ゆう", emptyList(), primaryIds = setOf(2834885L, 1540940L), cap = 2),
        )
    }
}
