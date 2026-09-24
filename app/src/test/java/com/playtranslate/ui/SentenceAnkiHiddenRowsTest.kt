package com.playtranslate.ui

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MotionEvent
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
import com.playtranslate.themeColor
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
 * Also pins the TARGET row's cell (T1-T4): its audio row, titled "Include
 * audio", sits inside the cell between the title line and the definition,
 * under the cell's highlight and spanning its width; a tap on the audio row
 * flips its switch, never the target; and the eye's widened hit square,
 * installed on the cell for a glyph that now sits in the title line, is
 * centred on the glyph's place in the cell and leaves what it overlaps of
 * the audio row to the row.
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
        assertNotNull("audio row offered for the target", audioRowTitle(fragment, "猫"))
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
        assertNotNull("audio row present while targeted", audioRowTitle(fragment, "猫"))
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

    // ─── T1 ───────────────────────────────────────────────────────────

    @Test
    fun targetRow_holdsItsAudioRowBetweenTitleAndDefinition_underTheHighlight(): Unit = runBlocking {
        Prefs(ctx).ankiWordAudioEnabled = true
        val fragment = open(targetWord = "猫")
        layOut(fragment)
        val cell = rowOf(fragment, "猫")
        val headword = textIn(cell, "猫") ?: error("no headword")
        val definition = textIn(cell, "cat") ?: error("no definition")
        val title = audioRowTitle(fragment, "猫") ?: error("no audio row")
        val audio = audioRow(fragment, "猫")

        // The audio row names its action; the headword right above it names
        // the word, so the row no longer repeats it.
        assertEquals(ctx.getString(R.string.anki_word_audio_row_title), title.text.toString())
        // One cell, top to bottom: the title line (headword and eye), the
        // audio row, the definition.
        assertEquals(0, childHolding(cell, headword))
        assertEquals(0, childHolding(cell, eyeOf(cell)))
        assertEquals(1, childHolding(cell, title))
        assertEquals(2, childHolding(cell, definition))
        // The highlight is the cell's own background, and the audio row lays
        // no fill over it (its background is the ripple, clear at rest).
        assertEquals(
            fragment.requireContext().themeColor(R.attr.ptAccentTint),
            (cell.background as ColorDrawable).color,
        )
        assertFalse(audio.background is ColorDrawable)
        // The audio row spans the cell, as the sentence's audio row spans
        // its card; the definition stays clear of the eye's column.
        val audioBounds = boundsIn(cell, audio)
        assertEquals(0, audioBounds.left)
        assertEquals(cell.width, audioBounds.right)
        assertTrue(boundsIn(cell, definition).right <= boundsIn(cell, eyeOf(cell)).left)

        // A word that isn't a target has no audio row.
        assertNull(audioRowTitle(fragment, "食べる"))
    }

    // ─── T2 ───────────────────────────────────────────────────────────

    @Test
    fun audioRowTap_flipsItsSwitch_notTheTarget(): Unit = runBlocking {
        Prefs(ctx).ankiWordAudioEnabled = true
        val fragment = open(targetWord = "猫")
        layOut(fragment)
        val cell = rowOf(fragment, "猫")
        assertTrue(audioSwitch(fragment, "猫").isChecked)

        // Through the cell's own touch pass: the audio row takes the tap, so
        // the cell's target toggle never sees it.
        val audio = boundsIn(cell, audioRow(fragment, "猫"))
        tap(cell, audio.centerX(), audio.centerY())

        assertFalse(audioSwitch(fragment, "猫").isChecked)
        assertFalse(Prefs(ctx).ankiWordAudioEnabled)
        assertTrue("猫" in fragment.content!!.selectedWords)
    }

    // ─── T3 ───────────────────────────────────────────────────────────

    @Test
    fun eyeHitSquare_isCentredOnTheGlyphWhereItSitsInTheCell(): Unit = runBlocking {
        val fragment = open()
        layOut(fragment)
        val cell = rowOf(fragment, "食べる")
        val eye = boundsIn(cell, eyeOf(cell))
        // The glyph sits in the title line, which starts at the cell's top
        // padding. Squared around the glyph's title-line-relative bounds (the
        // mapping a direct child needed), the rect would end that offset
        // higher; 4dp inside the bottom edge of the square around its place
        // in the CELL is outside that one whenever the offset beats 4dp.
        val titleTop = boundsIn(cell, cell.getChildAt(0)).top
        assertTrue(titleTop > dp(4))
        val y = eye.centerY() + dp(24) - dp(4)

        tap(cell, eye.centerX(), y)
        settle()

        assertTrue(isStub(rowOf(fragment, "食べる")))
        assertTrue("食べる" in HiddenWordsStore.snapshot(ctx, SourceLangId.JA))
    }

    // ─── T4 ───────────────────────────────────────────────────────────

    @Test
    fun eyeHitSquareOverTheAudioRow_staysTheAudioRows(): Unit = runBlocking {
        Prefs(ctx).ankiWordAudioEnabled = true
        val fragment = open(targetWord = "猫")
        // Robolectric's stand-in text metrics make the title line about 40dp
        // tall; a 16sp line is about 21dp on a device, which puts the audio
        // row's top inside the eye's square. Pin that height so the overlap
        // exists here too.
        val cell = rowOf(fragment, "猫")
        cell.getChildAt(0).apply {
            layoutParams.height = dp(21)
            requestLayout()
        }
        layOut(fragment)
        val eye = boundsIn(cell, eyeOf(cell))
        val audio = boundsIn(cell, audioRow(fragment, "猫"))
        // Inside the eye's square AND on the audio row, right under the eye.
        val y = audio.top + dp(4)
        assertTrue(y < eye.centerY() + dp(24))

        tap(cell, eye.centerX(), y)
        settle()

        // The audio row keeps what it covers: its switch flips, the word
        // stays shown and targeted.
        assertFalse(audioSwitch(fragment, "猫").isChecked)
        assertFalse(isStub(rowOf(fragment, "猫")))
        assertTrue("猫" in fragment.content!!.selectedWords)
        assertFalse("猫" in HiddenWordsStore.snapshot(ctx, SourceLangId.JA))
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

    /** The word rows top to bottom: each eye glyph's ancestor that sits
     *  directly in the Words group card. A shown word's row is a cell that
     *  holds the eye in its title line; a stub holds it directly. */
    private fun rows(fragment: HostFragment): List<ViewGroup> =
        fragment.requireView().descendants()
            .filter { isEye(it) }
            .map { eye ->
                generateSequence(eye) { it.parent as? View }
                    .first { it.parent is PtGroupCard } as ViewGroup
            }
            .toList()

    /** The word row for [word]: the one holding a TextView reading exactly
     *  [word] (its headword). */
    private fun rowOf(fragment: HostFragment, word: String): ViewGroup =
        rows(fragment).first { row -> textIn(row, word) != null }

    /** The word rows top to bottom, each named by its first TextView (the
     *  headword leads both the full row and the stub). */
    private fun rowOrder(fragment: HostFragment): List<String> =
        rows(fragment).map { row ->
            row.descendants().filterIsInstance<TextView>().first().text.toString()
        }

    private fun isEye(v: View): Boolean =
        v is ImageView && (v.contentDescription == hideCd || v.contentDescription == showCd)

    private fun eyeOf(row: ViewGroup): ImageView =
        row.descendants().first { isEye(it) } as ImageView

    private fun isStub(row: ViewGroup): Boolean = eyeOf(row).contentDescription == showCd

    private fun textIn(root: View, text: String): TextView? =
        root.descendants().filterIsInstance<TextView>().firstOrNull { it.text?.toString() == text }

    /** The audio row's title inside [word]'s row, or null when the row has
     *  no audio row (its layout carries R.id.tvRowTitle; the rest of a word
     *  row doesn't). */
    private fun audioRowTitle(fragment: HostFragment, word: String): TextView? =
        rowOf(fragment, word).descendants()
            .filterIsInstance<TextView>()
            .firstOrNull { it.id == R.id.tvRowTitle }

    /** The audio row itself: the nearest ancestor of its title that holds
     *  the switch. */
    private fun audioRow(fragment: HostFragment, word: String): ViewGroup {
        val title = audioRowTitle(fragment, word) ?: error("no audio row for $word")
        return generateSequence(title.parent as? View) { it.parent as? View }
            .first { p -> p.descendants().any { it.id == R.id.switchRowToggle } } as ViewGroup
    }

    private fun audioSwitch(fragment: HostFragment, word: String): CompoundButton =
        audioRow(fragment, word).descendants()
            .filterIsInstance<CompoundButton>()
            .first { it.id == R.id.switchRowToggle }

    /** The direct child of [cell] that holds [descendant]. */
    private fun childHolding(cell: ViewGroup, descendant: View): Int =
        (0 until cell.childCount).first { i ->
            cell.getChildAt(i).descendants().any { it === descendant }
        }

    /** Measures and lays the card out at [widthDp] so the rows have bounds
     *  and the eye's hit square is installed, independent of whether the
     *  test window ran its own layout pass. */
    private fun layOut(fragment: HostFragment, widthDp: Int = 400) {
        val root = fragment.requireView()
        val w = (widthDp * ctx.resources.displayMetrics.density).toInt()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        root.layout(0, 0, w, root.measuredHeight)
    }

    /** [view]'s bounds in [ancestor]'s coordinates. */
    private fun boundsIn(ancestor: ViewGroup, view: View): Rect =
        Rect(0, 0, view.width, view.height).also { ancestor.offsetDescendantRectToMyCoords(view, it) }

    /** A finger tap at ([x], [y]) in [target]'s coordinates, dispatched the
     *  way the parent's touch pass would hand it over, then the posted click
     *  run. */
    private fun tap(target: View, x: Int, y: Int) {
        val t = SystemClock.uptimeMillis()
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val ev = MotionEvent.obtain(t, t, action, x.toFloat(), y.toFloat(), 0)
            target.dispatchTouchEvent(ev)
            ev.recycle()
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

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
