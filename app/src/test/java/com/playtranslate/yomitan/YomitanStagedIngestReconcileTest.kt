package com.playtranslate.yomitan

import com.playtranslate.PtJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory

/**
 * Robolectric tests for the [YomitanDataStore] staged-ingest contract: rows
 * that [YomitanDataStore.tryIngest] lands for a not-yet-registered id must
 * survive a reconcile that runs before the caller's registry commit, for as
 * long as the ingesting coroutine is alive, and no longer.
 *
 * The 2026-09-10 Thor trace this pins: the auto-updater ingested JMnedict
 * under its new content id; a fresh process's first lookup reconciled
 * against the on-disk registry, which did not list the id yet, and purged
 * the 668k rows as an orphan; the heal pass then re-downloaded the deck it
 * had just ingested. The 2026-09-12 review cell: an import cancelled (the
 * dialog's Cancel) or failed after its ingest must not keep those rows
 * exempt from the purge for the rest of the process.
 *
 * Cells (id in ingested_dicts × in registry × stage owner), each a test:
 *  - staged by a LIVE job, unregistered: kept, across a cold init AND an
 *    unrelated invalidate (a settings edit racing the commit);
 *  - staged, registered: kept, and the stage ends (an entry removed later
 *    leaves a true orphan);
 *  - staged by a COMPLETED job (cancelled, failed), unregistered: purged;
 *  - unstaged, unregistered: purged (process restart, abort purge).
 */
@RunWith(RobolectricTestRunner::class)
class YomitanStagedIngestReconcileTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val root: File get() = YomitanDictionaryStore.rootDir(ctx)
    private val tmp = createTempDirectory("staged-ingest").toFile()

    @Before
    fun setUp() {
        runBlocking { YomitanDataStore.resetForTest() }
        root.deleteRecursively()
    }

    @After
    fun tearDown() {
        runBlocking { YomitanDataStore.resetForTest() }
        tmp.deleteRecursively()
    }

    private fun dict(id: String) = YomitanDictionary(
        id = id,
        title = "t-$id",
        format = 3,
        categories = listOf(YomitanCategory.TERMS),
        sizeBytes = 0,
        importedAtMs = 0,
    )

    /** Writes the on-disk registry directly: the file a cold reconcile reads. */
    private fun writeRegistry(vararg ids: String) {
        root.mkdirs()
        File(root, "registry.json").writeText(
            PtJson.pretty.encodeToString(YomitanRegistry(dictionaries = ids.map(::dict))),
        )
    }

    /** Minimal Yomitan zip: index.json + one term_bank entry. */
    private fun deckZip(term: String): File {
        val file = File(tmp, "$term.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("index.json"))
            zip.write("""{"title":"t","format":3,"revision":"1"}""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("term_bank_1.json"))
            zip.write("""[["$term","よみ","","",0,["gloss"],1,""]]""".toByteArray())
            zip.closeEntry()
        }
        return file
    }

    private suspend fun ingest(id: String): Boolean =
        YomitanDataStore.tryIngest(ctx, dict(id), deckZip(id))

    /** Ingests [id] from a coroutine that then stays suspended until the test
     *  cancels it: the shape of the real import flows, whose one withContext
     *  block spans ingest and commit. Completes [ingested] with tryIngest's
     *  result; returns the owning job. */
    private fun CoroutineScope.ingestAndHold(id: String, ingested: CompletableDeferred<Boolean>): Job =
        launch {
            ingested.complete(ingest(id))
            awaitCancellation()
        }

    /** Forces a reconcile (init is cold after every invalidate/reset) and
     *  returns the outdated set it reports, proving the reconcile ran. */
    private suspend fun reconcile(): Set<String> = YomitanDataStore.outdatedDictIds(ctx)

    private suspend fun isIngested(id: String): Boolean = YomitanDataStore.isIngested(ctx, id)

    private suspend fun invalidate() = YomitanDataStore.invalidate()

    @Test
    fun `staged rows survive a cold reconcile while the import is still running`() {
        runBlocking {
            writeRegistry("aaa")
            val ok = CompletableDeferred<Boolean>()
            val flow = ingestAndHold("bbb", ok)
            assertTrue(ok.await())
            // Cold init (fresh process): the on-disk registry lists only aaa.
            // Before the fix this purged bbb as an orphan. tryIngest's own
            // dispatcher-switch job has completed by now; the stage must be
            // owned by the caller's (still suspended) job.
            assertEquals(setOf("aaa"), reconcile())
            assertTrue(isIngested("bbb"))
            flow.cancelAndJoin()
        }
    }

    @Test
    fun `an unrelated invalidate does not end a live stage`() {
        runBlocking {
            writeRegistry("aaa")
            val ok = CompletableDeferred<Boolean>()
            val flow = ingestAndHold("bbb", ok)
            assertTrue(ok.await())
            invalidate() // a settings edit racing the commit
            assertEquals(setOf("aaa"), reconcile())
            assertTrue(isIngested("bbb"))
            flow.cancelAndJoin()
        }
    }

    @Test
    fun `stage ends once the registry lists the id`() {
        runBlocking {
            writeRegistry("aaa")
            val ok = CompletableDeferred<Boolean>()
            val flow = ingestAndHold("bbb", ok)
            assertTrue(ok.await())
            assertEquals(setOf("aaa"), reconcile())
            // Commit lands: bbb is registered and ingested, so not outdated.
            writeRegistry("aaa", "bbb")
            invalidate()
            assertEquals(setOf("aaa"), reconcile())
            assertTrue(isIngested("bbb"))
            // The flow finishing after its commit changes nothing.
            flow.cancelAndJoin()
            invalidate()
            assertEquals(setOf("aaa"), reconcile())
            assertTrue(isIngested("bbb"))
            // The stage is over: an entry removed later leaves a true orphan.
            writeRegistry("aaa")
            invalidate()
            reconcile()
            assertFalse(isIngested("bbb"))
        }
    }

    @Test
    fun `an import cancelled after its ingest is purged as an orphan`() {
        runBlocking {
            writeRegistry("aaa")
            val ok = CompletableDeferred<Boolean>()
            val flow = ingestAndHold("bbb", ok)
            assertTrue(ok.await())
            assertTrue(isIngested("bbb"))
            flow.cancelAndJoin() // the import dialog's Cancel, after the rows landed
            reconcile()
            assertFalse(isIngested("bbb"))
        }
    }

    @Test
    fun `an import that fails after its ingest is purged as an orphan`() {
        runBlocking {
            writeRegistry("aaa")
            // Own supervisor so the failure stays in the Deferred instead of
            // cancelling the test's scope.
            val flow = async(SupervisorJob()) {
                ingest("bbb")
                error("registry write failed")
            }
            assertTrue(runCatching { flow.await() }.isFailure)
            assertTrue(flow.isCompleted)
            reconcile()
            assertFalse(isIngested("bbb"))
        }
    }

    @Test
    fun `rows staged by a dead process are purged as orphans`() {
        runBlocking {
            writeRegistry("aaa")
            assertTrue(ingest("bbb"))
            YomitanDataStore.resetForTest() // process restart
            reconcile()
            assertFalse(isIngested("bbb"))
        }
    }

    @Test
    fun `abort purge drops the rows and the stage`() {
        runBlocking {
            writeRegistry("aaa")
            assertTrue(ingest("bbb"))
            YomitanDataStore.onDictDeleted(ctx, "bbb")
            assertFalse(isIngested("bbb"))
            assertEquals(setOf("aaa"), reconcile())
            assertFalse(isIngested("bbb"))
        }
    }
}
