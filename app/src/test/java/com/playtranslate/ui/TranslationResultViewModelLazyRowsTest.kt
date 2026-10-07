package com.playtranslate.ui

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.model.TextSegments
import com.playtranslate.model.TranslationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Pins the word lookup's two tiers ([WordLookupsState]): a result settles
 * only the analysis ([WordLookupsState.Analyzed]); the rows settle only on
 * [TranslationResultViewModel.requestWordRows], which is a no-op outside
 * Analyzed (including after the rows settled, where every Ready re-render
 * still asks) and while the rows are in flight; and a same-text Translating → Ready promotion neither restarts
 * the analysis nor cancels rows in flight. Runs a real English engine with
 * no pack installed: the tokenizer is pack-free, the dictionary finds
 * nothing, so the rows settle empty but the tiers are real.
 */
@RunWith(RobolectricTestRunner::class)
class TranslationResultViewModelLazyRowsTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var scope: CoroutineScope
    private lateinit var vm: TranslationResultViewModel

    private val text = "The cat eats fish."

    @Before
    fun setUp() {
        clearPrefs()
        LastSentenceCache.clear()
        Prefs(ctx).sourceLang = "en"
        Prefs(ctx).setTarget("en")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        vm = TranslationResultViewModel(scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        SourceLanguageEngines.release(SourceLangId.EN)
        LastSentenceCache.clear()
        clearPrefs()
    }

    private fun result(text: String) = TranslationResult(
        originalText = text, segments = TextSegments.ofText(text), translatedText = "translated",
        timestamp = "", langContext = Prefs(ctx).langContext(),
    )

    /** Drive the main looper until [done] holds: the tiers hop to IO and
     *  back, so the state moves on real threads. */
    private fun awaitState(done: (WordLookupsState) -> Boolean): WordLookupsState {
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            val state = vm.wordLookups.value
            if (done(state)) return state
            check(System.currentTimeMillis() < deadline) { "timed out in $state" }
            Thread.sleep(10)
        }
    }

    /** The VM's private row-tier job. A request that runs the row tier
     *  replaces it, so its identity before and after a request is what
     *  proves the tier did not run again: [TranslationResultViewModel.wordLookups]
     *  can't show a re-run, since the re-run's Settled equals the first one
     *  and the StateFlow conflates it (the value instance survives). */
    private fun rowsJob(): Job? = TranslationResultViewModel::class.java
        .getDeclaredField("rowsJob").apply { isAccessible = true }.get(vm) as Job?

    @Test
    fun `a result settles only the analysis, and the request settles the rows`() {
        vm.displayResult(result(text), ctx)
        val analyzed = awaitState { it !is WordLookupsState.Loading }
        assertTrue("got $analyzed", analyzed is WordLookupsState.Analyzed)
        analyzed as WordLookupsState.Analyzed
        assertTrue(analyzed.tokenSpans.any { it.surface == "cat" })
        // Nothing else is running: the state stays Analyzed.
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(analyzed, vm.wordLookups.value)

        vm.requestWordRows(ctx)
        val inFlight = rowsJob()
        assertTrue("the rows resolve off the main thread", inFlight?.isActive == true)
        // Idempotent while in flight: a repeat request launches nothing.
        vm.requestWordRows(ctx)
        assertSame(inFlight, rowsJob())
        val settled = awaitState { it is WordLookupsState.Settled } as WordLookupsState.Settled
        assertEquals("the rows keep the analysis's tap spans", analyzed.tokenSpans, settled.tokenSpans)
        assertSame(analyzed.annotation, settled.annotation)
        assertEquals(analyzed.phrases, settled.phrases)
    }

    @Test
    fun `after the rows settle, a request is a no-op`() {
        vm.displayResult(result(text), ctx)
        awaitState { it is WordLookupsState.Analyzed }
        vm.requestWordRows(ctx)
        val settled = awaitState { it is WordLookupsState.Settled }
        val job = rowsJob()
        // Every Ready re-render asks again (applyWordsVisibility →
        // onRowsWanted), e.g. a translation update after the rows settled:
        // the row tier must not re-run and rebuild the list.
        vm.requestWordRows(ctx)
        assertSame("the row tier did not run again", job, rowsJob())
        assertSame(settled, vm.wordLookups.value)
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(settled, vm.wordLookups.value)
        assertTrue(vm.wordLookups.value is WordLookupsState.Settled)
    }

    @Test
    fun `the request is a no-op in Idle and Loading`() {
        vm.requestWordRows(ctx)
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(WordLookupsState.Idle, vm.wordLookups.value)

        vm.startWordLookups(text, ctx)
        assertSame(WordLookupsState.Loading, vm.wordLookups.value)
        vm.requestWordRows(ctx)
        // The Loading-time request queued nothing: the analysis lands and stays.
        val state = awaitState { it !is WordLookupsState.Loading }
        assertTrue("got $state", state is WordLookupsState.Analyzed)
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(state, vm.wordLookups.value)

        // Leaving the results drops the analysis: a request after it is a no-op.
        vm.showStatus("Capturing")
        vm.requestWordRows(ctx)
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(WordLookupsState.Idle, vm.wordLookups.value)
    }

    @Test
    fun `a same-text promotion keeps the analysis and the rows in flight`() {
        vm.showTranslatingPlaceholder(text, TextSegments.ofText(text), ctx)
        val analyzed = awaitState { it !is WordLookupsState.Loading }
        assertTrue("got $analyzed", analyzed is WordLookupsState.Analyzed)
        vm.requestWordRows(ctx)
        assertSame("the rows are in flight (off the main thread)", analyzed, vm.wordLookups.value)
        // The translation lands while the rows resolve: no restart, so the
        // rows settle (a restart would re-emit Loading → Analyzed and stop).
        vm.displayResult(result(text), ctx)
        assertTrue(vm.result.value is ResultState.Ready)
        val settled = awaitState { it is WordLookupsState.Settled } as WordLookupsState.Settled
        assertSame((analyzed as WordLookupsState.Analyzed).annotation, settled.annotation)
    }

    @Test
    fun `the translation is cached without the rows, and the settle keeps it`() {
        val shown = result(text)
        vm.displayResult(shown, ctx)
        awaitState { it is WordLookupsState.Analyzed }
        // Rows never requested (a hidden Words card): the translation half is
        // cached anyway, so the drag lens's open-in-app is served from it.
        assertEquals(text, LastSentenceCache.original)
        assertEquals(shown.translatedText, LastSentenceCache.translation)
        assertNull("no rows settled, so no words are cached", LastSentenceCache.wordResults)

        vm.requestWordRows(ctx)
        awaitState { it is WordLookupsState.Settled }
        assertEquals(text, LastSentenceCache.original)
        assertEquals(shown.translatedText, LastSentenceCache.translation)
        assertNotNull("the settle writes the words half", LastSentenceCache.wordResults)
    }

    private fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }
}
