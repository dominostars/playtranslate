package com.playtranslate.yomitan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the single-flight slot shared by the background scan and the detail
 * page's manual "Check for updates" flow: exactly one holder at a time, and
 * the slot self-releases when the holder completes or cancels (there is no
 * give-back call to forget).
 */
class YomitanUpdateSingleFlightTest {

    @Test
    fun `slot claims when free, refuses while held, and self-releases on completion`() {
        val first = Job()
        assertTrue(YomitanAutoUpdateOrchestrator.tryClaimSlot(first))

        // Held: a second claimant (scan or manual) must be refused.
        val second = Job()
        assertFalse(YomitanAutoUpdateOrchestrator.tryClaimSlot(second))

        // Completion releases implicitly.
        first.complete()
        assertTrue(YomitanAutoUpdateOrchestrator.tryClaimSlot(second))

        // Cancellation releases the same way (a claimed-then-cancelled lazy
        // job, or a manual flow dying with its activity).
        second.cancel()
        val third = Job()
        assertTrue(YomitanAutoUpdateOrchestrator.tryClaimSlot(third))

        // Hygiene: the orchestrator is process-wide state — leave the slot
        // free for anything else running in this JVM.
        third.cancel()
    }

    @Test
    fun `an unstarted lazy job holds the slot`() {
        // Both real claimants publish CoroutineStart.LAZY jobs and start them
        // only after a successful claim. A lazy job is NEW — not active, not
        // completed — so an isActive-based predicate would treat a claimed
        // holder as free and let a concurrent claimant CAS over it (the Codex
        // adversarial finding). The holding predicate must be not-completed.
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val claimed = scope.launch(start = CoroutineStart.LAZY) { }
        assertTrue(YomitanAutoUpdateOrchestrator.tryClaimSlot(claimed))

        val rival = scope.launch(start = CoroutineStart.LAZY) { }
        assertFalse(YomitanAutoUpdateOrchestrator.tryClaimSlot(rival))

        // The claimant contract's failure leg: a claimed job that will never
        // start must be cancelled, and cancellation frees the slot.
        claimed.cancel()
        assertTrue(YomitanAutoUpdateOrchestrator.tryClaimSlot(rival))

        // Hygiene: leave the slot free.
        rival.cancel()
    }
}
