package com.playtranslate.translationlog

import android.content.Context
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Pins the History store: insert/read ordering, in-place update
 *  (supersession), delete/clear, and the FIFO retention prune. */
@RunWith(RobolectricTestRunner::class)
class TranslationHistoryStoreTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp(): Unit = runBlocking {
        TranslationHistoryStore.resetForTest(ctx)
    }

    private suspend fun insert(text: String, translation: String? = "t", atMs: Long = 0): Long =
        TranslationHistoryStore.insert(
            ctx, atMs, text, translation, "ja", "en",
            TranslationHistoryStore.PROVENANCE_AUTO, "session-1", "key-$text",
            Rect(0, 0, 10, 10), "TestBackend",
        )

    @Test
    fun insertAndReadNewestFirst(): Unit = runBlocking {
        val revisionBefore = TranslationHistoryStore.revision.value
        insert("one", atMs = 100)
        insert("two", atMs = 200)
        val entries = TranslationHistoryStore.recent(ctx, 10)
        assertEquals(listOf("two", "one"), entries.map { it.sourceText })
        assertEquals("TestBackend", entries[0].backendDisplayName)
        assertEquals("key-two", entries[0].normKey)
        // Live-update signal: every mutation bumps the revision.
        assertTrue(TranslationHistoryStore.revision.value >= revisionBefore + 2)
    }

    @Test
    fun updateRewritesInPlace(): Unit = runBlocking {
        val id = insert("partial", atMs = 100)
        TranslationHistoryStore.update(ctx, id, "partial grown.", "full translation", "key2")
        val entries = TranslationHistoryStore.recent(ctx, 10)
        assertEquals(1, entries.size)
        assertEquals("partial grown.", entries[0].sourceText)
        assertEquals("full translation", entries[0].translation)
        // Supersession keeps the FIRST-appearance stamp: the fuller read is
        // the same sentence, and the Anki audio anchor keys off at_ms.
        assertEquals(100L, entries[0].atMs)
    }

    @Test
    fun nullTranslationRoundTrips(): Unit = runBlocking {
        insert("no translation yet", translation = null)
        assertNull(TranslationHistoryStore.recent(ctx, 1)[0].translation)
    }

    @Test
    fun deleteAndClear(): Unit = runBlocking {
        val id = insert("a")
        insert("b")
        TranslationHistoryStore.delete(ctx, id)
        assertEquals(listOf("b"), TranslationHistoryStore.recent(ctx, 10).map { it.sourceText })
        TranslationHistoryStore.clear(ctx)
        assertEquals(0, TranslationHistoryStore.recent(ctx, 10).size)
    }

    @Test
    fun captureAttachIsIdempotentSessionScopedAndAttachOnly(): Unit = runBlocking {
        // A deferred capture's null row under its own session, plus a twin
        // key under ANOTHER session that must never receive this capture's
        // translation.
        TranslationHistoryStore.insert(
            ctx, 1, "line", null, "ja", "en",
            TranslationHistoryStore.PROVENANCE_ONE_SHOT, "cap:one", "k", null, null,
        )
        TranslationHistoryStore.insert(
            ctx, 2, "line", null, "ja", "en",
            TranslationHistoryStore.PROVENANCE_LOOKUP, "other", "k", null, null,
        )

        val first = TranslationHistoryStore.attachCaptureTranslation(
            ctx, "cap:one", "k", "hello", "ja", "en", "DeepL",
        )
        assertEquals(TranslationHistoryStore.CaptureAttachOutcome.ATTACHED, first)

        // Repeat completion (stash-reshow rebind, cross-surface trigger,
        // retry): durable no-op.
        val second = TranslationHistoryStore.attachCaptureTranslation(
            ctx, "cap:one", "k", "hello", "ja", "en", "DeepL",
        )
        assertEquals(TranslationHistoryStore.CaptureAttachOutcome.ALREADY, second)

        val entries = TranslationHistoryStore.recent(ctx, 10)
        assertEquals(2, entries.size)
        val bySession = entries.associateBy { it.sessionId }
        assertEquals("hello", bySession.getValue("cap:one").translation)
        assertNull(bySession.getValue("other").translation)

        // Rows gone entirely (History cleared): ATTACH-ONLY — the reveal
        // must not resurrect pre-clear text, and a capture made while
        // History was off (which has no rows) stays unrecorded.
        TranslationHistoryStore.clear(ctx)
        val third = TranslationHistoryStore.attachCaptureTranslation(
            ctx, "cap:one", "k", "hello", "ja", "en", null,
        )
        assertEquals(TranslationHistoryStore.CaptureAttachOutcome.NONE, third)
        assertEquals(0, TranslationHistoryStore.recent(ctx, 10).size)
    }

    @Test
    fun captureAttachSkipsCrossPairRows(): Unit = runBlocking {
        // Target changed between capture and reveal: the completion's
        // translation is a different pair, and cross-pair translations are
        // display-only — the capture-time row stays translation-less rather
        // than receiving a translation under a label it doesn't claim.
        TranslationHistoryStore.insert(
            ctx, 1, "line", null, "ja", "en",
            TranslationHistoryStore.PROVENANCE_ONE_SHOT, "cap:one", "k", null, null,
        )
        val out = TranslationHistoryStore.attachCaptureTranslation(
            ctx, "cap:one", "k", "bonjour", "ja", "fr", null,
        )
        assertEquals(TranslationHistoryStore.CaptureAttachOutcome.NONE, out)
        assertNull(TranslationHistoryStore.recent(ctx, 1)[0].translation)
    }

    @Test
    fun fifoPruneKeepsNewestCap(): Unit = runBlocking {
        val over = TranslationHistoryStore.MAX_ROWS + 25
        for (i in 1..over) {
            TranslationHistoryStore.insert(
                ctx, i.toLong(), "line $i", null, "ja", "en",
                TranslationHistoryStore.PROVENANCE_AUTO, "s", "k$i", null, null,
            )
        }
        assertEquals(TranslationHistoryStore.MAX_ROWS.toLong(), TranslationHistoryStore.count(ctx))
        val newest = TranslationHistoryStore.recent(ctx, 1)[0]
        assertEquals("line $over", newest.sourceText)
        // The oldest surviving row is exactly over-cap+1.
        val all = TranslationHistoryStore.recent(ctx, TranslationHistoryStore.MAX_ROWS)
        assertEquals("line ${over - TranslationHistoryStore.MAX_ROWS + 1}", all.last().sourceText)
        assertTrue(all.none { it.sourceText == "line 1" })
    }
}
