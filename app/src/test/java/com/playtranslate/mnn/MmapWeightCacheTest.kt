package com.playtranslate.mnn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MmapWeightCacheTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun modelDir(): File = tmp.newFolder("gemma-4-e2b-mnn")

    /** The on-disk shape 3.1.1 left behind: MNN's chunk + sync marker, no stamp. */
    private fun writeUnstampedCache(model: File): File {
        val cache = File(model, MMAP_CACHE_DIR_NAME).apply { mkdirs() }
        File(cache, "0_0_2_0_0.static").writeBytes(ByteArray(64) { 1 })
        File(cache, "0_0_2_0_sync.static").writeBytes(ByteArray(0))
        return cache
    }

    private fun stamp(cache: File, version: String) =
        File(cache, MmapWeightCache.LAYOUT_FILE_NAME).writeText(version)

    @Test
    fun dropStaleLayout_withoutCache_isNoop() {
        val model = modelDir()
        assertTrue(MmapWeightCache.dropStaleLayout(model))
        assertFalse(File(model, MMAP_CACHE_DIR_NAME).exists())
    }

    @Test
    fun dropStaleLayout_deletesUnstampedCache() {
        val model = modelDir()
        val cache = writeUnstampedCache(model)
        assertTrue(MmapWeightCache.dropStaleLayout(model))
        assertFalse(cache.exists())
    }

    @Test
    fun dropStaleLayout_deletesOlderStamp() {
        val model = modelDir()
        val cache = writeUnstampedCache(model)
        stamp(cache, (MmapWeightCache.LAYOUT_VERSION - 1).toString())
        assertTrue(MmapWeightCache.dropStaleLayout(model))
        assertFalse(cache.exists())
    }

    @Test
    fun dropStaleLayout_keepsCurrentCache() {
        val model = modelDir()
        val cache = writeUnstampedCache(model)
        stamp(cache, MmapWeightCache.LAYOUT_VERSION.toString())
        assertTrue(MmapWeightCache.dropStaleLayout(model))
        assertTrue(File(cache, "0_0_2_0_0.static").isFile)
        assertTrue(File(cache, "0_0_2_0_sync.static").isFile)
    }

    @Test
    fun dropStaleLayout_keepsEmptyUnstampedDir() {
        val model = modelDir()
        val cache = File(model, MMAP_CACHE_DIR_NAME).apply { mkdirs() }
        assertTrue(MmapWeightCache.dropStaleLayout(model))
        assertTrue(cache.isDirectory)
    }

    @Test
    fun prepareDir_stampsEmptyUnstampedDirInPlace() {
        val model = modelDir()
        val cache = File(model, MMAP_CACHE_DIR_NAME).apply { mkdirs() }
        assertEquals(cache, MmapWeightCache.prepareDir(model))
        assertEquals(listOf(MmapWeightCache.LAYOUT_FILE_NAME), cache.list()!!.toList())
    }

    @Test
    fun dropStaleLayout_treatsGarbageStampAsStale() {
        val model = modelDir()
        val cache = writeUnstampedCache(model)
        stamp(cache, "not-a-number")
        assertTrue(MmapWeightCache.dropStaleLayout(model))
        assertFalse(cache.exists())
    }

    @Test
    fun prepareDir_createsAndStampsFreshDir() {
        val model = modelDir()
        val cache = MmapWeightCache.prepareDir(model)
        assertNotNull(cache)
        assertEquals(File(model, MMAP_CACHE_DIR_NAME), cache)
        assertEquals(
            MmapWeightCache.LAYOUT_VERSION.toString(),
            File(cache!!, MmapWeightCache.LAYOUT_FILE_NAME).readText(),
        )
        assertEquals(listOf(MmapWeightCache.LAYOUT_FILE_NAME), cache.list()!!.toList())
    }

    @Test
    fun prepareDir_replacesStaleCacheWithEmptyStampedDir() {
        val model = modelDir()
        writeUnstampedCache(model)
        val cache = MmapWeightCache.prepareDir(model)
        assertNotNull(cache)
        assertEquals(listOf(MmapWeightCache.LAYOUT_FILE_NAME), cache!!.list()!!.toList())
        assertTrue(MmapWeightCache.dropStaleLayout(model))
        assertTrue(cache.exists())
    }

    @Test
    fun prepareDir_keepsCurrentCacheIntact() {
        val model = modelDir()
        val cache = writeUnstampedCache(model)
        stamp(cache, MmapWeightCache.LAYOUT_VERSION.toString())
        assertEquals(cache, MmapWeightCache.prepareDir(model))
        assertTrue(File(cache, "0_0_2_0_0.static").isFile)
        assertTrue(File(cache, "0_0_2_0_sync.static").isFile)
    }

    @Test
    fun prepareDir_failsWhenDirCannotBeCreated() {
        val model = modelDir()
        // Read-only model dir: mkdirs() fails. Skipped where the JVM can still
        // create files regardless (root).
        assumeTrue(model.setWritable(false, false))
        try {
            assumeFalse(File(model, "probe").mkdir())
            assertNull(MmapWeightCache.prepareDir(model))
        } finally {
            model.setWritable(true, false)
        }
    }
}
