package com.playtranslate.model

/**
 * Generic bilingual-dictionary result model. Originally modelled after the
 * Jisho REST API (which is why the shape still looks like a search response),
 * but now produced locally from the on-device dictionary database — nothing
 * here is parsed from JSON, so the old Gson `@SerializedName` annotations
 * have been dropped.
 *
 * These types are intended to be language-agnostic: a [Headword] can be a
 * Japanese kanji form, a Chinese simplified surface, or a Latin lemma;
 * [Sense.targetDefinitions] holds the glosses in the user's chosen target
 * language (English today).
 */
data class DictionaryResponse(
    val entries: List<DictionaryEntry>
)

data class DictionaryEntry(
    val slug: String,
    val isCommon: Boolean?,
    val tags: List<String>,
    val jlpt: List<String>,
    val headwords: List<Headword>,
    val senses: List<Sense>,
    val freqScore: Int = 0
)

/**
 * One written/spoken variant of a dictionary entry. [written] is the visible
 * headword (kanji surface for JA, hanzi for ZH, lemma for Latin); [reading]
 * is the pronunciation hint (hiragana for JA, pinyin for ZH, null for most
 * others). Either field may be null: pure-kana Japanese entries have no
 * [written], and Latin entries generally have no [reading].
 */
data class Headword(
    val written: String?,
    val reading: String?
)

data class Sense(
    val targetDefinitions: List<String>,
    val partsOfSpeech: List<String>,
    val tags: List<String>,
    val restrictions: List<String>,
    val info: List<String>,
    val misc: List<String> = emptyList(),
    val examples: List<Example> = emptyList(),
)

/**
 * One usage example attached to a [Sense]. [translation] is the English
 * rendering when the source provides one (Wiktionary frequently ships
 * bilingual examples); empty string otherwise.
 */
data class Example(
    val text: String,
    val translation: String,
)

/**
 * Character-level dictionary result. Sealed because each source language's
 * character metadata is intrinsic to its script — JA ships KANJIDIC2
 * (on/kun readings, JLPT, school grade, stroke count), while ZH reuses the
 * single-character CC-CEDICT entries already in its pack (pinyin, meanings,
 * frequency).
 *
 * [meaningsLang] is the BCP-47 code of the language [meanings] are currently
 * expressed in. For Japanese this can be one of the languages KANJIDIC2 ships
 * natively (en/fr/es/pt); for Chinese it's always "en" (CC-CEDICT source).
 * Callers compare against the user's target language to decide whether the
 * UI needs to run the meanings through machine translation.
 */
sealed interface CharacterDetail {
    val literal: Char
    val meanings: List<String>
    val meaningsLang: String
}

/**
 * Per-kanji detail from KANJIDIC2.
 * [jlpt] uses new N-levels: 5=N5 (easiest) … 2=N2, 0=not in JLPT.
 * [grade] is school grade 1-6, 8=secondary school, 0=ungraded.
 */
data class KanjiDetail(
    override val literal: Char,
    override val meanings: List<String>,
    override val meaningsLang: String,
    val onReadings: List<String>,
    val kunReadings: List<String>,
    val jlpt: Int,
    val grade: Int,
    val strokeCount: Int
) : CharacterDetail

/**
 * Per-hanzi detail reconstituted from the Chinese pack's single-character
 * CC-CEDICT entries. Pinyin is tone-marked; [freqScore] matches the 0-5 star
 * scale used by [DictionaryEntry.freqScore]. CC-CEDICT is Chinese↔English so
 * [meaningsLang] is always "en" — non-English UIs rely on MT fallback.
 */
data class HanziDetail(
    override val literal: Char,
    override val meanings: List<String>,
    val pinyin: String?,
    val isCommon: Boolean,
    val freqScore: Int
) : CharacterDetail {
    override val meaningsLang: String get() = "en"
}

/**
 * Returns a POS label suitable for blank-`pos` target rows (PanLex,
 * which doesn't carry POS metadata). When every sense across every
 * returned entry shares the same POS list — JMdict entries that are
 * uniformly verb/noun, Wiktionary single-POS entries — that shared list
 * is used. When senses disagree (Wiktionary multi-POS lookups like
 * "surprise" → noun/verb/intj, OR a JMdict entry that mixes noun and
 * verb senses under one headword), there's no way to align blank-pos
 * target senses to a specific source sense, so we return an empty list
 * and let the renderer suppress the label rather than mislabel rows as
 * the first sense's POS.
 */
fun unambiguousFallbackPos(entries: List<DictionaryEntry>): List<String> {
    val perSense = entries
        .flatMap { it.senses }
        .map { s -> s.partsOfSpeech.filter { it.isNotBlank() } }
        .filter { it.isNotEmpty() }
        .distinct()
    return if (perSense.size == 1) perSense.first() else emptyList()
}
