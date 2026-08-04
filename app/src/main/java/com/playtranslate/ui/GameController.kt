package com.playtranslate.ui

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent

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
