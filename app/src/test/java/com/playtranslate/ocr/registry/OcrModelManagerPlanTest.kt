package com.playtranslate.ocr.registry

import com.playtranslate.language.OcrBackend
import com.playtranslate.language.SourceLangId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure unit tests for `OcrModelManager.plan` — the declarative-reconcile spine.
 * The headline guarantee is shared-pack deletion safety as plain set-math.
 */
class OcrModelManagerPlanTest {

    private val cjk = OcrBackend.Paddle("paddle-rec-cjk")   // shared by ja/zh/en
    private val meiki = OcrBackend.Meiki("meiki-ja")
    private val mlkit = OcrBackend.MLKitJapanese

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
}
