package com.playtranslate.capture

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.R
import com.playtranslate.displaySizePx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.GraphicsMode

/**
 * Pins [OwnWindowMask]'s membership and grouping rules. Geometry goes
 * through the injectable [OwnWindowMask.geometryProbe]: Robolectric ships
 * no shadow for `currentWindowMetrics` or `Activity.getDisplay`, so the
 * production probe is validated on-device (the `[OwnWindowMask]` log line),
 * and these tests cover everything around it: STARTED membership, removal
 * on stop and on destroy, the translucent-window exclusion, per-display
 * grouping, empty-geometry dropping, and the serve-time entry point's
 * ownership and fail-closed rules.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OwnWindowMaskRegistryTest {

    /** Opaque page under the app theme — the shape of every real
     *  in-app screen. */
    class OpaqueActivity : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    /** The transparent-window shape: ProcessTextActivity's theme, whose
     *  contract is that the app BEHIND it stays visible. */
    class TranslucentActivity : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate_Transparent)
            super.onCreate(savedInstanceState)
        }
    }

    private val geometries = HashMap<Activity, OwnWindowMask.Geometry?>()
    private val controllers = mutableListOf<ActivityController<*>>()

    @Before
    fun setUp() {
        OwnWindowMask.resetForTest()
        OwnWindowMask.install(ApplicationProvider.getApplicationContext<Application>())
        OwnWindowMask.geometryProbe = { geometries[it] }
    }

    @After
    fun tearDown() {
        controllers.forEach { runCatching { it.pause().stop().destroy() } }
        OwnWindowMask.resetForTest()
    }

    private fun <A : Activity> start(cls: Class<A>, displayId: Int = Display.DEFAULT_DISPLAY, bounds: Rect? = Rect(0, 0, 100, 200)): A {
        val controller = Robolectric.buildActivity(cls).setup()
        controllers += controller
        val activity = controller.get()
        geometries[activity] = bounds?.let { OwnWindowMask.Geometry(displayId, it) }
        return activity
    }

    @Test
    fun `a started opaque activity is reported on its display and nowhere else`() {
        start(OpaqueActivity::class.java, displayId = 0, bounds = Rect(0, 100, 540, 1200))

        assertEquals(listOf(Rect(0, 100, 540, 1200)), OwnWindowMask.rectsOn(0))
        assertTrue(OwnWindowMask.rectsOn(1).isEmpty())
    }

    @Test
    fun `stopping the activity removes it`() {
        start(OpaqueActivity::class.java)
        assertEquals(1, OwnWindowMask.rectsOn(0).size)

        controllers.last().pause().stop()

        assertTrue(OwnWindowMask.rectsOn(0).isEmpty())
    }

    @Test
    fun `destroying the activity removes it even without a stop`() {
        val controller = Robolectric.buildActivity(OpaqueActivity::class.java).create().start()
        geometries[controller.get()] = OwnWindowMask.Geometry(0, Rect(0, 0, 10, 10))
        assertEquals(1, OwnWindowMask.rectsOn(0).size)

        controller.destroy()

        assertTrue(OwnWindowMask.rectsOn(0).isEmpty())
    }

    @Test
    fun `a translucent-window activity is never tracked`() {
        start(TranslucentActivity::class.java, bounds = Rect(0, 0, 1080, 2400))

        assertTrue(OwnWindowMask.rectsOn(0).isEmpty())
    }

    @Test
    fun `activities on different displays group separately`() {
        start(OpaqueActivity::class.java, displayId = 0, bounds = Rect(0, 0, 100, 200))
        start(OpaqueActivity::class.java, displayId = 2, bounds = Rect(0, 0, 300, 400))

        assertEquals(listOf(Rect(0, 0, 100, 200)), OwnWindowMask.rectsOn(0))
        assertEquals(listOf(Rect(0, 0, 300, 400)), OwnWindowMask.rectsOn(2))
        assertTrue(OwnWindowMask.rectsOn(1).isEmpty())
    }

    @Test
    fun `empty or unknown geometry is dropped`() {
        start(OpaqueActivity::class.java, bounds = Rect())
        start(OpaqueActivity::class.java, bounds = null)

        assertTrue(OwnWindowMask.rectsOn(0).isEmpty())
    }

    @Test
    fun `returned rects are copies, not the probe's instances`() {
        val live = Rect(0, 0, 100, 200)
        start(OpaqueActivity::class.java, bounds = live)

        val reported = OwnWindowMask.rectsOn(0).single()
        assertNotSame(live, reported)
        assertEquals(live, reported)
    }

    // ── maskServedFrame: the sources' entry point ────────────────────────

    private fun displaySizedWhiteFrame(): Bitmap {
        val size = ApplicationProvider.getApplicationContext<Application>().displaySizePx()
        return Bitmap.createBitmap(size.x, size.y, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
    }

    @Test
    fun `serve-time mask paints the started window in place on a display-sized frame`() = runBlocking {
        val frame = displaySizedWhiteFrame()
        start(OpaqueActivity::class.java, bounds = Rect(0, 0, frame.width / 2, frame.height))

        val out = OwnWindowMask.maskServedFrame(frame, Display.DEFAULT_DISPLAY)

        assertSame(frame, out)
        assertEquals(Color.BLACK, out.getPixel(frame.width / 4, frame.height / 2))
        assertEquals(Color.WHITE, out.getPixel(frame.width * 3 / 4, frame.height / 2))
    }

    @Test
    fun `serve-time mask with nothing started returns the frame untouched`() = runBlocking {
        val frame = displaySizedWhiteFrame()

        val out = OwnWindowMask.maskServedFrame(frame, Display.DEFAULT_DISPLAY)

        assertSame(frame, out)
        assertEquals(Color.WHITE, out.getPixel(frame.width / 4, frame.height / 2))
    }

    @Test
    fun `serve-time mask fails closed when the frame does not match the display`() = runBlocking {
        val display = displaySizedWhiteFrame()
        // A frame of some other size: a rect read against the display's
        // geometry cannot be trusted on it, so the whole frame goes black.
        val frame = Bitmap.createBitmap(display.width + 7, display.height, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.WHITE) }
        start(OpaqueActivity::class.java, bounds = Rect(0, 0, 10, 10))

        val out = OwnWindowMask.maskServedFrame(frame, Display.DEFAULT_DISPLAY)

        assertEquals(Color.BLACK, out.getPixel(frame.width - 1, frame.height - 1))
        assertEquals(Color.BLACK, out.getPixel(5, 5))
    }

    @Test
    fun `serve-time mask consumes an immutable frame and returns the painted copy`() = runBlocking {
        val frame = displaySizedWhiteFrame().copy(Bitmap.Config.ARGB_8888, false)
        start(OpaqueActivity::class.java, bounds = Rect(0, 0, frame.width, frame.height))

        val out = OwnWindowMask.maskServedFrame(frame, Display.DEFAULT_DISPLAY)

        assertNotSame(frame, out)
        assertTrue(frame.isRecycled)
        assertFalse(out.isRecycled)
        assertEquals(Color.BLACK, out.getPixel(frame.width / 2, frame.height / 2))
    }

    @Test
    fun `serve-time mask recycles the frame when the caller is cancelled mid-serve`() = runBlocking {
        val frame = displaySizedWhiteFrame()
        start(OpaqueActivity::class.java, bounds = Rect(0, 0, 10, 10))

        // A capture coroutine cancelled between its grab and its stamp: the
        // main-thread hop inside maskServedFrame is the first suspension
        // point after the grab and throws CancellationException on entry.
        val job = launch(Dispatchers.Main.immediate) {
            coroutineContext.job.cancel()
            OwnWindowMask.maskServedFrame(frame, Display.DEFAULT_DISPLAY)
        }
        job.join()

        assertTrue(job.isCancelled)
        assertTrue(frame.isRecycled)
    }
}
