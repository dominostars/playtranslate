package com.playtranslate.ui

import android.content.Context
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.themeColor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * The Capture and overlay screen's "Minimum text size" row: warning line,
 * value label ("-" at the lowest value), slider, and an example drawn at the
 * value only above the lowest one.
 */
@RunWith(RobolectricTestRunner::class)
class OverlayMinTextRowTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun activity(): CaptureOverlaySettingsActivity {
        val controller = Robolectric.buildActivity(CaptureOverlaySettingsActivity::class.java)
        // setupDisplays() asks for the activity's display, which Robolectric's
        // activity context refuses; the Overlay group is built before it runs.
        val thrown = runCatching { controller.create() }.exceptionOrNull()
        assertTrue("$thrown", thrown == null || thrown is UnsupportedOperationException)
        return controller.get()
    }

    private val CaptureOverlaySettingsActivity.slider
        get() = findViewById<SizeSliderView>(R.id.sliderOverlayMinText)
    private val CaptureOverlaySettingsActivity.label
        get() = findViewById<TextView>(R.id.tvOverlayMinTextValue)
    private val CaptureOverlaySettingsActivity.example
        get() = findViewById<TextView>(R.id.tvOverlayMinTextExample)

    private fun sp(value: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value.toFloat(), ctx.resources.displayMetrics)

    @Test
    fun atTheDefault_showsADash_andNoExample() {
        val a = activity()
        assertEquals(6, a.slider.value)
        assertEquals("-", a.label.text.toString())
        assertEquals(View.GONE, a.example.visibility)
    }

    @Test
    fun raisingIt_persists_andShowsTheExampleAtThatSize() {
        val a = activity()
        repeat(3) { a.slider.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)) }
        assertEquals(9, Prefs(ctx).overlayMinTextSp)
        assertEquals("9", a.label.text.toString())
        assertEquals(View.VISIBLE, a.example.visibility)
        assertEquals(sp(9), a.example.textSize, 0.01f)
    }

    @Test
    fun backToTheDefault_showsADash_andHidesTheExampleAgain() {
        Prefs(ctx).overlayMinTextSp = 7
        val a = activity()
        assertEquals(View.VISIBLE, a.example.visibility)
        a.slider.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(6, Prefs(ctx).overlayMinTextSp)
        assertEquals("-", a.label.text.toString())
        assertEquals(View.GONE, a.example.visibility)
    }

    @Test
    fun theWarning_isShown_inTheWarningColor() {
        val a = activity()
        val warning = a.findViewById<TextView>(R.id.tvOverlayMinTextWarning)
        assertTrue(a.findViewById<View>(R.id.rowOverlayMinText).let { row ->
            generateSequence(warning.parent) { it.parent }.any { it === row }
        })
        assertEquals(View.VISIBLE, warning.visibility)
        assertEquals(a.getString(R.string.settings_overlay_min_text_warning), warning.text.toString())
        assertEquals(a.themeColor(R.attr.ptWarning), warning.currentTextColor)
    }

    @Test
    fun aStoredValue_isShownOnOpen() {
        Prefs(ctx).overlayMinTextSp = 14
        val a = activity()
        assertEquals(14, a.slider.value)
        assertEquals("14", a.label.text.toString())
        assertEquals(sp(14), a.example.textSize, 0.01f)
    }

    @Test
    fun thePref_clampsToTheSliderRange() {
        val prefs = Prefs(ctx)
        prefs.overlayMinTextSp = 25
        assertEquals(20, prefs.overlayMinTextSp)
        prefs.overlayMinTextSp = 3
        assertEquals(6, prefs.overlayMinTextSp)
    }
}
