package com.playtranslate.ocr.registry

import com.playtranslate.language.OcrBackend
import com.playtranslate.language.SourceLangId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for `OcrModelManager.plan` — the declarative-reconcile spine.
 * The headline guarantee is shared-pack deletion safety as plain set-math.
 */
class OcrModelManagerPlanTest {

    private val cjk = OcrBackend.Paddle("paddle-rec-cjk")   // shared by ja/zh/en
    private val meiki = OcrBackend.Meiki("meiki-ja")
    private val mlkit = OcrBackend.MLKitJapanese
    private val cyr = OcrBackend.Paddle("paddle-rec-cyrillic")   // Russian: no ML Kit floor
    private val latin = OcrBackend.Paddle("paddle-rec-latin")

    private fun plan(langs: Map<SourceLangId, OcrBackend>, installed: Set<String>) =
        OcrModelManager.plan(langs.keys, { langs.getValue(it) }, installed)

    @Test fun emptyWhenNoLanguages() {
        val p = OcrModelManager.plan(emptySet(), { error("unused") }, emptySet())
        assertEquals(emptySet<String>(), p.required)
        assertEquals(emptySet<String>(), p.toDownload)
        assertEquals(emptySet<String>(), p.toDelete)
    }

    @Test fun downloadsRequiredForNewLanguages() {
        val p = plan(mapOf(SourceLangId.JA to meiki, SourceLangId.EN to cjk), installed = emptySet())
        assertEquals(setOf("meiki-ja", "paddle-rec-cjk"), p.toDownload)
        assertEquals(emptySet<String>(), p.toDelete)
    }

    /** The exact requirement: deleting one language must NOT delete a pack another
     *  installed language still uses. en+ja both used paddle-rec-cjk; en removed. */
    @Test fun sharedPackSurvivesWhenAnotherLanguageStillUsesIt() {
        val p = plan(mapOf(SourceLangId.JA to cjk), installed = setOf("paddle-rec-cjk"))
        assertEquals(setOf("paddle-rec-cjk"), p.required)
        assertEquals(emptySet<String>(), p.toDelete) // ja still needs it
    }

    @Test fun switchingBackendOrphansTheOldPackOnly() {
        // ja switched Meiki→Paddle while en already Paddle → meiki-ja orphaned, cjk kept.
        val p = plan(
            mapOf(SourceLangId.JA to cjk, SourceLangId.EN to cjk),
            installed = setOf("meiki-ja", "paddle-rec-cjk"),
        )
        assertEquals(setOf("paddle-rec-cjk"), p.required)
        assertEquals(setOf("meiki-ja"), p.toDelete)
        assertEquals(emptySet<String>(), p.toDownload) // cjk already present
    }

    @Test fun mlKitNeedsNoPacks() {
        val p = plan(mapOf(SourceLangId.JA to mlkit), installed = emptySet())
        assertEquals(emptySet<String>(), p.required)
        assertEquals(emptySet<String>(), p.toDownload)
    }

    /** A no-floor language whose backend isn't deliverable on this device
     *  resolves to a null selectedBackend; plan must treat it as requiring no
     *  packs (and not crash on the null). */
    @Test fun nullBackendContributesNoRequiredPacks() {
        val p = OcrModelManager.plan(
            setOf(SourceLangId.RU, SourceLangId.JA),
            { id -> if (id == SourceLangId.RU) null else meiki },
            installedPacks = setOf("meiki-ja"),
        )
        assertEquals(setOf("meiki-ja"), p.required) // RU adds nothing
        assertEquals(emptySet<String>(), p.toDownload)
        assertEquals(emptySet<String>(), p.toDelete)
    }

    // ── resolveSelectedBackend: the OCR token must stay NON-load-bearing ──────
    // A no-floor language (Russian) whose token is missing/stale must still
    // resolve to its installed recognizer, or currentPlan would orphan the pack
    // and engineForSelected would drop to the empty engine.

    @Test fun noFloorLangResolvesToItsOnlyBackendWhenTokenMissingOrStale() {
        assertEquals(cyr, OcrModelManager.resolveSelectedBackend(listOf(cyr), token = null, mlKitFloor = null))
        assertEquals(cyr, OcrModelManager.resolveSelectedBackend(listOf(cyr), token = "stale", mlKitFloor = null))
    }

    @Test fun storedTokenWinsWhenItMatchesAvailable() {
        assertEquals(latin, OcrModelManager.resolveSelectedBackend(listOf(latin, mlkit), token = "paddle", mlKitFloor = mlkit))
    }

    @Test fun flooredLangFallsBackToMlKitFloorWhenTokenMissing() {
        // Unchanged for floored languages: mlKitFloor short-circuits the no-floor fallback.
        assertEquals(mlkit, OcrModelManager.resolveSelectedBackend(listOf(latin, mlkit), token = null, mlKitFloor = mlkit))
    }

    @Test fun noDeliverableBackendAndNoFloorIsNull() {
        // No-floor language on a 32-bit device: nothing deliverable → null.
        assertEquals(null, OcrModelManager.resolveSelectedBackend(emptyList(), token = null, mlKitFloor = null))
    }

    // ── requiredOcrReady: completeness must imply the engine can load ────────
    // A no-floor language must NOT read as OCR-ready on a device that can't run
    // its only backend, even if the pack files are present — else isFullyInstalled
    // is true while engineForSelected yields the empty engine.

    @Test fun noFloorOnIncompatibleDeviceIsNotReadyEvenWithPackOnDisk() {
        // mnnAvailable=false → availableBackends empty → selectedBackend null,
        // even though the pack files are on disk (isInstalled = true).
        assertFalse(OcrModelManager.requiredOcrReady(hasFloor = false, selected = null, isInstalled = { true }))
    }

    @Test fun noFloorReadyOnlyWhenResolvedBackendsPacksInstalled() {
        assertFalse(OcrModelManager.requiredOcrReady(hasFloor = false, selected = cyr, isInstalled = { false }))
        assertTrue(OcrModelManager.requiredOcrReady(hasFloor = false, selected = cyr, isInstalled = { true }))
    }

    @Test fun flooredLangIsAlwaysReady() {
        assertTrue(OcrModelManager.requiredOcrReady(hasFloor = true, selected = null, isInstalled = { false }))
    }

    // ── decideOcrMigration: the launch-time grandfathered-OCR decision table ──
    // Floored source + no stored choice + a better-than-floor default ⇒ ADOPT when
    // its packs are already on disk (token switch, no download) else OFFER_DOWNLOAD.
    // Everything else is NONE.
    private fun migrate(choice: Boolean, floor: OcrBackend?, best: OcrBackend?, installed: Boolean) =
        OcrModelManager.decideOcrMigration(choice, floor, best, isInstalled = { installed })

    @Test fun migrationNoneWhenUserAlreadyChose() {
        // An explicit choice (incl. an explicit ML Kit pick) is never overridden.
        assertEquals(OcrModelManager.OcrMigration.NONE, migrate(choice = true, floor = mlkit, best = meiki, installed = false))
    }

    @Test fun migrationNoneForNoFloorSource() {
        // No ML Kit floor (Russian): the non-load-bearing token already resolves to
        // the lone recognizer, so there is nothing to migrate.
        assertEquals(OcrModelManager.OcrMigration.NONE, migrate(choice = false, floor = null, best = cyr, installed = true))
    }

    @Test fun migrationNoneWhenDefaultIsTheFloor() {
        // Vietnamese/Turkish: the floor IS the top default.
        assertEquals(OcrModelManager.OcrMigration.NONE, migrate(choice = false, floor = mlkit, best = mlkit, installed = false))
    }

    @Test fun migrationNoneWhenNoDeliverableBackend() {
        assertEquals(OcrModelManager.OcrMigration.NONE, migrate(choice = false, floor = mlkit, best = null, installed = false))
    }

    @Test fun migrationNoneWhenBestHasNoPacks() {
        // A pack-less non-floor "best" (no recognizer to fetch or adopt) is a no-op.
        assertEquals(OcrModelManager.OcrMigration.NONE, migrate(choice = false, floor = mlkit, best = OcrBackend.MLKitKorean, installed = false))
    }

    @Test fun migrationAdoptWhenDefaultPackAlreadyInstalled() {
        // The shared recognizer is already on disk (downloaded for another language).
        assertEquals(OcrModelManager.OcrMigration.ADOPT, migrate(choice = false, floor = mlkit, best = cjk, installed = true))
    }

    @Test fun migrationOfferDownloadWhenDefaultPackMissing() {
        assertEquals(OcrModelManager.OcrMigration.OFFER_DOWNLOAD, migrate(choice = false, floor = mlkit, best = meiki, installed = false))
    }
}
