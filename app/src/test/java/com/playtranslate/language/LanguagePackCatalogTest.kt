package com.playtranslate.language

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JUnit tests for [LanguagePackCatalog] Gson parsing. No Android
 * framework dependencies — run under plain JVM without Robolectric.
 */
class LanguagePackCatalogTest {

    private val gson = Gson()

    @Test fun `parses shipped JA-only catalog`() {
        val json = """
            {
              "catalogVersion": 1,
              "packs": {
                "ja": {
                  "display": "Japanese",
                  "script": "CJK_JAPANESE",
                  "bundled": true,
                  "packVersion": 1,
                  "size": 46000000,
                  "licenses": [
                    {
                      "component": "JMdict",
                      "license": "CC-BY-SA-4.0",
                      "attribution": "© EDRDG"
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val catalog = gson.fromJson(json, LanguagePackCatalog::class.java)

        assertEquals(1, catalog.catalogVersion)
        assertEquals(1, catalog.packs.size)
        val ja = catalog.packs["ja"]
        assertNotNull(ja)
        assertEquals("Japanese", ja!!.display)
        assertEquals("CJK_JAPANESE", ja.script)
        assertTrue(ja.bundled)
        assertEquals(1, ja.packVersion)
        assertEquals(46_000_000L, ja.size)
        val licenses = ja.licenses!!
        assertEquals(1, licenses.size)
        assertEquals("JMdict", licenses[0].component)
        assertEquals("CC-BY-SA-4.0", licenses[0].license)
        assertTrue(licenses[0].attribution.isNotBlank())
    }

    @Test fun `missing url and sha256 parse as null`() {
        val json = """
            {
              "catalogVersion": 1,
              "packs": {
                "ja": {
                  "display": "Japanese",
                  "script": "CJK_JAPANESE",
                  "bundled": true,
                  "packVersion": 1,
                  "size": 1
                }
              }
            }
        """.trimIndent()

        val catalog = gson.fromJson(json, LanguagePackCatalog::class.java)
        val ja = catalog.packs["ja"]!!
        assertNull(ja.url)
        assertNull(ja.sha256)
        assertNull(ja.coverageNote)
        // Gson bypasses Kotlin defaults, so an absent `licenses` array
        // deserializes to null rather than the emptyList() default. Callers
        // that copy licenses into a manifest coalesce via `.orEmpty()`.
        assertNull(ja.licenses)
    }

    @Test fun `empty packs map parses cleanly`() {
        val json = """{"catalogVersion": 1, "packs": {}}"""
        val catalog = gson.fromJson(json, LanguagePackCatalog::class.java)
        assertEquals(1, catalog.catalogVersion)
        assertTrue(catalog.packs.isEmpty())
    }

    @Test fun `downloaded pack entry has url and sha256`() {
        val json = """
            {
              "catalogVersion": 2,
              "packs": {
                "zh": {
                  "display": "Chinese",
                  "script": "CJK_CHINESE",
                  "bundled": false,
                  "packVersion": 3,
                  "size": 20971520,
                  "url": "https://example.com/zh.zip",
                  "sha256": "abc123"
                }
              }
            }
        """.trimIndent()

        val catalog = gson.fromJson(json, LanguagePackCatalog::class.java)
        val zh = catalog.packs["zh"]!!
        assertEquals(false, zh.bundled)
        assertEquals("https://example.com/zh.zip", zh.url)
        assertEquals("abc123", zh.sha256)
    }

    @Test fun `MultiFile entry parses files array round-trip`() {
        // MultiFile entries (e.g. the HyMt backend pointing at wangjazz's
        // individual HF files) replace top-level url/sha256 with a `files`
        // list. Verify each per-file field round-trips through Gson.
        val json = """
            {
              "catalogVersion": 1,
              "packs": {
                "engine-hymt-test": {
                  "display": "HyMt test",
                  "type": "engine",
                  "script": "",
                  "bundled": false,
                  "packVersion": 1,
                  "size": 1100,
                  "files": [
                    {
                      "path": "config.json",
                      "url": "https://huggingface.co/example/repo/resolve/main/config.json",
                      "size": 100,
                      "sha256": "${"a".repeat(64)}"
                    },
                    {
                      "path": "llm.mnn",
                      "url": "https://huggingface.co/example/repo/resolve/main/llm.mnn",
                      "size": 1000,
                      "sha256": "${"b".repeat(64)}"
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val catalog = gson.fromJson(json, LanguagePackCatalog::class.java)
        val entry = catalog.packs["engine-hymt-test"]!!
        assertNull(entry.url)
        assertNull(entry.sha256)
        val files = entry.files!!
        assertEquals(2, files.size)
        assertEquals("config.json", files[0].path)
        assertEquals(100L, files[0].size)
        assertEquals("a".repeat(64), files[0].sha256)
        assertTrue(files[0].url.startsWith("https://"))
        assertEquals("llm.mnn", files[1].path)
        assertEquals(1000L, files[1].size)
    }

    @Test fun `absent files field is null — backward compat with legacy entries`() {
        // Existing Qwen-MNN / Gemma-E2B entries don't set `files`. Verify
        // the schema extension doesn't break legacy parsing.
        val json = """
            {
              "catalogVersion": 1,
              "packs": {
                "legacy-zip": {
                  "display": "Legacy zip",
                  "script": "",
                  "bundled": false,
                  "packVersion": 1,
                  "size": 100,
                  "url": "https://example.com/x.zip",
                  "sha256": "${"c".repeat(64)}",
                  "extract": true
                }
              }
            }
        """.trimIndent()
        val catalog = gson.fromJson(json, LanguagePackCatalog::class.java)
        val entry = catalog.packs["legacy-zip"]!!
        assertNull(entry.files)
        assertEquals("https://example.com/x.zip", entry.url)
        assertEquals(true, entry.extract)
    }

    @Test fun `parser accepts both files and url — downstream validator rejects the combo`() {
        // Gson is happy with both fields populated; the
        // hasShippableCatalogEntry() validator on the model-helper side
        // is what rejects the combo to surface the misconfig as
        // "row hidden" rather than silent acceptance.
        val json = """
            {
              "catalogVersion": 1,
              "packs": {
                "combo": {
                  "display": "Combo",
                  "script": "",
                  "bundled": false,
                  "packVersion": 1,
                  "size": 100,
                  "url": "https://example.com/x.zip",
                  "sha256": "${"d".repeat(64)}",
                  "files": [
                    {
                      "path": "a.bin",
                      "url": "https://example.com/a.bin",
                      "size": 100,
                      "sha256": "${"e".repeat(64)}"
                    }
                  ]
                }
              }
            }
        """.trimIndent()
        val catalog = gson.fromJson(json, LanguagePackCatalog::class.java)
        val entry = catalog.packs["combo"]!!
        assertNotNull(entry.url)
        assertNotNull(entry.files)
        // Both fields survive parsing; the runtime validator (not Gson) gates.
    }
}
