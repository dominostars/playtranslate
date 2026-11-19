package com.playtranslate

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The floating controls' form: which one a display shows from the saved form
 * and where the window can be ([shownControlsForm], every cell), and how the
 * saved form and the window's bounds are kept per display ([Prefs]).
 */
@RunWith(RobolectricTestRunner::class)
class ControlsFormTest {

    @Test
    fun `the window shows only where it was saved and all three conditions allow it`() {
        for (saved in ControlsForm.entries) {
            for (oneScreen in listOf(true, false)) {
                for (ownActivity in listOf(true, false)) {
                    for (editor in listOf(true, false)) {
                        val expected =
                            if (saved == ControlsForm.WINDOW && oneScreen && !ownActivity && !editor) {
                                ControlsForm.WINDOW
                            } else {
                                ControlsForm.ICON
                            }
                        assertEquals(
                            "saved=$saved oneScreen=$oneScreen ownActivity=$ownActivity editor=$editor",
                            expected,
                            shownControlsForm(saved, oneScreen, ownActivity, editor),
                        )
                    }
                }
            }
        }
        // The one cell that shows the window, named, so a rule that returned
        // the icon everywhere would fail here and not agree with itself above.
        assertEquals(
            ControlsForm.WINDOW,
            shownControlsForm(
                ControlsForm.WINDOW, effectivelyOneScreen = true, ownActivityInFront = false, regionEditorOpen = false,
            ),
        )
    }

    /** Where auto-translate's results show, every cell: the captured
     *  displays (none, the one whose controls are the window, one whose
     *  controls are the icon, both), one screen or not, and the two-screen
     *  rule. Each expected value is written out, not computed. */
    @Test
    fun `the window's page takes the results for one captured display saved as the window, where the window can be`() {
        val window = 0
        val icon = 1
        fun surface(targets: Set<Int>, oneScreen: Boolean, appPage: Boolean) =
            liveSurface(targets, windowForm = { it == window }, effectivelyOneScreen = oneScreen, appPageOnly = appPage)

        // The window's display alone, the window able to be there.
        assertEquals(LiveSurface.WINDOW_PAGE, surface(setOf(window), oneScreen = true, appPage = false))
        // The window's page comes first: with the app behind on a second
        // screen and Show on screen off, the window is where it shows.
        assertEquals(LiveSurface.WINDOW_PAGE, surface(setOf(window), oneScreen = true, appPage = true))
        // The app in front on a second screen: the icon stands in there.
        assertEquals(LiveSurface.OVERLAYS, surface(setOf(window), oneScreen = false, appPage = false))
        assertEquals(LiveSurface.APP_PAGE, surface(setOf(window), oneScreen = false, appPage = true))
        // A display whose controls are the icon.
        assertEquals(LiveSurface.OVERLAYS, surface(setOf(icon), oneScreen = true, appPage = false))
        assertEquals(LiveSurface.APP_PAGE, surface(setOf(icon), oneScreen = true, appPage = true))
        assertEquals(LiveSurface.OVERLAYS, surface(setOf(icon), oneScreen = false, appPage = false))
        assertEquals(LiveSurface.APP_PAGE, surface(setOf(icon), oneScreen = false, appPage = true))
        // Two captured displays are never the window's, whichever is saved
        // as what: one stream of results, a page per display.
        assertEquals(LiveSurface.OVERLAYS, surface(setOf(window, icon), oneScreen = true, appPage = false))
        assertEquals(LiveSurface.APP_PAGE, surface(setOf(window, icon), oneScreen = true, appPage = true))
        assertEquals(LiveSurface.OVERLAYS, surface(setOf(window, icon), oneScreen = false, appPage = false))
        assertEquals(
            LiveSurface.OVERLAYS,
            liveSurface(setOf(0, 1), windowForm = { true }, effectivelyOneScreen = true, appPageOnly = false),
        )
        // Nothing captured.
        assertEquals(LiveSurface.OVERLAYS, surface(emptySet(), oneScreen = true, appPage = false))
        assertEquals(LiveSurface.APP_PAGE, surface(emptySet(), oneScreen = true, appPage = true))
    }

    private fun prefs() = Prefs(ApplicationProvider.getApplicationContext())

    @Test
    fun `a display nothing was saved for has the icon and no bounds`() {
        val state = prefs().detachedStateForDisplay(0)
        assertEquals(ControlsForm.ICON, state.form)
        assertNull(state.landscape)
        assertNull(state.portrait)
        assertEquals(emptySet<Int>(), prefs().displaysWithWindowForm())
    }

    @Test
    fun `the form is saved per display and survives a new Prefs`() {
        prefs().setControlsForm(0, ControlsForm.WINDOW)
        prefs().setControlsForm(2, ControlsForm.WINDOW)
        prefs().setControlsForm(2, ControlsForm.ICON)

        assertEquals(ControlsForm.WINDOW, prefs().detachedStateForDisplay(0).form)
        assertEquals(ControlsForm.ICON, prefs().detachedStateForDisplay(2).form)
        assertEquals(ControlsForm.ICON, prefs().detachedStateForDisplay(1).form)
        assertEquals(setOf(0), prefs().displaysWithWindowForm())
    }

    @Test
    fun `bounds are saved per display and per orientation, and a form write keeps them`() {
        val land = WindowBounds(1f, 0.25f, 420f, 680f)
        val port = WindowBounds(0f, 0.5f, 320f, 400f)
        prefs().setWindowBounds(0, landscape = true, bounds = land)
        prefs().setWindowBounds(0, landscape = false, bounds = port)
        prefs().setWindowBounds(3, landscape = true, bounds = port)
        prefs().setControlsForm(0, ControlsForm.WINDOW)
        prefs().setControlsForm(0, ControlsForm.ICON)

        val state = prefs().detachedStateForDisplay(0)
        assertEquals(land, state.bounds(landscape = true))
        assertEquals(port, state.bounds(landscape = false))
        assertEquals(port, prefs().detachedStateForDisplay(3).bounds(landscape = true))
        assertNull(prefs().detachedStateForDisplay(3).bounds(landscape = false))
        // Saving bounds does not detach.
        assertEquals(ControlsForm.ICON, prefs().detachedStateForDisplay(3).form)
    }
}
