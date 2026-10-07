package com.playtranslate.ui

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.widget.EditText
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.R
import com.playtranslate.language.SourceLangId
import com.playtranslate.vocab.HiddenWordsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * The sentence-only review sheet's lazy words fill: handed no words for a
 * fresh, non-blank sentence (the launching result's lookups deferred or
 * still running), the sheet fills its rows from
 * [LastSentenceCache.awaitOrStartWordLookups]; handed words, it renders
 * exactly those and never consults the cache. An edited Original, once
 * committed, refills the words and translation for the new sentence. The
 * cache is seeded so the lookup is a HIT (no engine runs under Robolectric).
 */
@RunWith(RobolectricTestRunner::class)
class AnkiReviewSheetWordsFillTest {

    /** Hosts the sheet under the app theme (pt* attrs resolve). */
    class Host : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp(): Unit = runBlocking {
        HiddenWordsStore.resetForTest(ctx)
        LastSentenceCache.clear()
    }

    @After
    fun tearDown() {
        shadowOf(Looper.getMainLooper()).idle()
        LastSentenceCache.clear()
        runBlocking { HiddenWordsStore.resetForTest(ctx) }
    }

    @Test
    fun handedNoWords_fillsFromTheSentencesLookup() {
        seedCache("猫" to Triple("ねこ", "cat", 3))
        val controller = show(
            AnkiReviewBottomSheet.newInstance(
                SENTENCE, TRANSLATION, emptyMap(), emptyMap(), emptyMap(), null, SourceLangId.JA,
            ),
        )
        settle()

        val words = cardWords(controller)
        assertEquals(listOf("猫"), words.map { it.word })
        assertEquals("ねこ", words.single().reading)
        assertEquals("cat", words.single().meaning)
        controller.pause().stop().destroy()
    }

    @Test
    fun handedWords_renderAsGiven_andTheCacheIsNotConsulted() {
        // The cache holds a DIFFERENT word for the same sentence: a fill
        // would replace the handed rows with it.
        seedCache("犬" to Triple("いぬ", "dog", 3))
        val controller = show(
            AnkiReviewBottomSheet.newInstance(
                SENTENCE, TRANSLATION,
                mapOf("猫" to Triple("ねこ", "cat", 3)), emptyMap(), emptyMap(),
                null, SourceLangId.JA,
            ),
        )
        settle()

        assertEquals(listOf("猫"), cardWords(controller).map { it.word })
        controller.pause().stop().destroy()
    }

    @Test
    fun editedOriginal_refillsWordsAndTranslationForTheNewSentence() {
        val controller = show(
            AnkiReviewBottomSheet.newInstance(
                SENTENCE, TRANSLATION,
                mapOf("猫" to Triple("ねこ", "cat", 3)), emptyMap(), emptyMap(),
                null, SourceLangId.JA,
            ),
        )
        settle()
        // The edited sentence's lookups are cache HITS (words and
        // translation), so the refill lands without an engine.
        seedCache("犬" to Triple("いぬ", "dog", 3), sentence = EDITED, translation = EDITED_TRANSLATION)

        // The commit runs on focus loss, as when the user taps away.
        val etOriginal = sheet(controller).requireView()
            .findViewById<EditText>(R.id.etAnkiOriginal)
        etOriginal.requestFocus()
        etOriginal.setText(EDITED)
        etOriginal.clearFocus()
        settle()

        val data = content(controller).getCardData()
        assertEquals(EDITED, data.source)
        assertEquals(listOf("犬"), data.words.map { it.word })
        assertEquals(EDITED_TRANSLATION, data.target)
        controller.pause().stop().destroy()
    }

    @Test
    fun wordsFillOwed_onlyWhileLoadingWithNoWords() {
        val owed = SentenceAnkiContentView.buildArgs(
            SENTENCE, TRANSLATION, emptyList(), screenshotPath = null, wordsLoading = true,
        )
        assertEquals(true, SentenceAnkiContentView.wordsFillOwed(owed))
        val landed = SentenceAnkiContentView.buildArgs(
            SENTENCE, TRANSLATION, listOf(SentenceAnkiHtmlBuilder.WordEntry("猫", "ねこ", "cat", 3)),
            screenshotPath = null, wordsLoading = true,
        )
        assertEquals(false, SentenceAnkiContentView.wordsFillOwed(landed))
        val final = SentenceAnkiContentView.buildArgs(
            SENTENCE, TRANSLATION, emptyList(), screenshotPath = null, wordsLoading = false,
        )
        assertEquals(false, SentenceAnkiContentView.wordsFillOwed(final))
    }

    private fun seedCache(
        vararg words: Pair<String, Triple<String, String, Int>>,
        sentence: String = SENTENCE,
        translation: String = TRANSLATION,
    ) {
        LastSentenceCache.setFromTranslationResult(
            sentence, translation, null,
            wordResults = mapOf(*words),
            surfaceForms = emptyMap(),
            wordEnrichment = emptyMap(),
        )
    }

    private fun show(sheet: AnkiReviewBottomSheet): ActivityController<Host> {
        val controller = Robolectric.buildActivity(Host::class.java).setup()
        sheet.showNow(controller.get().supportFragmentManager, AnkiReviewBottomSheet.TAG)
        return controller
    }

    /** The words the card would send. */
    private fun cardWords(controller: ActivityController<Host>): List<SentenceAnkiHtmlBuilder.WordEntry> =
        content(controller).getCardData().words

    private fun sheet(controller: ActivityController<Host>): AnkiReviewBottomSheet =
        controller.get().supportFragmentManager
            .findFragmentByTag(AnkiReviewBottomSheet.TAG) as AnkiReviewBottomSheet

    /** The content view, whose public card data is what Save sends, reached
     *  through the sheet's private host field (deliberately private prod
     *  API; read rather than widened). */
    private fun content(controller: ActivityController<Host>): SentenceAnkiContentView =
        AnkiReviewBottomSheet::class.java
            .getDeclaredField("contentView")
            .apply { isAccessible = true }
            .get(sheet(controller)) as SentenceAnkiContentView

    /** Main-thread work plus the hidden-words load the word rows trigger,
     *  whose revision bump rebuilds them (as in the snapshot lifecycle test). */
    private fun settle() {
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            runBlocking { HiddenWordsStore.loaded(ctx, SourceLangId.JA) }
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private companion object {
        const val SENTENCE = "猫が好きです。"
        const val TRANSLATION = "I like cats."
        const val EDITED = "犬が好きです。"
        const val EDITED_TRANSLATION = "I like dogs."
    }
}
