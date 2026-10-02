package com.playtranslate

import android.graphics.Bitmap
import android.graphics.Rect
import com.playtranslate.ui.TextBox
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * [TranslationOneShotProcessor]'s paint sequence for the press-and-hold
 * overlay: the first paint is the placeholders with what can be shown at once
 * (the reader's translations that arrived with its OCR, the cache's entries)
 * already in place, then a repaint on top of the first paint for each
 * translation the reader delivers while the translation runs, then the final
 * boxes. A device read (no reader: nothing delivered) paints once, its cached
 * groups translated and the rest skeletons, and returns the final boxes.
 *
 * Robolectric with legacy graphics for the color sampling's bitmap; the
 * translation is a fake that reports what a test scripts.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class TranslationOneShotProcessorTest {

    private val raw: Bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)

    private val ocr = OcrManager.OcrResult(
        fullText = "一 二",
        segments = emptyList(),
        groups = listOf(
            OcrManager.OcrGroup(text = "一", bounds = Rect(4, 4, 40, 20)),
            OcrManager.OcrGroup(text = "二", bounds = Rect(4, 28, 40, 44)),
        ),
    )

    private fun tr(text: String, backend: String = "Cloud") = CaptureService.GroupTranslation(text, null, backend)

    private fun build(
        atOnce: List<CaptureService.GroupTranslation?>,
        deliveries: List<List<CaptureService.GroupTranslation?>>,
        final: List<CaptureService.GroupTranslation>,
    ): Pair<List<List<String>>, List<String>> = runBlocking {
        val paints = mutableListOf<List<String>>()
        var askedFor: List<String>? = null
        val processor = TranslationOneShotProcessor(atOnce) { texts, onProgress ->
            askedFor = texts
            deliveries.forEach { onProgress(it) }
            final
        }
        val boxes = processor.buildBoxes(ocr, raw, 0, 0, 64, 64) { paints += it.map(TextBox::translatedText) }
        assertEquals(listOf("一", "二"), askedFor)
        paints to boxes.map(TextBox::translatedText)
    }

    @Test fun `ready translations are in the first paint, each delivery repaints, then the final boxes`() {
        val (paints, final) = build(
            atOnce = listOf(tr("one"), null),
            deliveries = listOf(listOf(tr("one"), tr("two"))),
            final = listOf(tr("one"), tr("two!", "Fake")),
        )
        assertEquals(listOf(listOf("one", ""), listOf("one", "two")), paints)
        assertEquals(listOf("one", "two!"), final)
    }

    @Test fun `a device read paints the skeletons once, then the final boxes`() {
        val (paints, final) = build(
            atOnce = listOf(null, null),
            deliveries = emptyList(),
            final = listOf(tr("one", "Fake"), tr("two", "Fake")),
        )
        assertEquals(listOf(listOf("", "")), paints)
        assertEquals(listOf("one", "two"), final)
    }

    @Test fun `a device read with a cached group paints it at once and the rest as skeletons, then the final boxes`() {
        val (paints, final) = build(
            atOnce = listOf(tr("one", "Fake"), null),
            deliveries = emptyList(),
            final = listOf(tr("one", "Fake"), tr("two", "Fake")),
        )
        assertEquals(listOf(listOf("one", "")), paints)
        assertEquals(listOf("one", "two"), final)
    }

    @Test fun `a delivery repaints on top of the first paint, so a cache-filled box keeps its text`() {
        val (paints, final) = build(
            atOnce = listOf(tr("one", "Fake"), null),
            // The reader names group 1 only; group 0 is the cache's.
            deliveries = listOf(listOf(null, tr("two"))),
            final = listOf(tr("one", "Fake"), tr("two")),
        )
        assertEquals(listOf(listOf("one", ""), listOf("one", "two")), paints)
        assertEquals(listOf("one", "two"), final)
    }
}
