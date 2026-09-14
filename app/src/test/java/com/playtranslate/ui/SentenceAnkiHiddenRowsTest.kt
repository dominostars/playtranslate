package com.playtranslate.ui

import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.audio.AudioSelection
import com.playtranslate.capture.GameAudioSnapshot
import com.playtranslate.language.SourceLangId
import com.playtranslate.vocab.HiddenWordsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowToast
import java.util.Locale

/**
 * Pins the hidden-word cells of the sentence card's Words group
 * ([SentenceAnkiContentView]): a hidden word renders as a stub (word only,
 * eye-off, band background, not counted in the header) unless it is the
 * TARGET, which always renders in full and is never un-hidden by merely
 * opening the card; the eye on a full row hides AND un-targets (redrawing
 * even when the store write is a no-op); un-targeting a store-hidden word
 * returns it to its stub; the eye-off on a stub shows the word again; the
 * card data still carries the hidden entry (the exclusion is the send
 * funnel's job, not the sheet's); a store change made elsewhere (the
 * results list on the other screen) redraws the rows through the revision
 * collector, no tap involved; a fresh list draws hidden words last (targets
 * exempt) while toggles never move a row; and a set that loads after the
 * card was built orders the list exactly once.
 *
 * Host shell copied from [SentenceAnkiSnapshotLifecycleTest].
 */
@RunWith(RobolectricTestRunner::class)
class SentenceAnkiHiddenRowsTest {

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
                },
            )
            content = c
            c.buildInto(view as LinearLayout, savedInstanceState)
        }

        override fun onDestroyView() {
            content?.release(deleteSnapshotFile = isFinalMediaTeardown())
            content = null
            super.onDestroyView()
        }
    }

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val hideCd by lazy { ctx.getString(R.string.hidden_word_hide_content_description) }
    private val showCd by lazy { ctx.getString(R.string.hidden_word_show_content_description) }

    private val words = listOf(
        SentenceAnkiHtmlBuilder.WordEntry("食べる", "たべる", "to eat", 3),
        SentenceAnkiHtmlBuilder.WordEntry("猫", "ねこ", "cat", 5),
    )

    @Before
    fun setUp(): Unit = runBlocking {
        HiddenWordsStore.resetForTest(ctx)
        clearPrefs()
    }

    /** Every activity a test opened, destroyed in [tearDown]. A leaked
     *  fragment's view scope keeps its revision collector alive into the
     *  next test, where its rebuild loads the store through a STALE
     *  application context whose file the earlier teardown deleted, and
     *  that empty set is cached for the language before the next test's
     *  own load runs (the late-load cell caught exactly this). */
    private val opened = mutableListOf<ActivityController<Host>>()

    @After
    fun tearDown(): Unit = runBlocking {
        opened.forEach { it.pause().stop().destroy() }
        opened.clear()
        shadowOf(Looper.getMainLooper()).idle()
        HiddenWordsStore.resetForTest(ctx)
        clearPrefs()
        GameAudioSnapshot.active = null
    }

    // ─── H1 ───────────────────────────────────────────────────────────

    @Test
    fun hiddenTargetRendersFull_openDoesNotUnhide_eyeStubsIt(): Unit = runBlocking {
        HiddenWordsStore.setHidden(ctx, SourceLangId.JA, "猫", "ねこ", hidden = true)
        Prefs(ctx).ankiWordAudioEnabled = true
        // Targeted on open (the lens path) AND hidden in the store: the
        // target wins. Full row, audio offered, counted, floated to the top —
        // and the store is NOT touched by opening (the send un-hides it).
        val fragment = open(targetWord = "猫")
        val row = rowOf(fragment, "猫")
        assertFalse(isStub(row))
        assertNotNull(textIn(row, "ねこ"))
        assertNotNull("audio sub-row offered for the target", audioRowTitle(fragment, "猫"))
        assertTrue("猫" in fragment.content!!.selectedWords)
        assertEquals(headerText(2), wordsHeader(fragment).text.toString())
        assertEquals(listOf("猫", "食べる"), rowOrder(fragment))
        assertTrue("opening never un-hides", "猫" in HiddenWordsStore.snapshot(ctx, SourceLangId.JA))

        // The eye on that row un-targets and stubs it. The store already has
        // it hidden, so that write is a no-op with no revision bump: the
        // redraw must come from the tap itself.
        eyeOf(row).performClick()
        settle()
        assertTrue(isStub(rowOf(fragment, "猫")))
        assertFalse("猫" in fragment.content!!.selectedWords)
        assertNull(audioRowTitle(fragment, "猫"))
        assertEquals(headerText(1), wordsHeader(fragment).text.toString())
        assertEquals(listOf("猫", "食べる"), rowOrder(fragment))
    }

    // ─── H10 ──────────────────────────────────────────────────────────

    @Test
    fun failedHideRestoresTargetAndAudioState(): Unit = runBlocking {
        Prefs(ctx).ankiWordAudioEnabled = true
        val fragment = open(targetWord = "猫")
        // Give the target a non-default audio state, so a mere re-seed from
        // the pref after the failure would be visibly different from a
        // restore: switch it off, then put the pref back to on.
        audioSwitch(fragment, "猫").performClick()
        assertFalse(audioSwitch(fragment, "猫").isChecked)
        Prefs(ctx).ankiWordAudioEnabled = true

        // Break the disk after the load: a directory at the store path.
        val file = HiddenWordsStore.fileFor(ctx)
        assertTrue(file.delete())
        assertTrue(file.mkdirs())
        try {
            eyeOf(rowOf(fragment, "猫")).performClick()
            settle()

            // Nothing changed: still targeted, full row, the audio row back
            // with its OFF state, the store untouched, and the user told.
            val row = rowOf(fragment, "猫")
            assertFalse(isStub(row))
            assertTrue("猫" in fragment.content!!.selectedWords)
            assertFalse(audioSwitch(fragment, "猫").isChecked)
            assertEquals(headerText(2), wordsHeader(fragment).text.toString())
            assertFalse("猫" in HiddenWordsStore.snapshot(ctx, SourceLangId.JA))
            assertEquals(
                ctx.getString(R.string.hidden_word_save_failed),
                ShadowToast.getTextOfLatestToast(),
            )
        } finally {
            file.delete()
        }
    }

    // ─── H9 ───────────────────────────────────────────────────────────

    @Test
    fun rowTapUntargetsStoreHiddenWord_backToStub(): Unit = runBlocking {
        HiddenWordsStore.setHidden(ctx, SourceLangId.JA, "猫", "ねこ", hidden = true)
        val fragment = open(targetWord = "猫")
        assertFalse(isStub(rowOf(fragment, "猫")))
        // The row body toggles target; un-targeting a store-hidden word
        // returns it to its stub (synchronous rebuild, no store write).
        val before = HiddenWordsStore.revision.value
        rowOf(fragment, "猫").performClick()
        assertTrue(isStub(rowOf(fragment, "猫")))
        assertFalse("猫" in fragment.content!!.selectedWords)
        assertEquals(before, HiddenWordsStore.revision.value)
    }

    // ─── H6 ───────────────────────────────────────────────────────────

    @Test
    fun freshListSortsHiddenLast_togglesKeepPosition(): Unit = runBlocking {
        // The FIRST word is hidden: a fresh list draws it after the visible one.
        HiddenWordsStore.setHidden(ctx, SourceLangId.JA, "食べる", "たべる", hidden = true)
        val fragment = open()
        assertEquals(listOf("猫", "食べる"), rowOrder(fragment))
        val stub = rowOf(fragment, "食べる")
        assertTrue(isStub(stub))
        assertNull(textIn(stub, "たべる"))
        assertNull(textIn(stub, "to eat"))
        // The band is the row's own background (the card colour would show
        // through a mere view alpha). Expected from the THEMED context: the
        // token resolves through the activity's theme, not the app's.
        assertEquals(
            hiddenWordBandColor(fragment.requireContext()),
            (stub.background as ColorDrawable).color,
        )

        // Hiding the (now first) visible word leaves it first.
        eyeOf(rowOf(fragment, "猫")).performClick()
        settle()
        assertEquals(listOf("猫", "食べる"), rowOrder(fragment))
        assertTrue(isStub(rowOf(fragment, "猫")))

        // Showing the second word leaves it second.
        eyeOf(rowOf(fragment, "食べる")).performClick()
        settle()
        assertEquals(listOf("猫", "食べる"), rowOrder(fragment))
        assertFalse(isStub(rowOf(fragment, "食べる")))
    }

    // ─── H7 ───────────────────────────────────────────────────────────

    @Test
    fun setLoadedAfterBuild_ordersOnce(): Unit = runBlocking {
        // Hidden on disk, but this "process" has not loaded ja yet when the
        // card opens: the rows draw unordered and unstubbed, then the load's
        // revision bump orders the list exactly once.
        HiddenWordsStore.setHidden(ctx, SourceLangId.JA, "食べる", "たべる", hidden = true)
        HiddenWordsStore.dropCacheForTest()
        val fragment = open()
        settle()
        assertEquals(listOf("猫", "食べる"), rowOrder(fragment))
        assertTrue(isStub(rowOf(fragment, "食べる")))
        // And a toggle after that still holds position.
        eyeOf(rowOf(fragment, "猫")).performClick()
        settle()
        assertEquals(listOf("猫", "食べる"), rowOrder(fragment))
    }

    // ─── H2 ───────────────────────────────────────────────────────────

    @Test
    fun eyeOnTargetedRow_hidesPersistedAndUntargets(): Unit = runBlocking {
        Prefs(ctx).ankiWordAudioEnabled = true
        val fragment = open(targetWord = "猫")
        assertNotNull("audio sub-row present while targeted", audioRowTitle(fragment, "猫"))
        assertEquals(headerText(2), wordsHeader(fragment).text.toString())

        eyeOf(rowOf(fragment, "猫")).performClick()
        settle()

        assertTrue("猫" in HiddenWordsStore.snapshot(ctx, SourceLangId.JA))
        assertFalse("猫" in fragment.content!!.selectedWords)
        val row = rowOf(fragment, "猫")
        assertTrue(isStub(row))
        assertNull(audioRowTitle(fragment, "猫"))
        assertEquals(headerText(1), wordsHeader(fragment).text.toString())
        // The other language is untouched.
        assertFalse("猫" in HiddenWordsStore.snapshot(ctx, SourceLangId.ZH))
    }

    // ─── H3 ───────────────────────────────────────────────────────────

    @Test
    fun eyeOffOnStub_showsAgain(): Unit = runBlocking {
        HiddenWordsStore.setHidden(ctx, SourceLangId.JA, "猫", "ねこ", hidden = true)
        val fragment = open()
        assertTrue(isStub(rowOf(fragment, "猫")))

        eyeOf(rowOf(fragment, "猫")).performClick()
        settle()

        assertFalse("猫" in HiddenWordsStore.snapshot(ctx, SourceLangId.JA))
        val row = rowOf(fragment, "猫")
        assertFalse(isStub(row))
        assertNotNull(textIn(row, "ねこ"))
        assertNotNull(textIn(row, "cat"))
        assertEquals(headerText(2), wordsHeader(fragment).text.toString())
    }

    // ─── H4 ───────────────────────────────────────────────────────────

    @Test
    fun cardDataStillCarriesHiddenEntry(): Unit = runBlocking {
        HiddenWordsStore.setHidden(ctx, SourceLangId.JA, "猫", "ねこ", hidden = true)
        val fragment = open()
        val data = fragment.content!!.getCardData()
        // The sheet hands over ALL its words; sendSentenceCard drops the
        // hidden ones (SentenceSendInputHiddenTest pins that rule).
        assertEquals(listOf("食べる", "猫"), data.words.map { it.word })
    }

    // ─── H5 ───────────────────────────────────────────────────────────

    @Test
    fun storeChangeWithoutTap_redrawsRows(): Unit = runBlocking {
        val fragment = open()
        assertFalse(isStub(rowOf(fragment, "猫")))

        // A hide from elsewhere (the results list on the other screen).
        HiddenWordsStore.setHidden(ctx, SourceLangId.JA, "猫", "ねこ", hidden = true)
        settle()
        assertTrue(isStub(rowOf(fragment, "猫")))
        assertEquals(headerText(1), wordsHeader(fragment).text.toString())

        HiddenWordsStore.setHidden(ctx, SourceLangId.JA, "猫", "ねこ", hidden = false)
        settle()
        assertFalse(isStub(rowOf(fragment, "猫")))
        assertEquals(headerText(2), wordsHeader(fragment).text.toString())
    }

    // ─── Harness ──────────────────────────────────────────────────────

    private fun open(targetWord: String? = null): HostFragment {
        val args = SentenceAnkiContentView.buildArgs(
            "猫が食べる。", "The cat eats.", words, screenshotPath = null,
            targetWord = targetWord, sourceLangId = SourceLangId.JA,
        )
        val controller = Robolectric.buildActivity(Host::class.java).setup()
        opened += controller
        val activity = controller.get()
        val fragment = HostFragment().apply { arguments = args }
        activity.supportFragmentManager.beginTransaction()
            .add(android.R.id.content, fragment, "hidden-rows-test")
            .commitNow()
        shadowOf(Looper.getMainLooper()).idle()
        return fragment
    }

    /** Drain the store hop and the main-thread resumptions behind a tap or
     *  a direct store write: the launch reaches the store dispatcher on the
     *  first idle, [HiddenWordsStore.loaded] orders behind the write, and
     *  the next idle runs the revision collector's rebuild. */
    private fun settle() {
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            runBlocking { HiddenWordsStore.loaded(ctx, SourceLangId.JA) }
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun View.descendants(): Sequence<View> = sequence {
        yield(this@descendants)
        if (this@descendants is ViewGroup) {
            for (i in 0 until childCount) yieldAll(getChildAt(i).descendants())
        }
    }

    /** The word row for [word]: the LinearLayout that directly holds an eye
     *  glyph and, somewhere beneath it, a TextView reading exactly [word]. */
    private fun rowOf(fragment: HostFragment, word: String): ViewGroup {
        val root = fragment.requireView()
        return root.descendants()
            .filterIsInstance<LinearLayout>()
            .filter { row -> (0 until row.childCount).any { isEye(row.getChildAt(it)) } }
            .first { row -> textIn(row, word) != null }
    }

    /** The word rows top to bottom, each named by its first TextView (the
     *  headword leads both the full row and the stub). */
    private fun rowOrder(fragment: HostFragment): List<String> =
        fragment.requireView().descendants()
            .filterIsInstance<LinearLayout>()
            .filter { row -> (0 until row.childCount).any { isEye(row.getChildAt(it)) } }
            .map { row -> row.descendants().filterIsInstance<TextView>().first().text.toString() }
            .toList()

    private fun isEye(v: View): Boolean =
        v is ImageView && (v.contentDescription == hideCd || v.contentDescription == showCd)

    private fun eyeOf(row: ViewGroup): ImageView =
        (0 until row.childCount).map { row.getChildAt(it) }.first { isEye(it) } as ImageView

    private fun isStub(row: ViewGroup): Boolean = eyeOf(row).contentDescription == showCd

    private fun textIn(root: View, text: String): TextView? =
        root.descendants().filterIsInstance<TextView>().firstOrNull { it.text?.toString() == text }

    /** The compact audio sub-row's title for [word], or null when there is
     *  no sub-row (its layout carries R.id.tvRowTitle; the word rows don't). */
    private fun audioRowTitle(fragment: HostFragment, word: String): TextView? =
        fragment.requireView().descendants()
            .filterIsInstance<TextView>()
            .firstOrNull { it.id == R.id.tvRowTitle && it.text?.toString() == word }

    /** The compact audio sub-row's switch for [word]: the nearest ancestor
     *  of the row's title that holds a switch is the row itself. */
    private fun audioSwitch(fragment: HostFragment, word: String): CompoundButton {
        val title = audioRowTitle(fragment, word) ?: error("no audio row for $word")
        val row = generateSequence(title.parent as? View) { it.parent as? View }
            .first { p -> p.descendants().any { it.id == R.id.switchRowToggle } }
        return row.descendants().filterIsInstance<CompoundButton>().first { it.id == R.id.switchRowToggle }
    }

    private fun headerText(count: Int): String =
        ctx.getString(R.string.anki_group_words_count, count).uppercase(Locale.ROOT)

    private fun wordsHeader(fragment: HostFragment): TextView =
        fragment.requireView().descendants().filterIsInstance<TextView>()
            .first { it.text?.toString() == headerText(1) || it.text?.toString() == headerText(2) }

    private fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }
}
