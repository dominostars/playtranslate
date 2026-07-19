package com.playtranslate.imageimport

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the routing's decode-first floor: metadata may REDIRECT to a
 * non-image opener but must never reject — a content URI with a null mime
 * and an opaque path (no extension) still reaches the image decoder,
 * exactly as the pre-generalization importer behaved. The decoder is
 * injected (Robolectric cannot decode real image bytes).
 */
@RunWith(RobolectricTestRunner::class)
class OpenPageSourceTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    /** Unregistered provider: getType() returns null; the last path segment
     *  is an opaque document-id-style token with no extension. */
    private val opaqueUri: Uri = Uri.parse("content://com.some.provider/document/4217")

    @Test fun unknownMetadataFallsThroughToImageDecode() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        var attempted = false
        val result = openPageSource(ctx, opaqueUri) { _, _ ->
            attempted = true
            UprightImageDecoder.Result.Success(bitmap)
        }
        assertTrue("routing must attempt the image decode", attempted)
        assertTrue(result is OpenResult.Ready)
        assertEquals(1, (result as OpenResult.Ready).source.pageCount)
    }

    @Test fun unknownMetadataThatFailsDecodeIsUnsupported() {
        val result = openPageSource(ctx, opaqueUri) { _, _ ->
            UprightImageDecoder.Result.Failure("not an image")
        }
        assertTrue(result is OpenResult.Failure)
        assertEquals(
            OpenResult.FailureReason.UNSUPPORTED,
            (result as OpenResult.Failure).reason,
        )
    }

    @Test fun declaredImageMimeThatFailsDecodeIsCorrupt() {
        // A file CLAIMING to be an image that fails decode is broken, not
        // unsupported — the toasts differ.
        val result = openPageSource(ctx, opaqueUri, declaredMime = "image/png") { _, _ ->
            UprightImageDecoder.Result.Failure("truncated")
        }
        assertTrue(result is OpenResult.Failure)
        assertEquals(
            OpenResult.FailureReason.CORRUPT,
            (result as OpenResult.Failure).reason,
        )
    }

    @Test fun positiveMetadataStillRedirectsAwayFromImages() {
        // A declared PDF must not fall into the image decoder even though
        // the decode-first floor exists (asymmetric routing).
        var attempted = false
        val result = openPageSource(ctx, opaqueUri, declaredMime = "application/pdf") { _, _ ->
            attempted = true
            UprightImageDecoder.Result.Failure("should not run")
        }
        assertTrue(!attempted)
        // The unregistered provider cannot open a descriptor — the PDF
        // branch fails its own way; the point is the routing.
        assertTrue(result is OpenResult.Failure)
    }

    @Test fun documentLayoutBias_singleImageOff_declaredDocumentsOn() {
        // 2026-07-19 review finding: a lone imported image is as likely a
        // game screenshot as a document page — the page-rhythm grouping
        // prior must stay off for it, and on only for declared documents.
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        assertEquals(false, documentLayoutBiasFor(ImagePageSource(bitmap)))
        assertEquals(true, documentLayoutBiasFor(MultiImagePageSource(ctx, emptyList())))
    }
}
