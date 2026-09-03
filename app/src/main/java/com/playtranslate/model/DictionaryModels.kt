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
    /** The pack's `entry.id` row for entries built from the JMdict pack;
     *  null for entries synthesized from imported Yomitan term dictionaries
     *  (YomitanEnrichment) — key EntryRef/hydration on this, never on
     *  [slug], which is display text. */
    val packId: Long? = null,
    val isCommon: Boolean?,
    val tags: List<String>,
    val jlpt: List<String>,
    val headwords: List<Headword>,
    val senses: List<Sense>,
    val freqScore: Int = 0,
    /** Definition groups from imported Yomitan term dictionaries, in the
     *  user's section order. Deliberately SEPARATE from [senses]: these are
     *  final display text (often monolingual) that must never enter the
     *  machine-translation tiers, the per-entry sense cap, or POS
     *  inference. Rendered ahead of [senses] on every surface. */
    val importedSenses: List<ImportedSenseGroup> = emptyList(),
    /** Whether the entry's natural display is kana even though a kanji form
     *  exists — [kanaOnlyFrom] over the pack's senses, decided when the entry
     *  is BUILT and carried from then on. Carried rather than re-derived from
     *  [senses] because the senses do not survive to render time on every
     *  path: Yomitan single-dictionary mode strips them off the pack entry
     *  when an imported group wins (YomitanEnrichment.mergeImportedTerms),
     *  and a derived check saw the empty list, answered false, and left the
     *  lens pill on 其れとも for それとも although the entry's only sense is
     *  uk (Thor, 2026-09-02). `copy` keeps it. Engines without the uk
     *  concept, and sense-less synthesized entries, leave the default. */
    val isKanaOnly: Boolean = false,
)

/** One imported term dictionary's definitions for a looked-up word.
 *  [source] is the group label (the dictionary's alias when set, else its
 *  title); [senses] are flattened plain-text definitions in the
 *  dictionary's own score order. */
data class ImportedSenseGroup(
    val source: String,
    val senses: List<ImportedSense>,
    /** Per-dictionary accent override (ARGB) for this group's title; null =
     *  the default muted header. */
    val accentColor: Int? = null,
    /** Owning dictionary's id — the styled renderer's key for scoping the
     *  dictionary's CSS to its own group ([data-dictionary]) and resolving
     *  media paths. Empty on legacy/synthetic groups; the flat tier never
     *  reads it. */
    val dictId: String = "",
)

/** One imported definition. [pos] is the entry's part-of-speech tags
 *  (resolved against the dictionary's tag bank, joined ", "), empty for
 *  dictionaries that don't tag — most monolingual conversions. */
data class ImportedSense(
    val definition: String,
    val pos: String = "",
    /** `term` table rowid when this sense's source entry retained a
     *  structured-content glossary (a `term_sc` row exists to fetch); null =
     *  flat text is all there is. Never serialized — these types stay
     *  in-process on [DictionaryEntry]. */
    val scRowid: Long? = null,
)

/**
 * One written/spoken variant of a dictionary entry. [written] is the visible
 * headword (kanji surface for JA, hanzi for ZH, lemma for Latin); [reading]
 * is the pronunciation hint (hiragana for JA, pinyin for ZH, null for most
 * others). Either field may be null: pure-kana Japanese entries have no
 * [written], and Latin entries generally have no [reading].
 *
 * [hasPriority] is true when the source dictionary marks this form with a
 * frequency-of-use tag. For JMdict this maps to `ke_pri` being non-empty
 * (ichi1/news1/nf01-48/etc.); other engines leave it false. It is a WORD
 * frequency signal, not a spelling one: JMdict stamps the same tags on the
 * kanji element and the reading element (沢山 and たくさん both carry
 * ichi1,news2,nf43), so it says nothing about whether the kanji is what
 * people actually write. Informational; no display decision reads it (see
 * [kanaOnlyFrom] for the rule that used to, and why that was wrong).
 */
data class Headword(
    val written: String?,
    val reading: String?,
    val hasPriority: Boolean = false,
    /** Pitch-accent downstep variants for this written↔reading pair, in
     *  source order (first = primary). Empty when no pitch dictionary is
     *  installed or the pair has no data; populated post-lookup by the JA
     *  engine's enrichment pass. */
    val pitch: List<Int> = emptyList(),
    /** Per-dictionary frequency data for this written↔reading pair, in the
     *  user's frequency-section order. Empty when no frequency dictionary is
     *  installed or the pair has no data; populated post-lookup by the JA
     *  engine's enrichment pass alongside [pitch]. */
    val frequencies: List<FrequencyTag> = emptyList(),
    /** Per-reading "common use" rank from the pack's `reading.rank_score`
     *  (higher = more common; from JMdict re_pri + re_inf + position). 0 when
     *  the pack predates the column. Orders the reading rows in the word-detail
     *  view; does NOT affect headword order (kept position-ordered). */
    val rankScore: Int = 0,
)

/**
 * One frequency dictionary's datum for a headword. [source] is the chip
 * label — the dictionary's user alias when set, else its title, never
 * truncated. [display] is the dictionary's own display form (e.g. "1234",
 * "Top 5k", "1234㋕"); multiple values from one dictionary arrive pre-joined.
 */
data class FrequencyTag(
    val source: String,
    val display: String,
    /** Per-dictionary accent override (ARGB) for the chip's rounded
     *  background; null = the default neutral chip. */
    val accentColor: Int? = null,
    /** The dictionary's sortable numeric value for this headword (rank or
     *  count), null for pure-string data ("common", "Top 5k"). Carried for
     *  frequency-sort aggregation (e.g. the Anki FreqSort harmonic mean);
     *  [display] remains the user-facing form. Trailing + defaulted so the
     *  positional [accentColor]-only construction sites stay valid. */
    val value: Double? = null,
    // Serializable so a per-word [WordEnrichment] map can ride through the Anki
    // review activity's intent extras as an atomic snapshot (see WordEnrichment).
) : java.io.Serializable

/**
 * One imported kanji dictionary's content for a character — the winning
 * dictionary's whole entry (no per-field mixing across imports). Readings
 * may be empty (some dictionaries ship meanings only); [meaningsLang] is
 * the dictionary's declared target language, defaulted to "en" upstream so
 * the character-breakdown MT fallback behaves like the built-in data.
 *
 * Dictionaries that never populate the onyomi field across their whole
 * bank (JPDB Kanji ships one usage-ranked list instead of the on/kun
 * split) deliver their readings in [combinedReadings] with the split
 * fields empty — the labels would be lies otherwise. The two shapes are
 * mutually exclusive.
 */
data class ImportedKanji(
    val meanings: List<String>,
    val onReadings: List<String>,
    val kunReadings: List<String>,
    val meaningsLang: String,
    val combinedReadings: List<String> = emptyList(),
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
    val strokeCount: Int,
    /** Per-dictionary frequency chips from imported Yomitan kanji-frequency
     *  dictionaries, in the user's section order. */
    val frequencies: List<FrequencyTag> = emptyList(),
    /** Readings from an imported dictionary that doesn't split on/kun
     *  (see [ImportedKanji.combinedReadings]). When non-empty, [onReadings]
     *  and [kunReadings] are empty and the UI renders one neutrally
     *  labelled line instead of the ON/KUN pair. */
    val combinedReadings: List<String> = emptyList(),
) : CharacterDetail

/**
 * Per-hanzi detail reconstituted from the Chinese pack's single-character
 * CC-CEDICT entries, optionally enriched by an imported zh-source Yomitan
 * kanji dictionary. Pinyin is tone-marked; [freqScore] matches the 0-5 star
 * scale used by [DictionaryEntry.freqScore]. [meaningsLang] is "en" for the
 * CC-CEDICT (Chinese↔English) glosses but carries an imported dictionary's
 * declared language when its meanings win, so the breakdown's MT fallback
 * behaves correctly; non-English UIs without a match rely on MT fallback.
 */
data class HanziDetail(
    override val literal: Char,
    override val meanings: List<String>,
    val pinyin: String?,
    val isCommon: Boolean,
    val freqScore: Int,
    override val meaningsLang: String = "en",
    /** Per-dictionary frequency chips from imported Yomitan kanji-frequency
     *  dictionaries, in the user's section order. Empty without imports. */
    val frequencies: List<FrequencyTag> = emptyList(),
) : CharacterDetail

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
 * The headword whose written form is [surface] AND whose reading is [reading] —
 * the occurrence-validated pick. Lets a caller honour the tokenizer's
 * per-occurrence reading for polyphonic kanji (明日 read あす in context vs the
 * entry's primary あした) WITHOUT trusting a reading the dictionary doesn't list:
 * a non-match returns null so [selectHeadword] falls back to the written-form
 * pick. Strict on BOTH fields by design — matching the reading alone could
 * surface a sibling written form the user never saw.
 */
fun DictionaryEntry.headwordForReading(surface: String?, reading: String?): Headword? {
    if (reading.isNullOrEmpty()) return null
    return headwords.firstOrNull { it.written == surface && it.reading == reading }
}

/**
 * The headword to display for a looked-up occurrence, in priority order:
 *  1. the occurrence [reading] matched against the seen [surface]
 *     ([headwordForReading]) — wins only when the entry lists that exact
 *     written↔reading pair, so a tokenizer misreading can't be injected;
 *  2. the seen [surface] (inflected or not) as a written/reading form;
 *  3. the [lookupForm] (lemma) as a written/reading form;
 *  4. the entry's primary headword.
 * Steps 2–4 reproduce the prior selection, so an empty/unmatched [reading]
 * leaves behaviour unchanged. Feed the result to [headwordDisplay].
 */
fun DictionaryEntry.selectHeadword(
    surface: String?,
    lookupForm: String?,
    reading: String?,
): Headword? =
    headwordForReading(surface, reading)
        ?: headwordFor(surface)
        ?: headwordFor(lookupForm)
        ?: headwords.firstOrNull()

/**
 * The kana-only verdict carried as [DictionaryEntry.isKanaOnly], from the
 * pack's senses in JMdict order: true when the FIRST sense carries JMdict's
 * "usually written using kana alone" (uk) tag, surfaced as the friendly
 * "Kana only" string by build_jmdict.py's MISC_ABBREV map. Evaluated once,
 * where the entry is built (DictionaryManager.buildEntry); the property's
 * doc says why it is carried instead of re-derived at render time.
 *
 * Why the first sense, and why nothing about the kanji form (decided
 * 2026-09-02; counts are a census of that day's JMdict_e, entries with a
 * kanji form):
 *  - `uk` is the editors' per-sense verdict on spelling, derived from n-gram
 *    counts, and sense order is JMdict's commonness order, so sense 1 is the
 *    meaning a reader most likely met. 8,949 entries are uk on every sense
 *    and 378 more on the first sense only (だめ, なんて, まもなく, たいてい);
 *    both display as kana. 264 entries are uk only on a LATER sense (決まる:
 *    six everyday senses written in kanji, one slang sense in kana) and
 *    display as kanji. An `any { uk }` rule collapsed that last class.
 *  - The kanji form's priority tag ([Headword.hasPriority]) is NOT a veto.
 *    The previous rule refused kana whenever a kanji form carried `ke_pri`,
 *    reading it as "this spelling is common". It is word frequency stamped
 *    on both elements, and 686 all-senses-uk words carry it on the kanji:
 *    たくさん, つまり, いつも, ため, うち, ください all rendered, and were
 *    Anki-saved, as 沢山, 詰まり, 何時も, 為, 家, 下さる. No JMdict consumer
 *    surveyed (yomitan-import, Jitendex, 10ten) treats priority presence
 *    as a kanji signal; Jitendex strips priority tags off rare forms as a
 *    data-integrity fix.
 *  - The seen surface still wins in [headwordDisplay]: an OCR'd 沢山 shows
 *    沢山 with たくさん beneath. This verdict only answers the case where
 *    no kanji was seen.
 *
 * Not consulted, on purpose: `stagr`/`stagk` restrictions that scope a uk
 * sense to particular readings or kanji forms (89 of 11,791 uk senses). The
 * verdict is entry-wide, so a reading the uk sense excludes collapses too.
 * Sized 2026-09-02: 11 first-sense-uk entries restrict readings, 6 leave a
 * visible reading uncovered (梓 し, 何がし なにぼう, 山鳥 さんちょう, 樺桜
 * かにわざくら, 新 あら/にい, 螺 つび/つみ), and the 11 stagk cases are
 * unreachable because [selectHeadword] takes the first headword with the
 * seen reading. The collapse only ever shows the kana the user saw, so the
 * cost is the hidden kanji, not an invented spelling. The refinement is the
 * pack's per-reading `uk_applicable` (already in v2 packs, serving the
 * cross-entry ranker) carried on [Headword] and required alongside this
 * verdict; planned with the ke_inf pass, which gives Headword per-form flags.
 */
fun kanaOnlyFrom(senses: List<Sense>): Boolean =
    senses.firstOrNull()?.misc?.any {
        MiscVocabulary.canonical(it) == MiscVocabulary.MiscCode.KANA_ONLY
    } == true

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
data class HeadwordDisplay(
    val written: String,
    val reading: String?,
    /** Pitch downsteps carried from the chosen [Headword] — survives the
     *  kana-only collapse (where the kana is promoted into [written] and
     *  [reading] is nulled) so pitch renderers still fire. */
    val pitch: List<Int> = emptyList(),
    /** Frequency chips carried from the chosen [Headword]; survives the
     *  kana-only collapse like [pitch]. */
    val frequencies: List<FrequencyTag> = emptyList(),
)

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
        // Track which headword supplied the kana so its pitch rides along.
        val kanaHw = form?.takeIf { it.reading?.isNotBlank() == true }
            ?: headwords.firstOrNull { it.reading?.isNotBlank() == true }
        val kana = kanaHw?.reading
        if (kana != null) {
            return HeadwordDisplay(
                written = kana,
                reading = null,
                pitch = kanaHw.pitch,
                frequencies = kanaHw.frequencies,
            )
        }
    }
    val written = form?.written?.takeIf { it.isNotBlank() }
        ?: form?.reading?.takeIf { it.isNotBlank() }
        ?: slug
    val reading = form?.reading?.takeIf { it.isNotBlank() && it != written }
    return HeadwordDisplay(
        written = written,
        reading = reading,
        pitch = form?.pitch ?: emptyList(),
        frequencies = form?.frequencies ?: emptyList(),
    )
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
