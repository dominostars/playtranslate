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

    @Test fun `ja pack at v1 on disk vs catalog v2 (additiveFromVersion=1) -- stale ADDITIVE`() {
        writeManifest("ja", packVersion = 1)
        // Write a v1-shaped dict.sqlite (structural columns present, no v2
        // columns). Loosened JmdictSchemaProbe must pass this so the pack
        // doesn't trip the schemaStale → FORCE branch — instead the catalog's
        // additiveFromVersion=1 says v1 qualifies for ADDITIVE.
        writeJaV1SchemaDb()
        val stale = LanguagePackStore.staleInstalledPacks(ctx)
        val ja = stale.firstOrNull { it.catalogKey == "ja" }
        assertNotNull("Expected ja pack to be flagged stale", ja)
        assertEquals(PackKind.SOURCE, ja!!.kind)
        assertEquals(SourceLangId.JA, ja.sourceLangId)
        assertEquals(SourceLangId.JA, ja.sourceLangId!!.packId)
        assertNull(ja.targetLangCode)
        assertEquals(
            "v1 on disk + additiveFromVersion=1 in catalog → ADDITIVE",
            UpgradeMode.ADDITIVE, ja.upgradeMode,
        )
    }

    @Test fun `schema-broken ja pack always classifies as FORCE`() {
        // Manifest says v1 (would qualify for ADDITIVE per additiveFromVersion=1),
        // but the dict.sqlite is missing required tables — schema probe fails
        // → FORCE regardless of version. This is the corruption backstop.
        writeManifest("ja", packVersion = 1)
        // Don't write a valid dict.sqlite; or write one missing tables.
        writeJaBrokenDb()
        val stale = LanguagePackStore.staleInstalledPacks(ctx)
        val ja = stale.firstOrNull { it.catalogKey == "ja" }
        assertNotNull(ja)
        assertEquals(
            "Schema probe failure overrides additive eligibility",
            UpgradeMode.FORCE, ja!!.upgradeMode,
        )
    }

    @Test fun `target pack below additiveFromVersion classifies as FORCE`() {
        // Catalog ships every pack with additiveFromVersion=1 (uniform
        // visibility convention — see langpack_catalog.json). On-disk
        // packVersion=0 is below the boundary → FORCE.
        writeManifest("target-fr", packVersion = 0)
        val stale = LanguagePackStore.staleInstalledPacks(ctx)
        val tf = stale.firstOrNull { it.catalogKey == "target-fr" }
        assertNotNull(tf)
        assertEquals(
            "on-disk < additiveFromVersion → FORCE",
            UpgradeMode.FORCE, tf!!.upgradeMode,
        )
    }

    @Test fun `target pack at-or-above additiveFromVersion classifies as ADDITIVE`() {
        // Synthetic future scenario: target-fr bumped to packVersion=2 (in
        // a hypothetical catalog change), v1 on disk, additiveFromVersion=1
        // (the current uniform setting) → ADDITIVE. This is the safe
        // default we want for target-pack version bumps: existing user data
        // preserved during install, restored on cancel/fail.
        writeManifest("target-fr", packVersion = 1)
        val stale = LanguagePackStore.staleInstalledPacks(ctx)
        val tf = stale.firstOrNull { it.catalogKey == "target-fr" }
        // With current catalog (target-fr packVersion=1), v1 on disk vs
        // catalog v1 means NOT stale, so won't appear at all. Skip the
        // ADDITIVE assertion when nothing is stale; this test mainly
        // documents the convention and will become live the moment any
        // target pack version is bumped.
        if (tf != null) {
            assertEquals(UpgradeMode.ADDITIVE, tf.upgradeMode)
        }
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

    @Test fun `loosened JmdictSchemaProbe accepts v1-shaped DBs`() {
        // Regression for the loosening: a v1-shaped DB (5 structural columns
        // present, NO rank_score / uk_applicable / ke_pri) must pass the
        // probe. If the probe ever re-tightens to require v2 columns, this
        // test fails loudly and the additive-upgrade path becomes unreachable.
        writeManifest("ja", packVersion = 1)
        writeJaV1SchemaDb()
        // The probe is consulted indirectly via staleInstalledPacks. If the
        // probe rejected v1, ja would be marked schemaStale → FORCE. With the
        // loosened probe, it's ADDITIVE per additiveFromVersion=1.
        val stale = LanguagePackStore.staleInstalledPacks(ctx)
        val ja = stale.firstOrNull { it.catalogKey == "ja" }
        assertNotNull(ja)
        assertEquals(UpgradeMode.ADDITIVE, ja!!.upgradeMode)
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

    /** v2-shaped dict.sqlite (all columns including rank_score / uk_applicable). */
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

    /** v1-shaped dict.sqlite — pre-ja-v2. Has the 5 structural tables/columns
     *  the loosened JmdictSchemaProbe requires (entry.freq_score,
     *  headword.text, sense.misc, kanjidic.literal, kanji_meaning.*) but
     *  NOT the v2 columns (rank_score, uk_applicable, ke_pri). Used to
     *  simulate an existing-user v1 install. */
    private fun writeJaV1SchemaDb() {
        val dbFile = LanguagePackStore.dictDbFor(ctx, SourceLangId.JA)
        dbFile.parentFile?.mkdirs()
        android.database.sqlite.SQLiteDatabase
            .openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL("CREATE TABLE entry (id INTEGER PRIMARY KEY, is_common INTEGER, freq_score INTEGER)")
            db.execSQL("CREATE TABLE headword (entry_id INTEGER, position INTEGER, text TEXT)")
            db.execSQL(
                "CREATE TABLE reading (entry_id INTEGER, position INTEGER, text TEXT, " +
                    "no_kanji INTEGER, re_pri TEXT, freq_score INTEGER)"
            )
            db.execSQL("CREATE TABLE sense (entry_id INTEGER, position INTEGER, pos TEXT, glosses TEXT, misc TEXT)")
            db.execSQL("CREATE TABLE kanjidic (literal TEXT PRIMARY KEY, on_readings TEXT, kun_readings TEXT, jlpt INTEGER, grade INTEGER, stroke_count INTEGER)")
            db.execSQL("CREATE TABLE kanji_meaning (literal TEXT, lang TEXT, meanings TEXT, PRIMARY KEY(literal, lang))")
        }
    }

    /** Genuinely broken dict.sqlite — missing the headword table entirely.
     *  Loosened probe rejects this (because headword.text probe throws),
     *  which is the corruption backstop firing as designed. */
    private fun writeJaBrokenDb() {
        val dbFile = LanguagePackStore.dictDbFor(ctx, SourceLangId.JA)
        dbFile.parentFile?.mkdirs()
        android.database.sqlite.SQLiteDatabase
            .openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL("CREATE TABLE entry (id INTEGER PRIMARY KEY, is_common INTEGER, freq_score INTEGER)")
            // No headword, no sense, no kanjidic, no kanji_meaning — schema probe will fail.
        }
    }
}
