package com.playtranslate.ui

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.R
import com.playtranslate.overlay.OverlayHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * The workspace header (title, back chevron, hairline) is the page's
 * navigation bar, and a page that fills the card on its own
 * ([WorkspacePage.hidesHeader]) takes it down while it is on top: the
 * screenshot crop editor, whose bar and pill are its chrome as in the region
 * editor over the game. The header returns with the page beneath.
 */
@RunWith(RobolectricTestRunner::class)
class WorkspaceHeaderTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    /** A bare page; [hidesHeader] as given, its host captured at creation. */
    private class Page(override val hidesHeader: Boolean) : WorkspacePage {
        var host: WorkspaceHost? = null
        override fun title(ctx: Context): CharSequence = "page"
        override fun onCreateView(ctx: Context, parent: ViewGroup, host: WorkspaceHost): View {
            this.host = host
            return View(ctx)
        }
    }

    private fun OverlayWorkspace.view(name: String): View =
        OverlayWorkspace::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(this) as View

    private fun OverlayWorkspace.assertHeader(visible: Boolean) {
        val want = if (visible) View.VISIBLE else View.GONE
        assertEquals("header", want, view("headerFrame").visibility)
        assertEquals("hairline", want, view("headerHairline").visibility)
    }

    @Test fun `a page that hides the header takes it and its hairline down while on top, and the page beneath gets them back`() {
        val activity = Robolectric.buildActivity(Host::class.java).setup().get()
        val container = FrameLayout(activity)
        activity.setContentView(container)
        val wm = activity.getSystemService(WindowManager::class.java)
        val overlayHost = OverlayHost(ctx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        val workspace = OverlayWorkspace(
            activity, wm, Display.DEFAULT_DISPLAY, overlayHost,
            sheetHost = ActivitySheetHost(container),
        )
        val base = Page(hidesHeader = false)
        workspace.show(1000, 2000) { base }
        workspace.assertHeader(visible = true)

        val host = base.host!!
        host.push(Page(hidesHeader = true))
        workspace.assertHeader(visible = false)

        host.pop()
        workspace.assertHeader(visible = true)
        workspace.dismiss()
    }

    @Test fun `the screenshot crop page hides the header`() {
        val f = File(ctx.cacheDir, "screenshots/capture-d0.png")
        f.parentFile!!.mkdirs()
        ImageIO.write(BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB), "png", f)
        val args = Bundle().apply { putString(AnkiScreenshotState.ARG_SCREENSHOT_PATH, f.absolutePath) }
        val state = AnkiScreenshotState.open(ctx, args)!!
        assertTrue(ScreenshotCropPage(state).hidesHeader)
        state.release(ctx, final = true)
    }
}
