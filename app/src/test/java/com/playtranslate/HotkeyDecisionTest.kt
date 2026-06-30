package com.playtranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HotkeyDecisionTest {

    // The pure machine is trigger-agnostic, so most cases use the HOLD
    // assignments; dedicated cases below cover TAP and mixed hold/tap shadowing.
    private val TRANSLATION = HotkeyAssignment.TRANSLATION_HOLD
    private val FURIGANA = HotkeyAssignment.FURIGANA_HOLD

    private fun combo(assignment: HotkeyAssignment, vararg keys: Int) =
        HotkeyCombo(keys.toSet(), assignment)

    // ── Baseline / no-op cases ─────────────────────────────────────────

    @Test
    fun `no combos configured returns NoChange regardless of held keys`() {
        val action = decideHotkeyAction(
            held = setOf(1, 2, 3),
            state = HotkeyState(active = null, pending = null),
            combos = emptyList(),
        )
        assertEquals(HotkeyAction.NoChange, action)
    }

    @Test
    fun `no held keys with combos configured returns NoChange`() {
        val action = decideHotkeyAction(
            held = emptySet(),
            state = HotkeyState(active = null, pending = null),
            combos = listOf(
                combo(TRANSLATION, 1),
                combo(FURIGANA, 1, 2),
            ),
        )
        assertEquals(HotkeyAction.NoChange, action)
    }

    // ── Immediate activation (no shadowing) ────────────────────────────

    @Test
    fun `single non-shadowed combo fully held activates immediately`() {
        val action = decideHotkeyAction(
            held = setOf(1),
            state = HotkeyState(null, null),
            combos = listOf(combo(TRANSLATION, 1)),
        )
        assertEquals(HotkeyAction.ActivateNow(TRANSLATION), action)
    }

    @Test
    fun `two non-overlapping combos — held matches one — activates immediately`() {
        val action = decideHotkeyAction(
            held = setOf(1),
            state = HotkeyState(null, null),
            combos = listOf(
                combo(TRANSLATION, 1),
                combo(FURIGANA, 10, 11),
            ),
        )
        assertEquals(HotkeyAction.ActivateNow(TRANSLATION), action)
    }

    @Test
    fun `two same-size non-subset combos — held matches one — activates immediately`() {
        // A+B vs A+C — neither is a subset of the other, no shadowing.
        val action = decideHotkeyAction(
            held = setOf(1, 2),
            state = HotkeyState(null, null),
            combos = listOf(
                combo(TRANSLATION, 1, 2),
                combo(FURIGANA, 1, 3),
            ),
        )
        assertEquals(HotkeyAction.ActivateNow(TRANSLATION), action)
    }

    // ── Deferred activation (shadowed combos) ──────────────────────────

    @Test
    fun `shadowed combo — subset held alone — defers activation`() {
        // A bound to TRANSLATION, A+B bound to FURIGANA. User presses A.
        val action = decideHotkeyAction(
            held = setOf(1),
            state = HotkeyState(null, null),
            combos = listOf(
                combo(TRANSLATION, 1),
                combo(FURIGANA, 1, 2),
            ),
        )
        assertEquals(HotkeyAction.DeferActivation(TRANSLATION), action)
    }

    @Test
    fun `shadowed combo — superset fully held — activates superset immediately`() {
        // Both A and A+B bound. User presses A+B together.
        val action = decideHotkeyAction(
            held = setOf(1, 2),
            state = HotkeyState(null, null),
            combos = listOf(
                combo(TRANSLATION, 1),
                combo(FURIGANA, 1, 2),
            ),
        )
        assertEquals(HotkeyAction.ActivateNow(FURIGANA), action)
    }

    // ── Pending → superset transitions ─────────────────────────────────

    @Test
    fun `pending subset — superset now fully held — supersedes with superset`() {
        // A was deferred; user then pressed B while still holding A.
        val action = decideHotkeyAction(
            held = setOf(1, 2),
            state = HotkeyState(active = null, pending = TRANSLATION),
            combos = listOf(
                combo(TRANSLATION, 1),
                combo(FURIGANA, 1, 2),
            ),
        )
        assertEquals(HotkeyAction.ActivateNow(FURIGANA), action)
    }

    @Test
    fun `pending subset — still only subset held — keeps waiting`() {
        val action = decideHotkeyAction(
            held = setOf(1),
            state = HotkeyState(active = null, pending = TRANSLATION),
            combos = listOf(
                combo(TRANSLATION, 1),
                combo(FURIGANA, 1, 2),
            ),
        )
        assertEquals(HotkeyAction.NoChange, action)
    }

    @Test
    fun `pending subset HOLD — combo released before window expires — clears pending`() {
        // HOLD case: a brief subset press released before the 60ms window must
        // be swallowed, so an accidental tap of a shadowed hold key never
        // flashes a hold preview. Contrast the TAP case below.
        val action = decideHotkeyAction(
            held = emptySet(),
            state = HotkeyState(active = null, pending = TRANSLATION),
            combos = listOf(
                combo(TRANSLATION, 1),
                combo(FURIGANA, 1, 2),
            ),
        )
        assertEquals(HotkeyAction.ClearPending, action)
    }

    @Test
    fun `pending subset TAP — combo released before window expires — fires the tap`() {
        // Codex adversarial-review finding (no-ship): A bound to a TAP, A+B to a
        // larger combo. Pressing A defers it (shadowed by A+B); releasing before
        // the 60ms window must still TOGGLE. A tap-to-toggle hotkey that happens
        // to overlap a larger combo cannot be silently dropped on a quick tap —
        // and unlike a HOLD (above), there is no preview to spuriously flash.
        val action = decideHotkeyAction(
            held = emptySet(),
            state = HotkeyState(active = null, pending = HotkeyAssignment.TRANSLATION_TAP),
            combos = listOf(
                combo(HotkeyAssignment.TRANSLATION_TAP, 1),
                combo(HotkeyAssignment.TRANSLATION_HOLD, 1, 2),
            ),
        )
        assertEquals(HotkeyAction.ActivateNow(HotkeyAssignment.TRANSLATION_TAP), action)
    }

    @Test
    fun `pending subset — intermediate combo still shadowed — re-defers to new pending`() {
        // Chain: A → A+B → A+B+C all configured. User holding A, then presses B.
        // The new best match is A+B, which is itself a subset of A+B+C, so defer.
        // The 3-key superset is a TAP binding here, exercising mixed hold/tap
        // shadowing (the machine keys shadowing by key-set containment, not by
        // assignment identity).
        val action = decideHotkeyAction(
            held = setOf(1, 2),
            state = HotkeyState(active = null, pending = TRANSLATION),
            combos = listOf(
                combo(TRANSLATION, 1),
                combo(FURIGANA, 1, 2),
                combo(HotkeyAssignment.TRANSLATION_TAP, 1, 2, 3),
            ),
        )
        assertEquals(HotkeyAction.DeferActivation(FURIGANA), action)
    }

    // ── Active combo release ───────────────────────────────────────────

    @Test
    fun `active combo still fully held returns NoChange`() {
        val action = decideHotkeyAction(
            held = setOf(1, 2),
            state = HotkeyState(active = FURIGANA, pending = null),
            combos = listOf(
                combo(TRANSLATION, 1),
                combo(FURIGANA, 1, 2),
            ),
        )
        assertEquals(HotkeyAction.NoChange, action)
    }

    @Test
    fun `active combo partially released returns Release`() {
        val action = decideHotkeyAction(
            held = setOf(1),
            state = HotkeyState(active = FURIGANA, pending = null),
            combos = listOf(
                combo(TRANSLATION, 1),
                combo(FURIGANA, 1, 2),
            ),
        )
        assertEquals(HotkeyAction.Release, action)
    }

    @Test
    fun `active combo fully released returns Release`() {
        val action = decideHotkeyAction(
            held = emptySet(),
            state = HotkeyState(active = TRANSLATION, pending = null),
            combos = listOf(combo(TRANSLATION, 1)),
        )
        assertEquals(HotkeyAction.Release, action)
    }

    @Test
    fun `active combo no longer in config — defensive release`() {
        // Simulates the user rebinding the hotkey while a combo is active.
        val action = decideHotkeyAction(
            held = setOf(1),
            state = HotkeyState(active = FURIGANA, pending = null),
            combos = listOf(combo(TRANSLATION, 1)),
        )
        assertEquals(HotkeyAction.Release, action)
    }

    @Test
    fun `active combo does not upgrade to larger configured combo`() {
        // Explicit invariant: once activated, holding additional keys that
        // would satisfy a larger combo must not swap modes.
        val action = decideHotkeyAction(
            held = setOf(1, 2),
            state = HotkeyState(active = TRANSLATION, pending = null),
            combos = listOf(
                combo(TRANSLATION, 1),
                combo(FURIGANA, 1, 2),
            ),
        )
        assertEquals(HotkeyAction.NoChange, action)
    }

    // ── "Extra" held keys ──────────────────────────────────────────────

    @Test
    fun `extra held keys do not prevent matching a configured combo`() {
        // User holds an unrelated key plus the TRANSLATION combo.
        val action = decideHotkeyAction(
            held = setOf(1, 99),
            state = HotkeyState(null, null),
            combos = listOf(combo(TRANSLATION, 1)),
        )
        assertEquals(HotkeyAction.ActivateNow(TRANSLATION), action)
    }

    @Test
    fun `best-combo selection prefers the longest matching combo`() {
        // Held = A+B+C. Configured: A (T) and A+B (F). Both match. A+B wins.
        val action = decideHotkeyAction(
            held = setOf(1, 2, 3),
            state = HotkeyState(null, null),
            combos = listOf(
                combo(TRANSLATION, 1),
                combo(FURIGANA, 1, 2),
            ),
        )
        assertEquals(HotkeyAction.ActivateNow(FURIGANA), action)
    }

    // ── Tap combos + mixed hold/tap shadowing ──────────────────────────

    @Test
    fun `tap combo flows through identically — activates immediately`() {
        val action = decideHotkeyAction(
            held = setOf(5),
            state = HotkeyState(null, null),
            combos = listOf(combo(HotkeyAssignment.TRANSLATION_TAP, 5)),
        )
        assertEquals(HotkeyAction.ActivateNow(HotkeyAssignment.TRANSLATION_TAP), action)
    }

    @Test
    fun `hold subset shadowed by a tap superset defers across triggers`() {
        // A bound to a HOLD combo, A+B bound to a TAP combo. Pressing A alone
        // must defer — the chord might still be completing toward the tap.
        val action = decideHotkeyAction(
            held = setOf(1),
            state = HotkeyState(null, null),
            combos = listOf(
                combo(HotkeyAssignment.TRANSLATION_HOLD, 1),
                combo(HotkeyAssignment.FURIGANA_TAP, 1, 2),
            ),
        )
        assertEquals(HotkeyAction.DeferActivation(HotkeyAssignment.TRANSLATION_HOLD), action)
    }

    @Test
    fun `tap superset fires immediately over a hold subset`() {
        val action = decideHotkeyAction(
            held = setOf(1, 2),
            state = HotkeyState(null, null),
            combos = listOf(
                combo(HotkeyAssignment.TRANSLATION_HOLD, 1),
                combo(HotkeyAssignment.FURIGANA_TAP, 1, 2),
            ),
        )
        assertEquals(HotkeyAction.ActivateNow(HotkeyAssignment.FURIGANA_TAP), action)
    }

    @Test
    fun `active tap combo released returns Release`() {
        // The caller turns a tap Release into a no-op (the toggle already fired
        // on activation); the machine still reports Release so state clears and
        // the next press can re-fire.
        val action = decideHotkeyAction(
            held = emptySet(),
            state = HotkeyState(active = HotkeyAssignment.FURIGANA_TAP, pending = null),
            combos = listOf(combo(HotkeyAssignment.FURIGANA_TAP, 5)),
        )
        assertEquals(HotkeyAction.Release, action)
    }

    // ── Reachability gate ──────────────────────────────────────────────

    @Test
    fun `unreachable with no active combo and no pending suppresses activation`() {
        // Icon hidden, app backgrounded, user presses a hotkey. Should
        // drop the event rather than fire a ghost overlay.
        val action = decideHotkeyAction(
            held = setOf(1),
            state = HotkeyState(active = null, pending = null),
            combos = listOf(combo(TRANSLATION, 1)),
            reachable = false,
        )
        assertEquals(HotkeyAction.NoChange, action)
    }

    @Test
    fun `unreachable with pending activation clears it`() {
        // User started a chord while reachable, then gate closed before
        // the deferral window expired. Pending must be cleared.
        val action = decideHotkeyAction(
            held = setOf(1),
            state = HotkeyState(active = null, pending = TRANSLATION),
            combos = listOf(
                combo(TRANSLATION, 1),
                combo(FURIGANA, 1, 2),
            ),
            reachable = false,
        )
        assertEquals(HotkeyAction.ClearPending, action)
    }

    @Test
    fun `unreachable with active combo still held returns NoChange`() {
        // Combo was activated while reachable, then gate closed. User is
        // still holding the combo. Must not latch — state should flow
        // normally so release can clean up.
        val action = decideHotkeyAction(
            held = setOf(1, 2),
            state = HotkeyState(active = FURIGANA, pending = null),
            combos = listOf(
                combo(TRANSLATION, 1),
                combo(FURIGANA, 1, 2),
            ),
            reachable = false,
        )
        assertEquals(HotkeyAction.NoChange, action)
    }

    @Test
    fun `unreachable with active combo released returns Release`() {
        // Critical: if the gate closed while a combo was held, releasing
        // it must still fire Release. Otherwise activeMode latches forever.
        val action = decideHotkeyAction(
            held = emptySet(),
            state = HotkeyState(active = FURIGANA, pending = null),
            combos = listOf(
                combo(TRANSLATION, 1),
                combo(FURIGANA, 1, 2),
            ),
            reachable = false,
        )
        assertEquals(HotkeyAction.Release, action)
    }

    @Test
    fun `unreachable with empty held and no state returns NoChange`() {
        val action = decideHotkeyAction(
            held = emptySet(),
            state = HotkeyState(active = null, pending = null),
            combos = listOf(combo(TRANSLATION, 1)),
            reachable = false,
        )
        assertEquals(HotkeyAction.NoChange, action)
    }

    // ── Same key bound to both Hold and Tap (instant hold + tap-on-release) ──

    @Test
    fun `a key bound to both hold and tap resolves to the hold on press`() {
        // Load-bearing for "instant hold": the hold preview must win combo
        // resolution so it shows immediately; the tap fires later, on a quick
        // release (see tapOnQuickRelease). Holds are listed first so the
        // same-size tie breaks to them.
        val action = decideHotkeyAction(
            held = setOf(1),
            state = HotkeyState(null, null),
            combos = listOf(
                combo(HotkeyAssignment.TRANSLATION_HOLD, 1),
                combo(HotkeyAssignment.TRANSLATION_TAP, 1),
            ),
        )
        assertEquals(HotkeyAction.ActivateNow(HotkeyAssignment.TRANSLATION_HOLD), action)
    }

    @Test
    fun `quick release of a shared key fires the same-key tap`() {
        val tap = tapOnQuickRelease(
            releasedHold = HotkeyAssignment.TRANSLATION_HOLD,
            heldDurationMs = 120,
            thresholdMs = 350,
            combos = listOf(
                combo(HotkeyAssignment.TRANSLATION_HOLD, 1),
                combo(HotkeyAssignment.TRANSLATION_TAP, 1),
            ),
        )
        assertEquals(HotkeyAssignment.TRANSLATION_TAP, tap)
    }

    @Test
    fun `long press of a shared key does not fire the tap`() {
        val tap = tapOnQuickRelease(
            releasedHold = HotkeyAssignment.TRANSLATION_HOLD,
            heldDurationMs = 900,
            thresholdMs = 350,
            combos = listOf(
                combo(HotkeyAssignment.TRANSLATION_HOLD, 1),
                combo(HotkeyAssignment.TRANSLATION_TAP, 1),
            ),
        )
        assertEquals(null, tap)
    }

    @Test
    fun `release exactly at the threshold is a pure hold`() {
        // Boundary: thresholdMs is exclusive — at the threshold it's a hold.
        val tap = tapOnQuickRelease(
            releasedHold = HotkeyAssignment.TRANSLATION_HOLD,
            heldDurationMs = 350,
            thresholdMs = 350,
            combos = listOf(
                combo(HotkeyAssignment.TRANSLATION_HOLD, 1),
                combo(HotkeyAssignment.TRANSLATION_TAP, 1),
            ),
        )
        assertEquals(null, tap)
    }

    @Test
    fun `quick release with no tap on the same key fires nothing`() {
        // Hold-only key: a quick release is just a short hold, not a tap.
        val tap = tapOnQuickRelease(
            releasedHold = HotkeyAssignment.TRANSLATION_HOLD,
            heldDurationMs = 100,
            thresholdMs = 350,
            combos = listOf(
                combo(HotkeyAssignment.TRANSLATION_HOLD, 1),
                combo(HotkeyAssignment.TRANSLATION_TAP, 9), // tap is on a different key
            ),
        )
        assertEquals(null, tap)
    }

    @Test
    fun `quick release fires a cross-mode tap sharing the same key`() {
        // Hold→Translations on key 1, Tap→auto Furigana also on key 1: the
        // quick release fires the furigana tap (the machine pairs by key-set,
        // not by mode).
        val tap = tapOnQuickRelease(
            releasedHold = HotkeyAssignment.TRANSLATION_HOLD,
            heldDurationMs = 100,
            thresholdMs = 350,
            combos = listOf(
                combo(HotkeyAssignment.TRANSLATION_HOLD, 1),
                combo(HotkeyAssignment.FURIGANA_TAP, 1),
            ),
        )
        assertEquals(HotkeyAssignment.FURIGANA_TAP, tap)
    }

    @Test
    fun `tapOnQuickRelease ignores a released tap assignment`() {
        // Defensive: only a released HOLD can trigger a tap-on-release.
        val tap = tapOnQuickRelease(
            releasedHold = HotkeyAssignment.TRANSLATION_TAP,
            heldDurationMs = 50,
            thresholdMs = 350,
            combos = listOf(combo(HotkeyAssignment.TRANSLATION_TAP, 1)),
        )
        assertEquals(null, tap)
    }

    // ── buildHotkeyCombos: reading-hint gating ─────────────────────────

    @Test
    fun `buildHotkeyCombos includes furigana combos when the language has a hint`() {
        val combos = buildHotkeyCombos(
            translationHold = "1",
            furiganaHold = "2",
            translationTap = "3",
            furiganaTap = "4",
            hasReadingHint = true,
        )
        assertEquals(
            setOf(
                HotkeyAssignment.TRANSLATION_HOLD,
                HotkeyAssignment.FURIGANA_HOLD,
                HotkeyAssignment.TRANSLATION_TAP,
                HotkeyAssignment.FURIGANA_TAP,
            ),
            combos.map { it.assignment }.toSet(),
        )
    }

    @Test
    fun `buildHotkeyCombos drops both furigana hold and tap without a reading hint`() {
        // A stale Furigana/Pinyin binding left over from a prior language must
        // not be loaded for a source with no hint layer — the rows are hidden
        // in the UI, so neither the hold nor the auto-toggle tap may fire.
        val combos = buildHotkeyCombos(
            translationHold = "1",
            furiganaHold = "2",
            translationTap = "3",
            furiganaTap = "4",
            hasReadingHint = false,
        )
        assertEquals(
            setOf(HotkeyAssignment.TRANSLATION_HOLD, HotkeyAssignment.TRANSLATION_TAP),
            combos.map { it.assignment }.toSet(),
        )
    }

    @Test
    fun `buildHotkeyCombos drops unset combos`() {
        val combos = buildHotkeyCombos(
            translationHold = "",
            furiganaHold = "5+6",
            translationTap = "",
            furiganaTap = "",
            hasReadingHint = true,
        )
        assertEquals(listOf(HotkeyAssignment.FURIGANA_HOLD), combos.map { it.assignment })
        assertEquals(setOf(5, 6), combos.first().keys)
    }

    // ── shouldLatchActive (quick-release tap must not latch) ───────────

    @Test
    fun `shouldLatchActive latches while the combo keys are held`() {
        // On-press tap / hold: keys still down, so a key-up must release it.
        assertTrue(shouldLatchActive(activatedKeys = setOf(1, 2), heldKeys = setOf(1, 2, 3)))
    }

    @Test
    fun `shouldLatchActive does not latch when the keys are already released`() {
        // The quick-release tap: keys are up at activation, nothing to release —
        // latching would swallow an immediate re-tap as "still active".
        assertFalse(shouldLatchActive(activatedKeys = setOf(1), heldKeys = emptySet()))
    }

    @Test
    fun `shouldLatchActive does not latch a partially-held combo`() {
        assertFalse(shouldLatchActive(activatedKeys = setOf(1, 2), heldKeys = setOf(1)))
    }

    @Test
    fun `shouldLatchActive does not latch an empty combo`() {
        assertFalse(shouldLatchActive(activatedKeys = emptySet(), heldKeys = setOf(1)))
    }
}
