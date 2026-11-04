package com.playtranslate.ui

import com.playtranslate.OneShotOverlayData
import com.playtranslate.OverlayMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the results page does on the game display as it renders
 * ([onScreenStep]): a state without boxes takes down what is up; more of the
 * capture rendered last fills its window while it is up and puts nothing back
 * once it is down; another capture's boxes, told by the capture's id and not
 * by a status rendered in between, replace what is up, going up themselves
 * on two screens in a mode that paints something, the mode being the one the
 * capture asked for itself (tap-to-lookup's Definitions) over the remembered
 * one; with no id on either side, boxes that follow boxes are the same
 * capture. Every cell. And when the page shows its capture-mode row
 * ([showsCaptureModeRow]): only with a capture's result on it, on two
 * screens.
 */
class CapturePresentationRulesTest {

    private val data = OneShotOverlayData(
        boxes = emptyList(),
        cropLeft = 0, cropTop = 0, screenshotW = 1, screenshotH = 1,
        cropWidth = 1, cropHeight = 1,
        text = emptyList(),
    )

    /** Capture 1's boxes, and more of them: its result landed. */
    private val a = OnScreenBoxes(data, displayId = 0, captureId = 1L)
    private val aLanded = a.copy(screenshotPath = "a.png", capturedAtMs = 5L)

    /** Capture 2's boxes, on the same display, in the same words. */
    private val b = OnScreenBoxes(data, displayId = 0, captureId = 2L)

    /** Boxes with no id: one screen, or no overlay to start the capture on. */
    private val plain = OnScreenBoxes(data, displayId = 0)
    private val plainLanded = plain.copy(screenshotPath = "p.png")

    private val boxModes = OverlayMode.entries.filter { it != OverlayMode.NONE }
    private val bools = listOf(true, false)

    @Test fun `a state without boxes takes down whatever is up`() {
        for (previous in listOf(a, plain, null)) for (shown in bools) for (shows in bools) for (mode in OverlayMode.entries) {
            assertEquals(
                "after $previous, shown=$shown, shows=$shows, $mode",
                OnScreenStep.HIDE, onScreenStep(previous, null, shown, shows, mode),
            )
        }
    }

    @Test fun `more of the capture rendered last fills its window while it is up`() {
        for (shows in bools) for (mode in OverlayMode.entries) {
            assertEquals("its result landing, $mode", OnScreenStep.FILL, onScreenStep(a, aLanded, true, shows, mode))
            assertEquals("the same replayed, $mode", OnScreenStep.FILL, onScreenStep(a, a, true, shows, mode))
        }
    }

    @Test fun `and puts nothing back once it is down`() {
        // A tap, a takeover or the app's onStop took it down; a page recreated
        // over the same result replays it.
        for (shows in bools) for (mode in OverlayMode.entries) {
            assertEquals("its result landing, $mode", OnScreenStep.NONE, onScreenStep(a, aLanded, false, shows, mode))
            assertEquals("the same replayed, $mode", OnScreenStep.NONE, onScreenStep(a, a, false, shows, mode))
        }
    }

    @Test fun `another capture's boxes replace what is up, with no status between`() {
        for (shown in bools) for (mode in boxModes) {
            assertEquals("after capture 1, shown=$shown, $mode", OnScreenStep.PRESENT, onScreenStep(a, b, shown, true, mode))
            assertEquals("after its landing, shown=$shown, $mode", OnScreenStep.PRESENT, onScreenStep(aLanded, b, shown, true, mode))
            assertEquals("after none, shown=$shown, $mode", OnScreenStep.PRESENT, onScreenStep(null, b, shown, true, mode))
        }
    }

    @Test fun `a new capture that paints nothing takes down what is up`() {
        for (previous in listOf(a, null)) for (shown in bools) {
            assertEquals(
                "after $previous, shown=$shown",
                OnScreenStep.HIDE, onScreenStep(previous, b, shown, true, OverlayMode.NONE),
            )
        }
    }

    @Test fun `with no other screen a new capture takes down what is up`() {
        for (previous in listOf(a, null)) for (shown in bools) for (mode in OverlayMode.entries) {
            assertEquals(
                "after $previous, shown=$shown, $mode",
                OnScreenStep.HIDE, onScreenStep(previous, b, shown, false, mode),
            )
        }
    }

    @Test fun `with no id on either side, boxes that follow boxes are the same capture`() {
        assertEquals("up", OnScreenStep.FILL, onScreenStep(plain, plainLanded, true, true, OverlayMode.TRANSLATION))
        assertEquals("down", OnScreenStep.NONE, onScreenStep(plain, plainLanded, false, true, OverlayMode.TRANSLATION))
        assertEquals("after none", OnScreenStep.PRESENT, onScreenStep(null, plain, false, true, OverlayMode.TRANSLATION))
    }

    @Test fun `an id on one side only is another capture`() {
        for (shown in bools) {
            assertEquals("id after none", OnScreenStep.PRESENT, onScreenStep(plain, a, shown, true, OverlayMode.TRANSLATION))
            assertEquals("none after id", OnScreenStep.PRESENT, onScreenStep(a, plain, shown, true, OverlayMode.TRANSLATION))
        }
    }

    @Test fun `the mode a capture asked for itself wins over the remembered one`() {
        val lookup = b.copy(modeOverride = OverlayMode.DEFINITIONS)
        val quiet = b.copy(modeOverride = OverlayMode.NONE)
        for (shown in bools) {
            assertEquals(
                "tap-to-lookup goes up under a remembered None, shown=$shown",
                OnScreenStep.PRESENT, onScreenStep(a, lookup, shown, true, OverlayMode.NONE),
            )
            assertEquals(
                "and a capture that asked for None puts nothing up, shown=$shown",
                OnScreenStep.HIDE, onScreenStep(a, quiet, shown, true, OverlayMode.TRANSLATION),
            )
        }
    }

    @Test fun `the mode row shows only with a capture's result, on two screens`() {
        assertEquals("a capture's result", true, showsCaptureModeRow(showsOnScreen = true, boxes = a))
        assertEquals("before one, or a result without boxes", false, showsCaptureModeRow(showsOnScreen = true, boxes = null))
        assertEquals("one screen", false, showsCaptureModeRow(showsOnScreen = false, boxes = a))
        assertEquals("neither", false, showsCaptureModeRow(showsOnScreen = false, boxes = null))
    }
}
