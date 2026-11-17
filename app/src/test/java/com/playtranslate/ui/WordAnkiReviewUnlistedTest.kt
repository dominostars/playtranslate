package com.playtranslate.ui

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.language.PreloadResult
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageEngine
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.language.TokenSpan
import com.playtranslate.model.DictionaryResponse
import com.playtranslate.vocab.HiddenWordsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.ConcurrentHashMap

/**
 * The word editor ([WordAnkiReviewBinder]) opened for a word no dictionary
 * lists, as a lens row with no entry opens it: its Definitions card says
 * there are none once its own lookup has found nothing, where it said the
 * lookup was running for good; and its sentence tab marks the word through
 * the [CardTarget.unlisted] target the row hands it, the word among the
 * rows and selected, with no definition. A listed target that finds no row
 * still marks nothing.
 */
@RunWith(RobolectricTestRunner::class)
class WordAnkiReviewUnlistedTest {

    class Host : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    /** The test's dictionary lists nothing. */
    private class FakeEngine : SourceLanguageEngine {
        override val profile = SourceLanguageProfiles[SourceLangId.JA]
        override suspend fun preload(): PreloadResult = PreloadResult.Success
        override suspend fun tokenize(text: String): List<TokenSpan> = emptyList()
        override suspend fun lookup(word: String, reading: String?): DictionaryResponse? = null
        override fun close() {}
    }

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val opened = mutableListOf<ActivityController<Host>>()

    @Suppress("UNCHECKED_CAST")
    private val engines: ConcurrentHashMap<SourceLangId, SourceLanguageEngine>
        get() = ReflectionHelpers.getStaticField(SourceLanguageEngines::class.java, "cache")

    private fun sp() = ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)

    @Before
    fun setUp(): Unit = runBlocking {
        sp().edit().clear().commit()
        Prefs(ctx).sourceLang = "ja"
        Prefs(ctx).setTarget("en")
        engines[SourceLangId.JA] = FakeEngine()
        HiddenWordsStore.resetForTest(ctx)
        LastSentenceCache.clear()
    }

    @After
    fun tearDown() {
        opened.forEach { it.pause().stop().destroy() }
        opened.clear()
        shadowOf(Looper.getMainLooper()).idle()
        LastSentenceCache.clear()
        runBlocking { HiddenWordsStore.resetForTest(ctx) }
        SourceLanguageEngines.release(SourceLangId.JA)
        sp().edit().clear().commit()
    }

    // Mutation: the lookup that found nothing leaves the placeholder's text.
    @Test
    fun `a word no dictionary lists shows no definitions once its lookup has found nothing`() {
        val controller = open(sentence = null, target = null)

        settleUntil { shows(controller, ctx.getString(R.string.word_detail_no_definitions)) }

        assertTrue(shows(controller, ctx.getString(R.string.word_detail_no_definitions)))
        assertFalse(
            "the line of a running lookup is gone",
            shows(controller, ctx.getString(R.string.words_loading)),
        )
    }

    // Mutations: the binder resolves no target for an unlisted word; the
    // content selects a word only once it is among the rows.
    @Test
    fun `its sentence card has the word among the rows, selected, with no definition`() {
        val controller = open(sentence = SENTENCE, target = lensRowCardTarget(null, "ググった", "ググる", "ぐぐる"))

        settleUntil { content(controller).getCardData().words.any { it.word == "ググる" } }

        val data = content(controller).getCardData()
        assertEquals(listOf("ググる", "猫"), data.words.map { it.word })
        assertEquals(SentenceAnkiHtmlBuilder.WordEntry("ググる", "ぐぐる", "", surfaceForm = "ググった"), data.words.first())
        assertEquals(setOf("ググる"), data.selectedWords)
    }

    // Mutation: the binder falls back for a listed target too.
    @Test
    fun `a listed target that finds no row leaves the card with nothing marked`() {
        val controller = open(sentence = SENTENCE, target = CardTarget("ググった", "ググる", "ぐぐる"))
        settle()
        // The placement job is the last fill to land: wait for the Save
        // button's fill indicator, here the binder's own count, to clear.
        settleUntil { wordsFillCount(controller) == 0 }

        val data = content(controller).getCardData()
        assertEquals(listOf("猫"), data.words.map { it.word })
        assertEquals(emptySet<String>(), data.selectedWords)
    }

    private fun open(sentence: String?, target: CardTarget?): ActivityController<Host> {
        val controller = Robolectric.buildActivity(Host::class.java).setup()
        opened += controller
        // As the lens opens it for a row with no entry: no part of speech,
        // no definition.
        WordAnkiReviewSheet.newInstance(
            "ググる", "ぐぐる", "", "", null,
            sentenceOriginal = sentence,
            sentenceTranslation = sentence?.let { "The cat googled it." },
            sentenceWordResults = sentence?.let { mapOf("猫" to Triple("ねこ", "cat", 0)) },
            sourceLangId = SourceLangId.JA,
            target = target,
        ).showNow(controller.get().supportFragmentManager, WordAnkiReviewSheet.TAG)
        settle()
        return controller
    }

    private fun sheet(controller: ActivityController<Host>): WordAnkiReviewSheet =
        controller.get().supportFragmentManager.findFragmentByTag(WordAnkiReviewSheet.TAG) as WordAnkiReviewSheet

    private fun view(controller: ActivityController<Host>): View = sheet(controller).requireDialog().window!!.decorView

    private fun binder(controller: ActivityController<Host>): WordAnkiReviewBinder =
        WordAnkiReviewSheet::class.java.declaredFields
            .first { it.type == WordAnkiReviewBinder::class.java }
            .apply { isAccessible = true }.get(sheet(controller)) as WordAnkiReviewBinder

    /** The sentence content, reached through the sheet's binder (private
     *  production state, read rather than widened). */
    private fun content(controller: ActivityController<Host>): SentenceAnkiContentView =
        WordAnkiReviewBinder::class.java.getDeclaredField("contentView")
            .apply { isAccessible = true }.get(binder(controller)) as SentenceAnkiContentView

    private fun wordsFillCount(controller: ActivityController<Host>): Int =
        WordAnkiReviewBinder::class.java.getDeclaredField("wordsFillCount")
            .apply { isAccessible = true }.getInt(binder(controller))

    /** Whether any text in the editor, either tab, reads [text]. */
    private fun shows(controller: ActivityController<Host>, text: String): Boolean {
        fun View.has(): Boolean = (this is TextView && this.text.toString() == text) ||
            (this is ViewGroup && (0 until childCount).any { getChildAt(it).has() })
        return view(controller).has()
    }

    private fun settle() {
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            runBlocking { HiddenWordsStore.loaded(ctx, SourceLangId.JA) }
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** [settle] until [done], for work that runs off the main thread;
     *  bounded, so what never lands fails the assertion after it. */
    private fun settleUntil(done: () -> Boolean) {
        repeat(40) {
            settle()
            if (done()) return
            Thread.sleep(25)
        }
    }

    private companion object {
        const val SENTENCE = "ググった猫"
    }
}
