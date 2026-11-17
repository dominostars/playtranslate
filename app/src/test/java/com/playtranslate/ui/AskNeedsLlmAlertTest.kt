package com.playtranslate.ui

import android.app.Activity
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.applyTheme
import com.playtranslate.overlayThemedContext
import com.playtranslate.themeColor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * The "Ask needs an AI model" alert's button ([askNeedsLlm]), built on each
 * kind of context its callers pass: an app activity, themed as every one of
 * them is ([applyTheme]: the in-activity results page, the camera's
 * frozen-frame lens); the over-game themed context ([overlayThemedContext]:
 * the floating workspace's pages, the capture sheet); and the raw display
 * context of the service (the drag lens's and the tap-to-look-up card's
 * default alert target, [DragLookupController]), whose theme is the
 * platform's and has none of the app's colours.
 *
 * On every one, in the dark theme and the light, the button is filled with
 * the user's accent as the alert resolves its own colours (on its themed
 * context, [OverlayAlert.Builder]) and labelled in the colour the theme
 * gives text over the accent. Coral is the accent, so the default (teal)
 * cannot pass for it.
 */
@RunWith(RobolectricTestRunner::class)
class AskNeedsLlmAlertTest {

    /** An app activity's theme, as every activity of the app applies it. */
    class ThemedActivity : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            applyTheme(this)
            super.onCreate(savedInstanceState)
        }
    }

    private val app: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(app)

    @Before fun setUp() {
        clearPrefs()
        prefs.accentName = AccentColor.Coral.name
    }

    @After fun tearDown() = clearPrefs()

    private fun clearPrefs() {
        app.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** The alert built on [ctx] as its callers build it, shown in a parent so
     *  its views exist; its first button, the one [askNeedsLlm] adds. */
    private fun firstButton(ctx: Context): Button {
        val parent = FrameLayout(ctx)
        OverlayAlert.Builder(ctx).askNeedsLlm(ctx) {}.showInParent(parent)
        val scrim = parent.getChildAt(0) as FrameLayout
        val column = (scrim.getChildAt(0) as ScrollView).getChildAt(0) as LinearLayout
        return (0 until column.childCount).map(column::getChildAt).filterIsInstance<Button>().first()
    }

    /** The button's fill: its rounded rect's colour, or transparent where the
     *  alert drew none (a zero colour). */
    private fun Button.fill(): Int = when (val bg = background) {
        is GradientDrawable -> bg.color!!.defaultColor
        is ColorDrawable -> bg.color
        else -> error("unexpected background $bg")
    }

    private fun activityContext(): Context =
        Robolectric.buildActivity(ThemedActivity::class.java).setup().get()

    private fun displayContext(): Context {
        val display = app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        return app.createDisplayContext(display)
    }

    private fun hex(color: Int) = "#" + Integer.toHexString(color).padStart(8, '0')

    /** On [ctx], under [mode]: the accent fill and the accent's text colour,
     *  as the alert's own themed context resolves them, read together so a
     *  failure names both. */
    private fun assertAccentButton(kind: String, mode: ThemeMode, ctx: () -> Context) {
        prefs.themeMode = mode
        val context = ctx()
        val themed = overlayThemedContext(context)
        val button = firstButton(context)
        val accent = app.getColor(AccentColor.Coral.color)
        assertEquals("$kind, $mode: the theme's accent is the user's", accent, themed.themeColor(R.attr.ptAccent))
        assertEquals(
            "$kind, $mode: fill and label",
            "fill ${hex(accent)}, label ${hex(themed.themeColor(R.attr.ptAccentOn))}",
            "fill ${hex(button.fill())}, label ${hex(button.currentTextColor)}",
        )
    }

    @Test fun `on an app activity the button is the accent with the accent's text colour`() {
        for (mode in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) assertAccentButton("activity", mode, ::activityContext)
    }

    @Test fun `on the over-game themed context the button is the accent with the accent's text colour`() {
        for (mode in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
            assertAccentButton("overlay-themed", mode) { overlayThemedContext(app) }
        }
    }

    /** The drag lens's and the tap-to-look-up card's alert target: the
     *  service's display context, with the platform's theme. */
    @Test fun `on the service's raw display context the button is the accent with the accent's text colour`() {
        assertEquals(
            "precondition: the raw context's theme has no accent", 0, displayContext().themeColor(R.attr.ptAccent),
        )
        for (mode in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) assertAccentButton("display", mode, ::displayContext)
    }
}
