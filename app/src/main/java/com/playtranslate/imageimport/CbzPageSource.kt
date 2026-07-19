package com.playtranslate.imageimport

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Comic-archive (CBZ = zip of images) pages.
 *
 * Random page access needs [ZipFile], which needs a real file — the content
 * URI is copied to a SESSION-SCOPED working file in cache (the Yomitan
 * import's pattern), deleted at [close]; crash leftovers are age-swept by
 * the activity alongside the frame files. This is a transient working copy,
 * not retention: nothing restores it across process death.
 *
 * Page order is the archives' convention: image entries sorted
 * NATURALLY (numeric chunks compare as numbers — "page2" before "page10"),
 * case-insensitive, so scan orders survive naive numbering.
 */
class CbzPageSource private constructor(
    private val workingCopy: File,
    private val zip: ZipFile,
    private val entryNames: List<String>,
    private val maxEntryBytes: Long,
) : PageSource {

    /** Serializes zip reads: page renders and grid-thumbnail jobs run on
     *  different workers, and ZipFile's cross-thread guarantees are not
     *  worth betting a review on. Byte extraction is the only guarded part;
     *  decoding runs unlocked. */
    private val lock = Any()
    private var closed = false

    override val pageCount: Int get() = entryNames.size

    override fun renderPage(index: Int): Bitmap? = decodeEntry(index)

    override fun renderThumb(index: Int, maxDim: Int): Bitmap? =
        decodeEntry(index, maxDim)

    private fun decodeEntry(index: Int, maxDim: Int = UprightImageDecoder.MAX_DIMENSION_PX): Bitmap? {
        if (index !in entryNames.indices) return null
        val bytes = synchronized(lock) {
            if (closed) return null
            readEntryBounded(entryNames[index])
        } ?: return null
        return when (val r = UprightImageDecoder.decode(bytes, maxDim)) {
            is UprightImageDecoder.Result.Success -> r.bitmap
            is UprightImageDecoder.Result.Failure -> null
        }
    }

    /** Read an entry with a HARD byte ceiling enforced during the read —
     *  archives are untrusted input, the central directory's size can lie,
     *  and the decoder's downsampling only bounds the BITMAP, not the bytes
     *  a `readBytes()` would have materialized first (a zip-bombed entry
     *  would OOM the process before any cap applied). Callers hold [lock].
     *  Null = oversized/corrupt entry → the page fails controlled. */
    private fun readEntryBounded(name: String): ByteArray? = try {
        val entry = zip.getEntry(name)
        if (entry == null || entry.size > maxEntryBytes) {
            null
        } else {
            zip.getInputStream(entry).use { input ->
                val expected = entry.size.coerceIn(0L, INITIAL_BUFFER_CAP).toInt()
                val out = java.io.ByteArrayOutputStream(maxOf(expected, 8 * 1024))
                val buf = ByteArray(64 * 1024)
                var total = 0L
                var truncated = false
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > maxEntryBytes) {
                        truncated = true
                        break
                    }
                    out.write(buf, 0, n)
                }
                if (truncated) {
                    Log.w(TAG, "CBZ entry '$name' exceeds $maxEntryBytes bytes; skipped")
                    null
                } else {
                    out.toByteArray()
                }
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "CBZ entry '$name' read failed", e)
        null
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "CBZ entry '$name' read OOM", e)
        null
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { zip.close() }
        }
        runCatching { workingCopy.delete() }
    }

    companion object {
        private const val TAG = "CbzPageSource"

        /** Working-copy prefix — the activity's orphan sweep matches it. */
        const val TEMP_PREFIX = "import-cbz-"

        /** Per-entry byte ceiling: generous next to any real page scan (a
         *  50 MP JPEG runs ~25 MB) while bounding what an untrusted archive
         *  can make the process materialize. */
        const val MAX_ENTRY_BYTES = 64L * 1024 * 1024

        /** Whole-archive ceiling for the working copy: generous next to any
         *  real comic volume (they run tens to a few hundred MB) while
         *  bounding what a picked multi-GB file can write into app cache
         *  before validation even starts. */
        const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024

        /** Page-count ceiling — a UI bound, NOT load-bearing security (the
         *  archive byte cap and ZipFile's own central-directory parse bound
         *  the real resource costs): a crafted archive with a million tiny
         *  entries would otherwise produce a degenerate million-page chip
         *  and a seconds-long sort. Several times any real volume. */
        const val MAX_PAGES = 2000

        /** Pre-size hint cap for the read buffer — never pre-allocate more
         *  than this on the header's say-so. */
        private const val INITIAL_BUFFER_CAP = 1L shl 20

        private val IMAGE_EXTENSIONS =
            setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")

        /** Blocking open — IO thread: copy the URI to the working file,
         *  enumerate + sort image entries. EVERY failure path deletes the
         *  working copy immediately (a partial copy from an I/O or
         *  disk-full failure must not squat in cache until the 24h sweep —
         *  repeated failed imports would stack them). [maxEntryBytes] is
         *  the per-entry ceiling ([MAX_ENTRY_BYTES]); parameterized for
         *  tests. */
        fun open(
            ctx: Context,
            uri: Uri,
            maxEntryBytes: Long = MAX_ENTRY_BYTES,
            maxArchiveBytes: Long = MAX_ARCHIVE_BYTES,
            maxPages: Int = MAX_PAGES,
        ): OpenResult {
            var temp: File? = null
            var zipInFlight: ZipFile? = null
            try {
                // Known-oversize fast path: reject on the provider's
                // declared length without copying a byte. Metadata can be
                // absent or lie — the counted copy below is the enforcement.
                val declared = runCatching {
                    ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
                }.getOrNull()
                if (declared != null &&
                    declared != android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH &&
                    declared > maxArchiveBytes
                ) {
                    Log.w(TAG, "CBZ rejected: declared $declared bytes exceeds $maxArchiveBytes")
                    return OpenResult.Failure(OpenResult.FailureReason.CORRUPT)
                }
                val dst = File.createTempFile(TEMP_PREFIX, ".zip", ctx.cacheDir)
                temp = dst
                val withinCap = ctx.contentResolver.openInputStream(uri)?.use { input ->
                    dst.outputStream().use { out ->
                        // Counted copy with a HARD cap: an untrusted
                        // multi-GB pick must not fill app cache before any
                        // validation runs.
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        var ok = true
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            total += n
                            if (total > maxArchiveBytes) {
                                ok = false
                                break
                            }
                            out.write(buf, 0, n)
                        }
                        ok
                    }
                } ?: run {
                    dst.delete()
                    return OpenResult.Failure(OpenResult.FailureReason.CORRUPT)
                }
                if (!withinCap) {
                    Log.w(TAG, "CBZ copy aborted: stream exceeds $maxArchiveBytes bytes")
                    dst.delete()
                    return OpenResult.Failure(OpenResult.FailureReason.CORRUPT)
                }
                val zip = ZipFile(dst)
                zipInFlight = zip
                // Entries whose DECLARED size exceeds the ceiling are
                // dropped up front so the page count reflects readable
                // pages; unknown sizes (-1) pass and the bounded read
                // enforces the ceiling at decode time (headers can lie).
                val unsorted = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.size <= maxEntryBytes }
                    .map { it.name }
                    .filter {
                        it.substringAfterLast('.', "").lowercase(Locale.ROOT) in IMAGE_EXTENSIONS
                    }
                    .toList()
                // Page-count bound BEFORE the sort (the sort is the cost
                // being bounded); truncating instead would show arbitrary
                // central-directory-order pages — failing is honest.
                if (unsorted.size > maxPages) {
                    Log.w(TAG, "CBZ rejected: ${unsorted.size} image entries exceeds $maxPages")
                    runCatching { zip.close() }
                    dst.delete()
                    return OpenResult.Failure(OpenResult.FailureReason.CORRUPT)
                }
                val names = unsorted.sortedWith(NaturalOrder)
                if (names.isEmpty()) {
                    runCatching { zip.close() }
                    dst.delete()
                    return OpenResult.Failure(OpenResult.FailureReason.EMPTY)
                }
                zipInFlight = null
                return OpenResult.Ready(CbzPageSource(dst, zip, names, maxEntryBytes))
            } catch (e: Exception) {
                Log.w(TAG, "CBZ open failed for $uri", e)
                zipInFlight?.let { runCatching { it.close() } }
                temp?.delete()
                return OpenResult.Failure(OpenResult.FailureReason.CORRUPT)
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "CBZ open OOM for $uri", e)
                zipInFlight?.let { runCatching { it.close() } }
                temp?.delete()
                return OpenResult.Failure(OpenResult.FailureReason.CORRUPT)
            }
        }
    }
}

/**
 * Natural-order string comparison: digit runs compare numerically, text
 * chunks case-insensitively — "p2.jpg" < "p10.jpg", "Ch1/003" < "Ch1/020".
 * Pure — JVM-tested.
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ia = i
                while (ia < a.length && a[ia].isDigit()) ia++
                var jb = j
                while (jb < b.length && b[jb].isDigit()) jb++
                val na = a.substring(i, ia).trimStart('0')
                val nb = b.substring(j, jb).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ia
                j = jb
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
