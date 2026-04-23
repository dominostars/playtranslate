package com.playtranslate.language

import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.DictionaryResponse
import com.playtranslate.model.Headword
import com.playtranslate.model.Sense
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Pure JUnit tests for [DefinitionResolver]. Uses fake implementations of
 * [SourceLanguageEngine], [TargetGlossLookup], and [WordTranslator] — no
 * Android framework or mocking library needed.
 */
class DefinitionResolverTest {

    // ── Fakes ────────────────────────────────────────────────────────────

    /** Fake engine that returns a pre-configured response for any lookup. */
    private class FakeEngine(
        private val responses: Map<String, DictionaryResponse> = emptyMap(),
        override val profile: SourceLanguageProfile = SourceLanguageProfiles[SourceLangId.JA],
    ) : SourceLanguageEngine {
        override suspend fun preload(): PreloadResult = PreloadResult.Success
        override suspend fun tokenize(text: String) = emptyList<TokenSpan>()
        override suspend fun lookup(word: String, reading: String?): DictionaryResponse? =
            responses[word]
        override fun close() {}
    }

    /** Fake target gloss DB that returns pre-configured senses. */
    private class FakeTargetGlossDb(
        private val data: Map<String, List<TargetSense>> = emptyMap()
    ) : TargetGlossLookup {
        /** Track which headwords were queried, for fan-out verification. */
        val queriedHeadwords = mutableListOf<String>()

        override fun lookup(sourceLang: String, written: String, reading: String?): List<TargetSense>? {
            queriedHeadwords.add(written)
            return data["$sourceLang:$written"]
        }
    }

    /** Fake translator that prepends a prefix to inputs. */
    private class FakeTranslator(private val prefix: String = "TR:") : WordTranslator {
        val calls = mutableListOf<String>()
        override suspend fun translate(text: String): String {
            calls.add(text)
            return "$prefix$text"
        }
    }

    /** Translator that always throws. */
    private class FailingTranslator : WordTranslator {
        override suspend fun translate(text: String): String =
            throw RuntimeException("ML Kit unavailable")
    }

    /** Translator that returns the input unchanged (identity). */
    private class IdentityTranslator : WordTranslator {
        override suspend fun translate(text: String) = text
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun entry(
        slug: String,
        written: String? = slug,
        reading: String? = null,
        senses: List<Sense> = listOf(sense("to eat")),
        freqScore: Int = 0,
    ) = DictionaryEntry(
        slug = slug,
        isCommon = null,
        tags = emptyList(),
        jlpt = emptyList(),
        headwords = listOf(Headword(written, reading)),
        senses = senses,
        freqScore = freqScore,
    )

    private fun sense(vararg definitions: String, pos: List<String> = emptyList()) = Sense(
        targetDefinitions = definitions.toList(),
        partsOfSpeech = pos,
        tags = emptyList(),
        restrictions = emptyList(),
        info = emptyList(),
    )

    private fun response(vararg entries: DictionaryEntry) =
        DictionaryResponse(entries.toList())

    private fun targetSense(senseOrd: Int, vararg glosses: String, source: String = "jmdict") =
        TargetSense(senseOrd, emptyList(), glosses.toList(), source)

    // ── Tier 1: Native ─────────────────────────────────────────────��────

    @Test fun `tier 1 - returns Native when target DB has a match`() = runBlocking {
        val resp = response(entry("食べる", reading = "たべる"))
        val glossDb = FakeTargetGlossDb(mapOf(
            "ja:食べる" to listOf(targetSense(0, "manger"))
        ))
        val resolver = DefinitionResolver(
            FakeEngine(mapOf("食べる" to resp)), glossDb, null, "fr"
        )

        val result = resolver.lookup("食べる", "たべる")

        assertTrue(result is DefinitionResult.Native)
        val native = result as DefinitionResult.Native
        assertEquals("manger", native.targetSenses[0].glosses[0])
        assertEquals("jmdict", native.source)
    }

    @Test fun `tier 1 - fan-out tries slug and tapped word`() = runBlocking {
        val resp = response(entry("走る", written = "走る", reading = "はしる"))
        val glossDb = FakeTargetGlossDb(mapOf(
            "ja:走" to listOf(targetSense(0, "courir"))  // only matches tapped surface
        ))
        val resolver = DefinitionResolver(
            FakeEngine(mapOf("走" to resp)), glossDb, null, "fr"
        )

        val result = resolver.lookup("走", null)

        assertTrue("Should find via tapped surface", result is DefinitionResult.Native)
        // Should have tried: "走る" (written), "走る" (slug), "走" (tapped word)
        assertTrue(glossDb.queriedHeadwords.contains("走"))
    }

    @Test fun `tier 1 - does NOT fan out to entries beyond the first`() = runBlocking {
        val resp = response(
            entry("食べる", written = "食べる"),
            entry("喰べる", written = "喰べる"),  // second entry — archaic variant
        )
        val glossDb = FakeTargetGlossDb(mapOf(
            "ja:喰べる" to listOf(targetSense(0, "manger (archaique)"))
        ))
        val resolver = DefinitionResolver(
            FakeEngine(mapOf("食べる" to resp)), glossDb, null, "fr"
        )

        val result = resolver.lookup("食べる", null)

        // Should NOT match 喰べる because it's entries[1], not entries[0]
        assertFalse("Should not cross-match entries[1]", result is DefinitionResult.Native)
    }

    @Test fun `tier 1 - skipped when target is English`() = runBlocking {
        val resp = response(entry("食べる"))
        val glossDb = FakeTargetGlossDb(mapOf(
            "ja:食べる" to listOf(targetSense(0, "manger"))
        ))
        val resolver = DefinitionResolver(
            FakeEngine(mapOf("食べる" to resp)), glossDb, null, "en"
        )

        val result = resolver.lookup("食べる", null)

        assertTrue("English target should skip tier 1", result is DefinitionResult.EnglishFallback)
    }

    @Test fun `tier 1 - skipped when target DB is null`() = runBlocking {
        val resp = response(entry("食べる"))
        val resolver = DefinitionResolver(
            FakeEngine(mapOf("食べる" to resp)), null, null, "fr"
        )

        val result = resolver.lookup("食べる", null)

        assertTrue(result is DefinitionResult.EnglishFallback)
    }

    // ── Tier 2: MachineTranslated ───────────────────────────────────────

    @Test fun `tier 2 - returns MachineTranslated with translated headword`() = runBlocking {
        val resp = response(entry("食べる", reading = "たべる", senses = listOf(sense("to eat"))))
        val translator = FakeTranslator("FR:")
        val resolver = DefinitionResolver(
            FakeEngine(mapOf("食べる" to resp)), null, translator, "fr"
        )

        val result = resolver.lookup("食べる", null)

        assertTrue(result is DefinitionResult.MachineTranslated)
        val mt = result as DefinitionResult.MachineTranslated
        assertEquals("FR:食べる", mt.translatedHeadword)
    }

    @Test fun `tier 2 - includes translated definitions when enToTarget available`() = runBlocking {
        val resp = response(entry("食べる", senses = listOf(sense("to eat"), sense("to consume"))))
        val headwordTranslator = FakeTranslator("FR:")
        val defTranslator = FakeTranslator("DEF:")
        val resolver = DefinitionResolver(
            FakeEngine(mapOf("食べる" to resp)), null, headwordTranslator, "fr", defTranslator
        )

        val result = resolver.lookup("食べる", null)

        assertTrue(result is DefinitionResult.MachineTranslated)
        val mt = result as DefinitionResult.MachineTranslated
        assertNotNull(mt.translatedDefinitions)
        assertEquals(2, mt.translatedDefinitions!!.size)
        assertEquals("DEF:to eat", mt.translatedDefinitions!![0])
        assertEquals("DEF:to consume", mt.translatedDefinitions!![1])
    }

    @Test fun `tier 2 - translatedDefinitions null when no enToTarget translator`() = runBlocking {
        val resp = response(entry("食べる", senses = listOf(sense("to eat"))))
        val resolver = DefinitionResolver(
            FakeEngine(mapOf("食べる" to resp)), null, FakeTranslator(), "fr", null
        )

        val result = resolver.lookup("食べる", null)

        assertTrue(result is DefinitionResult.MachineTranslated)
        assertNull((result as DefinitionResult.MachineTranslated).translatedDefinitions)
    }

    @Test fun `tier 2 - falls through when translator returns same word`() = runBlocking {
        val resp = response(entry("hello", senses = listOf(sense("a greeting"))))
        val resolver = DefinitionResolver(
            FakeEngine(mapOf("hello" to resp)), null, IdentityTranslator(), "fr"
        )

        val result = resolver.lookup("hello", null)

        assertTrue("Same-word translation should fall through", result is DefinitionResult.EnglishFallback)
    }

    @Test fun `tier 2 - falls through when translator throws`() = runBlocking {
        val resp = response(entry("食べる", senses = listOf(sense("to eat"))))
        val resolver = DefinitionResolver(
            FakeEngine(mapOf("食べる" to resp)), null, FailingTranslator(), "fr"
        )

        val result = resolver.lookup("食べる", null)

        assertTrue("Failing translator should fall through", result is DefinitionResult.EnglishFallback)
    }

    @Test fun `tier 2 - skipped when target is English`() = runBlocking {
        val resp = response(entry("食べる", senses = listOf(sense("to eat"))))
        val translator = FakeTranslator()
        val resolver = DefinitionResolver(
            FakeEngine(mapOf("食べる" to resp)), null, translator, "en"
        )

        val result = resolver.lookup("食べる", null)

        assertTrue(result is DefinitionResult.EnglishFallback)
        assertTrue("Should not call translator for English target", translator.calls.isEmpty())
    }

    // ── Tier 3: EnglishFallback ─────────────────────────────────────────

    @Test fun `tier 3 - returns EnglishFallback with translated definitions`() = runBlocking {
        val resp = response(entry("食べる", senses = listOf(sense("to eat"))))
        val defTranslator = FakeTranslator("ES:")
        val resolver = DefinitionResolver(
            FakeEngine(mapOf("食べる" to resp)), null, null, "es", defTranslator
        )

        val result = resolver.lookup("食べる", null)

        assertTrue(result is DefinitionResult.EnglishFallback)
        val fb = result as DefinitionResult.EnglishFallback
        assertNotNull(fb.translatedDefinitions)
        assertEquals("ES:to eat", fb.translatedDefinitions!![0])
    }

    @Test fun `tier 3 - translatedDefinitions null when target is English`() = runBlocking {
        val resp = response(entry("食べる", senses = listOf(sense("to eat"))))
        val defTranslator = FakeTranslator("ES:")
        val resolver = DefinitionResolver(
            FakeEngine(mapOf("食べる" to resp)), null, null, "en", defTranslator
        )

        val result = resolver.lookup("食べる", null)

        assertTrue(result is DefinitionResult.EnglishFallback)
        assertNull((result as DefinitionResult.EnglishFallback).translatedDefinitions)
    }

    @Test fun `tier 3 - translatedDefinitions null when no enToTarget translator`() = runBlocking {
        val resp = response(entry("食べる", senses = listOf(sense("to eat"))))
        val resolver = DefinitionResolver(
            FakeEngine(mapOf("食べる" to resp)), null, null, "fr", null
        )

        val result = resolver.lookup("食べる", null)

        assertTrue(result is DefinitionResult.EnglishFallback)
        assertNull((result as DefinitionResult.EnglishFallback).translatedDefinitions)
    }

    @Test fun `tier 3 - definition translation failure falls back to English text`() = runBlocking {
        val resp = response(entry("食べる", senses = listOf(sense("to eat"))))
        val resolver = DefinitionResolver(
            FakeEngine(mapOf("食べる" to resp)), null, null, "fr", FailingTranslator()
        )

        val result = resolver.lookup("食べる", null)

        assertTrue(result is DefinitionResult.EnglishFallback)
        val fb = result as DefinitionResult.EnglishFallback
        assertNotNull(fb.translatedDefinitions)
        // Failing translator → falls back to original English text
        assertEquals("to eat", fb.translatedDefinitions!![0])
    }

    // ── Edge cases ──────────────────────────────────────────────────────

    @Test fun `returns null when engine returns null`() = runBlocking {
        val resolver = DefinitionResolver(
            FakeEngine(), null, null, "fr"
        )

        assertNull(resolver.lookup("nonexistent", null))
    }

    @Test fun `handles empty entries list`() = runBlocking {
        val resp = DictionaryResponse(emptyList())
        val resolver = DefinitionResolver(
            FakeEngine(mapOf("test" to resp)), null, FakeTranslator(), "fr"
        )

        val result = resolver.lookup("test", null)

        // No entries → headword translator uses tapped word → MachineTranslated
        // (but with no senses to translate)
        assertTrue(result is DefinitionResult.MachineTranslated)
        val mt = result as DefinitionResult.MachineTranslated
        assertNull("No entry → no definitions to translate", mt.translatedDefinitions)
    }

    @Test fun `multi-sense definitions are translated individually`() = runBlocking {
        val resp = response(entry("test", senses = listOf(
            sense("to test", "to examine", pos = listOf("verb")),
            sense("a test", "an examination", pos = listOf("noun")),
        )))
        val defTranslator = FakeTranslator("FR:")
        val resolver = DefinitionResolver(
            FakeEngine(mapOf("test" to resp)), null, FakeTranslator("HW:"), "fr", defTranslator
        )

        val result = resolver.lookup("test", null)

        assertTrue(result is DefinitionResult.MachineTranslated)
        val mt = result as DefinitionResult.MachineTranslated
        assertEquals(2, mt.translatedDefinitions!!.size)
        // Definitions within a sense are joined with "; " before translation
        assertEquals("FR:to test; to examine", mt.translatedDefinitions!![0])
        assertEquals("FR:a test; an examination", mt.translatedDefinitions!![1])
    }

    @Test fun `tier priority - Native beats MachineTranslated`() = runBlocking {
        val resp = response(entry("食べる"))
        val glossDb = FakeTargetGlossDb(mapOf(
            "ja:食べる" to listOf(targetSense(0, "manger"))
        ))
        val resolver = DefinitionResolver(
            FakeEngine(mapOf("食べる" to resp)), glossDb, FakeTranslator(), "fr"
        )

        val result = resolver.lookup("食べる", null)

        assertTrue("Native should win over MachineTranslated", result is DefinitionResult.Native)
    }

    @Test fun `tier priority - MachineTranslated beats EnglishFallback`() = runBlocking {
        val resp = response(entry("食べる", senses = listOf(sense("to eat"))))
        val resolver = DefinitionResolver(
            FakeEngine(mapOf("食べる" to resp)), null, FakeTranslator(), "fr"
        )

        val result = resolver.lookup("食べる", null)

        assertTrue(
            "MachineTranslated should win over EnglishFallback",
            result is DefinitionResult.MachineTranslated
        )
    }

    @Test fun `response is always carried through regardless of tier`() = runBlocking {
        val resp = response(entry("食べる", freqScore = 3))
        val resolver = DefinitionResolver(
            FakeEngine(mapOf("食べる" to resp)), null, null, "fr"
        )

        val result = resolver.lookup("食べる", null)!!

        assertSame(resp, result.response)
        assertEquals(3, result.response.entries[0].freqScore)
    }
}
