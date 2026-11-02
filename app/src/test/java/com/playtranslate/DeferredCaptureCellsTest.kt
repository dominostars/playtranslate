package com.playtranslate

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two pure halves of the capture's deferred translation: whether a
 * capture skips MT at all ([shouldDeferTranslation], the service's gate) and
 * whether a deferred result must complete now ([deferredCompletionNeeded],
 * the sheet's funnel). Every cell, so a new mode or trigger has to say what
 * it does with a translation nobody is looking at.
 */
class DeferredCaptureCellsTest {

    @Test fun `the capture defers only when the caller opted in and no consumer needs the translation now`() {
        // allowDefer, sectionHidden, deferWhileParked, samePair -> defers
        val cells = listOf(
            // The caller did not opt in: never.
            listOf(false, false, false, false) to false,
            listOf(false, true, true, false) to false,
            // Opted in: a hidden section defers, a parked panel defers.
            listOf(true, true, false, false) to true,
            listOf(true, false, true, false) to true,
            listOf(true, true, true, false) to true,
            // Opted in, the section visible and the panel up: someone reads it.
            listOf(true, false, false, false) to false,
            // A same-language pair is free: never deferred.
            listOf(true, true, true, true) to false,
            listOf(true, true, false, true) to false,
            listOf(true, false, true, true) to false,
        )
        for ((args, expected) in cells) {
            val (allow, hidden, parked, same) = args
            assertEquals(
                "allow=$allow hidden=$hidden parked=$parked same=$same",
                expected,
                shouldDeferTranslation(allow, sectionHidden = hidden, deferWhileParked = parked, samePair = same),
            )
        }
    }

    @Test fun `translation chips always need the completion`() {
        for (hidden in listOf(false, true)) {
            for (parked in listOf(false, true)) {
                assertEquals(
                    "hidden=$hidden parked=$parked",
                    true,
                    deferredCompletionNeeded(hidden, parked, OverlayMode.TRANSLATION),
                )
            }
        }
    }

    @Test fun `the other modes need it only once the section is visible and the sheet is out of its park`() {
        for (mode in OverlayMode.entries.filter { it != OverlayMode.TRANSLATION }) {
            assertEquals("$mode visible, up", true, deferredCompletionNeeded(false, false, mode))
            assertEquals("$mode visible, parked", false, deferredCompletionNeeded(false, true, mode))
            assertEquals("$mode hidden, up", false, deferredCompletionNeeded(true, false, mode))
            assertEquals("$mode hidden, parked", false, deferredCompletionNeeded(true, true, mode))
        }
    }
}
