package com.playtranslate.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import com.playtranslate.R
import com.playtranslate.themeColor
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.language.inflection.ConjugationCell
import com.playtranslate.language.inflection.ConjugationRow
import com.playtranslate.language.inflection.ConjugationSection
import com.playtranslate.language.inflection.ConjugationTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins [ConjugationTableView]'s structure over a [ConjugationTable]: a chip
 * per section opening on the table's section, the grid's label column and
 * cells, the highlighted cells, the rare column's header, and the list form.
 */
@RunWith(RobolectricTestRunner::class)
class ConjugationTableViewTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun cell(vararg forms: String, secondary: String? = null, highlighted: Boolean = false) =
        ConjugationCell(forms.toList(), listOfNotNull(secondary), highlighted)

    private val fixture = ConjugationTable(
        sections = listOf(
            ConjugationSection(
                "Indicativo", listOf("presente", "pretérito"),
                listOf(
                    ConjugationRow("yo", listOf(cell("hablo"), cell("hablé"))),
                    ConjugationRow("tú", listOf(cell("hablas", secondary = "hablás"), cell("hablaste"))),
                    ConjugationRow("nosotros", listOf(cell("hablamos", highlighted = true), cell("hablamos", highlighted = true))),
                ),
            ),
            ConjugationSection(
                "Subjuntivo", listOf("presente", "futuro"),
                listOf(ConjugationRow("yo", listOf(cell("hable"), cell("hablare")))),
                rareColumns = setOf(1), rareMarker = "raro",
            ),
            ConjugationSection(
                "No personales", emptyList(),
                listOf(
                    ConjugationRow("infinitivo", listOf(cell("hablar"))),
                    ConjugationRow("gerundio", listOf(cell("hablando"))),
                ),
                isList = true,
            ),
        ),
        openSection = 0,
    )

    @Test
    fun `binds a chip per section and opens on the table's section`() {
        val view = ConjugationTableView(ctx).apply { bind(fixture) }
        assertEquals(listOf("Indicativo", "Subjuntivo", "No personales"), view.chips.map { it.text.toString() })
        assertEquals(0, view.selectedSection)
        assertTrue(view.chips[0].isSelected)
        assertFalse(view.chips[1].isSelected)
        assertTrue(view.chips.all { it.isClickable && it.isFocusable })
    }

    @Test
    fun `the grid shows the pronoun column, the headers and the forms`() {
        val view = ConjugationTableView(ctx).apply { bind(fixture) }
        assertEquals(listOf("yo", "tú", "nosotros"), view.rowLabels.map { it.text.toString() })
        assertEquals(listOf("presente", "pretérito"), view.columnHeaders.map { it.text.toString() })
        assertEquals("hablo", view.cells[0][0].text.toString())
        assertEquals("hablé", view.cells[0][1].text.toString())
        // vos rides the tú cell as a second line.
        assertEquals("hablas\nhablás", view.cells[1][0].text.toString())
    }

    @Test
    fun `the arrival form's cells are the highlighted ones, drawn bold`() {
        val view = ConjugationTableView(ctx).apply { bind(fixture) }
        assertEquals(ConjugationTableView.HIGHLIGHT_TAG, view.cells[2][0].tag)
        assertEquals(ConjugationTableView.HIGHLIGHT_TAG, view.cells[2][1].tag)
        assertNull(view.cells[0][0].tag)
        // Robolectric does not answer Typeface.isBold for the platform's bold
        // default, so pin the typeface the cell was given.
        assertSame(Typeface.DEFAULT_BOLD, view.cells[2][0].typeface)
        assertNotSame(Typeface.DEFAULT_BOLD, view.cells[0][0].typeface)
        // The fill is inset from the cell, and the cell keeps its own padding.
        assertTrue(view.cells[2][0].background is InsetDrawable)
        assertEquals(view.cells[0][0].paddingTop, view.cells[2][0].paddingTop)
        assertNull(view.cells[0][0].background)
    }

    @Test
    fun `the chip holding the arrival form keeps a tint when another section is open`() {
        val view = ConjugationTableView(ctx).apply { bind(fixture) }
        val accent = ctx.themeColor(R.attr.ptAccent)
        val tint = ctx.themeColor(R.attr.ptAccentTint)
        val bg = ctx.themeColor(R.attr.ptBg)
        val muted = ctx.themeColor(R.attr.ptTextMuted)
        fun fill(i: Int) = (view.chips[i].background as GradientDrawable).color!!.defaultColor
        // Selected: solid accent, text in the background colour.
        assertEquals(accent, fill(0))
        assertEquals(bg, view.chips[0].currentTextColor)
        // A plain chip wears the faint divider fill.
        assertEquals(ctx.themeColor(R.attr.ptDivider), fill(1))
        // The grid area keeps the card's own colour (no band of its own).
        assertNull(view.body.background)
        view.selectSection(1)
        // Not selected but holding the arrival form: the lighter tint, text muted.
        assertEquals(tint, fill(0))
        assertEquals(muted, view.chips[0].currentTextColor)
        assertEquals(accent, fill(1))
        assertEquals(ctx.themeColor(R.attr.ptDivider), fill(2))
    }

    @Test
    fun `a chip tap switches the section and a rare column carries its marker`() {
        val view = ConjugationTableView(ctx).apply { bind(fixture) }
        view.chips[1].performClick()
        assertEquals(1, view.selectedSection)
        assertTrue(view.chips[1].isSelected)
        assertFalse(view.chips[0].isSelected)
        assertEquals(listOf("presente", "futuro (raro)"), view.columnHeaders.map { it.text.toString() })
        assertEquals("hablare", view.cells[0][1].text.toString())
    }

    @Test
    fun `a list section renders label and form rows without headers`() {
        val view = ConjugationTableView(ctx).apply { bind(fixture) }
        view.selectSection(2)
        assertTrue(view.columnHeaders.isEmpty())
        assertEquals(listOf("infinitivo", "gerundio"), view.rowLabels.map { it.text.toString() })
        assertEquals(listOf("hablar", "hablando"), view.cells.map { it.single().text.toString() })
    }

    @Test
    fun `a one-section table shows its rows without the chip strip`() {
        val forms = ConjugationTable(
            listOf(ConjugationSection("Forms", emptyList(), listOf(
                ConjugationRow("past", listOf(cell("spoke", highlighted = true))),
                ConjugationRow("past participle", listOf(cell("spoken"))),
            ), isList = true)),
            openSection = 0,
        )
        val view = ConjugationTableView(ctx).apply { bind(forms) }
        assertEquals(android.view.View.GONE, view.chipStrip.visibility)
        assertEquals(listOf("past", "past participle"), view.rowLabels.map { it.text.toString() })
        assertEquals(listOf("spoke", "spoken"), view.cells.map { it.single().text.toString() })
        // Binding a many-section table afterwards brings the strip back.
        view.bind(fixture)
        assertEquals(android.view.View.VISIBLE, view.chipStrip.visibility)
    }

    @Test
    fun `an out-of-range open section is clamped`() {
        val view = ConjugationTableView(ctx).apply { bind(fixture.copy(openSection = 7)) }
        assertEquals(2, view.selectedSection)
    }
}
