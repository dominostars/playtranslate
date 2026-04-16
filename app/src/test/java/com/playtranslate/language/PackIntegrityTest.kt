package com.playtranslate.language

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pure JUnit tests for [PackIntegrity]. No Android classes touched — runs
 * under plain JVM without Robolectric.
 */
class PackIntegrityTest {

    @get:Rule val tmp = TemporaryFolder()

    // ── sha256Hex ─────────────────────────────────────────────────────────

    @Test fun `sha256 of empty file matches canonical value`() = runBlocking {
        val file = tmp.newFile("empty.bin")
        // SHA-256 of zero bytes is well-known.
        val expected = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        assertEquals(expected, PackIntegrity.sha256Hex(file))
    }

    @Test fun `sha256 of known content matches expected`() = runBlocking {
        val file = tmp.newFile("hello.txt")
        file.writeText("hello world")
        // SHA-256("hello world") — well-known value.
        val expected = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"
        assertEquals(expected, PackIntegrity.sha256Hex(file))
    }

    // ── extractZip ────────────────────────────────────────────────────────

    @Test fun `extractZip writes nested files with correct content`() = runBlocking {
        val zipFile = tmp.newFile("pack.zip")
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            addEntry(zos, "manifest.json", "{\"langId\":\"en\"}")
            addEntry(zos, "dict.sqlite", "fake binary content")
            addEntry(zos, "nested/data.txt", "nested value")
        }

        val out = tmp.newFolder("extracted")
        PackIntegrity.extractZip(zipFile, out)

        assertEquals("{\"langId\":\"en\"}", File(out, "manifest.json").readText())
        assertEquals("fake binary content", File(out, "dict.sqlite").readText())
        assertEquals("nested value", File(out, "nested/data.txt").readText())
    }

    @Test fun `extractZip rejects path traversal`() = runBlocking {
        val zipFile = tmp.newFile("evil.zip")
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            addEntry(zos, "../escape.txt", "evil")
            addEntry(zos, "safe.txt", "safe content")
        }

        val out = tmp.newFolder("extracted")
        PackIntegrity.extractZip(zipFile, out)

        // The traversal entry must NOT have been written to the parent.
        val parent = out.parentFile!!
        assertFalse("Path traversal escaped", File(parent, "escape.txt").exists())
        // The safe entry must still land inside out.
        assertTrue("Safe entry missing", File(out, "safe.txt").exists())
        assertEquals("safe content", File(out, "safe.txt").readText())
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun addEntry(zos: ZipOutputStream, name: String, content: String) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(content.toByteArray())
        zos.closeEntry()
    }
}
