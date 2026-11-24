package com.playtranslate.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.ask.AskPresets
import com.playtranslate.ask.AskQuestion
import com.playtranslate.ask.AskSentence
import com.playtranslate.ask.AskSession
import com.playtranslate.ask.AskState
import com.playtranslate.themeColor
import com.playtranslate.translation.BackendFailure
import com.playtranslate.translation.BackendFailureKind
import com.playtranslate.translation.CompletionFailedException
import com.playtranslate.translation.OpenAiRateLimitException
import com.playtranslate.translation.SentenceAnswer
import com.playtranslate.translation.TranslationBackend
import com.playtranslate.translation.TranslationBackendRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
 * Pins [AskSectionBinder] on the real results page (fragment_translation_result.xml,
 * attached to an Activity's window and laid out at the display's size), driven by
 * a real [AskSession] whose LLM is a fake the test answers or fails, a recording
 * [AskSectionBinder.Host], and a switch for whether an LLM exists.
 *
 * The visibility table, row by row: the card, which of its groups shows, the
 * eye's icon and the label's colour, and what a tap on the eye does to the pref
 * (whether it is WRITTEN at all, read from a recording preferences store, not
 * just its value) and to the session: the eye over a question shelves it and
 * never ends it, so the same eye brings the answer back, an answer that
 * arrives while shelved stays out of sight, and a pill asked over a shelved
 * question takes its place. A question asked from the collapsed header
 * shows its answer without writing the pref, and X collapses the section again.
 * The header's pills: offered only while collapsed with nothing asked, in
 * [AskPresets.ordered]'s order, never with ⋯, and dropped (the label whole and
 * the eye kept) in a row too narrow for them. With no LLM, every way to a
 * question (a chip, a pill, a touch on the field, the controller's A on it, a
 * send) alerts the host once and does nothing else. With one, a chip asks its
 * preset, which leads the recency from then on, and the card follows the
 * session to the answer and who gave it. The question's look: its title in the
 * hint colour, italic exactly when every letter is Latin, in a row as tall as
 * the chips' with no hairline under it, "Asking <service>…" under it (the next
 * service when the pass falls through, no name before one is picked), and
 * "Answered by" styled as the Translation card's note. The answer's Text size:
 * offered exactly while a question is on screen, opening the one-handle picker
 * on the surface's popover host as an interaction, and the answer's text at
 * the saved size, live under the picker and afresh at a render; a typed question goes trimmed, by the
 * button or the IME's Send, never blank, and writes no pref. A field that goes
 * out of sight (a chip tapped mid-typing, the eye) takes its keyboard with it
 * and keeps its draft. A failure shows the
 * generic line, with the service's reason under it when the session names one,
 * and Try again asks the same question. The spoiler line follows the
 * Translation section's pref over a question and never shows with nothing
 * asked. The host hears of every render, every eye tap and the field's focus.
 * The controller's targets in each state, and the chips' order after a preset
 * was asked.
 */
@RunWith(RobolectricTestRunner::class)
class AskSectionBinderTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    /** Everything the binder told its host. */
    private class RecordingHost : AskSectionBinder.Host {
        var interactions = 0
        var llmNeeded = 0
        var heightChanges = 0
        val focusChanges = mutableListOf<Boolean>()

        override fun onInteraction() { interactions++ }
        override fun onLlmNeeded() { llmNeeded++ }
        override fun onFieldFocusChanged(focused: Boolean) { focusChanges += focused }
        override fun onHeightChanged() { heightChanges++ }
    }

    /** The app's preferences, with every write made through them recorded by
     *  key, so a test can tell a write that changes nothing from no write. */
    private class RecordingStore(private val real: SharedPreferences) : SharedPreferences by real {
        val writes = mutableListOf<String>()

        override fun edit(): SharedPreferences.Editor = Recorder(real.edit())

        private inner class Recorder(private val editor: SharedPreferences.Editor) :
            SharedPreferences.Editor by editor {
            private fun wrote(key: String?, write: () -> Unit): SharedPreferences.Editor {
                writes += key.toString()
                write()
                return this
            }

            override fun putString(key: String?, value: String?) = wrote(key) { editor.putString(key, value) }
            override fun putStringSet(key: String?, values: MutableSet<String>?) =
                wrote(key) { editor.putStringSet(key, values) }
            override fun putInt(key: String?, value: Int) = wrote(key) { editor.putInt(key, value) }
            override fun putLong(key: String?, value: Long) = wrote(key) { editor.putLong(key, value) }
            override fun putFloat(key: String?, value: Float) = wrote(key) { editor.putFloat(key, value) }
            override fun putBoolean(key: String?, value: Boolean) = wrote(key) { editor.putBoolean(key, value) }
            override fun remove(key: String?) = wrote(key) { editor.remove(key) }
            override fun clear() = wrote(null) { editor.clear() }
        }
    }

    /** Opens [store] for whoever asks it for preferences ([Prefs]). */
    private class StoreContext(base: Context, private val store: RecordingStore) : ContextWrapper(base) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = store
    }

    /** A backend that is only its name: what an answer and a failure carry. */
    private class NamedBackend(override val displayName: String, online: Boolean) : TranslationBackend {
        override val id: String = displayName
        override val priority: Int = 0
        override val requiresInternet: Boolean = online
        override val isDegradedFallback: Boolean = false
        override fun isUsable(source: String, target: String): Boolean = true
        override suspend fun translate(text: String, source: String, target: String): String = text
    }

    /** One question put to the fake LLM: the prompt it got, the session's
     *  naming of who is asked (through which a test has the pass fall
     *  through to another service), whether its pass was to ask past the
     *  cooldowns (a retry's), and the gate the test answers or fails it
     *  through. It stays in flight until then. */
    private class Call(
        val user: String,
        val attempt: (TranslationBackend) -> Unit,
        val pastCooldowns: Boolean,
    ) {
        val reply = CompletableDeferred<TranslationBackendRegistry.Completion>()
    }

    private val app: Context = ApplicationProvider.getApplicationContext()
    private lateinit var controller: ActivityController<Host>
    private lateinit var activity: Host
    private lateinit var store: RecordingStore
    private lateinit var prefs: Prefs
    private lateinit var root: View
    private lateinit var session: AskSession
    private lateinit var binder: AskSectionBinder

    /** The page's popover host, as the results page gives the binder one. */
    private lateinit var popovers: PopoverHost
    private val host = RecordingHost()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Whether the user has an LLM: what the binder's hasLlm reads. */
    private var llm = true

    /** How many times the binder asked. */
    private var llmAsks = 0

    /** Every question the fake LLM was asked, in order. */
    private val calls = mutableListOf<Call>()

    /** Every state the host's collection handed the binder, in order. */
    private val rendered = mutableListOf<AskState>()

    private val gemini = NamedBackend("Gemini", online = true)

    @Before
    fun setUp() {
        clearPrefs()
        controller = Robolectric.buildActivity(Host::class.java).setup()
        activity = controller.get()
        store = RecordingStore(app.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE))
        prefs = Prefs(StoreContext(app, store))
        root = LayoutInflater.from(activity).inflate(R.layout.fragment_translation_result, null)
        // The page shows its sections once a result is up.
        root.findViewById<View>(R.id.statusContainer).visibility = View.GONE
        root.findViewById<View>(R.id.resultsContent).visibility = View.VISIBLE
        activity.setContentView(root)
        // As the registry's pass does, the fake names its first pick
        // (Gemini) before it first waits.
        session = AskSession(scope) { asked, pastCooldowns, onAttempt ->
            val call = Call(asked.user, onAttempt, pastCooldowns)
            calls += call
            onAttempt(gemini)
            call.reply.await()
        }
        session.bind(AskSentence("猫が魚を食べた。", "ja", "en"))
    }

    @After
    fun tearDown() {
        scope.cancel()
        controller.pause().stop().destroy()
        idle()
        clearPrefs()
    }

    /** Build the binder the way a host does, with the session's state
     *  collected into [AskSectionBinder.render] from here on, and lay the page
     *  out. The prefs a test sets before this are the binder's start. */
    private fun start() {
        popovers = PopoverHost(root as FrameLayout)
        binder = AskSectionBinder(root, activity, prefs, session, popovers, host, hasLlm = { llmAsks++; llm })
        scope.launch {
            session.state.collect {
                rendered += it
                binder.render(it)
            }
        }
        settle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Run what is posted, then one layout pass of the page at the display's
     *  size: a header decides which of its available actions show while it
     *  measures, so every read of the header follows one. */
    private fun settle() {
        idle()
        val dm = activity.resources.displayMetrics
        root.measure(
            MeasureSpec.makeMeasureSpec(dm.widthPixels, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(dm.heightPixels, MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, dm.widthPixels, dm.heightPixels)
    }

    /** A tap on [v], which must be on screen. */
    private fun tap(v: View) {
        assertTrue("tapped while on screen: $v", v.isShown)
        v.performClick()
        settle()
    }

    /** A finger's quick tap on the question field: down and up at its centre. */
    private fun touchField() {
        val t = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val e = MotionEvent.obtain(t, t, action, field.width / 2f, field.height / 2f, 0)
            field.dispatchTouchEvent(e)
            e.recycle()
        }
        settle()
    }

    private fun answer(text: String, by: TranslationBackend = gemini) {
        calls.last().reply.complete(TranslationBackendRegistry.Completion(SentenceAnswer.Text(text), by))
        settle()
    }

    private fun fail(e: Throwable) {
        calls.last().reply.completeExceptionally(e)
        settle()
    }

    private fun <T : View> view(id: Int): T = root.findViewById(id)
    private val header: SectionHeaderRow get() = view(R.id.askHeaderRow)
    private val label: TextView get() = view(R.id.labelAsk)
    private val eye: ImageButton get() = view(R.id.btnToggleAsk)
    private val textSizeButton: ImageButton get() = view(R.id.btnFontSizeAsk)
    private val more: View get() = view(R.id.btnMoreAsk)
    private val card: View get() = view(R.id.cardAsk)
    private val idleGroup: View get() = view(R.id.askIdle)
    private val answerGroup: View get() = view(R.id.askAnswer)
    private val field: EditText get() = view(R.id.etAskQuestion)
    private val send: ImageButton get() = view(R.id.btnAskSend)
    private val close: ImageButton get() = view(R.id.btnAskClose)
    private val retry: View get() = view(R.id.btnAskRetry)
    private val title: TextView get() = view(R.id.tvAskTitle)
    private val body: TextView get() = view(R.id.tvAskBody)
    private val footer: TextView get() = view(R.id.tvAskFooter)
    private val warning: View get() = view(R.id.tvAskWarning)

    /** The card's chips, in order. */
    private fun chips(): List<TextView> {
        val row = view<ViewGroup>(R.id.askPresetChips)
        return (0 until row.childCount).map { row.getChildAt(it) as TextView }
    }

    /** The header's question pills (its worded actions), in order, shown or not. */
    private fun pills(): List<TextView> =
        (0 until header.childCount).map { header.getChildAt(it) }
            .filterIsInstance<TextView>()
            .filter { it !== label }

    private fun shown(v: View) = v.visibility == View.VISIBLE

    /** The drawable the eye shows. */
    private fun eyeIcon(): Int = shadowOf(eye.drawable).createdFromResId

    private fun color(attr: Int) = activity.themeColor(attr)

    /** [value] sp in px, as a TextView's textSize reports it. */
    private fun sp(value: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value.toFloat(), activity.resources.displayMetrics)

    /** A finger on [track]: down at the first x, moved through the rest, and
     *  up at the last. */
    private fun drag(track: View, vararg xs: Float) {
        val t = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float) {
            val e = MotionEvent.obtain(t, t, action, x, track.height / 2f, 0)
            track.dispatchTouchEvent(e)
            e.recycle()
        }
        send(MotionEvent.ACTION_DOWN, xs.first())
        for (x in xs.drop(1)) send(MotionEvent.ACTION_MOVE, x)
        send(MotionEvent.ACTION_UP, xs.last())
        settle()
    }

    /** Whether [v] draws in italic, read through the typeface's style:
     *  under these tests' Robolectric, isItalic answered false for the
     *  italic typeface whose style says italic. */
    private fun italic(v: TextView) = ((v.typeface?.style ?: Typeface.NORMAL) and Typeface.ITALIC) != 0

    private fun ordered() = AskPresets.ordered(activity, prefs)

    private fun navViews(): List<View> = binder.navigableActions().map { it.view }

    // ── The visibility table, row by row ────────────────────────────────

    @Test
    fun `row 1, shown with nothing asked, is the questions under an open eye, whose tap writes the pref hidden`() {
        start()
        assertFalse("precondition: the pref shows the section", prefs.hideAskSection)
        assertEquals(AskState.Idle, session.state.value)
        assertEquals(View.VISIBLE, card.visibility)
        assertTrue(shown(idleGroup))
        assertFalse(shown(answerGroup))
        assertEquals(R.drawable.ic_visibility, eyeIcon())
        assertEquals(color(R.attr.ptTextMuted), label.currentTextColor)
        assertFalse(binder.isHidden)

        store.writes.clear()
        tap(eye)
        assertTrue(prefs.hideAskSection)
        assertEquals("the tap wrote the pref, once", 1, store.writes.size)
        assertEquals(AskState.Idle, session.state.value)
        assertEquals(View.GONE, card.visibility)
        assertEquals(R.drawable.ic_visibility_off, eyeIcon())
        assertEquals(color(R.attr.ptTextHint), label.currentTextColor)
    }

    @Test
    fun `row 2, shown with a question on screen, is the answer under an open eye, whose tap writes the pref and shelves the question`() {
        start()
        tap(chips().first())
        answer("Ga marks the cat as the one who ate.")
        assertFalse("precondition: the pref shows the section", prefs.hideAskSection)
        val answered = session.state.value
        assertTrue(answered is AskState.Answered)
        assertEquals(View.VISIBLE, card.visibility)
        assertFalse(shown(idleGroup))
        assertTrue(shown(answerGroup))
        assertEquals(R.drawable.ic_visibility, eyeIcon())
        assertEquals(color(R.attr.ptTextMuted), label.currentTextColor)

        store.writes.clear()
        tap(eye)
        assertTrue(prefs.hideAskSection)
        assertEquals("the tap wrote the pref, once", 1, store.writes.size)
        assertEquals("the answer stands", answered, session.state.value)
        assertTrue("shelved", session.shelved)
        assertTrue(binder.isHidden)
        assertEquals(View.GONE, card.visibility)
        assertEquals(R.drawable.ic_visibility_off, eyeIcon())
        assertEquals(color(R.attr.ptTextHint), label.currentTextColor)
        assertTrue("nobody was asked again", calls.size == 1)
    }

    @Test
    fun `row 3, collapsed with nothing asked, is the header alone under a crossed eye, whose tap writes the pref shown`() {
        prefs.hideAskSection = true
        start()
        assertEquals(AskState.Idle, session.state.value)
        assertEquals(View.GONE, card.visibility)
        assertEquals(R.drawable.ic_visibility_off, eyeIcon())
        assertEquals(color(R.attr.ptTextHint), label.currentTextColor)
        assertTrue(binder.isHidden)

        store.writes.clear()
        tap(eye)
        assertFalse(prefs.hideAskSection)
        assertEquals("the tap wrote the pref, once", 1, store.writes.size)
        assertEquals(AskState.Idle, session.state.value)
        assertEquals(View.VISIBLE, card.visibility)
        assertTrue(shown(idleGroup))
        assertFalse(shown(answerGroup))
        assertEquals(R.drawable.ic_visibility, eyeIcon())
        assertEquals(color(R.attr.ptTextMuted), label.currentTextColor)
    }

    @Test
    fun `row 4, collapsed with a question on screen, is the answer under an open eye, whose tap shelves the question and writes nothing`() {
        prefs.hideAskSection = true
        start()
        tap(pills().first())
        answer("Ga marks the cat as the one who ate.")
        assertTrue("precondition: the pref collapses the section", prefs.hideAskSection)
        val answered = session.state.value
        assertTrue(answered is AskState.Answered)
        assertFalse("precondition: on show", session.shelved)
        assertEquals(View.VISIBLE, card.visibility)
        assertFalse(shown(idleGroup))
        assertTrue(shown(answerGroup))
        assertEquals(R.drawable.ic_visibility, eyeIcon())
        assertEquals(color(R.attr.ptTextMuted), label.currentTextColor)
        assertFalse(binder.isHidden)

        store.writes.clear()
        tap(eye)
        assertTrue("the pref stays hidden", prefs.hideAskSection)
        assertEquals("and the tap wrote no pref at all", emptyList<String>(), store.writes)
        assertEquals("the answer stands", answered, session.state.value)
        assertTrue("shelved", session.shelved)
        assertTrue(binder.isHidden)
        assertEquals(View.GONE, card.visibility)
        assertEquals(R.drawable.ic_visibility_off, eyeIcon())
        assertEquals(color(R.attr.ptTextHint), label.currentTextColor)
    }

    @Test
    fun `row 5, collapsed over a shelved question, is the header alone under a crossed eye, whose tap writes the pref shown and brings the answer back`() {
        start()
        tap(chips().first())
        answer("Ga marks the cat as the one who ate.")
        val answered = session.state.value
        tap(eye)
        assertTrue("precondition: the pref collapses the section", prefs.hideAskSection)
        assertTrue("precondition: shelved", session.shelved)
        assertEquals(View.GONE, card.visibility)
        assertEquals(R.drawable.ic_visibility_off, eyeIcon())
        assertEquals(color(R.attr.ptTextHint), label.currentTextColor)
        assertTrue("the pills are offered over it", shown(pills().first()))
        assertFalse("and no Text size, with no text on screen to size", shown(textSizeButton))

        store.writes.clear()
        tap(eye)
        assertFalse(prefs.hideAskSection)
        assertEquals("the tap wrote the pref, once", 1, store.writes.size)
        assertEquals("the same answer", answered, session.state.value)
        assertFalse("on show again", session.shelved)
        assertEquals(View.VISIBLE, card.visibility)
        assertFalse(shown(idleGroup))
        assertTrue(shown(answerGroup))
        assertEquals("Ga marks the cat as the one who ate.", body.text.toString())
        assertEquals(R.drawable.ic_visibility, eyeIcon())
        assertEquals(color(R.attr.ptTextMuted), label.currentTextColor)
        assertTrue("the Text size is back with the text", shown(textSizeButton))
        assertTrue("nobody was asked again", calls.size == 1)
    }

    // ── A shelved question ──────────────────────────────────────────────

    @Test
    fun `a question hidden while in flight goes on, and its answer arrives out of sight, there when the section is shown`() {
        start()
        tap(chips().first())
        assertTrue("precondition: in flight", session.state.value is AskState.Asking)

        tap(eye)
        assertTrue("the request goes on", session.state.value is AskState.Asking)
        assertEquals(View.GONE, card.visibility)

        answer("Ga marks the cat as the one who ate.")
        assertTrue(session.state.value is AskState.Answered)
        assertTrue("the user's hide stands", prefs.hideAskSection)
        assertTrue(session.shelved)
        assertEquals("the answer did not bring the card back", View.GONE, card.visibility)
        assertEquals(R.drawable.ic_visibility_off, eyeIcon())
        assertFalse(shown(textSizeButton))

        tap(eye)
        assertEquals(View.VISIBLE, card.visibility)
        assertTrue(shown(answerGroup))
        assertEquals("Ga marks the cat as the one who ate.", body.text.toString())
    }

    @Test
    fun `a failure that arrives out of sight stays there, with Try again when the section is shown`() {
        start()
        tap(chips().first())
        tap(eye)

        fail(CompletionFailedException(null, null))
        assertTrue(session.state.value is AskState.Failed)
        assertEquals(View.GONE, card.visibility)

        tap(eye)
        assertEquals(View.VISIBLE, card.visibility)
        assertTrue(shown(retry))
    }

    @Test
    fun `a pill asked over a shelved question takes its place, on show, without writing the pref`() {
        start()
        tap(chips().first())
        answer("Ga marks the cat as the one who ate.")
        tap(eye)
        assertTrue("precondition: shelved", session.shelved)

        store.writes.clear()
        tap(pills().first(::shown))
        assertTrue("a new question", session.state.value is AskState.Asking)
        assertEquals("asked of the LLM", 2, calls.size)
        assertFalse("on show", session.shelved)
        assertTrue("the pref still hides the section", prefs.hideAskSection)
        assertFalse("and was not written", "hide_ask_section" in store.writes)
        assertEquals(View.VISIBLE, card.visibility)
        assertTrue(shown(answerGroup))

        answer("A second answer.")
        assertEquals("A second answer.", body.text.toString())
        tap(close)
        assertEquals("X collapses the section again", View.GONE, card.visibility)
        assertEquals(AskState.Idle, session.state.value)
    }

    @Test
    fun `a binder built over a session with a shelved answer starts collapsed, and the eye shows the answer`() {
        prefs.hideAskSection = true
        session.restore(session.sentence!!, AskState.Answered(AskQuestion.Custom("Why ga?"), "Because.", "Gemini"), shelved = true)
        start()
        assertEquals(View.GONE, card.visibility)
        assertEquals(R.drawable.ic_visibility_off, eyeIcon())

        tap(eye)
        assertEquals(View.VISIBLE, card.visibility)
        assertEquals("Because.", body.text.toString())
    }

    // ── A question from the collapsed header ────────────────────────────

    @Test
    fun `a question asked from the collapsed header shows its answer without writing the pref, and X collapses the section again`() {
        prefs.hideAskSection = true
        start()
        val preset = ordered().first()
        assertEquals("precondition: collapsed", View.GONE, card.visibility)

        store.writes.clear()
        tap(pills().first())
        // In flight.
        assertTrue(prefs.hideAskSection)
        assertEquals(AskState.Asking(AskQuestion.Preset(preset), "Gemini"), session.state.value)
        assertEquals(View.VISIBLE, card.visibility)
        assertTrue(shown(answerGroup))
        assertFalse(shown(idleGroup))
        assertEquals(activity.getString(R.string.ask_status_asking, "Gemini"), body.text.toString())
        assertTrue("the pills go while the card is up", pills().none(::shown))
        // The one write was the recency's (it changed); none was the pref's.
        assertEquals(1, store.writes.size)
        assertEquals(listOf(preset.id), prefs.askPresetRecency)

        answer("Ga marks the cat as the one who ate.")
        assertTrue(prefs.hideAskSection)
        assertEquals(View.VISIBLE, card.visibility)
        assertEquals("Ga marks the cat as the one who ate.", body.text.toString())

        val interactions = host.interactions
        tap(close)
        assertEquals("X is an interaction", interactions + 1, host.interactions)
        assertEquals(AskState.Idle, session.state.value)
        assertTrue(prefs.hideAskSection)
        assertEquals(View.GONE, card.visibility)
        assertEquals(R.drawable.ic_visibility_off, eyeIcon())
        assertTrue("the pills are back", shown(pills().first()))
        assertEquals("X writes no pref", 1, store.writes.size)
    }

    // ── The header's pills ──────────────────────────────────────────────

    @Test
    fun `the header offers the presets as pills, in their order, only while collapsed with nothing asked`() {
        prefs.hideAskSection = true
        start()
        val titles = ordered().map { it.title }
        assertEquals(
            "the one built-in preset today",
            listOf(activity.getString(R.string.ask_preset_grammar_breakdown)), titles,
        )
        assertEquals("one slot per preset", titles.size, pills().size)
        assertEquals("every pill fits the page, in order", titles, pills().filter(::shown).map { it.text.toString() })
        assertEquals(View.GONE, more.visibility)
        assertTrue(shown(eye))

        // A question on screen keeps the card up, and the pills go.
        tap(pills().first())
        assertTrue(pills().none(::shown))
        tap(close)
        assertEquals(titles, pills().filter(::shown).map { it.text.toString() })

        // Expanded, the header is its label and its eye.
        tap(eye)
        assertFalse(prefs.hideAskSection)
        assertTrue(pills().none(::shown))
        assertEquals(View.GONE, more.visibility)
        val items = (0 until header.childCount).map { header.getChildAt(it) }.filter(::shown)
            .filter { it !is android.widget.Space }
        assertEquals(listOf(label, eye), items)
    }

    @Test
    fun `more is never shown, and a row too narrow for a pill drops it, keeping the label whole and the eye`() {
        prefs.hideAskSection = true
        start()
        val pill = pills().first()
        assertTrue("precondition: the pill fits the page", shown(pill))
        assertEquals(View.GONE, more.visibility)

        // Room for the label, the eye and the gap between them, and one gap
        // more, which no pill fits in (a pill is wider than its own padding).
        val gap = (16 * activity.resources.displayMetrics.density).toInt()
        label.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        val row = header.paddingLeft + label.measuredWidth + gap + eye.layoutParams.width + gap +
            header.paddingRight
        header.measure(
            MeasureSpec.makeMeasureSpec(row, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        header.layout(0, 0, row, header.measuredHeight)
        assertEquals("the pill is not shown", View.GONE, pill.visibility)
        assertEquals("nor is more", View.GONE, more.visibility)
        assertTrue("the eye is", shown(eye))
        assertTrue("and the label", shown(label))
        assertEquals("whole", Int.MAX_VALUE, label.maxWidth)

        // Back at the page's width, the pill returns.
        settle()
        assertTrue(shown(pill))
        assertEquals(View.GONE, more.visibility)
    }

    // ── No LLM: the host's alert, and nothing else ──────────────────────

    /** Nothing came of reaching for a question but the host's alert: one
     *  onLlmNeeded with its interaction, no question put to the LLM, the
     *  session never off Idle, no pref written, and no focus in the field. */
    private fun assertOnlyAlerted(what: String) {
        assertEquals("$what alerts once", 1, host.llmNeeded)
        assertEquals("$what is one interaction", 1, host.interactions)
        assertTrue("$what asks the LLM nothing", calls.isEmpty())
        assertTrue("$what leaves the session Idle", rendered.all { it == AskState.Idle })
        assertEquals("$what writes no pref", emptyList<String>(), store.writes)
        assertTrue("$what leaves the recency empty", prefs.askPresetRecency.isEmpty())
        assertFalse("$what gives the field no focus", field.isFocused)
    }

    @Test
    fun `with no LLM a chip only alerts the host`() {
        llm = false
        start()
        store.writes.clear()
        tap(chips().first())
        assertOnlyAlerted("a chip")
    }

    @Test
    fun `with no LLM a header pill only alerts the host`() {
        llm = false
        prefs.hideAskSection = true
        start()
        store.writes.clear()
        tap(pills().first())
        assertOnlyAlerted("a pill")
        assertEquals("still collapsed", View.GONE, card.visibility)
    }

    @Test
    fun `with no LLM a touch on the field only alerts the host, and the field takes no focus`() {
        llm = false
        start()
        assertFalse("precondition: no focus", field.isFocused)
        store.writes.clear()
        touchField()
        assertOnlyAlerted("a touch on the field")
        assertTrue("no focus change reached the host", host.focusChanges.isEmpty())
    }

    @Test
    fun `whether a touch on the field is the field's is decided once, where the touch begins`() {
        llm = false
        start()
        llmAsks = 0
        val t0 = SystemClock.uptimeMillis()
        fun send(action: Int, y: Float) {
            val e = MotionEvent.obtain(t0, t0, action, field.width / 2f, y, 0)
            field.dispatchTouchEvent(e)
            e.recycle()
        }
        // A drag that starts on the field: one question for all of it.
        send(MotionEvent.ACTION_DOWN, field.height / 2f)
        for (i in 1..5) send(MotionEvent.ACTION_MOVE, field.height / 2f + i)
        assertEquals("asked at the down, and not again at each move", 1, llmAsks)
        send(MotionEvent.ACTION_UP, field.height / 2f + 5)
        settle()
        assertEquals("its end is the click, which asks for itself", 2, llmAsks)
        assertEquals(1, host.llmNeeded)
        assertFalse(field.isFocused)
        // The next touch is asked afresh: with an LLM it is the field's.
        llm = true
        touchField()
        assertTrue(field.isFocused)
    }

    @Test
    fun `with no LLM the controller's A on the field only alerts the host`() {
        llm = false
        start()
        store.writes.clear()
        binder.navigableActions().single { it.view === field }.activate()
        settle()
        assertOnlyAlerted("A on the field")
    }

    @Test
    fun `with no LLM a send only alerts the host, and keeps the question`() {
        llm = false
        start()
        field.setText("Why ga and not wa?")
        store.writes.clear()
        tap(send)
        assertOnlyAlerted("a send")
        assertEquals("the question is kept for later", "Why ga and not wa?", field.text.toString())
    }

    // ── With an LLM ─────────────────────────────────────────────────────

    @Test
    fun `a chip with an LLM asks its preset, puts it first in the recency, and the card follows the session to the answer`() {
        assertNotEquals(color(R.attr.ptText), color(R.attr.ptTextHint))
        prefs.askPresetRecency = listOf("a_preset_since_removed")
        start()
        val preset = ordered().first()
        tap(chips().first())
        assertEquals(1, host.interactions)
        assertEquals(0, host.llmNeeded)
        assertEquals("first in the recency", listOf(preset.id, "a_preset_since_removed"), prefs.askPresetRecency)
        assertEquals(AskState.Asking(AskQuestion.Preset(preset), "Gemini"), session.state.value)
        assertEquals(1, calls.size)
        assertTrue(shown(answerGroup))
        assertFalse(shown(idleGroup))
        assertEquals(preset.title, title.text.toString())
        assertEquals(color(R.attr.ptTextHint), title.currentTextColor)
        assertEquals(activity.getString(R.string.ask_status_asking, "Gemini"), body.text.toString())
        assertEquals(color(R.attr.ptText), body.currentTextColor)
        assertFalse(shown(footer))
        assertFalse(shown(retry))
        assertEquals("X is tinted in code", color(R.attr.ptTextMuted), close.imageTintList?.defaultColor)

        answer("Ga marks the cat as the one who ate.", by = gemini)
        assertEquals(
            AskState.Answered(AskQuestion.Preset(preset), "Ga marks the cat as the one who ate.", "Gemini"),
            session.state.value,
        )
        assertEquals(preset.title, title.text.toString())
        assertEquals("Ga marks the cat as the one who ate.", body.text.toString())
        assertEquals(color(R.attr.ptText), body.currentTextColor)
        assertTrue(shown(footer))
        assertEquals(activity.getString(R.string.ask_answered_by, "Gemini"), footer.text.toString())
        assertFalse(shown(retry))
    }

    @Test
    fun `the send button is enabled exactly while the field holds more than blanks`() {
        assertNotEquals(color(R.attr.ptAccent), color(R.attr.ptTextHint))
        start()
        for ((text, sendable) in listOf("" to false, "   " to false, "why" to true, "  why  " to true, "" to false)) {
            field.setText(text)
            assertEquals("'$text'", sendable, send.isEnabled)
            assertEquals(
                "'$text'", color(if (sendable) R.attr.ptAccent else R.attr.ptTextHint),
                send.imageTintList?.defaultColor,
            )
        }
    }

    @Test
    fun `a send asks the trimmed question and empties the field, by the button or the IME's Send, and a blank one sends nothing`() {
        prefs.askPresetRecency = listOf(AskPresets.GRAMMAR_BREAKDOWN)
        start()
        val imm = activity.getSystemService(InputMethodManager::class.java)

        // Blank: nothing, whichever way it is sent (a click reaches a
        // disabled button's handler too).
        field.setText("   ")
        send.performClick()
        field.onEditorAction(EditorInfo.IME_ACTION_SEND)
        settle()
        assertTrue(calls.isEmpty())
        assertEquals(AskState.Idle, session.state.value)
        assertEquals("a blank send is no interaction", 0, host.interactions)

        // The button. The keyboard is read rather than the focus alone: a
        // group that stops showing drops its field's focus by itself, and
        // leaves the keyboard up.
        field.requestFocus()
        imm.showSoftInput(field, 0)
        field.setText("  What does tabeta mean?  ")
        store.writes.clear()
        assertTrue("precondition: typing, keyboard up", field.isFocused && shadowOf(imm).isSoftInputVisible)
        tap(send)
        assertEquals(AskState.Asking(AskQuestion.Custom("What does tabeta mean?"), "Gemini"), session.state.value)
        assertEquals("What does tabeta mean?", title.text.toString())
        assertTrue(calls.single().user.contains("What does tabeta mean?"))
        assertEquals("", field.text.toString())
        assertFalse(field.isFocused)
        assertFalse("the keyboard is down", shadowOf(imm).isSoftInputVisible)
        assertEquals("taking the field's focus, then the send", 2, host.interactions)
        assertEquals("a typed question writes no pref", emptyList<String>(), store.writes)
        assertEquals(listOf(AskPresets.GRAMMAR_BREAKDOWN), prefs.askPresetRecency)

        // The IME's Send.
        tap(close)
        field.requestFocus()
        imm.showSoftInput(field, 0)
        field.setText(" Is it polite? ")
        store.writes.clear()
        assertTrue("precondition: typing, keyboard up", field.isFocused && shadowOf(imm).isSoftInputVisible)
        field.onEditorAction(EditorInfo.IME_ACTION_SEND)
        settle()
        assertEquals(AskState.Asking(AskQuestion.Custom("Is it polite?"), "Gemini"), session.state.value)
        assertEquals("Is it polite?", title.text.toString())
        assertEquals(2, calls.size)
        assertEquals("", field.text.toString())
        assertFalse(field.isFocused)
        assertFalse("the keyboard is down", shadowOf(imm).isSoftInputVisible)
        assertEquals(emptyList<String>(), store.writes)
    }

    @Test
    fun `a field that goes out of sight takes its keyboard with it, at a chip and at the eye, and keeps its draft`() {
        start()
        val imm = activity.getSystemService(InputMethodManager::class.java)

        // A chip tapped mid-typing: the question takes the field's place.
        field.requestFocus()
        imm.showSoftInput(field, 0)
        field.setText("Is it pol")
        assertTrue("precondition: typing, keyboard up", field.isFocused && shadowOf(imm).isSoftInputVisible)
        tap(chips().first())
        assertTrue(session.state.value is AskState.Asking)
        assertFalse(field.isFocused)
        assertFalse("the keyboard is down at a chip", shadowOf(imm).isSoftInputVisible)
        tap(close)
        assertEquals("the draft is there when the questions come back", "Is it pol", field.text.toString())

        // The eye, mid-typing: the card goes.
        field.requestFocus()
        imm.showSoftInput(field, 0)
        assertTrue("precondition: typing, keyboard up", field.isFocused && shadowOf(imm).isSoftInputVisible)
        tap(eye)
        assertFalse(shown(card))
        assertFalse(field.isFocused)
        assertFalse("the keyboard is down at the eye", shadowOf(imm).isSoftInputVisible)
        tap(eye)
        assertEquals("Is it pol", field.text.toString())
    }

    // ── The question's look ─────────────────────────────────────────────

    @Test
    fun `asking, the title takes the chips' place at their height, with no hairline under it`() {
        start()
        assertEquals("precondition: one line of chips", 1, chips().map { it.top }.distinct().size)
        val chipRowHeight = view<View>(R.id.askPresetChips).height
        assertTrue("precondition: laid out", chipRowHeight > 0)

        tap(chips().first())

        val titleRow = title.parent as View
        val below = body.parent as View
        assertEquals("the title row is as tall as the chips' row was", chipRowHeight, titleRow.height)
        assertEquals("what has come of it starts where the title row ends", titleRow.bottom, below.top)
    }

    @Test
    fun `the title is in italic exactly when every letter in it is Latin script`() {
        start()
        // A preset: its name, in the app's language (English here).
        tap(chips().first())
        assertTrue("the preset's name", italic(title))
        tap(close)

        // A question of the user's own: whatever they typed. Alternating,
        // so each render is seen to set the style rather than keep it.
        val cases = listOf(
            "Why ga and not wa?" to true,
            "なぜ「が」なの？" to false,
            "Pourquoi « ga » et pas « wa » ?" to true,
            "Почему здесь «га»?" to false,
            "Tại sao dùng ga?" to true,
            "Why が and not は?" to false,
            "2 + 2?" to true,
            "为什么用这个词？" to false,
            "Ποιο είναι το υποκείμενο;" to false,
            "왜 이 단어를 썼어?" to false,
            "ทำไมใช้คำนี้" to false,
            "لماذا هذه الكلمة؟" to false,
        )
        for ((text, expected) in cases) {
            binder.render(AskState.Asking(AskQuestion.Custom(text), "Gemini"))
            settle()
            assertEquals(text, title.text.toString())
            assertEquals("'$text'", expected, italic(title))
        }
    }

    @Test
    fun `the line under the title names the service being asked, the next one when the pass falls through, and none before one is picked`() {
        start()
        tap(chips().first())
        assertEquals(activity.getString(R.string.ask_status_asking, "Gemini"), body.text.toString())

        calls.last().attempt(NamedBackend("Qwen 3.5", online = false))
        settle()
        assertEquals("Qwen 3.5", (session.state.value as AskState.Asking).service)
        assertEquals(activity.getString(R.string.ask_status_asking, "Qwen 3.5"), body.text.toString())
        assertEquals(color(R.attr.ptText), body.currentTextColor)

        // Before the pass has picked anyone, a state no surface's immediate
        // dispatcher lets a frame show: the line without a name.
        binder.render(AskState.Asking(AskQuestion.Custom("Why ga?")))
        settle()
        assertEquals(activity.getString(R.string.ask_status_asking_unnamed), body.text.toString())
        assertEquals(color(R.attr.ptText), body.currentTextColor)
    }

    @Test
    fun `the line under an answer is styled as the Translation card's Translated by`() {
        start()
        tap(chips().first())
        answer("Ga marks the cat as the one who ate.", by = gemini)
        assertTrue(shown(footer))
        // The same page's note, as section_target.xml draws it; italic is
        // TranslationSectionBinder's, set when it says who translated.
        val note = view<TextView>(R.id.tvTranslationNote)
        assertEquals("size", note.textSize, footer.textSize)
        assertEquals("colour", note.currentTextColor, footer.currentTextColor)
        // The note's gap is its row's top padding (the row is the tap
        // target of the translation service picker).
        assertEquals(
            "gap above",
            view<View>(R.id.translationNoteRow).paddingTop,
            (footer.layoutParams as ViewGroup.MarginLayoutParams).topMargin,
        )
        assertTrue("italic", italic(footer))
    }

    // ── The answer's text size ──────────────────────────────────────────

    @Test
    fun `the Text size is offered exactly while a question is on screen, the section collapsed or not`() {
        start()
        assertFalse("nothing asked", shown(textSizeButton))
        tap(chips().first())
        assertTrue("asking", shown(textSizeButton))
        answer("Ga marks the cat as the one who ate.")
        assertTrue("answered", shown(textSizeButton))
        tap(close)
        assertFalse("put away", shown(textSizeButton))
        tap(chips().first())
        fail(CompletionFailedException(null, null))
        assertTrue("failed", shown(textSizeButton))
        tap(close)

        // Collapsed: not with nothing asked; a question asked from the
        // header's pills keeps the card up, and the Text size with it.
        tap(eye)
        assertFalse("collapsed, nothing asked", shown(textSizeButton))
        tap(pills().first(::shown))
        assertEquals(View.VISIBLE, card.visibility)
        assertTrue("collapsed, a question on screen", shown(textSizeButton))
    }

    @Test
    fun `a row too narrow for the Text size keeps it, as it keeps the eye, and cuts the label`() {
        start()
        tap(chips().first())
        assertTrue("precondition: offered", shown(textSizeButton))
        val gap = (16 * activity.resources.displayMetrics.density).toInt()
        label.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        val natural = label.measuredWidth
        // Room for the label, the eye and the gap between them: none for an
        // icon more.
        val row = header.paddingLeft + natural + gap + eye.layoutParams.width + header.paddingRight
        header.measure(
            MeasureSpec.makeMeasureSpec(row, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        header.layout(0, 0, row, header.measuredHeight)
        assertTrue("the Text size stays", shown(textSizeButton))
        assertTrue("as does the eye", shown(eye))
        assertTrue("the label gives way", label.maxWidth < natural)
    }

    @Test
    fun `with no size saved, the answer's text is 16 sp`() {
        start()
        assertEquals(sp(16), body.textSize)
    }

    @Test
    fun `the Text size opens the one-handle picker on the surface's popover host, anchored on it, and is an interaction`() {
        start()
        tap(chips().first())
        val interactions = host.interactions
        tap(textSizeButton)
        assertTrue("${popovers.content}", popovers.content is FontSizePopover)
        assertSame(textSizeButton, popovers.anchor)
        assertEquals("opening it is an interaction", interactions + 1, host.interactions)
    }

    @Test
    fun `the answer's text is at the saved size from the start, the picker resizes it live and tells the host, and a render reads it afresh`() {
        prefs.askFontSp = 20
        start()
        assertEquals("the saved size from the start", sp(20), body.textSize)
        tap(chips().first())
        tap(textSizeButton)
        val track = root.findViewById<View>(R.id.sizePickerTrack)
        assertTrue("precondition: the picker is laid out", track.width > 0)

        // A touch at the track's far end jumps the one handle there.
        val heights = host.heightChanges
        drag(track, track.width - 1f)
        assertEquals(Prefs.FONT_SP_CEIL, prefs.askFontSp)
        assertEquals("the text follows at once", sp(Prefs.FONT_SP_CEIL), body.textSize)
        assertEquals("the host hears the card may have changed height", heights + 1, host.heightChanges)

        // And at its start, the axis's other end.
        drag(track, 0f)
        assertEquals(Prefs.FONT_SP_FLOOR, prefs.askFontSp)
        assertEquals(sp(Prefs.FONT_SP_FLOOR), body.textSize)

        // Another surface's picker changed it: the next render reads it.
        prefs.askFontSp = 18
        binder.render(session.state.value)
        assertEquals(sp(18), body.textSize)
    }

    // ── Failure ─────────────────────────────────────────────────────────

    @Test
    fun `a failure with nothing to say shows the generic line alone, and Try again asks the same question`() {
        assertNotEquals(color(R.attr.ptTextMuted), color(R.attr.ptText))
        start()
        tap(chips().first())
        val asked = (session.state.value as AskState.Asking).question
        // An on-device model failed: the session names no service.
        fail(CompletionFailedException(NamedBackend("Qwen 3.5", online = false), IllegalStateException("engine")))
        assertEquals(AskState.Failed(asked), session.state.value)
        assertEquals(asked.title, title.text.toString())
        assertEquals(activity.getString(R.string.ask_failed), body.text.toString())
        assertEquals(color(R.attr.ptTextMuted), body.currentTextColor)
        assertTrue(shown(retry))
        assertFalse(shown(footer))

        val interactions = host.interactions
        tap(retry)
        assertEquals("Try again is an interaction", interactions + 1, host.interactions)
        assertEquals(AskState.Asking(asked, "Gemini"), session.state.value)
        assertEquals(2, calls.size)
        assertEquals("the same question, asked again", calls[0].user, calls[1].user)
        assertEquals("the retry's pass alone asks past the cooldowns", listOf(false, true), calls.map { it.pastCooldowns })
        assertFalse(shown(retry))
        assertEquals(activity.getString(R.string.ask_status_asking, "Gemini"), body.text.toString())
    }

    @Test
    fun `a failure naming a service shows its reason under the generic line`() {
        start()
        tap(chips().first())
        val failure = BackendFailure(BackendFailureKind.RATE_LIMITED, 429)
        fail(CompletionFailedException(NamedBackend("OpenAI", online = true), OpenAiRateLimitException(failure)))
        val failed = session.state.value as AskState.Failed
        assertEquals("precondition: the session names the service", "OpenAI", failed.service)
        assertEquals(failure, failed.failure)
        assertEquals(
            activity.getString(R.string.ask_failed) + "\n" +
                activity.getString(R.string.translation_error_rate_limited, "OpenAI"),
            body.text.toString(),
        )
        assertEquals(color(R.attr.ptTextMuted), body.currentTextColor)
        assertTrue(shown(retry))
        assertFalse(shown(footer))
    }

    // ── A cut answer ────────────────────────────────────────────────────

    @Test
    fun `an answer the service cut short says so over Answered by, and the next whole answer says only who answered`() {
        start()
        tap(chips().first())
        val asked = (session.state.value as AskState.Asking).question
        val cloud = NamedBackend("PlayTranslate Cloud", online = true)
        val by = activity.getString(R.string.ask_answered_by, "PlayTranslate Cloud")

        calls.last().reply.complete(
            TranslationBackendRegistry.Completion(SentenceAnswer.Text("Ga marks the cat as the one who", cut = true), cloud),
        )
        settle()

        assertEquals(
            AskState.Answered(asked, "Ga marks the cat as the one who", "PlayTranslate Cloud", cut = true),
            session.state.value,
        )
        assertEquals("the text as it came", "Ga marks the cat as the one who", body.text.toString())
        assertEquals(color(R.attr.ptText), body.currentTextColor)
        assertTrue(shown(footer))
        assertEquals(activity.getString(R.string.ask_answer_cut) + "\n" + by, footer.text.toString())
        assertFalse("an answer, so nothing to try again", shown(retry))

        // The same footer view serves the next answer: no note left behind.
        tap(close)
        tap(chips().first())
        answer("Ga marks the cat as the one who ate.", by = cloud)
        assertEquals(by, footer.text.toString())
    }

    // ── Out of scope ────────────────────────────────────────────────────

    @Test
    fun `a question the service calls out of scope shows the app's own line naming it, with no Try again and no Answered by`() {
        assertNotEquals(color(R.attr.ptTextMuted), color(R.attr.ptText))
        start()
        tap(chips().first())
        val asked = (session.state.value as AskState.Asking).question
        val cloud = NamedBackend("PlayTranslate Cloud", online = true)

        calls.last().reply.complete(TranslationBackendRegistry.Completion(SentenceAnswer.OutOfScope, cloud))
        settle()

        assertEquals(AskState.OutOfScope(asked, "PlayTranslate Cloud"), session.state.value)
        assertTrue("a question on screen keeps the card up", shown(card))
        assertTrue(shown(answerGroup))
        assertFalse(shown(idleGroup))
        assertEquals(asked.title, title.text.toString())
        assertEquals(activity.getString(R.string.ask_out_of_scope, "PlayTranslate Cloud"), body.text.toString())
        assertTrue("the line names who declined: ${body.text}", body.text.contains("PlayTranslate Cloud"))
        assertEquals(color(R.attr.ptTextMuted), body.currentTextColor)
        assertFalse("nothing to try again", shown(retry))
        assertFalse("not an answer, so no Answered by", shown(footer))
        assertEquals("the Text size, the eye and X, and no Try again", listOf(textSizeButton, eye, close), navViews())
        assertEquals("nobody was asked again", 1, calls.size)

        tap(close)
        assertEquals(AskState.Idle, session.state.value)
        assertTrue("X brings the questions back", shown(idleGroup))
    }

    // ── The spoiler line ────────────────────────────────────────────────

    @Test
    fun `the spoiler line shows over a question exactly while the Translation section is hidden, and never with nothing asked`() {
        prefs.hideTranslationSection = true
        start()
        assertFalse("nothing asked", shown(warning))
        binder.syncSpoilerWarning()
        assertFalse("nothing asked, re-read", shown(warning))

        tap(chips().first())
        assertTrue("asking, Translation hidden", shown(warning))
        prefs.hideTranslationSection = false
        val heights = host.heightChanges
        binder.syncSpoilerWarning()
        assertFalse("asking, Translation shown", shown(warning))
        assertEquals("the card changed height: the host hears of it", heights + 1, host.heightChanges)
        binder.syncSpoilerWarning()
        assertEquals("no change, no news", heights + 1, host.heightChanges)

        answer("Ga marks the cat as the one who ate.")
        assertFalse("answered, Translation shown", shown(warning))
        prefs.hideTranslationSection = true
        binder.syncSpoilerWarning()
        assertTrue("answered, Translation hidden", shown(warning))

        tap(close)
        assertFalse("nothing asked again", shown(warning))
        tap(chips().first())
        fail(CompletionFailedException(null, null))
        assertTrue("failed, Translation hidden", shown(warning))
        prefs.hideTranslationSection = false
        binder.syncSpoilerWarning()
        assertFalse("failed, Translation shown", shown(warning))
    }

    // ── What the host hears ─────────────────────────────────────────────

    @Test
    fun `the host hears of every render and every eye tap, and of the field's focus`() {
        start()
        assertEquals("the first render", 1, host.heightChanges)
        tap(chips().first())
        // Two renders in the one tap: the question, then the service the
        // pass picked (which the line under it names).
        assertEquals("asking, then whom", 3, host.heightChanges)
        answer("Ga marks the cat as the one who ate.")
        assertEquals("answered", 4, host.heightChanges)
        tap(close)
        assertEquals("nothing asked", 5, host.heightChanges)
        tap(eye)
        assertEquals("the eye hides the card", 6, host.heightChanges)
        tap(eye)
        assertEquals("the eye shows it", 7, host.heightChanges)
        binder.render(AskState.Idle)
        assertEquals("a render the host repeats", 8, host.heightChanges)

        // A finger's tap with an LLM focuses the field, which is an
        // interaction (a live host holds the sentence being asked about);
        // clearFieldFocus takes the focus and the keyboard away, is none,
        // and is a no-op after.
        assertTrue(host.focusChanges.isEmpty())
        val interactions = host.interactions
        touchField()
        assertTrue(field.isFocused)
        assertTrue(binder.fieldHasFocus)
        assertEquals(listOf(true), host.focusChanges)
        assertEquals("taking the focus is an interaction", interactions + 1, host.interactions)
        val imm = activity.getSystemService(InputMethodManager::class.java)
        imm.showSoftInput(field, 0)
        assertTrue("precondition: the keyboard is up", shadowOf(imm).isSoftInputVisible)
        binder.clearFieldFocus()
        assertFalse(field.isFocused)
        assertFalse(binder.fieldHasFocus)
        assertEquals(listOf(true, false), host.focusChanges)
        assertEquals("losing it is none", interactions + 1, host.interactions)
        assertFalse("the keyboard is down", shadowOf(imm).isSoftInputVisible)
        binder.clearFieldFocus()
        assertEquals(listOf(true, false), host.focusChanges)

        // The controller's A on the field focuses it too.
        binder.navigableActions().single { it.view === field }.activate()
        assertTrue(field.isFocused)
        assertEquals(listOf(true, false, true), host.focusChanges)
        assertEquals("focus is no alert", 0, host.llmNeeded)
    }

    // ── The controller ──────────────────────────────────────────────────

    @Test
    fun `the controller reaches what is on screen, in order, in each state`() {
        prefs.hideAskSection = true
        start()
        // Collapsed with nothing asked: the pills, then the eye.
        assertTrue("precondition: a pill shows", pills().any(::shown))
        assertEquals(pills().filter(::shown) + eye, navViews())

        // Expanded with nothing asked: the eye, the chips, the field, and
        // send once there is something to send.
        tap(eye)
        assertEquals(listOf<View>(eye) + chips() + field, navViews())
        field.setText("Why ga?")
        assertEquals(listOf<View>(eye) + chips() + field + send, navViews())
        field.setText("")

        // A question in flight: the Text size, the eye and X.
        tap(chips().first())
        assertEquals(listOf(textSizeButton, eye, close), navViews())

        // Failed: the Text size, the eye, X and Try again.
        fail(CompletionFailedException(null, null))
        assertEquals(listOf(textSizeButton, eye, close, retry), navViews())
    }

    // ── The order ───────────────────────────────────────────────────────

    @Test
    fun `a preset asked and put away leads the chips from then on`() {
        start()
        val asked = ordered().last()
        tap(chips().last())
        answer("Ga marks the cat as the one who ate.")
        tap(close)
        assertEquals(asked.id, prefs.askPresetRecency.first())
        assertEquals(asked.title, chips().first().text.toString())
        assertEquals(ordered().map { it.title }, chips().map { it.text.toString() })
    }

    private fun clearPrefs() {
        app.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private companion object {
        const val PREFS_FILE = "playtranslate_prefs"
    }
}
