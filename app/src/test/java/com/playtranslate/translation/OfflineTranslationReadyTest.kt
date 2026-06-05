package com.playtranslate.translation

import com.google.mlkit.nl.translate.TranslateLanguage
import com.playtranslate.language.SourceLangId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests `OfflineModelReclaimer.isOfflineTranslationReady` — the predicate behind
 * the Settings "Download offline models" card. It must mirror the translation
 * waterfall: count an offline backend only when it's actually USABLE (enabled +
 * installed), not merely when its files are on disk, and treat ML Kit specially
 * (usable for every pair, offline-ready only once its models are downloaded).
 *
 * RU's translationCode is "ru"; the pivot is "en", so the needed ML Kit set is
 * {"ru","en"}.
 */
class OfflineTranslationReadyTest {

    private val mlkit = FakeOfflineBackend(id = MlKitBackend.ID, usable = true)
    private fun bergamot(usable: Boolean) = FakeOfflineBackend(id = "bergamot", usable = usable)
    private fun llm(usable: Boolean) = FakeOfflineBackend(id = "qwen_mnn", usable = usable)

    private fun ready(backends: List<TranslationBackend>, mlKitCodes: Set<String>): Boolean = runBlocking {
        OfflineModelReclaimer.isOfflineTranslationReady(
            sourceId = SourceLangId.RU,
            targetLang = TranslateLanguage.ENGLISH,
            offlineBackends = backends,
            downloadedMlKit = { mlKitCodes },
        )
    }

    @Test fun disabledBergamotWithNoMlKitModelsIsNotReady() {
        // The Codex case: Bergamot files on disk but the backend is disabled
        // (isUsable=false), ML Kit usable but its models aren't downloaded → the
        // card must SHOW (predicate false). The old code counted Bergamot's files
        // and wrongly returned true.
        assertFalse(ready(listOf(bergamot(usable = false), mlkit), mlKitCodes = emptySet()))
    }

    @Test fun enabledBergamotIsReadyEvenWithoutMlKitModels() {
        assertTrue(ready(listOf(bergamot(usable = true), mlkit), mlKitCodes = emptySet()))
    }

    @Test fun usableOnDeviceLlmIsReady() {
        // No Bergamot, no ML Kit models, but an installed+enabled LLM is an offline
        // path the old predicate ignored (false-positive card).
        assertTrue(ready(listOf(llm(usable = true), mlkit), mlKitCodes = emptySet()))
    }

    @Test fun mlKitWithAllPairModelsDownloadedIsReady() {
        assertTrue(ready(listOf(bergamot(usable = false), mlkit), mlKitCodes = setOf("ru", "en")))
    }

    @Test fun mlKitMissingOnePairModelIsNotReady() {
        // Only the source model present, pivot/target missing → not offline-ready.
        assertFalse(ready(listOf(bergamot(usable = false), mlkit), mlKitCodes = setOf("ru")))
    }

    @Test fun samePairIsTriviallyReady() {
        assertTrue(
            runBlocking {
                OfflineModelReclaimer.isOfflineTranslationReady(
                    sourceId = SourceLangId.RU,
                    targetLang = "ru",
                    offlineBackends = emptyList(),
                    downloadedMlKit = { emptySet() },
                )
            }
        )
    }
}
