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
 *
 * [hasPriority] is true when the source dictionary marks this form as
 * preferred/common. For JMdict this maps to `ke_pri` being non-empty
 * (ichi1/news1/nf01-48/etc.); other engines leave it false. The signal
 * disambiguates entries like 決まる where one minor sense carries `uk`
 * "usually kana" but the kanji form is the standard everyday spelling.
 */
data class Headword(
    val written: String?,
    val reading: String?,
    val hasPriority: Boolean = false,
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
 * Returns the headword whose [Headword.written] or [Headword.reading]
 * exactly matches [query], or null when none match. Use when rendering an
 * entry that the user reached by clicking a specific surface — JMdict
 * frequently groups variant kanji under one entry (e.g. 無下 / 無気 share
 * entry 2863328 because they're pronounced the same way and mean the same
 * thing), so the entry's primary headword is often NOT the form the user
 * actually saw.
 *
 * Strict (null on miss) instead of falling back to the primary headword so
 * callers can chain alternatives — typically `headwordFor(surface)
 * ?: headwordFor(lookupForm) ?: headwords.firstOrNull()` — and so an
 * inflected surface that doesn't match any headword surfaces (出逢って vs
 * stored 出逢う) correctly falls through to the next branch instead of
 * silently latching onto the entry's primary form.
 */
fun DictionaryEntry.headwordFor(query: String?): Headword? {
    if (query.isNullOrEmpty()) return null
    return headwords.firstOrNull { it.written == query || it.reading == query }
}

/**
 * True when the entry's natural display is kana even though a kanji form
 * exists. Requires BOTH:
 *  1. At least one sense carries JMdict's "usually written using kana alone"
 *     (uk) tag — surfaced as the friendly "Kana only" string by
 *     build_jmdict.py's MISC_ABBREV map.
 *  2. No kanji headword is marked as a priority/common form
 *     ([Headword.hasPriority]). Entries like 決まる (sense 7's slang "to get
 *     high" is uk-tagged but the kanji form 決まる carries ichi1+news1+nf14)
 *     would mis-classify under the uk-tag check alone.
 *
 * For v1 packs (no `ke_pri` data, so `hasPriority` is always false), this
 * degrades to the old uk-only behaviour — same misclassification as before,
 * no crashes. v2+ packs get the tighter check.
 */
val DictionaryEntry.isKanaOnly: Boolean
    get() {
        val hasUkSense = senses.any { sense ->
            sense.misc.any { it.equals("Kana only", ignoreCase = true) }
        }
        if (!hasUkSense) return false
        val anyPriorityKanji = headwords.any { it.written != null && it.hasPriority }
        return !anyPriorityKanji
    }

/**
 * Headword display fields for a dictionary entry, with kana-only suppression
 * already applied. [queriedWord] is the surface the user clicked, used to
 * pick the matching variant (entry 2863328 groups 無下 + 無気; clicking 無気
 * must show 無気). Falls back to the entry's first headword when no variant
 * matches.
 *
 * When [DictionaryEntry.isKanaOnly] is true and the resolved variant has a
 * reading, the kanji is suppressed: [written] becomes the kana reading and
 * [reading] is null (since duplicating it on the muted reading line would
 * just repeat the headword).
 */
data class HeadwordDisplay(val written: String, val reading: String?)

/**
 * [surface] is the text the user actually saw — the OCR'd / clicked
 * source. When it shares an ideographic character with one of the entry's
 * kanji headwords, the kana-only override is suppressed: the user engaged
 * with the kanji form (何故 in the wild), so the UI shows that with the
 * kana as a reading instead of collapsing to the bare kana (なぜ). The
 * check is char-level rather than exact-match so inflected verb / adjective
 * surfaces (e.g. 決まっている for headword 決まる) still recognise that the
 * source had kanji. Pass null when no surface context is available (e.g.
 * drag-flow lens fallbacks); the override then fires as before.
 */
fun DictionaryEntry.headwordDisplay(
    form: Headword?,
    surface: String? = null,
): HeadwordDisplay {
    val surfaceIsKanji = surface != null && headwords.any { hw ->
        hw.written?.any { c ->
            Character.isIdeographic(c.code) && surface.contains(c)
        } == true
    }
    if (isKanaOnly && !surfaceIsKanji) {
        val kana = form?.reading?.takeIf { it.isNotBlank() }
            ?: headwords.firstNotNullOfOrNull { hw ->
                hw.reading?.takeIf { it.isNotBlank() }
            }
        if (kana != null) return HeadwordDisplay(written = kana, reading = null)
    }
    val written = form?.written?.takeIf { it.isNotBlank() }
        ?: form?.reading?.takeIf { it.isNotBlank() }
        ?: slug
    val reading = form?.reading?.takeIf { it.isNotBlank() && it != written }
    return HeadwordDisplay(written = written, reading = reading)
}

fun DictionaryEntry.headwordDisplay(queriedWord: String? = null): HeadwordDisplay =
    headwordDisplay(
        form = headwordFor(queriedWord) ?: headwords.firstOrNull(),
        surface = queriedWord,
    )

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
