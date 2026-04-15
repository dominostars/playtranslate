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
    val misc: List<String> = emptyList()
)

/**
 * Per-kanji detail from KANJIDIC2. Japanese-specific — stays named [KanjiDetail]
 * rather than a generic "CharacterDetail" because the fields (on/kun readings,
 * JLPT level, school grade) are intrinsically tied to Japanese kanji pedagogy.
 * [jlpt] uses new N-levels: 5=N5 (easiest) … 2=N2, 0=not in JLPT.
 * [grade] is school grade 1-6, 8=secondary school, 0=ungraded.
 */
data class KanjiDetail(
    val literal: Char,
    val meanings: List<String>,
    val onReadings: List<String>,
    val kunReadings: List<String>,
    val jlpt: Int,
    val grade: Int,
    val strokeCount: Int
)
