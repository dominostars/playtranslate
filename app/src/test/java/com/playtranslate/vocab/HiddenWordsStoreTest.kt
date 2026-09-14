package com.playtranslate.vocab

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.language.SourceLangId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the hidden-words store: per-language sets, no-op toggles, the disk
 * round-trip across a simulated process restart, restore-at-install
 * tolerance (a pre-existing file, including one stamped by a newer build,
 * is read as it is and never recreated), the metadata every row carries for
 * the planned list screen and deck import, and the deliberate-Anki un-hide.
 */
@RunWith(RobolectricTestRunner::class)
class HiddenWordsStoreTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp(): Unit = runBlocking { HiddenWordsStore.resetForTest(ctx) }

    @After
    fun tearDown(): Unit = runBlocking { HiddenWordsStore.resetForTest(ctx) }

    private suspend fun hide(word: String, lang: SourceLangId = SourceLangId.JA, reading: String? = null): Boolean =
        HiddenWordsStore.setHidden(ctx, lang, word, reading, hidden = true)

    private suspend fun show(word: String, lang: SourceLangId = SourceLangId.JA): Boolean =
        HiddenWordsStore.setHidden(ctx, lang, word, null, hidden = false)

    // ─── S1 ───────────────────────────────────────────────────────────

    @Test
    fun hideShowsInSnapshotAndBumpsRevisionOnce(): Unit = runBlocking {
        HiddenWordsStore.loaded(ctx, SourceLangId.JA) // the load's own bump, out of the way
        val before = HiddenWordsStore.revision.value
        hide("食べる", reading = "たべる")
        assertTrue("食べる" in HiddenWordsStore.snapshot(ctx, SourceLangId.JA))
        assertEquals(before + 1, HiddenWordsStore.revision.value)
        // Repeating the same state is a no-op: no write, no bump.
        hide("食べる")
        assertEquals(before + 1, HiddenWordsStore.revision.value)
    }

    // ─── S9 ───────────────────────────────────────────────────────────

    @Test
    fun failedWriteLeavesCacheAndRevisionUntouched_thenRecovers(): Unit = runBlocking {
        hide("kept")
        // Make the store file unopenable: a directory at its path. The load
        // is already cached, so the next toggle goes straight to the write.
        val file = HiddenWordsStore.fileFor(ctx)
        assertTrue(file.delete())
        assertTrue(file.mkdirs())
        val before = HiddenWordsStore.revision.value
        assertFalse(hide("lost"))
        assertFalse(show("kept"))
        // Nothing changed for the UI: no bump, the sets are what disk holds.
        assertEquals(before, HiddenWordsStore.revision.value)
        assertEquals(setOf("kept"), HiddenWordsStore.snapshot(ctx, SourceLangId.JA))
        // Disk comes back: the next toggle lands and is visible after a restart.
        assertTrue(file.delete())
        assertTrue(hide("lost"))
        assertEquals(before + 1, HiddenWordsStore.revision.value)
        HiddenWordsStore.dropCacheForTest()
        // The directory replaced the file, so "kept" is gone from disk; the
        // cache never pretended otherwise beyond what that session had loaded.
        assertEquals(setOf("lost"), HiddenWordsStore.loaded(ctx, SourceLangId.JA))
    }

    // ─── S10 ──────────────────────────────────────────────────────────

    @Test
    fun failedLoadLeavesLanguageUnloaded_writesRefused_untilRetry(): Unit = runBlocking {
        // Unopenable from the start: a directory at the file path before any load.
        val file = HiddenWordsStore.fileFor(ctx)
        file.parentFile?.mkdirs()
        assertTrue(file.mkdirs())
        val before = HiddenWordsStore.revision.value
        assertTrue(HiddenWordsStore.loaded(ctx, SourceLangId.JA).isEmpty())
        assertFalse("a failed load must not count as loaded", HiddenWordsStore.isLoaded(SourceLangId.JA))
        assertEquals(before, HiddenWordsStore.revision.value)
        // Writes against an unloaded language are refused, not guessed.
        assertFalse(hide("x"))
        assertFalse(show("x"))
        assertEquals(before, HiddenWordsStore.revision.value)
        assertTrue(HiddenWordsStore.snapshot(ctx, SourceLangId.JA).isEmpty())
        // Disk comes back: the next explicit call retries the read and lands.
        assertTrue(file.delete())
        assertTrue(hide("x"))
        assertTrue(HiddenWordsStore.isLoaded(SourceLangId.JA))
        assertEquals(setOf("x"), HiddenWordsStore.snapshot(ctx, SourceLangId.JA))
    }

    // ─── S11 ──────────────────────────────────────────────────────────

    @Test
    fun insertThatWritesNoRowIsAFailure(): Unit = runBlocking {
        hide("seed")
        // The platform's non-throwing failure signal: an insert that changed
        // no row returns -1. A BEFORE INSERT trigger raising IGNORE is the
        // one way to produce it without an exception.
        val file = HiddenWordsStore.fileFor(ctx)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL(
                "CREATE TRIGGER swallow BEFORE INSERT ON hidden_words " +
                    "BEGIN SELECT RAISE(IGNORE); END"
            )
        }
        val before = HiddenWordsStore.revision.value
        assertFalse(hide("swallowed"))
        assertEquals(before, HiddenWordsStore.revision.value)
        assertEquals(setOf("seed"), HiddenWordsStore.snapshot(ctx, SourceLangId.JA))
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TRIGGER swallow")
        }
        assertTrue(hide("swallowed"))
        assertEquals(setOf("seed", "swallowed"), HiddenWordsStore.snapshot(ctx, SourceLangId.JA))
    }

    // ─── S12 ──────────────────────────────────────────────────────────

    @Test
    fun unhideOfRowAlreadyGoneOnDiskSucceeds(): Unit = runBlocking {
        hide("x")
        // The row vanished behind the cache's back (a restored file, an
        // external edit): the goal state already holds, so the un-hide is a
        // success and the cache follows.
        SQLiteDatabase.openDatabase(
            HiddenWordsStore.fileFor(ctx).path, null, SQLiteDatabase.OPEN_READWRITE,
        ).use { db -> db.execSQL("DELETE FROM hidden_words WHERE word = 'x'") }
        val before = HiddenWordsStore.revision.value
        assertTrue(show("x"))
        assertEquals(before + 1, HiddenWordsStore.revision.value)
        assertTrue(HiddenWordsStore.snapshot(ctx, SourceLangId.JA).isEmpty())
    }

    // ─── S8 ───────────────────────────────────────────────────────────

    @Test
    fun loadCompletionBumpsRevisionOnce_evenWhenEmpty(): Unit = runBlocking {
        val before = HiddenWordsStore.revision.value
        assertFalse(HiddenWordsStore.isLoaded(SourceLangId.KO))
        // Nothing hidden for ko: the load still announces itself, because a
        // host that drew rows before it applies its ordering on this bump.
        assertTrue(HiddenWordsStore.loaded(ctx, SourceLangId.KO).isEmpty())
        assertTrue(HiddenWordsStore.isLoaded(SourceLangId.KO))
        assertEquals(before + 1, HiddenWordsStore.revision.value)
        // Already loaded: no second bump.
        HiddenWordsStore.loaded(ctx, SourceLangId.KO)
        assertEquals(before + 1, HiddenWordsStore.revision.value)
    }

    // ─── S2 ───────────────────────────────────────────────────────────

    @Test
    fun languagesAreIsolated(): Unit = runBlocking {
        hide("手紙", lang = SourceLangId.JA)
        assertTrue("手紙" in HiddenWordsStore.loaded(ctx, SourceLangId.JA))
        assertFalse("手紙" in HiddenWordsStore.loaded(ctx, SourceLangId.ZH))
        // The code is stored verbatim: zh-Hant is its own set, never folded
        // onto zh.
        hide("手紙", lang = SourceLangId.ZH_HANT)
        assertFalse("手紙" in HiddenWordsStore.loaded(ctx, SourceLangId.ZH))
        assertTrue("手紙" in HiddenWordsStore.loaded(ctx, SourceLangId.ZH_HANT))
    }

    // ─── S3 ───────────────────────────────────────────────────────────

    @Test
    fun unhideRemovesAndUnknownUnhideIsNoOp(): Unit = runBlocking {
        hide("a")
        hide("b")
        val before = HiddenWordsStore.revision.value
        show("a")
        assertEquals(setOf("b"), HiddenWordsStore.snapshot(ctx, SourceLangId.JA))
        assertEquals(before + 1, HiddenWordsStore.revision.value)
        show("never-hidden")
        assertEquals(before + 1, HiddenWordsStore.revision.value)
        assertEquals(setOf("b"), HiddenWordsStore.snapshot(ctx, SourceLangId.JA))
    }

    // ─── S4 ───────────────────────────────────────────────────────────

    @Test
    fun survivesProcessRestart(): Unit = runBlocking {
        hide("走る")
        hide("見る")
        show("見る")
        HiddenWordsStore.dropCacheForTest()
        // Before the load completes the synchronous view is empty (and
        // triggers the load); the awaited view is the disk truth.
        assertEquals(setOf("走る"), HiddenWordsStore.loaded(ctx, SourceLangId.JA))
        assertEquals(setOf("走る"), HiddenWordsStore.snapshot(ctx, SourceLangId.JA))
    }

    // ─── S5 ───────────────────────────────────────────────────────────

    @Test
    fun preExistingFileIsReadNotRecreated(): Unit = runBlocking {
        hide("one")
        hide("two")
        HiddenWordsStore.dropCacheForTest()
        // Restore-at-install shape: the file exists before this "process"
        // touches the store. Stamp it as a NEWER build would have.
        val file = HiddenWordsStore.fileFor(ctx)
        assertTrue(file.exists())
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("PRAGMA user_version = 99")
        }
        assertEquals(setOf("one", "two"), HiddenWordsStore.loaded(ctx, SourceLangId.JA))
        // A write against the ahead-stamped file keeps the rows and the stamp.
        hide("three")
        assertEquals(setOf("one", "two", "three"), HiddenWordsStore.snapshot(ctx, SourceLangId.JA))
        val stamp = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA user_version", null).use { c -> c.moveToFirst(); c.getInt(0) }
        }
        assertEquals(99, stamp)
    }

    @Test
    fun freshFileIsStampedWithCurrentSchema(): Unit = runBlocking {
        hide("x")
        val stamp = SQLiteDatabase.openDatabase(
            HiddenWordsStore.fileFor(ctx).path, null, SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            db.rawQuery("PRAGMA user_version", null).use { c -> c.moveToFirst(); c.getInt(0) }
        }
        assertEquals(1, stamp)
    }

    // ─── S6 ───────────────────────────────────────────────────────────

    @Test
    fun entriesCarryMetadataNewestFirst(): Unit = runBlocking {
        hide("古い", reading = "ふるい")
        hide("新しい", reading = "")
        val entries = HiddenWordsStore.entries(ctx, SourceLangId.JA)
        assertEquals(listOf("新しい", "古い"), entries.map { it.word })
        val old = entries.single { it.word == "古い" }
        assertEquals("ja", old.lang)
        assertEquals("ふるい", old.reading)
        assertEquals(HiddenWordsStore.SOURCE_MANUAL, old.source)
        assertTrue(old.hiddenAtMs > 0)
        // A blank reading is stored as NULL, not "".
        assertNull(entries.single { it.word == "新しい" }.reading)
        // Other languages' rows never appear.
        assertTrue(HiddenWordsStore.entries(ctx, SourceLangId.EN).isEmpty())
    }

    // ─── S7 ───────────────────────────────────────────────────────────

    @Test
    fun unhideForCardClearsOnlyHidden(): Unit = runBlocking {
        hide("target")
        val before = HiddenWordsStore.revision.value
        HiddenWordsStore.unhideForCard(ctx, SourceLangId.JA, "target")
        assertFalse("target" in HiddenWordsStore.snapshot(ctx, SourceLangId.JA))
        assertEquals(before + 1, HiddenWordsStore.revision.value)
        HiddenWordsStore.unhideForCard(ctx, SourceLangId.JA, "visible")
        assertEquals(before + 1, HiddenWordsStore.revision.value)
    }
}
