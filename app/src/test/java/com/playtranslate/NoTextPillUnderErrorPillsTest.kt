package com.playtranslate

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.translation.BackendFailure
import com.playtranslate.translation.BackendFailureKind
import com.playtranslate.translation.TranslationError
import com.playtranslate.ui.TranslationErrorPills
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * The transient no-text pill and the translation-error pills on one
 * display: whichever came first, the no-text pill ends up below the error
 * pills, and follows them as they change. Reaches the controller's
 * private pills through reflection; the error pills go up through the
 * stack directly, since the controller's own path wants a capture session
 * (a floating icon) that this test has no service for.
 */
@RunWith(RobolectricTestRunner::class)
class NoTextPillUnderErrorPillsTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val controller = OverlayUiController(
        ctx, OverlayHost(ctx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY),
    )
    private val display: Display =
        (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)

    private val errorPills = privateField("errorPills") as TranslationErrorPills

    private fun privateField(name: String): Any? =
        OverlayUiController::class.java.getDeclaredField(name).apply { isAccessible = true }.get(controller)

    private fun noTextPill(): View? = privateField("pillView") as View?

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

    /** Let every window removed here (the no-text pill's fade-out) finish
     *  its churn-gated destroy inside this test: the gate is a process-wide
     *  object, and a destroy still pending would run in the next test,
     *  against windows Robolectric has already torn down. */
    @After fun drainChurnGate() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
    }

    /** The y the window manager has for [view]'s window: its own copy of
     *  the params, which only addView and updateViewLayout write. */
    private fun windowY(view: View): Int {
        val root = View::class.java.getDeclaredMethod("getViewRootImpl").invoke(view)
        val attrs = Class.forName("android.view.ViewRootImpl").getDeclaredField("mWindowAttributes")
            .apply { isAccessible = true }.get(root) as WindowManager.LayoutParams
        return attrs.y
    }

    private fun stackBottom(): Int {
        val root = errorPills.stackRoot!!
        return windowY(root) + root.height
    }

    @Test fun `a no-text pill put up before the error pills' first layout ends up below them`() {
        // Codex native 2026-09-27: an error and a failed capture landed in
        // the same frame; the stack had no size yet, so the no-text pill
        // took its usual spot, on top of the error pill.
        errorPills.show(ctx, display.displayId, TranslationError.Connection)
        controller.showNoTextPill(display, "Couldn't translate")
        settle()

        val pill = noTextPill()
        assertNotNull(pill)
        assertTrue(windowY(pill!!) >= stackBottom())
    }

    @Test fun `a no-text pill already up moves below error pills that arrive, and below each one added`() {
        controller.showNoTextPill(display, "No text found")
        settle()
        errorPills.show(ctx, display.displayId, TranslationError.Connection)
        settle()

        val pill = noTextPill()
        assertNotNull("the error pill doesn't take it down", pill)
        assertTrue(windowY(pill!!) >= stackBottom())

        errorPills.show(
            ctx, display.displayId,
            TranslationError.Service("claude", "Claude", BackendFailure(BackendFailureKind.BILLING)),
        )
        settle()
        assertTrue(windowY(pill) >= stackBottom())
    }
}
