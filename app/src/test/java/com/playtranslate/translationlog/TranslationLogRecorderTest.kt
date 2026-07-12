package com.playtranslate.translationlog

import android.content.Context
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the recorder's decision→sink mapping and the two-feature
 * independence contract: Append inserts (history) / pushes (ring);
 * Replace updates the SAME row (awaiting the async insert's id) and
 * replaces in the ring; Suppress touches nothing; both-prefs-off is a
 * no-op; a language switch recreates the gate and clears the ring.
 * Uses a fake sink + Unconfined scope so async paths run synchronously.
 */
@RunWith(RobolectricTestRunner::class)
class TranslationLogRecorderTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val box = Rect(100, 800, 1800, 1000)

    private class FakeSink : TranslationLogRecorder.HistorySink {
        data class Row(
            var sourceText: String,
            var translation: String?,
            val provenance: String,
            val sessionId: String,
            var normKey: String,
        )

        val rows = LinkedHashMap<Long, Row>()
        private var nextId = 1L

        override suspend fun insert(
            atMs: Long, sourceText: String, translation: String?, sourceLang: String,
            targetLang: String, provenance: String, sessionId: String, normKey: String,
            rect: Rect?, backendDisplayName: String?,
        ): Long {
            val id = nextId++
            rows[id] = Row(sourceText, translation, provenance, sessionId, normKey)
            return id
        }

        override suspend fun update(rowId: Long, sourceText: String, translation: String?, atMs: Long, normKey: String) {
            val row = rows.getValue(rowId)
            row.sourceText = sourceText
            row.translation = translation
            row.normKey = normKey
        }
    }

    private lateinit var sink: FakeSink
    private lateinit var recorder: TranslationLogRecorder

    @Before
    fun setUp() {
        Prefs(ctx).translationHistoryEnabled = true
        Prefs(ctx).llmContextEnabled = true
        sink = FakeSink()
        recorder = TranslationLogRecorder(
            ctx, sink, CoroutineScope(Dispatchers.Unconfined),
        )
    }

    @Test
    fun appendInsertsRowAndPushesContextPair() {
        recorder.onShown("こんにちは、世界のみなさん。", "Hello, everyone.", box, "ja", "en")
        assertEquals(1, sink.rows.size)
        val row = sink.rows.getValue(1L)
        assertEquals("こんにちは、世界のみなさん。", row.sourceText)
        assertEquals(TranslationHistoryStore.PROVENANCE_AUTO, row.provenance)
        val block = recorder.contextBlockFor("ja", "en")
        assertTrue(block.contains("こんにちは、世界のみなさん。 → Hello, everyone."))
        assertTrue(block.endsWith("\n\n"))
    }

    @Test
    fun replaceUpdatesTheSameRowAndTheRing() {
        recorder.onShown("こんにちは、世界", "Hello, world", box, "ja", "en")
        recorder.onShown("こんにちは、世界のみなさん。", "Hello, everyone in the world.", box, "ja", "en")
        // Typewriter growth: still ONE row, holding the fullest read.
        assertEquals(1, sink.rows.size)
        assertEquals("こんにちは、世界のみなさん。", sink.rows.getValue(1L).sourceText)
        val block = recorder.contextBlockFor("ja", "en")
        assertTrue(block.contains("Hello, everyone in the world."))
        assertTrue(!block.contains("Hello, world\n"))
    }

    @Test
    fun suppressedCommitsTouchNothing() {
        recorder.onShown("こんにちは、世界のみなさん。", "Hello.", box, "ja", "en")
        recorder.onShown("こんにちは、世界のみなさん。", "Hello.", box, "ja", "en") // dup
        recorder.onShown("12:41", "12:41", box, "ja", "en") // noise
        assertEquals(1, sink.rows.size)
    }

    @Test
    fun bothPrefsOffIsANoOp() {
        Prefs(ctx).translationHistoryEnabled = false
        Prefs(ctx).llmContextEnabled = false
        recorder.onShown("こんにちは、世界のみなさん。", "Hello.", box, "ja", "en")
        assertEquals(0, sink.rows.size)
        assertEquals("", recorder.contextBlockFor("ja", "en"))
    }

    @Test
    fun historyOffContextOnStillFeedsTheRing() {
        Prefs(ctx).translationHistoryEnabled = false
        recorder.onShown("こんにちは、世界のみなさん。", "Hello.", box, "ja", "en")
        assertEquals(0, sink.rows.size)
        assertTrue(recorder.contextBlockFor("ja", "en").isNotEmpty())
    }

    @Test
    fun languageSwitchClearsRingAndStartsNewSession() {
        recorder.onShown("こんにちは、世界のみなさん。", "Hello.", box, "ja", "en")
        val firstSession = sink.rows.getValue(1L).sessionId
        recorder.onShown("Bonjour tout le monde, mes amis.", "Hello everyone.", box, "fr", "en")
        // Old-language pair must not leak into the new language's context.
        assertEquals("", recorder.contextBlockFor("ja", "en"))
        assertTrue(recorder.contextBlockFor("fr", "en").isNotEmpty())
        assertTrue(sink.rows.getValue(2L).sessionId != firstSession)
    }

    @Test
    fun liveStopClearsContextButHistoryPersists() {
        recorder.onShown("こんにちは、世界のみなさん。", "Hello.", box, "ja", "en")
        recorder.onLiveStopped()
        assertEquals("", recorder.contextBlockFor("ja", "en"))
        assertEquals(1, sink.rows.size)
    }

    @Test
    fun deliberateProvenanceIsRecordedUngated() {
        recorder.onShownDeliberate(
            "アリス", "Alice", null, "ja", "en",
            TranslationHistoryStore.PROVENANCE_ONE_SHOT,
        )
        assertEquals(
            TranslationHistoryStore.PROVENANCE_ONE_SHOT,
            sink.rows.getValue(1L).provenance,
        )
    }

    @Test
    fun clearHistoryResetsDedupe_sameLineRecordsAgain() {
        recorder.onShown("こんにちは、世界のみなさん。", "Hello.", box, "ja", "en")
        assertEquals(1, sink.rows.size)
        recorder.onHistoryCleared()
        recorder.onShown("こんにちは、世界のみなさん。", "Hello.", box, "ja", "en")
        assertEquals(2, sink.rows.size)
    }

    @Test
    fun deleteEntryResetsItsDedupe_sameLineRecordsAgain() {
        recorder.onShown("こんにちは、世界のみなさん。", "Hello.", box, "ja", "en")
        val key = sink.rows.getValue(1L).normKey
        recorder.onEntryDeleted(key)
        recorder.onShown("こんにちは、世界のみなさん。", "Hello.", box, "ja", "en")
        assertEquals(2, sink.rows.size)
        // An untouched line still dedupes.
        recorder.onShown("こんにちは、世界のみなさん。", "Hello.", box, "ja", "en")
        assertEquals(2, sink.rows.size)
    }

    @Test
    fun contextBlockRespectsLanguagePair() {
        recorder.onShown("こんにちは、世界のみなさん。", "Hello.", box, "ja", "en")
        assertEquals("", recorder.contextBlockFor("ja", "fr"))
    }
}
