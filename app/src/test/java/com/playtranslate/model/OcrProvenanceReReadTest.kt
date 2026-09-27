package com.playtranslate.model

import com.playtranslate.RegionEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OcrProvenance.canReRead]: an OCR-tool switch never re-reads a saved frame
 * that can contain our own overlays (a live pinhole-tier frame carries our
 * translation boxes, the floating icon and any error pill), so neither host
 * offers the gear for one. Frames without them, and results saved before the
 * flag existed, re-read as before.
 */
class OcrProvenanceReReadTest {

    private fun provenance(includesOwnOverlays: Boolean?) = OcrProvenance(
        engineLabel = "ML Kit",
        engineToken = "mlkit",
        displayId = 0,
        sourceLangId = com.playtranslate.language.SourceLangId.JA,
        region = RegionEntry("", 0f, 1f, 0f, 1f),
        frameIncludesOwnOverlays = includesOwnOverlays,
    )

    @Test fun `a frame that carries our own overlays is never re-read`() {
        assertFalse(provenance(includesOwnOverlays = true).canReRead)
    }

    @Test fun `a clean frame, or one saved before the flag existed, is`() {
        assertTrue(provenance(includesOwnOverlays = false).canReRead)
        assertTrue(provenance(includesOwnOverlays = null).canReRead)
    }
}
