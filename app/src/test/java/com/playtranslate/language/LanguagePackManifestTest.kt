package com.playtranslate.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pure JUnit tests for [LanguagePackManifestIO] round-trip. No Android
 * framework dependencies.
 */
class LanguagePackManifestTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `write then read returns an equal manifest`() {
        val manifest = LanguagePackManifest(
            langId = "ja",
            schemaVersion = 1,
            packVersion = 1,
            appMinVersion = 5,
            files = listOf(ManifestFile(path = "dict.sqlite", size = 46_000_000L, sha256 = null)),
            totalSize = 46_000_000L,
            licenses = listOf(
                ManifestLicense("JMdict", "CC-BY-SA-4.0", "© EDRDG"),
                ManifestLicense("KANJIDIC2", "CC-BY-SA-4.0", "© EDRDG"),
            ),
        )

        val file = File(tmp.root, "manifest.json")
        LanguagePackManifestIO.write(file, manifest)
        val readBack = LanguagePackManifestIO.read(file)

        assertEquals(manifest, readBack)
    }

    @Test fun `read on nonexistent file returns null`() {
        val file = File(tmp.root, "absent.json")
        assertNull(LanguagePackManifestIO.read(file))
    }

    @Test fun `read on malformed JSON returns null`() {
        val file = File(tmp.root, "bogus.json")
        file.writeText("not json at all")
        assertNull(LanguagePackManifestIO.read(file))
    }

    @Test fun `write creates parent directories`() {
        val file = File(tmp.root, "nested/subdir/manifest.json")
        val manifest = LanguagePackManifest(
            langId = "ja",
            schemaVersion = 1,
            packVersion = 1,
            appMinVersion = 5,
            files = emptyList(),
            totalSize = 0L,
            licenses = emptyList(),
        )
        LanguagePackManifestIO.write(file, manifest)
        assert(file.exists())
    }
}
