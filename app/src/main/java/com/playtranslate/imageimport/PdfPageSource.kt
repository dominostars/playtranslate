package com.playtranslate.imageimport

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log

/**
 * PDF pages via the platform [PdfRenderer].
 *
 * Contracts this class exists to hold:
 *  - ALL renderer access is serialized on [lock] — PdfRenderer is not
 *    thread-safe and allows only one open page at a time (page renders and
 *    grid thumbnails race otherwise).
 *  - Every render target is filled WHITE before [PdfRenderer.Page.render]:
 *    the renderer composites onto transparency, and OCR (plus the review's
 *    black letterbox) reads an unfilled bitmap as garbage.
 *  - A vector page has no native resolution: pages render with the longest
 *    side at the import cap, and that raster IS "native" for the review
 *    zoom's ceiling (deep-zoom re-render at higher DPI is a later quality
 *    lever, deliberately not built).
 *
 * The file descriptor lives for the review session; process death drops it
 * and the user re-picks (no document retention by design).
 */
class PdfPageSource private constructor(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) : PageSource {

    private val lock = Any()
    private var closed = false

    override val pageCount: Int get() = renderer.pageCount

    override fun renderPage(index: Int): Bitmap? =
        render(index, UprightImageDecoder.MAX_DIMENSION_PX)

    override fun renderThumb(index: Int, maxDim: Int): Bitmap? = render(index, maxDim)

    private fun render(index: Int, maxDim: Int): Bitmap? = synchronized(lock) {
        if (closed || index !in 0 until renderer.pageCount) return null
        try {
            renderer.openPage(index).use { page ->
                val scale = maxDim.toFloat() / maxOf(page.width, page.height)
                val w = (page.width * scale).toInt().coerceAtLeast(1)
                val h = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                val m = Matrix().apply { setScale(scale, scale) }
                page.render(bitmap, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        } catch (e: Exception) {
            Log.w(TAG, "PDF page $index render failed", e)
            null
        } catch (e: OutOfMemoryError) {
            // The render target is bounded (≤ cap² ARGB), but allocation can
            // still fail under pressure — a failed page beats a dead process.
            Log.w(TAG, "PDF page $index render OOM", e)
            null
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { renderer.close() }
            runCatching { pfd.close() }
        }
    }

    companion object {
        private const val TAG = "PdfPageSource"

        /** Blocking open — IO thread. Password-protected PDFs surface their
         *  own failure (the renderer throws SecurityException); anything
         *  else unreadable is CORRUPT; a zero-page document is EMPTY. */
        fun open(ctx: Context, uri: Uri): OpenResult {
            val pfd = try {
                ctx.contentResolver.openFileDescriptor(uri, "r")
            } catch (e: Exception) {
                Log.w(TAG, "PDF descriptor open failed for $uri", e)
                null
            } ?: return OpenResult.Failure(OpenResult.FailureReason.CORRUPT)
            val renderer = try {
                PdfRenderer(pfd)
            } catch (e: SecurityException) {
                runCatching { pfd.close() }
                return OpenResult.Failure(OpenResult.FailureReason.PASSWORD_PROTECTED)
            } catch (e: Exception) {
                Log.w(TAG, "PDF open failed for $uri", e)
                runCatching { pfd.close() }
                return OpenResult.Failure(OpenResult.FailureReason.CORRUPT)
            }
            if (renderer.pageCount <= 0) {
                runCatching { renderer.close() }
                runCatching { pfd.close() }
                return OpenResult.Failure(OpenResult.FailureReason.EMPTY)
            }
            return OpenResult.Ready(PdfPageSource(pfd, renderer))
        }
    }
}
