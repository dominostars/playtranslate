package com.playtranslate.translation

import com.google.mlkit.nl.translate.TranslateLanguage
import com.playtranslate.language.SourceLangId
import com.playtranslate.translation.OfflineModelReclaimer.InstallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [OfflineModelReclaimer]'s pure decision layer. Plain JUnit — no
 * Android classes touched, no Robolectric (mirrors [com.playtranslate.language.LanguageTest],
 * which already loads [com.playtranslate.language.SourceLanguageProfiles] this way).
 * The Context gather, GMS calls, and provider eviction are device territory.
 */
class OfflineModelReclaimerTest {

    private fun state(
        sources: Set<SourceLangId> = emptySet(),
        targets: Set<String> = emptySet(),
        selectedSource: SourceLangId = SourceLangId.JA,
        selectedTarget: String = "en",
    ) = InstallState(sources, targets, selectedSource, selectedTarget)

    @Test fun `ZH and ZH_HANT collapse to a single zh code`() {
        val s = state(
            sources = setOf(SourceLangId.ZH, SourceLangId.ZH_HANT),
            selectedSource = SourceLangId.ZH,
        )
        assertEquals(setOf("zh"), OfflineModelReclaimer.sourceCodes(s))
    }

    @Test fun `distinct sources map to distinct codes`() {
        val s = state(sources = setOf(SourceLangId.JA, SourceLangId.KO), selectedSource = SourceLangId.JA)
        assertEquals(setOf("ja", "ko"), OfflineModelReclaimer.sourceCodes(s))
    }

    @Test fun `neededMlKit always contains English`() {
        val s = state(
            sources = setOf(SourceLangId.JA),
            targets = setOf("es"),
            selectedSource = SourceLangId.JA,
            selectedTarget = "es",
        )
        assertTrue(TranslateLanguage.ENGLISH in OfflineModelReclaimer.neededMlKit(s))
    }

    @Test fun `target survives a source delete`() {
        // JA was both a source and a target; user deletes the JA source pack, so
        // installedSourceIds no longer has JA but installedTargetCodes still does.
        val afterDelete = state(
            sources = emptySet(),
            targets = setOf("ja"),
            selectedSource = SourceLangId.KO,
            selectedTarget = "ja",
        )
        val needed = OfflineModelReclaimer.neededMlKit(afterDelete)
        assertTrue("ja" in needed)
        // Even though "ja" is downloaded and no longer a source, it is not an orphan.
        val orphans = OfflineModelReclaimer.mlKitOrphans(downloaded = setOf("ja", "ko"), needed = needed)
        assertFalse("ja" in orphans)
    }

    @Test fun `neededBergamotDirs builds xx-en and en-xx hops and never emits an en dir`() {
        val s = state(
            sources = setOf(SourceLangId.JA),
            targets = setOf("es"),
            selectedSource = SourceLangId.JA,
            selectedTarget = "es",
        )
        val dirs = OfflineModelReclaimer.neededBergamotDirs(s)
        assertTrue("ja-en" in dirs)
        assertTrue("en-es" in dirs)
        assertFalse(dirs.any { it == "en-en" })
    }

    @Test fun `mlKitOrphans is downloaded minus needed`() {
        assertEquals(
            setOf("fr"),
            OfflineModelReclaimer.mlKitOrphans(downloaded = setOf("ja", "fr"), needed = setOf("ja", "en")),
        )
    }

    @Test fun `bergamotOrphans is onDisk minus needed`() {
        assertEquals(
            setOf("fr-en"),
            OfflineModelReclaimer.bergamotOrphans(onDisk = setOf("ja-en", "fr-en"), neededDirs = setOf("ja-en")),
        )
    }
}
