package com.playtranslate

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * Every read of a display goes through one window context of that display,
 * kept while the display's rotation and panel size stay as they were and
 * made again when they change ([DisplayWindowContexts]). A context per read
 * ran Android 11 out of window contexts in live mode (its limit of five
 * windowless tokens), and the capture was rebuilt at the wrong size.
 */
@RunWith(RobolectricTestRunner::class)
class DisplayWindowContextsTest {

    private val app: Context = ApplicationProvider.getApplicationContext()
    private val dm = app.getSystemService(DisplayManager::class.java)
    private val display: Display get() = dm.getDisplay(Display.DEFAULT_DISPLAY)

    private fun windowContext(id: Int = Display.DEFAULT_DISPLAY) = DisplayWindowContexts.of(app, id)

    private val realMake = DisplayWindowContexts.make

    @Before fun setUp() = DisplayWindowContexts.reset()

    @After fun tearDown() {
        DisplayWindowContexts.make = realMake
        DisplayWindowContexts.reset()
    }

    /** Window contexts can't be made now (Android 11 out of tokens). */
    private fun contextsRefused() {
        DisplayWindowContexts.make = { _, _ -> throw UnsupportedOperationException("refused (test)") }
    }

    private fun read(id: Int = Display.DEFAULT_DISPLAY) = DisplayWindowContexts.metrics(app, id)

    @Test fun `every read of a display shares one window context`() {
        val first = windowContext()
        assertNotNull(first)
        repeat(10) { assertSame(first, windowContext()) }
    }

    @Test fun `a read through any context of the display goes through it`() {
        val first = windowContext()
        val metrics = app.createDisplayContext(display).displayWindowMetrics()
        assertNotNull("the read succeeds", metrics)
        assertSame(first, windowContext())
    }

    @Test fun `a rotation makes a new one, kept at the new rotation`() {
        val before = windowContext()
        shadowOf(display).setRotation(Surface.ROTATION_90)
        val after = windowContext()
        assertNotSame(before, after)
        assertSame(after, windowContext())
    }

    @Test fun `a refresh-rate switch keeps it`() {
        val before = windowContext()
        shadowOf(display).setRefreshRate(120f)
        assertSame(before, windowContext())
    }

    @Test fun `a new panel size makes a new one`() {
        val before = windowContext()
        val mode = display.mode
        // The SDK hides Display.Mode's constructor.
        val bigger = ReflectionHelpers.callConstructor(
            Display.Mode::class.java,
            ClassParameter.from(Int::class.javaPrimitiveType, mode.modeId),
            ClassParameter.from(Int::class.javaPrimitiveType, mode.physicalWidth * 2),
            ClassParameter.from(Int::class.javaPrimitiveType, mode.physicalHeight * 2),
            ClassParameter.from(Float::class.javaPrimitiveType, mode.refreshRate),
        )
        ShadowDisplayManager.setSupportedModes(Display.DEFAULT_DISPLAY, bigger)
        assertEquals("the mode's size changed", mode.physicalWidth * 2, display.mode.physicalWidth)
        assertNotSame(before, windowContext())
    }

    @Test fun `each display has its own, and a removed display has none`() {
        val second = ShadowDisplayManager.addDisplay("w400dp-h300dp")
        val mine = windowContext(second)
        assertNotNull(mine)
        assertNotSame(windowContext(), mine)
        ShadowDisplayManager.removeDisplay(second)
        assertNull(windowContext(second))
    }

    @Test fun `a removed display's context is dropped at the next read of any display`() {
        val second = ShadowDisplayManager.addDisplay("w400dp-h300dp")
        assertNotNull(read(second))
        read()
        ShadowDisplayManager.removeDisplay(second)
        read()
        assertEquals(setOf(Display.DEFAULT_DISPLAY), DisplayWindowContexts.kept())
    }

    @Test fun `what waits on a failing display runs at its next successful read`() {
        contextsRefused()
        assertNull(read())
        var ran = 0
        DisplayWindowContexts.whenReadable(Display.DEFAULT_DISPLAY) { ran++ }
        assertNull(read())
        assertEquals("still failing", 0, ran)
        DisplayWindowContexts.make = realMake
        assertNotNull(read())
        assertEquals(1, ran)
        read()
        assertEquals("once", 1, ran)
    }

    @Test fun `what waits on a display that reads fine runs at once`() {
        read()
        var ran = 0
        DisplayWindowContexts.whenReadable(Display.DEFAULT_DISPLAY) { ran++ }
        assertEquals(1, ran)
    }

    @Test fun `what waits on a display that goes away runs`() {
        val second = ShadowDisplayManager.addDisplay("w400dp-h300dp")
        contextsRefused()
        assertNull(read(second))
        var ran = 0
        DisplayWindowContexts.whenReadable(second) { ran++ }
        ShadowDisplayManager.removeDisplay(second)
        DisplayWindowContexts.make = realMake
        read()
        assertEquals(1, ran)
    }

    @Test fun `a new Application starts with none`() {
        val before = windowContext()
        DisplayWindowContexts.reset()
        assertNotSame(before, windowContext())
    }
}
