package com.playtranslate.translationlog

import android.content.Context
import android.graphics.Rect
import com.playtranslate.language.SourceLangId
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
            ctx, atMs, text, translation, "ja", SourceLangId.JA, "en",
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
            ctx, 1, "line", null, "ja", SourceLangId.JA, "en",
            TranslationHistoryStore.PROVENANCE_ONE_SHOT, "cap:one", "k", null, null,
        )
        TranslationHistoryStore.insert(
            ctx, 2, "line", null, "ja", SourceLangId.JA, "en",
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
            ctx, 1, "line", null, "ja", SourceLangId.JA, "en",
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
                ctx, i.toLong(), "line $i", null, "ja", SourceLangId.JA, "en",
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

    /** Schema v1 → v2: a store written before the exact-language column opens,
     *  keeps its rows (with no exact language), and takes new rows with one. The
     *  v1 file is built by hand here, as the shipped v1 schema wrote it. */
    @Test
    fun v1StoreMigratesInPlaceAndKeepsItsRows(): Unit = runBlocking {
        val file = java.io.File(
            java.io.File(ctx.applicationContext.noBackupFilesDir, "translationlog"), "history.sqlite",
        )
        file.parentFile?.mkdirs()
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE entries (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, at_ms INTEGER NOT NULL, " +
                    "source_text TEXT NOT NULL, translation TEXT, source_lang TEXT NOT NULL, " +
                    "target_lang TEXT NOT NULL, provenance TEXT NOT NULL, session_id TEXT NOT NULL, " +
                    "norm_key TEXT NOT NULL, rect_l INTEGER, rect_t INTEGER, rect_r INTEGER, " +
                    "rect_b INTEGER, backend TEXT)"
            )
            db.execSQL(
                "INSERT INTO entries (at_ms, source_text, translation, source_lang, target_lang, " +
                    "provenance, session_id, norm_key) VALUES (1, '軟體', NULL, 'zh', 'en', 'auto', 's', 'k')"
            )
            db.execSQL("PRAGMA user_version = 1")
        }

        val old = TranslationHistoryStore.recent(ctx, 10).single()
        assertEquals("軟體", old.sourceText)
        assertEquals("zh", old.sourceLang)
        assertNull("a v1 row carries no exact language", old.sourceLangId)
        // With no exact language stored, the pair alone matches a late attach,
        // whichever Chinese variant produced the translation.
        assertEquals(
            1,
            TranslationHistoryStore.attachTranslationByKey(
                ctx, "k", "software", "zh", SourceLangId.ZH_HANT, "en", null,
            ),
        )
        assertEquals("software", TranslationHistoryStore.recent(ctx, 10).single().translation)

        TranslationHistoryStore.insert(
            ctx, 2, "新しい", "new", "zh", SourceLangId.ZH_HANT, "en",
            TranslationHistoryStore.PROVENANCE_AUTO, "s", "k2", null, null,
        )
        val rows = TranslationHistoryStore.recent(ctx, 10)
        assertEquals(listOf(SourceLangId.ZH_HANT, null), rows.map { it.sourceLangId })
        assertEquals(listOf("zh", "zh"), rows.map { it.sourceLang })
    }

    /** An interrupted v1 → v2 migration (column added, version bump lost to a
     *  process death) must open cleanly: the column add is skipped when the
     *  column is present, and the version is set. An unconditional ALTER
     *  failed on the duplicate column at every open and stranded the store
     *  (Codex adversarial, 2026-10-06). */
    @Test
    fun interruptedMigrationOpensAndFinishes(): Unit = runBlocking {
        val file = java.io.File(
            java.io.File(ctx.applicationContext.noBackupFilesDir, "translationlog"), "history.sqlite",
        )
        file.parentFile?.mkdirs()
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE entries (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, at_ms INTEGER NOT NULL, " +
                    "source_text TEXT NOT NULL, translation TEXT, source_lang TEXT NOT NULL, " +
                    "target_lang TEXT NOT NULL, provenance TEXT NOT NULL, session_id TEXT NOT NULL, " +
                    "norm_key TEXT NOT NULL, rect_l INTEGER, rect_t INTEGER, rect_r INTEGER, " +
                    "rect_b INTEGER, backend TEXT)"
            )
            db.execSQL(
                "INSERT INTO entries (at_ms, source_text, translation, source_lang, target_lang, " +
                    "provenance, session_id, norm_key) VALUES (1, '軟體', 'software', 'zh', 'en', 'auto', 's', 'k')"
            )
            // The earlier attempt got this far and died before the version bump.
            db.execSQL("ALTER TABLE entries ADD COLUMN source_lang_id TEXT")
            db.execSQL("PRAGMA user_version = 1")
        }

        val old = TranslationHistoryStore.recent(ctx, 10).single()
        assertEquals("軟體", old.sourceText)
        assertNull(old.sourceLangId)
        insert("fresh")
        assertEquals(2, TranslationHistoryStore.recent(ctx, 10).size)
        android.database.sqlite.SQLiteDatabase.openDatabase(
            file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            db.rawQuery("PRAGMA user_version", null).use { c ->
                c.moveToFirst()
                assertEquals(2, c.getInt(0))
            }
        }
    }

    /** A row stored with its exact language takes a late translation only
     *  from that language: ZH and ZH_HANT share the pair code, so the code
     *  alone let a Traditional translation fill a Simplified row (Codex
     *  adversarial, 2026-10-06). */
    @Test
    fun attachByKeyRequiresTheExactLanguageWhenTheRowHasOne(): Unit = runBlocking {
        TranslationHistoryStore.insert(
            ctx, 1, "這是一個句子", null, "zh", SourceLangId.ZH, "en",
            TranslationHistoryStore.PROVENANCE_LOOKUP, "s", "k", null, null,
        )
        assertEquals(
            0,
            TranslationHistoryStore.attachTranslationByKey(ctx, "k", "Hello.", "zh", SourceLangId.ZH_HANT, "en", null),
        )
        assertNull(TranslationHistoryStore.recent(ctx, 10).single().translation)
        assertEquals(
            1,
            TranslationHistoryStore.attachTranslationByKey(ctx, "k", "Hello.", "zh", SourceLangId.ZH, "en", null),
        )
        assertEquals("Hello.", TranslationHistoryStore.recent(ctx, 10).single().translation)
    }
}
