package com.playtranslate.ui

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.playtranslate.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * [QuickSettingsRows]: the quick settings as the icon menu's gear panel shows
 * them. A row with a value shows it, and goes on showing it as the row's own
 * tap changes it (Overlays cycling, MangaOCR flipping) with no rebuild; a
 * link row shows its icon and no value.
 */
@RunWith(RobolectricTestRunner::class)
class QuickSettingsRowsTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private val activity: Host = Robolectric.buildActivity(Host::class.java).setup().get()
    private val container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    private fun rowOf(id: QuickSetting.Id): View {
        for (i in 0 until container.childCount) {
            val v = container.getChildAt(i)
            if (v.getTag(R.id.quickSettingRow) == id) return v
        }
        error("no row for $id")
    }

    private fun valueOf(id: QuickSetting.Id): String =
        rowOf(id).findViewById<TextView>(R.id.tvRowValue).text.toString()

    @Test fun `a row shows its value, and the value its own tap sets`() {
        val modes = listOf("Translation", "Furigana")
        var at = 0
        val overlays = QuickSetting(QuickSetting.Id.OVERLAYS, "Overlays", modes[0]) { row ->
            at = (at + 1) % modes.size
            row.value = modes[at]
        }
        val ocr = QuickSetting(QuickSetting.Id.OCR, "OCR", "ML Kit") {}
        QuickSettingsRows.populate(container, listOf(ocr, overlays), iconTint = 0)
        assertEquals("Translation", valueOf(QuickSetting.Id.OVERLAYS))
        assertEquals("ML Kit", valueOf(QuickSetting.Id.OCR))

        rowOf(QuickSetting.Id.OVERLAYS).performClick()
        assertEquals("the row on show follows", "Furigana", valueOf(QuickSetting.Id.OVERLAYS))
        assertEquals("its neighbour is left alone", "ML Kit", valueOf(QuickSetting.Id.OCR))

        rowOf(QuickSetting.Id.OVERLAYS).performClick()
        assertEquals("Translation", valueOf(QuickSetting.Id.OVERLAYS))

        // And a value set from outside a tap, while the row is up.
        ocr.value = "MangaOCR"
        assertEquals("MangaOCR", valueOf(QuickSetting.Id.OCR))
    }

    @Test fun `a link row shows its icon and runs its tap`() {
        var opened = 0
        val open = QuickSetting(QuickSetting.Id.OPEN_APP, "Open PlayTranslate", icon = R.drawable.ic_open_in_new) { opened++ }
        QuickSettingsRows.populate(container, listOf(open), iconTint = 0)

        val row = rowOf(QuickSetting.Id.OPEN_APP)
        assertNotNull(row.findViewById<ImageView>(R.id.ivRowIcon).drawable)
        assertNull("no value on a link", row.findViewById<TextView?>(R.id.tvRowValue))
        row.performClick()

        assertEquals(1, opened)
    }
}
