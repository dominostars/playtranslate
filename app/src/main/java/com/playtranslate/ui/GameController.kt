package com.playtranslate.ui

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View

/** True when a physical input device that can drive overlay navigation is
 *  connected: a game controller (gamepad / joystick — including a handheld's
 *  built-in controls), or a real hardware keyboard, whose Escape / arrows /
 *  Enter mirror B / dpad / A. Alphabetic + non-virtual filters out the
 *  phantom built-in keyboard devices some firmwares register. Evaluated at
 *  the moment of asking; there is no device listener, so callers re-check
 *  per show, not per frame. Gates whether an overlay window (the capture
 *  sheet, the sticky lens) takes input focus for key navigation. */
fun hasNavInputDevice(ctx: Context): Boolean {
    val inputManager = ctx.getSystemService(InputManager::class.java) ?: return false
    for (id in inputManager.inputDeviceIds) {
        val device = inputManager.getInputDevice(id) ?: continue
        val sources = device.sources
        if (sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        ) {
            return true
        }
        if (!device.isVirtual &&
            device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
        ) {
            return true
        }
    }
    return false
}

/** Keycode classification for controller navigation. Raw gamepad codes are
 *  matched directly — the framework's Generic.kcm fallbacks (BUTTON_B→BACK,
 *  BUTTON_A→DPAD_CENTER) only fire for UNhandled events, and relying on them
 *  would make consumption order-dependent. */
object ControllerKeys {
    /** Dismiss/cancel: the gamepad B button, a system back key, or a hardware
     *  keyboard's Escape. */
    fun isBack(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_ESCAPE,
        -> true
        else -> false
    }

    /** Confirm: the gamepad A button or a keyboard confirm key (hardware
     *  Enter included). */
    fun isActivate(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        -> true
        else -> false
    }

    /** Dpad direction of [keyCode], or null for non-directional keys. Hat-axis
     *  dpads arrive here too: ViewRootImpl synthesizes these keycodes from
     *  AXIS_HAT_X/Y motion the view tree leaves unconsumed. Hardware keyboard
     *  arrow keys arrive here as well — Android reports them AS these DPAD
     *  keycodes; there are no separate arrow codes. */
    fun direction(keyCode: Int): SheetNavGeometry.Dir? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> SheetNavGeometry.Dir.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> SheetNavGeometry.Dir.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> SheetNavGeometry.Dir.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> SheetNavGeometry.Dir.RIGHT
        else -> null
    }

    /** True for every keycode a focused controller-nav surface consumes
     *  (back, activate, dpad). While a focusable sticky lens is up, these
     *  keys are driving it — the a11y key filter's "game input clears the
     *  drag lookup" rule (and its game-input signal) stands down for them.
     *  Hotkey COMBO matching is deliberately NOT gated on this: no real
     *  binding uses gameplay face buttons (they'd fire constantly in-game),
     *  and if one ever shows up the right lever is blocking nav keys at
     *  binding time, like KEYCODE_BACK already is. */
    fun consumesForNav(keyCode: Int): Boolean =
        isBack(keyCode) || isActivate(keyCode) || direction(keyCode) != null
}

/** Delivery of a controller/keyboard activate press INTO a view's own
 *  confirm-key press machinery (View.onKeyDown / onKeyUp) — for the
 *  virtual-cursor navs (capture sheet, sticky lens), whose targets never hold
 *  real focus and so never receive keys from the framework. Routing the press
 *  through the view makes hold behavior the VIEW's own: pressed state on
 *  DOWN, the long-press firing AT the long-press timeout while the key is
 *  still held, the click on an earlier release, no click after a fired
 *  long-press — the same code and timing a touch press runs, not a parallel
 *  reimplementation. BUTTON_A is re-coded to DPAD_CENTER: gamepad buttons are
 *  not View-level confirm keys — the same translation the framework's
 *  Generic.kcm fallback applies, which never runs here because the navs
 *  consume the raw events. */
object ConfirmKeyPress {
    fun down(view: View, ev: KeyEvent) {
        val e = asConfirmKey(ev)
        view.onKeyDown(e.keyCode, e)
    }

    /** Complete the press: the view clicks if its long-press hasn't fired,
     *  and clears its pressed state. A no-op on a view whose press was
     *  [cancel]ed (it is no longer pressed). */
    fun up(view: View, ev: KeyEvent) {
        val e = asConfirmKey(ev)
        view.onKeyUp(e.keyCode, e)
    }

    /** Abandon the press without firing anything (cursor moved away, nav
     *  teardown): the key analogue of a touch ACTION_CANCEL, delivered the
     *  way the framework itself cancels keys — an UP flagged FLAG_CANCELED. */
    fun cancel(view: View) {
        val now = SystemClock.uptimeMillis()
        view.onKeyUp(
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent(
                now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_CANCELED,
            ),
        )
    }

    /** DPAD_CENTER / ENTER / NUMPAD_ENTER pass through unchanged (all View-
     *  level confirm keys); BUTTON_A is re-coded, preserving device identity
     *  and flags (a system-canceled UP must stay canceled). */
    private fun asConfirmKey(ev: KeyEvent): KeyEvent =
        if (ev.keyCode != KeyEvent.KEYCODE_BUTTON_A) ev
        else KeyEvent(
            ev.downTime, ev.eventTime, ev.action, KeyEvent.KEYCODE_DPAD_CENTER,
            ev.repeatCount, ev.metaState, ev.deviceId, ev.scanCode, ev.flags, ev.source,
        )
}

/** Key down/up pair bookkeeping for a focusable overlay window, plus the
 *  dismissal linger that closes the orphaned-UP bug class.
 *
 *  A focused window swallows every key it is delivered — consumed or not,
 *  the app beneath never sees them. So any key that went DOWN on this window
 *  must also come UP on it: if the window is torn down mid-press (an action
 *  fired at the long-press timeout dismissing its own surface — the Anki
 *  one-tap), the release would be delivered, unmatched, to whatever window
 *  is focused next — the game itself in the over-game flows.
 *
 *  [track] records the pairs from the window root's dispatchKeyEvent. At the
 *  window's removal seam, [hasPendingDown] says whether a press is still in
 *  flight; if so the seam hides the window, makes it untouchable (touches
 *  fall through to the app beneath), and calls [beginLinger] with the
 *  deferred removal instead of removing it. The root routes every key to
 *  [lingerKey] while [isLingering]: the invisible window swallows all input,
 *  completing pairs as UPs arrive — including system-synthesized
 *  FLAG_CANCELED UPs, sent when focus moves elsewhere or the device
 *  disconnects — and fires the removal the moment nothing is held. A key
 *  pressed DURING the linger extends it symmetrically: the sink swallowed
 *  its DOWN, so its UP must land here too. The backstop only fires after
 *  [LINGER_BACKSTOP_MS] of key SILENCE (a held key auto-repeats, resetting
 *  it), so it catches a genuinely lost UP without cutting a long hold short.
 *
 *  This makes pair completion structural — an action may freely dismiss its
 *  window mid-press without hand-auditing key state, on every capture
 *  backend. */
class WindowKeyPairGuard {
    private val handler = Handler(Looper.getMainLooper())
    private val pendingDown = mutableSetOf<Int>()
    private var finishRemoval: (() -> Unit)? = null

    /** Call FIRST in the window root's dispatchKeyEvent, before any handler
     *  runs: a handler may dismiss the window synchronously (B-release, an
     *  activating click), and the removal seam it reaches must see this very
     *  event already accounted for — an UP that is being delivered must not
     *  be lingered for, and a DOWN whose handler dismisses must be. */
    fun track(ev: KeyEvent) {
        when (ev.action) {
            KeyEvent.ACTION_DOWN -> pendingDown.add(ev.keyCode)
            KeyEvent.ACTION_UP -> pendingDown.remove(ev.keyCode)
        }
    }

    /** A key that went down on this window is still held. */
    val hasPendingDown: Boolean get() = pendingDown.isNotEmpty()

    val isLingering: Boolean get() = finishRemoval != null

    /** Enter sink mode: [remove] fires once every tracked DOWN has seen its
     *  UP, or after the silence backstop. The caller has already hidden the
     *  window and made it untouchable. */
    fun beginLinger(remove: () -> Unit) {
        finishRemoval = remove
        if (pendingDown.isEmpty()) {
            finish()
            return
        }
        handler.postDelayed(backstop, LINGER_BACKSTOP_MS)
    }

    /** Sink-mode dispatch: swallow everything, complete pairs, and reset the
     *  silence backstop — a held key's auto-repeats keep arriving, so the
     *  backstop can only expire when events truly stopped. */
    fun lingerKey(ev: KeyEvent): Boolean {
        track(ev)
        handler.removeCallbacks(backstop)
        if (pendingDown.isEmpty()) {
            finish()
        } else {
            handler.postDelayed(backstop, LINGER_BACKSTOP_MS)
        }
        return true
    }

    private val backstop = Runnable { finish() }

    private fun finish() {
        val remove = finishRemoval ?: return
        finishRemoval = null
        handler.removeCallbacks(backstop)
        remove()
    }

    companion object {
        /** Key silence after which a lingering window gives up on its UPs and
         *  removes anyway — bounds a stranded invisible window (which would
         *  otherwise hold key focus over the game) when a release is truly
         *  lost. Generous because silence is weak evidence: gamepad buttons
         *  don't reliably auto-repeat, so a deliberately held key can be
         *  silent the whole time — the backstop must comfortably outlast any
         *  real hold. */
        const val LINGER_BACKSTOP_MS = 8000L
    }
}
