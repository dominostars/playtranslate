package com.playtranslate.ui

import android.app.Activity
import android.graphics.Outline
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.ViewOutlineProvider
import com.playtranslate.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.time.Duration

/**
 * The lens draws its card's rounded shape itself, so nothing clipped its
 * rows to it, and a pinned word head's band, as wide as its row, squared
 * the card's corners off. The definitions' scroller now clips to a rounded
 * outline inset like the scroller itself (the card's radius less the edge
 * buffer), and as tall as the grow's clip while the card grows, so the
 * bottom corners are rounded on every frame of it.
 *
 * No engine lays a page out here, so the test stands in for it
 * ([MagnifierLens.layoutRowsForTest]).
 */
@RunWith(RobolectricTestRunner::class)
class MagnifierLensRoundedClipTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private lateinit var controller: ActivityController<Host>
    private lateinit var activity: Activity
    private lateinit var lens: MagnifierLens

    private fun section(word: String, reading: String) = LensSection(
        WordDefinitionData(word, reading, emptyList(), freqScore = 0, isCommon = false),
        label = null,
        opens = true,
        anki = false,
        explain = null,
    )

    @Before
    fun setUp() {
        DefinitionShells.reset()
        controller = Robolectric.buildActivity(Host::class.java).setup()
        activity = controller.get()
        lens = MagnifierLens(activity, activity.windowManager, Display.DEFAULT_DISPLAY)
        val dm = activity.resources.displayMetrics
        lens.show(dm.widthPixels / 2, dm.heightPixels * 3 / 4, dm.widthPixels, dm.heightPixels)
        lens.makeInteractive()
    }

    @After
    fun tearDown() {
        lens.dismiss()
        controller.pause().stop().destroy()
        DefinitionShells.reset()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun outlineOf(v: View): Pair<Rect, Float> {
        val o = Outline()
        v.outlineProvider.getOutline(v, o)
        val r = Rect()
        assertTrue("a rounded rect", o.getRect(r))
        return r to o.radius
    }

    /** Definitions on the lens, laid out and the card grown to them. */
    private fun showDefinitions() {
        lens.setSplitDefinitions(section("猫", "ねこ"), listOf(section("犬", "いぬ"), section("鳥", "とり")), false)
        idle()
        lens.layoutRowsForTest(400)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
    }

    @Test fun `the rows are clipped to the card's rounded shape, inset like the scroller`() {
        showDefinitions()
        val scroll = requireNotNull(lens.definitionsScrollForTest())
        assertTrue("precondition: the rows are on show", scroll.isShown && scroll.height > 0)
        assertTrue(scroll.clipToOutline)
        val (rect, radius) = outlineOf(scroll)
        assertEquals(Rect(0, 0, scroll.width, scroll.height), rect)
        val density = activity.resources.displayMetrics.density
        assertEquals("the card's 16dp corners less the 2dp edge buffer", 14f * density, radius, 0.5f)
    }

    @Test fun `each frame of the grow clips the rows at its own height, and its end at the whole scroller`() {
        showDefinitions()
        val scroll = requireNotNull(lens.definitionsScrollForTest())
        val buffer = Math.round(2 * activity.resources.displayMetrics.density)
        val finalCard = scroll.height + 2 * buffer
        lens.beginGrowForTest(finalCard, 0)
        idle()
        val full = scroll.height
        val heights = mutableListOf<Int>()
        val rounded = scroll.outlineProvider
        scroll.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                rounded.getOutline(view, outline)
                heights += Rect().also { outline.getRect(it) }.height()
            }
        }
        heights.clear()
        lens.growFrameForTest(finalCard - 100, 0)
        assertEquals("a frame 100 px short of the end clips 100 px short", listOf(full - 100), heights)
        lens.growFrameForTest(finalCard - 40, 0)
        assertEquals(full - 40, heights.last())
        lens.endGrowForTest()
        assertEquals("the grow's end: the whole scroller", full, heights.last())
    }
}
