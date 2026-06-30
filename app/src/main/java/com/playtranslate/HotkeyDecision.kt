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
 *
 * The machine is agnostic to a combo's [HotkeyTrigger]: HOLD and TAP combos
 * flow through identically (activate on press, "release" on key-up). The
 * trigger only changes what the *caller* does on activation/release — a HOLD
 * shows a momentary overlay preview, a TAP toggles the persistent auto
 * session — so the shadowing window must consider all four bindings together
 * (a TAP combo can shadow a HOLD combo and vice-versa).
 */

/** How a hotkey combo is triggered. */
enum class HotkeyTrigger { HOLD, TAP }

/**
 * The four configurable hotkeys. Each binds a key combo to an overlay [mode]
 * and a [trigger] style:
 *  - HOLD combos show the overlay only while held (momentary preview).
 *  - TAP combos toggle the persistent auto/live session in [mode].
 *
 * Used as the combo identity throughout the state machine — [OverlayMode]
 * alone can't tell the hold and tap bindings for the same mode apart.
 */
enum class HotkeyAssignment(val mode: OverlayMode, val trigger: HotkeyTrigger) {
    TRANSLATION_HOLD(OverlayMode.TRANSLATION, HotkeyTrigger.HOLD),
    FURIGANA_HOLD(OverlayMode.FURIGANA, HotkeyTrigger.HOLD),
    TRANSLATION_TAP(OverlayMode.TRANSLATION, HotkeyTrigger.TAP),
    FURIGANA_TAP(OverlayMode.FURIGANA, HotkeyTrigger.TAP),
}

/** A configured hotkey: a set of keycodes bound to an [assignment]. */
data class HotkeyCombo(
    val keys: Set<Int>,
    val assignment: HotkeyAssignment,
)

/** Parse a stored combo ("keyCode+keyCode") into a key-set. Blank = empty. */
fun parseHotkeyCombo(stored: String): Set<Int> {
    if (stored.isBlank()) return emptySet()
    return stored.split("+").mapNotNull { it.toIntOrNull() }.toSet()
}

/**
 * Assemble the live combo list from the four stored bindings, dropping unset
 * (empty) ones. When [hasReadingHint] is false the Furigana/Pinyin combos —
 * both hold and tap — are excluded entirely: a source language with no hint
 * layer hides those rows on the Hotkeys page, so a stale binding left over
 * from a prior language must not fire invisibly (the tap variant is worse than
 * the hold, since it would silently start an auto session). Mirrors the
 * `hintTextKind != NONE` gate used by the Hotkeys page and the settings digest.
 */
fun buildHotkeyCombos(
    translationHold: String,
    furiganaHold: String,
    translationTap: String,
    furiganaTap: String,
    hasReadingHint: Boolean,
): List<HotkeyCombo> = listOfNotNull(
    HotkeyCombo(parseHotkeyCombo(translationHold), HotkeyAssignment.TRANSLATION_HOLD),
    if (hasReadingHint)
        HotkeyCombo(parseHotkeyCombo(furiganaHold), HotkeyAssignment.FURIGANA_HOLD) else null,
    HotkeyCombo(parseHotkeyCombo(translationTap), HotkeyAssignment.TRANSLATION_TAP),
    if (hasReadingHint)
        HotkeyCombo(parseHotkeyCombo(furiganaTap), HotkeyAssignment.FURIGANA_TAP) else null,
).filter { it.keys.isNotEmpty() }

/** Snapshot of mutable hotkey state, used as input to [decideHotkeyAction]. */
data class HotkeyState(
    val active: HotkeyAssignment?,
    val pending: HotkeyAssignment?,
)

/** Action the caller should apply after calling [decideHotkeyAction]. */
sealed class HotkeyAction {
    /** No state transition — nothing to do. */
    object NoChange : HotkeyAction()

    /** Activate [assignment] immediately. Any pending activation should be cancelled. */
    data class ActivateNow(val assignment: HotkeyAssignment) : HotkeyAction()

    /**
     * Defer activation of [assignment] by the combo window. Any prior pending
     * activation should be cancelled and a new deferred activation scheduled.
     */
    data class DeferActivation(val assignment: HotkeyAssignment) : HotkeyAction()

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
 *   does not swap assignments — the user must release and re-press to change.
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
    if (!reachable && state.active == null) {
        return if (state.pending != null) HotkeyAction.ClearPending
            else HotkeyAction.NoChange
    }

    // 1. If a combo is already active, only check whether it is still held.
    //    We deliberately do not upgrade to a larger combo mid-hold.
    state.active?.let { active ->
        val activeCombo = combos.firstOrNull { it.assignment == active }
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
    state.pending?.let { pending ->
        if (best == null) {
            // Pending combo is no longer fully held — released before the
            // window expired. A HOLD is swallowed (a brief subset press must
            // not flash a preview); a TAP is exactly a quick tap of a shadowed
            // combo and must still fire its toggle rather than be dropped.
            return if (pending.trigger == HotkeyTrigger.TAP) {
                HotkeyAction.ActivateNow(pending)
            } else {
                HotkeyAction.ClearPending
            }
        }
        if (best.assignment == pending) {
            // Same pending combo is still the best match. Let the timer tick.
            return HotkeyAction.NoChange
        }
        // A larger combo is now matched. Supersede the pending one.
        return if (isShadowed(best, combos)) {
            HotkeyAction.DeferActivation(best.assignment)
        } else {
            HotkeyAction.ActivateNow(best.assignment)
        }
    }

    // 4. No active, no pending. If a combo now matches, activate or defer.
    if (best == null) return HotkeyAction.NoChange
    return if (isShadowed(best, combos)) {
        HotkeyAction.DeferActivation(best.assignment)
    } else {
        HotkeyAction.ActivateNow(best.assignment)
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

/**
 * "Instant hold, tap on quick release" — lets the user bind the *same* key
 * combo to both a HOLD and a TAP action.
 *
 * When a key-set carries both, [decideHotkeyAction] resolves it to the HOLD
 * (holds are listed first, so `maxByOrNull` ties break to them), and its
 * preview shows immediately on press. This decides what the *release* does:
 * if the press was brief — held under [thresholdMs] — and a TAP shares the
 * released hold's exact key-set, the release also fires that TAP (so a quick
 * tap toggles auto, with the preview only flashing). A longer press is a pure
 * hold and returns null.
 *
 * Pure so the tap/hold-on-one-button timing is unit-testable without the
 * accessibility service.
 *
 * @param releasedHold the assignment whose hold preview just ended.
 * @param heldDurationMs how long that hold was active before release.
 * @param thresholdMs the longest press that still counts as a tap.
 * @param combos the currently-configured combos (empty key-sets filtered out).
 * @return the TAP assignment to fire, or null to leave it a pure hold.
 */
fun tapOnQuickRelease(
    releasedHold: HotkeyAssignment,
    heldDurationMs: Long,
    thresholdMs: Long,
    combos: List<HotkeyCombo>,
): HotkeyAssignment? {
    if (releasedHold.trigger != HotkeyTrigger.HOLD) return null
    if (heldDurationMs >= thresholdMs) return null
    val holdKeys = combos.firstOrNull { it.assignment == releasedHold }?.keys
    if (holdKeys.isNullOrEmpty()) return null
    return combos.firstOrNull {
        it.assignment.trigger == HotkeyTrigger.TAP && it.keys == holdKeys
    }?.assignment
}

/**
 * Whether a just-activated combo should be tracked as the "active" assignment
 * so a later key-up releases it. True only while its keys are still held.
 *
 * A tap fired on quick-release of a shadowed combo (its keys already up — see
 * the pending-TAP branch of [decideHotkeyAction]) has nothing left to release;
 * latching it would make an immediate re-tap of the same key look like the
 * combo is "still active" and get swallowed. Holds and on-press taps activate
 * while their keys are down, so they latch normally.
 */
fun shouldLatchActive(activatedKeys: Set<Int>, heldKeys: Set<Int>): Boolean =
    activatedKeys.isNotEmpty() && heldKeys.containsAll(activatedKeys)
