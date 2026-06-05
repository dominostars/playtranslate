package com.playtranslate.translation

import com.playtranslate.language.TranslationManagerProvider

/**
 * Offline ML Kit translation backend — the last resort in the waterfall.
 *
 * Delegates to [TranslationManagerProvider] so the dictionary / word-detail
 * path and the waterfall share one set of per-pair `TranslationManager`
 * (and thus underlying `Translator`) instances. [TranslationManagerProvider.getOrCreate]
 * is the pure-infrastructure entry point with no en-null policy.
 *
 * Model download is performed lazily on the first translate() call for a
 * given pair via [com.playtranslate.TranslationManager.ensureModelReady].
 * If the model isn't available and can't be downloaded, the call throws —
 * which is the right thing for a waterfall last-resort: surfacing the
 * failure to the caller is preferable to a silent fire-and-forget warm
 * that swallows download errors.
 */
class MlKitBackend : TranslationBackend {

    companion object {
        /** Stable backend id. Offline-readiness checks reference it to treat ML
         *  Kit specially: usable for every pair, but offline-ready only once its
         *  per-language models are downloaded (unlike Bergamot / on-device LLMs,
         *  whose isUsable already implies the model is on disk). */
        const val ID: BackendId = "mlkit"
    }

    override val id: BackendId = ID
    override val displayName: String = "ML Kit"
    override val priority: Int = 30
    override val requiresInternet: Boolean = false
    override val isDegradedFallback: Boolean = true
    override val qualityStars: StarRating = 1.0f
    override val speedStars: StarRating = 5.0f

    /** The floor of the dictionary definition-gloss waterfall: priority 30 puts
     *  it last among the gloss-capable backends, after Bergamot. See
     *  DefinitionGlossTranslators. */
    override val usableForDefinitionGloss: Boolean = true

    override val status: BackendStatus = BackendStatus.Info("Bundled with the app, used as a fallback")

    override fun isUsable(source: String, target: String): Boolean = true

    override suspend fun translate(text: String, source: String, target: String): String {
        val tm = TranslationManagerProvider.getOrCreate(source, target)
        tm.ensureModelReady()
        return tm.translate(text)
    }

    /** No-op. The shared [TranslationManagerProvider] is owned at app
     *  scope and used by the dictionary path too — closing it from the
     *  backend would yank models out from under unrelated callers. */
    override fun close() {}
}
