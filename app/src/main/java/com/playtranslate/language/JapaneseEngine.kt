package com.playtranslate.language

import android.content.Context
import com.playtranslate.dictionary.Deinflector
import com.playtranslate.dictionary.DictionaryManager
import com.playtranslate.model.DictionaryResponse

/**
 * Japanese source-language engine. Thin forwarder over the existing
 * [DictionaryManager] singleton and [Deinflector] object — there's no new
 * runtime state here, just an interface-matching façade that Phase 1+ can
 * route calls through without touching the underlying implementation.
 *
 * Note: [close] is a no-op. [DictionaryManager] is a process-scoped singleton
 * that survives engine lifecycle changes; closing the dict here would break
 * any other caller that still reaches into [DictionaryManager.get] directly.
 */
class JapaneseEngine(appContext: Context) : SourceLanguageEngine {

    override val profile: SourceLanguageProfile = SourceLanguageProfiles[SourceLangId.JA]

    private val dict: DictionaryManager = DictionaryManager.get(appContext)

    override suspend fun preload() {
        dict.preload()
        Deinflector.preload()
    }

    override suspend fun tokenize(text: String): List<TokenSpan> =
        dict.tokenizeWithSurfaces(text).map {
            TokenSpan(surface = it.surface, lookupForm = it.lookupForm, reading = it.reading)
        }

    override suspend fun lookup(word: String, reading: String?): DictionaryResponse? =
        dict.lookup(word, reading)

    override suspend fun lookupCharacter(literal: Char): CharacterDetail? =
        dict.lookupKanji(literal)

    override fun annotateForHintText(text: String): List<HintTextAnnotation> =
        dict.tokenizeForFurigana(text).map {
            HintTextAnnotation(
                baseStart = it.startOffset,
                baseEnd = it.endOffset,
                hintText = it.reading,
            )
        }

    override fun close() {
        // Intentionally empty — see class doc.
    }
}
