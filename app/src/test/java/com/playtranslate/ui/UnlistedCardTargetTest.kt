package com.playtranslate.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import com.playtranslate.language.AnnotatedSpan
import com.playtranslate.language.PreloadResult
import com.playtranslate.language.ReadingPart
import com.playtranslate.language.SentenceAnnotation
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageEngine
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.language.TokenSpan
import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.DictionaryResponse
import com.playtranslate.model.Headword
import com.playtranslate.model.Sense
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.ConcurrentHashMap

/**
 * A card for a word no dictionary lists, as a lens row with no entry names
 * it ([lensRowCardTarget]): its [CardTarget.unlisted] target resolves to a
 * row of its own with no definition ([resolveCardTarget]), takes its place
 * among the sentence's rows ([withCardTarget]), and the sentence card made
 * from them marks the word, bold in both sentence fields and named in
 * TargetWord, with its definition empty. A listed target that finds no row
 * still names nothing, and a row with an entry names no target at all.
 *
 * Robolectric only for the application context the row pipeline takes; the
 * engine is a fake put in the engines' cache, and the target is English, so
 * no gloss pack or translator is touched.
 */
@RunWith(RobolectricTestRunner::class)
class UnlistedCardTargetTest {

    private companion object {
        val NEKO = DictionaryEntry(
            slug = "猫", isCommon = true, tags = emptyList(), jlpt = emptyList(),
            headwords = listOf(Headword("猫", "ねこ")),
            senses = listOf(Sense(listOf("cat"), listOf("noun"), emptyList(), emptyList(), emptyList())),
        )
    }

    /** The test's dictionary: 猫 and nothing else. 猫好き, which it does not
     *  list, splits into its member 猫; a lookup of 壊れる throws. */
    private class FakeEngine : SourceLanguageEngine {
        override val profile = SourceLanguageProfiles[SourceLangId.JA]
        override suspend fun preload(): PreloadResult = PreloadResult.Success
        override suspend fun tokenize(text: String): List<TokenSpan> = emptyList()
        override suspend fun lookup(word: String, reading: String?): DictionaryResponse? = when (word) {
            "猫" -> DictionaryResponse(listOf(NEKO))
            "壊れる" -> error("the dictionary failed")
            else -> null
        }
        override suspend fun memberWordsOf(
            headword: String,
            expressionClass: Boolean,
            headwordReading: String?,
        ): List<TokenSpan> = if (headword == "猫好き") listOf(TokenSpan("猫", "猫")) else emptyList()
        override fun close() {}
    }

    private val app: Context = ApplicationProvider.getApplicationContext()

    @Suppress("UNCHECKED_CAST")
    private val engines: ConcurrentHashMap<SourceLangId, SourceLanguageEngine>
        get() = ReflectionHelpers.getStaticField(SourceLanguageEngines::class.java, "cache")

    private fun sp() = app.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)

    @Before fun setUp() {
        sp().edit().clear().commit()
        Prefs(app).sourceLang = "ja"
        Prefs(app).setTarget("en")
        engines[SourceLangId.JA] = FakeEngine()
    }

    @After fun tearDown() {
        SourceLanguageEngines.release(SourceLangId.JA)
        sp().edit().clear().commit()
    }

    private fun resolve(word: String, target: CardTarget) =
        runBlocking { resolveCardTarget(app, SourceLangId.JA, word, target) }

    /** ググる found as ググった and read ぐぐる, as a lens row with no entry
     *  names it. */
    private val googled = lensRowCardTarget(null, "ググった", "ググる", "ぐぐる")!!

    // Mutations: the lens's target is listed; the unlisted miss names nothing;
    // the row drops the surface, or the reading; the row carries a definition.
    @Test
    fun `an unlisted word resolves to a row of its own, found as its text, read as looked up, with no definition`() {
        assertEquals(CardTarget("ググった", "ググる", "ぐぐる", unlisted = true), googled)
        assertEquals(
            ResolvedCardTarget(
                SentenceAnkiHtmlBuilder.WordEntry("ググる", "ぐぐる", "", surfaceForm = "ググった"),
                readingOverride = null,
            ),
            resolve("ググる", googled),
        )
    }

    // Mutation: the reading kept when it is the word itself.
    @Test
    fun `an unlisted word read as itself shows no reading, as a kana row shows none`() {
        val row = resolve("ググる", lensRowCardTarget(null, null, "ググる", "ググる")!!)!!.row
        assertEquals("", row.reading)
        assertEquals("found as its key when the lens had no surface", "ググる", row.surfaceForm)
    }

    // Mutation: a miss that split into member rows names nothing.
    @Test
    fun `an unlisted word whose lookup splits into member rows is still a row of its own`() {
        val row = resolve("猫好き", lensRowCardTarget(null, "猫好き", "猫好き", null)!!)!!.row
        assertEquals(SentenceAnkiHtmlBuilder.WordEntry("猫好き", "", "", surfaceForm = "猫好き"), row)
    }

    // Mutation: the fallback taken for every target, listed or not.
    @Test
    fun `a listed target that finds no row names nothing, as before`() {
        assertNull(resolve("ググる", CardTarget("ググった", "ググる", "ぐぐる")))
        assertNull(resolve("猫好き", CardTarget("猫好き", "猫好き", null)))
    }

    // Mutations: a failed lookup names nothing for an unlisted word; names
    // the word for a listed one.
    @Test
    fun `a lookup that fails still names an unlisted word, and a listed one not`() {
        assertEquals(
            SentenceAnkiHtmlBuilder.WordEntry("壊れる", "こわれる", "", surfaceForm = "壊れた"),
            resolve("壊れる", lensRowCardTarget(null, "壊れた", "壊れる", "こわれる")!!)?.row,
        )
        assertNull(resolve("壊れる", CardTarget("壊れた", "壊れる", "こわれる")))
    }

    // Mutation: an unlisted target skips the lookup.
    @Test
    fun `an unlisted word the dictionary lists after all takes its own row`() {
        val row = resolve("猫", lensRowCardTarget(null, "猫", "猫", "ねこ")!!)!!.row
        assertEquals("cat", row.meaning)
        assertEquals("ねこ", row.reading)
    }

    // Mutation: a row with an entry names a target.
    @Test
    fun `a lens row with an entry names no target`() {
        assertNull(lensRowCardTarget(NEKO, "猫", "猫", "ねこ"))
    }

    // Mutations: the row left out of the sentence's rows; selected but drawn
    // without its surface.
    @Test
    fun `the sentence card for an unlisted word marks it in both sentence fields and in TargetWord, its definition empty`() {
        val text = "ググった猫"
        val sentenceRows = listOf(SentenceAnkiHtmlBuilder.WordEntry("猫", "ねこ", "cat", surfaceForm = "猫"))
        // The sentence's own analysis: ググった a content span no lookup
        // resolved, 猫 a resolved one.
        val annotation = SentenceAnnotation(
            text, SourceLangId.JA, 0,
            listOf(
                AnnotatedSpan(0, 4, "ググった", lookupForm = "ググる", furigana = listOf(ReadingPart("ググった", null))),
                AnnotatedSpan(4, 5, "猫", lookupForm = "猫", word = "猫", reading = "ねこ", furigana = listOf(ReadingPart("猫", "ねこ"))),
            ),
        )

        val placed = withCardTarget(sentenceRows, resolve("ググる", googled), text)

        assertEquals("the word joins the rows, at the front", listOf("ググる", "猫"), placed.words.map { it.word })
        // Both cards select the card's word only when it is among the rows,
        // which it now is.
        val data = SentenceAnkiContentView.CardData(
            source = text, target = "The cat googled it", words = placed.words,
            selectedWords = setOf("ググる"), screenshot = null, sourceLangId = SourceLangId.JA,
        )
        val note = PtNoteBuilder.forSentence(
            data, annotation, imageFilename = null, audioFilename = null,
            wordAudioFilenames = emptyMap(), audioCredit = null,
        )
        assertEquals("<b>ググった</b>猫", note.sentence)
        assertTrue(note.sentenceFurigana, note.sentenceFurigana.startsWith("<b><span data-pt-w=\"ググる\">ググった</span></b>"))
        assertEquals("ググる", note.targetWord)
        assertTrue(note.wordsTable, note.wordsTable.contains("data-pt-w=\"ググる\" class=\"gl-w-target\""))

        val outputs = AnkiCardOutputBuilder.forSentence(data, annotation, imageFilename = null)
        assertEquals("ググる", outputs.expression)
        assertEquals("no definition to carry", "", outputs.definition)
    }

    @Test
    fun `a word card for a word with no entry has an empty Definition`() {
        val glossary = WordCardDefinition(fallback = "", entry = null)
            .glossary(null, emptyMap(), { it.joinToString() }, { null })
        assertEquals("", glossary.panelHtml(classStyler, "Definitions"))
    }
}
