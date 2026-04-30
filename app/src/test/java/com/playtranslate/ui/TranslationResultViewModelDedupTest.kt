package com.playtranslate.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.model.TextSegment
import com.playtranslate.model.TranslationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Locks in the load-bearing invariants of [TranslationResultViewModel]'s
 * dedup machinery. Two distinct layers cooperate, and a future refactor
 * could silently dismantle either:
 *
 *  - **VM identity dedup** (`===`). [displayResult] and
 *    [displayServiceResult] early-return when handed the same
 *    `TranslationResult` instance they last consumed. This protects
 *    the lookup pipeline from re-running on a sticky-StateFlow replay
 *    of the *exact same* result. It is intentionally identity-based,
 *    not equality-based: a fresh capture of the same source text under
 *    a different backend or dictionary should still be treated as new
 *    and re-trigger lookups.
 *
 *  - **Service vs local tracker split**. `lastSeenServiceResult` is
 *    only advanced by [displayServiceResult]; a local update via
 *    [displayResult] (e.g. drag-sentence) must not poison it, or the
 *    next STOP→START replay of the service's panel StateFlow would
 *    re-deliver the prior service result and clobber the local one.
 *
 * Note: [_result] is a `MutableStateFlow` and conflates by equality.
 * That means a `.copy()` of the current result, even though it would
 * miss VM identity dedup, is still conflated at the StateFlow boundary
 * (same data-class equality on `ResultState.Ready`) and produces no
 * visible UI change — the fragment doesn't re-render. The two layers
 * are complementary: identity dedup avoids redundant *lookup work*
 * while StateFlow conflation avoids redundant *UI updates*. These
 * tests pin the observable behavior; the lookup-skipping half is
 * documented but not asserted (it would require running the full
 * lookup pipeline, which is Android-heavy).
 */
@RunWith(RobolectricTestRunner::class)
class TranslationResultViewModelDedupTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun result(original: String = "hello", translated: String = "translated") =
        TranslationResult(
            originalText = original,
            segments = original.map { TextSegment(it.toString()) },
            translatedText = translated,
            timestamp = "00:00:00",
            screenshotPath = null,
            note = null,
        )

    @Test
    fun `displayResult dedupes the same instance, leaving Ready unchanged`() {
        val vm = TranslationResultViewModel()
        val r = result()
        vm.displayResult(r, ctx)
        val firstReady = vm.result.value
        vm.displayResult(r, ctx)  // sticky replay of the same instance
        val secondReady = vm.result.value
        // Same instance in → same Ready wrapper out (early return fired).
        assertSame(firstReady, secondReady)
    }

    @Test
    fun `same-content copy does not produce a visible UI change`() {
        // VM identity dedup misses on `.copy()` (different instance), so
        // the lookup pipeline does re-run — but `_result` is a
        // MutableStateFlow that conflates by equality, and
        // `ResultState.Ready` / `TranslationResult` are data classes, so
        // the wrapper instance the StateFlow holds stays the same. The
        // user-visible behavior is "no UI flicker for content-equal
        // emissions" regardless of which dedup layer catches it. If
        // someone introduces a reference-equality StateFlow in place of
        // _result, this test fails — that's the regression to catch.
        val vm = TranslationResultViewModel()
        val r = result()
        vm.displayResult(r, ctx)
        val firstReady = vm.result.value
        vm.displayResult(r.copy(), ctx)  // new instance, identical content
        val secondReady = vm.result.value
        assertSame(firstReady, secondReady)
    }

    @Test
    fun `different content updates the Ready wrapper`() {
        val vm = TranslationResultViewModel()
        vm.displayResult(result(original = "first"), ctx)
        val firstReady = vm.result.value
        vm.displayResult(result(original = "second"), ctx)
        val secondReady = vm.result.value
        // New content → new Ready (StateFlow inequality, no conflation).
        // The negative case for the test above: same-content emissions
        // stay stable, different-content emissions propagate.
        assertNotSame(firstReady, secondReady)
    }

    @Test
    fun `displayServiceResult dedupes the same instance`() {
        val vm = TranslationResultViewModel()
        val r = result()
        vm.displayServiceResult(r, ctx)
        val firstReady = vm.result.value
        vm.displayServiceResult(r, ctx)  // STOP→START reattach replays the StateFlow
        val secondReady = vm.result.value
        assertSame(firstReady, secondReady)
    }

    @Test
    fun `local displayResult does not poison service-replay dedup`() {
        val vm = TranslationResultViewModel()
        val service1 = result(original = "service")
        // Service emits a result. lastSeenServiceResult is now `service1`,
        // lastSeenResult is also `service1`.
        vm.displayServiceResult(service1, ctx)

        // User does a drag-sentence: local update via displayResult.
        // This must NOT touch lastSeenServiceResult — otherwise a later
        // service-replay of `service1` (e.g. STOP→START reattach to the
        // panel StateFlow) would look "new" and clobber the local result.
        val local = result(original = "local")
        vm.displayResult(local, ctx)
        assertEquals(local, (vm.result.value as ResultState.Ready).result)

        // Simulate the reattach: panel StateFlow re-delivers `service1`.
        // displayServiceResult should identity-dedup against
        // lastSeenServiceResult and leave the local result on screen.
        vm.displayServiceResult(service1, ctx)
        assertEquals(local, (vm.result.value as ResultState.Ready).result)
    }

    @Test
    fun `service emits a different instance and is processed`() {
        val vm = TranslationResultViewModel()
        val service1 = result(original = "first")
        val service2 = result(original = "second")
        vm.displayServiceResult(service1, ctx)
        assertEquals(service1, (vm.result.value as ResultState.Ready).result)
        vm.displayServiceResult(service2, ctx)
        // Different instance → processed. (Each live cycle constructs a
        // fresh TranslationResult, so this is the common path.)
        assertEquals(service2, (vm.result.value as ResultState.Ready).result)
    }

    @Test
    fun `service result followed by local copy maintains both trackers`() {
        val vm = TranslationResultViewModel()
        val service1 = result(original = "service")
        vm.displayServiceResult(service1, ctx)

        // Local code constructs a derived result and pushes it through
        // displayResult (e.g. updateOriginalText copies the current
        // result with edited text). This advances lastSeenResult but
        // not lastSeenServiceResult.
        val edited = service1.copy(originalText = "edited")
        vm.displayResult(edited, ctx)
        assertEquals(edited, (vm.result.value as ResultState.Ready).result)

        // Service replay of service1 still dedupes — lastSeenServiceResult
        // is unchanged.
        vm.displayServiceResult(service1, ctx)
        assertEquals(edited, (vm.result.value as ResultState.Ready).result)
    }

    @Test
    fun `Idle state is the initial value before any displayResult`() {
        val vm = TranslationResultViewModel()
        assertTrue(vm.result.value is ResultState.Idle)
    }
}
