package com.playtranslate.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.PosVocabulary
import com.playtranslate.model.PosVocabulary.PosCode
import com.playtranslate.model.Sense
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The part-of-speech line as a user reads it ([localizePos]), over the real
 * string resources: what a stored token becomes on screen and on a card.
 */
@RunWith(RobolectricTestRunner::class)
class PosLabelsTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun `a description the pack stored whole is shown as its short label`() {
        // 勉強's first sense, as the packVersion 4 ja pack stores it.
        assertEquals(
            "Noun · Suru verb · Transitive",
            ctx.localizePos(listOf("Noun", "noun or participle which takes the aux. verb suru", "transitive verb")),
        )
        assertEquals(
            "Godan verb · Intransitive",
            ctx.localizePos(listOf("Godan verb with 'ru' ending", "intransitive verb")),
        )
        assertEquals("Noun · No-adjective", ctx.localizePos(listOf("Noun", "nouns which may take the genitive case particle 'no'")))
        assertEquals("Adverb · To-adverb", ctx.localizePos(listOf("Adverb", "adverb taking the 'to' particle")))
    }

    @Test fun `a short form and its description read the same`() {
        assertEquals(ctx.localizePos("Suru verb"), ctx.localizePos("suru verb - included"))
        assertEquals("Ichidan verb", ctx.localizePos("Ichidan verb"))
        assertEquals("Na-adjective", ctx.localizePos("adjectival nouns or quasi-adjectives (keiyodoshi)"))
        assertEquals("I-adjective", ctx.localizePos("I-adjective (ii)"))
    }

    @Test fun `tokens that share a label show it once`() {
        // The two senses of the packVersion 4 ja pack this happens in.
        assertEquals("Suffix", ctx.localizePos(listOf("Noun suffix", "Suffix")))
        assertEquals(
            "Nidan verb · Yodan verb · Intransitive",
            ctx.localizePos(
                listOf(
                    "Nidan verb (upper class) with 'ru' ending (archaic)",
                    "Yodan verb with 'ru' ending (archaic)",
                    "Nidan verb (lower class) with 'ru' ending (archaic)",
                    "intransitive verb",
                ),
            ),
        )
    }

    @Test fun `a description with a comma of its own is one label from a stored field`() {
        // JMdict's text joined as a pack joins a sense's tokens. No pack
        // holds the two noun ones today: both builders shorten them.
        assertEquals(
            "Noun · Suffix · Prefix",
            ctx.localizePos(
                PosVocabulary.parse("noun (common) (futsuumeishi),noun, used as a suffix,noun, used as a prefix"),
            ),
        )
        assertEquals(
            "Expression · Ri verb",
            ctx.localizePos(
                PosVocabulary.parse("expressions (phrases, clauses, etc.),irregular ru verb, plain form ends with -ri"),
            ),
        )
    }

    @Test fun `a token with no code is shown as stored`() {
        assertEquals("Jitendex · n, v5r", ctx.localizePos("Jitendex · n, v5r"))
        assertEquals("Noun · name", ctx.localizePos(listOf("noun", "name")))
        assertEquals("", ctx.localizePos(emptyList()))
    }

    @Test fun `each Japanese class reads as its own label`() {
        // One stored form per code, with the English label it must read as.
        val expected = linkedMapOf(
            "transitive verb" to "Transitive",
            "intransitive verb" to "Intransitive",
            "copula" to "Copula",
            "Unclassified" to "Unclassified",
            "noun or participle which takes the aux. verb suru" to "Suru verb",
            "su verb - precursor to the modern suru" to "Su verb",
            "Kuru verb" to "Kuru verb",
            "Ichidan verb (zuru)" to "Zuru verb",
            "Ichidan verb" to "Ichidan verb",
            "Ichidan verb (kureru)" to "Ichidan verb (special)",
            "Godan verb with 'su' ending" to "Godan verb",
            "Godan verb (iku)" to "Godan verb (special)",
            "Godan verb with 'ru' ending (irregular verb)" to "Godan verb (irregular)",
            "Nidan verb with 'u' ending (archaic)" to "Nidan verb",
            "Yodan verb with 'ku' ending (archaic)" to "Yodan verb",
            "irregular nu verb" to "Nu verb",
            "irregular ru verb, plain form ends with -ri" to "Ri verb",
            "I-adjective" to "I-adjective",
            "Na-adjective" to "Na-adjective",
            "nouns which may take the genitive case particle 'no'" to "No-adjective",
            "'taru' adjective" to "Taru-adjective",
            "'ku' adjective (archaic)" to "Ku-adjective",
            "'shiku' adjective (archaic)" to "Shiku-adjective",
            "archaic/formal form of na-adjective" to "Nari-adjective",
            "Prenominal" to "Prenominal",
            "adverb taking the 'to' particle" to "To-adverb",
        )
        expected.forEach { (stored, label) -> assertEquals("label of: $stored", label, ctx.localizePos(stored)) }
        // Every code past the universal ones is in the table above.
        val japanese = PosCode.values().dropWhile { it != PosCode.TRANSITIVE }
        assertEquals(japanese.toSet(), expected.keys.map { PosVocabulary.canonical(it) }.toSet())
        assertEquals(japanese.size, expected.size)
    }

    @Test fun `a word card's part of speech is its first sense's line`() {
        fun sense(vararg pos: String) = Sense(
            targetDefinitions = listOf("def"), partsOfSpeech = pos.toList(),
            tags = emptyList(), restrictions = emptyList(), info = emptyList(),
        )
        fun entry(vararg senses: Sense) = DictionaryEntry(
            slug = "勉強", isCommon = true, tags = emptyList(), jlpt = emptyList(),
            headwords = emptyList(), senses = senses.toList(),
        )
        assertEquals(
            "Noun · Suru verb · Transitive",
            ctx.firstSensePosLabel(
                entry(
                    sense("Noun", "", "noun or participle which takes the aux. verb suru", "transitive verb"),
                    sense("Godan verb with 'ru' ending"),
                ),
            ),
        )
        assertEquals("", ctx.firstSensePosLabel(entry()))
        assertEquals("", ctx.firstSensePosLabel(entry(sense())))
    }
}
