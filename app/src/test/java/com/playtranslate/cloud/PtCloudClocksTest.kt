package com.playtranslate.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two per-frame clock lines ([PtCloudClocks]), field by field: every
 * field present, each absent value as a dash (the warm lead as `none`), the
 * final status and the reworded count per outcome, and inputs a server or a
 * clock could hand over that must never make either line throw (the final
 * line runs inside part 2's read, which must never fail).
 */
class PtCloudClocksTest {

    /** What the read keeps of [entries] (group id, text, arrival): the item
     *  lines' count and times, and their texts by group id (the last one,
     *  should a group repeat), as [FrameResponse.items] holds them. */
    private fun items(vararg entries: Triple<Int, String, Long>): Pair<ItemLines, Map<Int, String>> {
        val lines = ItemLines()
        val texts = LinkedHashMap<Int, String>()
        for ((id, text, atMs) in entries) {
            lines.add(atMs)
            texts[id] = text
        }
        return lines to texts
    }

    private fun final(
        status: String = "ok",
        translations: List<WireTranslation>? = listOf(WireTranslation(0, "Hello"), WireTranslation(1, "Hello there")),
        error: WireTranslationError? = null,
        timings: Map<String, Double> = mapOf("translate_ms" to 988.0, "total_ms" to 1210.0),
    ) = Part2Result.Line(
        TranslationPart(
            part = "translation", schemaVersion = 1, translationStatus = status, translations = translations,
            translationError = error, timings = timings,
        ),
    )

    // ── part 1 ─────────────────────────────────────────────────────────

    @Test fun `part 1 with every field present`() {
        val line = PtCloudClocks.part1Line(
            frameId = "d0-123456", bytes = 217_000, width = 1920, height = 1080,
            encodeMs = 31, headersMs = 402, part1Ms = 455, groups = 3, translated = 2, connectionReused = true,
            uploadMs = 120, warmLeadMs = 380, edgeUploadHeader = "118", edgeHeader = "430.6",
            timings = mapOf("recv_ms" to 110.4, "ocr_ms" to 250.0, "ocr_total_ms" to 300.5, "det_ms" to 90.0),
        )
        assertEquals(
            "part1 id=d0-123456 bytes=217000 size=1920x1080 encode=31 hdr=402 part1=455 groups=3 tr1=2 " +
                "reused=true upload=120 warm=380 edge_upload=118 edge=431 recv=110 ocr=250 ocr_total=301",
            line,
        )
    }

    @Test fun `part 1 with nothing reported prints dashes, and no warm prints none`() {
        val line = PtCloudClocks.part1Line(
            frameId = "d1-7", bytes = 145_000, width = 1280, height = 720,
            encodeMs = 272, headersMs = 870, part1Ms = 872, groups = 0, translated = 0, connectionReused = null,
            uploadMs = null, warmLeadMs = null, edgeUploadHeader = null, edgeHeader = null, timings = emptyMap(),
        )
        assertEquals(
            "part1 id=d1-7 bytes=145000 size=1280x720 encode=272 hdr=870 part1=872 groups=0 tr1=0 " +
                "reused=- upload=- warm=none edge_upload=- edge=- recv=- ocr=- ocr_total=-",
            line,
        )
    }

    @Test fun `a fresh connection prints reused false`() {
        val line = PtCloudClocks.part1Line(
            "f", 1, 1, 1, 0, 0, 0, 0, 0, connectionReused = false, uploadMs = 0, warmLeadMs = 0,
            edgeUploadHeader = null, edgeHeader = null, timings = emptyMap(),
        )
        assertTrue(" reused=false upload=0 warm=0 " in line)
    }

    @Test fun `hostile part 1 inputs never throw and print a dash or the clamped number`() {
        for (bad in listOf("", "  ", "abc", "12ms", "NaN", "Infinity", "-Infinity", "1e400", "0x10")) {
            val line = PtCloudClocks.part1Line(
                "", Int.MAX_VALUE, 0, 0, Long.MAX_VALUE, Long.MIN_VALUE, -5, -1, Int.MIN_VALUE,
                connectionReused = null, uploadMs = -1, warmLeadMs = Long.MAX_VALUE,
                edgeUploadHeader = bad, edgeHeader = bad,
                timings = mapOf("recv_ms" to Double.NaN, "ocr_ms" to Double.POSITIVE_INFINITY, "ocr_total_ms" to Double.NEGATIVE_INFINITY),
            )
            assertEquals(
                bad,
                "part1 id= bytes=${Int.MAX_VALUE} size=0x0 encode=${Long.MAX_VALUE} hdr=${Long.MIN_VALUE} " +
                    "part1=-5 groups=-1 tr1=${Int.MIN_VALUE} reused=- upload=-1 warm=${Long.MAX_VALUE} edge_upload=- " +
                    "edge=- recv=- ocr=- ocr_total=-",
                line,
            )
        }
        // A finite number beyond a Long clamps instead of throwing; a header
        // with spaces around it still reads; negatives round as numbers.
        val line = PtCloudClocks.part1Line(
            "f", 0, 0, 0, 0, 0, 0, 0, 0, null, null, null,
            edgeUploadHeader = " 12 ", edgeHeader = "-5.6",
            timings = mapOf("recv_ms" to 1e300, "ocr_ms" to -1e300, "ocr_total_ms" to 0.0),
        )
        assertTrue(line, line.endsWith("edge_upload=12 edge=-6 recv=${Long.MAX_VALUE} ocr=${Long.MIN_VALUE} ocr_total=0"))
    }

    // ── final ──────────────────────────────────────────────────────────

    @Test fun `final ok counts the items the final list words differently`() {
        val (seen, texts) = items(Triple(0, "Hello", 700L), Triple(1, "Helo", 820L))
        val line = PtCloudClocks.finalLine("d0-1", final(), seen, texts, finalMs = 1215)
        assertEquals(
            "final id=d0-1 status=ok err=- items=2 item_first=700 item_last=820 final=1215 reworded=1 " +
                "translate=988 total=1210",
            line,
        )
    }

    @Test fun `an item whose group the final list does not name counts as reworded`() {
        val (seen, texts) = items(Triple(5, "Extra", 10L), Triple(0, "Hello", 20L))
        val line = PtCloudClocks.finalLine("f", final(), seen, texts, 30)
        assertTrue(line, " reworded=1 " in line)
    }

    @Test fun `final ok with no items prints zero items, no item times, and zero reworded`() {
        val line = PtCloudClocks.finalLine("f", final(), ItemLines(), emptyMap(), finalMs = 1300)
        assertEquals(
            "final id=f status=ok err=- items=0 item_first=- item_last=- final=1300 reworded=0 translate=988 total=1210",
            line,
        )
    }

    @Test fun `final failed prints its error class and no reworded count`() {
        val (seen, texts) = items(Triple(0, "Hello", 700L))
        val line = PtCloudClocks.finalLine(
            "f",
            final(status = "failed", translations = emptyList(), error = WireTranslationError("rate_limited", 429, 12.0)),
            seen,
            texts,
            finalMs = 1400,
        )
        assertEquals(
            "final id=f status=failed err=rate_limited items=1 item_first=700 item_last=700 final=1400 reworded=- " +
                "translate=988 total=1210",
            line,
        )
    }

    @Test fun `deferred and skipped print their status and no reworded count`() {
        for (status in listOf("deferred", "skipped")) {
            val line = PtCloudClocks.finalLine("f", final(status = status, translations = emptyList()), ItemLines(), emptyMap(), 5)
            assertTrue(line.startsWith("final id=f status=$status err=- items=0 "))
            assertTrue(" reworded=- " in line)
        }
    }

    @Test fun `a body that ended before the final part prints ended, the items it saw, and dashes`() {
        val (seen, texts) = items(Triple(0, "Hello", 700L))
        val line = PtCloudClocks.finalLine("f", Part2Result.Ended, seen, texts, finalMs = 900)
        assertEquals(
            "final id=f status=ended err=- items=1 item_first=700 item_last=700 final=900 reworded=- translate=- total=-",
            line,
        )
    }

    @Test fun `a failed read prints unreadable`() {
        val line = PtCloudClocks.finalLine("f", Part2Result.Failed(IllegalArgumentException("x")), ItemLines(), emptyMap(), 50)
        assertEquals(
            "final id=f status=unreadable err=- items=0 item_first=- item_last=- final=50 reworded=- translate=- total=-",
            line,
        )
    }

    @Test fun `hostile final inputs never throw`() {
        // Status ok with no translations list: every item counts as reworded.
        val (seen, texts) = items(Triple(0, "A", Long.MAX_VALUE), Triple(1, "B", Long.MIN_VALUE))
        val noList = PtCloudClocks.finalLine(
            "", final(translations = null, timings = mapOf("translate_ms" to Double.NaN, "total_ms" to Double.NEGATIVE_INFINITY)),
            seen, texts, finalMs = Long.MAX_VALUE,
        )
        assertEquals(
            "final id= status=ok err=- items=2 item_first=${Long.MAX_VALUE} item_last=${Long.MIN_VALUE} " +
                "final=${Long.MAX_VALUE} reworded=2 translate=- total=-",
            noList,
        )
        // Blank status and error class, empty timings, huge finite numbers.
        val blank = PtCloudClocks.finalLine(
            "f",
            final(status = "", translations = emptyList(), error = WireTranslationError(""), timings = mapOf("translate_ms" to 1e300)),
            ItemLines(),
            emptyMap(),
            finalMs = -1,
        )
        assertEquals(
            "final id=f status= err= items=0 item_first=- item_last=- final=-1 reworded=- translate=${Long.MAX_VALUE} total=-",
            blank,
        )
        // A group repeated across items is one text, its last (as the
        // response's items keep it); the count is lines.
        val (repeatedSeen, repeatedTexts) = items(Triple(0, "Helo", 1L), Triple(0, "Hello", 2L))
        val repeated = PtCloudClocks.finalLine("f", final(), repeatedSeen, repeatedTexts, 3)
        assertTrue(repeated, " items=2 " in repeated && " reworded=0 " in repeated)
    }
}
