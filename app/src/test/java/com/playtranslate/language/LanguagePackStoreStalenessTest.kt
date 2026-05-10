package com.playtranslate.language

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Tests for [LanguagePackStore.staleInstalledPacks] — the launch-time
 * staleness scan that drives the upgrade-prompt overlay.
 *
 * Reads the real bundled `langpack_catalog.json` (via Robolectric's asset
 * pipeline) and writes fake on-disk manifests under
 * `noBackupFilesDir/langpacks/<id>/manifest.json` to simulate various
 * installed-pack states.
 *
 * The catalog's current state (post-this-PR): `ja` has packVersion=2;
 * other source packs and target packs are at packVersion=1. Tests assume
 * `ja` is at v2 and treat it as the "version mismatch when on-disk is at
 * v1" case.
 */
@RunWith(RobolectricTestRunner::class)
class LanguagePackStoreStalenessTest {

    private lateinit var ctx: Context
    private lateinit var langpacksRoot: File

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        langpacksRoot = LanguagePackStore.rootDir(ctx)
        // Clean slate per-test so writeManifest in one test doesn't bleed
        // into the next via Robolectric's persistent file system.
        if (langpacksRoot.exists()) langpacksRoot.deleteRecursively()
        langpacksRoot.mkdirs()
    }

    @Test fun `empty list when no packs installed`() {
        val stale = LanguagePackStore.staleInstalledPacks(ctx)
        assertTrue(
            "Never-installed packs must NOT appear in stale list (per spec)",
            stale.isEmpty(),
        )
    }

    @Test fun `ja pack at v1 on disk vs catalog v2 -- stale`() {
        writeManifest("ja", packVersion = 1)
        val stale = LanguagePackStore.staleInstalledPacks(ctx)
        val ja = stale.firstOrNull { it.catalogKey == "ja" }
        assertNotNull("Expected ja pack to be flagged stale", ja)
        assertEquals(PackKind.SOURCE, ja!!.kind)
        assertEquals(SourceLangId.JA, ja.sourceLangId)
        // Per design contract: sourceLangId is ALWAYS the packId variant
        // (so releaseForPack/dirFor see the canonical pack).
        assertEquals(SourceLangId.JA, ja.sourceLangId!!.packId)
        assertNull(ja.targetLangCode)
    }

    @Test fun `ja pack at v2 on disk vs catalog v2 -- not stale`() {
        writeManifest("ja", packVersion = 2)
        // Also write a minimal SQLite to satisfy the schema-current
        // corruption backstop in the source path.
        writeJaSchemaCurrentDb()
        val stale = LanguagePackStore.staleInstalledPacks(ctx)
        assertFalse(
            "Same-version pack must not be stale",
            stale.any { it.catalogKey == "ja" },
        )
    }

    @Test fun `target pack at older version is stale`() {
        // Force "target-fr" to look stale by writing manifest with version 0
        // (less than the catalog's packVersion).
        writeManifest("target-fr", packVersion = 0)
        val stale = LanguagePackStore.staleInstalledPacks(ctx)
        val tf = stale.firstOrNull { it.catalogKey == "target-fr" }
        assertNotNull("target-fr should be stale", tf)
        assertEquals(PackKind.TARGET, tf!!.kind)
        assertEquals("fr", tf.targetLangCode)
        assertNull(tf.sourceLangId)
    }

    @Test fun `mixed stale packs returned together`() {
        writeManifest("ja", packVersion = 1)
        writeManifest("target-fr", packVersion = 0)
        val stale = LanguagePackStore.staleInstalledPacks(ctx)
        assertTrue(stale.any { it.catalogKey == "ja" })
        assertTrue(stale.any { it.catalogKey == "target-fr" })
    }

    @Test fun `pack with no on-disk manifest is treated as never-installed`() {
        // Create the directory but no manifest.json.
        val dir = LanguagePackStore.dirFor(ctx, SourceLangId.JA)
        dir.mkdirs()
        File(dir, "dict.sqlite").writeBytes(byteArrayOf(0))
        val stale = LanguagePackStore.staleInstalledPacks(ctx)
        assertFalse(
            "Manifest absence treated as not-installed; no prompt",
            stale.any { it.catalogKey == "ja" },
        )
    }

    @Test fun `engine packs are filtered out (would crash SourceLangId mapping)`() {
        // Engine packs are bundled=false in the catalog with type="engine".
        // If we ever write a manifest under their slot, they must STILL be
        // filtered out so SourceLangId.fromCode("engine-translategemma")
        // doesn't crash and so we don't try to install them via
        // LanguagePackStore.install (which they don't go through).
        writeManifest("engine-translategemma", packVersion = 0, dirName = "engine-translategemma")
        writeManifest("engine-qwen-1-5b", packVersion = 0, dirName = "engine-qwen-1-5b")
        val stale = LanguagePackStore.staleInstalledPacks(ctx)
        assertFalse(
            "Engine packs must never appear in stale list",
            stale.any { it.catalogKey.startsWith("engine-") },
        )
    }

    @Test fun `zh source pack returns ZH packId not a variant`() {
        // Regression for ZH/ZH_HANT collapse. The catalog key is "zh" and
        // ZH.packId == ZH. The ZH_HANT.packId override would collapse to
        // ZH if ever introduced. Either way, the returned StalePack carries
        // the ZH (packId variant), so releaseForPack and dirFor see the
        // canonical pack.
        writeManifest("zh", packVersion = 0, dirName = "zh")
        val stale = LanguagePackStore.staleInstalledPacks(ctx)
        val zh = stale.firstOrNull { it.catalogKey == "zh" }
        assertNotNull(zh)
        assertEquals(SourceLangId.ZH, zh!!.sourceLangId)
        assertEquals(SourceLangId.ZH, zh.sourceLangId!!.packId)
    }

    @Test fun `unparseable manifest json is treated as not-installed`() {
        // Synthetic corruption: garbage in the manifest file. The scan must
        // not throw.
        val manifest = LanguagePackStore.manifestFileFor(ctx, SourceLangId.JA)
        manifest.parentFile?.mkdirs()
        manifest.writeText("definitely not json {{{")
        val stale = LanguagePackStore.staleInstalledPacks(ctx)
        assertFalse(
            "Garbage manifest skipped, not flagged",
            stale.any { it.catalogKey == "ja" },
        )
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Write a minimal manifest.json under the pack directory. */
    private fun writeManifest(
        catalogKey: String,
        packVersion: Int,
        dirName: String = catalogKey,
    ) {
        val packDir = if (catalogKey.startsWith("target-")) {
            File(langpacksRoot, dirName)
        } else {
            File(langpacksRoot, dirName)
        }
        packDir.mkdirs()
        val json = """
            {
              "langId": "${dirName.removePrefix("target-")}",
              "schemaVersion": 1,
              "packVersion": $packVersion,
              "appMinVersion": 0,
              "files": [{"path": "dict.sqlite", "size": 0, "sha256": null}],
              "totalSize": 0,
              "licenses": []
            }
        """.trimIndent()
        File(packDir, "manifest.json").writeText(json)
    }

    /** Create a minimal ja dict.sqlite that passes
     *  [com.playtranslate.dictionary.JmdictSchemaProbe.isCurrent] so the
     *  source-pack corruption backstop doesn't false-positive in the
     *  "not stale" tests. */
    private fun writeJaSchemaCurrentDb() {
        val dbFile = LanguagePackStore.dictDbFor(ctx, SourceLangId.JA)
        dbFile.parentFile?.mkdirs()
        android.database.sqlite.SQLiteDatabase
            .openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL("CREATE TABLE entry (id INTEGER PRIMARY KEY, is_common INTEGER, freq_score INTEGER)")
            db.execSQL(
                "CREATE TABLE headword (entry_id INTEGER, position INTEGER, text TEXT, " +
                    "ke_pri TEXT DEFAULT '', rank_score INTEGER DEFAULT 0)"
            )
            db.execSQL(
                "CREATE TABLE reading (entry_id INTEGER, position INTEGER, text TEXT, " +
                    "no_kanji INTEGER, re_pri TEXT, freq_score INTEGER, " +
                    "re_inf TEXT, rank_score INTEGER, uk_applicable INTEGER)"
            )
            db.execSQL("CREATE TABLE sense (entry_id INTEGER, position INTEGER, pos TEXT, glosses TEXT, misc TEXT)")
            db.execSQL("CREATE TABLE kanjidic (literal TEXT PRIMARY KEY, on_readings TEXT, kun_readings TEXT, jlpt INTEGER, grade INTEGER, stroke_count INTEGER)")
            db.execSQL("CREATE TABLE kanji_meaning (literal TEXT, lang TEXT, meanings TEXT, PRIMARY KEY(literal, lang))")
        }
    }
}
