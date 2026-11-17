package com.playtranslate.ui

import android.app.Activity
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.playtranslate.R
import com.playtranslate.ask.AskPrompts
import com.playtranslate.ask.AskQuestion
import com.playtranslate.ask.AskSentence
import com.playtranslate.ask.AskState
import com.playtranslate.ask.Explanation
import com.playtranslate.ask.WordInSentence
import com.playtranslate.themeColor
import com.playtranslate.translation.SentenceAnswer
import com.playtranslate.translation.SentenceQuestion
import com.playtranslate.translation.TranslationBackend
import com.playtranslate.translation.TranslationBackendRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * [WordExplainGroup], the word page's "In this sentence" card, in a page
 * card on an Activity's window, driven by a fake AI model the test answers,
 * fails or leaves out, a recording host, and a switch for whether a model
 * exists. Written from the brief as a contract.
 *
 * The states, each in the words every surface draws a question in
 * ([askDisplay]): with nothing asked one row, the explain glyph and its
 * label; a tap asks the page's question ([AskQuestion.Word] about the
 * subject) and shows who is being asked; then the answer and who gave it,
 * the cut-off line first for an answer the service cut, the service's
 * out-of-scope line muted with no Try again, or the failure with Try
 * again, which asks the same question again. The X: while asking it
 * cancels, and a late answer writes nothing; on an answer it brings the row
 * back. With no model the tap is the host's alert and nothing is asked,
 * read at each tap. A carried answer is drawn at once, whole, with no
 * request. Release cancels a question in flight and nothing is written
 * afterwards. Every control is a tap target of at least 48dp that overlaps
 * no other; a pressed control that leaves the screen hands Android's focus
 * and the host's cursor to the one standing in its place. Colours are the
 * page's theme attributes.
 */
@RunWith(RobolectricTestRunner::class)
class WordExplainGroupTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    /** Everything the group told its host. */
    private class RecordingHost : WordExplainGroup.Host {
        var alerts = 0
        val gone = mutableListOf<View>()
        override fun onAskNeedsLlm() { alerts++ }
        override fun onControlGone(next: View) { gone += next }
    }

    /** A backend that is only its name: what an answer carries. */
    private class NamedBackend(override val displayName: String) : TranslationBackend {
        override val id: String = displayName
        override val priority: Int = 0
        override val requiresInternet: Boolean = true
        override val isDegradedFallback: Boolean = false
        override fun isUsable(source: String, target: String): Boolean = true
        override suspend fun translate(text: String, source: String, target: String): String = text
    }

    /** One question put to the fake model, whether its pass was to ask past
     *  the cooldowns (a retry's), and the gate the test answers it through;
     *  [cancelled] once its request saw its cancellation. */
    private class Call(val asked: SentenceQuestion, val pastCooldowns: Boolean) {
        val reply = CompletableDeferred<TranslationBackendRegistry.Completion>()
        var cancelled = false
    }

    private lateinit var controller: ActivityController<Host>
    private lateinit var activity: Host
    private lateinit var root: LinearLayout

    /** A focusable control above the card, as the page's header has: where
     *  Android puts a focus dropped on nothing, the first one in the
     *  window. */
    private lateinit var above: View
    private lateinit var card: LinearLayout
    private lateinit var group: WordExplainGroup
    private val host = RecordingHost()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val calls = mutableListOf<Call>()
    private val gemini = NamedBackend("Gemini")

    /** Whether a model exists, and how many times the group asked. */
    private var llm = true
    private var llmAsks = 0

    /** A model that cannot be stopped: its request waits on its gate past a
     *  cancel and then returns its answer as if nothing happened, so what
     *  keeps a cancelled question from being written is the group's own. */
    private var ignoreCancellation = false

    private val sentence = AskSentence("They ran out of the leaded room.", "en", "fr")
    private val subject = WordInSentence(sentence, "run", "rʌn", listOf("ran"))

    @Before fun setUp() {
        controller = Robolectric.buildActivity(Host::class.java).setup()
        activity = controller.get()
    }

    @After fun tearDown() {
        scope.cancel()
        controller.pause().stop().destroy()
        idle()
    }

    /** The question the group puts, as its title is the group's own. */
    private val question get() = AskQuestion.Word(
        "run", "rʌn", listOf("ran"), activity.getString(R.string.word_explain_title),
    )

    /** The group in a page card, as the page builds it, laid out. */
    private fun start(carried: Explanation? = null) {
        root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        above = View(activity).apply { isFocusable = true; isFocusableInTouchMode = true }
        root.addView(above, LinearLayout.LayoutParams(dp(48), dp(48)))
        card = PtGroupCard(activity)
        root.addView(card)
        activity.setContentView(root)
        group = WordExplainGroup(
            activity, card, scope, subject, carried, host,
            hasLlm = { llmAsks++; llm },
            complete = { asked, pastCooldowns, onAttempt ->
                val call = Call(asked, pastCooldowns)
                calls += call
                onAttempt(gemini)
                if (ignoreCancellation) {
                    withContext(NonCancellable) { call.reply.await() }
                } else {
                    try {
                        call.reply.await()
                    } catch (e: CancellationException) {
                        call.cancelled = true
                        throw e
                    }
                }
            },
        )
        settle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun settle() {
        idle()
        val dm = activity.resources.displayMetrics
        root.measure(
            MeasureSpec.makeMeasureSpec(dm.widthPixels, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(dm.heightPixels, MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, dm.widthPixels, dm.heightPixels)
    }

    private fun tap(v: View) {
        assertTrue("tapped while on screen: $v", v.isShown)
        v.performClick()
        settle()
    }

    private fun answer(text: String, cut: Boolean = false) {
        calls.last().reply.complete(TranslationBackendRegistry.Completion(SentenceAnswer.Text(text, cut), gemini))
        settle()
    }

    // ── The card's views, found by what they show ──────────────────────

    private fun all(v: View = card): List<View> =
        listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } }.orEmpty())

    /** The texts the card shows, in order. */
    private fun shownTexts(): List<String> =
        all().filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }

    private val label: TextView get() =
        all().filterIsInstance<TextView>().single { it.text == activity.getString(R.string.word_explain_ask) }

    /** The row with nothing asked: the label's tappable parent. */
    private val row: View get() = label.parent as View

    private val close: ImageButton get() =
        all().filterIsInstance<ImageButton>().single { it.contentDescription == activity.getString(R.string.cd_ask_close) }

    private val retry: TextView get() =
        all().filterIsInstance<TextView>().single { it.text == activity.getString(R.string.ask_retry) }

    /** The text that carries the question's state: the first text shown
     *  under a question (the status line, the answer, the failure). */
    private val body: TextView get() =
        all().filterIsInstance<TextView>().first { it.isShown && it !== retry }

    private fun footerOf(by: String, cut: Boolean = false): String {
        val answeredBy = activity.getString(R.string.ask_answered_by, by)
        return if (cut) activity.getString(R.string.ask_answer_cut) + "\n" + answeredBy else answeredBy
    }

    private fun color(attr: Int) = activity.themeColor(attr)

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    // ── Idle and asking ─────────────────────────────────────────────────

    @Test fun `with nothing asked the card is one row, the explain glyph and its label, in the accent`() {
        start()

        assertEquals(listOf(activity.getString(R.string.word_explain_ask)), shownTexts())
        assertFalse("no X with nothing asked", close.isShown)
        assertTrue("the row is the tap target", row.isClickable && row.isFocusable)
        val glyph = (row as ViewGroup).getChildAt(0) as android.widget.ImageView
        assertEquals(R.drawable.ic_ai_info, shadowOf(glyph.drawable).createdFromResId)
        assertEquals(color(R.attr.ptAccent), label.currentTextColor)
        assertEquals(color(R.attr.ptAccent), glyph.imageTintList!!.defaultColor)
        assertEquals("the question is about the subject's sentence", sentence, group.session.sentence)
    }

    // Mutations: ask another word, reading or form; ask about another
    // sentence or pair; ask nothing.
    @Test fun `a tap with a model asks the page's question about its sentence and shows who is being asked`() {
        start()

        tap(row)

        val asked = calls.single().asked
        val expected = AskPrompts.build(question, sentence)
        assertEquals(expected.user, asked.user)
        assertEquals(expected.system, asked.system)
        assertEquals("the parts a service that writes its own prompt is sent", expected, asked)
        assertEquals(AskState.Asking(question, "Gemini"), group.session.state.value)
        assertEquals(listOf(activity.getString(R.string.ask_status_asking, "Gemini")), shownTexts())
        assertTrue("the X is up while asking", close.isShown)
        assertFalse("the row is gone", row.isShown)
    }

    // ── What came of it ──────────────────────────────────────────────────

    @Test fun `an answer shows its text and who gave it, the X beside it`() {
        start()
        tap(row)

        answer("  Here it means to flee. ")

        assertEquals(listOf("Here it means to flee.", footerOf("Gemini")), shownTexts())
        assertEquals(color(R.attr.ptText), body.currentTextColor)
        assertTrue(close.isShown)
        assertFalse("no Try again under an answer", retry.isShown)
    }

    // Mutation: drop the cut-off line.
    @Test fun `an answer the service cut shows the cut-off line before who gave it`() {
        start()
        tap(row)

        answer("Here it means to fl", cut = true)

        assertEquals(listOf("Here it means to fl", footerOf("Gemini", cut = true)), shownTexts())
    }

    // Mutations: offer Try again; draw the line as an answer.
    @Test fun `out of scope is the service's line, muted, with no Try again`() {
        start()
        tap(row)

        calls.last().reply.complete(TranslationBackendRegistry.Completion(SentenceAnswer.OutOfScope, gemini))
        settle()

        assertEquals(listOf(activity.getString(R.string.ask_out_of_scope, "Gemini")), shownTexts())
        assertEquals(color(R.attr.ptTextMuted), body.currentTextColor)
        assertFalse(retry.isShown)
        assertTrue(close.isShown)
    }

    // Mutations: no Try again after a failure; Try again asks another
    // question, or nothing.
    @Test fun `a failure is muted with Try again, which asks the same question again`() {
        start()
        tap(row)

        calls.last().reply.completeExceptionally(java.io.IOException("no route"))
        settle()

        assertEquals(listOf(activity.getString(R.string.ask_failed), activity.getString(R.string.ask_retry)), shownTexts())
        assertEquals(color(R.attr.ptTextMuted), body.currentTextColor)
        assertEquals(color(R.attr.ptAccent), retry.currentTextColor)
        assertTrue(close.isShown)

        tap(retry)

        assertEquals("asked again", 2, calls.size)
        assertEquals(calls[0].asked, calls[1].asked)
        assertEquals("the retry's pass alone asks past the cooldowns", listOf(false, true), calls.map { it.pastCooldowns })
        assertEquals(AskState.Asking(question, "Gemini"), group.session.state.value)
        assertEquals(listOf(activity.getString(R.string.ask_status_asking, "Gemini")), shownTexts())
    }

    // ── The X ────────────────────────────────────────────────────────────

    // Mutations: the X leaves the request running; a late answer is shown.
    @Test fun `the X while asking cancels the request, and its late answer writes nothing`() {
        start()
        tap(row)

        tap(close)

        assertTrue("the request saw its cancellation", calls.single().cancelled)
        assertEquals(AskState.Idle, group.session.state.value)
        assertEquals("the row is back", listOf(activity.getString(R.string.word_explain_ask)), shownTexts())

        answer("Too late.")

        assertEquals(AskState.Idle, group.session.state.value)
        assertEquals(listOf(activity.getString(R.string.word_explain_ask)), shownTexts())

        // A model that returns its answer after the cancel all the same.
        ignoreCancellation = true
        tap(row)
        tap(close)
        answer("Too late as well.")

        assertEquals(AskState.Idle, group.session.state.value)
        assertEquals(listOf(activity.getString(R.string.word_explain_ask)), shownTexts())
    }

    @Test fun `the X on an answer brings the row back`() {
        start()
        tap(row)
        answer("Here it means to flee.")

        tap(close)

        assertEquals(AskState.Idle, group.session.state.value)
        assertEquals(listOf(activity.getString(R.string.word_explain_ask)), shownTexts())
        assertFalse(close.isShown)
    }

    // ── No model ─────────────────────────────────────────────────────────

    // Mutations: ask with no model; read the model once, not at the tap.
    @Test fun `with no model the tap is the host's alert, nothing is asked, and a model there at the next tap is asked`() {
        llm = false
        start()
        assertEquals("not asked before the tap", 0, llmAsks)

        tap(row)

        assertEquals(1, host.alerts)
        assertTrue("nothing asked", calls.isEmpty())
        assertEquals(AskState.Idle, group.session.state.value)
        assertEquals(listOf(activity.getString(R.string.word_explain_ask)), shownTexts())

        llm = true
        tap(row)

        assertEquals("the model is read at the tap", 2, llmAsks)
        assertEquals(1, calls.size)
        assertEquals("no second alert", 1, host.alerts)
    }

    // ── A carried answer ─────────────────────────────────────────────────

    // Mutations: drop the cut, or who gave it; ask for it again; draw it
    // only after a render the test does not cause.
    @Test fun `a carried answer is drawn at once, whole, with the X and no request`() {
        start(carried = Explanation("It is the past of run.", "Claude", cut = true))

        assertEquals(listOf("It is the past of run.", footerOf("Claude", cut = true)), shownTexts())
        assertTrue(close.isShown)
        assertTrue("nothing asked for it", calls.isEmpty())
        assertEquals(AskState.Answered(question, "It is the past of run.", "Claude", cut = true), group.session.state.value)
        assertEquals("about the page's sentence", sentence, group.session.sentence)

        tap(close)

        assertEquals(listOf(activity.getString(R.string.word_explain_ask)), shownTexts())
    }

    // ── Release ──────────────────────────────────────────────────────────

    // Mutations: release leaves the request running; a late answer is
    // written after it.
    @Test fun `release cancels a question in flight and nothing is written afterwards`() {
        start()
        tap(row)
        val drawn = shownTexts()

        group.release()
        idle()
        answer("Too late.")

        assertTrue("the request saw its cancellation", calls.single().cancelled)
        assertEquals(AskState.Idle, group.session.state.value)
        assertEquals("the card is drawn no more", drawn, shownTexts())
    }

    // Mutation: release cancels nothing, and an answer that comes anyway is
    // written.
    @Test fun `after release an answer that comes anyway is written nowhere`() {
        ignoreCancellation = true
        start()
        tap(row)
        val drawn = shownTexts()

        group.release()
        idle()
        answer("Too late.")

        assertEquals(AskState.Idle, group.session.state.value)
        assertEquals(drawn, shownTexts())
    }

    // ── Tap targets and the controller ───────────────────────────────────

    /** [v]'s rect in the card's coordinates. */
    private fun rectIn(v: View): android.graphics.Rect {
        val r = android.graphics.Rect(0, 0, v.width, v.height)
        card.offsetDescendantRectToMyCoords(v, r)
        return r
    }

    // Mutations: a target under 48dp; the X over the text.
    @Test fun `every control is a tap target of at least 48dp that overlaps no other`() {
        start()
        assertTrue("the row ${row.height}px", row.height >= dp(48))

        tap(row)
        calls.last().reply.completeExceptionally(java.io.IOException("no route"))
        settle()

        assertTrue("the X ${close.width}x${close.height}px", close.width >= dp(48) && close.height >= dp(48))
        assertTrue("Try again ${retry.width}x${retry.height}px", retry.width >= dp(48) && retry.height >= dp(48))
        assertFalse("the X and Try again apart", android.graphics.Rect.intersects(rectIn(close), rectIn(retry)))
        assertFalse("the X and the text apart", android.graphics.Rect.intersects(rectIn(close), rectIn(body)))
        assertFalse("Try again and the text apart", android.graphics.Rect.intersects(rectIn(retry), rectIn(body)))
    }

    // Mutations: drop the focus on nothing; tell the host of no control, or
    // of the one pressed.
    @Test fun `a pressed control that leaves the screen hands the focus and the cursor to the one in its place`() {
        start()
        // This environment's windows are in touch mode, where a button takes
        // no focus; a controller's are not.
        for (v in listOf(row, close, retry)) v.isFocusableInTouchMode = true
        assertTrue("precondition: the row holds the focus", row.requestFocus() && row.isFocused)

        tap(row)
        assertTrue("the X has it while asking", close.isFocused)
        assertSame(close, host.gone.last())

        calls.last().reply.completeExceptionally(java.io.IOException("no route"))
        settle()
        assertTrue("a state that moved by itself keeps it where it was", close.isFocused)
        assertEquals("and tells the host nothing", 1, host.gone.size)

        assertTrue(retry.requestFocus())
        tap(retry)
        assertTrue("Try again gives way to the X", close.isFocused)
        assertSame(close, host.gone.last())

        tap(close)
        assertTrue("the X gives way to the row", row.isFocused)
        assertSame(row, host.gone.last())
    }

    @Test fun `with no model the row stays, and so do the focus and the cursor`() {
        llm = false
        start()
        row.isFocusableInTouchMode = true
        assertTrue(row.requestFocus())

        tap(row)

        assertTrue(row.isFocused)
        assertTrue("nothing left the screen", host.gone.isEmpty())
    }

    @Test fun `the controls are what a controller walks, and a click is what activates them`() {
        start()
        assertNotNull("the row is a clickable target", row.takeIf { it.isClickable })
        tap(row)
        assertTrue("the X is a clickable target", close.isClickable)
        calls.last().reply.completeExceptionally(java.io.IOException("no route"))
        settle()
        assertTrue("Try again is a clickable target", retry.isClickable && retry.isFocusable)
    }
}
