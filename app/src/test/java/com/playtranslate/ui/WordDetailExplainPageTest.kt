package com.playtranslate.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Looper
import android.view.Display
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.PlayTranslateApplication
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.ask.AskPrompts
import com.playtranslate.ask.AskQuestion
import com.playtranslate.ask.AskSentence
import com.playtranslate.ask.Explanation
import com.playtranslate.language.PreloadResult
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageEngine
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.language.TokenSpan
import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.DictionaryResponse
import com.playtranslate.model.Headword
import com.playtranslate.model.Sense
import com.playtranslate.overlayThemedContext
import com.playtranslate.themeColor
import com.playtranslate.translation.BackendFailure
import com.playtranslate.translation.BackendFailureKind
import com.playtranslate.translation.CooldownCause
import com.playtranslate.translation.CooldownLadder
import com.playtranslate.translation.FakeCompleterBackend
import com.playtranslate.translation.StructuralFailureException
import com.playtranslate.translation.TranslationBackendRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import org.robolectric.util.ReflectionHelpers
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The word page's "In this sentence" group as the page builds it: the real
 * [WordDetailBinder] bound on the page's layout in an Activity, over a
 * dictionary engine of the test's (two entries, "lead" and "leaden", and
 * nothing else) and a recording [WordDetailBinder.Ui]. Written from the
 * brief as a contract.
 *
 * Pins: with a sentence the group's header and card stand directly under
 * the Definitions card and above what follows (the Other matches section
 * every page here has); with no sentence nothing of it is on the page; the
 * not-found page puts it under its notice. The question names the word as
 * the header shows it, the reading the page gives it and the form it was
 * found as, about the page's sentence under the page's pair. An answer the
 * page was opened with, through its launch arguments or its host's
 * context, is shown at once, whole, and nothing is asked for it; one about
 * another sentence is not. With no model the tap is the host's alert. And
 * the note: an answer asked on the page is the page's own word's note in
 * the review and on the one-tap path; after the X there is none, whatever
 * the launch arguments and the host still carry; a cell's note is what
 * the sentence holds about the cell's word, whatever the page asked.
 *
 * The model, where there is one, is a fake registered in the backend
 * registry beside the app's own (removed after each test) that answers
 * when the test completes [reply]. AnkiDroid counts as installed and the
 * permission as not held, so the Anki button's tap opens the review and its
 * hold falls back to it, the one-tap path's review.
 */
@RunWith(RobolectricTestRunner::class)
class WordDetailExplainPageTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private companion object {
        val LEAD = DictionaryEntry(
            slug = "lead", packId = 1L, isCommon = false, tags = emptyList(), jlpt = emptyList(),
            // Two readings of one spelling: the page's question must name
            // the one the opener showed, not the first.
            headwords = listOf(Headword("lead", "liːd"), Headword("lead", "lɛd")),
            senses = listOf(Sense(listOf("a heavy metal"), listOf("noun"), emptyList(), emptyList(), emptyList())),
        )
        val LEADEN = DictionaryEntry(
            slug = "leaden", packId = 2L, isCommon = false, tags = emptyList(), jlpt = emptyList(),
            headwords = listOf(Headword("leaden", null)),
            senses = listOf(Sense(listOf("dull and heavy"), listOf("adjective"), emptyList(), emptyList(), emptyList())),
        )
    }

    /** The test's dictionary: "lead" and "leaden" and nothing else, every
     *  word could also be "leaden" (the page's Other matches), and a text
     *  splits on spaces, "leaded" read as "lead". */
    private class FakeEngine : SourceLanguageEngine {
        override val profile = SourceLanguageProfiles[SourceLangId.EN]
        override suspend fun preload(): PreloadResult = PreloadResult.Success
        override suspend fun tokenize(text: String): List<TokenSpan> =
            text.split(' ').filter { it.isNotBlank() }.map { TokenSpan(it, if (it == "leaded") "lead" else it) }
        override suspend fun lookup(word: String, reading: String?): DictionaryResponse? = when (word) {
            "lead" -> DictionaryResponse(listOf(LEAD))
            "leaden" -> DictionaryResponse(listOf(LEADEN))
            else -> null
        }
        override suspend fun alternativesOf(span: TokenSpan, primaryIds: Set<Long>): List<TokenSpan> =
            if (span.lookupForm == "leaden") emptyList() else listOf(TokenSpan(span.surface, "leaden"))
        override fun close() {}
    }

    /** Everything the page asked of its host. */
    private class RecordingUi : WordDetailBinder.Ui {
        var alive = true
        var hostContext: SentenceContext? = null
        val reviews = mutableListOf<WordDetailBinder.WordAnkiArgs>()
        var llmAlerts = 0
        override val isAlive: Boolean get() = alive
        override fun sentenceContext(): SentenceContext? = hostContext
        override fun openWordDetail(word: String, reading: String?, surface: String?, lookupForm: String, lookupReading: String?) {}
        override fun openAnkiReview(args: WordDetailBinder.WordAnkiArgs) { reviews += args }
        override fun showAnkiNotInstalled() {
            error("AnkiDroid counts as installed here")
        }
        override fun openFieldMapping(result: AnkiSendResult.NeedsMapping, mode: CardMode, fallback: WordDetailBinder.WordAnkiArgs) {}
        override fun present(builder: OverlayAlert.Builder) {}
        override fun launchOneTap(
            send: suspend () -> Pair<AnkiSendResult, CardMode>,
            presentResult: (Pair<AnkiSendResult, CardMode>) -> Unit,
        ) {
            error("the permission is not held here, so no one-tap send starts")
        }
        override fun onAskNeedsLlm() { llmAlerts++ }
    }

    private val app: Context = ApplicationProvider.getApplicationContext()
    private lateinit var controller: ActivityController<Host>
    private lateinit var activity: Host
    private lateinit var root: View
    private lateinit var binder: WordDetailBinder
    private val ui = RecordingUi()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** What the model answers the question in flight, completed by the test. */
    private var reply = CompletableDeferred<String>()

    /** Requests that saw their cancellation while waiting for [reply]. */
    private val cancelledAsks = AtomicInteger(0)
    private val llm = FakeCompleterBackend(
        id = "word-page-explain-llm", priority = 0, displayName = "Test LLM",
        answer = { _, _ ->
            try {
                reply.await()
            } catch (e: CancellationException) {
                cancelledAsks.incrementAndGet()
                throw e
            }
        },
    )

    private val sentence = "The window had leaded glass."
    private val otherSentence = "The pipes were made of lead."

    /** The page as a Words row of [sentence] opens it: "lead" read lɛd,
     *  found as "leaded". */
    private val launch = WordDetailBinder.Args(
        word = "lead", reading = "lɛd", surface = "leaded", screenshotPath = null,
        sentenceOriginal = sentence, sentenceTranslation = "La fenêtre avait du verre au plomb.",
    )

    private fun sp() = app.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)

    @Suppress("UNCHECKED_CAST")
    private val engines: ConcurrentHashMap<SourceLangId, SourceLanguageEngine>
        get() = ReflectionHelpers.getStaticField(SourceLanguageEngines::class.java, "cache")

    @Before fun setUp() {
        sp().edit().clear().commit()
        Prefs(app).sourceLang = "en"
        Prefs(app).setTarget("en")
        engines[SourceLangId.EN] = FakeEngine()
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = "com.ichi2.anki" })
        controller = Robolectric.buildActivity(Host::class.java).setup()
        activity = controller.get()
    }

    @After fun tearDown() {
        ui.alive = false
        if (::binder.isInitialized) binder.release()
        scope.cancel()
        controller.pause().stop().destroy()
        idle()
        TranslationBackendRegistry.removeOnlineBackend(llm.id)
        SourceLanguageEngines.release(SourceLangId.EN)
        sp().edit().clear().commit()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Drive the main looper until [done], on real threads' hops too. */
    private fun awaitUntil(what: String, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            idle()
            if (done()) return
            check(System.currentTimeMillis() < deadline) { "never: $what" }
            Thread.sleep(5)
        }
    }

    /** The page bound for [args] over [host], built through its Other
     *  matches section. */
    private fun open(args: WordDetailBinder.Args, host: SentenceContext? = null) {
        ui.hostContext = host
        root = LayoutInflater.from(activity).inflate(R.layout.bottom_sheet_word_detail, null)
        activity.setContentView(root)
        binder = WordDetailBinder(activity, scope, ui)
        binder.bind(root, args)
        awaitUntil("the page and its Other matches section are built") {
            headerIndex(activity.getString(R.string.section_other_matches)) >= 0
        }
    }

    private fun giveLlm() = TranslationBackendRegistry.addOnlineBackend(llm)

    private fun answer(text: String) {
        val r = reply
        reply = CompletableDeferred()
        r.complete(text)
        awaitUntil("the answer is drawn") { shownTexts().contains(text.trim()) }
    }

    // ── The page's views, found by what they show ──────────────────────

    private val content: LinearLayout get() = root.findViewById(R.id.detailContent)

    private fun children(g: ViewGroup = content) = (0 until g.childCount).map(g::getChildAt)

    private fun all(v: View): List<View> =
        listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } }.orEmpty())

    /** The index in the page's content of the child whose group header, or
     *  a group header inside it, reads [title]; -1 with none. */
    private fun headerIndex(title: String): Int = children().indexOfFirst { child ->
        all(child).any { it.id == R.id.tvGroupTitle && (it as TextView).text == title }
    }

    private val explainTitle get() = activity.getString(R.string.word_explain_title)
    private val askLabel get() = activity.getString(R.string.word_explain_ask)

    private fun group(): View = children()[headerIndex(explainTitle) + 1]

    /** The texts the group's card shows, in order. */
    private fun shownTexts(): List<String> =
        all(group()).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }

    private fun row(): View = all(group()).filterIsInstance<TextView>().single { it.text == askLabel }.parent as View

    private fun close(): View =
        all(group()).single { it.contentDescription == activity.getString(R.string.cd_ask_close) }

    private fun tap(v: View) {
        assertTrue("tapped while on screen: $v", v.isShown)
        v.performClick()
        idle()
    }

    private val anki: View get() = root.findViewById(R.id.btnWordAddToAnki)

    /** The note the page's own word's review starts with, by the Anki
     *  button's tap, and by its hold, the one-tap path's review. */
    private fun ownNotes(): Pair<String, String> {
        anki.performClick()
        val tapped = ui.reviews.last()
        anki.performLongClick()
        val held = ui.reviews.last()
        assertEquals("the page's own word", launch.target, tapped.target)
        assertEquals(launch.target, held.target)
        return tapped.notes to held.notes
    }

    private fun footerOf(by: String, cut: Boolean = false): String {
        val answeredBy = activity.getString(R.string.ask_answered_by, by)
        return if (cut) activity.getString(R.string.ask_answer_cut) + "\n" + answeredBy else answeredBy
    }

    // ── Presence and place ───────────────────────────────────────────────

    // Mutations: place the group after the tables or the sections, or
    // before the definitions; leave its header out.
    @Test fun `with a sentence the group stands directly under the Definitions card, above what follows`() {
        open(launch)

        val definitions = headerIndex(activity.getString(R.string.word_detail_group_definitions))
        val explain = headerIndex(explainTitle)
        val others = headerIndex(activity.getString(R.string.section_other_matches))
        assertTrue("the Definitions header is on the page", definitions >= 0)
        assertTrue("the Definitions card follows its header", children()[definitions + 1] is PtGroupCard)
        assertEquals("the group's header right after the Definitions card", definitions + 2, explain)
        assertTrue("its card right after its header", children()[explain + 1] is PtGroupCard)
        assertEquals(listOf(askLabel), shownTexts())
        assertTrue("what follows comes after it", others > explain + 1)
    }

    // Mutation: build the group whatever the sentence.
    @Test fun `with no sentence nothing of the group is on the page`() {
        open(launch.copy(sentenceOriginal = null, sentenceTranslation = null))

        assertEquals(-1, headerIndex(explainTitle))
        assertFalse("no row", all(content).any { it is TextView && it.text == askLabel })
        assertTrue(
            "the page itself is built",
            headerIndex(activity.getString(R.string.word_detail_group_definitions)) >= 0,
        )
    }

    // Mutation: leave the group off the not-found page, or put it after
    // what the page descends to.
    @Test fun `the not-found page with a sentence has the group under its notice`() {
        open(launch.copy(word = "zzz", surface = "zzz", lookupForm = "zzz", lookupReading = null))

        val notice = children().indexOfFirst {
            it is TextView && it.text == activity.getString(R.string.word_detail_no_definitions)
        }
        val explain = headerIndex(explainTitle)
        assertTrue("the notice is on the page", notice >= 0)
        assertEquals("the group's header right under the notice", notice + 1, explain)
        assertTrue(children()[explain + 1] is PtGroupCard)
        assertTrue(
            "what the page descends to comes after it",
            headerIndex(activity.getString(R.string.section_other_matches)) > explain + 1,
        )
    }

    // ── The question ─────────────────────────────────────────────────────

    // Mutations: name the word by its key, the first reading, or no found
    // form; ask about the launch fields over the host's sentence; another
    // pair.
    @Test fun `the page asks its word as its header shows it, the reading it gives it and the form it was found as, about its sentence`() {
        giveLlm()
        open(launch)

        tap(row())

        val expected = AskPrompts.build(
            AskQuestion.Word("lead", "lɛd", listOf("leaded"), explainTitle),
            AskSentence(sentence, "en", "en"),
        )
        awaitUntil("the model was asked") { llm.completeCalls.get() == 1 }
        assertEquals(expected.system to expected.user, llm.lastPrompt)
        assertEquals(listOf(activity.getString(R.string.ask_status_asking, "Test LLM")), shownTexts())

        answer("Here it means made with lead.")
        assertEquals(listOf("Here it means made with lead.", footerOf("Test LLM")), shownTexts())
    }

    // ── Try again ────────────────────────────────────────────────────────

    // Mutation: hand the group a pass that keeps the cooldowns on a retry.
    @Test fun `Try again after a failure that put the model in a cooldown asks it again`() {
        giveLlm()
        open(launch)
        tap(row())
        awaitUntil("the model was asked") { llm.completeCalls.get() == 1 }
        // The model fails as Gemini does on a 503: its cooldown recorded,
        // then the throw.
        llm.cooldownState.recordLadderFailure(CooldownLadder.RateLimit, "Server error", CooldownCause.SERVER_ERROR)
        val r = reply
        reply = CompletableDeferred()
        r.completeExceptionally(StructuralFailureException("error 503", BackendFailure(BackendFailureKind.SERVER_ERROR, 503)))
        val tryAgain = activity.getString(R.string.ask_retry)
        awaitUntil("the failure is drawn with Try again") { shownTexts().contains(tryAgain) }
        assertNotNull("precondition: the model is in a cooldown", llm.unavailableUntil())

        tap(all(group()).filterIsInstance<TextView>().single { it.text == tryAgain })

        awaitUntil("the model was asked again") { llm.completeCalls.get() == 2 }
        answer("Here it means made with lead.")
        assertNull("the answer ended the cooldown", llm.unavailableUntil())
    }

    // ── A carried answer ─────────────────────────────────────────────────

    // Mutations: drop the launch answer, or its cut, or who gave it; ask
    // for it.
    @Test fun `an answer in the launch arguments is shown at once, whole, and nothing is asked`() {
        giveLlm()
        open(launch.copy(explanation = Explanation("It means made with lead.", "Gemini", cut = true)))

        assertEquals(listOf("It means made with lead.", footerOf("Gemini", cut = true)), shownTexts())
        assertTrue("the X is up", close().isShown)
        assertEquals("nothing was asked", 0, llm.completeCalls.get())
    }

    // Mutation: read the carried answer from the launch arguments alone.
    @Test fun `an answer in the host's context is shown at once, whole, and nothing is asked`() {
        giveLlm()
        val embedded = launch.copy(sentenceOriginal = null, sentenceTranslation = null, embedded = true)
        val host = SentenceContext(sentence, null, null)
            .explaining(embedded.target, Explanation("It means made with lead.", "Gemini"))
        open(embedded, host)

        assertEquals(listOf("It means made with lead.", footerOf("Gemini")), shownTexts())
        assertEquals(0, llm.completeCalls.get())
    }

    // Mutation: show an answer beside a sentence it is not about.
    @Test fun `an answer about another sentence than the page's is not shown`() {
        giveLlm()
        val explained = launch.copy(explanation = Explanation("It means made with lead.", "Gemini"))
        open(explained, SentenceContext(otherSentence, null, null))

        assertEquals("the group is there, for the page's sentence", listOf(askLabel), shownTexts())
        assertEquals(0, llm.completeCalls.get())
    }

    // ── No model ─────────────────────────────────────────────────────────

    @Test fun `with no model the tap raises the host's alert and nothing is asked`() {
        assertFalse("precondition: nothing here could answer", TranslationBackendRegistry.hasCompleter())
        open(launch)

        tap(row())

        assertEquals(1, ui.llmAlerts)
        assertEquals(listOf(askLabel), shownTexts())
    }

    // ── The note ─────────────────────────────────────────────────────────

    // Mutations: the review or the one-tap path reads the note from the
    // launch fields; a question asked on the page is no note.
    @Test fun `an answer asked on the page is its own word's note, in the review and on the one-tap path`() {
        giveLlm()
        open(launch)
        assertEquals("nothing asked, no note", "" to "", ownNotes())

        tap(row())
        assertEquals("a question still out is no note", "" to "", ownNotes())
        answer("Here it means made with lead.")

        assertEquals("Here it means made with lead." to "Here it means made with lead.", ownNotes())
    }

    // Mutations: after the X the carried answer comes back from the launch
    // arguments, or from the host.
    @Test fun `after the X the page's own word has no note, though its launch arguments and its host still carry one`() {
        val carried = Explanation("It means made with lead.", "Gemini")
        val explained = launch.copy(explanation = carried)
        open(explained, SentenceContext(sentence, null, null).explaining(explained.target, carried))
        assertEquals("the carried answer is the note while it stands", carried.text to carried.text, ownNotes())

        tap(close())

        assertEquals(listOf(askLabel), shownTexts())
        assertEquals("" to "", ownNotes())
    }

    // Mutations: a cell's note follows the page's own; the page's session
    // stands under every word.
    @Test fun `a cell's note is what the sentence holds about the cell's word, whatever the page asked`() {
        giveLlm()
        open(launch)
        @Suppress("UNCHECKED_CAST")
        val list = (ReflectionHelpers.getField<MutableList<WordRowList<RowState>>>(binder, "sectionLists")).single()
        val cell = list.wordRows.single()
        val cellTarget = CardTarget(cell.surface, cell.lookupForm, cell.lookupReading)
        val cellAnswer = Explanation("Here it is the adjective.", "Gemini")
        ui.hostContext = SentenceContext(sentence, null, null).explaining(cellTarget, cellAnswer)
        fun cellNotes(): String {
            list.tapForTest(0, DefAction.TRAILING)
            return ui.reviews.last().also { assertEquals(cellTarget, it.target) }.notes
        }
        assertEquals(cellAnswer.text, cellNotes())

        tap(row())
        answer("Here it means made with lead.")
        assertEquals("the page's own answer is its own word's", cellAnswer.text, cellNotes())
        assertEquals("Here it means made with lead." to "Here it means made with lead.", ownNotes())

        tap(close())
        assertEquals(cellAnswer.text, cellNotes())
    }

    // ── Release ──────────────────────────────────────────────────────────

    // Mutation: the page's release leaves its group's question running.
    @Test fun `releasing the page ends its question in flight, and an answer that comes afterwards is drawn nowhere`() {
        giveLlm()
        open(launch)
        tap(row())
        awaitUntil("the model was asked") { llm.completeCalls.get() == 1 }
        val asking = shownTexts()
        assertEquals(listOf(activity.getString(R.string.ask_status_asking, "Test LLM")), asking)

        // The page's own release, its scope still running: the host cancels
        // that after it, so the question must not wait for it.
        binder.release()

        awaitUntil("the request saw its cancellation") { cancelledAsks.get() == 1 }
        reply.complete("Too late.")
        idle()
        assertEquals("nothing drawn after the release", asking, shownTexts())
    }

    // ── The hosts ────────────────────────────────────────────────────────

    class SheetHost : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    /** The no-model alert's button in [parent], found by its label. */
    private fun alertButton(parent: View): Button =
        all(parent).filterIsInstance<Button>().single { it.text == activity.getString(R.string.ask_no_llm_open_services) }

    private fun Button.fill(): Int = (background as GradientDrawable).color!!.defaultColor

    /** [ctx]'s theme as the alert themes its own: the accent and the
     *  colour of text over it. */
    private fun accentPair(ctx: Context): Pair<Int, Int> {
        val themed = overlayThemedContext(ctx)
        return themed.themeColor(R.attr.ptAccent) to themed.themeColor(R.attr.ptAccentOn)
    }

    // Mutations: the sheet asks for no alert, or shows it in the accent
    // read on a context without it; its button opens nothing.
    @Test fun `the in-app sheet raises the no-model alert above itself, in the accent, and its button opens Translation services`() {
        val sheetController = Robolectric.buildActivity(SheetHost::class.java).setup()
        try {
            val sheetHost = sheetController.get()
            val sheet = WordDetailBottomSheet.newInstance(
                word = "lead", reading = "lɛd", surface = "leaded", sentenceOriginal = sentence,
                lookupForm = "lead", lookupReading = "lɛd",
            )
            sheet.showNow(sheetHost.supportFragmentManager, WordDetailBottomSheet.TAG)
            val page = sheet.requireView()
            awaitUntil("the sheet's group is built") { all(page).any { it is TextView && it.text == askLabel } }

            tap(all(page).filterIsInstance<TextView>().single { it.text == askLabel }.parent as View)

            val button = alertButton(sheet.requireDialog().window!!.decorView)
            assertEquals(accentPair(sheetHost), button.fill() to button.currentTextColor)
            button.performClick()
            idle()
            assertEquals(
                TranslationServicesActivity::class.java.name,
                shadowOf(sheetHost).nextStartedActivity?.component?.className,
            )
        } finally {
            sheetController.pause().stop().destroy()
        }
    }

    /** A workspace host over [ctx] whose modal layer is [modal], recording
     *  where the page asks the cursor to go. */
    private fun workspaceHost(ctx: Context, modal: FrameLayout, cursor: MutableList<View?>): WorkspaceHost =
        Proxy.newProxyInstance(WorkspaceHost::class.java.classLoader, arrayOf(WorkspaceHost::class.java)) { _, method, args ->
            when (method.name) {
                "getCtx" -> ctx
                "getScope" -> scope
                "getDisplayId" -> Display.DEFAULT_DISPLAY
                "getModalLayer" -> modal
                "alert" -> OverlayAlert.Builder(ctx)
                "invalidateNav" -> { cursor += args?.get(0) as View?; null }
                "awaitEnterSettled" -> Unit
                else -> null
            }
        } as WorkspaceHost

    /** The workspace's word page for [sentence]'s "lead", built in a window
     *  over the workspace's themed context, its modal layer above it. */
    private fun openWorkspacePage(cursor: MutableList<View?> = mutableListOf()): Pair<WorkspaceWordDetailPage, FrameLayout> {
        val themed = overlayThemedContext(activity)
        val frame = FrameLayout(themed)
        val pageSlot = FrameLayout(themed)
        val modal = FrameLayout(themed)
        frame.addView(pageSlot)
        frame.addView(modal)
        activity.setContentView(frame)
        val page = WorkspaceWordDetailPage(
            "lead", "lɛd", "leaded", "lead", "lɛd",
            sentenceContext = { SentenceContext(sentence, null, null) },
        )
        pageSlot.addView(page.onCreateView(themed, pageSlot, workspaceHost(themed, modal, cursor)))
        awaitUntil("the workspace page's group is built") { all(pageSlot).any { it is TextView && it.text == askLabel } }
        root = pageSlot.getChildAt(0)
        return page to modal
    }

    // Mutations: the workspace page shows no alert, or one off its modal
    // layer; the button stays in the workspace or opens nothing.
    @Test fun `the workspace page raises the no-model alert in its modal layer, in the accent, and its button leaves for Translation services`() {
        val (page, modal) = openWorkspacePage()
        try {
            tap(row())

            val button = alertButton(modal)
            assertEquals(accentPair(activity), button.fill() to button.currentTextColor)
            // The launch lands on the display of a resumed activity of the
            // app, and this environment's activity has no display (reading
            // it throws): no activity of ours is in front here, as over a
            // game ([PtCloudPillGearTest] clears the same registry).
            PlayTranslateApplication::class.java.getDeclaredField("resumedActivity")
                .apply { isAccessible = true }.set(null, null)
            button.performClick()
            idle()
            val started = shadowOf(app as Application).nextStartedActivity
            assertEquals(TranslationServicesActivity::class.java.name, started?.component?.className)
            assertTrue("a new task, from over the game", started!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        } finally {
            page.onDestroy()
        }
    }

    // Mutation: the workspace page leaves its cursor on a control that is
    // gone.
    @Test fun `the workspace page moves its cursor to the control that takes a pressed one's place`() {
        giveLlm()
        val cursor = mutableListOf<View?>()
        val (page, _) = openWorkspacePage(cursor)
        try {
            val askRow = row()
            tap(askRow)
            assertEquals("the X, while asking", close(), cursor.single())

            tap(close())
            assertEquals("the row, once put away", askRow, cursor.last())
            assertEquals(2, cursor.size)
        } finally {
            page.onDestroy()
        }
    }
}
