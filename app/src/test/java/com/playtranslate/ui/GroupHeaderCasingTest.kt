package com.playtranslate.ui

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import com.playtranslate.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A group header's capitals come from its style (`Text.PT.GroupHeader`,
 * textAllCaps), which applies the UI language's case rules. Code that
 * uppercases the title itself runs first with the root locale's rules and
 * fixes Turkish i as dotless I («FIIL ÇEKIMI» for «Fiil çekimi»), so the
 * title goes in as written and the style alone capitalizes it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "tr")
class GroupHeaderCasingTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    @Test
    fun `a group header keeps its title as written and shows it in Turkish capitals`() {
        val activity = Robolectric.buildActivity(Host::class.java).setup().get()
        val parent = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        ankiGroupHeader(parent, "Fiil çekimi")
        val title = parent.findViewById<TextView>(R.id.tvGroupTitle)
        assertEquals("Fiil çekimi", title.text.toString())
        assertEquals(
            "FİİL ÇEKİMİ",
            title.transformationMethod.getTransformation(title.text, title).toString(),
        )
    }
}
