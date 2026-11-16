package com.playtranslate.audio.localaudio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.audio.AudioCache
import com.playtranslate.audio.localaudio.LocalAudioFixture.Recording
import com.playtranslate.audio.sources.LocalAudioSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.RandomAccessFile
import java.util.Collections

/**
 * [LocalAudioDb] against a database made with the statements the real file
 * is made with ([LocalAudioFixture]): a word's rows, a clip's bytes, the
 * sources, the check an import runs, and what a read answers when there is
 * no file or the file is damaged.
 */
@RunWith(RobolectricTestRunner::class)
class LocalAudioDbTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    /** Where a test builds a database that is not the imported file. */
    private val scratchDir get() = File(ctx.cacheDir, "local-audio-test")

    @Before fun setUp() {
        LocalAudioFixture.clear(ctx)
        scratchDir.deleteRecursively()
    }

    @After fun tearDown() {
        LocalAudioFixture.clear(ctx)
        scratchDir.deleteRecursively()
    }

    // ── Rows ─────────────────────────────────────────────────────────────

    @Test fun `a word's rows are every recording of that written form and no other's`() = runBlocking {
        LocalAudioFixture.install(ctx)
        val rows = LocalAudioDb.rows(ctx, "食べる")
        assertEquals(
            setOf("nhk16" to "audio/taberu.opus", "shinmeikai8" to "media/taberu.mp3", "forvo" to "strawberrybrown/食べる.mp3"),
            rows.map { it.source to it.file }.toSet(),
        )
        val nhk = rows.single { it.source == "nhk16" }
        assertEquals("タベル", nhk.reading)
        assertEquals("タベ＼ル", nhk.display)
        assertNull(nhk.speaker)
        val forvo = rows.single { it.source == "forvo" }
        assertNull("a Forvo row has no reading", forvo.reading)
        assertEquals("strawberrybrown", forvo.speaker)
    }

    @Test fun `a word the file has no recording of has no rows, and neither does its reading`() = runBlocking {
        LocalAudioFixture.install(ctx)
        assertTrue(LocalAudioDb.rows(ctx, "飲む").isEmpty())
        assertTrue("the written form is the key, not the reading", LocalAudioDb.rows(ctx, "タベル").isEmpty())
    }

    // ── Clips ────────────────────────────────────────────────────────────

    @Test fun `a clip is the bytes stored under its file and its source`() = runBlocking {
        val nhk = byteArrayOf(1, 2, 3, 4)
        val other = byteArrayOf(9, 8, 7)
        LocalAudioFixture.install(
            ctx,
            listOf(
                Recording("食べる", "タベル", "nhk16", "a.opus", data = nhk),
                // The same file name under another source is another clip.
                Recording("食べる", "たべる", "daijisen", "a.opus", data = other),
            ),
        )
        assertArrayEquals(nhk, LocalAudioDb.clip(ctx, "nhk16", "a.opus") { it })
        assertArrayEquals(other, LocalAudioDb.clip(ctx, "daijisen", "a.opus") { it })
        assertNull(LocalAudioDb.clip(ctx, "forvo", "a.opus") { it })
        assertNull(LocalAudioDb.clip(ctx, "nhk16", "b.opus") { it })
    }

    @Test fun `a clip too large for a cursor row is a miss and the next read still works`() = runBlocking {
        LocalAudioFixture.install(
            ctx,
            listOf(
                Recording("長い", "ながい", "nhk16", "long.opus", data = ByteArray(3 * 1024 * 1024) { 7 }),
                Recording("食べる", "タベル", "nhk16", "short.opus", data = byteArrayOf(5)),
            ),
        )
        assertNull(LocalAudioDb.clip(ctx, "nhk16", "long.opus") { it })
        assertArrayEquals(byteArrayOf(5), LocalAudioDb.clip(ctx, "nhk16", "short.opus") { it })
    }

    // ── What the file holds ──────────────────────────────────────────────

    @Test fun `the sources are listed once each in the offered order`() = runBlocking {
        LocalAudioFixture.install(ctx)
        assertEquals(listOf("nhk16", "shinmeikai8", "forvo", "my_recordings"), LocalAudioDb.sources(ctx))
    }

    @Test fun `the clips are counted by file extension`() = runBlocking {
        LocalAudioFixture.install(ctx)
        assertEquals(mapOf("mp3" to 2, "ogg" to 1, "opus" to 3), LocalAudioDb.clipsByExtension(ctx))
    }

    @Test fun `an extension counts the same in capitals, and a file with none counts under no extension`() = runBlocking {
        LocalAudioFixture.install(
            ctx,
            listOf(
                Recording("食べる", null, "forvo", "a.MP3"),
                Recording("飲む", null, "forvo", "b.mp3"),
                Recording("犬", null, "forvo", "bare"),
            ),
        )
        assertEquals(mapOf("" to 1, "mp3" to 2), LocalAudioDb.clipsByExtension(ctx))
    }

    @Test fun `a clip stored empty is no clip`() = runBlocking {
        LocalAudioFixture.install(ctx, listOf(Recording("食べる", "タベル", "nhk16", "empty.opus", data = ByteArray(0))))
        assertNull(LocalAudioDb.clip(ctx, "nhk16", "empty.opus") { it })
    }

    // ── No file, another file, a damaged file ────────────────────────────

    @Test fun `with no file every read answers nothing`() = runBlocking {
        assertTrue(LocalAudioDb.rows(ctx, "食べる").isEmpty())
        assertNull(LocalAudioDb.clip(ctx, "nhk16", "audio/taberu.opus") { it })
        assertNull("sources that could not be read are not an empty list", LocalAudioDb.sources(ctx))
    }

    @Test fun `after the file is replaced a read opens the file that is there then`() = runBlocking {
        LocalAudioFixture.install(ctx)
        assertEquals(3, LocalAudioDb.rows(ctx, "食べる").size)
        LocalAudioFixture.install(ctx, listOf(Recording("飲む", "のむ", "jpod", "nomu.mp3")))
        assertTrue(LocalAudioDb.rows(ctx, "食べる").isEmpty())
        assertEquals(1, LocalAudioDb.rows(ctx, "飲む").size)
    }

    @Test fun `a file SQLite calls corrupt is read as nothing and is not deleted`() = runBlocking {
        val file = LocalAudioFixture.install(ctx)
        // Everything past the first page, which holds the schema, written
        // over: the tables are still named, and no page of them is sound.
        val pageSize = 4096
        assertTrue("the fixture has pages to damage", file.length() > 2L * pageSize)
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(pageSize.toLong())
            raf.write(ByteArray((file.length() - pageSize).toInt()) { 0x5A })
        }
        assertTrue(LocalAudioDb.rows(ctx, "食べる").isEmpty())
        assertNull(LocalAudioDb.clip(ctx, "nhk16", "audio/taberu.opus") { it })
        assertTrue("the platform's own handler would have deleted it", file.exists())
    }

    // ── Removing and replacing the file is one step ──────────────────────

    /** This signal, or a failed test if it does not come. A test that waits
     *  on the code it tests must not wait for ever when that code is wrong:
     *  that would hang the whole suite where it should fail one test. */
    private suspend fun <T> Deferred<T>.soon(): T = withTimeout(5_000) { await() }

    @Test fun `a clip is handed to its keeper, and a recording the file does not hold is handed to none`() = runBlocking {
        LocalAudioFixture.install(ctx)
        var kept = 0
        assertEquals(
            "nhk16/audio/taberu.opus".length,
            LocalAudioDb.clip(ctx, "nhk16", "audio/taberu.opus") { kept++; it.size },
        )
        assertNull(LocalAudioDb.clip(ctx, "nhk16", "audio/none.opus") { kept++; it.size })
        assertEquals(1, kept)
    }

    @Test fun `a clip being kept is kept before the file can be removed, and the removal takes it`() = runBlocking {
        val file = LocalAudioFixture.install(ctx)
        val cache = AudioCache(ctx)
        val keeping = CompletableDeferred<Unit>()
        val letKeep = CompletableDeferred<Unit>()
        val order = Collections.synchronizedList(mutableListOf<String>())
        // A word is tapped: its clip is read, and is about to be stored.
        val read = async(Dispatchers.IO) {
            LocalAudioDb.clip(ctx, "nhk16", "audio/taberu.opus") { bytes ->
                keeping.complete(Unit)
                letKeep.await()
                order += "kept"
                cache.putClip(LocalAudioSource.ID, "nhk16/audio/taberu.opus", bytes, null)
            }
        }
        keeping.soon()
        // At that moment the user removes the file.
        val removal = async(Dispatchers.IO) {
            LocalAudioDb.replacing(ctx) { order += "removed"; file.delete() }
        }
        assertNull("the removal waits for the clip to be kept", withTimeoutOrNull(300) { removal.await() })
        assertTrue(file.exists())
        assertTrue(order.isEmpty())
        letKeep.complete(Unit)
        read.soon()
        removal.soon()
        assertEquals(listOf("kept", "removed"), order.toList())
        assertNull("nothing of the removed file is left kept", cache.getClip(LocalAudioSource.ID, "nhk16/audio/taberu.opus"))
    }

    @Test fun `nothing opens the file while it is being removed, and no handle on it is left`() = runBlocking {
        val file = LocalAudioFixture.install(ctx)
        assertEquals("the file is open", 3, LocalAudioDb.rows(ctx, "食べる").size)
        val removing = CompletableDeferred<Unit>()
        val letRemove = CompletableDeferred<Unit>()
        // The removal has let the handle go and has not yet deleted the file.
        val removal = async(Dispatchers.IO) {
            LocalAudioDb.replacing(ctx) { removing.complete(Unit); letRemove.await(); file.delete() }
        }
        removing.soon()
        // A word is tapped at that moment: the file is there to be opened.
        assertTrue(file.exists())
        val lookup = async(Dispatchers.IO) { LocalAudioDb.rows(ctx, "食べる") }
        assertNull("the lookup waits for the removal", withTimeoutOrNull(300) { lookup.await() })
        letRemove.complete(Unit)
        removal.soon()
        assertTrue("and then finds no file", lookup.soon().isEmpty())
        // Had the lookup reopened the removed file, that handle would still
        // be the one read. A file put there now, by hand, is the one read.
        LocalAudioFixture.build(LocalAudioStore.file(ctx), listOf(Recording("飲む", "のむ", "jpod", "nomu.mp3")))
        assertTrue(LocalAudioDb.rows(ctx, "食べる").isEmpty())
        assertEquals(1, LocalAudioDb.rows(ctx, "飲む").size)
    }

    @Test fun `a clip asked for while the file is being removed waits too, and is not kept`() = runBlocking {
        val file = LocalAudioFixture.install(ctx)
        val removing = CompletableDeferred<Unit>()
        val letRemove = CompletableDeferred<Unit>()
        val removal = async(Dispatchers.IO) {
            LocalAudioDb.replacing(ctx) { removing.complete(Unit); letRemove.await(); file.delete() }
        }
        removing.soon()
        var kept = 0
        val read = async(Dispatchers.IO) { LocalAudioDb.clip(ctx, "nhk16", "audio/taberu.opus") { kept++; it } }
        assertNull(withTimeoutOrNull(300) { read.await() })
        letRemove.complete(Unit)
        removal.soon()
        assertNull(read.soon())
        assertEquals(0, kept)
    }

    @Test fun `replacing the file drops the clips kept of the old one, and those of no other source`() = runBlocking {
        LocalAudioFixture.install(ctx)
        val cache = AudioCache(ctx)
        cache.putClip(LocalAudioSource.ID, "nhk16/audio/taberu.opus", byteArrayOf(1), null)
        cache.putClip("wikimedia_commons", "Ja-taberu.ogg", byteArrayOf(2), null)
        LocalAudioDb.replacing(ctx) { }
        assertNull(cache.getClip(LocalAudioSource.ID, "nhk16/audio/taberu.opus"))
        assertNotNull(cache.getClip("wikimedia_commons", "Ja-taberu.ogg"))
    }

    @Test fun `a change that fails leaves the kept clips, whose file is still the one loaded`() = runBlocking {
        LocalAudioFixture.install(ctx)
        val cache = AudioCache(ctx)
        cache.putClip(LocalAudioSource.ID, "nhk16/audio/taberu.opus", byteArrayOf(1), null)
        val failure = runCatching { LocalAudioDb.replacing(ctx) { throw java.io.IOException("the rename failed") } }
        assertEquals("the rename failed", failure.exceptionOrNull()?.message)
        assertNotNull(cache.getClip(LocalAudioSource.ID, "nhk16/audio/taberu.opus"))
        assertEquals("and the file is read as before", 3, LocalAudioDb.rows(ctx, "食べる").size)
    }

    // ── What went wrong is kept, for the page to show ────────────────────

    @Test fun `a sound file read without failure has no problem to tell`() = runBlocking {
        LocalAudioFixture.install(ctx)
        LocalAudioDb.check(ctx)
        LocalAudioDb.rows(ctx, "食べる")
        LocalAudioDb.clip(ctx, "nhk16", "audio/taberu.opus") { it }
        assertNull("a recording the file does not hold is no failure", LocalAudioDb.clip(ctx, "nhk16", "audio/none.opus") { it })
        assertTrue(LocalAudioDb.rows(ctx, "飲む").isEmpty())
        assertNull(LocalAudioDb.problem)
    }

    @Test fun `with no file there is nothing to check and nothing wrong`() = runBlocking {
        LocalAudioDb.check(ctx)
        assertNull(LocalAudioDb.problem)
    }

    @Test fun `a read that meets a damaged page is kept as the file's problem, in SQLite's words`() = runBlocking {
        val file = LocalAudioFixture.install(ctx)
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(4096)
            raf.write(ByteArray((file.length() - 4096).toInt()) { 0x5A })
        }
        assertNull("nothing is known until a read meets it", LocalAudioDb.problem)
        assertTrue(LocalAudioDb.rows(ctx, "食べる").isEmpty())
        assertEquals("database disk image is malformed", LocalAudioDb.problem)
    }

    @Test fun `a file that is no database any more is found by the check, without a word being asked for`() = runBlocking {
        LocalAudioStore.file(ctx).apply { parentFile?.mkdirs(); writeText("not a database\n".repeat(100)) }
        LocalAudioDb.check(ctx)
        assertEquals("file is not a database", LocalAudioDb.problem)
        assertTrue("and the file is still the user's to remove", LocalAudioStore.file(ctx).exists())
    }

    @Test fun `a file the system will not open is found by the check`() = runBlocking {
        val file = LocalAudioFixture.install(ctx)
        // Opening a file that is not a database succeeds, and it is the
        // first read that fails. This is the open itself failing.
        assertTrue(file.setReadable(false))
        try {
            LocalAudioDb.check(ctx)
            assertNotNull(LocalAudioDb.problem)
            assertTrue("no read was made of a file that never opened", LocalAudioDb.rows(ctx, "食べる").isEmpty())
        } finally {
            file.setReadable(true)
        }
    }

    @Test fun `a database of something else is found by the check`() = runBlocking {
        LocalAudioFixture.build(LocalAudioStore.file(ctx), schema = listOf(LocalAudioFixture.CREATE_ENTRIES))
        LocalAudioDb.check(ctx)
        assertEquals("no such table: android", LocalAudioDb.problem)
    }

    @Test fun `a clip too large to read is kept as a problem too`() = runBlocking {
        LocalAudioFixture.install(
            ctx,
            listOf(Recording("長い", "ながい", "nhk16", "long.opus", data = ByteArray(3 * 1024 * 1024) { 7 })),
        )
        assertNull(LocalAudioDb.clip(ctx, "nhk16", "long.opus") { it })
        assertTrue(LocalAudioDb.problem.orEmpty(), LocalAudioDb.problem.orEmpty().startsWith("Row too big"))
    }

    @Test fun `letting go of the file forgets what was wrong with it`() = runBlocking {
        LocalAudioStore.file(ctx).apply { parentFile?.mkdirs(); writeText("not a database\n".repeat(100)) }
        LocalAudioDb.check(ctx)
        assertTrue(LocalAudioDb.problem != null)
        LocalAudioDb.replacing(ctx) { }
        assertNull(LocalAudioDb.problem)
        // The file that replaces it starts with a clean record.
        LocalAudioFixture.install(ctx)
        LocalAudioDb.check(ctx)
        assertNull(LocalAudioDb.problem)
    }

    // ── The check an import runs ─────────────────────────────────────────

    private fun scratch(name: String) = File(scratchDir, name).also { it.parentFile?.mkdirs() }

    @Test fun `the file the generator makes passes the check`() {
        assertNull(LocalAudioDb.probe(LocalAudioFixture.build(scratch("ok.db"))))
    }

    @Test fun `a database in write-ahead-log mode opens read-only too`() = runBlocking {
        val walFile = LocalAudioFixture.build(scratch("wal.db"), wal = true)
        assertNull(LocalAudioDb.probe(walFile))
        // And as the imported file: opened, read, and closed again.
        walFile.copyTo(LocalAudioStore.file(ctx).also { it.parentFile?.mkdirs() }, overwrite = true)
        assertEquals(3, LocalAudioDb.rows(ctx, "食べる").size)
        assertNotNull(LocalAudioDb.clip(ctx, "nhk16", "audio/taberu.opus") { it })
    }

    @Test fun `a database without the clip table is refused in SQLite's own words`() {
        val noClips = LocalAudioFixture.build(
            scratch("no-android.db"),
            schema = listOf(LocalAudioFixture.CREATE_ENTRIES),
        )
        assertEquals("no such table: android", LocalAudioDb.probe(noClips))
    }

    @Test fun `a database whose rows lack a column read here is refused, naming the column`() {
        val oldShape = LocalAudioFixture.build(
            scratch("no-display.db"),
            recordings = emptyList(),
            schema = listOf(
                "CREATE TABLE entries (id integer PRIMARY KEY NOT NULL, expression text NOT NULL, " +
                    "reading text, source text NOT NULL, speaker text, file text NOT NULL)",
                LocalAudioFixture.CREATE_ANDROID,
            ),
        )
        assertEquals("no such column: display", LocalAudioDb.probe(oldShape))
    }

    @Test fun `a file that is no database is refused`() {
        val junk = scratch("junk.db").apply { writeText("not a database at all, just some text\n".repeat(200)) }
        assertNotNull(LocalAudioDb.probe(junk))
    }
}
