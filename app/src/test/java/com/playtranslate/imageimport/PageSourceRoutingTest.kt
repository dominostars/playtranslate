package com.playtranslate.imageimport

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the pure routing/ordering logic behind the generalized importer:
 * [classifySource] (mime first, extension fallback) and [NaturalOrder]
 * (CBZ page ordering).
 */
class PageSourceRoutingTest {

    // ── classifySource ──────────────────────────────────────────────────

    @Test fun mimeWinsOverExtension() {
        // A provider-declared mime outranks a misleading name.
        assertEquals(SourceKind.PDF, classifySource("application/pdf", "scan.jpg"))
        assertEquals(SourceKind.IMAGE, classifySource("image/webp", "download.bin"))
        assertEquals(SourceKind.ARCHIVE, classifySource("application/vnd.comicbook+zip", "x"))
    }

    @Test fun extensionCoversMimelessProviders() {
        assertEquals(SourceKind.IMAGE, classifySource(null, "IMG_2024.HEIC"))
        assertEquals(SourceKind.PDF, classifySource(null, "manual.pdf"))
        assertEquals(SourceKind.ARCHIVE, classifySource(null, "chapter-01.cbz"))
        assertEquals(SourceKind.ARCHIVE, classifySource("application/octet-stream", "pages.zip"))
    }

    @Test fun unknownStaysUnknown() {
        assertEquals(SourceKind.UNKNOWN, classifySource(null, "notes.txt"))
        assertEquals(SourceKind.UNKNOWN, classifySource("application/epub+zip", "book.epub"))
        assertEquals(SourceKind.UNKNOWN, classifySource(null, null))
    }

    // ── NaturalOrder ────────────────────────────────────────────────────

    @Test fun numericChunksCompareAsNumbers() {
        val entries = listOf("p10.jpg", "p2.jpg", "p1.jpg", "p100.jpg")
        assertEquals(
            listOf("p1.jpg", "p2.jpg", "p10.jpg", "p100.jpg"),
            entries.sortedWith(NaturalOrder),
        )
    }

    @Test fun zeroPaddingAndCaseAreHandled() {
        val entries = listOf("Page-003.png", "page-0020.png", "PAGE-1.png")
        assertEquals(
            listOf("PAGE-1.png", "Page-003.png", "page-0020.png"),
            entries.sortedWith(NaturalOrder),
        )
    }

    @Test fun directoriesSortByTheirOwnChunks() {
        // Equal numeric chunks (002 == 02 == 2) fall through to the next
        // character: '.' sorts before 'a'.
        val entries = listOf("ch2/01.jpg", "ch10/01.jpg", "ch1/02.jpg", "ch1/002a.jpg")
        assertEquals(
            listOf("ch1/02.jpg", "ch1/002a.jpg", "ch2/01.jpg", "ch10/01.jpg"),
            entries.sortedWith(NaturalOrder),
        )
    }

    @Test fun prefixOrdersBeforeLonger() {
        assertEquals(
            listOf("a.jpg", "a1.jpg", "ab.jpg"),
            listOf("ab.jpg", "a1.jpg", "a.jpg").sortedWith(NaturalOrder),
        )
    }
}
