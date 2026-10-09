package com.playtranslate.ui

import android.app.Activity
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.playtranslate.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * Pins the magnifier lens's "Load more (n)" row ([LensLoadMore]): it reads
 * the count, a tap reaches [MagnifierLens.onLoadMoreTap] once per gesture and
 * never while loading, [MagnifierLens.setLoadMoreLoading] flips its text in
 * place, it is the split body's last controller target, a rebind without it
 * removes it (moving a controller cursor that sat on it to the first
 * section), and its ripple stays off the background the nav tint owns.
 */
@RunWith(RobolectricTestRunner::class)
class MagnifierLensLoadMoreTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private lateinit var controller: ActivityController<Host>
    private lateinit var lens: MagnifierLens
    private var loadMoreTaps = 0

    private fun section(word: String, reading: String, opens: Boolean = true) = LensSection(
        WordDefinitionData(word, reading, emptyList(), freqScore = 0, isCommon = false),
        label = null,
        opens = opens,
    )

    private val primary = section("弾ける", "はじける")
    private val sec1 = section("弾く", "ひく")
    private val sec2 = section("弾く", "はじく")

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(Host::class.java).setup()
        val activity = controller.get()
        lens = MagnifierLens(activity, activity.windowManager, Display.DEFAULT_DISPLAY)
        lens.onLoadMoreTap = { loadMoreTaps++ }
        val dm = activity.resources.displayMetrics
        // Finger low on the screen: the lens sits above it, not flipped, so
        // the body is below the chrome and DPAD_DOWN walks into it.
        lens.show(dm.widthPixels / 2, dm.heightPixels * 3 / 4, dm.widthPixels, dm.heightPixels)
        lens.makeInteractive()
    }

    @After
    fun tearDown() {
        lens.dismiss()
        controller.pause().stop().destroy()
    }

    /** Moves the clock past the 300 ms debounce the row shares with the open
     *  taps. Robolectric's clock starts near zero, which is inside the window
     *  of a lens that has never been tapped. */
    private fun pastDebounce() = ShadowSystemClock.advanceBy(Duration.ofMillis(400))

    private fun row(): TextView = requireNotNull(lens.loadMoreRowForTest()) { "no row bound" }

    private fun press(target: View, keyCode: Int) {
        target.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        target.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    @Test fun `row shows the count and a click fires once`() {
        lens.setSplitDefinitions(primary, emptyList(), false, LensLoadMore(3))
        val row = row()
        assertEquals("Load more (3)", row.text.toString())

        pastDebounce()
        row.performClick()
        row.performClick()   // a second delivery of the same gesture, inside the debounce
        assertEquals(1, loadMoreTaps)
    }

    @Test fun `row ignores clicks while loading`() {
        lens.setSplitDefinitions(primary, emptyList(), false, LensLoadMore(2))
        val row = row()
        pastDebounce()
        row.performClick()
        assertEquals(1, loadMoreTaps)

        lens.setLoadMoreLoading(true)
        assertSame("flipped in place, no rebind", row, lens.loadMoreRowForTest())
        assertEquals("Looking up…", row.text.toString())
        pastDebounce()
        row.performClick()
        assertEquals("a loading row is inert", 1, loadMoreTaps)

        lens.setLoadMoreLoading(false)
        assertEquals("Load more (2)", row.text.toString())
        pastDebounce()
        row.performClick()
        assertEquals(2, loadMoreTaps)
    }

    @Test fun `row is the last nav target`() {
        lens.setSplitDefinitions(primary, emptyList(), false, LensLoadMore(2))
        val row = row()
        val nav = lens.navSectionsForTest()
        assertEquals(2, nav.size)
        assertSame(row, nav.last())
        // The zero-secondary shape: the primary's column, a divider, the row.
        val body = row.parent as ViewGroup
        assertEquals(3, body.childCount)
        assertSame(nav.first(), body.getChildAt(0))
        assertSame(row, body.getChildAt(2))

        // A primary with nothing to open leaves the row as the only target.
        lens.setSplitDefinitions(
            section("弾ける", "はじける", opens = false), emptyList(), false, LensLoadMore(2),
        )
        assertEquals(listOf<View>(row()), lens.navSectionsForTest())
    }

    @Test fun `rebind without the row removes it`() {
        lens.setSplitDefinitions(primary, emptyList(), false, LensLoadMore(2))
        val oldRow = row()

        lens.setSplitDefinitions(primary, listOf(sec1, sec2), false)
        assertNull(lens.loadMoreRowForTest())
        assertNull("detached", oldRow.parent)
        val nav = lens.navSectionsForTest()
        assertEquals(3, nav.size)
        assertFalse(oldRow in nav)

        // Without a row the loading flip is a no-op.
        lens.setLoadMoreLoading(true)
        assertEquals("Load more (2)", oldRow.text.toString())
    }

    @Test fun `the row carries no background`() {
        lens.setSplitDefinitions(primary, emptyList(), false, LensLoadMore(2))
        val row = row()
        // The nav tint writes and then nulls a target's background, so the
        // ripple must live in the foreground.
        assertNull(row.background)
        assertNotNull(row.foreground)
    }

    @Test fun `a controller cursor on the row lands on the first section when a rebind drops it`() {
        lens.setSplitDefinitions(primary, emptyList(), false, LensLoadMore(2))
        val row = row()
        val window = row.rootView
        lens.focusPillForController()
        press(window, KeyEvent.KEYCODE_DPAD_DOWN)   // pill to the primary
        press(window, KeyEvent.KEYCODE_DPAD_DOWN)   // primary to the row
        assertNotNull("the cursor tints the row", row.background)

        lens.setSplitDefinitions(primary, listOf(sec1, sec2), false)
        val nav = lens.navSectionsForTest()
        assertNotNull("the cursor tints the first section", nav[0].background)
        assertNull(nav[1].background)
        assertNull(nav[2].background)
    }
}
