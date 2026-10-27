package com.playtranslate.ui

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.R
import com.playtranslate.language.ReadingOverride
import com.playtranslate.language.SourceLangId
import com.playtranslate.vocab.HiddenWordsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * The word editor's saved-state restore ([WordAnkiReviewBinder]): it resumes
 * the fills its content still owes, for the sentence the content shows. An
 * Original edit commits its text into that state, so a recreation before the
 * edit's refill landed refills the edited sentence: never the old sentence's
 * rows, or their reading override, beside the edit, and never a fill for the
 * launch sentence that the visible text would discard. A restore with
 * nothing owed keeps what it had.
 */
@RunWith(RobolectricTestRunner::class)
class WordAnkiReviewRestoreTest {

    class Host : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val opened = mutableListOf<ActivityController<Host>>()

    @Before
    fun setUp(): Unit = runBlocking {
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
    }

    @Test
    fun `an edit restored before its refill landed is refilled for the edited sentence`() {
        val controller = open()
        // A placement had landed: 弾く in place of 弾ける, with its reading.
        content(controller).applyWords(
            SENTENCE,
            listOf(
                SentenceAnkiHtmlBuilder.WordEntry("弾く", "ひく", "to play", 3, surfaceForm = "弾けた"),
                SentenceAnkiHtmlBuilder.WordEntry("一番", "いちばん", "most", 5),
            ),
            "弾く", ReadingOverride("弾けた", "弾く", "ひけた"),
        )
        settle()

        // Committed on focus loss; the uncached sentence's refill has not
        // landed when the sheet is recreated (rotation, dark mode).
        val etOriginal = view(controller).findViewById<EditText>(R.id.etAnkiOriginal)
        etOriginal.requestFocus()
        etOriginal.setText(EDITED)
        etOriginal.clearFocus()
        controller.recreate()
        settleUntil { !showsWordsLoading(controller) }

        // The edited sentence has no dictionary here, so its fill lands empty.
        val data = content(controller).getCardData()
        assertEquals(EDITED, data.source)
        assertEquals(emptyList<String>(), data.words.map { it.word })
        assertNull(data.readingOverride)
        assertFalse(showsWordsLoading(controller))
    }

    @Test
    fun `a restore with nothing owed keeps its rows and translation`() {
        val controller = open()
        controller.recreate()
        settle()

        val data = content(controller).getCardData()
        assertEquals(SENTENCE, data.source)
        assertEquals(TRANSLATION, data.target)
        assertEquals(setOf("一番", "弾ける"), data.words.map { it.word }.toSet())
        assertFalse(showsWordsLoading(controller))
    }

    private fun open(): ActivityController<Host> {
        val controller = Robolectric.buildActivity(Host::class.java).setup()
        opened += controller
        WordAnkiReviewSheet.newInstance(
            "弾く", "ひく", "Godan verb", "to play", null,
            sentenceOriginal = SENTENCE, sentenceTranslation = TRANSLATION,
            sentenceWordResults = mapOf(
                "一番" to Triple("いちばん", "most", 5),
                "弾ける" to Triple("はじける", "to burst", 0),
            ),
            sourceLangId = SourceLangId.JA,
        ).showNow(controller.get().supportFragmentManager, WordAnkiReviewSheet.TAG)
        settle()
        return controller
    }

    private fun sheet(controller: ActivityController<Host>): WordAnkiReviewSheet =
        controller.get().supportFragmentManager.findFragmentByTag(WordAnkiReviewSheet.TAG) as WordAnkiReviewSheet

    private fun view(controller: ActivityController<Host>): View = sheet(controller).requireDialog().window!!.decorView

    /** The sentence content, reached through the sheet's binder (private
     *  production state, read rather than widened). */
    private fun content(controller: ActivityController<Host>): SentenceAnkiContentView {
        val binder = WordAnkiReviewSheet::class.java.declaredFields
            .first { it.type == WordAnkiReviewBinder::class.java }
            .apply { isAccessible = true }.get(sheet(controller)) as WordAnkiReviewBinder
        return WordAnkiReviewBinder::class.java.getDeclaredField("contentView")
            .apply { isAccessible = true }.get(binder) as SentenceAnkiContentView
    }

    /** Whether the Words card still shows its "Looking up words…"
     *  placeholder: a fill is owed and has not landed. */
    private fun showsWordsLoading(controller: ActivityController<Host>): Boolean {
        val loading = ctx.getString(R.string.words_loading)
        fun View.has(): Boolean = (this is TextView && text.toString() == loading) ||
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

    /** [settle] until [done], for a fill whose lookup runs off the main
     *  thread; bounded, so a fill that never lands fails the assertion
     *  after it rather than hanging. */
    private fun settleUntil(done: () -> Boolean) {
        repeat(40) {
            settle()
            if (done()) return
            Thread.sleep(25)
        }
    }

    private companion object {
        const val SENTENCE = "一番うまく弾けた！"
        const val TRANSLATION = "I played it best!"
        const val EDITED = "ピアノを上手に弾けた！"
    }
}
