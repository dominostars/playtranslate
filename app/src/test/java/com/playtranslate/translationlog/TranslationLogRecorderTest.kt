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
            val sourceLang: String,
            val targetLang: String,
            var backend: String? = null,
        )

        val rows = LinkedHashMap<Long, Row>()
        private var nextId = 1L

        override suspend fun insert(
            atMs: Long, sourceText: String, translation: String?, sourceLang: String,
            targetLang: String, provenance: String, sessionId: String, normKey: String,
            rect: Rect?, backendDisplayName: String?,
        ): Long {
            val id = nextId++
            rows[id] = Row(
                sourceText, translation, provenance, sessionId, normKey,
                sourceLang, targetLang, backendDisplayName,
            )
            return id
        }

        override suspend fun update(rowId: Long, sourceText: String, translation: String?, atMs: Long, normKey: String) {
            val row = rows.getValue(rowId)
            row.sourceText = sourceText
            row.translation = translation
            row.normKey = normKey
        }

        override suspend fun attachByKey(
            normKey: String, translation: String,
            sourceLang: String, targetLang: String, backendDisplayName: String?,
        ): Int {
            val target = rows.entries.lastOrNull {
                it.value.normKey == normKey && it.value.translation.isNullOrEmpty() &&
                    it.value.sourceLang == sourceLang && it.value.targetLang == targetLang
            } ?: return 0
            target.value.translation = translation
            return 1
        }

        override suspend fun attachById(rowId: Long, translation: String, backendDisplayName: String?) {
            rows.getValue(rowId).apply {
                this.translation = translation
                if (backendDisplayName != null) backend = backendDisplayName
            }
        }

        override suspend fun attachCaptureTranslation(
            sessionId: String, normKey: String, translation: String,
            sourceLang: String, targetLang: String, backendDisplayName: String?,
        ): TranslationHistoryStore.CaptureAttachOutcome {
            // Mirror of the store's session-scoped, ATTACH-ONLY decision.
            val match = rows.entries.lastOrNull {
                it.value.sessionId == sessionId && it.value.normKey == normKey &&
                    it.value.sourceLang == sourceLang && it.value.targetLang == targetLang
            } ?: return TranslationHistoryStore.CaptureAttachOutcome.NONE
            return if (match.value.translation.isNullOrEmpty()) {
                match.value.translation = translation
                if (backendDisplayName != null) match.value.backend = backendDisplayName
                TranslationHistoryStore.CaptureAttachOutcome.ATTACHED
            } else {
                TranslationHistoryStore.CaptureAttachOutcome.ALREADY
            }
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
    fun targetSwitchStartsFreshState_reReadLineRecordsUnderNewPair() {
        // Codex regression: target-blind session state let a re-read line
        // die as an old-target duplicate and let a typewriter supersession
        // update the old-pair row with a new-pair translation.
        recorder.onShown("こんにちは、世界", "Hello, world", box, "ja", "en")
        recorder.onShown("こんにちは、世界", "Bonjour le monde", box, "ja", "fr")
        // New pair ⇒ new row, not a duplicate of the (ja,en) entry.
        assertEquals(2, sink.rows.size)
        assertEquals("en", sink.rows.getValue(1L).targetLang)
        assertEquals("fr", sink.rows.getValue(2L).targetLang)
        assertTrue(sink.rows.getValue(1L).sessionId != sink.rows.getValue(2L).sessionId)
        // Old-pair context cleared; new pair present.
        assertEquals("", recorder.contextBlockFor("ja", "en"))
        assertTrue(recorder.contextBlockFor("ja", "fr").contains("Bonjour"))
    }

    @Test
    fun typewriterGrowthNeverSupersedesAcrossATargetSwitch() {
        recorder.onShown("こんにちは、世界", "Hello, world", box, "ja", "en")
        // The grown read arrives under a NEW target: must append its own
        // row — the (ja,en) row keeps its original text and translation.
        recorder.onShown("こんにちは、世界のみなさん。", "Bonjour tout le monde.", box, "ja", "fr")
        assertEquals(2, sink.rows.size)
        assertEquals("こんにちは、世界", sink.rows.getValue(1L).sourceText)
        assertEquals("Hello, world", sink.rows.getValue(1L).translation)
        assertEquals("fr", sink.rows.getValue(2L).targetLang)
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
    fun contextOnlyUseDoesNotBlockPersistenceAfterHistoryEnables() {
        // Codex regression: with history off / context on, the gate marks
        // lines seen while nothing persists; flipping history on must let
        // re-sightings record.
        Prefs(ctx).translationHistoryEnabled = false
        recorder.onShown("こんにちは、世界のみなさん。", "Hello.", box, "ja", "en")
        assertEquals(0, sink.rows.size)
        assertTrue(recorder.contextBlockFor("ja", "en").isNotEmpty())

        Prefs(ctx).translationHistoryEnabled = true
        recorder.onHistoryEnabled()
        recorder.onShown("こんにちは、世界のみなさん。", "Hello.", box, "ja", "en")
        assertEquals(1, sink.rows.size)
        // Ring continuity survived the flip (independence both ways).
        assertTrue(recorder.contextBlockFor("ja", "en").isNotEmpty())
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
    fun deliberateTranslationAttachesToItsSourceOnlyEntry() {
        // Drag lookup: source recorded at release, translation arrives later
        // from the dual-screen flow — same row, completed in place.
        recorder.onShownDeliberate(
            "こんにちは、世界のみなさん。", null, null, "ja", "en",
            TranslationHistoryStore.PROVENANCE_LOOKUP,
        )
        assertEquals(1, sink.rows.size)
        recorder.onDeliberateTranslation(
            "こんにちは、世界のみなさん。", "Hello, everyone.", "ja", "en",
            TranslationHistoryStore.PROVENANCE_LOOKUP, "TestBackend",
        )
        assertEquals(1, sink.rows.size)
        assertEquals("Hello, everyone.", sink.rows.getValue(1L).translation)
        // Attach-style update: the backend that produced the translation
        // sticks to the row (the supersession update would drop it).
        assertEquals("TestBackend", sink.rows.getValue(1L).backend)
        // The completed pair enters the context ring.
        assertTrue(recorder.contextBlockFor("ja", "en").contains("Hello, everyone."))
    }

    @Test
    fun historyEntryTranslationAttachesToTheExactRow_neverItsTwin() {
        // Codex regression: twin translation-less rows share a normKey
        // across sessions; tapping the OLDER row must fill row 1, not the
        // newest key match.
        recorder.onShownDeliberate(
            "こんにちは、世界のみなさん。", null, null, "ja", "en",
            TranslationHistoryStore.PROVENANCE_LOOKUP,
        )
        recorder.onHistoryCleared() // new session: gate forgets, rows persist
        recorder.onShownDeliberate(
            "こんにちは、世界のみなさん。", null, null, "ja", "en",
            TranslationHistoryStore.PROVENANCE_LOOKUP,
        )
        assertEquals(2, sink.rows.size)
        recorder.onHistoryEntryTranslated(
            1L, "こんにちは、世界のみなさん。", "Hello, everyone.", "ja", "en",
        )
        assertEquals("Hello, everyone.", sink.rows.getValue(1L).translation)
        assertEquals(null, sink.rows.getValue(2L).translation)
        // The completed pair still feeds the context ring.
        assertTrue(recorder.contextBlockFor("ja", "en").contains("Hello, everyone."))
    }

    @Test
    fun crossPairDelayedTranslationRecordsFresh_neverCorruptsTheOldPairRow() {
        // Codex regression: row recorded translation-less under (ja,en);
        // the target language switches before the delayed translation
        // lands. The (ja,fr) translation must not touch the (ja,en) row.
        recorder.onShownDeliberate(
            "こんにちは、世界のみなさん。", null, null, "ja", "en",
            TranslationHistoryStore.PROVENANCE_LOOKUP,
        )
        recorder.onDeliberateTranslation(
            "こんにちは、世界のみなさん。", "Bonjour tout le monde.", "ja", "fr",
            TranslationHistoryStore.PROVENANCE_LOOKUP,
        )
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(null, sink.rows.getValue(1L).translation) // old pair untouched
        assertEquals(2, sink.rows.size)                         // fresh row, new pair
        assertEquals("fr", sink.rows.getValue(2L).targetLang)
        assertEquals("Bonjour tout le monde.", sink.rows.getValue(2L).translation)
    }

    @Test
    fun deliberateTranslationAttachesByKeyWhenRowIsNoLongerTracked() {
        // A History-tapped entry can be far older than the recorder's
        // tracked window: the attach must reach the store by key.
        recorder.onShownDeliberate(
            "こんにちは、世界のみなさん。", null, null, "ja", "en",
            TranslationHistoryStore.PROVENANCE_LOOKUP,
        )
        recorder.onHistoryCleared() // drops the tracked-row map (not the sink rows)
        recorder.onDeliberateTranslation(
            "こんにちは、世界のみなさん。", "Hello, everyone.", "ja", "en",
            TranslationHistoryStore.PROVENANCE_LOOKUP,
        )
        assertEquals(1, sink.rows.size)
        assertEquals("Hello, everyone.", sink.rows.getValue(1L).translation)
    }

    @Test
    fun deliberateTranslationWithoutTrackedRowFallsBackToAppend() {
        recorder.onDeliberateTranslation(
            "こんにちは、世界のみなさん。", "Hello.", "ja", "en",
            TranslationHistoryStore.PROVENANCE_LOOKUP,
        )
        // The no-row fallback hops to Main for the fresh record.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, sink.rows.size)
        assertEquals("Hello.", sink.rows.getValue(1L).translation)
    }

    @Test
    fun contextBlockRespectsLanguagePair() {
        recorder.onShown("こんにちは、世界のみなさん。", "Hello.", box, "ja", "en")
        assertEquals("", recorder.contextBlockFor("ja", "fr"))
    }

    // ── Deferred captures: null rows at capture, onCaptureTranslated later ──

    @Test
    fun deferredCaptureRowRecordsNullThenAttachFillsExactlyThatRow() {
        val token = recorder.beginCaptureSession()
        recorder.onCaptureShown(
            token, "こんにちは、世界のみなさん。", null, box, "ja", "en",
            TranslationHistoryStore.PROVENANCE_ONE_SHOT,
        )
        assertEquals(1, sink.rows.size)
        assertEquals(null, sink.rows.getValue(1L).translation)
        // Deferral: nothing reaches the LLM context ring for a null row.
        assertEquals("", recorder.contextBlockFor("ja", "en"))

        recorder.onCaptureTranslated(
            token.sessionId, "こんにちは、世界のみなさん。", "Hello, everyone.", "ja", "en",
            contextEligible = true, "DeepL",
        )
        // Attached in place — no fresh row — and the ring gets the pair now.
        assertEquals(1, sink.rows.size)
        assertEquals("Hello, everyone.", sink.rows.getValue(1L).translation)
        assertTrue(recorder.contextBlockFor("ja", "en").contains("Hello, everyone."))
    }

    @Test
    fun captureAttachWithRowsGoneWritesNothing() {
        // History cleared (or FIFO-pruned) between capture and reveal:
        // ATTACH-ONLY — the reveal must not resurrect pre-clear text.
        val token = recorder.beginCaptureSession()
        recorder.onCaptureTranslated(
            token.sessionId, "こんにちは、世界のみなさん。", "Hello.", "ja", "en",
            contextEligible = false,
        )
        assertEquals(0, sink.rows.size)
    }

    @Test
    fun optedOutCaptureStaysUnrecordedAfterPrefsFlipOn() {
        // Codex round-3 regression: History + context OFF at capture time —
        // no rows were written, so the pending carries sessionId = null and
        // contextEligible = false. The user enables BOTH before revealing;
        // the completion must still write nothing anywhere.
        recorder.onCaptureTranslated(
            null, "こんにちは、世界のみなさん。", "Hello.", "ja", "en",
            contextEligible = false,
        )
        assertEquals(0, sink.rows.size)
        assertEquals("", recorder.contextBlockFor("ja", "en"))
    }

    @Test
    fun deliberateAttachHonorsLookupTimeContextOptOut() {
        // Drag lookup made with context OFF (contextEligible=false in the
        // pending), context enabled before the reveal: the row still gets
        // its translation, but the ring must not receive an opted-out lookup.
        recorder.onShownDeliberate(
            "こんにちは、世界のみなさん。", null, null, "ja", "en",
            TranslationHistoryStore.PROVENANCE_LOOKUP,
        )
        recorder.onDeliberateTranslation(
            "こんにちは、世界のみなさん。", "Hello, everyone.", "ja", "en",
            TranslationHistoryStore.PROVENANCE_LOOKUP,
            contextEligible = false,
        )
        assertEquals("Hello, everyone.", sink.rows.getValue(1L).translation)
        assertEquals("", recorder.contextBlockFor("ja", "en"))
    }

    @Test
    fun deliberateAttachHonorsLookupTimeHistoryOptOut() {
        // Lookup made with History OFF (no row recorded, historyEligible
        // false) but context on at both ends: the reveal must not record a
        // fresh row even with History now enabled — the ring alone is fed.
        recorder.onDeliberateTranslation(
            "こんにちは、世界のみなさん。", "Hello, everyone.", "ja", "en",
            TranslationHistoryStore.PROVENANCE_LOOKUP,
            historyEligible = false,
            contextEligible = true,
        )
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(0, sink.rows.size)
        assertTrue(recorder.contextBlockFor("ja", "en").contains("Hello, everyone."))
    }

    @Test
    fun historyEntryAttachHonorsLookupTimeContextOptOut() {
        // History-row tap with context OFF at tap time, enabled before the
        // reveal: the exact-row attach proceeds (the row's recording consent
        // predates the tap) but the ring stays clean.
        recorder.onShownDeliberate(
            "こんにちは、世界のみなさん。", null, null, "ja", "en",
            TranslationHistoryStore.PROVENANCE_LOOKUP,
        )
        recorder.onHistoryEntryTranslated(
            1L, "こんにちは、世界のみなさん。", "Hello, everyone.", "ja", "en",
            contextEligible = false,
        )
        assertEquals("Hello, everyone.", sink.rows.getValue(1L).translation)
        assertEquals("", recorder.contextBlockFor("ja", "en"))
    }

    @Test
    fun contextEligibleHistoryOffFeedsOnlyTheRing() {
        // History off at capture (no rows, sessionId null) but context opted
        // in at both ends: the reveal feeds the ring, touches no rows.
        recorder.onCaptureTranslated(
            null, "こんにちは、世界のみなさん。", "Hello.", "ja", "en",
            contextEligible = true,
        )
        assertEquals(0, sink.rows.size)
        assertTrue(recorder.contextBlockFor("ja", "en").contains("Hello."))
    }

    @Test
    fun captureAttachDoesNotStealATrackedDeliberateRow() {
        // A drag lookup recorded the same text translation-less and IS
        // tracked in rowIds. The capture's late attach must fill the CAPTURE
        // row (attachByKey picks the newest translation-less match), never
        // attachById the deliberate row — that one belongs to the drag
        // flow's own onDeliberateTranslation.
        recorder.onShownDeliberate(
            "こんにちは、世界のみなさん。", null, null, "ja", "en",
            TranslationHistoryStore.PROVENANCE_LOOKUP,
        )
        val token = recorder.beginCaptureSession()
        recorder.onCaptureShown(
            token, "こんにちは、世界のみなさん。", null, box, "ja", "en",
            TranslationHistoryStore.PROVENANCE_ONE_SHOT,
        )
        assertEquals(2, sink.rows.size)

        recorder.onCaptureTranslated(
            token.sessionId, "こんにちは、世界のみなさん。", "Hello, everyone.", "ja", "en",
            contextEligible = false,
        )
        assertEquals(2, sink.rows.size)
        // The capture row (session-scoped match) got the text …
        assertEquals("Hello, everyone.", sink.rows.getValue(2L).translation)
        // … and the tracked drag row stays null for its own flow to fill.
        assertEquals(null, sink.rows.getValue(1L).translation)
    }

    @Test
    fun repeatCaptureAttachIsIdempotent() {
        // A second completion for the same capture — stash-reshow rebind,
        // cross-surface trigger, retry — must be a durable no-op once the
        // first attach filled the row: no fresh-insert fallback, no
        // duplicate ring push. Per-surface dedupe cannot see across UI
        // instances; the store boundary owns this.
        val token = recorder.beginCaptureSession()
        recorder.onCaptureShown(
            token, "こんにちは、世界のみなさん。", null, box, "ja", "en",
            TranslationHistoryStore.PROVENANCE_ONE_SHOT,
        )
        recorder.onCaptureTranslated(
            token.sessionId, "こんにちは、世界のみなさん。", "Hello, everyone.", "ja", "en",
            contextEligible = true,
        )
        recorder.onCaptureTranslated(
            token.sessionId, "こんにちは、世界のみなさん。", "Hello, everyone.", "ja", "en",
            contextEligible = true,
        )
        assertEquals(1, sink.rows.size)
        assertEquals("Hello, everyone.", sink.rows.getValue(1L).translation)
        // The ring got the pair exactly once (block contains one arrow line).
        val block = recorder.contextBlockFor("ja", "en")
        assertEquals(1, block.split("Hello, everyone.").size - 1)
    }
}
