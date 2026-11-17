package com.playtranslate.ui

import android.app.Activity
import android.os.Bundle
import android.os.Looper
import android.view.Display
import android.view.KeyEvent
import com.playtranslate.R
import com.playtranslate.ask.AskSentence
import com.playtranslate.ask.Explanation
import com.playtranslate.ask.WordInSentence
import com.playtranslate.translation.FakeCompleterBackend
import com.playtranslate.translation.TranslationBackendRegistry
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * The lens's rows and the explain button: the lens is one more host of the
 * word list ([WordRowList]), so what a row's question is and what keeps or
 * forgets its answer are the list's ([WordRowsBinderTest] pins them through
 * the Words card). Pinned here is what is the lens's own:
 *  - a section's subject ([LensSection.explain]) is what gives its row the
 *    button, and a tap asks about that subject;
 *  - a rebind that adds sections (the "Load more" row's) keeps an answer,
 *    and another lookup's section of the same word, in another sentence,
 *    has none;
 *  - with no model the lens is gone before its owner is asked for the
 *    alert, which would otherwise open under the lens's window;
 *  - the controller's cursor reaches the button and the answer's ✕, and
 *    after a press of the ✕ stands on the button that brought the answer,
 *    not nowhere.
 *
 * No engine lays a page out here, so the test stands in for it
 * ([MagnifierLens.layoutRowsForTest]).
 */
@RunWith(RobolectricTestRunner::class)
class MagnifierLensExplainTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private lateinit var controller: ActivityController<Host>
    private lateinit var activity: Activity
    private lateinit var lens: MagnifierLens

    /** [MagnifierLens.onExplainNeedsModel] invocations, and whether the
     *  lens's window was still up at each. */
    private val lensUpWhenAsked = mutableListOf<Boolean>()
    private var dismissals = 0

    private val sentence = AskSentence("猫が魚を食べた", sourceLang = "ja", targetLang = "en")
    private val otherSentence = AskSentence("猫がいる", sourceLang = "ja", targetLang = "en")

    private fun section(word: String, reading: String, explain: WordInSentence?, anki: Boolean = false) = LensSection(
        WordDefinitionData(word, reading, emptyList(), freqScore = 0, isCommon = false),
        label = null,
        opens = true,
        anki = anki,
        explain = explain,
    )

    private val cat = section("猫", "ねこ", WordInSentence(sentence, "猫", "ねこ"))
    private val eat = section("食べる", "たべる", WordInSentence(sentence, "食べる", "たべる", listOf("食べた")))
    private val fish = section("魚", "さかな", explain = null)

    private var reply = CompletableDeferred<String>()
    private val llm = FakeCompleterBackend(
        id = "lens-test-llm", priority = 0, displayName = "Test LLM",
        answer = { _, _ -> reply.await() },
    )

    private fun giveLlm() = TranslationBackendRegistry.addOnlineBackend(llm)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun answer(text: String) {
        val r = reply
        reply = CompletableDeferred()
        r.complete(text)
        idle()
    }

    @Before
    fun setUp() {
        DefinitionShells.reset()
        controller = Robolectric.buildActivity(Host::class.java).setup()
        activity = controller.get()
        lens = MagnifierLens(activity, activity.windowManager, Display.DEFAULT_DISPLAY)
        lens.onDismiss = { dismissals++ }
        lens.onExplainNeedsModel = { lensUpWhenAsked += (lens.rootViewForTest() != null) }
        val dm = activity.resources.displayMetrics
        lens.show(dm.widthPixels / 2, dm.heightPixels * 3 / 4, dm.widthPixels, dm.heightPixels)
        lens.makeInteractive()
    }

    @After
    fun tearDown() {
        TranslationBackendRegistry.removeOnlineBackend(llm.id)
        lens.dismiss()
        controller.pause().stop().destroy()
        DefinitionShells.reset()
    }

    private fun rows(): List<DefRow.Word> = lens.shownRowsForTest().filterIsInstance<DefRow.Word>()
    private fun row(word: String): DefRow.Word = rows().first { it.data.word == word }

    private fun tap(word: String, act: String) {
        lens.tapRowForTest(row(word).id, act)
        idle()
    }

    private fun press(keyCode: Int) {
        val window = requireNotNull(lens.rootViewForTest())
        window.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        window.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        idle()
    }

    private fun answered(text: String) = DefRow.Explain(
        answer = DefRow.Answer(
            activity.getString(R.string.word_explain_title), text,
            footer = activity.getString(R.string.ask_answered_by, "Test LLM"),
        ),
    )

    @Test fun `a section's subject gives its row the explain button, and a section with none has none`() {
        lens.setSplitDefinitions(cat, listOf(eat, fish), false)
        assertEquals(DefRow.Explain(), row("猫").explain)
        assertEquals(DefRow.Explain(), row("食べる").explain)
        assertNull("no sentence behind this unit", row("魚").explain)
    }

    @Test fun `a tap asks about the section's word in its sentence and the answer shows in its row`() {
        giveLlm()
        lens.setSplitDefinitions(cat, listOf(eat), false)

        tap("食べる", DefAction.EXPLAIN)

        assertEquals(DefRow.Explain(busy = true), row("食べる").explain)
        val user = llm.lastPrompt!!.second
        assertTrue(user, user.contains("\"食べる\" (read たべる; it appears here as \"食べた\")"))
        assertTrue(user, user.contains("猫が魚を食べた"))

        answer("It is the plain past of to eat.")

        assertEquals(answered("It is the plain past of to eat."), row("食べる").explain)
        assertEquals("the tapped unit's row was not asked", DefRow.Explain(), row("猫").explain)
        assertEquals("the lens stays up", 0, dismissals)
    }

    // Mutations: answer by the wrong row; keep an answer after the lens is
    // gone; count a question still out as an answer.
    @Test fun `the owner reads the answer standing in a row by the row's place, and none once the lens is dismissed`() {
        giveLlm()
        lens.setSplitDefinitions(cat, listOf(eat, fish))
        idle()
        assertNull("nothing asked", lens.explanationOf(null))

        tap("食べる", DefAction.EXPLAIN)
        assertNull("a question still out", lens.explanationOf(0))
        answer(" It is the plain past. ")

        // The whole answer, as the word's page draws it again.
        assertEquals("the first secondary's", Explanation("It is the plain past.", "Test LLM"), lens.explanationOf(0))
        assertNull("the tapped unit's row has none", lens.explanationOf(null))
        assertNull("a row with no button has none", lens.explanationOf(1))
        assertNull("no such row", lens.explanationOf(7))

        lens.dismiss()
        assertNull("the lens's answers went with it", lens.explanationOf(0))
    }

    @Test fun `a rebind that adds sections keeps an answer and a question still out`() {
        giveLlm()
        lens.setSplitDefinitions(cat, listOf(eat), false, LensLoadMore(1))
        tap("猫", DefAction.EXPLAIN)
        answer("the cat's")
        tap("食べる", DefAction.EXPLAIN)

        // The "Load more" row's sections join the same lookup's.
        lens.setSplitDefinitions(cat, listOf(eat, fish), false)

        assertEquals(answered("the cat's"), row("猫").explain)
        assertEquals("not cancelled by the rebind", DefRow.Explain(busy = true), row("食べる").explain)
        answer("the verb's")
        assertEquals(answered("the verb's"), row("食べる").explain)
        assertEquals("each was put once", 2, llm.completeCalls.get())
    }

    @Test fun `another lookup's section of the same word, in another sentence, has no answer`() {
        giveLlm()
        lens.setDefinitions(cat)
        tap("猫", DefAction.EXPLAIN)
        answer("in the first sentence")
        assertEquals(answered("in the first sentence"), row("猫").explain)

        // The same word tapped in another line: same row id, another subject.
        lens.setDefinitions(section("猫", "ねこ", WordInSentence(otherSentence, "猫", "ねこ")))

        assertEquals(DefRow.Explain(), row("猫").explain)
    }

    @Test fun `with no model the lens is gone before its owner is asked for the alert`() {
        lens.setDefinitions(cat)

        tap("猫", DefAction.EXPLAIN)

        assertEquals("the owner was asked once, the lens already down", listOf(false), lensUpWhenAsked)
        assertEquals("by an ordinary dismissal, which its owner hears of", 1, dismissals)
        assertEquals("nothing was asked of any model", 0, llm.completeCalls.get())
    }

    @Test fun `with a model the owner is not asked and the lens stays`() {
        giveLlm()
        lens.setDefinitions(cat)
        tap("猫", DefAction.EXPLAIN)
        assertTrue(lensUpWhenAsked.isEmpty())
        assertEquals(0, dismissals)
        assertFalse(lens.rootViewForTest() == null)
    }

    @Test fun `the cursor reaches the button and the answer's X, and leaves a pressed X onto the button`() {
        giveLlm()
        lens.setDefinitions(cat)
        tap("猫", DefAction.EXPLAIN)
        answer("the cat's")
        lens.layoutRowsForTest(ROW)
        val id = row("猫").id

        lens.focusForController()
        assertEquals(id to DefAction.OPEN, lens.cursorForTest())
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("the head's first button", id to DefAction.EXPLAIN, lens.cursorForTest())
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(id to DefAction.SPEAK, lens.cursorForTest())
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("after the head's buttons, the answer's", id to DefAction.EXPLAIN_CLOSE, lens.cursorForTest())

        press(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals("the X put the answer away", DefRow.Explain(), row("猫").explain)
        // The page lays the row out again without the answer.
        lens.layoutRowsForTest(ROW)
        assertEquals(
            "on the button that brought it, where the next press asks again",
            id to DefAction.EXPLAIN,
            lens.cursorForTest(),
        )
    }

    @Test fun `a pressed Try again leaves the cursor on the button, which now spins`() {
        giveLlm()
        lens.setDefinitions(cat)
        tap("猫", DefAction.EXPLAIN)
        val r = reply
        reply = CompletableDeferred()
        r.completeExceptionally(java.io.IOException("no route"))
        idle()
        assertTrue("precondition: a failure with Try again", row("猫").explain!!.answer!!.retry)
        lens.layoutRowsForTest(ROW)
        val id = row("猫").id

        lens.focusForController()
        repeat(4) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        assertEquals("past the X, Try again", id to DefAction.EXPLAIN_RETRY, lens.cursorForTest())

        press(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals("asked again: the failure folds away", DefRow.Explain(busy = true), row("猫").explain)
        lens.layoutRowsForTest(ROW)
        assertEquals(id to DefAction.EXPLAIN, lens.cursorForTest())
    }

    private companion object {
        const val ROW = 300
    }
}
