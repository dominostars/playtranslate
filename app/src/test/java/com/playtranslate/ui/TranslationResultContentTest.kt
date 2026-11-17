package com.playtranslate.ui

import android.app.Activity
import android.content.Context
import android.graphics.Point
import android.os.Bundle
import android.os.Looper
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.OverlayMode
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.ask.AskQuestion
import com.playtranslate.ask.AskState
import com.playtranslate.ask.Explanation
import com.playtranslate.language.AnnotatedSpan
import com.playtranslate.language.InflectionTag
import com.playtranslate.language.SentenceAnnotation
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.TokenSpan
import com.playtranslate.model.OcrProvenance
import com.playtranslate.model.PendingTranslation
import com.playtranslate.model.TextSegments
import com.playtranslate.model.TranslationResult
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.translation.TranslationBackendRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pins [TranslationResultContent]'s shell contract — what the in-app
 * fragment and the workspace Sentence page both rely on: every render
 * reaches the host's pre-render hook first; Idle/Status/Error show the
 * status screen (the start hint only when the host says so) and hide the
 * results; Translating and Ready bind the sections and reveal the results
 * (fit before show, across the posted steps) with the Clear row following
 * the host; the deferred-translation request reaches the host only while a
 * pending is bound AND the translation section is visible or the request
 * is forced; the floating Anki pill (a tap and a hold), the language
 * headers, the Clear button and a word row's tap route to the host; the
 * pill shows with a result, makes way for the status screen and for the
 * question being typed (its field focused with the keyboard up), and stays
 * under an open popover; the content's end leaves the pill's room; a hidden Edit
 * button when the host has no editor; a settled word list builds after
 * the host's enter-settle gate; an analyzed lookup sets the tap spans
 * whether or not the Words card is shown and asks for the rows only while
 * it is; and on a narrow page both headers fold
 * behind ⋯, whose menu runs the same actions (Edit reaches the host, Text
 * size opens the size picker anchored on the target's ⋯).
 * The fit follows the sections' arrangement: stacked, each text is fitted
 * to half the scroll viewport; paired, each to the viewport less its own
 * header row and card chrome; and a change of the page's width (a
 * rotation) posts a re-fit for the arrangement it brings, while a change
 * of its height alone (a keyboard) posts none.
 * The Ask section is the view-model's session drawn: an answer stays with
 * its sentence across renders (a text still translating is already that
 * sentence) and ends when another sentence, or none, is on show; with no
 * LLM a preset leads to the host's alert, as an interaction; and the
 * Translation eye tells an answer on show whether it may give the
 * translation away.
 */
@RunWith(RobolectricTestRunner::class)
class TranslationResultContentTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private class FakeHost(activity: Activity, override val scope: CoroutineScope) : TranslationResultContent.Host {
        override val isAlive: Boolean = true
        override val ttsAlertTarget: TtsAlertTarget = TtsAlertTarget.InActivity(activity)
        override val lensOverlayHost: OverlayHost? = null
        override val lensWindowManager: WindowManager = activity.windowManager
        override val lensDisplayId: Int = 0
        override fun screenSize() = Point(1080, 1920)
        override val opensWithoutEntry: Boolean = false
        override fun wireLensActions(lens: MagnifierLens, resolved: SourceWordLookup.ResolvedAt) = Unit

        val rendered = mutableListOf<ResultState>()
        val tappedWords = mutableListOf<Triple<String, String?, String?>>()
        /** Each tap's detail key (lookupForm, lookupReading). */
        val tappedKeys = mutableListOf<Pair<String, String?>>()
        /** Each tap's answer, as it stood in the tapped row. */
        val tappedExplanations = mutableListOf<Explanation?>()
        var clears = 0
        var completions = 0
        var ankiTaps = 0
        var ankiHolds = 0
        var edits = 0
        var languageTaps = mutableListOf<Boolean>()
        var clearAction = false
        var startHint = false
        var edit = false
        var modeOnScreen: OverlayMode? = null
        var askAlerts = 0
        var interactions = 0

        override val captureModeOnScreen: OverlayMode? get() = modeOnScreen
        override fun onAskNeedsLlm() { askAlerts++ }
        override fun onInteraction() { interactions++ }
        override fun onRender(state: ResultState) { rendered += state }
        override fun onWordTapped(
            word: String,
            reading: String?,
            surface: String?,
            lookupForm: String,
            lookupReading: String?,
            explanation: Explanation?,
        ) {
            tappedWords += Triple(word, reading, surface)
            tappedKeys += lookupForm to lookupReading
            tappedExplanations += explanation
        }
        override fun onClear() { clears++ }
        override fun completeDeferredTranslation() { completions++ }
        override fun onAddToAnki() { ankiTaps++ }
        override fun onAnkiOneTap() { ankiHolds++ }
        override fun onEditRequested() { edits++ }
        override fun onChangeLanguage(isSource: Boolean) { languageTaps += isSource }
        override val showsClearAction: Boolean get() = clearAction
        override val showsStartHint: Boolean get() = startHint
        override val editAvailable: Boolean get() = edit
    }

    private val app: Context = ApplicationProvider.getApplicationContext()
    private lateinit var controller: ActivityController<Host>
    private lateinit var activity: Host
    private lateinit var root: View
    private lateinit var vm: TranslationResultViewModel
    private lateinit var host: FakeHost
    private lateinit var content: TranslationResultContent
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Whether the keyboard is up over the page, as the content reads it. */
    private var keyboard = false

    @Before
    fun setUp() {
        clearPrefs()
        Prefs(app).hideTranslationSection = false
        // A new install starts with the Words card collapsed; these tests are about a shown card.
        Prefs(app).hideWordsSection = false
        controller = Robolectric.buildActivity(Host::class.java).setup()
        activity = controller.get()
        root = LayoutInflater.from(activity).inflate(R.layout.fragment_translation_result, null)
        activity.setContentView(root)
        vm = TranslationResultViewModel(scope)
        host = FakeHost(activity, scope)
        content = TranslationResultContent(root, activity, Prefs(activity), vm, host, keyboardShown = { keyboard })
    }

    @After
    fun tearDown() {
        content.release()
        scope.cancel()
        controller.pause().stop().destroy()
        idle()
        clearPrefs()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun result(text: String, translation: String, pending: PendingTranslation? = null) =
        TranslationResult(
            originalText = text, segments = TextSegments.ofText(text), translatedText = translation,
            timestamp = "", pendingTranslation = pending, langContext = Prefs(app).langContext(),
        )

    private fun view(id: Int): View = root.findViewById(id)
    private fun text(id: Int): String = root.findViewById<TextView>(id).text.toString()

    @Test
    fun `status states show the status screen and reach the render hook first`() {
        content.render(ResultState.Idle)
        assertEquals(View.VISIBLE, view(R.id.statusContainer).visibility)
        assertEquals(View.GONE, view(R.id.resultsContent).visibility)
        assertEquals("no start hint unless the host shows one", View.GONE, view(R.id.tvStatusHint).visibility)
        host.startHint = true
        content.render(ResultState.Idle)
        assertEquals(View.VISIBLE, view(R.id.tvStatusHint).visibility)
        content.render(ResultState.Error("boom"))
        assertTrue(text(R.id.tvStatus).contains("boom"))
        assertEquals(View.GONE, view(R.id.resultsContent).visibility)
        content.render(ResultState.Status("Capturing", showHint = false, ocrProvenance = null as OcrProvenance?))
        assertEquals("Capturing", text(R.id.tvStatus))
        assertEquals(3, host.rendered.count { it is ResultState.Idle || it is ResultState.Error || it is ResultState.Status } - 1)
    }

    @Test
    fun `translating and ready bind the sections and reveal the results after the fit`() {
        host.clearAction = true
        content.render(ResultState.Translating("猫が食べる。", TextSegments.ofText("猫が食べる。")))
        assertEquals(View.GONE, view(R.id.statusContainer).visibility)
        assertEquals("hidden until the posted fit", View.INVISIBLE, view(R.id.resultsContent).visibility)
        idle()
        assertEquals(View.VISIBLE, view(R.id.resultsContent).visibility)
        assertEquals(activity.getString(R.string.status_translating), text(R.id.tvTranslation))
        assertEquals("猫が食べる。", content.displayedOriginalText())
        assertEquals(View.VISIBLE, view(R.id.resultActionButtons).visibility)

        host.clearAction = false
        content.render(ResultState.Ready(result("猫が食べる。", "The cat eats.")))
        idle()
        assertEquals("The cat eats.", text(R.id.tvTranslation))
        assertEquals(View.GONE, view(R.id.resultActionButtons).visibility)
        assertEquals(listOf("Translating", "Ready"), host.rendered.map { it::class.simpleName })
    }

    @Test
    fun `a superseded reveal never resurrects results over the status screen`() {
        content.render(ResultState.Translating("猫", TextSegments.ofText("猫")))
        content.render(ResultState.Status("Capturing", showHint = false))
        idle()
        assertEquals(View.GONE, view(R.id.resultsContent).visibility)
        assertEquals(View.VISIBLE, view(R.id.statusContainer).visibility)
    }

    @Test
    fun `the deferred request reaches the host only for a bound pending with the section visible or forced`() {
        content.requestDeferredCompletion()
        assertEquals("no result, no request", 0, host.completions)
        val pending = PendingTranslation(listOf("猫"), SourceLangId.JA, "en")
        vm.displayResult(result("猫", "", pending), app)
        content.render(vm.result.value)
        assertEquals("a visible section requests on the Ready render", 1, host.completions)
        Prefs(app).hideTranslationSection = true
        content.requestDeferredCompletion()
        assertEquals("hidden section: no request", 1, host.completions)
        content.requestDeferredCompletion(force = true)
        assertEquals(2, host.completions)
        Prefs(app).hideTranslationSection = false
        vm.applyDeferredTranslation(pending, "Cat", null, null)
        content.requestDeferredCompletion()
        assertEquals("no pending left: no request", 2, host.completions)
    }

    @Test
    fun `translation boxes on the game screen run the deferred translation when the result lands, whatever the eye says`() {
        // Two screens, the section hidden: the row picked Translations while
        // the capture was still being read, and the request found nothing.
        Prefs(app).hideTranslationSection = true
        host.modeOnScreen = OverlayMode.TRANSLATION
        content.render(ResultState.Translating("猫", TextSegments.ofText("猫")))
        content.requestDeferredCompletion()
        assertEquals("nothing has landed yet", 0, host.completions)

        val pending = PendingTranslation(listOf("猫"), SourceLangId.JA, "en")
        vm.displayResult(result("猫", "", pending), app)
        content.render(vm.result.value)

        assertEquals("the Ready bind runs it for the chips", 1, host.completions)
        // Boxes in another mode paint without it and wait for the eye, as
        // the over-game sheet's do; with none up, likewise.
        host.modeOnScreen = OverlayMode.FURIGANA
        content.requestDeferredCompletion()
        host.modeOnScreen = null
        content.requestDeferredCompletion()
        assertEquals(1, host.completions)
    }

    // ── The floating Anki pill ──────────────────────────────────────────

    // Mutations: show the pill on the status screen; hide it on a
    // Translating placeholder.
    @Test
    fun `the Anki pill shows with a result and makes way for the status screen`() {
        val pill = view(R.id.btnAnkiPill)
        content.render(ResultState.Idle)
        assertEquals("idle: none", View.GONE, pill.visibility)
        content.render(ResultState.Translating("猫が食べる。", TextSegments.ofText("猫が食べる。")))
        assertEquals("a placeholder is a result on show", View.VISIBLE, pill.visibility)
        content.render(ResultState.Ready(result("猫が食べる。", "The cat eats.")))
        idle()
        assertEquals(View.VISIBLE, pill.visibility)
        // Both texts hidden: the headers offered no Anki then; the pill does.
        Prefs(app).hideOriginalSection = true
        Prefs(app).hideTranslationSection = true
        content.render(ResultState.Ready(result("猫が食べる。", "The cat eats!")))
        idle()
        assertEquals("with both texts hidden too", View.VISIBLE, pill.visibility)
        content.render(ResultState.Status("Capturing", showHint = false))
        assertEquals("a status: none", View.GONE, pill.visibility)
        content.render(ResultState.Ready(result("猫が食べる。", "The cat eats.")))
        content.render(ResultState.Error("boom"))
        assertEquals("an error: none", View.GONE, pill.visibility)
    }

    // Mutations: hide the pill on the field's focus alone; never bring it
    // back while the field keeps its focus; drop the keyboard watch.
    @Test
    fun `the pill makes way while the question is typed in and comes back when the keyboard closes`() {
        content.render(ResultState.Ready(result("猫が食べる。", "The cat eats.")))
        idle()
        val pill = view(R.id.btnAnkiPill)
        val field = view(R.id.etAskQuestion)
        assertTrue("precondition: the question field is on the page", field.isShown)

        field.requestFocus()
        assertTrue("precondition: the field has the focus", field.isFocused)
        assertEquals("focused with no keyboard yet: the pill stays", View.VISIBLE, pill.visibility)

        // The keyboard comes up: the window lays out again.
        keyboard = true
        root.requestLayout()
        idle()
        assertEquals("typing: the pill makes way", View.GONE, pill.visibility)

        // A keyboard closed by its own button or Back leaves the field focused.
        keyboard = false
        root.requestLayout()
        idle()
        assertTrue("precondition: still focused", field.isFocused)
        assertEquals("the keyboard closed: back", View.VISIBLE, pill.visibility)

        // Focus leaving with the keyboard still up (a send) brings it back too.
        keyboard = true
        root.requestLayout()
        idle()
        assertEquals(View.GONE, pill.visibility)
        content.ask.clearFieldFocus()
        assertEquals("the focus gone: back", View.VISIBLE, pill.visibility)
    }

    // Mutation: put the pill in the root directly, its elevation over the
    // popover host's scrim.
    @Test
    fun `an open popover takes a tap on the pill's spot, and the pill opens nothing under it`() {
        content.render(ResultState.Ready(result("猫が食べる。", "The cat eats.")))
        idle()
        layoutPage()
        val pill = view(R.id.btnAnkiPill)
        assertEquals(View.VISIBLE, pill.visibility)
        /** A finger's tap at the pill's centre, through the page's root. */
        fun tapAt() {
            val loc = IntArray(2)
            val rootLoc = IntArray(2)
            pill.getLocationInWindow(loc)
            root.getLocationInWindow(rootLoc)
            val x = loc[0] - rootLoc[0] + pill.width / 2f
            val y = loc[1] - rootLoc[1] + pill.height / 2f
            val t = android.os.SystemClock.uptimeMillis()
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val e = android.view.MotionEvent.obtain(t, t, action, x, y, 0)
                root.dispatchTouchEvent(e)
                e.recycle()
            }
            idle()
        }

        view(R.id.btnFontSize).performClick()
        assertTrue("precondition: the size picker is open", content.popovers.isShowing)
        layoutPage()
        tapAt()
        assertFalse("the popover's scrim took the tap and closed it", content.popovers.isShowing)
        assertEquals("the pill under it opened nothing", 0, host.ankiTaps)

        // With nothing over it, the same tap is the pill's.
        tapAt()
        assertEquals(1, host.ankiTaps)
    }

    // Mutation: drop the clearance from the content's end.
    @Test
    fun `content scrolled to the end clears the pill`() {
        val clearance = activity.resources.getDimensionPixelSize(R.dimen.pt_anki_pill_clearance)
        assertEquals("the content leaves the pill's room at its end",
            clearance, view(R.id.resultsScrollContent).paddingBottom)
        val pill = view(R.id.btnAnkiPill)
        val lp = pill.layoutParams as ViewGroup.MarginLayoutParams
        assertTrue("the room holds the pill and its margin (${lp.height} + ${lp.bottomMargin} < $clearance)",
            lp.height + lp.bottomMargin < clearance)
    }

    @Test
    fun `header and row actions route to the host`() {
        host.edit = false
        content.render(ResultState.Ready(result("猫が食べる。", "The cat eats.")))
        idle()
        assertEquals("no editor: the Edit button is hidden", View.GONE, view(R.id.btnEditOriginal).visibility)
        view(R.id.btnAnkiPill).performClick()
        assertEquals("the Anki pill's tap routes to the host", 1, host.ankiTaps)
        view(R.id.btnAnkiPill).performLongClick()
        assertEquals("and its hold", 1, host.ankiHolds)
        assertEquals("a hold is no tap", 1, host.ankiTaps)
        view(R.id.labelOriginal).performClick()
        view(R.id.labelTranslation).performClick()
        assertEquals(listOf(true, false), host.languageTaps)
        view(R.id.btnResultClear).performClick()
        assertEquals(1, host.clears)

        content.renderWordLookups(
            WordLookupsState.Settled(
                rows = listOf(
                    RowState(
                        displayWord = "食べる", reading = "たべる", meaning = "to eat", senses = emptyList(),
                        freqScore = 3, isCommon = true, surface = "食べた",
                        lookupForm = "たべる", lookupReading = null,
                    ),
                ),
                tokenSpans = emptyList(), lookupToReading = emptyMap(),
            ),
        )
        idle()
        assertFalse("rows build after the enter-settle gate", content.wordRows.isEmpty)
        content.wordRows.tapForTest(0, DefAction.OPEN)
        assertEquals(
            "the row's occurrence text rides with the word, for the detail header",
            listOf(Triple("食べる", "たべる", "食べた")), host.tappedWords,
        )
        assertEquals(
            "the row's own key rides with the display, for the detail page's lookup",
            listOf("たべる" to null), host.tappedKeys,
        )
        content.renderWordLookups(WordLookupsState.Loading)
        assertTrue(content.wordRows.isEmpty)
    }

    @Test
    fun `an analyzed lookup sets the tap spans and asks for rows only while the card is shown`() {
        var wanted = 0
        content.wordRows.onRowsWanted = { wanted++ }
        val text = "猫が食べた。"
        content.render(ResultState.Ready(result(text, "The cat ate.")))
        idle()
        assertEquals("the Ready render's visibility pass asks for the shown card", 1, wanted)
        val cat = TokenSpan("猫", "猫", "ねこ")
        val ate = TokenSpan("食べた", "食べる", "たべる", listOf(InflectionTag.TA))
        val analyzed = WordLookupsState.Analyzed(
            tokenSpans = listOf(cat, ate),
            annotation = SentenceAnnotation(
                text, SourceLangId.JA, 0,
                listOf(
                    AnnotatedSpan(0, 1, "猫", lookupForm = "猫", lookupHint = "ねこ"),
                    AnnotatedSpan(2, 5, "食べた", lookupForm = "食べる", lookupHint = "たべる"),
                ),
            ),
            phrases = emptyList(),
        )
        val expected = listOf(
            SourceWordLookup.TapSpan(0..0, "猫", "ねこ", cat),
            SourceWordLookup.TapSpan(2..4, "食べる", "たべる", ate),
        )
        content.renderWordLookups(analyzed)
        assertEquals("readings fall back to the analysis's own", expected, content.sourceLens.wordSpans)
        assertEquals(
            "the tapped token's conjugation tags ride with its span",
            listOf(InflectionTag.TA), content.sourceLens.wordSpans[1].token.inflections,
        )
        assertEquals(2, wanted)
        assertTrue("no rows until they settle", content.wordRows.isEmpty)

        Prefs(app).hideWordsSection = true
        content.wordRows.applyWordsVisibility()
        content.renderWordLookups(WordLookupsState.Loading)
        assertTrue(content.sourceLens.wordSpans.isEmpty())
        content.renderWordLookups(analyzed)
        assertEquals("the spans don't depend on the card", expected, content.sourceLens.wordSpans)
        assertEquals("a hidden card asks for nothing", 2, wanted)
    }

    // ── Ask ─────────────────────────────────────────────────────────────

    private fun sentence(text: String) = askSentence(text, Prefs(app).langContext())

    private fun answered(text: String) = AskState.Answered(AskQuestion.Custom("Why が?"), text, "Gemini")

    @Test
    fun `an Ask answer stays with its sentence across renders and ends when another sentence, or none, is on show`() {
        content.render(ResultState.Translating("猫が食べる。", TextSegments.ofText("猫が食べる。")))
        idle()
        assertEquals("a text still translating is already the sentence", sentence("猫が食べる。"), vm.ask.sentence)
        vm.ask.restore(sentence("猫が食べる。"), answered("It marks the subject."))
        idle()
        assertEquals("the session's state is drawn", "It marks the subject.", text(R.id.tvAskBody))
        assertEquals(View.VISIBLE, view(R.id.askAnswer).visibility)
        assertEquals(View.GONE, view(R.id.askIdle).visibility)

        content.render(ResultState.Ready(result("猫が食べる。", "The cat eats.")))
        idle()
        assertEquals("its translation landing keeps the answer", answered("It marks the subject."), vm.ask.state.value)

        content.render(ResultState.Ready(result("犬が走る。", "The dog runs.")))
        idle()
        assertEquals("another sentence ends it", AskState.Idle, vm.ask.state.value)
        assertEquals(sentence("犬が走る。"), vm.ask.sentence)
        assertEquals("the questions are back", View.VISIBLE, view(R.id.askIdle).visibility)
        assertEquals(View.GONE, view(R.id.askAnswer).visibility)

        vm.ask.restore(sentence("犬が走る。"), answered("Runs."))
        content.render(ResultState.Status("Capturing", showHint = false))
        idle()
        assertEquals("no result on show ends it", AskState.Idle, vm.ask.state.value)
        assertNull(vm.ask.sentence)
    }

    @Test
    fun `with no LLM a Words row's explain button leads to the same alert of the host, as an interaction`() {
        assertFalse("precondition: nothing here could answer", TranslationBackendRegistry.hasCompleter())
        content.render(ResultState.Ready(result("猫が食べる。", "The cat eats.")))
        content.renderWordLookups(
            WordLookupsState.Settled(
                rows = listOf(
                    RowState(
                        displayWord = "食べる", reading = "たべる", meaning = "to eat", senses = emptyList(),
                        freqScore = 3, isCommon = true, surface = "食べる",
                    ),
                ),
                tokenSpans = emptyList(), lookupToReading = emptyMap(),
                sentence = sentence("猫が食べる。"),
            ),
        )
        idle()
        assertEquals("the rows came with their sentence: the button", DefRow.Explain(), content.wordRows.shownRows.single().explain)
        val before = host.interactions

        content.wordRows.tapForTest(0, DefAction.EXPLAIN)

        assertEquals("the alert its Ask section raises", 1, host.askAlerts)
        assertEquals(before + 1, host.interactions)
        assertEquals("nothing was asked", DefRow.Explain(), content.wordRows.shownRows.single().explain)
        assertTrue("and the word was not opened", host.tappedWords.isEmpty())
    }

    // Mutations: the page's note leaves the Ask answer out; keeps it for a
    // sentence it was not asked about; takes a question still out for one.
    @Test
    fun `the note of a card of the sentence on show starts with the Ask section's answer, and with none once the answer is gone`() {
        content.render(ResultState.Ready(result("猫が食べる。", "The cat eats.")))
        idle()
        assertEquals("nothing asked", "", content.sentenceCardNote())

        vm.ask.restore(sentence("猫が食べる。"), answered("が marks the subject."))
        idle()
        assertEquals("が marks the subject.", content.sentenceCardNote())

        // Another sentence on show ends the answer, and the note with it.
        content.render(ResultState.Ready(result("犬がいる。", "There is a dog.")))
        idle()
        assertEquals("", content.sentenceCardNote())

        vm.ask.ask(AskQuestion.Custom("Why が?"))
        assertTrue("precondition: a question is out (${vm.ask.state.value})", vm.ask.state.value !is AskState.Answered)
        assertEquals("a question still out, or one that failed, is no answer", "", content.sentenceCardNote())
    }

    // Mutation: the content drops the row's answer on its way to the host.
    @Test
    fun `a Words row's tap hands the host the answer standing in the row, for the word's card`() {
        val llm = com.playtranslate.translation.FakeCompleterBackend(
            id = "content-test-llm", priority = 0, displayName = "Test LLM",
            answer = { _, _ -> "It is the plain form." },
        )
        TranslationBackendRegistry.addOnlineBackend(llm)
        try {
            content.render(ResultState.Ready(result("猫が食べる。", "The cat eats.")))
            content.renderWordLookups(
                WordLookupsState.Settled(
                    rows = listOf(
                        RowState(
                            displayWord = "食べる", reading = "たべる", meaning = "to eat", senses = emptyList(),
                            freqScore = 3, isCommon = true, surface = "食べる",
                        ),
                    ),
                    tokenSpans = emptyList(), lookupToReading = emptyMap(),
                    sentence = sentence("猫が食べる。"),
                ),
            )
            idle()
            content.wordRows.tapForTest(0, DefAction.OPEN)
            assertEquals("nothing asked, nothing handed on", listOf<Explanation?>(null), host.tappedExplanations)

            content.wordRows.tapForTest(0, DefAction.EXPLAIN)
            idle()
            assertNotNull("precondition: the answer is in the row", content.wordRows.shownRows.single().explain?.answer)
            content.wordRows.tapForTest(0, DefAction.OPEN)

            // The whole answer: its text and who gave it, as the word's page
            // draws it again.
            assertEquals(listOf(null, Explanation("It is the plain form.", "Test LLM")), host.tappedExplanations)
            assertEquals("and it is what a card of the sentence starts with", "It is the plain form.", content.sentenceCardNote())
        } finally {
            TranslationBackendRegistry.removeOnlineBackend(llm.id)
        }
    }

    @Test
    fun `the lens over the source text gives its row the explain button, and with no LLM closes and leaves the host its alert`() {
        assertFalse("precondition: nothing here could answer", TranslationBackendRegistry.hasCompleter())
        // English: a tokenizer and a lookup that need no pack.
        Prefs(app).sourceLang = "en"
        val text = "hello world"
        content.render(ResultState.Ready(result(text, "bonjour le monde")))
        val hello = TokenSpan("hello", "hello")
        val world = TokenSpan("world", "world")
        content.renderWordLookups(
            WordLookupsState.Analyzed(
                tokenSpans = listOf(hello, world),
                annotation = SentenceAnnotation(
                    text, SourceLangId.EN, 0,
                    listOf(
                        AnnotatedSpan(0, 5, "hello", lookupForm = "hello", lookupHint = ""),
                        AnnotatedSpan(6, 11, "world", lookupForm = "world", lookupHint = ""),
                    ),
                ),
                phrases = emptyList(),
            ),
        )
        idle()
        assertEquals("precondition: the source text has its tap spans", 2, content.sourceLens.wordSpans.size)

        content.sourceLens.onTapAtOffset(1)
        val deadline = System.currentTimeMillis() + 3000
        while (!content.sourceLens.isShowing && System.currentTimeMillis() < deadline) {
            idle()
            Thread.sleep(5)
        }
        assertTrue("precondition: the lens came up over the word", content.sourceLens.isShowing)
        val lens = SourceTextLens::class.java.getDeclaredField("lens").apply { isAccessible = true }
            .get(content.sourceLens) as MagnifierLens
        val row = lens.shownRowsForTest().filterIsInstance<DefRow.Word>().first()
        assertEquals("the unit was resolved against a displayed text: the button", DefRow.Explain(), row.explain)

        lens.tapRowForTest(row.id, DefAction.EXPLAIN)
        idle()

        assertFalse("the lens is gone before the alert: its window would cover the page's", content.sourceLens.isShowing)
        assertEquals("the alert the page's Ask section raises", 1, host.askAlerts)
    }

    @Test
    fun `with no LLM an Ask preset leads to the host's alert, as an interaction, and asks nothing`() {
        assertFalse("precondition: nothing here could answer", TranslationBackendRegistry.hasCompleter())
        content.render(ResultState.Ready(result("猫が食べる。", "The cat eats.")))
        idle()
        (view(R.id.askPresetChips) as ViewGroup).getChildAt(0).performClick()
        assertEquals(1, host.askAlerts)
        assertEquals(1, host.interactions)
        assertEquals(AskState.Idle, vm.ask.state.value)
    }

    @Test
    fun `the Translation eye tells an answer on show whether it may give the translation away`() {
        content.render(ResultState.Ready(result("猫が食べる。", "The cat eats.")))
        idle()
        vm.ask.restore(sentence("猫が食べる。"), answered("It marks the subject."))
        idle()
        assertEquals(View.GONE, view(R.id.tvAskWarning).visibility)
        view(R.id.btnToggleTranslation).performClick()
        assertTrue("precondition: the eye hid the translation", Prefs(app).hideTranslationSection)
        assertEquals(View.VISIBLE, view(R.id.tvAskWarning).visibility)
        view(R.id.btnToggleTranslation).performClick()
        assertEquals(View.GONE, view(R.id.tvAskWarning).visibility)
    }

    /** One traversal of the page at the display's size. */
    private fun layoutPage() {
        val dm = activity.resources.displayMetrics
        root.measure(
            View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, dm.widthPixels, dm.heightPixels)
    }

    private fun menuRow(name: String): View =
        content.popovers.navActions().orEmpty().map { it.view }.single {
            it.findViewById<TextView>(R.id.overflowRowLabel).text.toString() == name
        }

    @Test
    @Config(qualifiers = "w160dp-h640dp")
    fun `a narrow source header folds, and its menu runs what the inline button would`() {
        content.binder.editAvailable = true
        content.render(ResultState.Ready(result("猫が食べる。", "The cat eats.")))
        idle()
        layoutPage()
        val more = view(R.id.btnMoreOriginal)
        assertEquals("the header ran out of room", View.VISIBLE, more.visibility)
        assertEquals(View.GONE, view(R.id.btnEditOriginal).visibility)
        more.performClick()
        assertTrue(content.popovers.isShowing)
        menuRow(activity.getString(R.string.header_action_edit)).performClick()
        assertFalse(content.popovers.isShowing)
        assertEquals(1, host.edits)
    }

    @Test
    @Config(qualifiers = "w160dp-h640dp")
    fun `the menu's Text size opens the size picker on the target header's more`() {
        // Show on screen offered (two screens): beside Text size it makes two
        // actions to fold. Text size alone never folds, a single action
        // being no wider than ⋯ ([HeaderFit]).
        content.binder.setShowOnScreenAvailable(true)
        content.render(ResultState.Ready(result("猫が食べる。", "The cat eats.")))
        idle()
        layoutPage()
        val more = view(R.id.btnMoreTranslation)
        assertEquals(View.VISIBLE, more.visibility)
        more.performClick()
        menuRow(activity.getString(R.string.cd_text_size)).performClick()
        assertTrue(content.popovers.content is FontSizeRangePopover)
        assertSame(more, content.popovers.anchor)
    }

    @Test
    fun `the Ask header's Text size opens the one-handle picker on the page's popover host`() {
        content.render(ResultState.Ready(result("猫が食べる。", "The cat eats.")))
        idle()
        val sentence = vm.ask.sentence
        assertNotNull("precondition: the page bound its sentence", sentence)
        vm.ask.restore(sentence!!, AskState.Answered(AskQuestion.Custom("Why?"), "Because.", "Gemini"))
        idle()
        layoutPage()
        val size = view(R.id.btnFontSizeAsk)
        assertEquals("an answer on show offers it", View.VISIBLE, size.visibility)
        size.performClick()
        assertTrue("${content.popovers.content}", content.popovers.content is FontSizePopover)
        assertSame(size, content.popovers.anchor)
    }

    @Test
    fun `a re-render keeps an open popover, though the reveal hides the results to fit`() {
        content.render(ResultState.Ready(result("猫が食べる。", "The cat eats.")))
        idle()
        layoutPage()
        view(R.id.btnFontSize).performClick()
        assertTrue(content.popovers.isShowing)
        // A live result: the funnel hides the scroll (INVISIBLE) for the fit.
        content.render(ResultState.Ready(result("猫が食べる。", "The cat eats!")))
        assertEquals(View.INVISIBLE, view(R.id.resultsContent).visibility)
        root.viewTreeObserver.dispatchOnPreDraw()
        idle()
        assertTrue("the size picker survives the update", content.popovers.isShowing)
        // A status takeover replaces the results (GONE): that closes it.
        content.render(ResultState.Status("Capturing", showHint = false))
        root.viewTreeObserver.dispatchOnPreDraw()
        idle()
        assertFalse(content.popovers.isShowing)
    }

    // ── The fit, per arrangement ────────────────────────────────────────
    // Native graphics for these, so the texts measure in a real font and a
    // fit lands between the size bounds instead of at one of them.

    /** The page pinned to [width] x [height] px and laid out there. Pinned,
     *  so a traversal the looper runs later (each fit requests one) measures
     *  the same size instead of the window's. */
    private fun pinPage(width: Int, height: Int) {
        root.layoutParams = android.widget.FrameLayout.LayoutParams(width, height)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)
    }

    private val arrangement: ResultSectionsLayout.Arrangement
        get() = root.findViewById<ResultSectionsLayout>(R.id.resultsScrollContent).arrangement

    private val viewportHeight: Int get() = view(R.id.resultsContent).height

    /** The texts' sizes right now, in px: (Original, Translation). */
    private fun sizes(): Pair<Float, Float> =
        root.findViewById<TextView>(R.id.tvOriginal).textSize to
            root.findViewById<TextView>(R.id.tvTranslation).textSize

    private fun px(sp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, activity.resources.displayMetrics)

    /** A section's chrome around its text: the content holder's vertical
     *  padding and the card frame's own. No note row shows in these results
     *  (no backend label, no OCR provenance), which [renderLongResult]
     *  asserts. */
    private fun chrome(card: Int, holder: Int): Int =
        view(holder).paddingTop + view(holder).paddingBottom + view(card).paddingTop + view(card).paddingBottom

    private val sourceChrome: Int get() = chrome(R.id.cardOriginal, R.id.originalContent)
    private val translationChrome: Int get() = chrome(R.id.cardTranslation, R.id.translationContent)

    /** The sizes the binder's own fit gives each text for a target height,
     *  in px: the oracle for WHICH height each arrangement fits to. */
    private fun fittedTo(sourceTargetPx: Int, translationTargetPx: Int): Pair<Float, Float> =
        px(content.binder.sourceSizeFor(sourceTargetPx)) to px(content.binder.targetSizeFor(translationTargetPx))

    /** Stacked: each text fitted to half the scroll viewport. */
    private fun stackedRule(): Pair<Float, Float> = fittedTo(viewportHeight / 2, viewportHeight / 2)

    /** Paired: each text fitted to the viewport less its own header row and
     *  card chrome. */
    private fun pairedRule(): Pair<Float, Float> = fittedTo(
        viewportHeight - view(R.id.sourceHeaderRow).height - sourceChrome,
        viewportHeight - view(R.id.targetHeaderRow).height - translationChrome,
    )

    private fun assertSizes(message: String, expected: Pair<Float, Float>, actual: Pair<Float, Float>) {
        assertEquals("$message: the Original", expected.first, actual.first, 0.01f)
        assertEquals("$message: the Translation", expected.second, actual.second, 0.01f)
    }

    /** [rule] and [other] size each text apart, by more than the fit's
     *  bisect step, so a fit to [other] cannot pass for [rule]. */
    private fun assertApart(what: String, rule: Pair<Float, Float>, other: Pair<Float, Float>) {
        assertNotEquals("$what, for the Original", rule.first, other.first, px(0.1f))
        assertNotEquals("$what, for the Translation", rule.second, other.second, px(0.1f))
    }

    /** Both texts fitted strictly between the size bounds (16 to 24sp), so
     *  the target height decides the size rather than a bound. */
    private fun assertBetweenBounds(actual: Pair<Float, Float>) {
        for ((what, size) in listOf("the Original" to actual.first, "the Translation" to actual.second)) {
            assertTrue("$what's fit lands between the bounds ($size px)", size > px(16f) && size < px(24f))
        }
    }

    private fun renderLongResult() {
        content.render(ResultState.Ready(result(LONG_SOURCE, LONG_TRANSLATION)))
        idle()
        assertEquals(View.VISIBLE, view(R.id.resultsContent).visibility)
        assertEquals("no note row in the chrome", View.GONE, view(R.id.tvTranslationNote).visibility)
        assertEquals("no note row in the chrome", View.GONE, view(R.id.sourceNoteRow).visibility)
    }

    @Test
    @Config(qualifiers = "w412dp-h360dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `stacked, each text is fitted to half the scroll viewport`() {
        renderLongResult()
        pinPage(412, 360)
        assertEquals(ResultSectionsLayout.Arrangement.STACKED, arrangement)
        content.fitTextSizes()
        assertApart("the paired rule would size them otherwise", stackedRule(), pairedRule())
        assertSizes("stacked", stackedRule(), sizes())
        assertBetweenBounds(sizes())
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `paired, each text is fitted to the viewport less its own header row and card chrome`() {
        renderLongResult()
        pinPage(800, 360)
        assertEquals(ResultSectionsLayout.Arrangement.PAIRED, arrangement)
        content.fitTextSizes()
        val viewport = viewportHeight
        val sourceHeader = view(R.id.sourceHeaderRow).height
        val targetHeader = view(R.id.targetHeaderRow).height
        // The near misses each give other sizes: half the viewport (the
        // stacked rule), the viewport less the header alone (no chrome),
        // less the chrome alone (no header), and the whole viewport.
        assertApart("half the viewport", pairedRule(), stackedRule())
        assertApart("no chrome", pairedRule(), fittedTo(viewport - sourceHeader, viewport - targetHeader))
        assertApart("no header", pairedRule(), fittedTo(viewport - sourceChrome, viewport - translationChrome))
        assertApart("the whole viewport", pairedRule(), fittedTo(viewport, viewport))
        assertSizes("paired", pairedRule(), sizes())
        assertBetweenBounds(sizes())
    }

    @Test
    @Config(qualifiers = "w412dp-h360dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `a width change posts a re-fit for the arrangement it brings, and a height-only change posts none`() {
        renderLongResult()
        pinPage(412, 360)
        idle()
        content.fitTextSizes()
        assertEquals(ResultSectionsLayout.Arrangement.STACKED, arrangement)
        val stacked = sizes()
        assertSizes("the stacked page's fit", stackedRule(), stacked)

        // Rotated to landscape: the sections pair at the new width.
        pinPage(800, 360)
        assertEquals(ResultSectionsLayout.Arrangement.PAIRED, arrangement)
        assertEquals("posted, not run inside the layout pass", stacked, sizes())
        idle()
        assertEquals("still the pinned landscape page", 800, view(R.id.resultsContent).width)
        assertEquals(ResultSectionsLayout.Arrangement.PAIRED, arrangement)
        assertApart("the stacked fit would size them otherwise", pairedRule(), stacked)
        assertSizes("re-fitted for the pair", pairedRule(), sizes())

        // A keyboard: only the height changes, and the text is left alone,
        // though a fit to the new height would size it differently.
        val beforeKeyboard = sizes()
        pinPage(800, 500)
        assertApart("a fit at the new height would size them otherwise", pairedRule(), beforeKeyboard)
        idle()
        assertEquals("still the pinned page", 500, viewportHeight)
        assertEquals("no re-fit for a height change", beforeKeyboard, sizes())
    }

    private fun clearPrefs() {
        app.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private companion object {
        /** Each too long to fit at the largest size in either arrangement's
         *  room, and short enough to fit at the smallest, at both widths. */
        const val LONG_SOURCE =
            "When the festival lanterns were lit along the river, the children ran from stall to " +
                "stall with paper fans, while their grandparents sat on wooden benches by the water, " +
                "telling the same stories they had told every summer for as long as anyone could remember. " +
                "Later the music started in the square, and the dancing went on long after the lanterns burned low."
        const val LONG_TRANSLATION =
            "The old lighthouse keeper climbed the spiral stairs every evening, counting each of the " +
                "two hundred steps aloud, because his father had told him that a keeper who loses count " +
                "will one day lose the light. Tonight the wind was strong, but he lit the lamp on time. " +
                "The ships far out at sea saw it and turned toward the harbor, and he sat by the window until morning."
    }
}
