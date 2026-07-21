package com.playtranslate

import android.view.InputDevice
import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The input-source policy behind hotkeys. Two predicates over the same key
 * stream: [isHotkeySource] ("the user pressed something on purpose") and
 * [isGameInputSource] ("the player is working the controls"). They used to be
 * one gate, which is why a keyboard could not fire a hotkey.
 *
 * Runs on a plain JVM: only compile-time `SOURCE_*` constants are referenced,
 * and the compiler inlines them.
 */
class HotkeySourceTest {

    // Real masks as reported by InputDevice.getSources(). All of the
    // button-class ones share SOURCE_CLASS_BUTTON (0x1), which is exactly the
    // overlap a naive `and != 0` test would trip over.
    private val KEYBOARD = InputDevice.SOURCE_KEYBOARD                  // 0x101
    private val DPAD = InputDevice.SOURCE_DPAD                          // 0x201
    private val GAMEPAD = InputDevice.SOURCE_GAMEPAD                    // 0x401
    private val CONTROLLER = GAMEPAD or DPAD or KEYBOARD                // 0x701, typical pad
    private val TOUCHSCREEN = InputDevice.SOURCE_TOUCHSCREEN
    private val JOYSTICK_ONLY = InputDevice.SOURCE_JOYSTICK

    // ── The widening: keyboards drive hotkeys ──────────────────────────

    @Test
    fun `plain keyboard is hotkey-eligible`() {
        assertTrue(isHotkeySource(KEYBOARD))
    }

    @Test
    fun `plain keyboard is not gameplay input`() {
        assertFalse(isGameInputSource(KEYBOARD))
    }

    // ── No regression: everything that worked before still works ───────

    @Test
    fun `gamepad drives both roles`() {
        assertTrue(isHotkeySource(GAMEPAD))
        assertTrue(isGameInputSource(GAMEPAD))
    }

    @Test
    fun `dpad drives both roles`() {
        assertTrue(isHotkeySource(DPAD))
        assertTrue(isGameInputSource(DPAD))
    }

    @Test
    fun `combined controller mask drives both roles`() {
        assertTrue(isHotkeySource(CONTROLLER))
        assertTrue(isGameInputSource(CONTROLLER))
    }

    // ── Non-button sources stay out of both ────────────────────────────

    @Test
    fun `touchscreen drives neither role`() {
        assertFalse(isHotkeySource(TOUCHSCREEN))
        assertFalse(isGameInputSource(TOUCHSCREEN))
    }

    @Test
    fun `joystick-only source drives neither role`() {
        // Covered instead by the gamepad-keycode escape hatch in
        // PlayTranslateAccessibilityService.isHotkeyEligible.
        assertFalse(isHotkeySource(JOYSTICK_ONLY))
        assertFalse(isGameInputSource(JOYSTICK_ONLY))
    }

    @Test
    fun `class-button bit alone is not a source match`() {
        // The bug a bare `and mask != 0` would cause: every button-class
        // source matching every other.
        assertFalse(isHotkeySource(InputDevice.SOURCE_CLASS_BUTTON))
        assertFalse(isGameInputSource(InputDevice.SOURCE_CLASS_BUTTON))
    }

    // ── Which sources can type ─────────────────────────────────────────

    @Test
    fun `keyboard bit marks a source as text-capable`() {
        assertTrue(isKeyboardSource(KEYBOARD))
        // The common handheld / controller mask carries the keyboard bit too,
        // which is why the printing-key rule has to be decided per keycode
        // rather than per device: these buttons produce no glyph and pass.
        assertTrue(isKeyboardSource(CONTROLLER))
    }

    @Test
    fun `controller-only sources are not text-capable`() {
        assertFalse(isKeyboardSource(GAMEPAD))
        assertFalse(isKeyboardSource(DPAD))
        assertFalse(isKeyboardSource(JOYSTICK_ONLY))
    }

    // ── The typing-collision warning ───────────────────────────────────

    private val T = KeyEvent.KEYCODE_T

    @Test
    fun `a bare typing key warns`() {
        assertTrue(comboTakesTypingKey(heldKeys = setOf(T), typingKeys = setOf(T)))
    }

    @Test
    fun `a combo with no typing key never warns`() {
        // F5, and a controller button: nothing to take away from typing.
        assertFalse(comboTakesTypingKey(setOf(KeyEvent.KEYCODE_F5), emptySet()))
        assertFalse(comboTakesTypingKey(setOf(KeyEvent.KEYCODE_BUTTON_A), emptySet()))
    }

    @Test
    fun `ctrl and meta qualify a typing key as a command`() {
        assertFalse(comboTakesTypingKey(setOf(KeyEvent.KEYCODE_CTRL_LEFT, T), setOf(T)))
        assertFalse(comboTakesTypingKey(setOf(KeyEvent.KEYCODE_CTRL_RIGHT, T), setOf(T)))
        assertFalse(comboTakesTypingKey(setOf(KeyEvent.KEYCODE_META_LEFT, T), setOf(T)))
    }

    @Test
    fun `left alt qualifies but right alt does not`() {
        // Right Alt is AltGr on 40 of the 46 layouts Android ships, where it
        // types rather than commands: AltGr+E is € on German. Warning on it is
        // the point, not an oversight.
        assertFalse(comboTakesTypingKey(setOf(KeyEvent.KEYCODE_ALT_LEFT, T), setOf(T)))
        assertTrue(comboTakesTypingKey(setOf(KeyEvent.KEYCODE_ALT_RIGHT, T), setOf(T)))
    }

    @Test
    fun `shift does not qualify a typing key`() {
        // Shift+T still types a letter.
        assertTrue(comboTakesTypingKey(setOf(KeyEvent.KEYCODE_SHIFT_LEFT, T), setOf(T)))
        assertTrue(comboTakesTypingKey(setOf(KeyEvent.KEYCODE_SHIFT_RIGHT, T), setOf(T)))
    }

    @Test
    fun `a command modifier anywhere in the combo is enough`() {
        assertFalse(
            comboTakesTypingKey(
                setOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_SHIFT_LEFT, T),
                setOf(T),
            )
        )
    }

    // ── The invariant onKeyEvent's early return depends on ─────────────

    @Test
    fun `gameplay sources are a subset of hotkey sources`() {
        // onKeyEvent bails on !hotkeyInput before ever consulting gameInput,
        // so a gameplay source that is not hotkey-eligible would be silently
        // dropped from live mode's interaction signal.
        val masks = listOf(
            KEYBOARD, DPAD, GAMEPAD, CONTROLLER, TOUCHSCREEN, JOYSTICK_ONLY,
            InputDevice.SOURCE_CLASS_BUTTON,
            InputDevice.SOURCE_MOUSE,
            InputDevice.SOURCE_STYLUS,
            InputDevice.SOURCE_HDMI,
            InputDevice.SOURCE_UNKNOWN,
            InputDevice.SOURCE_ANY,
        )
        for (mask in masks) {
            if (isGameInputSource(mask)) {
                assertTrue(
                    "gameplay source 0x${mask.toString(16)} must be hotkey-eligible",
                    isHotkeySource(mask)
                )
            }
        }
    }
}
