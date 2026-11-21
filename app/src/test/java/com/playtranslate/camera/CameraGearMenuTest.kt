package com.playtranslate.camera

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.OverlayMode
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.language.SourceLanguageProfiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * The camera and import tools' gear menu, its Overlays row: the tool's one
 * control for what it paints over the frame. It cycles through the modes
 * the tool offers in its current state and shows the mode on show now.
 */
@RunWith(RobolectricTestRunner::class)
class CameraGearMenuTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(ctx)

    private lateinit var activity: Activity
    private lateinit var menuHost: LinearLayout
    private lateinit var gearButton: ImageButton
    private var menu: CameraGearMenu? = null

    /** What the tool offers and shows; the picks it was handed. */
    private var modes: List<OverlayMode> = emptyList()
    private var current: OverlayMode = OverlayMode.TRANSLATION
    private val picks = mutableListOf<OverlayMode>()

    private fun sp() = ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)

    @Before fun setUp() {
        sp().edit().clear().commit()
        prefs.sourceLang = "ja"
        activity = Robolectric.buildActivity(Host::class.java).setup().get()
        val controls = FrameLayout(activity)
        activity.setContentView(controls)
        val pill = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_camera_control_pill)
        }
        val iconRow = LinearLayout(activity)
        gearButton = ImageButton(activity)
        iconRow.addView(gearButton, LinearLayout.LayoutParams(88, 88))
        menuHost = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        pill.addView(iconRow)
        pill.addView(menuHost)
        controls.addView(pill, FrameLayout.LayoutParams(-2, -2))
        menu = CameraGearMenu(
            activity = activity,
            pill = pill,
            iconRow = iconRow,
            menuHost = menuHost,
            gearButton = gearButton,
            controlsHost = controls,
            onLanguageRow = {},
            onOcrRow = {},
            overlayModes = { modes },
            overlayMode = { current },
            onPickOverlayMode = { picks += it; current = it },
        )
        idle()
    }

    @After fun tearDown() {
        menu?.destroy()
        idle()
        sp().edit().clear().commit()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

    private fun open() {
        gearButton.performClick()
        idle()
    }

    private fun close() {
        gearButton.performClick()
        idle()
    }

    /** The menu's row titled [title], or null. */
    private fun row(title: String): View? =
        (0 until menuHost.childCount).map { menuHost.getChildAt(it) }
            .firstOrNull { it.findViewById<TextView>(R.id.tvRowTitle)?.text?.toString() == title }

    private fun overlaysRow(): View? = row(activity.getString(R.string.floating_menu_panel_overlays))

    private fun valueOf(row: View): String = row.findViewById<TextView>(R.id.tvRowValue).text.toString()

    private fun label(mode: OverlayMode): String =
        activity.getString(mode.labelRes(SourceLanguageProfiles[prefs.sourceLangId].hintTextKind))

    @Test fun `the row cycles through what the tool offers, in order, its value following each pick`() {
        modes = listOf(OverlayMode.TRANSLATION, OverlayMode.FURIGANA, OverlayMode.DEFINITIONS, OverlayMode.NONE)
        current = OverlayMode.TRANSLATION
        open()
        val row = overlaysRow()!!
        assertEquals(label(OverlayMode.TRANSLATION), valueOf(row))
        val expected = listOf(
            OverlayMode.FURIGANA, OverlayMode.DEFINITIONS, OverlayMode.NONE, OverlayMode.TRANSLATION,
        )
        for (mode in expected) {
            row.performClick()
            assertEquals(mode, picks.last())
            assertEquals("the value shows the mode just picked", label(mode), valueOf(row))
            assertNotNull("the menu stays open through a pick", overlaysRow())
        }
        assertEquals(expected, picks)
    }

    @Test fun `the row offers only what the tool offers now, and reads it again at every tap`() {
        modes = listOf(OverlayMode.TRANSLATION, OverlayMode.FURIGANA)
        current = OverlayMode.FURIGANA
        open()
        val row = overlaysRow()!!
        row.performClick()
        assertEquals(OverlayMode.TRANSLATION, picks.last())
        // The tool's state changed under the open menu (a snapshot came up):
        // the next tap cycles the new list.
        modes = listOf(OverlayMode.TRANSLATION, OverlayMode.FURIGANA, OverlayMode.NONE)
        current = OverlayMode.FURIGANA
        row.performClick()
        assertEquals(OverlayMode.NONE, picks.last())
    }

    @Test fun `a language without readings has the row whenever there is more than one mode`() {
        prefs.sourceLang = "en"
        modes = listOf(OverlayMode.TRANSLATION, OverlayMode.NONE)
        current = OverlayMode.TRANSLATION
        open()
        val row = overlaysRow()!!
        row.performClick()
        assertEquals(OverlayMode.NONE, picks.last())
        assertEquals(label(OverlayMode.NONE), valueOf(row))
    }

    @Test fun `there is no row while the tool has a single mode`() {
        modes = listOf(OverlayMode.TRANSLATION)
        open()
        assertNull(overlaysRow())
    }

    @Test fun `the row shows the mode on show at each opening`() {
        modes = listOf(OverlayMode.TRANSLATION, OverlayMode.FURIGANA, OverlayMode.NONE)
        current = OverlayMode.TRANSLATION
        open()
        assertEquals(label(OverlayMode.TRANSLATION), valueOf(overlaysRow()!!))
        close()
        // A snapshot's review remembers None, its boxes switch.
        current = OverlayMode.NONE
        open()
        assertEquals(label(OverlayMode.NONE), valueOf(overlaysRow()!!))
    }
}
