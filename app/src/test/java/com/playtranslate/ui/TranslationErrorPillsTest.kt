package com.playtranslate.ui

import android.content.Context
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.R
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.translation.BackendFailure
import com.playtranslate.translation.BackendFailureKind
import com.playtranslate.translation.TranslationError
import com.playtranslate.translation.TranslationErrorKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDisplayManager
import java.time.Duration

/**
 * The pill stack's window and rows, added through a real [OverlayHost]:
 * one pill per owner, a newer error updating its owner's pill, × and
 * [TranslationErrorPills.hide] removing pills, the gear handing its owner on
 * to the controller, the window leaving with the last one, and the rects the
 * OCR blackout reads.
 */
@RunWith(RobolectricTestRunner::class)
class TranslationErrorPillsTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val host = OverlayHost(ctx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
    private val pills = TranslationErrorPills(host)
    private val display = Display.DEFAULT_DISPLAY
    private val density = ctx.resources.displayMetrics.density

    private fun service(id: String, name: String, kind: BackendFailureKind) =
        TranslationError.Service(id, name, BackendFailure(kind))

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

    /** Let every window a test removed finish its churn-gated destroy
     *  inside that test: the gate is a process-wide object, and a destroy
     *  still pending would run in the next test, against windows
     *  Robolectric has already torn down. */
    @After fun drainChurnGate() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
    }

    private fun rows(): List<ViewGroup> {
        val root = pills.stackRoot ?: return emptyList()
        return (0 until root.childCount).map { root.getChildAt(it) as ViewGroup }
    }

    private fun ViewGroup.message(): String = (getChildAt(1) as TextView).text.toString()
    private fun ViewGroup.settingsButton(): ImageView = getChildAt(2) as ImageView
    private fun ViewGroup.closeButton(): ImageView = getChildAt(3) as ImageView

    @Test fun `one pill per owner, in arrival order`() {
        assertTrue(pills.show(ctx, display, service("claude", "Claude", BackendFailureKind.BILLING)))
        assertTrue(pills.show(ctx, display, TranslationError.Connection))
        settle()

        assertEquals(listOf("Claude: Out of credits", "Connection error"), rows().map { it.message() })
        assertEquals(display, pills.displayId)
    }

    @Test fun `a newer error from the same service replaces its pill's text`() {
        pills.show(ctx, display, service("gemini", "Gemini", BackendFailureKind.RATE_LIMITED))
        pills.show(ctx, display, service("gemini", "Gemini", BackendFailureKind.DAILY_QUOTA))
        settle()

        assertEquals(listOf("Gemini: Daily limit reached"), rows().map { it.message() })
    }

    @Test fun `the close button takes down just its own pill`() {
        pills.show(ctx, display, service("claude", "Claude", BackendFailureKind.BILLING))
        pills.show(ctx, display, service("deepl", "DeepL", BackendFailureKind.MONTHLY_QUOTA))
        settle()

        rows()[0].closeButton().performClick()
        settle()

        assertEquals(listOf("DeepL: Monthly quota used up"), rows().map { it.message() })
        assertEquals("Close", rows()[0].closeButton().contentDescription)
    }

    @Test fun `the gear sits between the message and the ×, a button like it`() {
        pills.show(ctx, display, TranslationError.Connection)
        settle()
        val row = rows().single()
        assertEquals("logo, message, gear, ×", 4, row.childCount)
        val gear = row.settingsButton()
        val close = row.closeButton()
        assertEquals(R.drawable.ic_settings, shadowOf(gear.drawable).createdFromResId)
        assertEquals(R.drawable.ic_close, shadowOf(close.drawable).createdFromResId)
        assertEquals("Translation services", gear.contentDescription)
        val target = (TranslationErrorPills.BUTTON_DP * density).toInt()
        for (button in listOf(gear, close)) {
            assertEquals(target, button.width)
            assertEquals(target, button.height)
        }
        assertEquals(close.imageTintList, gear.imageTintList)
        assertEquals(close.paddingLeft, gear.paddingLeft)
        assertEquals(
            "a gap between the gear and the ×",
            (TranslationErrorPills.BUTTON_GAP_DP * density).toInt(), close.left - gear.right,
        )
    }

    @Test fun `the gear hands its owner and display on, and leaves the pill to the controller`() {
        // The controller may ask first (a card editor open over the game),
        // and a Cancel there must find the pill where it was.
        val second = ShadowDisplayManager.addDisplay("w640dp-h480dp")
        val secondCtx = ctx.createDisplayContext(
            ctx.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(second),
        )
        val opened = mutableListOf<Pair<String, Int>>()
        pills.onSettings = { owner, id -> opened += owner to id }
        pills.show(secondCtx, second, service("claude", "Claude", BackendFailureKind.BILLING))
        pills.show(secondCtx, second, TranslationError.Connection)
        settle()

        rows()[0].settingsButton().performClick()

        assertEquals(listOf("claude" to second), opened)
        assertEquals(listOf("Claude: Out of credits", "Connection error"), rows().map { it.message() })
    }

    @Test fun `the × never opens the settings`() {
        val opened = mutableListOf<String>()
        pills.onSettings = { owner, _ -> opened += owner }
        pills.show(ctx, display, TranslationError.Connection)
        settle()

        rows()[0].closeButton().performClick()

        assertTrue(opened.isEmpty())
        assertNull(pills.stackRoot)
    }

    @Test fun `a raised pill's gear still opens the settings for its owner`() {
        val opened = mutableListOf<Pair<String, Int>>()
        pills.onSettings = { owner, id -> opened += owner to id }
        pills.show(ctx, display, service("claude", "Claude", BackendFailureKind.BILLING))
        settle()
        pills.raise(display)
        settle()

        rows()[0].settingsButton().performClick()

        assertEquals(listOf("claude" to display), opened)
    }

    @Test fun `the window goes with the last pill, and the next pill gets a new one`() {
        pills.show(ctx, display, service("claude", "Claude", BackendFailureKind.BILLING))
        settle()
        val first = pills.stackRoot
        assertNotNull(first)

        pills.hide(listOf("claude"))
        assertNull(pills.stackRoot)
        assertNull(pills.displayId)

        pills.show(ctx, display, TranslationError.Connection)
        // Never re-add a removed view: the churn gate may still hold it.
        assertNotSame(first, pills.stackRoot)
    }

    @Test fun `hiding an owner with no pill changes nothing`() {
        pills.show(ctx, display, TranslationError.Connection)
        pills.hide(listOf("gemini"))
        assertEquals(1, rows().size)
    }

    @Test fun `the screen rect covers every pill and its shadow room, on its display only`() {
        pills.show(ctx, display, service("claude", "Claude", BackendFailureKind.BILLING))
        pills.show(ctx, display, TranslationError.Connection)
        settle()

        val rects = pills.screenRects(display)
        assertEquals(1, rects.size)
        val stack = rects.single()
        val room = (TranslationErrorPills.SHADOW_ROOM_DP * density).toInt()
        val loc = IntArray(2)
        val pillRects = rows().map { row ->
            row.getLocationOnScreen(loc)
            android.graphics.Rect(loc[0], loc[1], loc[0] + row.width, loc[1] + row.height)
        }
        for (pill in pillRects) {
            assertTrue("the pill is inside the rect", stack.contains(pill))
            assertTrue("with shadow room beside it", pill.left - stack.left >= room && stack.right - pill.right >= room)
        }
        assertTrue("shadow room above the first pill", pillRects.first().top - stack.top >= room)
        assertTrue("shadow room below the last pill", stack.bottom - pillRects.last().bottom >= room)
        assertTrue(pills.screenRects(display + 1).isEmpty())
    }

    @Test fun `the window's width is pinned in pixels, never left to the platform`() {
        // On the Thor a WRAP_CONTENT width came out as the OTHER screen's
        // (1240px on the 1920px screen) and cut the pills off on both sides.
        pills.show(ctx, display, TranslationError.Connection)
        settle()
        val params = pills.stackRoot!!.layoutParams as WindowManager.LayoutParams
        val margin = (TranslationErrorPills.SCREEN_MARGIN_DP * density).toInt()
        val room = (TranslationErrorPills.SHADOW_ROOM_DP * density).toInt()
        val displayWidth = ctx.resources.displayMetrics.widthPixels
        assertEquals(displayWidth - 2 * margin + 2 * room, params.width)
        assertEquals(WindowManager.LayoutParams.WRAP_CONTENT, params.height)
    }

    @Test fun `each pill spans the display less the menu's margin a side`() {
        pills.show(ctx, display, TranslationError.Connection)
        settle()
        val margin = (TranslationErrorPills.SCREEN_MARGIN_DP * density).toInt()
        val displayWidth = ctx.resources.displayMetrics.widthPixels
        assertEquals(displayWidth - 2 * margin, rows().single().width)
    }

    @Test fun `the pill is at least 48dp tall around a 28dp logo in a 10dp buffer, raised like the menu card`() {
        // The declared layout, not a measured text height: Robolectric's
        // stand-in font measures a 14sp line at 35px, nothing like a device.
        pills.show(ctx, display, TranslationError.Connection)
        settle()
        val row = rows().single()
        val logo = row.getChildAt(0)
        val lp = logo.layoutParams as android.widget.LinearLayout.LayoutParams
        val buffer = ((TranslationErrorPills.ROW_HEIGHT_DP - TranslationErrorPills.LOGO_DP) / 2 * density).toInt()
        assertEquals((TranslationErrorPills.ROW_HEIGHT_DP * density).toInt(), row.minimumHeight)
        assertTrue(row.height >= row.minimumHeight)
        assertEquals((TranslationErrorPills.LOGO_DP * density).toInt(), logo.width)
        assertEquals((TranslationErrorPills.LOGO_DP * density).toInt(), logo.height)
        assertEquals(10, (buffer / density).toInt())
        assertEquals(buffer, lp.marginStart)
        assertEquals(buffer, lp.topMargin)
        assertEquals(buffer, lp.bottomMargin)
        assertEquals(TranslationErrorPills.ELEVATION_DP * density, row.elevation, 0.01f)
    }
    @Test fun `the no-text pill is placed under the stack, not on it`() {
        pills.show(ctx, display, TranslationError.Connection)
        settle()
        val params = WindowManager.LayoutParams().apply { y = 40 }

        assertTrue(pills.placeBelow(display, params))
        val root = pills.stackRoot!!
        assertTrue(params.y >= root.height)
        assertFalse(pills.placeBelow(display + 1, WindowManager.LayoutParams()))
    }

    @Test fun `removeAll clears the stack`() {
        pills.show(ctx, display, TranslationError.Connection)
        pills.removeAll()
        assertNull(pills.stackRoot)
        assertTrue(pills.screenRects(display).isEmpty())
    }

    @Test fun `each pill carries the app mark first`() {
        pills.show(ctx, display, TranslationError.Connection)
        settle()
        val first: View = rows()[0].getChildAt(0)
        assertTrue(first is ViewGroup && first.clipToOutline)
    }

    @Test fun `a key for another owner leaves the owner's pill`() {
        pills.show(ctx, display, service("claude", "Claude", BackendFailureKind.BILLING))
        pills.hide(listOf(TranslationErrorKey.CONNECTION_OWNER))
        assertEquals(1, rows().size)
    }

    @Test fun `a display leaving capture takes the stack with it, reporting its owners`() {
        val withdrawn = mutableListOf<List<String>>()
        pills.onWithdrawn = { withdrawn += it }
        pills.show(ctx, display, service("claude", "Claude", BackendFailureKind.BILLING))
        pills.show(ctx, display, TranslationError.Connection)

        pills.withdrawFrom(display + 1)
        assertNotNull("another display leaving changes nothing", pills.stackRoot)

        pills.withdrawFrom(display)
        assertNull(pills.stackRoot)
        assertNull(pills.displayId)
        assertEquals(listOf(listOf("claude", TranslationErrorKey.CONNECTION_OWNER)), withdrawn)
    }

    @Test fun `a window vanishing under the stack reports its owners, our own removals don't`() {
        val withdrawn = mutableListOf<List<String>>()
        pills.onWithdrawn = { withdrawn += it }
        pills.show(ctx, display, TranslationError.Connection)
        pills.hide(listOf(TranslationErrorKey.CONNECTION_OWNER))
        pills.show(ctx, display, TranslationError.Connection)
        pills.removeAll()
        settle()
        assertTrue("hide and removeAll are ours", withdrawn.isEmpty())

        pills.show(ctx, display, TranslationError.Connection)
        settle()
        // What an unplugged display does: the window goes without us.
        ctx.getSystemService(WindowManager::class.java).removeViewImmediate(pills.stackRoot)
        assertNull(pills.stackRoot)
        assertEquals(listOf(listOf(TranslationErrorKey.CONNECTION_OWNER)), withdrawn)
    }

    @Test fun `raise moves the pills to a fresh window on top, withdrawing nothing`() {
        val withdrawn = mutableListOf<List<String>>()
        pills.onWithdrawn = { withdrawn += it }
        pills.show(ctx, display, service("claude", "Claude", BackendFailureKind.BILLING))
        pills.show(ctx, display, TranslationError.Connection)
        settle()
        val before = pills.stackRoot

        pills.raise(display + 1)
        assertSame("a stack elsewhere stays put", before, pills.stackRoot)
        // Animations on (Robolectric runs with them off; the setter is the
        // framework's test API), so a fade-in on the fresh window would show
        // as alpha 0 right after the raise.
        val setScale = android.animation.ValueAnimator::class.java
            .getMethod("setDurationScale", Float::class.javaPrimitiveType)
        val scale = android.animation.ValueAnimator.getDurationScale()
        setScale.invoke(null, 1f)
        try {
            pills.raise(display)
            assertEquals("no fade: the pills never left", 1f, pills.stackRoot!!.alpha)
        } finally {
            setScale.invoke(null, scale)
        }
        settle()

        assertNotSame(before, pills.stackRoot)
        assertEquals(listOf("Claude: Out of credits", "Connection error"), rows().map { it.message() })
        rows()[0].closeButton().performClick()
        assertEquals("the moved × still closes its pill", listOf("Connection error"), rows().map { it.message() })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))   // the old window's destroy
        assertTrue(withdrawn.isEmpty())
    }

    private fun windowAlpha() = (pills.stackRoot!!.layoutParams as WindowManager.LayoutParams).alpha

    @Test fun `a pill reported during a clean capture stays out of the frame, and shows after`() {
        // Codex native 2026-09-27: the stack's window, added after the
        // capture blanked our windows, went up visible in a frame stamped
        // clean, and OCR read the pill's words as game text.
        val capture = host.prepareForCleanCapture(display)

        pills.show(ctx, display, TranslationError.Connection)
        assertEquals(0f, windowAlpha())

        host.restoreAfterCapture(capture)
        assertEquals(1f, windowAlpha())
    }

    @Test fun `a raise during a clean capture stays out of the frame, and shows after`() {
        pills.show(ctx, display, TranslationError.Connection)
        settle()
        val before = pills.stackRoot
        val capture = host.prepareForCleanCapture(display)

        pills.raise(display)

        assertNotSame(before, pills.stackRoot)
        assertEquals(0f, windowAlpha())
        host.restoreAfterCapture(capture)
        assertEquals(1f, windowAlpha())
        assertEquals(listOf("Connection error"), rows().map { it.message() })
    }

    @Test fun `update rewords its owner's pill and never adds one`() {
        assertFalse(pills.update(service("gemini", "Gemini", BackendFailureKind.DAILY_QUOTA)))
        assertNull("no stack, no window", pills.stackRoot)

        pills.show(ctx, display, service("gemini", "Gemini", BackendFailureKind.RATE_LIMITED))
        assertTrue(pills.update(service("gemini", "Gemini", BackendFailureKind.DAILY_QUOTA)))
        assertFalse(pills.update(service("claude", "Claude", BackendFailureKind.BILLING)))
        settle()
        assertEquals(listOf("Gemini: Daily limit reached"), rows().map { it.message() })
    }

    @Test fun `the stack reports its first layout and each change of its height`() {
        val moved = mutableListOf<Int>()
        pills.onStackMoved = { moved += it }
        pills.show(ctx, display, TranslationError.Connection)
        assertFalse(
            "before its first layout there is no size to place below",
            pills.placeBelow(display, WindowManager.LayoutParams()),
        )
        settle()
        assertEquals("its first layout", listOf(display), moved)
        assertTrue(pills.placeBelow(display, WindowManager.LayoutParams()))

        pills.show(ctx, display, service("claude", "Claude", BackendFailureKind.BILLING))
        settle()
        assertEquals("a pill added", 2, moved.size)

        pills.hide(listOf("claude"))
        settle()
        assertEquals("a pill removed", 3, moved.size)
    }
}
