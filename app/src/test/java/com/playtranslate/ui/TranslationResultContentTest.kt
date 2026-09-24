package com.playtranslate.ui

import android.app.Activity
import android.content.Context
import android.graphics.Point
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.language.SourceLangId
import com.playtranslate.model.OcrProvenance
import com.playtranslate.model.PendingTranslation
import com.playtranslate.model.TextSegments
import com.playtranslate.model.TranslationResult
import com.playtranslate.overlay.OverlayHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/**
 * Pins [TranslationResultContent]'s shell contract — what the in-app
 * fragment and the workspace Sentence page both rely on: every render
 * reaches the host's pre-render hook first; Idle/Status/Error show the
 * status screen (the start hint only when the host says so) and hide the
 * results; Translating and Ready bind the sections and reveal the results
 * (fit before show, across the posted steps) with the Clear row following
 * the host; the deferred-translation request reaches the host only while a
 * pending is bound AND the translation section is visible or the request
 * is forced; the section headers' Anki button, the language headers, the
 * Clear button and a word row's tap route to the host; a hidden Edit
 * button when the host has no editor; a settled word list builds after
 * the host's enter-settle gate; and on a narrow page both headers fold
 * behind ⋯, whose menu runs the same actions (Add to Anki reaches the
 * host, Text size opens the size picker anchored on the target's ⋯).
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
        val tappedWords = mutableListOf<Pair<String, String?>>()
        var clears = 0
        var completions = 0
        var ankiTaps = 0
        var languageTaps = mutableListOf<Boolean>()
        var clearAction = false
        var startHint = false
        var edit = false

        override fun onRender(state: ResultState) { rendered += state }
        override fun onWordTapped(word: String, reading: String?) { tappedWords += word to reading }
        override fun onClear() { clears++ }
        override fun completeDeferredTranslation() { completions++ }
        override fun onAddToAnki() { ankiTaps++ }
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

    @Before
    fun setUp() {
        clearPrefs()
        Prefs(app).hideTranslationSection = false
        controller = Robolectric.buildActivity(Host::class.java).setup()
        activity = controller.get()
        root = LayoutInflater.from(activity).inflate(R.layout.fragment_translation_result, null)
        activity.setContentView(root)
        vm = TranslationResultViewModel(scope)
        host = FakeHost(activity, scope)
        content = TranslationResultContent(root, activity, Prefs(activity), vm, host)
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
    fun `header and row actions route to the host`() {
        host.edit = false
        content.render(ResultState.Ready(result("猫が食べる。", "The cat eats.")))
        idle()
        assertEquals("no editor: the Edit button is hidden", View.GONE, view(R.id.btnEditOriginal).visibility)
        view(R.id.btnAnkiOriginal).performClick()
        assertEquals("the header's Anki button routes to the host", 1, host.ankiTaps)
        view(R.id.labelOriginal).performClick()
        view(R.id.labelTranslation).performClick()
        assertEquals(listOf(true, false), host.languageTaps)
        view(R.id.btnResultClear).performClick()
        assertEquals(1, host.clears)

        content.renderWordLookups(
            WordLookupsState.Settled(
                rows = listOf(
                    RowState(
                        displayWord = "猫", reading = "ねこ", meaning = "cat", senses = emptyList(),
                        freqScore = 3, isCommon = true, surface = "猫",
                    ),
                ),
                tokenSpans = emptyList(), lookupToReading = emptyMap(),
            ),
        )
        idle()
        assertFalse("rows build after the enter-settle gate", content.wordRows.isEmpty)
        val cell = (0 until content.wordRows.container.childCount)
            .map { content.wordRows.container.getChildAt(it) }
            .filterIsInstance<WordResultCell>().single()
        cell.performClick()
        assertEquals(listOf("猫" to "ねこ"), host.tappedWords)
        content.renderWordLookups(WordLookupsState.Loading)
        assertTrue(content.wordRows.isEmpty)
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
        content.render(ResultState.Ready(result("猫が食べる。", "The cat eats.")))
        idle()
        layoutPage()
        val more = view(R.id.btnMoreOriginal)
        assertEquals("the header ran out of room", View.VISIBLE, more.visibility)
        assertEquals(View.GONE, view(R.id.btnAnkiOriginal).visibility)
        more.performClick()
        assertTrue(content.popovers.isShowing)
        menuRow(activity.getString(R.string.cd_add_to_anki)).performClick()
        assertFalse(content.popovers.isShowing)
        assertEquals(1, host.ankiTaps)
    }

    @Test
    @Config(qualifiers = "w160dp-h640dp")
    fun `the menu's Text size opens the size picker on the target header's more`() {
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

    private fun clearPrefs() {
        app.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }
}
