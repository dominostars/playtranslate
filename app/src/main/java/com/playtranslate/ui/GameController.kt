package com.playtranslate.ui

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent

/** True when a physical game controller (gamepad / joystick) is connected —
 *  including a handheld's built-in controls, which report as a real
 *  SOURCE_GAMEPAD / SOURCE_JOYSTICK device. Evaluated at the moment of asking;
 *  there is no device listener, so callers re-check per show, not per frame.
 *  Gates whether an overlay window takes input focus for controller
 *  navigation (the capture sheet) or stick-nudge dismissal ([MagnifierLens]). */
fun hasGameController(ctx: Context): Boolean {
    val inputManager = ctx.getSystemService(InputManager::class.java) ?: return false
    for (id in inputManager.inputDeviceIds) {
        val sources = inputManager.getInputDevice(id)?.sources ?: continue
        if (sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
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
    /** Dismiss/cancel: the gamepad B button or a system back key. */
    fun isBack(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B

    /** Confirm: the gamepad A button or a keyboard confirm key. */
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
     *  AXIS_HAT_X/Y motion the view tree leaves unconsumed. */
    fun direction(keyCode: Int): SheetNavGeometry.Dir? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> SheetNavGeometry.Dir.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> SheetNavGeometry.Dir.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> SheetNavGeometry.Dir.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> SheetNavGeometry.Dir.RIGHT
        else -> null
    }
}
