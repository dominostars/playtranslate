package com.playtranslate.audio.localaudio

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.audio.AudioCache
import com.playtranslate.audio.localaudio.LocalAudioFixture.Recording
import com.playtranslate.audio.sources.LocalAudioSource
import com.playtranslate.audio.sources.WikimediaCommonsAudioSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.io.RandomAccessFile
import java.util.Collections

/**
 * [LocalAudioStore]: a picked file copied in, with its progress; what is
 * refused and with what cause; that every end but a whole import leaves
 * nothing behind; removal; and the part file a killed process left.
 */
@RunWith(RobolectricTestRunner::class)
class LocalAudioStoreTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val picked get() = File(ctx.cacheDir, "local-audio-picked")
    private val storeDir get() = LocalAudioStore.file(ctx).parentFile!!
    private val part get() = File(LocalAudioStore.file(ctx).path + ".part")

    /** The free space the device is said to have, in the 4096-byte blocks
     *  the test environment counts in. */
    private fun freeSpace(bytes: Long) {
        ShadowStatFs.registerStats(ctx.noBackupFilesDir, 1 shl 24, (bytes / 4096).toInt(), (bytes / 4096).toInt())
    }

    @Before fun setUp() {
        LocalAudioFixture.clear(ctx)
        picked.deleteRecursively()
        File(ctx.cacheDir, "audioCache").deleteRecursively()
        freeSpace(8L shl 30)
    }

    @After fun tearDown() {
        LocalAudioFixture.clear(ctx)
        picked.deleteRecursively()
        File(ctx.cacheDir, "audioCache").deleteRecursively()
        ShadowStatFs.reset()
    }

    private fun pickedDb(
        name: String = "android.db",
        recordings: List<Recording> = LocalAudioFixture.RECORDINGS,
        schema: List<String> = LocalAudioFixture.SCHEMA,
        wal: Boolean = false,
    ): File = LocalAudioFixture.build(File(picked, name), recordings, schema, wal)

    /** A database of more than the 8 MB between two reports of a copy:
     *  [megabytes] clips of a megabyte each. */
    private fun largeDb(megabytes: Int = 12): File = pickedDb(
        recordings = (1..megabytes).map { n ->
            Recording("語$n", null, "forvo", "clip$n.mp3", data = ByteArray(1 shl 20) { n.toByte() })
        },
    )

    private fun import(file: File, onStep: (LocalAudioImportStep) -> Unit = {}): LocalAudioImportResult =
        runBlocking { LocalAudioStore.import(ctx, Uri.fromFile(file), onStep) }

    private fun leftInStore(): List<String> = storeDir.list().orEmpty().sorted()

    // ── A whole import ───────────────────────────────────────────────────

    @Test fun `a picked file is copied in whole and is then the imported file`() = runBlocking {
        val source = pickedDb()
        assertFalse(LocalAudioStore.isImported(ctx))
        assertEquals(LocalAudioImportResult.Imported, import(source))
        assertTrue(LocalAudioStore.isImported(ctx))
        assertEquals(source.length(), LocalAudioStore.sizeBytes(ctx))
        assertArrayEquals(source.readBytes(), LocalAudioStore.file(ctx).readBytes())
        assertEquals("nothing but the file is left", listOf("android.db"), leftInStore())
        assertTrue("the original is not touched", source.exists())
        assertEquals(3, LocalAudioDb.rows(ctx, "食べる").size)
    }

    @Test fun `a copy reports how far it is against the whole, and then that it is being checked`() {
        val source = largeDb()
        val steps = Collections.synchronizedList(mutableListOf<LocalAudioImportStep>())
        assertEquals(LocalAudioImportResult.Imported, import(source) { steps += it })
        val copying = steps.filterIsInstance<LocalAudioImportStep.Copying>()
        assertTrue("a copy of ${source.length()} bytes reports on its way", copying.isNotEmpty())
        assertTrue(copying.all { it.totalBytes == source.length() })
        assertTrue(copying.all { it.copiedBytes in 1..source.length() })
        assertEquals("further each time", copying.map { it.copiedBytes }.sorted(), copying.map { it.copiedBytes })
        assertTrue("no more often than every 8 MB", copying.size <= source.length() / (8L shl 20))
        assertEquals("checked once the copy is done", LocalAudioImportStep.Checking, steps.last())
        assertEquals(1, steps.count { it == LocalAudioImportStep.Checking })
    }

    // ── Ends that are no import ──────────────────────────────────────────

    @Test fun `a cancelled copy stops there, is rethrown as a cancel and leaves nothing`() = runBlocking {
        // Three reports' worth, were it copied to its end.
        val source = largeDb(megabytes = 26)
        val steps = Collections.synchronizedList(mutableListOf<LocalAudioImportStep>())
        var cancelled: CancellationException? = null
        var result: LocalAudioImportResult? = null
        lateinit var job: Job
        job = launch {
            try {
                result = LocalAudioStore.import(ctx, Uri.fromFile(source)) { step ->
                    steps += step
                    // The first report comes with megabytes still to copy.
                    if (step is LocalAudioImportStep.Copying) job.cancel()
                }
            } catch (e: CancellationException) {
                cancelled = e
                throw e
            }
        }
        job.join()
        assertNotNull("a cancel is not an alert's worth of result", cancelled)
        assertNull(result)
        assertEquals("nothing more is copied once it is cancelled", 1, steps.size)
        assertFalse(LocalAudioStore.isImported(ctx))
        assertEquals(emptyList<String>(), leftInStore())
        assertTrue(
            "a cancel is not logged as an import that failed",
            ShadowLog.getLogsForTag("PtAudio").none { it.msg == "Local audio: import failed" },
        )
    }

    @Test fun `a cancel that lands while the copy is being checked still leaves nothing`() = runBlocking {
        val source = pickedDb()
        lateinit var job: Job
        job = launch {
            LocalAudioStore.import(ctx, Uri.fromFile(source)) { step ->
                if (step == LocalAudioImportStep.Checking) job.cancel()
            }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse("the file is put in place only by an import nobody cancelled", LocalAudioStore.isImported(ctx))
        assertEquals(emptyList<String>(), leftInStore())
    }

    @Test fun `the line logged for an import says the file's size, its sources and its clips by extension`() = runBlocking {
        val source = pickedDb()
        assertEquals(LocalAudioImportResult.Imported, import(source))
        LocalAudioStore.logSummary(ctx)
        assertEquals(
            "Local audio imported: bytes=${source.length()} " +
                "sources=[nhk16, shinmeikai8, forvo, my_recordings] clips={mp3=2, ogg=1, opus=3}",
            ShadowLog.getLogsForTag("PtAudio").last { it.msg.startsWith("Local audio imported") }.msg,
        )
    }

    @Test fun `a file that is no SQLite database is refused before anything is copied`() {
        val text = File(picked, "notes.txt").apply { parentFile?.mkdirs(); writeText("just some notes\n".repeat(50)) }
        val steps = mutableListOf<LocalAudioImportStep>()
        assertEquals(LocalAudioImportResult.NotSqlite, import(text) { steps += it })
        assertEquals(emptyList<LocalAudioImportStep>(), steps)
        assertEquals(emptyList<String>(), leftInStore())

        val short = File(picked, "short.db").apply { writeBytes("SQLite format 3\u0000".toByteArray()) }
        assertEquals("the head of a database and no more", LocalAudioImportResult.NotSqlite, import(short))
        assertEquals(emptyList<String>(), leftInStore())
    }

    @Test fun `a file that does not fit is refused with what it needs and what there is`() {
        val source = pickedDb()
        val free = 50L * 4096
        freeSpace(free)
        // A small file needs itself and 100 MB of room.
        assertEquals(
            LocalAudioImportResult.InsufficientSpace(neededBytes = source.length() + 100_000_000L, freeBytes = free),
            import(source),
        )
        assertEquals(emptyList<String>(), leftInStore())
    }

    @Test fun `room for the file and its headroom is room enough`() {
        val source = pickedDb()
        freeSpace(source.length() + 100_000_000L + 4096)
        assertEquals(LocalAudioImportResult.Imported, import(source))
    }

    @Test fun `a database that is not a local audio file is refused, naming what is missing`() {
        val other = pickedDb(schema = listOf(LocalAudioFixture.CREATE_ENTRIES))
        assertEquals(LocalAudioImportResult.InvalidFormat("no such table: android"), import(other))
        assertFalse(LocalAudioStore.isImported(ctx))
        assertEquals("the copy that was checked is gone too", emptyList<String>(), leftInStore())
    }

    @Test fun `a file cut short of the size its header records is refused before anything is copied`() {
        val source = largeDb()
        val whole = source.length()
        // More than 8 MB is left: a copy, were one made, would have reported.
        val cut = whole - (2L shl 20)
        RandomAccessFile(source, "rw").use { it.setLength(cut) }
        val steps = mutableListOf<LocalAudioImportStep>()
        assertEquals(
            LocalAudioImportResult.Incomplete(haveBytes = cut, expectedBytes = whole),
            import(source) { steps += it },
        )
        assertEquals(emptyList<LocalAudioImportStep>(), steps)
        assertEquals(emptyList<String>(), leftInStore())
    }

    /** A document whose provider gives a stream and no size, as a picker
     *  backed by a pipe does. */
    private fun sizeless(bytes: ByteArray): Uri {
        val uri = Uri.parse("content://local-audio-test/android.db")
        shadowOf(ctx.contentResolver).registerInputStreamSupplier(uri) { bytes.inputStream() }
        return uri
    }

    @Test fun `a document whose picker gives no size is measured by its own header`() {
        val source = largeDb()
        val steps = Collections.synchronizedList(mutableListOf<LocalAudioImportStep>())
        val result = runBlocking { LocalAudioStore.import(ctx, sizeless(source.readBytes())) { steps += it } }
        assertEquals(LocalAudioImportResult.Imported, result)
        val copying = steps.filterIsInstance<LocalAudioImportStep.Copying>()
        assertTrue(copying.isNotEmpty())
        assertTrue("the whole is the header's", copying.all { it.totalBytes == source.length() })
        assertEquals(source.length(), LocalAudioStore.sizeBytes(ctx))
    }

    @Test fun `such a document that does not fit is refused by the size in its header`() {
        val source = pickedDb()
        freeSpace(50L * 4096)
        val result = runBlocking { LocalAudioStore.import(ctx, sizeless(source.readBytes())) {} }
        assertEquals(
            LocalAudioImportResult.InsufficientSpace(neededBytes = source.length() + 100_000_000L, freeBytes = 50L * 4096),
            result,
        )
    }

    @Test fun `a copy that comes out short of the header's size is refused and leaves nothing`() {
        val source = largeDb()
        val whole = source.length()
        val cut = (whole - (2L shl 20)).toInt()
        val result = runBlocking { LocalAudioStore.import(ctx, sizeless(source.readBytes().copyOf(cut))) {} }
        assertEquals(LocalAudioImportResult.Incomplete(haveBytes = cut.toLong(), expectedBytes = whole), result)
        assertFalse(LocalAudioStore.isImported(ctx))
        assertEquals(emptyList<String>(), leftInStore())
    }

    @Test fun `a database in write-ahead-log mode is imported with nothing left beside it`() {
        assertEquals(LocalAudioImportResult.Imported, import(pickedDb(wal = true)))
        assertEquals("what the check's own read of the copy left is gone", listOf("android.db"), leftInStore())
    }

    @Test fun `a copy is short when it is less than the larger of the sizes known for it`() {
        val incomplete = { have: Long, whole: Long -> LocalAudioImportResult.Incomplete(haveBytes = have, expectedBytes = whole) }
        // Both known, the copy whole.
        assertNull(LocalAudioStore.shortfall(copied = 1000, declared = 1000, expected = 1000))
        // Short of the header's size, whatever the picker said.
        assertEquals(incomplete(900, 1000), LocalAudioStore.shortfall(copied = 900, declared = -1, expected = 1000))
        assertEquals(incomplete(900, 1000), LocalAudioStore.shortfall(copied = 900, declared = 900, expected = 1000))
        // The header gives no size, and the stream ended before the size
        // its picker gave: short all the same.
        assertEquals(incomplete(900, 1000), LocalAudioStore.shortfall(copied = 900, declared = 1000, expected = -1))
        assertEquals(incomplete(900, 1200), LocalAudioStore.shortfall(copied = 900, declared = 1200, expected = 1000))
        // A file with more in it than its header counts is not short.
        assertNull(LocalAudioStore.shortfall(copied = 1200, declared = 1200, expected = 1000))
        // Neither size is known: there is nothing to measure against.
        assertNull(LocalAudioStore.shortfall(copied = 900, declared = -1, expected = -1))
    }

    @Test fun `a database's header gives the size of its file`() {
        val source = pickedDb()
        val header = source.inputStream().use { it.readNBytes(100) }
        assertEquals(source.length(), LocalAudioStore.sizeInHeader(header))
        // The two change counters disagree: the page count is not vouched for.
        val stale = header.copyOf().also { it[95] = (it[95] + 1).toByte() }
        assertEquals(-1L, LocalAudioStore.sizeInHeader(stale))
        // No page count at all, as files of old SQLite versions have.
        val none = header.copyOf().also { for (i in 28..31) it[i] = 0 }
        assertEquals(-1L, LocalAudioStore.sizeInHeader(none))
    }

    @Test fun `a file that cannot be opened is a failed copy with the system's words`() {
        val result = import(File(picked, "missing.db"))
        assertTrue("$result", result is LocalAudioImportResult.IoError)
        assertFalse((result as LocalAudioImportResult.IoError).message.isNullOrBlank())
        assertEquals(emptyList<String>(), leftInStore())
    }

    // ── Remove, and import again ─────────────────────────────────────────

    /** This signal, or a failed test if it does not come. A test that waits
     *  on the code it tests must not wait for ever when that code is wrong:
     *  that would hang the whole suite where it should fail one test. */
    private suspend fun <T> Deferred<T>.soon(): T = withTimeout(5_000) { await() }

    @Test fun `remove deletes the file, what SQLite left beside it, and the clips read from it`() = runBlocking {
        assertEquals(LocalAudioImportResult.Imported, import(pickedDb()))
        assertEquals(3, LocalAudioDb.rows(ctx, "食べる").size)
        listOf("-journal", "-wal", "-shm").forEach { File(LocalAudioStore.file(ctx).path + it).writeText("x") }
        val cache = AudioCache(ctx)
        cache.putClip(LocalAudioSource.ID, "nhk16/audio/taberu.opus", byteArrayOf(1), null)
        cache.putClip(WikimediaCommonsAudioSource.ID, "Ja-taberu.ogg", byteArrayOf(2), null)

        LocalAudioStore.remove(ctx)

        assertFalse(LocalAudioStore.isImported(ctx))
        assertEquals(emptyList<String>(), leftInStore())
        assertNull(cache.getClip(LocalAudioSource.ID, "nhk16/audio/taberu.opus"))
        assertNotNull("another source's clips are not this file's", cache.getClip(WikimediaCommonsAudioSource.ID, "Ja-taberu.ogg"))
        assertTrue("and nothing is read from a file that is gone", LocalAudioDb.rows(ctx, "食べる").isEmpty())
    }

    @Test fun `a clip being kept when the file is removed is not put back after the removal`() = runBlocking {
        assertEquals(LocalAudioImportResult.Imported, import(pickedDb()))
        val cache = AudioCache(ctx)
        val keeping = CompletableDeferred<Unit>()
        val letKeep = CompletableDeferred<Unit>()
        val read = async(Dispatchers.IO) {
            LocalAudioDb.clip(ctx, "nhk16", "audio/taberu.opus") { bytes ->
                keeping.complete(Unit)
                letKeep.await()
                cache.putClip(LocalAudioSource.ID, "nhk16/audio/taberu.opus", bytes, null)
            }
        }
        keeping.soon()
        val removal = async(Dispatchers.IO) { LocalAudioStore.remove(ctx) }
        assertNull("the removal waits", withTimeoutOrNull(300) { removal.await() })
        assertTrue(LocalAudioStore.isImported(ctx))
        letKeep.complete(Unit)
        read.soon()
        removal.soon()
        assertFalse(LocalAudioStore.isImported(ctx))
        assertNull(cache.getClip(LocalAudioSource.ID, "nhk16/audio/taberu.opus"))
        assertEquals(emptyList<String>(), leftInStore())
    }

    @Test fun `a file imported after a removal is the one that is read`() = runBlocking {
        assertEquals(LocalAudioImportResult.Imported, import(pickedDb()))
        assertEquals(3, LocalAudioDb.rows(ctx, "食べる").size)
        LocalAudioStore.remove(ctx)
        val second = pickedDb("second.db", listOf(Recording("飲む", "のむ", "jpod", "nomu.mp3")))
        assertEquals(LocalAudioImportResult.Imported, import(second))
        assertTrue(LocalAudioDb.rows(ctx, "食べる").isEmpty())
        assertEquals(1, LocalAudioDb.rows(ctx, "飲む").size)
    }

    @Test fun `a file imported over another is the one that is read`() = runBlocking {
        assertEquals(LocalAudioImportResult.Imported, import(pickedDb()))
        assertEquals("the first file is open", 3, LocalAudioDb.rows(ctx, "食べる").size)
        val second = pickedDb("second.db", listOf(Recording("飲む", "のむ", "jpod", "nomu.mp3")))
        assertEquals(LocalAudioImportResult.Imported, import(second))
        assertTrue(LocalAudioDb.rows(ctx, "食べる").isEmpty())
        assertEquals(1, LocalAudioDb.rows(ctx, "飲む").size)
    }

    @Test fun `an import drops clips kept from a file that was here before`() = runBlocking {
        val cache = AudioCache(ctx)
        cache.putClip(LocalAudioSource.ID, "nhk16/audio/taberu.opus", byteArrayOf(1), null)
        assertEquals(LocalAudioImportResult.Imported, import(pickedDb()))
        assertNull(cache.getClip(LocalAudioSource.ID, "nhk16/audio/taberu.opus"))
    }

    // ── What a killed process left ───────────────────────────────────────

    @Test fun `the launch sweep deletes a part file and leaves an imported file alone`() = runBlocking {
        assertEquals(LocalAudioImportResult.Imported, import(pickedDb()))
        part.writeBytes(ByteArray(64))
        File(part.path + "-journal").writeText("x")
        LocalAudioStore.sweepPart(ctx)
        assertEquals(listOf("android.db"), leftInStore())
    }

    @Test fun `the launch sweep with nothing to sweep does nothing`() = runBlocking {
        LocalAudioStore.sweepPart(ctx)
        assertFalse(LocalAudioStore.isImported(ctx))
    }
}
