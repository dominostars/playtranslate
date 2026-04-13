package com.playtranslate

/**
 * Pure decision logic for hotkey combo detection. Extracted from
 * [PlayTranslateAccessibilityService.checkHotkeyCombos] so the state machine
 * can be unit-tested without mocking the accessibility service.
 *
 * The problem this solves: if the user binds both `A` and `A+B`, pressing
 * them together can briefly look like "A alone" for a few ms before `B`
 * arrives, causing the wrong combo to fire. The fix is to defer any combo
 * that is a proper subset of another configured combo (a "shadowed" combo)
 * by a short window, giving the chord a chance to complete before we
 * commit. Combos that aren't shadowed fire immediately — zero latency.
 */

/** A configured hotkey: a set of keycodes bound to an overlay mode. */
data class HotkeyCombo(
    val keys: Set<Int>,
    val mode: OverlayMode,
)

/** Snapshot of mutable hotkey state, used as input to [decideHotkeyAction]. */
data class HotkeyState(
    val activeMode: OverlayMode?,
    val pendingMode: OverlayMode?,
)

/** Action the caller should apply after calling [decideHotkeyAction]. */
sealed class HotkeyAction {
    /** No state transition — nothing to do. */
    object NoChange : HotkeyAction()

    /** Activate [mode] immediately. Any pending activation should be cancelled. */
    data class ActivateNow(val mode: OverlayMode) : HotkeyAction()

    /**
     * Defer activation of [mode] by the combo window. Any prior pending
     * activation should be cancelled and a new deferred activation scheduled.
     */
    data class DeferActivation(val mode: OverlayMode) : HotkeyAction()

    /** The currently active combo was released. Fire release and clear state. */
    object Release : HotkeyAction()

    /** The pending combo was released before the window expired. Clear pending. */
    object ClearPending : HotkeyAction()
}

/**
 * Decide what action the hotkey state machine should take given the current
 * set of held keycodes, current state, and configured combos.
 *
 * Design notes:
 * - Active combos are not upgraded to larger combos while held. Once `A+B`
 *   has activated, pressing an extra `C` (even if `A+B+C` is configured)
 *   does not swap modes — the user must release and re-press to change.
 * - A "shadowed" combo (proper subset of another configured combo) is
 *   always deferred. Non-shadowed combos fire immediately.
 * - When a combo is pending and a larger combo subsequently becomes fully
 *   held, the pending combo is superseded by the larger one, which itself
 *   is either activated immediately or re-deferred depending on whether it
 *   is also shadowed.
 * - If [reachable] is false (the user has no visible indication the app is
 *   listening — icon hidden and app backgrounded), new activations are
 *   suppressed. Release of an already-active combo still flows through so
 *   state cannot latch across a gate closure.
 */
fun decideHotkeyAction(
    held: Set<Int>,
    state: HotkeyState,
    combos: List<HotkeyCombo>,
    reachable: Boolean = true,
): HotkeyAction {
    // 0. Gate: if the user can't see the app and no combo is currently
    //    active, ignore new presses. A pending activation must be cleared
    //    so the deferred runnable does not fire into empty space. An
    //    already-active combo is allowed to flow through so it can release
    //    cleanly — otherwise state would latch until the service restarts.
    if (!reachable && state.activeMode == null) {
        return if (state.pendingMode != null) HotkeyAction.ClearPending
            else HotkeyAction.NoChange
    }

    // 1. If a combo is already active, only check whether it is still held.
    //    We deliberately do not upgrade to a larger combo mid-hold.
    state.activeMode?.let { active ->
        val activeCombo = combos.firstOrNull { it.mode == active }
        return if (activeCombo == null || !held.containsAll(activeCombo.keys)) {
            HotkeyAction.Release
        } else {
            HotkeyAction.NoChange
        }
    }

    // 2. Find the longest configured combo currently satisfied by held keys.
    val best = combos
        .filter { it.keys.isNotEmpty() && held.containsAll(it.keys) }
        .maxByOrNull { it.keys.size }

    // 3. If a combo is pending, either keep waiting, supersede it with a
    //    larger match, or clear it if the user released.
    state.pendingMode?.let { pending ->
        if (best == null) {
            // Pending combo is no longer fully held — user released before
            // the window expired. Swallow the tap.
            return HotkeyAction.ClearPending
        }
        if (best.mode == pending) {
            // Same pending combo is still the best match. Let the timer tick.
            return HotkeyAction.NoChange
        }
        // A larger combo is now matched. Supersede the pending one.
        return if (isShadowed(best, combos)) {
            HotkeyAction.DeferActivation(best.mode)
        } else {
            HotkeyAction.ActivateNow(best.mode)
        }
    }

    // 4. No active, no pending. If a combo now matches, activate or defer.
    if (best == null) return HotkeyAction.NoChange
    return if (isShadowed(best, combos)) {
        HotkeyAction.DeferActivation(best.mode)
    } else {
        HotkeyAction.ActivateNow(best.mode)
    }
}

/**
 * True if [combo] is a proper subset of any other combo in [all]. A shadowed
 * combo cannot fire immediately: we must wait for the detection window to
 * see whether the user is building toward its superset. Self-comparison is
 * harmless — a set cannot be strictly larger than itself.
 */
private fun isShadowed(combo: HotkeyCombo, all: List<HotkeyCombo>): Boolean {
    return all.any { other ->
        other.keys.size > combo.keys.size && other.keys.containsAll(combo.keys)
    }
}
