package com.playtranslate.ui

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.language.SourceLangId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDisplayManager

/**
 * The Capture and overlay screen's Auto-translate and Overlay cards, row by
 * row, in each cell of the two conditions that hide rows: a source language
 * with hint text (Overlay Mode shows) and a second screen (Hide overlays
 * shows). Each row's divider comes and goes with it, so no cell stacks two
 * dividers; the Hide overlays divider used to stay up on a single screen.
 */
@RunWith(RobolectricTestRunner::class)
class CaptureOverlaySettingsLayoutTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun hintLanguage_singleScreen() = assertCards(SourceLangId.JA, multiScreen = false)

    @Test
    fun hintLanguage_multiScreen() = assertCards(SourceLangId.JA, multiScreen = true)

    @Test
    fun noHintLanguage_singleScreen() = assertCards(SourceLangId.EN, multiScreen = false)

    @Test
    fun noHintLanguage_multiScreen() = assertCards(SourceLangId.EN, multiScreen = true)

    private fun assertCards(source: SourceLangId, multiScreen: Boolean) {
        Prefs(ctx).sourceLang = source.code
        if (multiScreen) ShadowDisplayManager.addDisplay("w640dp-h480dp")
        val controller = Robolectric.buildActivity(CaptureOverlaySettingsActivity::class.java)
        // setupDisplays() asks for the activity's display, which Robolectric's activity
        // context refuses; both cards are built before it runs.
        val thrown = runCatching { controller.create() }.exceptionOrNull()
        assertTrue("$thrown", thrown == null || thrown is UnsupportedOperationException)
        val activity = controller.get()
        assertEquals(
            listOfNotNull(
                "rowEnhancedAutoTranslate", "dividerEnhancedAutoTranslate",
                "rowHideOverlays".takeIf { multiScreen },
                "dividerHideOverlays".takeIf { multiScreen },
                "rowTouchesRefresh", "dividerTouchesRefresh",
                "rowCaptureInterval",
            ),
            visibleRows(activity, R.id.rowTouchesRefresh),
        )
        val hint = source == SourceLangId.JA
        assertEquals(
            listOfNotNull(
                "overlayModeSection".takeIf { hint },
                "dividerOverlayMode".takeIf { hint },
                "rowOverlayMinText", "dividerOverlayMinText",
                "rowVerticalGrow", "dividerVerticalGrow",
                "rowEdgeIndicator",
            ),
            visibleRows(activity, R.id.rowEdgeIndicator),
        )
    }

    /** Entry names of the visible views in the card column holding [rowId]. */
    private fun visibleRows(activity: Activity, rowId: Int): List<String> {
        val column = activity.findViewById<View>(rowId).parent as ViewGroup
        return (0 until column.childCount).map(column::getChildAt)
            .filter { it.visibility == View.VISIBLE }
            .map { activity.resources.getResourceEntryName(it.id) }
    }
}
