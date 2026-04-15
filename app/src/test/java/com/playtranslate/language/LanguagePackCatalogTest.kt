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
}
