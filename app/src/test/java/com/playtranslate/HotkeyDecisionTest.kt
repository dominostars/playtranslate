package com.playtranslate

import org.junit.Assert.assertEquals
import org.junit.Test

class HotkeyDecisionTest {

    private val TRANSLATION = OverlayMode.TRANSLATION
    private val FURIGANA = OverlayMode.FURIGANA

    private fun combo(mode: OverlayMode, vararg keys: Int) =
        HotkeyCombo(keys.toSet(), mode)

    // ── Baseline / no-op cases ─────────────────────────────────────────

    @Test
    fun `no combos configured returns NoChange regardless of held keys`() {
        val action = decideHotkeyAction(
            held = setOf(1, 2, 3),
            state = HotkeyState(activeMode = null, pendingMode = null),
            combos = emptyList(),
        )
        assertEquals(HotkeyAction.NoChange, action)
    }

    @Test
    fun `no held keys with combos configured returns NoChange`() {
        val action = decideHotkeyAction(
            held = emptySet(),
            state = HotkeyState(activeMode = null, pendingMode = null),
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
            state = HotkeyState(activeMode = null, pendingMode = TRANSLATION),
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
            state = HotkeyState(activeMode = null, pendingMode = TRANSLATION),
            combos = listOf(
                combo(TRANSLATION, 1),
                combo(FURIGANA, 1, 2),
            ),
        )
        assertEquals(HotkeyAction.NoChange, action)
    }

    @Test
    fun `pending subset — combo released before window expires — clears pending`() {
        // User tapped A and released before the 60ms window expired.
        val action = decideHotkeyAction(
            held = emptySet(),
            state = HotkeyState(activeMode = null, pendingMode = TRANSLATION),
            combos = listOf(
                combo(TRANSLATION, 1),
                combo(FURIGANA, 1, 2),
            ),
        )
        assertEquals(HotkeyAction.ClearPending, action)
    }

    @Test
    fun `pending subset — intermediate combo still shadowed — re-defers to new pending`() {
        // Chain: A → A+B → A+B+C all configured. User holding A, then presses B.
        // The new best match is A+B, which is itself a subset of A+B+C, so defer.
        // Using TRANSLATION for A, FURIGANA for A+B; A+B+C has no mode in the
        // 2-mode enum so simulate with a 3rd synthetic combo would need a third
        // OverlayMode. Instead simulate with A → TRANSLATION, A+B → FURIGANA,
        // and a 3-key superset also bound to TRANSLATION (same mode, different
        // keys is fine for this pure function since we key shadow by set size).
        val action = decideHotkeyAction(
            held = setOf(1, 2),
            state = HotkeyState(activeMode = null, pendingMode = TRANSLATION),
            combos = listOf(
                combo(TRANSLATION, 1),
                combo(FURIGANA, 1, 2),
                // Pretend there's a third binding that shadows A+B. Since the
                // decision logic keys shadowing by key-set containment (not by
                // mode identity), reusing TRANSLATION here is fine for this test.
                combo(TRANSLATION, 1, 2, 3),
            ),
        )
        assertEquals(HotkeyAction.DeferActivation(FURIGANA), action)
    }

    // ── Active combo release ───────────────────────────────────────────

    @Test
    fun `active combo still fully held returns NoChange`() {
        val action = decideHotkeyAction(
            held = setOf(1, 2),
            state = HotkeyState(activeMode = FURIGANA, pendingMode = null),
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
            state = HotkeyState(activeMode = FURIGANA, pendingMode = null),
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
            state = HotkeyState(activeMode = TRANSLATION, pendingMode = null),
            combos = listOf(combo(TRANSLATION, 1)),
        )
        assertEquals(HotkeyAction.Release, action)
    }

    @Test
    fun `active combo no longer in config — defensive release`() {
        // Simulates the user rebinding the hotkey while a combo is active.
        val action = decideHotkeyAction(
            held = setOf(1),
            state = HotkeyState(activeMode = FURIGANA, pendingMode = null),
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
            state = HotkeyState(activeMode = TRANSLATION, pendingMode = null),
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

    // ── Reachability gate ──────────────────────────────────────────────

    @Test
    fun `unreachable with no active combo and no pending suppresses activation`() {
        // Icon hidden, app backgrounded, user presses a hotkey. Should
        // drop the event rather than fire a ghost overlay.
        val action = decideHotkeyAction(
            held = setOf(1),
            state = HotkeyState(activeMode = null, pendingMode = null),
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
            state = HotkeyState(activeMode = null, pendingMode = TRANSLATION),
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
            state = HotkeyState(activeMode = FURIGANA, pendingMode = null),
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
            state = HotkeyState(activeMode = FURIGANA, pendingMode = null),
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
            state = HotkeyState(activeMode = null, pendingMode = null),
            combos = listOf(combo(TRANSLATION, 1)),
            reachable = false,
        )
        assertEquals(HotkeyAction.NoChange, action)
    }
}
