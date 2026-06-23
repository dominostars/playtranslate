package com.playtranslate.ui

import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the opt-in empty-state in [WordDefinitionsView.bind]: a word with no
 * renderable senses shows the [WordDefinitionsView.emptyPlaceholder] line (the
 * "no dictionary entry" case the magnifying lens and in-app tap-word popup
 * hit) — but ONLY when a caller opted in by setting the field. Guards the
 * exact regression class this change fixed: the no-entry path used to return
 * null / empty and dismiss the surface entirely, and the shared renderer also
 * serves [WordResultCell] / DictionaryLookupActivity, which must NOT sprout a
 * placeholder when they bind empty senses.
 */
@RunWith(RobolectricTestRunner::class)
class WordDefinitionsViewTest {

    // The view resolves pt* theme attrs at construction; wrap the app context
    // in the base app theme so they resolve (themeColor returns 0 otherwise —
    // harmless here, but this mirrors the production path).
    private val ctx = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext(),
        R.style.Theme_PlayTranslate,
    )

    private val placeholder = "No definitions found."

    private fun emptyData() = WordDefinitionData(
        word = "foo", reading = null, senses = emptyList(),
        freqScore = 0, isCommon = false,
    )

    /** Text of every TextView in the subtree. */
    private fun texts(v: View): List<String> = when (v) {
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        is TextView -> listOf(v.text.toString())
        else -> emptyList()
    }

    @Test
    fun emptySenses_withPlaceholderSet_rendersPlaceholder() {
        val v = WordDefinitionsView(ctx).apply { emptyPlaceholder = placeholder }
        v.bind(emptyData(), label = null, scale = 1f)
        assertTrue(
            "placeholder should render for empty senses when opted in",
            texts(v).any { it == placeholder },
        )
    }

    @Test
    fun emptySenses_withoutPlaceholder_rendersNothing() {
        // WordResultCell's case: never opts in, so empty senses → empty body.
        val v = WordDefinitionsView(ctx)
        v.bind(emptyData(), label = null, scale = 1f)
        assertTrue("no opt-in → no placeholder leakage", texts(v).none { it == placeholder })
    }

    @Test
    fun nonEmptySenses_withPlaceholderSet_doesNotRenderPlaceholder() {
        val v = WordDefinitionsView(ctx).apply { emptyPlaceholder = placeholder }
        val data = emptyData().copy(
            senses = listOf(SenseDisplay(pos = emptyList(), definition = "to eat")),
        )
        v.bind(data, label = null, scale = 1f)
        val t = texts(v)
        assertTrue("the real definition renders", t.any { it.contains("to eat") })
        assertFalse("placeholder must not appear when senses exist", t.any { it == placeholder })
    }
}
