package com.playtranslate.ui

import android.content.Context
import android.graphics.Rect
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.OneShotOverlayData
import com.playtranslate.fillOneShotOverlayData
import com.playtranslate.language.ChineseScriptVariant
import com.playtranslate.language.SourceLangId
import com.playtranslate.model.PendingTranslation
import com.playtranslate.model.TextSegment
import com.playtranslate.model.TranslationLangContext
import com.playtranslate.model.TranslationResult
import com.playtranslate.translation.BackendFailure
import com.playtranslate.translation.BackendFailureKind
import com.playtranslate.translation.FakeSwitchedBackend
import com.playtranslate.translation.TranslationBackend
import com.playtranslate.ui.ChosenServiceTranslation.Outcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * A service the user picked for the result on show, on
 * [TranslationResultViewModel] ([TranslationResultViewModel.retranslateWith]).
 *
 * The result is not changed while the run is in flight: the run stands
 * beside it ([TranslationResultViewModel.pickRun]), so whatever keeps or
 * reads the result meanwhile has the whole of the old one. A translation
 * lands in one step, on the very result it was started for and no other:
 * the text, the service as its credit, the fallback note gone, and the
 * capture's boxes for the game screen filled again group by group (kept as
 * they are where they cannot be). A failure changes nothing on show: it
 * becomes the run's own state ([PickRun.failure]) and stays until the page
 * has said so ([TranslationResultViewModel.pickFailureTold]) or the result
 * it is about is gone, so no failure is ever said over another result.
 *
 * "The very result" is identity. A newer result on show by the time the
 * service answers takes nothing and hears of no failure; the same result
 * put back after a status (a cancelled capture) still takes the landing; a
 * translation that arrives under the status lands nowhere; a
 * pick for a result that is no longer on show asks nobody; a second pick
 * replaces the first, also when the first one's service answers after it
 * was called off: that answer is dropped, a translation and a failure
 * alike, and the second pick's still lands.
 *
 * Whatever takes the result's place ends the run, calls its request off,
 * so no service goes on being asked for a result nobody sees, and drops a
 * failure not yet said: another result, new text being translated, an edit,
 * a translation given from elsewhere, an error. A status does not, since
 * the result may be put back under it.
 *
 * And the units such a run translates ([TranslationResult.translationUnits]):
 * a capture's groups, the whole text where there are none, and the whole
 * edited text after an edit of the source.
 *
 * The translation itself is a seam held open by the test
 * ([TranslationResultViewModel.chosenTranslation]).
 */
@RunWith(RobolectricTestRunner::class)
class TranslationResultViewModelPickTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var vm: TranslationResultViewModel

    private val gemini: TranslationBackend = FakeSwitchedBackend(id = "gemini", priority = 10, displayName = "Gemini")
    private val deepl: TranslationBackend = FakeSwitchedBackend(id = "deepl", priority = 20, displayName = "DeepL")

    /** Each call of the seam: who was asked, about which result, and the
     *  answer the test gives when it chooses to. */
    private class Asked(val backend: TranslationBackend, val result: TranslationResult) {
        val answer = CompletableDeferred<Outcome>()

        /** The request was called off: its run cancelled while it waited. */
        var calledOff = false
    }

    private val asked = mutableListOf<Asked>()

    @Before
    fun setUp() {
        vm = TranslationResultViewModel(scope)
        vm.chosenTranslation = { _, backend, result ->
            val call = Asked(backend, result).also { asked += it }
            try {
                call.answer.await()
            } catch (e: CancellationException) {
                call.calledOff = true
                throw e
            }
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun result(
        text: String = "こんにちは",
        translation: String = "Hello",
        by: String? = "Lingva",
        note: String? = null,
        groups: List<String>? = null,
        pending: PendingTranslation? = null,
    ) = TranslationResult(
        originalText = text,
        segments = listOf(TextSegment(text)),
        translatedText = translation,
        timestamp = "",
        note = note,
        backendDisplayName = by,
        pendingTranslation = pending,
        langContext = TranslationLangContext(SourceLangId.JA, "en", ChineseScriptVariant.SIMPLIFIED),
        groupTexts = groups,
    )

    private fun ready() = vm.result.value as ResultState.Ready

    private fun translated(vararg perUnit: String, by: String = "Gemini") = Outcome.Translated(perUnit.toList(), by)

    private fun answer(index: Int, outcome: Outcome) {
        asked[index].answer.complete(outcome)
        idle()
    }

    /** A capture's boxes for two groups, filled with [first] and [second]. */
    private fun boxes(first: String, second: String): OnScreenBoxes {
        val rects = listOf(Rect(0, 0, 100, 30), Rect(0, 40, 100, 70))
        val skeleton = OneShotOverlayData(
            boxes = rects.map { TextBox("", it, drawBounds = it) },
            cropLeft = 0, cropTop = 0, screenshotW = 400, screenshotH = 300,
            cropWidth = 200, cropHeight = 160, text = rects,
        )
        return OnScreenBoxes(fillOneShotOverlayData(skeleton, listOf(first, second))!!, displayId = 2, captureId = 7L)
    }

    // ── while it runs ────────────────────────────────────────────────────

    @Test
    fun `while a pick runs the result on show is the object it was, and the run stands beside it`() {
        val on = result()
        vm.displayResult(on, ctx)
        assertNull(vm.pickRun.value)

        vm.retranslateWith(gemini, on, ctx)

        assertSame("the service was asked about this result", on, asked.single().result)
        assertSame(gemini, asked.single().backend)
        assertSame("nothing of the result changed", on, ready().result)
        assertEquals("Hello", ready().result.translatedText)
        val run = vm.pickRun.value!!
        assertSame(on, run.result)
        assertEquals("Gemini", run.serviceName)
    }

    // ── a translation lands ──────────────────────────────────────────────

    @Test
    fun `a translation lands as the text, the service's credit and no note, and the run is over`() {
        val on = result(
            translation = "offline hello", by = "ML Kit", note = "⚠ Showing offline translation",
            groups = listOf("こん", "にちは"),
        )
        vm.displayResult(on, ctx)
        vm.retranslateWith(gemini, on, ctx)

        answer(0, translated("Good", "day"))

        val landed = ready().result
        assertEquals("the units' translations joined as a capture joins its groups'", "Good\n\nday", landed.translatedText)
        assertEquals("Gemini", landed.backendDisplayName)
        assertNull("the user chose this service: no fallback warning under it", landed.note)
        assertEquals("the same sentence", on.originalText, landed.originalText)
        assertEquals("still a capture of two groups, for the next pick", listOf("こん", "にちは"), landed.groupTexts)
        assertNull("nothing is left of the run, a failure to say least of all", vm.pickRun.value)
    }

    @Test
    fun `a translation that lands leaves nothing put off to land over it`() {
        // Not a state the gear is offered in (a result with a translation on
        // show has none put off), but the rule every derived translation
        // keeps.
        val on = result(pending = PendingTranslation(listOf("こんにちは"), SourceLangId.JA, "en"))
        vm.displayResult(on, ctx)
        vm.retranslateWith(gemini, on, ctx)

        answer(0, translated("Hi"))

        assertNull(ready().result.pendingTranslation)
    }

    @Test
    fun `the capture's boxes are filled again group by group and stay the same capture's`() {
        val on = result(translation = "one\n\ntwo", groups = listOf("一", "二"))
        val shown = boxes("one", "two")
        vm.displayResult(on, ctx, onScreenBoxes = shown)
        vm.retranslateWith(gemini, on, ctx)
        assertSame("untouched while it runs", shown, ready().onScreenBoxes)

        answer(0, translated("uno", "dos"))

        val refilled = ready().onScreenBoxes!!
        assertEquals(listOf("uno", "dos"), refilled.data.boxes.map { it.translatedText })
        assertEquals("the same capture, so the page fills its window in place", 7L, refilled.captureId)
        assertEquals(2, refilled.displayId)
    }

    @Test
    fun `boxes that cannot be refilled stay as they are`() {
        // One unit for two boxes: a result whose units are not its boxes'
        // groups.
        val on = result(translation = "one two", groups = null)
        val shown = boxes("one", "two")
        vm.displayResult(on, ctx, onScreenBoxes = shown)
        vm.retranslateWith(gemini, on, ctx)

        answer(0, translated("uno dos"))

        assertEquals("uno dos", ready().result.translatedText)
        assertSame(shown, ready().onScreenBoxes)
    }

    // ── a failure ────────────────────────────────────────────────────────

    @Test
    fun `a failure changes nothing on show and stays as the run's state until the page has said so`() {
        val on = result()
        vm.displayResult(on, ctx)
        vm.retranslateWith(gemini, on, ctx)
        assertTrue("being asked", vm.pickRun.value!!.running)

        val failed = Outcome.Failed("gemini", "Gemini", BackendFailure(BackendFailureKind.RATE_LIMITED, 429))
        answer(0, failed)

        assertSame(on, ready().result)
        assertEquals("Hello", ready().result.translatedText)
        val run = vm.pickRun.value!!
        assertSame("what the service answered, for the page to say", failed, run.failure)
        assertFalse("no longer being asked, so the page draws the translation as it was", run.running)
        assertSame("about the result it was started for", on, run.result)
        assertEquals("Gemini", run.serviceName)

        vm.pickFailureTold(run)

        assertNull("said, and that is the last of it", vm.pickRun.value)
        assertSame(on, ready().result)
    }

    @Test
    fun `a failure not yet said is dropped with the result it is about, whatever takes that result's place`() {
        for ((what, replace) in replacements) {
            val on = result()
            vm.displayResult(on, ctx)
            vm.retranslateWith(gemini, on, ctx)
            answer(asked.lastIndex, Outcome.Failed("gemini", "Gemini", null))
            assertSame(what, on, vm.pickRun.value?.result)
            assertFalse(what, vm.pickRun.value!!.running)

            replace()
            idle()

            assertNull("$what leaves no failure to say over it", vm.pickRun.value)
        }
    }

    @Test
    fun `a failure not yet said is kept under a status, and is still there when its result is put back`() {
        val on = result()
        vm.displayResult(on, ctx)
        val shownBefore = vm.result.value
        vm.retranslateWith(gemini, on, ctx)
        val failed = Outcome.Failed("gemini", "Gemini", null)
        answer(0, failed)

        vm.showStatus("Capturing")
        assertSame(failed, vm.pickRun.value?.failure)
        vm.restore(shownBefore, ctx)

        assertSame(on, ready().result)
        assertSame("still to be said, of the result that is on show again", failed, vm.pickRun.value?.failure)
        assertSame(on, vm.pickRun.value?.result)
    }

    @Test
    fun `a pick made over a failure not yet said takes its place, and saying the old one then changes nothing`() {
        val on = result()
        vm.displayResult(on, ctx)
        vm.retranslateWith(gemini, on, ctx)
        answer(0, Outcome.Failed("gemini", "Gemini", null))
        val failedRun = vm.pickRun.value!!

        vm.retranslateWith(deepl, on, ctx)
        vm.pickFailureTold(failedRun)

        val run = vm.pickRun.value!!
        assertEquals("DeepL", run.serviceName)
        assertTrue("the newer run is still being asked", run.running)
        answer(1, translated("from DeepL", by = "DeepL"))
        assertEquals("from DeepL", ready().result.translatedText)
    }

    // ── the very result, and no other ────────────────────────────────────

    @Test
    fun `a newer result on show takes nothing of the pick, translation or failure`() {
        val on = result()
        vm.displayResult(on, ctx)
        vm.retranslateWith(gemini, on, ctx)
        // Another capture of the same text: everything the page draws is
        // equal, and it is not the result the service was asked about.
        val newer = result().copy(createdAtMs = on.createdAtMs + 1)
        vm.displayResult(newer, ctx)

        answer(0, translated("Hi"))

        assertSame(newer, ready().result)
        assertEquals("Hello", ready().result.translatedText)
        assertNull(vm.pickRun.value)

        // And a failure that arrives while its result is not on show is
        // kept for nobody: the run is over, as it is for a translation that
        // arrives then.
        vm.retranslateWith(gemini, newer, ctx)
        vm.showStatus("Capturing")
        answer(1, Outcome.Failed("gemini", "Gemini", null))
        assertNull(vm.pickRun.value)
        assertTrue(vm.result.value is ResultState.Status)
    }

    @Test
    fun `the same result put back after a status still takes the landing`() {
        val on = result()
        vm.displayResult(on, ctx)
        val shownBefore = vm.result.value
        vm.retranslateWith(gemini, on, ctx)
        // A capture started over the page and was cancelled: its status,
        // then what the page showed before it.
        vm.showStatus("Capturing")
        vm.restore(shownBefore, ctx)
        assertSame(on, ready().result)

        answer(0, translated("Hi"))

        assertEquals("Hi", ready().result.translatedText)
        assertEquals("Gemini", ready().result.backendDisplayName)
    }

    @Test
    fun `a pick for a result that is no longer on show asks nobody`() {
        val opened = result()
        vm.displayResult(opened, ctx)
        vm.displayResult(result(text = "さようなら", translation = "Goodbye"), ctx)

        vm.retranslateWith(gemini, opened, ctx)

        assertTrue(asked.isEmpty())
        assertNull(vm.pickRun.value)
        assertEquals("Goodbye", ready().result.translatedText)
    }

    @Test
    fun `a second pick replaces the first`() {
        val on = result()
        vm.displayResult(on, ctx)
        vm.retranslateWith(gemini, on, ctx)
        vm.retranslateWith(deepl, on, ctx)
        idle()
        assertEquals("DeepL", vm.pickRun.value?.serviceName)
        assertTrue("the first request was called off", asked[0].calledOff)
        assertFalse(asked[1].calledOff)

        // The first service answers late: its run was called off.
        answer(0, translated("from Gemini"))
        assertSame(on, ready().result)
        assertEquals("the second run is still the model's", "DeepL", vm.pickRun.value?.serviceName)

        answer(1, translated("from DeepL", by = "DeepL"))
        assertEquals("from DeepL", ready().result.translatedText)
        assertEquals("DeepL", ready().result.backendDisplayName)
        assertNull(vm.pickRun.value)
    }

    // ── what ends a run ──────────────────────────────────────────────────

    /** Each way a result's place on the page is taken. */
    private val replacements = listOf<Pair<String, () -> Unit>>(
        "another result" to { vm.displayResult(result(text = "さようなら", translation = "Goodbye"), ctx) },
        "new text being translated" to { vm.showTranslatingPlaceholder("次の行", listOf(TextSegment("次の行")), ctx) },
        "an edit of the source" to { vm.updateOriginalText("こんばんは", ctx) },
        "a translation given from elsewhere" to { vm.updateTranslation("Hi there", "Lingva", ctx) },
        "an error" to { vm.showError("failed") },
        "the page emptied" to { vm.restore(ResultState.Idle, ctx) },
    )

    @Test
    fun `whatever takes the result's place ends the run and calls its request off`() {
        for ((what, replace) in replacements) {
            val on = result()
            vm.displayResult(on, ctx)
            vm.retranslateWith(gemini, on, ctx)
            val call = asked.last()
            assertSame(what, on, vm.pickRun.value?.result)

            replace()
            idle()

            assertTrue("$what calls the request off", call.calledOff)
            assertNull("$what ends the run", vm.pickRun.value)
        }
    }

    @Test
    fun `a status keeps the run going, and the result that follows it ends it`() {
        val on = result()
        vm.displayResult(on, ctx)
        vm.retranslateWith(gemini, on, ctx)

        // A capture started over the page: it may be cancelled and the
        // result put back.
        vm.showStatus("Capturing")
        idle()
        assertFalse("the request is left running", asked[0].calledOff)
        assertSame(on, vm.pickRun.value?.result)

        // It was not cancelled: its own result takes the page.
        vm.displayResult(result(text = "次の行", translation = "The next line"), ctx)
        idle()
        assertTrue(asked[0].calledOff)
        assertNull(vm.pickRun.value)
    }

    @Test
    fun `a translation that arrives under a status lands nowhere, and the result put back is as it was`() {
        val on = result()
        vm.displayResult(on, ctx)
        val shownBefore = vm.result.value
        vm.retranslateWith(gemini, on, ctx)
        vm.showStatus("Capturing")

        answer(0, translated("Hi"))

        assertTrue(vm.result.value is ResultState.Status)
        assertNull("the run is over", vm.pickRun.value)
        vm.restore(shownBefore, ctx)
        assertSame(on, ready().result)
        assertEquals("Hello", ready().result.translatedText)
    }

    // ── a called-off run whose service answers all the same ──────────────

    /** The seam as a request a cancellation does not interrupt: its reply
     *  comes back to a run that was called off, as a reply already in hand
     *  does through the release of a switched-off on-device model.
     *  [cameBack] names each service whose reply did come back, so a case
     *  can show the drop was the model's and not the cancellation's. */
    private val cameBack = mutableListOf<TranslationBackend>()

    private fun answerPastCancellation() {
        vm.chosenTranslation = { _, backend, result ->
            val call = Asked(backend, result).also { asked += it }
            withContext(NonCancellable) { call.answer.await() }.also { cameBack += backend }
        }
    }

    @Test
    fun `a called-off pick's translation that comes back all the same does not land, and the newer pick's does`() {
        answerPastCancellation()
        val on = result()
        vm.displayResult(on, ctx)
        vm.retranslateWith(gemini, on, ctx)
        vm.retranslateWith(deepl, on, ctx)

        answer(0, translated("from Gemini"))

        assertEquals("the called-off request did hand its reply back", listOf(gemini), cameBack)
        assertSame("the result both picks are for is as it was", on, ready().result)
        assertEquals("Hello", ready().result.translatedText)
        assertEquals("the newer run is still the model's", "DeepL", vm.pickRun.value?.serviceName)

        answer(1, translated("from DeepL", by = "DeepL"))
        assertEquals("the newer pick still finds its result on show", "from DeepL", ready().result.translatedText)
        assertEquals("DeepL", ready().result.backendDisplayName)
        assertNull(vm.pickRun.value)
    }

    @Test
    fun `a called-off pick's failure that comes back all the same is said to nobody`() {
        answerPastCancellation()
        val on = result()
        vm.displayResult(on, ctx)
        vm.retranslateWith(gemini, on, ctx)
        vm.retranslateWith(deepl, on, ctx)

        answer(0, Outcome.Failed("gemini", "Gemini", BackendFailure(BackendFailureKind.RATE_LIMITED, 429)))

        assertEquals(listOf(gemini), cameBack)
        assertEquals("the newer run is still the model's", "DeepL", vm.pickRun.value?.serviceName)
        assertTrue("and the pick the user replaced has nothing to say", vm.pickRun.value!!.running)

        // The newer pick's own failure is the one to say.
        val failed = Outcome.Failed("deepl", "DeepL", null)
        answer(1, failed)
        assertSame(failed, vm.pickRun.value?.failure)
        assertSame(on, ready().result)
    }

    // ── the units ────────────────────────────────────────────────────────

    @Test
    fun `a result's units are its groups, the whole text with none, and the edited text after an edit`() {
        assertEquals(listOf("一", "二"), result(text = "一 二", groups = listOf("一", "二")).translationUnits)
        assertEquals(listOf("こんにちは"), result(groups = null).translationUnits)
        assertEquals("no groups is no units to lose the text to", listOf("こんにちは"), result(groups = emptyList()).translationUnits)

        vm.displayResult(result(text = "一 二", groups = listOf("一", "二")), ctx)
        vm.updateOriginalText("一つ 二つ", ctx)

        assertNull("the edited text is no longer the capture's groups", ready().result.groupTexts)
        assertEquals(listOf("一つ 二つ"), ready().result.translationUnits)
    }
}
