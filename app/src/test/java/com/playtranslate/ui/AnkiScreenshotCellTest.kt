package com.playtranslate.ui

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * The Screenshot group ([addAnkiScreenshotGroup]) follows the ONE
 * [AnkiScreenshotState] every group of an editor shares: the crop set
 * through either shows in both (the overlay), the ✕ in either takes both
 * out of their containers, and the crop button, shown once the frame is
 * on screen, opens the editor. This is what replaced the two tabs'
 * hand-mirrored removal.
 */
@RunWith(RobolectricTestRunner::class)
class AnkiScreenshotCellTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After fun tearDown() = scope.cancel()

    private fun state(): AnkiScreenshotState {
        val f = File(ctx.cacheDir, "screenshots/capture-d0.png")
        f.parentFile!!.mkdirs()
        ImageIO.write(BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB), "png", f)
        val args = Bundle().apply { putString(AnkiScreenshotState.ARG_SCREENSHOT_PATH, f.absolutePath) }
        return AnkiScreenshotState.open(ctx, args)!!
    }

    /** One tab's container with its group: the frame's children are the
     *  image, the overlay, the crop button and the ✕. */
    private class Tab(val container: LinearLayout, val group: AnkiScreenshotGroup) {
        val frame: FrameLayout get() = ((group.view.getChildAt(1) as ViewGroup).getChildAt(0)) as FrameLayout
        val image: ImageView get() = frame.getChildAt(0) as ImageView
        val overlay: View get() = frame.getChildAt(1)
        val cropButton: View get() = frame.getChildAt(2)
        val remove: View get() = frame.getChildAt(3)
        fun layOut() {
            container.measure(
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            container.layout(0, 0, 1000, container.measuredHeight)
        }
    }

    private fun tab(activity: Activity, state: AnkiScreenshotState, onCrop: () -> Unit = {}): Tab {
        val container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val group = addAnkiScreenshotGroup(container, activity, scope, state, onCrop)!!
        return Tab(container, group)
    }

    /** The IO decode lands on the main thread: wait for the frame. */
    private fun awaitFrame(vararg tabs: Tab) {
        val deadline = System.currentTimeMillis() + 5_000
        while (tabs.any { it.image.drawable == null }) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() < deadline) { "the frame never landed" }
            Thread.sleep(10)
        }
        tabs.forEach { it.layOut() }
    }

    @Test fun `a crop set through one group shows in both, and clearing it hides it in both`() {
        val activity = Robolectric.buildActivity(Host::class.java).setup().get()
        val state = state()
        val word = tab(activity, state)
        val sentence = tab(activity, state)
        awaitFrame(word, sentence)
        assertEquals(View.INVISIBLE, word.overlay.visibility)
        assertEquals(View.VISIBLE, word.cropButton.visibility)

        state.setCrop(ScreenshotCrop.of(0.1f, 0.2f, 0.9f, 0.8f))
        assertEquals(View.VISIBLE, word.overlay.visibility)
        assertEquals(View.VISIBLE, sentence.overlay.visibility)

        state.setCrop(null)
        assertEquals(View.INVISIBLE, word.overlay.visibility)
        assertEquals(View.INVISIBLE, sentence.overlay.visibility)
    }

    @Test fun `the remove button in one group takes both out of their containers`() {
        val activity = Robolectric.buildActivity(Host::class.java).setup().get()
        val state = state()
        val word = tab(activity, state)
        val sentence = tab(activity, state)
        awaitFrame(word, sentence)
        assertEquals(1, word.container.childCount)
        assertEquals(1, sentence.container.childCount)

        sentence.remove.performClick()
        assertNull(state.attachment)
        assertEquals(0, word.container.childCount)
        assertEquals(0, sentence.container.childCount)
    }

    @Test fun `the crop button opens the editor for the shared state, and is hidden until the frame lands`() {
        val activity = Robolectric.buildActivity(Host::class.java).setup().get()
        val state = state()
        var opened = 0
        val word = tab(activity, state) { opened++ }
        assertEquals(View.GONE, word.cropButton.visibility)
        awaitFrame(word)
        word.cropButton.performClick()
        assertEquals(1, opened)
        assertSame(word.group.view, word.container.getChildAt(0))
    }

    @Test fun `a state with nothing builds no group`() {
        val activity = Robolectric.buildActivity(Host::class.java).setup().get()
        val state = state()
        state.remove()
        val container = LinearLayout(activity)
        assertNull(addAnkiScreenshotGroup(container, activity, scope, state) {})
        assertEquals(0, container.childCount)
    }
}
