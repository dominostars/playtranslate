package com.playtranslate.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.R
import com.playtranslate.audio.AudioSelection
import com.playtranslate.language.ReadingOverride
import com.playtranslate.language.SourceLangId
import com.playtranslate.vocab.HiddenWordsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * The sentence content's [ReadingOverride]: it arrives with the rows it
 * describes ([SentenceAnkiContentView.applyWords]), rides the card data the
 * send reads, persists beside the rows in the content's args so a rebuilt
 * content (a saved-state restore) keeps it, is replaced by the next rows
 * applied, and leaves with the rows when the Original is edited, in memory
 * and in the args, which then hold the edited sentence owing its fills.
 *
 * Host shell copied from [SentenceAnkiHiddenRowsTest], with the re-fetch
 * callback every real host wires (an edit commits only when one is set).
 */
@RunWith(RobolectricTestRunner::class)
class SentenceAnkiReadingOverrideTest {

    class Host : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    class HostFragment : Fragment() {
        var content: SentenceAnkiContentView? = null

        override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
        ): View = inflater.inflate(R.layout.fragment_sentence_anki_content, container, false)

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            val c = SentenceAnkiContentView(
                requireContext(),
                viewLifecycleOwner.lifecycleScope,
                requireArguments(),
                object : SentenceAnkiContentView.Host {
                    override val isAlive: Boolean get() = isAdded
                    override fun openAudioPicker(
                        intent: Intent, onPicked: (AudioSelection) -> Unit,
                    ) = Unit
                    override fun openScreenshotCrop(state: AnkiScreenshotState) = Unit
                },
            )
            content = c
            c.buildInto(view as LinearLayout, savedInstanceState)
            c.onOriginalCommitted = {}
        }

        override fun onDestroyView() {
            content?.release(deleteSnapshotFile = isFinalMediaTeardown())
            content = null
            super.onDestroyView()
        }
    }

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val opened = mutableListOf<ActivityController<Host>>()

    private val ichiban = SentenceAnkiHtmlBuilder.WordEntry("一番", "いちばん", "most", 5)
    private val hiku = SentenceAnkiHtmlBuilder.WordEntry("弾く", "ひく", "to play", 3, surfaceForm = "弾けた")
    private val override = ReadingOverride("弾けた", "弾く", "ひけた")

    @Before
    fun setUp(): Unit = runBlocking { HiddenWordsStore.resetForTest(ctx) }

    @After
    fun tearDown(): Unit = runBlocking {
        opened.forEach { it.pause().stop().destroy() }
        opened.clear()
        shadowOf(Looper.getMainLooper()).idle()
        HiddenWordsStore.resetForTest(ctx)
    }

    @Test
    fun `the override arrives with its rows and survives a rebuild from the args`() {
        val fragment = open(fresh())
        fragment.content!!.applyWords(TEXT, listOf(hiku, ichiban), "弾く", override)
        settle()

        assertEquals(override, fragment.content!!.getCardData().readingOverride)
        assertEquals(override, fragment.requireArguments().getReadingOverride())

        // A saved-state restore rebuilds the content from the same args.
        val rebuilt = open(Bundle(fragment.requireArguments()))
        val data = rebuilt.content!!.getCardData()
        assertEquals(override, data.readingOverride)
        assertEquals(listOf("弾く", "一番"), data.words.map { it.word })
        assertTrue("弾く" in data.selectedWords)
    }

    @Test
    fun `rows applied without one replace it`() {
        val fragment = open(fresh())
        fragment.content!!.applyWords(TEXT, listOf(hiku, ichiban), "弾く", override)
        fragment.content!!.applyWords(TEXT, listOf(ichiban), null)
        settle()

        assertNull(fragment.content!!.getCardData().readingOverride)
        assertNull(fragment.requireArguments().getReadingOverride())
    }

    @Test
    fun `an Original edit takes it away with the rows`() {
        val fragment = open(fresh())
        fragment.content!!.applyWords(TEXT, listOf(hiku, ichiban), "弾く", override)
        settle()

        // The commit runs on focus loss, as when the user taps away.
        val etOriginal = fragment.requireView().findViewById<EditText>(R.id.etAnkiOriginal)
        etOriginal.requestFocus()
        etOriginal.setText("一番うまく書けた！")
        etOriginal.clearFocus()
        settle()

        val data = fragment.content!!.getCardData()
        assertNull(data.readingOverride)
        assertTrue(data.words.isEmpty())

        // The edit is the saved state too: a restore rebuilds the edited
        // sentence owing its fills, never the old rows and their reading.
        val saved = fragment.requireArguments()
        assertEquals("一番うまく書けた！", SentenceAnkiContentView.originalOf(saved))
        assertTrue(SentenceAnkiContentView.wordsFillOwed(saved))
        assertEquals("", SentenceAnkiContentView.translationOf(saved))
        assertNull(saved.getReadingOverride())
        val rebuilt = open(Bundle(saved)).content!!.getCardData()
        assertEquals("一番うまく書けた！", rebuilt.source)
        assertTrue(rebuilt.words.isEmpty())
        assertNull(rebuilt.readingOverride)
    }

    private fun fresh(): Bundle = SentenceAnkiContentView.buildArgs(
        TEXT, "I played it best!", emptyList(),
        targetWord = "弾く", sourceLangId = SourceLangId.JA, wordsLoading = true,
    )

    private fun open(args: Bundle): HostFragment {
        val controller = Robolectric.buildActivity(Host::class.java).setup()
        opened += controller
        val fragment = HostFragment().apply { arguments = args }
        controller.get().supportFragmentManager.beginTransaction()
            .add(android.R.id.content, fragment, "reading-override-test")
            .commitNow()
        shadowOf(Looper.getMainLooper()).idle()
        return fragment
    }

    private fun settle() {
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            runBlocking { HiddenWordsStore.loaded(ctx, SourceLangId.JA) }
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private companion object {
        const val TEXT = "一番うまく弾けた！"
    }
}
