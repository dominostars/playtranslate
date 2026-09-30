package com.playtranslate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.language.OcrBackend
import com.playtranslate.ocr.DeviceFrameReader
import com.playtranslate.ocr.ReaderAttribution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [DeviceFrameReader.attributionOf]: the "Scanned by" label and the re-OCR
 * picker's selection token of a device OCR result, the one derivation every
 * device result's provenance goes through (capture-once, the hold, the live
 * panels). MangaOCR extends the label only; the token stays the base engine's
 * so the gear still keys the picker. Robolectric for the Context (the Paddle
 * tier label is a string resource).
 */
@RunWith(RobolectricTestRunner::class)
class DeviceFrameReaderAttributionTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun result(backend: OcrBackend?, mangaOcrUsed: Boolean = false) = OcrManager.OcrResult(
        fullText = "テキスト",
        segments = emptyList(),
        engineBackend = backend,
        mangaOcrUsed = mangaOcrUsed,
    )

    @Test fun `a Meiki result is attributed to Meiki`() {
        assertEquals(
            ReaderAttribution("Meiki", "meiki"),
            DeviceFrameReader.attributionOf(result(OcrBackend.Meiki("x")), ctx),
        )
    }

    @Test fun `MangaOCR extends the label and keeps the base engine's token`() {
        assertEquals(
            ReaderAttribution("Meiki + MangaOCR", "meiki"),
            DeviceFrameReader.attributionOf(result(OcrBackend.Meiki("x"), mangaOcrUsed = true), ctx),
        )
    }

    @Test fun `a result with no engine identity has no attribution`() {
        assertNull(DeviceFrameReader.attributionOf(result(null), ctx))
    }

    @Test fun `the fast Paddle tier keys its own token`() {
        assertEquals(
            ReaderAttribution(ctx.getString(R.string.ocr_label_paddle_fast), "paddle-fast"),
            DeviceFrameReader.attributionOf(result(OcrBackend.Paddle("rec", fast = true)), ctx),
        )
    }
}
