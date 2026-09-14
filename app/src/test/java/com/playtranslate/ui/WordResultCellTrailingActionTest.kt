package com.playtranslate.ui

import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.children
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the results cell's trailing slot and stub mode
 * ([WordResultCell.TrailingAction]): the Anki action keeps today's plain
 * card-stack button; the Hide action puts the eye there; a hidden word
 * draws the cell as a stub (word only, eye-off, body gone, speak and
 * chevron invisible so the columns hold); a deck-badge update while stubbed
 * changes nothing on screen but renders once the word is shown again; and a
 * stub-then-show round trip restores the cell exactly (no leak into a
 * recycled cell).
 */
@RunWith(RobolectricTestRunner::class)
class WordResultCellTrailingActionTest {

    private val ctx = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext(),
        R.style.Theme_PlayTranslate,
    )

    // isCommon puts a Common pill in the body, so the flat body has a child
    // without needing structured senses.
    private val data = WordDefinitionData(
        word = "食べる", reading = "たべる", senses = emptyList(),
        freqScore = 0, isCommon = true,
    )

    private fun cell(trailing: WordResultCell.TrailingAction, onCellTap: () -> Unit = {}): WordResultCell =
        WordResultCell(ctx).also {
            it.bind(data, WordResultCell.DEFAULT_SCALE, emptyList(), onCellTap, {}, trailing)
        }

    // ─── C1 ───────────────────────────────────────────────────────────

    @Test
    fun ankiAction_plainCardStackButton_bodyVisible() {
        var taps = 0
        val c = cell(WordResultCell.TrailingAction.Anki { taps++ })
        assertEquals(R.drawable.ic_card_stack_add, c.trailingIconRes)
        assertEquals(ctx.getString(R.string.cd_add_to_anki), trailingSlot(c).contentDescription)
        assertTrue(definitions(c).isVisible)
        assertTrue(readingView(c).isVisible)
        trailingSlot(c).performClick()
        assertEquals(1, taps)
    }

    // ─── C2 ───────────────────────────────────────────────────────────

    @Test
    fun hideAction_visibleWord_eyeButton_bodyVisible() {
        var toggles = 0
        val c = cell(WordResultCell.TrailingAction.Hide(hidden = false) { shown ->
            assertFalse("the tap reports the state the cell showed", shown)
            toggles++
        })
        assertEquals(R.drawable.ic_visibility, c.trailingIconRes)
        assertEquals(
            ctx.getString(R.string.hidden_word_hide_content_description),
            trailingSlot(c).contentDescription,
        )
        assertTrue(definitions(c).isVisible)
        assertTrue(readingView(c).isVisible)
        assertFalse(speakSlot(c).isInvisible)
        assertFalse(chevronSlot(c).isInvisible)
        assertNull(bandColor(c))
        trailingSlot(c).performClick()
        assertEquals(1, toggles)
    }

    // ─── C3 ───────────────────────────────────────────────────────────

    @Test
    fun hideAction_hiddenWord_stub() {
        var cellTaps = 0
        val c = cell(WordResultCell.TrailingAction.Hide(hidden = true) {}, onCellTap = { cellTaps++ })
        assertEquals(R.drawable.ic_visibility_off, c.trailingIconRes)
        assertEquals(
            ctx.getString(R.string.hidden_word_show_content_description),
            trailingSlot(c).contentDescription,
        )
        assertEquals("食べる", wordView(c).text.toString())
        assertTrue(readingView(c).isGone)
        assertTrue(readingsFlow(c).isGone)
        assertTrue(inflectionView(c).isGone)
        assertTrue(definitions(c).isGone)
        // Speak is invisible, not gone: the columns keep their places. The
        // chevron stays: the stub still opens Word Detail.
        assertTrue(speakSlot(c).isInvisible)
        assertFalse(speakSlot(c).isGone)
        assertTrue(chevronSlot(c).isVisible)
        // The band is drawn as the cell's own background (a view alpha would
        // leave the card colour showing through), under the ripple.
        assertEquals(hiddenWordBandColor(ctx), bandColor(c))
        assertEquals(1f, c.alpha, 0.001f)
        c.performClick()
        assertEquals(1, cellTaps)
    }

    // ─── C4 ───────────────────────────────────────────────────────────

    @Test
    fun deckUpdateWhileStubbed_staysHidden_rendersOnShow() {
        val c = cell(WordResultCell.TrailingAction.Hide(hidden = true) {})
        c.updateAnkiDecks(listOf("Deck1"))
        assertTrue(definitions(c).isGone)
        assertTrue(texts(definitions(c)).none { it.contains("Deck1") })

        c.setHidden(false)
        assertTrue(definitions(c).isVisible)
        assertTrue("deck pill renders from the decks kept while stubbed",
            texts(definitions(c)).any { it.contains("Deck1") })
        assertEquals(R.drawable.ic_visibility, c.trailingIconRes)
    }

    // ─── C5 ───────────────────────────────────────────────────────────

    @Test
    fun stubRoundTrip_restoresFullCellExactly() {
        val reference = cell(WordResultCell.TrailingAction.Hide(hidden = false) {})
        val c = cell(WordResultCell.TrailingAction.Hide(hidden = false) {})
        c.setHidden(true)
        assertTrue(definitions(c).isGone)
        c.setHidden(false)

        assertEquals(reference.paddingTop, c.paddingTop)
        assertEquals(reference.paddingBottom, c.paddingBottom)
        assertNull(bandColor(c))
        assertEquals(reference.background?.javaClass, c.background?.javaClass)
        assertEquals(wordView(reference).textSize, wordView(c).textSize, 0.01f)
        assertEquals(wordView(reference).currentTextColor, wordView(c).currentTextColor)
        assertEquals(wordView(reference).paddingTop, wordView(c).paddingTop)
        assertEquals(readingView(reference).visibility, readingView(c).visibility)
        assertEquals(readingsFlow(reference).visibility, readingsFlow(c).visibility)
        assertEquals(inflectionView(reference).visibility, inflectionView(c).visibility)
        assertEquals(definitions(reference).visibility, definitions(c).visibility)
        assertEquals(speakSlot(reference).visibility, speakSlot(c).visibility)
        assertEquals(chevronSlot(reference).visibility, chevronSlot(c).visibility)
        assertEquals(reference.trailingIconRes, c.trailingIconRes)
        assertEquals(trailingSlot(reference).contentDescription, trailingSlot(c).contentDescription)
        // Unchanged flag is a no-op (no rebind churn).
        c.setHidden(false)
        assertEquals(R.drawable.ic_visibility, c.trailingIconRes)
    }

    // ─── C6 ───────────────────────────────────────────────────────────

    @Test
    fun toggleReportsTheStateShown_afterEveryFlip() {
        val seen = mutableListOf<Boolean>()
        val c = cell(WordResultCell.TrailingAction.Hide(hidden = false) { seen += it })
        trailingSlot(c).performClick()
        c.setHidden(true)
        trailingSlot(c).performClick()
        c.setHidden(false)
        trailingSlot(c).performClick()
        // Each tap hands over what the icon showed at that moment, through
        // the same callback across in-place flips; no store read involved.
        assertEquals(listOf(false, true, false), seen)
    }

    // ─── Structure (init order: headRow, readingsFlow, inflectionView,
    //     styledContainer, definitionsView; headRow: title group, speak,
    //     trailing, chevron) ───────────────────────────────────────────

    private fun headRow(c: WordResultCell) = c.getChildAt(0) as LinearLayout
    private fun titleGroup(c: WordResultCell) = headRow(c).getChildAt(0) as LinearLayout
    private fun wordView(c: WordResultCell) = titleGroup(c).getChildAt(0) as TextView
    private fun readingView(c: WordResultCell) = titleGroup(c).getChildAt(1) as TextView
    private fun speakSlot(c: WordResultCell) = headRow(c).getChildAt(1) as FrameLayout
    private fun trailingSlot(c: WordResultCell) = headRow(c).getChildAt(2) as FrameLayout
    private fun chevronSlot(c: WordResultCell) = headRow(c).getChildAt(3) as FrameLayout
    private fun readingsFlow(c: WordResultCell): View = c.getChildAt(1)
    private fun inflectionView(c: WordResultCell) = c.getChildAt(2) as TextView
    private fun definitions(c: WordResultCell) = c.children.filterIsInstance<WordDefinitionsView>().single()

    /** The colour of the band layer under the cell's background, or null
     *  when the background is the plain ripple (no band). RippleDrawable
     *  itself extends LayerDrawable and carries a ColorDrawable mask, so it
     *  is excluded first. */
    private fun bandColor(c: WordResultCell): Int? {
        val bg = c.background
        if (bg is RippleDrawable || bg !is LayerDrawable) return null
        return (bg.getDrawable(0) as? ColorDrawable)?.color
    }

    private fun texts(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> v.children.flatMap { texts(it) }.toList()
        else -> emptyList()
    }
}
