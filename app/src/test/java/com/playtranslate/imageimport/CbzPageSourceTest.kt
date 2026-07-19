package com.playtranslate.imageimport

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins [CbzPageSource]'s untrusted-archive handling: the working-copy
 * lifecycle (created on open, deleted on close AND on every failure path —
 * a partial copy must not squat in cache until the 24h sweep) and the
 * per-entry byte ceiling (declared-size filtering at open; archives are
 * adversarial input).
 */
@RunWith(RobolectricTestRunner::class)
class CbzPageSourceTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun tempCount(): Int =
        ctx.cacheDir.listFiles()?.count { it.name.startsWith(CbzPageSource.TEMP_PREFIX) } ?: 0

    @Before fun cleanCache() {
        ctx.cacheDir.listFiles()
            ?.filter { it.name.startsWith(CbzPageSource.TEMP_PREFIX) }
            ?.forEach { it.delete() }
    }

    /** A real one-pixel PNG, produced by the platform encoder. */
    private fun pngBytes(): ByteArray {
        val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private fun writeZip(entries: Map<String, ByteArray>): Uri {
        val file = File(ctx.cacheDir, "fixture.cbz")
        ZipOutputStream(file.outputStream()).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return Uri.fromFile(file)
    }

    @Test fun openReadsNaturallyOrderedPagesAndCloseDeletesTheWorkingCopy() {
        val png = pngBytes()
        val uri = writeZip(
            mapOf(
                "p10.png" to png,
                "p2.png" to png,
                "notes.txt" to "ignored".toByteArray(),
                "p1.png" to png,
            ),
        )
        val result = CbzPageSource.open(ctx, uri)
        assertTrue(result is OpenResult.Ready)
        val source = (result as OpenResult.Ready).source
        assertEquals(3, source.pageCount)
        assertEquals(1, tempCount())
        // renderPage is deliberately NOT asserted: Robolectric's
        // ImageDecoder cannot decode real image bytes, so the decode half
        // is device-validated; the bounded READ and lifecycle are what this
        // test pins.
        source.close()
        assertEquals("close() must delete the working copy", 0, tempCount())
    }

    @Test fun oversizedDeclaredEntriesAreFilteredAtOpen() {
        val png = pngBytes()
        val uri = writeZip(mapOf("p1.png" to png, "p2.png" to png))
        // A ceiling below the PNG's size filters every entry → EMPTY, and
        // the failure path leaves no working copy behind.
        val result = CbzPageSource.open(ctx, uri, maxEntryBytes = 4)
        assertTrue(result is OpenResult.Failure)
        assertEquals(
            OpenResult.FailureReason.EMPTY,
            (result as OpenResult.Failure).reason,
        )
        assertEquals(0, tempCount())
    }

    @Test fun tooManyPagesIsRejectedWithNoWorkingCopy() {
        // The page-count UI bound: exceeding it fails honestly (silently
        // truncating would show arbitrary central-directory-order pages).
        val png = pngBytes()
        val uri = writeZip(mapOf("p1.png" to png, "p2.png" to png, "p3.png" to png))
        val result = CbzPageSource.open(ctx, uri, maxPages = 2)
        assertTrue(result is OpenResult.Failure)
        assertEquals(
            OpenResult.FailureReason.CORRUPT,
            (result as OpenResult.Failure).reason,
        )
        assertEquals(0, tempCount())
    }

    @Test fun unreadableSourceLeavesNoWorkingCopy() {
        val result =
            CbzPageSource.open(ctx, Uri.parse("content://com.playtranslate.test/absent.cbz"))
        assertTrue(result is OpenResult.Failure)
        assertEquals("no stranded partial copies", 0, tempCount())
    }

    @Test fun oversizedArchiveIsRejectedWithNoWorkingCopy() {
        // Whichever bound trips first (declared-length fast path or the
        // counted copy), the contract is the same: controlled failure,
        // nothing stranded in cache.
        val uri = writeZip(mapOf("p1.png" to pngBytes()))
        val result = CbzPageSource.open(ctx, uri, maxArchiveBytes = 16)
        assertTrue(result is OpenResult.Failure)
        assertEquals(
            OpenResult.FailureReason.CORRUPT,
            (result as OpenResult.Failure).reason,
        )
        assertEquals("no partial copy stranded", 0, tempCount())
    }

    @Test fun corruptArchiveLeavesNoWorkingCopy() {
        val junk = File(ctx.cacheDir, "junk.cbz").apply { writeText("not a zip") }
        val result = CbzPageSource.open(ctx, Uri.fromFile(junk))
        assertTrue(result is OpenResult.Failure)
        assertEquals(
            OpenResult.FailureReason.CORRUPT,
            (result as OpenResult.Failure).reason,
        )
        assertEquals("copy deleted on zip-open failure", 0, tempCount())
    }
}
