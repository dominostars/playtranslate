package com.playtranslate.language

import android.content.Context
import com.hankcs.hanlp.HanLP
import com.hankcs.hanlp.dictionary.py.Pinyin
import com.playtranslate.model.DictionaryResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Chinese source-language engine. Uses HanLP's CRF/perceptron segmenter for
 * word-level tokenization (handles both Simplified and Traditional, resolves
 * ambiguity using context, and supports custom dictionaries for game-specific
 * terms via `CustomDictionary.add`). Dictionary lookups go through
 * [ChineseDictionaryManager] against a CC-CEDICT-derived pack.
 *
 * HanLP's first `segment()` call deserializes the CRF model (~1-2s).
 * [preload] triggers this on the background IO thread so the user's first
 * capture doesn't stall.
 */
class ChineseEngine(appContext: Context, langId: SourceLangId = SourceLangId.ZH) : SourceLanguageEngine {

    override val profile: SourceLanguageProfile = SourceLanguageProfiles[langId]

    private val dict: ChineseDictionaryManager = ChineseDictionaryManager.get(appContext)

    override suspend fun preload() {
        withContext(Dispatchers.IO) {
            HanLP.segment("预热")
        }
        dict.preload()
    }

    override suspend fun tokenize(text: String): List<TokenSpan> = withContext(Dispatchers.Default) {
        val terms = HanLP.segment(text)
        terms.filter { isLookupWorthy(it.word) }
            .map { TokenSpan(surface = it.word, lookupForm = it.word, reading = null) }
    }

    override suspend fun lookup(word: String, reading: String?): DictionaryResponse? =
        dict.lookup(word, profile.preferTraditional)

    override fun annotateForHintText(text: String): List<HintTextAnnotation> {
        val pinyinList = HanLP.convertToPinyinList(text)
        val annotations = mutableListOf<HintTextAnnotation>()
        for (i in text.indices) {
            val pinyin = pinyinList.getOrNull(i) ?: continue
            if (pinyin == Pinyin.none5) continue
            val pinyinStr = pinyin.pinyinWithToneMark ?: continue
            annotations.add(HintTextAnnotation(baseStart = i, baseEnd = i + 1, hintText = pinyinStr))
        }
        return annotations
    }

    override fun close() {
        dict.close()
    }

    private fun isLookupWorthy(token: String): Boolean {
        if (token.isBlank()) return false
        if (token.all { it.code <= 0x7F }) return false
        return true
    }
}
