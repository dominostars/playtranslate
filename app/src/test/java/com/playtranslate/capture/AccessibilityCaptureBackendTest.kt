package com.playtranslate.capture

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDisplayManager

/**
 * The accessibility backend's answer to "what does the saved selection
 * resolve to": its connected, non-private displays, whatever their power
 * state, else the default display; an empty selection resolves to nothing.
 * Until 2026-10-14 every id was capturable and there was no fallback, so a
 * selection naming a display that no longer existed (a Galaxy S25 whose
 * stored id had died with the projection that created it) stayed the
 * target of live start and the one-shot fan-out, which captured nothing.
 * The MediaProjection cells ([CaptureBackendResolverKeepsSelectionTest])
 * are unchanged: the default display, whatever was saved.
 */
@RunWith(RobolectricTestRunner::class)
class AccessibilityCaptureBackendTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val dm: DisplayManager = ctx.getSystemService(DisplayManager::class.java)
    private val backend: CaptureBackend = AccessibilityCaptureBackend
    private val first = Display.DEFAULT_DISPLAY

    /** A display id nothing is connected on. */
    private val dead = 7

    private fun addDisplay(): Int = ShadowDisplayManager.addDisplay("w640dp-h480dp")

    @Test fun `a connected public display is capturable, whatever its power state`() {
        val second = addDisplay()
        assertTrue(backend.canCapture(ctx, second))
        shadowOf(dm.getDisplay(second)).setState(Display.STATE_OFF)
        assertTrue("off is the skip predicate's business, not the shim's", backend.canCapture(ctx, second))
    }

    @Test fun `a missing display and a private one are not capturable`() {
        assertFalse(backend.canCapture(ctx, dead))
        // This app's own MediaProjection VirtualDisplay: private, presentation.
        val projection = addDisplay()
        shadowOf(dm.getDisplay(projection)).setFlags(Display.FLAG_PRIVATE or Display.FLAG_PRESENTATION)
        assertFalse(backend.canCapture(ctx, projection))
    }

    @Test fun `a selection naming only dead displays resolves to the default display`() {
        assertEquals(setOf(first), backend.capturableTargets(ctx, setOf(dead)))
        assertEquals(setOf(first), backend.capturableTargets(ctx, linkedSetOf(dead, 8)))
    }

    @Test fun `a selection keeps its connected displays, in order, and drops the dead ones`() {
        val second = addDisplay()
        assertEquals(linkedSetOf(second, first), backend.capturableTargets(ctx, linkedSetOf(dead, second, first)))
        assertEquals(setOf(second), backend.capturableTargets(ctx, linkedSetOf(second, dead)))
    }

    @Test fun `an empty selection resolves to nothing, on both backends`() {
        assertEquals(emptySet<Int>(), backend.capturableTargets(ctx, emptySet()))
        assertEquals(emptySet<Int>(), MediaProjectionCaptureBackend.capturableTargets(ctx, emptySet()))
    }
}
