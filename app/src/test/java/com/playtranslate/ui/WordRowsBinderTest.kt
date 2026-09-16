package com.playtranslate.ui

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.language.SourceLangId
import com.playtranslate.model.ImportedSense
import com.playtranslate.model.ImportedSenseGroup
import com.playtranslate.vocab.HiddenWordsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

/**
 * Pins [WordRowsBinder]'s hidden-words contract, the results page's rules
 * now shared with the workspace's Sentence page: a fresh list drawn against
 * a LOADED set orders visible words first and hidden words last, each in
 * lookup order, with the hidden ones as stubs; a store change after the
 * render re-stubs the cell in place (a toggle never moves a row); a list
 * drawn BEFORE the language's set loaded is provisional, and the load's
 * bump re-renders it ordered exactly once; a row tap reaches the host with
 * the row; and Loading / Idle clear the rows.
 */
@RunWith(RobolectricTestRunner::class)
class WordRowsBinderTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private val app: Context = ApplicationProvider.getApplicationContext()
    private val hideCd by lazy { app.getString(R.string.hidden_word_hide_content_description) }
    private val showCd by lazy { app.getString(R.string.hidden_word_show_content_description) }

    private lateinit var controller: ActivityController<Host>
    private lateinit var activity: Host
    private lateinit var root: View
    private lateinit var binder: WordRowsBinder
    private val tapped = mutableListOf<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val rows = listOf(
        row("猫", "ねこ", "cat"),
        row("食べる", "たべる", "to eat"),
        row("魚", "さかな", "fish"),
    )

    private fun row(word: String, reading: String, meaning: String) = RowState(
        displayWord = word, reading = reading, meaning = meaning, senses = emptyList(),
        freqScore = 3, isCommon = true, surface = word,
    )

    @Before
    fun setUp(): Unit = runBlocking {
        HiddenWordsStore.resetForTest(app)
        clearPrefs()
        controller = Robolectric.buildActivity(Host::class.java).setup()
        activity = controller.get()
        root = LayoutInflater.from(activity).inflate(R.layout.fragment_translation_result, null)
        binder = newBinder()
    }

    /** A binder over the inflated root; the styled tests re-point [binder]
     *  at one with their own cap. */
    private fun newBinder(styledRowCap: Int = STYLED_WORD_ROW_CAP) = WordRowsBinder(
        root, activity, Prefs(activity),
        object : WordRowsBinder.Host {
            override val isAlive: Boolean get() = true
            override val scope: CoroutineScope get() = this@WordRowsBinderTest.scope
            override val ttsAlertTarget: TtsAlertTarget get() = TtsAlertTarget.InActivity(activity)
            override fun onWordTapped(row: RowState) { tapped += row.displayWord }
        },
        styledRowCap = styledRowCap,
    )

    @After
    fun tearDown(): Unit = runBlocking {
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        HiddenWordsStore.resetForTest(app)
        clearPrefs()
    }

    private fun settled() = WordLookupsState.Settled(rows, emptyList(), emptyMap())

    private fun cells(): List<WordResultCell> =
        (0 until binder.container.childCount).map { binder.container.getChildAt(it) }
            .filterIsInstance<WordResultCell>()

    private fun order(): List<String> = cells().map { cell ->
        cell.descendants().filterIsInstance<TextView>().first().text.toString()
    }

    /** The eye lives on the cell's trailing action slot (a FrameLayout
     *  carrying the content description). */
    private fun isStub(cell: WordResultCell): Boolean =
        cell.descendants()
            .first { it.contentDescription == hideCd || it.contentDescription == showCd }
            .contentDescription == showCd

    private fun View.descendants(): Sequence<View> = sequence {
        yield(this@descendants)
        if (this@descendants is ViewGroup) {
            for (i in 0 until childCount) yieldAll(getChildAt(i).descendants())
        }
    }

    @Test
    fun `a fresh list against a loaded set draws hidden words last as stubs`(): Unit = runBlocking {
        HiddenWordsStore.setHidden(app, SourceLangId.JA, "猫", "ねこ", hidden = true)
        binder.render(settled())
        assertEquals(listOf("食べる", "魚", "猫"), order())
        assertTrue(isStub(cells()[2]))
        assertFalse(isStub(cells()[0]))
        assertFalse(root.findViewById<View>(R.id.tvNoWords).isShown && binder.isEmpty)
    }

    @Test
    fun `a store change after the render re-stubs in place without moving the row`(): Unit = runBlocking {
        HiddenWordsStore.loaded(app, SourceLangId.JA)
        binder.render(settled())
        assertEquals(listOf("猫", "食べる", "魚"), order())
        HiddenWordsStore.setHidden(app, SourceLangId.JA, "食べる", "たべる", hidden = true)
        binder.applyHiddenState()
        assertEquals("a toggle never moves a row", listOf("猫", "食べる", "魚"), order())
        assertTrue(isStub(cells()[1]))
        HiddenWordsStore.setHidden(app, SourceLangId.JA, "食べる", "たべる", hidden = false)
        binder.applyHiddenState()
        assertFalse(isStub(cells()[1]))
    }

    @Test
    fun `a list drawn before the set loaded is re-rendered ordered once the load lands`(): Unit = runBlocking {
        HiddenWordsStore.setHidden(app, SourceLangId.JA, "猫", "ねこ", hidden = true)
        HiddenWordsStore.dropCacheForTest()
        assertFalse(HiddenWordsStore.isLoaded(SourceLangId.JA))
        binder.render(settled())
        assertEquals("provisional: lookup order", listOf("猫", "食べる", "魚"), order())
        HiddenWordsStore.loaded(app, SourceLangId.JA)
        binder.applyHiddenState()
        assertEquals(listOf("食べる", "魚", "猫"), order())
        assertTrue(isStub(cells()[2]))
        // A later toggle re-stubs in place: the ordering ran exactly once.
        HiddenWordsStore.setHidden(app, SourceLangId.JA, "魚", "さかな", hidden = true)
        binder.applyHiddenState()
        assertEquals(listOf("食べる", "魚", "猫"), order())
    }

    private fun structuredRow(word: String, reading: String, meaning: String, rowid: Long) =
        row(word, reading, meaning).copy(
            importedGroups = listOf(
                ImportedSenseGroup("Jitendex", listOf(ImportedSense(meaning, scRowid = rowid)), dictId = "jt"),
            ),
        )

    private fun renderersUnder(word: String): List<YomitanDefinitionsView> = cells()
        .first { it.descendants().filterIsInstance<TextView>().first().text.toString() == word }
        .descendants().filterIsInstance<YomitanDefinitionsView>().toList()

    private fun allRenderers(): List<YomitanDefinitionsView> =
        cells().flatMap { it.descendants().filterIsInstance<YomitanDefinitionsView>().toList() }

    @Test
    fun `styled renderers are capped in list order, pooled across rebuilds, and destroyed by release`() {
        val payload = YomitanStyledData(
            mapOf(1L to "{}", 2L to "{}", 3L to "{}"), mapOf("jt" to ".x{}"), "ja",
        )
        // Imported, but nothing structured retained: the flat tier, never a WebView.
        val flat = row("魚", "さかな", "fish").copy(
            importedGroups = listOf(ImportedSenseGroup("JMdict", listOf(ImportedSense("fish")), dictId = "jm")),
        )
        val settled = WordLookupsState.Settled(
            listOf(
                structuredRow("猫", "ねこ", "cat", 1L), flat,
                structuredRow("犬", "いぬ", "dog", 2L), structuredRow("鳥", "とり", "bird", 3L),
            ),
            emptyList(), emptyMap(), styled = payload,
        )
        binder = newBinder(styledRowCap = 2)
        binder.render(settled)
        assertEquals(1, renderersUnder("猫").size)
        assertEquals(0, renderersUnder("魚").size)
        assertEquals(1, renderersUnder("犬").size)
        // Third structured row, past the cap: flat.
        assertEquals(0, renderersUnder("鳥").size)
        val first = allRenderers()
        assertEquals(2, first.size)

        // A rebuild (a capture in live mode) reuses the same two renderers:
        // no re-mint, and none on screen in between.
        binder.render(WordLookupsState.Loading)
        assertTrue(allRenderers().isEmpty())
        binder.render(settled)
        assertEquals(first.toSet(), allRenderers().toSet())

        // Teardown is terminal and idempotent: nothing on screen, and a
        // render after it binds every row flat.
        binder.release()
        assertTrue(allRenderers().isEmpty())
        binder.release()
        binder.render(settled)
        assertEquals(4, cells().size)
        assertTrue(allRenderers().isEmpty())
    }

    @Test
    fun `a cap of zero binds every row flat`() {
        val payload = YomitanStyledData(mapOf(1L to "{}"), mapOf("jt" to ".x{}"), "ja")
        binder = newBinder(styledRowCap = 0)
        binder.render(
            WordLookupsState.Settled(
                listOf(structuredRow("猫", "ねこ", "cat", 1L)), emptyList(), emptyMap(), styled = payload,
            ),
        )
        assertEquals(1, cells().size)
        assertTrue(allRenderers().isEmpty())
    }

    @Test
    fun `row taps reach the host and Loading clears the rows`() {
        binder.render(settled())
        cells()[1].performClick()
        assertEquals(listOf("食べる"), tapped)
        binder.render(WordLookupsState.Loading)
        assertTrue(binder.isEmpty)
        assertTrue(root.findViewById<View>(R.id.tvMainWordsLoading).visibility == View.VISIBLE)
        binder.render(WordLookupsState.Idle)
        assertTrue(binder.isEmpty)
    }

    private fun clearPrefs() {
        app.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }
}
