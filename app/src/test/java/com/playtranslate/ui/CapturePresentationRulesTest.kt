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
 * on two screens when Show on screen is on or the capture asked for a mode
 * of its own (tap-to-lookup's Definitions, whatever the setting; None asks
 * for nothing), and leaving the game display bare otherwise; with no id on
 * either side, boxes that follow boxes are the same capture. Every cell.
 * The mode an overlay paints in on two screens ([twoScreenMode]): the
 * capture's own, else the remembered one, a remembered None reading as
 * auto-translate's. When the page offers Show on screen for a capture
 * ([offersShowOnScreen]): only with a capture's result on it, on two
 * screens, and not while auto-translate runs.
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
    private val autoModes = listOf(OverlayMode.TRANSLATION, OverlayMode.FURIGANA)
    private val bools = listOf(true, false)

    @Test fun `a state without boxes takes down whatever is up`() {
        for (previous in listOf(a, plain, null)) for (shown in bools) for (shows in bools) for (on in bools) {
            assertEquals(
                "after $previous, shown=$shown, shows=$shows, on=$on",
                OnScreenStep.HIDE, onScreenStep(previous, null, shown, shows, on),
            )
        }
    }

    @Test fun `more of the capture rendered last fills its window while it is up`() {
        for (shows in bools) for (on in bools) {
            assertEquals("its result landing, on=$on", OnScreenStep.FILL, onScreenStep(a, aLanded, true, shows, on))
            assertEquals("the same replayed, on=$on", OnScreenStep.FILL, onScreenStep(a, a, true, shows, on))
        }
    }

    @Test fun `and puts nothing back once it is down`() {
        // A tap, a takeover or the app's onStop took it down; a page recreated
        // over the same result replays it.
        for (shows in bools) for (on in bools) {
            assertEquals("its result landing, on=$on", OnScreenStep.NONE, onScreenStep(a, aLanded, false, shows, on))
            assertEquals("the same replayed, on=$on", OnScreenStep.NONE, onScreenStep(a, a, false, shows, on))
        }
    }

    @Test fun `with Show on screen on, another capture's boxes replace what is up, with no status between`() {
        for (shown in bools) {
            assertEquals("after capture 1, shown=$shown", OnScreenStep.PRESENT, onScreenStep(a, b, shown, true, true))
            assertEquals("after its landing, shown=$shown", OnScreenStep.PRESENT, onScreenStep(aLanded, b, shown, true, true))
            assertEquals("after none, shown=$shown", OnScreenStep.PRESENT, onScreenStep(null, b, shown, true, true))
        }
    }

    @Test fun `with Show on screen off, a new capture takes down what is up and stays down`() {
        for (previous in listOf(a, null)) for (shown in bools) {
            assertEquals(
                "after $previous, shown=$shown",
                OnScreenStep.HIDE, onScreenStep(previous, b, shown, true, false),
            )
        }
    }

    @Test fun `with no other screen a new capture takes down what is up`() {
        for (previous in listOf(a, null)) for (shown in bools) for (on in bools) {
            assertEquals(
                "after $previous, shown=$shown, on=$on",
                OnScreenStep.HIDE, onScreenStep(previous, b, shown, false, on),
            )
            assertEquals(
                "tap-to-lookup's too, after $previous, shown=$shown, on=$on",
                OnScreenStep.HIDE, onScreenStep(previous, b.copy(modeOverride = OverlayMode.DEFINITIONS), shown, false, on),
            )
        }
    }

    @Test fun `with no id on either side, boxes that follow boxes are the same capture`() {
        assertEquals("up", OnScreenStep.FILL, onScreenStep(plain, plainLanded, true, true, true))
        assertEquals("down", OnScreenStep.NONE, onScreenStep(plain, plainLanded, false, true, true))
        assertEquals("after none", OnScreenStep.PRESENT, onScreenStep(null, plain, false, true, true))
    }

    @Test fun `an id on one side only is another capture`() {
        for (shown in bools) {
            assertEquals("id after none", OnScreenStep.PRESENT, onScreenStep(plain, a, shown, true, true))
            assertEquals("none after id", OnScreenStep.PRESENT, onScreenStep(a, plain, shown, true, true))
        }
    }

    @Test fun `a capture that asked for a mode of its own goes up whatever the setting, and one that asked for None never`() {
        val lookup = b.copy(modeOverride = OverlayMode.DEFINITIONS)
        val quiet = b.copy(modeOverride = OverlayMode.NONE)
        for (shown in bools) for (on in bools) {
            assertEquals(
                "tap-to-lookup goes up, on=$on, shown=$shown",
                OnScreenStep.PRESENT, onScreenStep(a, lookup, shown, true, on),
            )
            assertEquals(
                "and a capture that asked for None puts nothing up, on=$on, shown=$shown",
                OnScreenStep.HIDE, onScreenStep(a, quiet, shown, true, on),
            )
        }
    }

    @Test fun `a capture goes up as it lands with a mode of its own, or with Show on screen on`() {
        for (on in bools) {
            for (own in boxModes) assertEquals("asked for $own, on=$on", true, goesUpAsItLands(own, on))
            assertEquals("asked for None, on=$on", false, goesUpAsItLands(OverlayMode.NONE, on))
            assertEquals("asked for nothing, on=$on", on, goesUpAsItLands(null, on))
        }
    }

    @Test fun `on two screens an overlay paints in the capture's own mode, else the remembered one`() {
        for (auto in autoModes) for (remembered in OverlayMode.entries) {
            assertEquals(
                "tap-to-lookup over $remembered, auto-translate in $auto",
                OverlayMode.DEFINITIONS, twoScreenMode(OverlayMode.DEFINITIONS, remembered, auto),
            )
        }
        for (auto in autoModes) for (remembered in boxModes) {
            assertEquals("$remembered remembered, auto-translate in $auto", remembered, twoScreenMode(null, remembered, auto))
        }
    }

    @Test fun `on two screens a remembered None reads as auto-translate's mode`() {
        for (auto in autoModes) {
            assertEquals("auto-translate in $auto", auto, twoScreenMode(null, OverlayMode.NONE, auto))
        }
    }

    @Test fun `show on screen is offered only with a capture's result, on two screens, with auto-translate off`() {
        for (shows in bools) for (boxes in listOf(a, null)) for (live in bools) {
            assertEquals(
                "shows=$shows, boxes=${boxes != null}, live=$live",
                shows && boxes != null && !live,
                offersShowOnScreen(showsOnScreen = shows, boxes = boxes, liveRunning = live),
            )
        }
    }
}
