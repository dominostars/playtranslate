package com.playtranslate.ui

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import com.playtranslate.R
import com.playtranslate.themeColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * [PillAnkiButton] over the shared pill layout (layout/anki_pill.xml),
 * inflated the way the workspace and the capture sheet inflate it: with a
 * plain inflater, which drops the glyph's app:tint. The class tints the glyph
 * itself, runs its tap and hold handlers, and swaps to its spinner while a
 * one-tap send is out.
 */
@RunWith(RobolectricTestRunner::class)
class PillAnkiButtonTest {

    /** A plain Activity: no AppCompat inflater factory, so app:tint is dropped. */
    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private fun inflatePill(activity: Activity): FrameLayout =
        LayoutInflater.from(activity).inflate(R.layout.anki_pill, FrameLayout(activity), false) as FrameLayout

    // Mutations: drop the tint; swap the tap and hold handlers.
    @Test fun `a plain-inflated pill gets its glyph tinted, and its tap and hold run their handlers`() {
        val activity = Robolectric.buildActivity(Host::class.java).setup().get()
        val pill = inflatePill(activity)
        val glyph = (pill.getChildAt(0) as ViewGroup).getChildAt(0) as ImageView
        assertNull("precondition: the plain inflater dropped app:tint", glyph.imageTintList)

        var taps = 0
        var holds = 0
        PillAnkiButton(pill, onTap = { taps++ }, onHold = { holds++ })

        assertEquals("the glyph takes the on-accent colour",
            activity.themeColor(R.attr.ptAccentOn), glyph.imageTintList?.defaultColor)
        pill.performClick()
        assertEquals(1, taps)
        assertTrue("a hold is consumed", pill.performLongClick())
        assertEquals(1, holds)
        assertEquals("a hold is no tap", 1, taps)
    }

    @Test fun `loading swaps the icon and label for a spinner and blocks taps, and back`() {
        val activity = Robolectric.buildActivity(Host::class.java).setup().get()
        val pill = inflatePill(activity)
        val content = pill.getChildAt(0)
        val button = PillAnkiButton(pill, onTap = {}, onHold = {})
        val spinner = pill.getChildAt(1)

        button.setLoading(true)
        assertFalse(pill.isEnabled)
        assertEquals("the pill keeps its width", View.INVISIBLE, content.visibility)
        assertEquals(View.VISIBLE, spinner.visibility)

        button.setLoading(false)
        assertTrue(pill.isEnabled)
        assertEquals(View.VISIBLE, content.visibility)
        assertEquals(View.GONE, spinner.visibility)
    }
}
