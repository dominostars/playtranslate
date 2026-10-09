package com.playtranslate.dictionary.deinflect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageTransformerTest {

    private val japanese = LanguageTransformer(JapaneseTransforms.descriptor)

    private fun flags(key: String): Int = japanese.conditionFlags(listOf(key))

    private fun leaf(key: String): Condition = Condition(key, isDictionaryForm = false, subConditions = null)

    private fun suffix(suffixIn: String, suffixOut: String, conditionsIn: List<String>, conditionsOut: List<String>): Rule =
        Rule.Suffix(suffixIn, suffixOut, conditionsIn, conditionsOut)

    private fun transformer(conditions: List<Condition>, vararg transforms: Pair<String, Rule>): LanguageTransformer =
        LanguageTransformer(
            TransformDescriptor(conditions, transforms.map { (key, rule) -> Transform(key, key, listOf(rule)) }),
        )

    @Test(timeout = 10_000)
    fun cycleGuardStopsALengtheningLoop() {
        // grow lengthens a to ab and shrink takes it back: without the guard the queue never drains.
        val loop = transformer(
            emptyList(),
            "grow" to suffix("a", "ab", emptyList(), emptyList()),
            "shrink" to suffix("ab", "a", emptyList(), emptyList()),
        )
        assertEquals(
            listOf(
                TransformedText("a", 0, emptyList()),
                TransformedText("ab", 0, listOf(TraceFrame("grow", 0, "a"))),
                TransformedText("a", 0, listOf(TraceFrame("shrink", 0, "ab"), TraceFrame("grow", 0, "a"))),
            ),
            loop.transform("a"),
        )
    }

    @Test
    fun cycleGuardLetsARuleRepeatOnADifferentText() {
        val halve = transformer(emptyList(), "halve" to suffix("aa", "a", emptyList(), emptyList()))
        assertEquals(listOf("aaa", "aa", "a"), halve.transform("aaa").map { it.text })
    }

    @Test
    fun conditionsZeroPassesEveryRuleAndEmptyConditionsInRequiresZero() {
        val gated = transformer(
            listOf(leaf("x"), leaf("y")),
            "toY" to suffix("c", "b", emptyList(), listOf("y")),
            "needsX" to suffix("b", "a", listOf("x"), listOf("x")),
            "needsZero" to suffix("a", "z", emptyList(), emptyList()),
        )
        // The source carries conditions 0, so the x-gated rule applies; its x result fails the empty-conditionsIn rule.
        assertEquals(listOf("b", "a"), gated.transform("b").map { it.text })
        assertEquals(listOf("a", "z"), gated.transform("a").map { it.text })
        // A y result does not pass the x-gated rule.
        assertEquals(listOf("c", "b"), gated.transform("c").map { it.text })

        // The real table: kansai-ben -たら has no conditionsOut, so -たら (no conditionsIn) applies mid-chain.
        val results = japanese.transform("買うたら")
        assertEquals(0, results.single { it.text == "買ったら" }.conditions)
        assertTrue(results.any { it.text == "買う" && it.trace.map { f -> f.transformKey } == listOf("-たら", "kansai-ben -たら") })
    }

    @Test
    fun emptyOutRuleYieldsAnEmptyTextThatTheFacadeDrops() {
        // -た's でした -> "" rule applied to a text that is exactly でした.
        val empty = japanese.transform("でした").single { it.text.isEmpty() }
        assertEquals(listOf(TraceFrame("-た", 37, "でした")), empty.trace)
        assertEquals(flags("-ません"), empty.conditions)
        val candidates = JapaneseDeinflector.candidates("でした")
        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.none { it.text.isEmpty() })
    }

    @Test
    fun ruleIndexMatchesNaiveScanOverEveryFixtureSource() {
        val descriptor = JapaneseTransforms.descriptor
        for (source in YomitanJapaneseFixture.fixture.cases.map { it.source }.distinct()) {
            assertEquals(source, naiveTransform(descriptor, source), japanese.transform(source))
        }
    }

    /** Upstream `transform` restated without the last-character index: every transform, every rule, in order. */
    private fun naiveTransform(descriptor: TransformDescriptor, source: String): List<TransformedText> {
        val results = mutableListOf(TransformedText(source, 0, emptyList()))
        var i = 0
        while (i < results.size) {
            val (text, conditions, trace) = results[i++]
            for (transform in descriptor.transforms) {
                transform.rules.forEachIndexed { j, rule ->
                    if (!LanguageTransformer.conditionsMatch(conditions, japanese.conditionFlags(rule.conditionsIn))) return@forEachIndexed
                    if (!rule.isInflected(text)) return@forEachIndexed
                    if (trace.any { it.transformKey == transform.key && it.ruleIndex == j && it.text == text }) return@forEachIndexed
                    val frame = TraceFrame(transform.key, j, text)
                    results.add(TransformedText(rule.deinflect(text), japanese.conditionFlags(rule.conditionsOut), listOf(frame) + trace))
                }
            }
        }
        return results
    }

    @Test
    fun conditionBitsFollowDeclarationOrderWithCompositesResolvedOverPasses() {
        val leaves = listOf(
            "v1d", "v1p", "v5d", "v5ss", "v5sp", "vk", "vs", "vz", "adj-i",
            "-ます", "-ません", "-て", "-ば", "-く", "-た", "-ん", "-なさい", "-ゃ",
        )
        leaves.forEachIndexed { bit, key -> assertEquals(key, 1 shl bit, flags(key)) }
        assertEquals(flags("v1d") or flags("v1p"), flags("v1"))
        assertEquals(flags("v5ss") or flags("v5sp"), flags("v5s"))
        assertEquals(flags("v5d") or flags("v5s"), flags("v5"))
        assertEquals(flags("v1") or flags("v5") or flags("vk") or flags("vs") or flags("vz"), flags("v"))
        assertEquals(0, flags("unknown"))
        // Only isDictionaryForm conditions count as parts of speech.
        assertEquals(flags("v1"), japanese.partOfSpeechFlags(listOf("v1", "v1d", "-ます", "v")))
    }

    @Test
    fun moreThanThirtyTwoLeafConditionsThrow() {
        fun descriptor(leafCount: Int) = TransformDescriptor((0 until leafCount).map { leaf("c$it") }, emptyList())
        assertEquals(Int.MIN_VALUE, LanguageTransformer(descriptor(32)).conditionFlags(listOf("c31")))
        assertThrows(IllegalArgumentException::class.java) { LanguageTransformer(descriptor(33)) }
    }

    @Test
    fun posFlagsMapEachClassFamily() {
        // Every verb and adjective class token the JA pack stores in sense.pos, plus tokens that name none.
        val expected = linkedMapOf(
            "Ichidan verb" to flags("v1"),
            "Ichidan verb (kureru)" to flags("v1"),
            "Ichidan verb (zuru)" to flags("vz"),
            "Godan verb with 'ru' ending" to flags("v5"),
            "Godan verb with 'su' ending" to flags("v5"),
            "Godan verb with 'ku' ending" to flags("v5"),
            "Godan verb with 'u' ending" to flags("v5"),
            "Godan verb with 'mu' ending" to flags("v5"),
            "Godan verb with 'tsu' ending" to flags("v5"),
            "Godan verb with 'gu' ending" to flags("v5"),
            "Godan verb with 'bu' ending" to flags("v5"),
            "Godan verb with 'nu' ending" to flags("v5"),
            "Godan verb with 'ru' ending (irregular verb)" to flags("v5"),
            "Godan verb with 'u' ending (special class)" to flags("v5"),
            "Godan verb (iku)" to flags("v5"),
            "Godan verb - -aru special class" to flags("v5"),
            "Suru verb" to flags("vs"),
            "Suru verb (special)" to flags("vs"),
            "su verb - precursor to the modern suru" to flags("vs"),
            "Kuru verb" to flags("vk"),
            "I-adjective" to flags("adj-i"),
            "I-adjective (ii)" to flags("adj-i"),
            "Nidan verb (lower class) with 'ru' ending (archaic)" to 0,
            "Yodan verb with 'ku' ending (archaic)" to 0,
            "'ku' adjective (archaic)" to 0,
            "irregular nu verb" to 0,
            "irregular ru verb" to 0,
            "Noun" to 0,
            "noun or participle which takes the aux. verb suru" to 0,
            "Na-adjective" to 0,
            "Aux. verb" to 0,
            "transitive verb" to 0,
            "intransitive verb" to 0,
        )
        for ((pos, posFlags) in expected) assertEquals(pos, posFlags, JapaneseDeinflector.posFlags(listOf(pos)))
        assertEquals(
            flags("v5") or flags("vs"),
            JapaneseDeinflector.posFlags(listOf("Noun", "Godan verb with 'su' ending", "Suru verb")),
        )
    }

    @Test
    fun acceptsTruthTable() {
        fun candidate(conditions: Int) = Deinflection("x", listOf("-た"), conditions)
        val v1 = flags("v1")
        val v5 = flags("v5")
        // A conditions-0 candidate accepts any entry, with or without a recognized class.
        assertTrue(JapaneseDeinflector.accepts(candidate(0), 0))
        assertTrue(JapaneseDeinflector.accepts(candidate(0), v5))
        // An entry with no recognized class rejects every candidate that carries conditions.
        assertFalse(JapaneseDeinflector.accepts(candidate(v1), 0))
        // Otherwise the two masks must share a flag.
        assertTrue(JapaneseDeinflector.accepts(candidate(v1), v1))
        assertFalse(JapaneseDeinflector.accepts(candidate(v1), v5))
        assertTrue(JapaneseDeinflector.accepts(candidate(flags("v1d")), v1))
        assertTrue(JapaneseDeinflector.accepts(candidate(flags("v")), v5 or flags("vs")))
        assertFalse(JapaneseDeinflector.accepts(candidate(flags("adj-i")), v1 or v5))
    }

    @Test
    fun candidatesAreTheTransformResultsAfterTheSourceNonEmptyFirstPerTextAndConditions() {
        for (source in YomitanJapaneseFixture.fixture.cases.map { it.source }.distinct()) {
            val expected = japanese.transform(source)
                .drop(1)
                .filter { it.text.isNotEmpty() }
                .distinctBy { it.text to it.conditions }
                .map { Deinflection(it.text, it.trace.map { frame -> frame.transformKey }, it.conditions) }
            val candidates = JapaneseDeinflector.candidates(source)
            assertEquals(source, expected, candidates)
            assertEquals(source, candidates.sortedBy { it.chainLength }, candidates)
        }
        assertTrue(
            JapaneseDeinflector.candidates("食べませんでした")
                .any { it.text == "食べる" && it.transformKeys == listOf("-ます", "negative", "-た") && it.chainLength == 3 },
        )
    }

    @Test
    fun repeatedWordReturnsTheSameListInstance() {
        val first = JapaneseDeinflector.candidates("食べさせられた")
        assertSame(first, JapaneseDeinflector.candidates("食べさせられた"))
    }

    @Test
    fun cacheKeepsTheLast256WordsByAccess() {
        val kept = JapaneseDeinflector.candidates("書かれた")
        repeat(255) { JapaneseDeinflector.candidates("書かれた$it") }
        // Still cached, and this access makes it the most recent entry again.
        assertSame(kept, JapaneseDeinflector.candidates("書かれた"))
        repeat(255) { JapaneseDeinflector.candidates("読まれた$it") }
        assertSame(kept, JapaneseDeinflector.candidates("書かれた"))
        repeat(256) { JapaneseDeinflector.candidates("見られた$it") }
        assertNotSame(kept, JapaneseDeinflector.candidates("書かれた"))
    }
}
