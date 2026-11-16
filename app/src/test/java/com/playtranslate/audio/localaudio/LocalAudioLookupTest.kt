package com.playtranslate.audio.localaudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [LocalAudioLookup]: which recordings of a written form answer a request,
 * in which order, and under what name and key.
 */
class LocalAudioLookupTest {

    private fun row(source: String, reading: String?, file: String = "$source-${reading ?: "none"}.opus") =
        LocalAudioRow(source = source, reading = reading, speaker = null, display = null, file = file)

    private fun files(rows: List<LocalAudioRow>) = rows.map { it.file }

    // ── Which rows ───────────────────────────────────────────────────────

    @Test fun `a row of the reading asked for is kept and a row of another reading is not`() {
        val rows = listOf(row("nhk16", "にほん", "nihon.opus"), row("nhk16", "にっぽん", "nippon.opus"))
        assertEquals(listOf("nihon.opus"), files(LocalAudioLookup.recordings(rows, "にほん")))
        assertEquals(listOf("nippon.opus"), files(LocalAudioLookup.recordings(rows, "にっぽん")))
    }

    @Test fun `a row with no reading is kept whatever reading is asked for`() {
        val rows = listOf(row("forvo", null, "a.mp3"), row("forvo", "", "b.mp3"), row("forvo", " ", "c.mp3"))
        assertEquals(listOf("a.mp3", "b.mp3", "c.mp3"), files(LocalAudioLookup.recordings(rows, "たべる")))
    }

    @Test fun `katakana and hiragana are one reading, whichever side has which`() {
        assertEquals(1, LocalAudioLookup.recordings(listOf(row("nhk16", "タベル")), "たべる").size)
        assertEquals(1, LocalAudioLookup.recordings(listOf(row("nhk16", "たべる")), "タベル").size)
        assertEquals(1, LocalAudioLookup.recordings(listOf(row("nhk16", "ヴァイオリン")), "ゔぁいおりん").size)
        assertEquals("the long vowel mark is no kana to fold", "らーめん", LocalAudioLookup.foldKana("ラーメン"))
        assertEquals("a reading of another word stays another", 0, LocalAudioLookup.recordings(listOf(row("nhk16", "ノム")), "たべる").size)
    }

    @Test fun `a request with no reading takes every recording of the written form`() {
        val rows = listOf(row("nhk16", "にほん", "nihon.opus"), row("nhk16", "にっぽん", "nippon.opus"))
        assertEquals(2, LocalAudioLookup.recordings(rows, null).size)
        assertEquals(2, LocalAudioLookup.recordings(rows, "").size)
        assertEquals(2, LocalAudioLookup.recordings(rows, "  ").size)
    }

    // ── In which order ───────────────────────────────────────────────────

    @Test fun `sources come in the listed order and an unlisted one after them by id`() {
        val rows = listOf("zeta", "forvo_ext2", "alpha", "forvo", "ozk5", "jpod_alternate", "taas", "jpod", "forvo_ext", "shinmeikai8", "daijisen", "nhk16")
            .map { row(it, "たべる") }
        assertEquals(
            listOf(
                "nhk16", "daijisen", "shinmeikai8", "jpod", "jpod_alternate",
                "taas", "ozk5", "forvo", "forvo_ext", "forvo_ext2", "alpha", "zeta",
            ),
            LocalAudioLookup.recordings(rows, "たべる").map { it.source },
        )
    }

    @Test fun `a recording tied to the reading leads one with no reading`() {
        // Forvo's rows have no reading; NHK's do. Given in the other order.
        val rows = listOf(row("forvo", null, "forvo.mp3"), row("nhk16", "タベル", "nhk.opus"))
        assertEquals(listOf("nhk.opus", "forvo.mp3"), files(LocalAudioLookup.recordings(rows, "たべる")))
    }

    @Test fun `within a source a row with a reading is ahead of one without`() {
        val rows = listOf(row("jpod", null, "bare.mp3"), row("jpod", "たべる", "read.mp3"))
        assertEquals(listOf("read.mp3", "bare.mp3"), files(LocalAudioLookup.recordings(rows, "たべる")))
    }

    @Test fun `one recording listed by two rows is offered once`() {
        val rows = listOf(row("nhk16", "タベル", "same.opus"), row("nhk16", null, "same.opus"), row("daijisen", "たべる", "same.opus"))
        assertEquals(
            "the same file of another source is another recording",
            listOf("nhk16/same.opus", "daijisen/same.opus"),
            LocalAudioLookup.recordings(rows, "たべる").map(LocalAudioLookup::key),
        )
    }

    @Test fun `the sources of a file are listed once each in the offered order`() {
        assertEquals(
            listOf("nhk16", "jpod", "forvo", "custom"),
            LocalAudioLookup.ordered(listOf("forvo", "custom", "nhk16", "jpod", "forvo")),
        )
    }

    // ── Under what name and key ──────────────────────────────────────────

    @Test fun `a listed source is shown under its name and any other under its id`() {
        assertEquals("NHK", LocalAudioLookup.sourceName("nhk16"))
        assertEquals("Shinmeikai", LocalAudioLookup.sourceName("shinmeikai8"))
        assertEquals("JapanesePod101", LocalAudioLookup.sourceName("jpod"))
        assertEquals("Forvo", LocalAudioLookup.sourceName("forvo"))
        assertEquals("my_recordings", LocalAudioLookup.sourceName("my_recordings"))
        assertEquals(
            "every source in the offered order has a name of its own",
            LocalAudioLookup.SOURCE_ORDER.size,
            LocalAudioLookup.SOURCE_ORDER.map(LocalAudioLookup::sourceName).filter { it !in LocalAudioLookup.SOURCE_ORDER }.distinct().size,
        )
    }

    @Test fun `a key is the source and the file, and comes apart again`() {
        val r = row("forvo", null, "strawberrybrown/食べる.mp3")
        val key = LocalAudioLookup.key(r)
        assertEquals("forvo/strawberrybrown/食べる.mp3", key)
        assertEquals("a file's own slashes stay in the file", "forvo" to "strawberrybrown/食べる.mp3", LocalAudioLookup.parseKey(key))
        assertEquals("the key ends in the clip's extension", "mp3", key.substringAfterLast('.'))
    }

    @Test fun `what is not a key comes apart into nothing`() {
        assertNull(LocalAudioLookup.parseKey("no-slash.opus"))
        assertNull(LocalAudioLookup.parseKey("/file.opus"))
        assertNull(LocalAudioLookup.parseKey("source/"))
        assertNull(LocalAudioLookup.parseKey(""))
    }
}
