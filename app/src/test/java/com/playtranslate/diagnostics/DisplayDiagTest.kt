package com.playtranslate.diagnostics

import android.content.Context
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The capture-display block of the log-export header: the selection line
 * says where the capture goes, or that the selection names nothing
 * connected, and where the icons actually are (none while the reconcile
 * installs none), each display prints with the flags that decide whether
 * it is a screen, and the block reaches the exported file.
 */
@RunWith(RobolectricTestRunner::class)
class DisplayDiagTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun row(
        id: Int,
        name: String = "Built-in Screen",
        flags: Int = 0,
        state: Int = Display.STATE_ON,
        width: Int = 1080,
        height: Int = 2340,
    ) = DisplayDiag.Row(id, name, flags, state, width, height)

    @Test fun `a selection whose display is connected names it`() {
        val lines = DisplayDiag.format(
            saved = setOf(0), stored = true, backend = "accessibility", resolved = setOf(0),
            icons = setOf(0), displays = listOf(row(0)),
        )
        assertEquals(
            listOf(
                "Capture displays: saved=0 backend=accessibility resolved=0 targets=0 icons=0",
                "- id=0 'Built-in Screen' flags=0x0 state=ON 1080x2340",
            ),
            lines,
        )
    }

    @Test fun `a stale selection names its dead display, and a virtual display shows its flags`() {
        // The S25 export of 2026-10-14: the selection named a dead display
        // while the app's own projection display was alive beside the screen.
        // The shim now collapses the dead id to the default display; the
        // line still says which saved display is not there.
        val lines = DisplayDiag.format(
            saved = setOf(3), stored = true, backend = "accessibility", resolved = setOf(0),
            icons = setOf(0),
            displays = listOf(
                row(0, "Integrierter Bildschirm", flags = 0xc083),
                row(6, "PlayTranslateCapture", flags = 0xc, width = 2340, height = 1080),
            ),
        )
        assertEquals(
            "Capture displays: saved=3 (not connected: 3) backend=accessibility resolved=0 targets=0 icons=0",
            lines[0],
        )
        assertEquals("- id=0 'Integrierter Bildschirm' flags=0xc083 state=ON 1080x2340", lines[1])
        assertEquals("- id=6 'PlayTranslateCapture' flags=0xc (private,presentation) state=ON 2340x1080", lines[2])
    }

    @Test fun `a resolution that is not connected either says so`() {
        // A backend without a fallback, or a race with a disconnect: the
        // reconcile's belt puts one icon on the fallback display.
        val lines = DisplayDiag.format(
            saved = setOf(3), stored = true, backend = "accessibility", resolved = setOf(3),
            icons = setOf(0), displays = listOf(row(0)),
        )
        assertEquals(
            "Capture displays: saved=3 (not connected: 3) backend=accessibility resolved=3 targets=none connected icons=0",
            lines[0],
        )
    }

    @Test fun `MediaProjection resolves the same selection to the default display`() {
        val lines = DisplayDiag.format(
            saved = setOf(3), stored = true, backend = "mediaprojection", resolved = setOf(0),
            icons = setOf(0), displays = listOf(row(0)),
        )
        assertEquals(
            "Capture displays: saved=3 (not connected: 3) backend=mediaprojection resolved=0 targets=0 icons=0",
            lines[0],
        )
    }

    @Test fun `an unstored selection, a missing icon and a switched-off display are marked`() {
        // A resolved target with no icon: the preference off, Hide for Now,
        // MediaProjection not turned on. The line must not imply one.
        val lines = DisplayDiag.format(
            saved = setOf(0), stored = false, backend = "accessibility", resolved = setOf(0),
            icons = emptySet(), displays = listOf(row(0), row(4, "Screen-2", state = Display.STATE_OFF)),
        )
        assertEquals(
            "Capture displays: saved=0 (unstored default) backend=accessibility resolved=0 targets=0 icons=none",
            lines[0],
        )
        assertEquals("- id=4 'Screen-2' flags=0x0 state=OFF 1080x2340", lines[2])
    }

    @Test fun `the live read lists the default display, and the exported header carries the block`() {
        val lines = DisplayDiag.lines(ctx)
        assertTrue(lines[0], lines[0].startsWith("Capture displays: saved="))
        assertTrue("no controller, so no icon", lines[0].endsWith(" icons=none"))
        assertTrue(lines.toString(), lines.any { it.startsWith("- id=0 ") })

        val header = LogExporter.exportLogcat(ctx).readText().substringBefore("─")
        assertTrue(header, header.contains("\nCapture displays: saved="))
        assertTrue(header, header.contains("\n- id=0 "))
    }
}
