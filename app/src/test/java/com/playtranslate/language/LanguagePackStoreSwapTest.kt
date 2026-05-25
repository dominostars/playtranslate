package com.playtranslate.language

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Unit tests for [LanguagePackStore.safeSwap] — the rollback-safe directory
 * swap used by both install() and installTarget(). Pure JVM, no Android.
 */
class LanguagePackStoreSwapTest {

    private lateinit var root: File

    @Before fun setUp() {
        root = createTempDirectory("packswap").toFile()
    }

    @After fun tearDown() {
        root.deleteRecursively()
    }

    private fun dir(name: String) = File(root, name)

    private fun createPack(dir: File, content: String) {
        dir.mkdirs()
        File(dir, "dict.sqlite").writeText(content)
        File(dir, "manifest.json").writeText("{}")
    }

    // ── Happy path ───────────────────────────────────────────────────────

    @Test fun `swap replaces old pack with new pack`() {
        val tmpDir = dir("tmp")
        val finalDir = dir("final")
        createPack(tmpDir, "new-data")
        createPack(finalDir, "old-data")

        LanguagePackStore.safeSwap(tmpDir, finalDir)

        assertTrue("finalDir should exist", finalDir.exists())
        assertEquals("new-data", File(finalDir, "dict.sqlite").readText())
        assertFalse("tmpDir should be gone", tmpDir.exists())
        assertFalse("backup should be gone", dir("final.old").exists())
    }

    @Test fun `swap works for first install (no existing finalDir)`() {
        val tmpDir = dir("tmp")
        val finalDir = dir("final")
        createPack(tmpDir, "first-install")

        LanguagePackStore.safeSwap(tmpDir, finalDir)

        assertTrue(finalDir.exists())
        assertEquals("first-install", File(finalDir, "dict.sqlite").readText())
        assertFalse(tmpDir.exists())
    }

    // ── Backup cleanup ───────────────────────────────────────────────────

    @Test fun `swap cleans up stale backup from previous failed swap`() {
        val tmpDir = dir("tmp")
        val finalDir = dir("final")
        val staleBackup = dir("final.old")
        createPack(tmpDir, "new-data")
        createPack(finalDir, "current")
        createPack(staleBackup, "stale-backup")

        LanguagePackStore.safeSwap(tmpDir, finalDir)

        assertTrue(finalDir.exists())
        assertEquals("new-data", File(finalDir, "dict.sqlite").readText())
        assertFalse("stale backup should be cleaned up", staleBackup.exists())
    }

    // ── Rollback on failure ──────────────────────────────────────────────

    @Test fun `safeSwap refuses to promote an empty tmpDir and preserves the old pack`() {
        val tmpDir = dir("tmp")
        val finalDir = dir("final")
        tmpDir.mkdirs() // empty — no files to copy
        createPack(finalDir, "old-data")

        // Post-2026-05-22 hardening (Codex adversarial review): safeSwap
        // throws on an empty source rather than silently promoting an
        // empty dir over the previous good install. The existing pack
        // must survive untouched.
        try {
            LanguagePackStore.safeSwap(tmpDir, finalDir)
            fail("expected IllegalStateException for empty tmpDir")
        } catch (e: IllegalStateException) {
            assertTrue(
                "expected 'empty; refusing to promote' message, got: ${e.message}",
                e.message?.contains("empty") == true,
            )
        }

        assertTrue("finalDir should still exist", finalDir.exists())
        assertEquals(
            "old pack data must be untouched",
            "old-data",
            File(finalDir, "dict.sqlite").readText(),
        )
        assertFalse("no backup should be left behind", dir("final.old").exists())
    }

    @Test fun `swap preserves all files in multi-file pack`() {
        val tmpDir = dir("tmp")
        val finalDir = dir("final")
        tmpDir.mkdirs()
        File(tmpDir, "dict.sqlite").writeText("db-content")
        File(tmpDir, "manifest.json").writeText("{\"version\":1}")
        File(tmpDir, "extra.dat").writeText("extra")

        LanguagePackStore.safeSwap(tmpDir, finalDir)

        assertEquals("db-content", File(finalDir, "dict.sqlite").readText())
        assertEquals("{\"version\":1}", File(finalDir, "manifest.json").readText())
        assertEquals("extra", File(finalDir, "extra.dat").readText())
    }
}
