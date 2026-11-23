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
 * row, in each cell of the conditions that hide rows: a source language
 * with hint text (Overlay Mode shows; JA here, which also shows Filter
 * furigana) and a second screen (Hide overlays shows). Each row's divider comes and goes with it, so no cell stacks two
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
        val japanese = source == SourceLangId.JA
        assertEquals(
            listOfNotNull(
                "overlayModeSection".takeIf { hint },
                "dividerOverlayMode".takeIf { hint },
                "rowOverlayMinText", "dividerOverlayMinText",
                "rowFilterFurigana".takeIf { japanese },
                "dividerFilterFurigana".takeIf { japanese },
                "rowVerticalGrow", "dividerVerticalGrow",
                "rowEdgeIndicator", "dividerEdgeIndicator",
                "rowAutoCopy",
            ),
            visibleRows(activity, R.id.rowEdgeIndicator),
        )
    }

    /** The Overlay card's last row: Auto Copy to Clipboard, off on a new
     *  install, saved by its own tap either way, and shown as saved when the
     *  page is opened again. */
    @Test
    fun autoCopyRow_offUntilTapped_savedByItsTap() {
        fun open(): Activity {
            val controller = Robolectric.buildActivity(CaptureOverlaySettingsActivity::class.java)
            val thrown = runCatching { controller.create() }.exceptionOrNull()
            assertTrue("$thrown", thrown == null || thrown is UnsupportedOperationException)
            return controller.get()
        }
        fun Activity.row(): View = findViewById(R.id.rowAutoCopy)
        fun Activity.on(): Boolean =
            row().findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchRowToggle).isChecked

        val page = open()
        assertEquals(
            "Auto Copy to Clipboard",
            page.row().findViewById<android.widget.TextView>(R.id.tvRowTitle).text.toString(),
        )
        val subtitle = page.row().findViewById<android.widget.TextView>(R.id.tvRowSubtitle)
        assertEquals(View.VISIBLE, subtitle.visibility)
        assertEquals(
            "Copies the original text after a capture. Not during auto-translate.",
            subtitle.text.toString(),
        )
        assertEquals("off on a new install", false, page.on())
        assertEquals(false, Prefs(ctx).autoCopyOriginal)

        page.row().performClick()
        assertEquals(true, Prefs(ctx).autoCopyOriginal)
        assertEquals("the edge indicator's own setting is untouched", true, Prefs(ctx).edgeIndicatorEnabled)
        assertEquals("shown as saved when the page is opened again", true, open().on())

        page.row().performClick()
        assertEquals("off is saved the same way", false, Prefs(ctx).autoCopyOriginal)
    }

    /** The OCR group's order: its header, PlayTranslate Cloud's card (debug
     *  builds, which the unit tests are), then the engine card, which shows
     *  only for a language with an engine choice; the header shows with the
     *  cloud card even when the engine card is hidden. On the JVM the engine
     *  set is whatever the registry offers without native runtimes, so the
     *  cell reads it rather than assuming it. */
    @Test
    fun ocrGroup_cloudCardFirst_headerWithEitherCard() {
        for (source in listOf(SourceLangId.JA, SourceLangId.EN)) {
            Prefs(ctx).sourceLang = source.code
            val controller = Robolectric.buildActivity(CaptureOverlaySettingsActivity::class.java)
            val thrown = runCatching { controller.create() }.exceptionOrNull()
            assertTrue("$thrown", thrown == null || thrown is UnsupportedOperationException)
            val activity = controller.get()
            val engines = com.playtranslate.ocr.registry.OcrModelManager.availableBackends(activity, source).size > 1
            // The whole page column, whose unnamed views are skipped.
            val column = activity.findViewById<View>(R.id.cardPtCloud).parent as ViewGroup
            val page = (0 until column.childCount).map(column::getChildAt)
                .filter { it.visibility == View.VISIBLE && it.id != View.NO_ID }
                .map { activity.resources.getResourceEntryName(it.id) }
            assertEquals(
                "$source",
                listOfNotNull("headerOcr", "cardPtCloud", "cardOcr".takeIf { engines }),
                page.take(if (engines) 3 else 2),
            )
            // The MangaOCR card may follow for Japanese; the next group does after it.
            assertTrue("$source: $page", page.indexOf("headerAutoTranslate") > page.indexOf("cardPtCloud"))
            assertEquals(
                "PlayTranslate Cloud (Alpha)",
                activity.findViewById<View>(R.id.rowPtCloudOcr)
                    .findViewById<android.widget.TextView>(R.id.tvRowTitle).text.toString(),
            )
        }
    }

    /** Entry names of the visible views in the card column holding [rowId]. */
    private fun visibleRows(activity: Activity, rowId: Int): List<String> {
        val column = activity.findViewById<View>(rowId).parent as ViewGroup
        return (0 until column.childCount).map(column::getChildAt)
            .filter { it.visibility == View.VISIBLE }
            .map { activity.resources.getResourceEntryName(it.id) }
    }
}
