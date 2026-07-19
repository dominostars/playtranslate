package com.playtranslate.imageimport

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import java.io.Closeable
import java.util.Locale

/**
 * A paged visual document under review: a single image, a PDF, or a comic
 * archive (CBZ). The review pipeline is page-blind — every page renders to
 * the same upright ARGB_8888 bitmap contract the single-image flow already
 * consumes (longest side capped at [UprightImageDecoder.MAX_DIMENSION_PX]).
 *
 * Rendering is blocking — call off the main thread. Implementations own
 * whatever backs them (a bitmap, a PdfRenderer + file descriptor, a
 * session-scoped zip working copy); [close] releases it. No source survives
 * process death by design (user decision: no document retention — re-upload
 * beats retaining user content in cache).
 */
interface PageSource : Closeable {
    val pageCount: Int

    /** Render page [index] at review resolution. Null = that page failed
     *  (corrupt entry); the caller surfaces the open-failure string. */
    fun renderPage(index: Int): Bitmap?

    /** Render a small thumbnail for the page grid. */
    fun renderThumb(index: Int, maxDim: Int): Bitmap?
}

/** The single-image source: the decoded bitmap, page-count 1. */
class ImagePageSource(bitmap: Bitmap) : PageSource {
    private var bitmap: Bitmap? = bitmap

    override val pageCount: Int get() = 1

    override fun renderPage(index: Int): Bitmap? = bitmap?.takeIf { !it.isRecycled }

    override fun renderThumb(index: Int, maxDim: Int): Bitmap? {
        val src = renderPage(index) ?: return null
        val scale = maxDim.toFloat() / maxOf(src.width, src.height)
        if (scale >= 1f) return src.copy(Bitmap.Config.ARGB_8888, false)
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    // Dropped for GC, never recycled — the review pipeline's cooperative
    // cancellation may still be reading it (the import flow's standing
    // bitmap discipline).
    override fun close() {
        bitmap = null
    }
}

/** Close on a worker thread. A [PdfPageSource]'s close contends for the
 *  render mutex — an in-flight page render would stall the MAIN thread for
 *  its remainder if close ran there — and teardown paths (review dismissal,
 *  activity destroy) must never block. A plain thread rather than a scope:
 *  destroy-time closes must survive the lifecycleScope's own cancellation
 *  (the app-start sweep precedent). */
fun PageSource.closeAsync() {
    val source = this
    Thread({ runCatching { source.close() } }, "import-source-close").start()
}

/** Typed open outcome — every failure has a user-facing string. */
sealed class OpenResult {
    class Ready(val source: PageSource) : OpenResult()
    class Failure(val reason: FailureReason) : OpenResult()

    enum class FailureReason { UNSUPPORTED, PASSWORD_PROTECTED, CORRUPT, EMPTY }
}

/** What a mime/filename pair routes to — pure, JVM-tested. */
enum class SourceKind { IMAGE, PDF, ARCHIVE, UNKNOWN }

/** Route by declared mime first (share intents and providers usually carry
 *  one), file extension as the fallback (SAF providers sometimes declare
 *  application/octet-stream). */
fun classifySource(mime: String?, fileName: String?): SourceKind {
    val m = mime?.lowercase(Locale.ROOT)
    when {
        m != null && m.startsWith("image/") -> return SourceKind.IMAGE
        m == "application/pdf" -> return SourceKind.PDF
        m == "application/zip" || m == "application/x-cbz" ||
            m == "application/vnd.comicbook+zip" -> return SourceKind.ARCHIVE
    }
    val ext = fileName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
    return when (ext) {
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif" -> SourceKind.IMAGE
        "pdf" -> SourceKind.PDF
        "cbz", "zip" -> SourceKind.ARCHIVE
        else -> SourceKind.UNKNOWN
    }
}

/**
 * Open [uri] as a paged source. Blocking (decode / copy / renderer setup) —
 * call on IO. [declaredMime] is the intent/clipboard-carried type when there
 * is one; the resolver's own type and the provider's DISPLAY_NAME back it
 * up.
 *
 * Routing is deliberately ASYMMETRIC: PDF and archive branches require
 * POSITIVE identification, but unknown metadata falls through to an image
 * decode attempt — the pre-generalization importer was content-based
 * (decode-first, metadata-blind) and providers/clipboards with null types
 * and opaque paths must keep working exactly as they did then. Metadata
 * only ever REDIRECTS to a non-image opener; it never rejects.
 *
 * [imageDecode] is injectable for tests (Robolectric cannot decode real
 * image bytes).
 */
fun openPageSource(
    ctx: Context,
    uri: Uri,
    declaredMime: String? = null,
    imageDecode: (Context, Uri) -> UprightImageDecoder.Result = { c, u ->
        UprightImageDecoder.decode(c, u)
    },
): OpenResult {
    val mime = declaredMime ?: runCatching { ctx.contentResolver.getType(uri) }.getOrNull()
    fun decodeAsImage(unknownMetadata: Boolean): OpenResult =
        when (val r = imageDecode(ctx, uri)) {
            is UprightImageDecoder.Result.Success -> OpenResult.Ready(ImagePageSource(r.bitmap))
            is UprightImageDecoder.Result.Failure -> OpenResult.Failure(
                // A file CLAIMING to be an image that fails to decode is
                // broken; a metadata-less file that fails the image attempt
                // is honestly "not a type we read".
                if (unknownMetadata) OpenResult.FailureReason.UNSUPPORTED
                else OpenResult.FailureReason.CORRUPT,
            )
        }
    return when (classifySource(mime, displayNameOf(ctx, uri))) {
        SourceKind.IMAGE -> decodeAsImage(unknownMetadata = false)
        SourceKind.PDF -> PdfPageSource.open(ctx, uri)
        SourceKind.ARCHIVE -> CbzPageSource.open(ctx, uri)
        SourceKind.UNKNOWN -> decodeAsImage(unknownMetadata = true)
    }
}

/** The user-facing file name for extension fallback. A SAF content URI's
 *  lastPathSegment is typically an OPAQUE document id (no extension) — the
 *  provider's DISPLAY_NAME is the real name; the raw segment remains the
 *  fallback for file:// URIs and unqueryable providers. */
private fun displayNameOf(ctx: Context, uri: Uri): String? {
    runCatching {
        ctx.contentResolver.query(
            uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx)?.let { return it }
            }
        }
    }
    return uri.lastPathSegment
}
