package com.playtranslate.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.ask.AskSentence
import com.playtranslate.ask.Explanation
import com.playtranslate.language.InflectedForm
import com.playtranslate.language.SentenceAnnotation
import com.playtranslate.language.SourceLangId
import com.playtranslate.model.ImportedSense
import com.playtranslate.model.ImportedSenseGroup
import com.playtranslate.themeColor
import com.playtranslate.translation.BackendFailure
import com.playtranslate.translation.BackendFailureKind
import com.playtranslate.translation.CooldownCause
import com.playtranslate.translation.CooldownLadder
import com.playtranslate.translation.FakeCompleterBackend
import com.playtranslate.translation.StructuralFailureException
import com.playtranslate.translation.TranslationBackendRegistry
import com.playtranslate.vocab.HiddenWordsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

/**
 * Pins [WordRowsBinder]'s hidden-words contract, the results page's rules
 * now shared with the workspace's Sentence page: a fresh list drawn against
 * a LOADED set orders visible words first and hidden words last, each in
 * lookup order, with the hidden ones as stubs; a store change after the
 * render re-stubs the row in place (a toggle never moves a row); a list
 * drawn BEFORE the language's set loaded is provisional, and the load's
 * bump re-renders it ordered exactly once; a row tap reaches the host with
 * the row; Loading / Idle clear the rows and keep the page for the lookup
 * that is running; and a lookup that finds no words lets the page go.
 *
 * And the card's own visibility: the effective hidden state is the pref OR
 * the live session's auto-hide; an Analyzed lookup asks for its rows only
 * while the card is shown; live start auto-hides without writing the pref
 * and is edge-triggered; the eye writes the opposite of what the card is
 * showing and spends the auto-hide; and the header's label recedes to the
 * hint colour while the card is hidden (by the eye or the auto-hide) and is
 * muted while it shows.
 *
 * And the wait for the page: rows are never shown as an empty card. The
 * loading line is up while a lookup runs and until the page has drawn the
 * rows, a lookup's or those of a card revealed with its rows looked up; a
 * reveal shows the card (and the line) at once and takes its page, or
 * starts one for a running lookup, on the main thread's next turn, which a
 * hide or a release before it calls off; and a page that cannot draw ends
 * the wait with its notice, as rows that cannot be built end it with none.
 *
 * And a row's dictionaries: a tap on a dictionary's title shuts that
 * dictionary in that row alone and a second opens it; a rebind of the same
 * words and a hide and reveal of the card keep it; a new lookup, and a row
 * that leaves the list, forget it; and among rows of one word and reading,
 * which their order alone tells apart, it never passes to another row.
 *
 * And what a controller reaches here: the header's eye first, then, while
 * the card shows, one action per tappable target the rows' page reported,
 * each a target inside the one document view; the eye alone while the card
 * is hidden. The page is played by its JVM stand-in
 * ([WordRowList.layoutForTest]), since no engine lays anything out here.
 *
 * And the page it asks for: the card's theme asks for two columns on a wide
 * page, and a word list built without asking (the dictionary search, a word
 * page's sections) and a card's theme by default do not. The columns
 * themselves are the shell's rule (DefinitionsDocumentTest), which no engine
 * lays out here either.
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

    private lateinit var controller: ActivityController<Host>
    private lateinit var activity: Host
    private lateinit var root: View
    private lateinit var binder: WordRowsBinder
    private val tapped = mutableListOf<String>()
    /** The answer each row tap in [tapped] handed the host. */
    private val tappedExplanations = mutableListOf<Explanation?>()
    /** [WordRowsBinder.onRowsWanted] invocations. */
    private var rowsWanted = 0
    /** [WordRowsBinder.Host.onAskNeedsLlm] invocations. */
    private var needsModel = 0
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
        // A new install starts with the card collapsed; these tests are about a shown card.
        Prefs(app).hideWordsSection = false
        controller = Robolectric.buildActivity(Host::class.java).setup()
        activity = controller.get()
        root = LayoutInflater.from(activity).inflate(R.layout.fragment_translation_result, null)
        binder = newBinder()
    }

    private fun newBinder() = WordRowsBinder(
        root, activity, Prefs(activity),
        object : WordRowsBinder.Host {
            override val isAlive: Boolean get() = true
            override val scope: CoroutineScope get() = this@WordRowsBinderTest.scope
            override val ttsAlertTarget: TtsAlertTarget get() = TtsAlertTarget.InActivity(activity)
            override fun onWordTapped(row: RowState, explanation: Explanation?) {
                tapped += row.displayWord
                tappedExplanations += explanation
            }
            override fun onAskNeedsLlm() { needsModel++ }
        },
    ).also { it.onRowsWanted = { rowsWanted++ } }

    @After
    fun tearDown(): Unit = runBlocking {
        TranslationBackendRegistry.removeOnlineBackend(llm.id)
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        HiddenWordsStore.resetForTest(app)
        clearPrefs()
    }

    private fun settled() = WordLookupsState.Settled(rows, emptyList(), emptyMap())

    /** The rows the card has published to its document, in drawn order. */
    private fun shown(): List<DefRow.Word> = binder.shownRows

    private fun order(): List<String> = shown().map { it.data.word }

    /** A hidden word's row: the stub, whose eye shows it again. */
    private fun isStub(row: DefRow.Word): Boolean = row.trailing == DefRow.Trailing.SHOW

    @Test
    fun `a fresh list against a loaded set draws hidden words last as stubs`(): Unit = runBlocking {
        HiddenWordsStore.setHidden(app, SourceLangId.JA, "猫", "ねこ", hidden = true)
        binder.render(settled())
        assertEquals(listOf("食べる", "魚", "猫"), order())
        assertTrue(isStub(shown()[2]))
        assertFalse(isStub(shown()[0]))
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
        assertTrue(isStub(shown()[1]))
        HiddenWordsStore.setHidden(app, SourceLangId.JA, "食べる", "たべる", hidden = false)
        binder.applyHiddenState()
        assertFalse(isStub(shown()[1]))
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
        assertTrue(isStub(shown()[2]))
        // A later toggle re-stubs in place: the ordering ran exactly once.
        HiddenWordsStore.setHidden(app, SourceLangId.JA, "魚", "さかな", hidden = true)
        binder.applyHiddenState()
        assertEquals(listOf("食べる", "魚", "猫"), order())
    }

    @Test
    fun `the rows are one document, kept across rebuilds and gone for good on release`() {
        binder.render(settled())
        assertEquals(3, shown().size)
        assertEquals("one view holds every row", 1, binder.container.childCount)
        val view = binder.container.getChildAt(0)
        assertTrue(view is DefinitionDocumentView)

        // A rebuild (a capture in live mode) empties the page and fills it
        // again in the same view: no new WebView per capture.
        binder.render(WordLookupsState.Loading)
        assertTrue(shown().isEmpty())
        binder.render(settled())
        assertEquals(3, shown().size)
        assertTrue(view === binder.container.getChildAt(0))

        // Teardown is terminal and idempotent: nothing on screen, and a
        // render after it draws nothing.
        binder.release()
        assertEquals(0, binder.container.childCount)
        binder.release()
        binder.render(settled())
        assertEquals(0, binder.container.childCount)
        assertTrue(shown().isEmpty())
    }

    @Test
    fun `a lookup that finds no words lets the page go, and the spare it had asked for`() {
        val none = WordLookupsState.Settled(emptyList(), emptyList(), emptyMap())

        // A lookup is running: the card asks for a page, which loads as the
        // spare.
        binder.prepareRows()
        binder.render(WordLookupsState.Loading)
        assertNotNull(DefinitionShells.spareForTest)
        // It finds nothing: the card's claim on a page ends, and with the
        // last claim the spare goes.
        binder.render(none)
        assertNull(DefinitionShells.spareForTest)
        assertEquals(0, binder.container.childCount)

        // A page that was drawing rows goes the same way...
        binder.render(settled())
        assertEquals(1, binder.container.childCount)
        binder.render(WordLookupsState.Loading)
        assertEquals("kept while the next lookup runs", 1, binder.container.childCount)
        binder.render(none)
        assertEquals(0, binder.container.childCount)
        // ...and the next words draw again.
        binder.render(settled())
        assertEquals(3, shown().size)
        assertEquals(1, binder.container.childCount)
    }

    @Test
    fun `a row carries its imported dictionaries, its eye and its speak button`() {
        val imported = listOf(
            ImportedSenseGroup("Jitendex", listOf(ImportedSense("cat", scRowid = 1L)), dictId = "jt"),
        )
        binder.render(
            WordLookupsState.Settled(
                listOf(row("猫", "ねこ", "cat").copy(importedGroups = imported)),
                emptyList(), emptyMap(), definitionLang = "ja",
            ),
        )
        val row = shown().single()
        assertEquals(imported, row.data.importedGroups)
        assertEquals(DefRow.Trailing.HIDE, row.trailing)
        assertEquals(DefRow.Speak.IDLE, row.speak)
        assertTrue(row.opens)
    }

    @Test
    fun `the eye writes the opposite of what its row was showing`(): Unit = runBlocking {
        HiddenWordsStore.loaded(app, SourceLangId.JA)
        binder.render(settled())
        binder.tapForTest(1, DefAction.TRAILING)
        awaitHidden("食べる", true)
        binder.applyHiddenState()
        assertTrue(isStub(shown()[1]))
        // The stub's eye shows the word again.
        binder.tapForTest(1, DefAction.TRAILING)
        awaitHidden("食べる", false)
    }

    /** The eye's store write runs off the main thread; wait for it to land. */
    private fun awaitHidden(word: String, hidden: Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if ((word in HiddenWordsStore.snapshot(app, SourceLangId.JA)) == hidden) return
            Thread.sleep(10)
        }
        throw AssertionError("$word never became hidden=$hidden")
    }

    @Test
    fun `row taps reach the host and Loading clears the rows`() {
        binder.render(settled())
        binder.tapForTest(1, DefAction.OPEN)
        assertEquals(listOf("食べる"), tapped)
        binder.render(WordLookupsState.Loading)
        assertTrue(binder.isEmpty)
        assertTrue(root.findViewById<View>(R.id.tvMainWordsLoading).visibility == View.VISIBLE)
        binder.render(WordLookupsState.Idle)
        assertTrue(binder.isEmpty)
    }

    private fun analyzed() = WordLookupsState.Analyzed(
        tokenSpans = emptyList(),
        annotation = SentenceAnnotation("猫", SourceLangId.JA, 0, emptyList()),
        phrases = emptyList(),
    )

    private val card: View get() = root.findViewById(R.id.cardWords)
    private val eye: View get() = root.findViewById(R.id.btnToggleWords)
    private val hidePref: Boolean get() = Prefs(app).hideWordsSection
    private val label: String get() = root.findViewById<android.widget.TextView>(R.id.labelWords).text.toString()

    @Test
    fun `the header counts the rows only while the card is showing them`() {
        val plain = activity.getString(R.string.section_words)
        assertEquals("nothing looked up yet", plain, label)
        binder.render(WordLookupsState.Loading)
        assertEquals("a lookup still running has no count", plain, label)
        binder.render(settled())
        assertEquals(activity.getString(R.string.section_words_count, 3), label)
        // Hidden: the rows are kept for the reveal, but none are on screen.
        eye.performClick()
        assertEquals(View.GONE, card.visibility)
        assertEquals(plain, label)
        eye.performClick()
        assertEquals(activity.getString(R.string.section_words_count, 3), label)
        // A lookup that finds no words has nothing to count.
        binder.render(WordLookupsState.Settled(emptyList(), emptyList(), emptyMap()))
        assertEquals(plain, label)
    }

    @Test
    fun `a hidden card's label recedes to the hint colour, and a shown one's is muted`() {
        val muted = activity.themeColor(R.attr.ptTextMuted)
        val hint = activity.themeColor(R.attr.ptTextHint)
        assertNotEquals("the two colours differ in this theme", muted, hint)
        val labelView = root.findViewById<android.widget.TextView>(R.id.labelWords)
        binder.applyWordsVisibility()
        assertEquals("shown", muted, labelView.currentTextColor)
        eye.performClick()
        assertEquals(View.GONE, card.visibility)
        assertEquals("hidden by the eye", hint, labelView.currentTextColor)
        eye.performClick()
        assertEquals(View.VISIBLE, card.visibility)
        assertEquals("shown again", muted, labelView.currentTextColor)
        // The live session's auto-hide hides the card too, so the label
        // recedes with it, and comes back when the session ends.
        binder.setLiveMode(true)
        assertEquals(View.GONE, card.visibility)
        assertEquals("auto-hidden by live mode", hint, labelView.currentTextColor)
        binder.setLiveMode(false)
        assertEquals(muted, labelView.currentTextColor)
    }

    @Test
    fun `a hidden card never asks for rows and holds no page, and a Settled landing while hidden is drawn on the reveal`() {
        Prefs(app).hideWordsSection = true
        binder.applyWordsVisibility()
        assertEquals(View.GONE, card.visibility)
        binder.render(analyzed())
        assertEquals(0, rowsWanted)
        assertTrue("Analyzed draws like Loading", binder.isEmpty)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.tvMainWordsLoading).visibility)
        // Rows requested earlier, card hidden before they settled: kept (the
        // lookup was paid), but a hidden card takes no page for them.
        binder.render(settled())
        assertFalse(binder.isEmpty)
        assertTrue(shown().isEmpty())
        assertEquals(0, binder.container.childCount)
        assertEquals(View.GONE, root.findViewById<View>(R.id.tvMainWordsLoading).visibility)
        // Outside live mode the eye reveals and persists the reveal, and the
        // kept rows are drawn.
        eye.performClick()
        assertEquals(View.VISIBLE, card.visibility)
        assertFalse(hidePref)
        assertEquals(1, rowsWanted)
        idle()
        assertEquals(3, shown().size)
    }

    @Test
    fun `a shown card asks for rows on the reveal and on an Analyzed render`() {
        binder.applyWordsVisibility()
        assertEquals(View.VISIBLE, card.visibility)
        assertEquals(1, rowsWanted)
        binder.render(analyzed())
        assertEquals(2, rowsWanted)
        assertTrue(binder.isEmpty)
        binder.render(WordLookupsState.Loading)
        assertEquals("only Analyzed asks", 2, rowsWanted)
        binder.setLiveMode(false)
        assertEquals("a stop with no live session on record is no edge", 2, rowsWanted)
    }

    @Test
    fun `live start auto-hides without the pref, the eye reveals for the session, a second tap hides for good`() {
        binder.applyWordsVisibility()
        rowsWanted = 0
        binder.setLiveMode(true)
        assertEquals(View.GONE, card.visibility)
        assertTrue(binder.isHidden)
        assertFalse("the auto-hide is never persisted", hidePref)
        assertEquals(0, rowsWanted)
        binder.render(analyzed())
        assertEquals("auto-hidden: no rows", 0, rowsWanted)
        binder.setLiveMode(true)
        assertEquals("a repeat start changes nothing", View.GONE, card.visibility)
        eye.performClick()
        assertEquals(View.VISIBLE, card.visibility)
        assertFalse(hidePref)
        assertEquals("the reveal asks for the rows", 1, rowsWanted)
        eye.performClick()
        assertEquals(View.GONE, card.visibility)
        assertTrue("a hide from a shown card persists", hidePref)
        binder.setLiveMode(false)
        assertEquals("the stop leaves the persisted hide", View.GONE, card.visibility)
        assertEquals(1, rowsWanted)
    }

    @Test
    fun `live mode is edge-triggered and a stop restores the pref's state`() {
        binder.applyWordsVisibility()
        binder.setLiveMode(true)
        eye.performClick()
        assertEquals(View.VISIBLE, card.visibility)
        binder.setLiveMode(true)
        assertEquals("a repeat start does not re-hide a revealed card", View.VISIBLE, card.visibility)
        binder.setLiveMode(false)
        assertEquals(View.VISIBLE, card.visibility)

        binder.setLiveMode(true)
        assertEquals(View.GONE, card.visibility)
        rowsWanted = 0
        binder.setLiveMode(false)
        assertEquals("stop drops the auto-hide: back to the shown pref", View.VISIBLE, card.visibility)
        assertEquals(1, rowsWanted)
        assertFalse(hidePref)
    }

    @Test
    fun `the eye on a pref-hidden card under live mode reveals it and clears the pref`() {
        Prefs(app).hideWordsSection = true
        binder.applyWordsVisibility()
        binder.setLiveMode(true)
        assertEquals(View.GONE, card.visibility)
        binder.render(analyzed())
        assertEquals(0, rowsWanted)
        // A stop with no tap: the pref still hides it.
        binder.setLiveMode(false)
        assertEquals(View.GONE, card.visibility)
        assertEquals(0, rowsWanted)

        binder.setLiveMode(true)
        eye.performClick()
        assertEquals(View.VISIBLE, card.visibility)
        assertFalse(hidePref)
        assertEquals(1, rowsWanted)
        binder.setLiveMode(false)
        assertEquals(View.VISIBLE, card.visibility)
    }

    // ── A row's dictionaries ────────────────────────────────────────────

    private val twoDictionaries = listOf(
        ImportedSenseGroup("Jitendex", listOf(ImportedSense("cat (jitendex)")), dictId = "jt"),
        ImportedSenseGroup("Other", listOf(ImportedSense("cat (other)")), dictId = "ot"),
    )

    private fun settledWithDictionaries(vararg words: String) = WordLookupsState.Settled(
        words.map { row(it, "", "meaning").copy(importedGroups = twoDictionaries) },
        emptyList(), emptyMap(), definitionLang = "ja",
    )

    private fun shutIn(word: String): Set<String> = shown().first { it.data.word == word }.shutDictionaries

    @Test
    fun `a tap on a dictionary's title shuts it in that row alone, and a second opens it`() {
        binder.render(settledWithDictionaries("猫", "犬"))
        assertTrue("every dictionary of a new row is open", shown().all { it.shutDictionaries.isEmpty() })
        binder.tapForTest(0, DefAction.dictionary(1))
        assertEquals(setOf("ot"), shutIn("猫"))
        assertEquals("the other row is untouched", emptySet<String>(), shutIn("犬"))
        binder.tapForTest(0, DefAction.dictionary(0))
        assertEquals(setOf("ot", "jt"), shutIn("猫"))
        binder.tapForTest(0, DefAction.dictionary(1))
        assertEquals("opened again", setOf("jt"), shutIn("猫"))
        // A title names a dictionary the row has: any other number does nothing.
        binder.tapForTest(0, DefAction.dictionary(7))
        assertEquals(setOf("jt"), shutIn("猫"))
        // The pack's own definitions are a dictionary of the row like the others.
        binder.tapForTest(0, DefAction.OWN_DICTIONARY)
        assertEquals(setOf("jt", OWN_DICTIONARY_KEY), shutIn("猫"))
        assertEquals(emptySet<String>(), shutIn("犬"))
        binder.tapForTest(0, DefAction.OWN_DICTIONARY)
        assertEquals(setOf("jt"), shutIn("猫"))
        assertTrue("a title is no tap on the row", tapped.isEmpty())
    }

    @Test
    fun `a shut dictionary stays shut while its row is listed, and a new lookup opens everything again`() {
        binder.render(settledWithDictionaries("猫", "犬"))
        binder.tapForTest(0, DefAction.dictionary(0))
        binder.tapForTest(1, DefAction.dictionary(1))
        // The same words again (a collector that restarts, a re-ordered list).
        binder.render(settledWithDictionaries("犬", "猫"))
        assertEquals(setOf("jt"), shutIn("猫"))
        assertEquals(setOf("ot"), shutIn("犬"))
        // The card hidden and shown: the rows are the same rows.
        eye.performClick()
        eye.performClick()
        idle()
        assertEquals(setOf("jt"), shutIn("猫"))
        // A row that leaves the list takes its own with it.
        binder.render(settledWithDictionaries("猫"))
        binder.render(settledWithDictionaries("猫", "犬"))
        assertEquals(setOf("jt"), shutIn("猫"))
        assertEquals("back in the list, as new", emptySet<String>(), shutIn("犬"))
        // The next sentence, the same word in it or not: all open.
        binder.render(WordLookupsState.Loading)
        binder.render(settledWithDictionaries("猫", "犬"))
        assertTrue(shown().all { it.shutDictionaries.isEmpty() })
    }

    /** A row of 猫 that [n] alone tells from its like: what two entries of
     *  one word and reading are to the list. */
    private fun twin(n: Int) = row("猫", "", "meaning").copy(freqScore = n, importedGroups = twoDictionaries)

    private fun settledRows(vararg rows: RowState) =
        WordLookupsState.Settled(rows.toList(), emptyList(), emptyMap(), definitionLang = "ja")

    private fun shutInTwin(n: Int): Set<String> = shown().first { it.data.freqScore == n }.shutDictionaries

    @Test
    fun `among rows of one word and reading a shut dictionary never passes to another row`() {
        // The first of two leaves, and its place in the order is the other's.
        binder.render(settledRows(twin(1), twin(2)))
        binder.tapForTest(0, DefAction.dictionary(0))
        assertEquals(setOf("jt"), shutInTwin(1))
        assertEquals(emptySet<String>(), shutInTwin(2))
        binder.render(settledRows(twin(2)))
        assertEquals("the row that stayed was never shut", emptySet<String>(), shutInTwin(2))

        // Another is listed before a row that stood alone (the lens's
        // later-loaded entry above the first).
        binder.render(WordLookupsState.Loading)
        binder.render(settledRows(twin(1)))
        binder.tapForTest(0, DefAction.dictionary(0))
        binder.render(settledRows(twin(2), twin(1)))
        assertEquals(emptySet<String>(), shutInTwin(2))

        // The two change places.
        binder.render(WordLookupsState.Loading)
        binder.render(settledRows(twin(1), twin(2)))
        binder.tapForTest(0, DefAction.dictionary(0))
        binder.render(settledRows(twin(2), twin(1)))
        assertEquals(emptySet<String>(), shutInTwin(2))
    }

    @Test
    fun `rows of one word and reading keep a shut dictionary while each is the row it was`() {
        val dog = row("犬", "", "meaning").copy(importedGroups = twoDictionaries)
        binder.render(settledRows(dog, twin(1), twin(2)))
        binder.tapForTest(1, DefAction.dictionary(0))
        binder.tapForTest(2, DefAction.dictionary(1))
        // The same rows in the same order (a collector that restarts).
        binder.render(settledRows(dog, twin(1), twin(2)))
        assertEquals(setOf("jt"), shutInTwin(1))
        assertEquals(setOf("ot"), shutInTwin(2))
        // Another word leaves from before them: among their like each
        // stands where it stood.
        binder.render(settledRows(twin(1), twin(2)))
        assertEquals(setOf("jt"), shutInTwin(1))
        assertEquals(setOf("ot"), shutInTwin(2))
        // The second leaves: the first is still the first.
        binder.render(settledRows(twin(1)))
        assertEquals(setOf("jt"), shutInTwin(1))
        // And alone, a row is its word's whatever changes in it.
        binder.render(settledRows(twin(7)))
        assertEquals(setOf("jt"), shutInTwin(7))
    }

    // ── The explain button ──────────────────────────────────────────────
    //
    // A row has the button when its rows came with the text they are the
    // words of ([WordLookupsState.Settled.sentence]); what it asks is put to
    // the registry's services (a fake here), as the Ask section's is.

    private val sentence = AskSentence("猫が魚を食べた", sourceLang = "ja", targetLang = "en")
    private val otherSentence = AskSentence("猫がいる", sourceLang = "ja", targetLang = "en")

    private fun settledIn(sentence: AskSentence?, vararg rows: RowState = this.rows.toTypedArray()) =
        WordLookupsState.Settled(rows.toList(), emptyList(), emptyMap(), definitionLang = "ja", sentence = sentence)

    /** What the model answers the question in flight, completed by the
     *  test; a question stays in flight until then. */
    private var reply = CompletableDeferred<String>()
    /** Requests that were cancelled while waiting for their reply. */
    private var cancelledRequests = 0
    private val llm = FakeCompleterBackend(
        id = "words-test-llm", priority = 0, displayName = "Test LLM",
        answer = { _, _ ->
            try {
                reply.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelledRequests++
                throw e
            }
        },
    )

    /** The user has a model: the fake, answering through [reply]. */
    private fun giveLlm() = TranslationBackendRegistry.addOnlineBackend(llm)

    private fun answer(text: String) {
        val r = reply
        reply = CompletableDeferred()
        r.complete(text)
        idle()
    }

    private fun explainOf(word: String): DefRow.Explain? = shown().first { it.data.word == word }.explain

    private fun tapOn(word: String, action: String) {
        binder.tapForTest(shown().indexOfFirst { it.data.word == word }, action)
        idle()
    }

    private val explainTitle: String get() = activity.getString(R.string.word_explain_title)

    private fun answered(text: String) = DefRow.Explain(
        answer = DefRow.Answer(explainTitle, text, footer = activity.getString(R.string.ask_answered_by, "Test LLM")),
    )

    @Test
    fun `rows that came with their sentence have the explain button, rows that came with none do not`() {
        binder.render(settled())
        assertTrue("no sentence, no button", shown().all { it.explain == null })
        binder.render(WordLookupsState.Loading)
        binder.render(settledIn(sentence))
        assertTrue("the button alone, nothing asked", shown().all { it.explain == DefRow.Explain() })
        binder.render(WordLookupsState.Loading)
        binder.render(settledIn(null))
        assertTrue(shown().all { it.explain == null })
    }

    @Test
    fun `with no model a tap on the button asks the surface for its alert and asks nothing`() {
        binder.render(settledIn(sentence))
        tapOn("猫", DefAction.EXPLAIN)
        assertEquals(1, needsModel)
        assertEquals(DefRow.Explain(), explainOf("猫"))
        assertTrue("a button is no tap on the row", tapped.isEmpty())
        // A model set up a moment later answers the next tap: asked at the tap.
        giveLlm()
        tapOn("猫", DefAction.EXPLAIN)
        assertEquals(1, needsModel)
        assertEquals(DefRow.Explain(busy = true), explainOf("猫"))
    }

    @Test
    fun `a tap asks about the row's word in the rows' sentence, spins, then shows the answer under its title`() {
        giveLlm()
        // Found first as 食べた, and in all as that and 食べない.
        val eat = row("食べる", "たべる", "to eat").copy(
            surface = "食べた",
            inflectedForms = listOf(InflectedForm("食べた", emptyList()), InflectedForm("食べない", emptyList())),
        )
        binder.render(settledIn(sentence, rows[0], eat))

        tapOn("食べる", DefAction.EXPLAIN)

        assertEquals("the spinner, and nothing else of the row", DefRow.Explain(busy = true), explainOf("食べる"))
        assertEquals("its neighbour is untouched", DefRow.Explain(), explainOf("猫"))
        val user = llm.lastPrompt!!.second
        assertTrue(
            "the word as its row heads it, with its reading and the forms it takes here, each once: $user",
            user.contains("\"食べる\" (read たべる; it appears here as \"食べた\", \"食べない\")"),
        )
        assertTrue("about the text the rows are the words of: $user", user.contains("猫が魚を食べた"))
        assertTrue("answered in the reader's language: $user", user.trimEnd().endsWith("Answer in English (English, en)."))

        answer("  It is the plain past of to eat. ")

        assertEquals(answered("It is the plain past of to eat."), explainOf("食べる"))
        assertEquals(DefRow.Explain(), explainOf("猫"))
        assertEquals(1, llm.completeCalls.get())
        assertTrue(tapped.isEmpty())

        // A language whose rows gather no inflected forms: the form is the
        // one the word was first found as.
        binder.render(WordLookupsState.Loading)
        binder.render(settledIn(sentence, row("run", "", "to run").copy(surface = "ran")))
        tapOn("run", DefAction.EXPLAIN)
        assertTrue(llm.lastPrompt!!.second, llm.lastPrompt!!.second.contains("\"run\" (it appears here as \"ran\")"))
    }

    @Test
    fun `rows drawn before the hidden words loaded keep their button and an answer through the re-order`(): Unit = runBlocking {
        giveLlm()
        HiddenWordsStore.setHidden(app, SourceLangId.JA, "猫", "ねこ", hidden = true)
        HiddenWordsStore.dropCacheForTest()
        binder.render(settledIn(sentence))
        assertEquals("provisional: lookup order", listOf("猫", "食べる", "魚"), order())
        tapOn("魚", DefAction.EXPLAIN)
        answer("the fish's")

        HiddenWordsStore.loaded(app, SourceLangId.JA)
        binder.applyHiddenState()

        assertEquals("re-rendered, the hidden word last", listOf("食べる", "魚", "猫"), order())
        assertEquals("under the same sentence: the answer stays", answered("the fish's"), explainOf("魚"))
        assertEquals("and every row still has its button", DefRow.Explain(), explainOf("食べる"))
    }

    @Test
    fun `the button takes back what it brought, an answer or a question still out, and so does the X`() {
        giveLlm()
        binder.render(settledIn(sentence))
        // An answer, by its X.
        tapOn("猫", DefAction.EXPLAIN)
        answer("one")
        tapOn("猫", DefAction.EXPLAIN_CLOSE)
        assertEquals(DefRow.Explain(), explainOf("猫"))
        // An answer, by the button that brought it.
        tapOn("猫", DefAction.EXPLAIN)
        answer("two")
        assertEquals(answered("two"), explainOf("猫"))
        tapOn("猫", DefAction.EXPLAIN)
        assertEquals(DefRow.Explain(), explainOf("猫"))
        // A question still out: stopped, and its late answer is nobody's.
        tapOn("猫", DefAction.EXPLAIN)
        assertEquals(DefRow.Explain(busy = true), explainOf("猫"))
        tapOn("猫", DefAction.EXPLAIN)
        assertEquals(DefRow.Explain(), explainOf("猫"))
        answer("too late")
        assertEquals(DefRow.Explain(), explainOf("猫"))
        assertEquals("three questions were put", 3, llm.completeCalls.get())
    }

    @Test
    fun `a question that is out is stopped when its row is put away, when the rows go, and when the card is released`() {
        giveLlm()
        binder.render(settledIn(sentence))
        tapOn("猫", DefAction.EXPLAIN)
        tapOn("猫", DefAction.EXPLAIN)
        assertEquals("the button took it back", 1, cancelledRequests)

        tapOn("猫", DefAction.EXPLAIN)
        binder.render(WordLookupsState.Loading)
        assertEquals("the next lookup has no use for it", 2, cancelledRequests)

        binder.render(settledIn(sentence))
        tapOn("猫", DefAction.EXPLAIN)
        binder.release()
        assertEquals("a released card asks nothing more", 3, cancelledRequests)
    }

    // ── A row's answer, for an Anki card's note ─────────────────────────

    // Mutations: hand a tap its neighbour's answer; count a question still
    // out, a failure or a closed answer as an answer.
    @Test
    fun `a row tap hands the host the answer standing in the row, and nothing in any other state`() {
        giveLlm()
        binder.render(settledIn(sentence))
        fun tapRow(word: String): Explanation? {
            tapOn(word, DefAction.OPEN)
            assertEquals(word, tapped.last())
            return tappedExplanations.last()
        }

        assertNull("nothing asked", tapRow("猫"))
        tapOn("猫", DefAction.EXPLAIN)
        assertNull("a question still out", tapRow("猫"))
        answer("  the cat's ")
        // The whole answer, its text trimmed and who gave it, as the word's
        // page draws it again.
        assertEquals(Explanation("the cat's", "Test LLM"), tapRow("猫"))
        assertNull("its neighbour's row has none", tapRow("魚"))

        tapOn("魚", DefAction.EXPLAIN)
        val r = reply
        reply = CompletableDeferred()
        r.completeExceptionally(java.io.IOException("no route"))
        idle()
        assertNull("a failure is no answer", tapRow("魚"))

        tapOn("猫", DefAction.EXPLAIN_CLOSE)
        assertNull("an answer put away", tapRow("猫"))
    }

    // Mutations: label a lone answer; drop the labels of several; order by
    // when each was asked; keep an answer of the sentence before; name a
    // word by the form it has in the text.
    @Test
    fun `the sentence card's note is one answer as it is and several each under its word, in the rows' order`() {
        giveLlm()
        // 食べる stands in the text as 食べた: its row is headed by the word.
        val eat = row("食べる", "たべる", "to eat").copy(surface = "食べた")
        binder.render(settledIn(sentence, rows[0], eat, rows[2]))
        assertEquals("", sentenceCardNote(null, binder.explainedWords()))

        tapOn("魚", DefAction.EXPLAIN)
        assertEquals("a question still out is no note", "", sentenceCardNote(null, binder.explainedWords()))
        answer("the fish's")
        assertEquals("the fish's", sentenceCardNote(null, binder.explainedWords()))

        tapOn("食べる", DefAction.EXPLAIN)
        answer("the verb's")
        assertEquals(
            "the rows' order, not the order asked in, each under the word its row is headed by",
            "食べる\nthe verb's\n\n魚\nthe fish's",
            sentenceCardNote(null, binder.explainedWords()),
        )

        tapOn("魚", DefAction.EXPLAIN_CLOSE)
        assertEquals("the verb's", sentenceCardNote(null, binder.explainedWords()))

        binder.render(settledIn(otherSentence))
        assertEquals("another sentence has none of these", "", sentenceCardNote(null, binder.explainedWords()))
    }

    @Test
    fun `two rows are asked side by side and each shows its own answer`() {
        giveLlm()
        binder.render(settledIn(sentence))
        tapOn("猫", DefAction.EXPLAIN)
        val first = reply
        reply = CompletableDeferred()
        tapOn("魚", DefAction.EXPLAIN)
        assertEquals(DefRow.Explain(busy = true), explainOf("猫"))
        assertEquals(DefRow.Explain(busy = true), explainOf("魚"))

        answer("the fish's")
        assertEquals(answered("the fish's"), explainOf("魚"))
        assertEquals("the first is still out", DefRow.Explain(busy = true), explainOf("猫"))
        first.complete("the cat's")
        idle()
        assertEquals(answered("the cat's"), explainOf("猫"))
        assertEquals(answered("the fish's"), explainOf("魚"))
    }

    @Test
    fun `a failure is shown with Try again, which asks again`() {
        giveLlm()
        binder.render(settledIn(sentence))
        tapOn("猫", DefAction.EXPLAIN)
        val r = reply
        reply = CompletableDeferred()
        r.completeExceptionally(java.io.IOException("no route"))
        idle()

        val failed = explainOf("猫")!!
        assertFalse(failed.busy)
        assertEquals(explainTitle, failed.answer!!.title)
        assertTrue("the app's word, muted: ${failed.answer}", failed.answer!!.muted)
        assertTrue(failed.answer!!.text.startsWith(activity.getString(R.string.ask_failed)))
        assertTrue("Try again is offered", failed.answer!!.retry)

        tapOn("猫", DefAction.EXPLAIN_RETRY)
        assertEquals("the failure folds away while it asks", DefRow.Explain(busy = true), explainOf("猫"))
        answer("this time")
        assertEquals(answered("this time"), explainOf("猫"))
    }

    /** A row's Try again over the registry's own pass, as the list asks in
     *  production: the model fails as Gemini does on a 503 (its cooldown
     *  recorded, then the throw), and Try again still reaches it. */
    @Test
    fun `Try again after a failure that put the model in a cooldown asks it again`() {
        giveLlm()
        binder.render(settledIn(sentence))
        tapOn("猫", DefAction.EXPLAIN)
        llm.cooldownState.recordLadderFailure(CooldownLadder.RateLimit, "Server error", CooldownCause.SERVER_ERROR)
        val r = reply
        reply = CompletableDeferred()
        r.completeExceptionally(StructuralFailureException("error 503", BackendFailure(BackendFailureKind.SERVER_ERROR, 503)))
        idle()
        assertTrue("precondition: failed, with Try again", explainOf("猫")!!.answer!!.retry)
        assertNotNull("precondition: the model is in a cooldown", llm.unavailableUntil())

        tapOn("猫", DefAction.EXPLAIN_RETRY)

        assertEquals("asked again", DefRow.Explain(busy = true), explainOf("猫"))
        answer("this time")
        assertEquals(answered("this time"), explainOf("猫"))
        assertNull("the answer ended the cooldown", llm.unavailableUntil())
    }

    @Test
    fun `an answer and a question in flight stay with their rows while the same rows are listed`() {
        giveLlm()
        binder.render(settledIn(sentence))
        tapOn("猫", DefAction.EXPLAIN)
        answer("the cat's")
        tapOn("魚", DefAction.EXPLAIN)
        // The same rows again, in another order (a collector that restarts,
        // the hidden words' re-order): nothing was asked again.
        binder.render(settledIn(sentence, rows[2], rows[1], rows[0]))
        assertEquals(answered("the cat's"), explainOf("猫"))
        assertEquals("still out, not cancelled", DefRow.Explain(busy = true), explainOf("魚"))
        // The card hidden and shown: the rows are the same rows.
        eye.performClick()
        eye.performClick()
        idle()
        assertEquals(answered("the cat's"), explainOf("猫"))
        // The answer that lands meanwhile is shown with the rest.
        answer("the fish's")
        assertEquals(answered("the fish's"), explainOf("魚"))
        assertEquals("two questions, each put once", 2, llm.completeCalls.get())
    }

    @Test
    fun `an answer that lands while the card is hidden is there when it is shown`() {
        giveLlm()
        binder.render(settledIn(sentence))
        tapOn("猫", DefAction.EXPLAIN)
        eye.performClick()
        idle()
        answer("landed unseen")
        eye.performClick()
        idle()
        assertEquals(answered("landed unseen"), explainOf("猫"))
    }

    @Test
    fun `the same word in another sentence starts with nothing asked, with or without a Loading between`() {
        giveLlm()
        binder.render(settledIn(sentence))
        tapOn("猫", DefAction.EXPLAIN)
        answer("in the first sentence")
        tapOn("魚", DefAction.EXPLAIN)

        // No Loading between: a collector that restarts onto the next
        // sentence's rows hands them over directly.
        binder.render(settledIn(otherSentence))
        assertEquals("an answer about one sentence is none about another", DefRow.Explain(), explainOf("猫"))
        assertEquals("and a question about it is no longer wanted", DefRow.Explain(), explainOf("魚"))
        answer("too late, about the first sentence")
        assertEquals(DefRow.Explain(), explainOf("魚"))

        // With one, the usual way.
        tapOn("猫", DefAction.EXPLAIN)
        answer("in the second")
        binder.render(WordLookupsState.Loading)
        binder.render(settledIn(sentence))
        assertEquals(DefRow.Explain(), explainOf("猫"))
    }

    @Test
    fun `a row that leaves the list takes its answer with it, and one whose word changes form asks anew`() {
        giveLlm()
        binder.render(settledIn(sentence))
        tapOn("猫", DefAction.EXPLAIN)
        answer("the cat's")
        binder.render(settledIn(sentence, rows[1]))
        binder.render(settledIn(sentence))
        assertEquals("back in the list, as new", DefRow.Explain(), explainOf("猫"))

        // The same word and reading, found as another form: another question.
        tapOn("食べる", DefAction.EXPLAIN)
        answer("as itself")
        assertEquals(answered("as itself"), explainOf("食べる"))
        binder.render(settledIn(sentence, rows[0], rows[1].copy(surface = "食べた"), rows[2]))
        assertEquals("what it was asked about is not what the row is now", DefRow.Explain(), explainOf("食べる"))
    }

    @Test
    fun `among rows of one word and reading an answer never passes to another row`() {
        giveLlm()
        // The first of two leaves, and its place in the order is the other's.
        binder.render(settledIn(sentence, twin(1), twin(2)))
        binder.tapForTest(0, DefAction.EXPLAIN)
        answer("the first twin's")
        assertEquals(answered("the first twin's"), shown().first { it.data.freqScore == 1 }.explain)
        binder.render(settledIn(sentence, twin(2)))
        assertEquals("the row that stayed was never asked", DefRow.Explain(), shown().single().explain)
    }

    @Test
    fun `a hidden word's stub keeps the button's place, and its answer is there when the word is shown again`(): Unit = runBlocking {
        giveLlm()
        HiddenWordsStore.loaded(app, SourceLangId.JA)
        binder.render(settledIn(sentence))
        tapOn("猫", DefAction.EXPLAIN)
        answer("the cat's")
        HiddenWordsStore.setHidden(app, SourceLangId.JA, "猫", "ねこ", hidden = true)
        binder.applyHiddenState()
        assertTrue(isStub(shown().first { it.data.word == "猫" }))
        assertEquals("the stub is handed it, and draws its place alone", answered("the cat's"), explainOf("猫"))
        HiddenWordsStore.setHidden(app, SourceLangId.JA, "猫", "ねこ", hidden = false)
        binder.applyHiddenState()
        assertEquals(answered("the cat's"), explainOf("猫"))
    }

    // ── The wait for the page ───────────────────────────────────────────

    private val loading: View get() = root.findViewById(R.id.tvMainWordsLoading)

    /** The main thread's next turn: where a reveal takes its page. */
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** The render process of the card's page dies, as the platform tells it. */
    private fun killRenderer() {
        val web = (binder.container.getChildAt(0) as DefinitionDocumentView).pageView as WebView
        shadowOf(web).webViewClient.onRenderProcessGone(web, null)
        idle()
    }

    @Test
    fun `a lookup's loading line stays up until the page has drawn its rows`() {
        binder.applyWordsVisibility()
        binder.render(WordLookupsState.Loading)
        assertEquals(View.VISIBLE, loading.visibility)
        binder.render(settled())
        assertEquals("up over rows the page has not drawn", View.VISIBLE, loading.visibility)
        // The same state again (a collector that restarts) lets nothing go.
        binder.render(settled())
        assertEquals(View.VISIBLE, loading.visibility)
        list.layoutForTest(50)
        assertEquals("the rows are drawn", View.GONE, loading.visibility)

        // The next sentence: the page is emptied for its lookup, and the
        // line is up over its rows the same way.
        binder.render(analyzed())
        assertEquals(View.VISIBLE, loading.visibility)
        binder.render(settled())
        assertEquals(View.VISIBLE, loading.visibility)
        list.layoutForTest(50)
        assertEquals(View.GONE, loading.visibility)
    }

    @Test
    fun `rows taken with no lookup before them show the loading line until they are drawn`() {
        // A page built over a lookup that had already settled.
        binder.applyWordsVisibility()
        binder.render(settled())
        assertEquals(View.VISIBLE, loading.visibility)
        list.layoutForTest(50)
        assertEquals(View.GONE, loading.visibility)
        // A new row set over rows on screen is no wait: they stay up.
        binder.render(WordLookupsState.Settled(rows.reversed(), emptyList(), emptyMap()))
        assertEquals(View.GONE, loading.visibility)
    }

    @Test
    fun `a reveal draws the card and its loading line first and takes the page on the next turn`() {
        binder.applyWordsVisibility()
        binder.render(settled())
        list.layoutForTest(50)
        eye.performClick()
        assertEquals(View.GONE, card.visibility)
        assertEquals("the page went with the hide", 0, binder.container.childCount)

        eye.performClick()
        assertEquals(View.VISIBLE, card.visibility)
        assertEquals("the line shows where the rows will be", View.VISIBLE, loading.visibility)
        assertEquals("no page inside the tap", 0, binder.container.childCount)
        assertNull(DefinitionShells.spareForTest)
        assertTrue(shown().isEmpty())
        idle()
        assertEquals("the page, a turn later", 1, binder.container.childCount)
        assertEquals(3, shown().size)
        assertEquals("until its rows are drawn", View.VISIBLE, loading.visibility)
        list.layoutForTest(50)
        assertEquals(View.GONE, loading.visibility)
    }

    @Test
    fun `rows that settled on a hidden card are revealed under the loading line`() {
        Prefs(app).hideWordsSection = true
        binder.applyWordsVisibility()
        binder.render(analyzed())
        binder.render(settled())
        assertEquals("a hidden card waits for no page", View.GONE, loading.visibility)
        eye.performClick()
        assertEquals(View.VISIBLE, card.visibility)
        assertEquals(View.VISIBLE, loading.visibility)
        idle()
        assertEquals(View.VISIBLE, loading.visibility)
        list.layoutForTest(50)
        assertEquals(View.GONE, loading.visibility)
    }

    @Test
    fun `the reveal of a card whose lookup is running starts the page a turn later, and one with no lookup takes none`() {
        Prefs(app).hideWordsSection = true
        binder.applyWordsVisibility()
        // Nothing looked up: a reveal has no rows on their way, so no line
        // for even a turn, and no page.
        eye.performClick()
        assertEquals(View.GONE, loading.visibility)
        idle()
        assertNull(DefinitionShells.spareForTest)
        assertEquals(View.GONE, loading.visibility)
        eye.performClick()

        binder.render(analyzed())
        assertNull("a hidden card asks for no page", DefinitionShells.spareForTest)
        rowsWanted = 0
        eye.performClick()
        assertEquals("the lookup's line, at once", View.VISIBLE, loading.visibility)
        assertEquals("and the lookup itself", 1, rowsWanted)
        assertNull("no page inside the tap", DefinitionShells.spareForTest)
        idle()
        assertNotNull("the page loads beside the lookup", DefinitionShells.spareForTest)
        // Its rows land: the line lasts until they are drawn.
        binder.render(settled())
        assertEquals(View.VISIBLE, loading.visibility)
        list.layoutForTest(50)
        assertEquals(View.GONE, loading.visibility)
    }

    @Test
    fun `rows that land between a reveal and its page are drawn by that page`() {
        Prefs(app).hideWordsSection = true
        binder.applyWordsVisibility()
        binder.render(analyzed())
        eye.performClick()
        binder.render(settled())
        assertEquals(View.VISIBLE, loading.visibility)
        assertTrue("the list is not shown yet", shown().isEmpty())
        idle()
        assertEquals(3, shown().size)
        assertEquals(View.VISIBLE, loading.visibility)
        list.layoutForTest(50)
        assertEquals(View.GONE, loading.visibility)
    }

    @Test
    fun `a reveal undone before its page starts takes no page, and neither does a released card`() {
        binder.applyWordsVisibility()
        binder.render(settled())
        eye.performClick()
        eye.performClick()
        assertEquals(View.VISIBLE, loading.visibility)
        eye.performClick()
        assertEquals(View.GONE, card.visibility)
        idle()
        assertEquals(0, binder.container.childCount)
        assertNull(DefinitionShells.spareForTest)
        assertTrue(shown().isEmpty())

        // Revealed for good: one page, however many times the state is applied.
        eye.performClick()
        binder.applyWordsVisibility()
        idle()
        assertEquals(1, binder.container.childCount)

        // Released with a reveal's page still to start: nothing runs after.
        eye.performClick()
        eye.performClick()
        var heightChanges = 0
        binder.onHeightChanged = { heightChanges++ }
        binder.release()
        idle()
        assertEquals(0, binder.container.childCount)
        assertTrue(shown().isEmpty())
        assertEquals("the host hears nothing from a released card", 0, heightChanges)
    }

    @Test
    fun `a page that cannot draw ends the wait`() {
        binder.applyWordsVisibility()
        binder.render(settled())
        assertEquals(View.VISIBLE, loading.visibility)
        killRenderer()
        assertFalse("recovered once", list.showsNotice)
        assertEquals("still waiting, on the new page", View.VISIBLE, loading.visibility)
        killRenderer()
        assertTrue(list.showsNotice)
        assertEquals("no layout will come: the notice stands alone", View.GONE, loading.visibility)
    }

    @Test
    fun `rows that cannot be built end the wait with no rows`() {
        // The rows' markup is built off the main thread and reads the
        // context's resources: a context that fails there fails the build
        // and nothing the card itself reads.
        var failing = false
        val ctx = object : ContextWrapper(activity) {
            override fun getResources(): Resources {
                if (failing && Looper.myLooper() != Looper.getMainLooper()) throw IllegalStateException("no resources")
                return super.getResources()
            }
        }
        // A page of its own: a header takes its actions from one binder.
        val page = LayoutInflater.from(activity).inflate(R.layout.fragment_translation_result, null)
        val line = page.findViewById<View>(R.id.tvMainWordsLoading)
        val failingBinder = WordRowsBinder(
            page, ctx, Prefs(activity),
            object : WordRowsBinder.Host {
                override val isAlive: Boolean get() = true
                override val scope: CoroutineScope get() = this@WordRowsBinderTest.scope
                override val ttsAlertTarget: TtsAlertTarget get() = TtsAlertTarget.InActivity(activity)
                override fun onWordTapped(row: RowState, explanation: Explanation?) {}
                override fun onAskNeedsLlm() {}
            },
        )
        try {
            failingBinder.applyWordsVisibility()
            failing = true
            failingBinder.render(WordLookupsState.Loading)
            failingBinder.render(settled())
            assertEquals("waiting for the rows' layout", View.VISIBLE, line.visibility)
            for (i in 0 until 300) {
                idle()
                if (line.visibility == View.GONE) break
                Thread.sleep(10)
            }
            assertEquals("no layout is coming: the line goes", View.GONE, line.visibility)
            assertEquals("the rows are still the card's", 3, failingBinder.shownRows.size)

            // The next rows are a new wait, from the moment they are set.
            failing = false
            failingBinder.render(WordLookupsState.Loading)
            failingBinder.render(settled())
            assertEquals(View.VISIBLE, line.visibility)
        } finally {
            failing = false
            failingBinder.release()
        }
    }

    @Test
    fun `a lookup that finds no words waits for no page`() {
        binder.applyWordsVisibility()
        binder.render(WordLookupsState.Loading)
        binder.render(WordLookupsState.Settled(emptyList(), emptyList(), emptyMap()))
        assertEquals(View.GONE, loading.visibility)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.tvNoWords).visibility)
    }

    // ── The controller's targets ────────────────────────────────────────

    /** The binder's list, whose page the test plays. */
    private val list: WordRowList<*>
        get() = WordRowsBinder::class.java.getDeclaredField("list").apply { isAccessible = true }
            .get(binder) as WordRowList<*>

    /** The page on screen, as it is once a result is up, laid out at the
     *  display's size: a header offers only the actions it shows, and it
     *  decides which show while it measures. */
    private fun showPage() {
        root.findViewById<View>(R.id.statusContainer).visibility = View.GONE
        root.findViewById<View>(R.id.resultsContent).visibility = View.VISIBLE
        if (root.parent == null) activity.setContentView(root)
        val dm = activity.resources.displayMetrics
        root.measure(
            View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, dm.widthPixels, dm.heightPixels)
    }

    @Test
    fun `the controller reaches the eye, then every tappable target of the rows while the card shows, and the eye alone while it is hidden`() {
        binder.applyWordsVisibility()
        binder.render(settled())
        list.layoutForTest(50)
        showPage()
        val tappable = list.targets.filter { it.act.isNotEmpty() }
        assertEquals(
            "precondition: each row reports its tap, its speak button and its eye",
            rows.flatMap { listOf(DefAction.OPEN, DefAction.SPEAK, DefAction.TRAILING) },
            tappable.map { it.act },
        )

        val actions = binder.navigableActions()
        assertEquals("the eye first", eye, actions.first().view)
        assertNull("the eye is a view of its own", actions.first().target)
        val rowActions = actions.drop(1)
        assertTrue("every row action is a target", rowActions.all { it.target != null })
        assertEquals(
            "one per tappable target, in the page's order",
            tappable.map { it.rowId to it.act },
            rowActions.map { (it.target as DocNavTarget).let { t -> t.rowId to t.act } },
        )
        assertTrue("all inside the one document view", rowActions.all { it.view === list.view })

        eye.performClick()
        showPage()
        assertEquals("precondition: the card is hidden", View.GONE, card.visibility)
        assertEquals("the eye alone", listOf(eye), binder.navigableActions().map { it.view })
    }

    @Test
    fun `the explain button is a target ahead of Speak, and an answer adds its X after the head's buttons`() {
        giveLlm()
        binder.applyWordsVisibility()
        binder.render(settledIn(sentence))
        list.layoutForTest(50)
        fun acts(word: String) = list.targets.filter { it.rowId.startsWith(word) && it.act.isNotEmpty() }.map { it.act }
        assertEquals(listOf(DefAction.OPEN, DefAction.EXPLAIN, DefAction.SPEAK, DefAction.TRAILING), acts("猫"))

        tapOn("猫", DefAction.EXPLAIN)
        answer("the cat's")
        list.layoutForTest(50)
        assertEquals(
            listOf(DefAction.OPEN, DefAction.EXPLAIN, DefAction.SPEAK, DefAction.TRAILING, DefAction.EXPLAIN_CLOSE),
            acts("猫"),
        )
        assertEquals("its neighbour has none", listOf(DefAction.OPEN, DefAction.EXPLAIN, DefAction.SPEAK, DefAction.TRAILING), acts("魚"))
        showPage()
        val targets = binder.navigableActions().mapNotNull { it.target as? DocNavTarget }
        val close = targets.single { it.act == DefAction.EXPLAIN_CLOSE }
        assertEquals(
            "the sheet's cursor reaches the X, and follows it to its row's explain button when it goes",
            targets.single { it.rowId == close.rowId && it.act == DefAction.EXPLAIN },
            close.successor(),
        )
        assertTrue(
            "no other target names one",
            targets.filter { it.act != DefAction.EXPLAIN_CLOSE }.all { it.successor() == null },
        )
    }

    // ── The page it asks for ────────────────────────────────────────────

    /** The script a list's page in [slot] has run once its shell finished
     *  loading. Called right after the list took its shell, that is the
     *  theme, the one script queued then: the rows are built off the main
     *  thread and sent only once the main looper runs again. */
    private fun firstScriptRunIn(slot: ViewGroup): String {
        val web = (slot.getChildAt(0) as DefinitionDocumentView).pageView as WebView
        shadowOf(web).webViewClient.onPageFinished(web, "")
        return shadowOf(web).lastEvaluatedJavascript.orEmpty()
    }

    @Test
    fun `the card's page asks for two columns when wide, and a word list or a card theme that does not ask has one`() {
        binder.render(settled())
        val theme = firstScriptRunIn(binder.container)
        assertTrue("precondition: the theme is what ran: ${theme.take(40)}", theme.startsWith("ptTheme("))
        assertTrue(theme, theme.contains("cols:true"))

        // The dictionary search and a word page's sections build their lists
        // without asking.
        val slot = FrameLayout(activity)
        val other = wordRowList(
            activity, slot, scope,
            object : WordRowList.Host<RowState> {
                override val isAlive: Boolean get() = true
                override val deckBadges: Boolean get() = false
                override fun trailing(row: RowState): DefRow.Trailing = DefRow.Trailing.ANKI
                override fun onOpen(row: RowState) {}
                override fun onTrailing(row: RowState, shown: DefRow.Trailing) {}
                override suspend fun speak(row: RowState) {}
            },
            WordRowList.LARGE_SCALE,
        )
        other.setRows(rows, "ja")
        val otherTheme = firstScriptRunIn(slot)
        other.release()
        assertTrue("precondition: the theme is what ran: ${otherTheme.take(40)}", otherTheme.startsWith("ptTheme("))
        assertTrue(otherTheme, otherTheme.contains("cols:false"))
        // Nor does a card's theme, by default.
        assertFalse(cardDocTheme(activity, WordRowList.GLOSS_SP).twoColumnsWhenWide)
    }

    @Test
    fun `the card's page sits on the theme's card colour, or on the colour its host says the card is seen as`() {
        binder.render(settled())
        val card = DefinitionsDocument.cssHex(activity.themeColor(R.attr.ptCard))
        val theme = firstScriptRunIn(binder.container)
        assertTrue("precondition: the theme is what ran: ${theme.take(40)}", theme.startsWith("ptTheme("))
        assertTrue(theme, theme.contains("\"--pt-panel\":\"$card\""))

        val seen = 0xFF345678.toInt()
        val other = LayoutInflater.from(activity).inflate(R.layout.fragment_translation_result, null)
        val onCard = WordRowsBinder(
            other, activity, Prefs(activity),
            object : WordRowsBinder.Host {
                override val isAlive: Boolean get() = true
                override val scope: CoroutineScope get() = this@WordRowsBinderTest.scope
                override val ttsAlertTarget: TtsAlertTarget get() = TtsAlertTarget.InActivity(activity)
                override fun onWordTapped(row: RowState, explanation: Explanation?) {}
                override fun onAskNeedsLlm() {}
            },
            cardColor = seen,
        )
        onCard.render(settled())
        val seenTheme = firstScriptRunIn(onCard.container)
        assertTrue(seenTheme, seenTheme.contains("\"--pt-panel\":\"#345678\""))
        // The row chrome's band behind a pinned head is that colour.
        assertTrue(DefinitionsDocument.documentShellHtml().contains("background: var(--pt-panel);"))
    }

    private fun clearPrefs() {
        app.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }
}
