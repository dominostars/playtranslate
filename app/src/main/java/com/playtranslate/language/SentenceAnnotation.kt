package com.playtranslate.language

/**
 * The single authoritative analysis of one piece of source text — the model
 * at the heart of the sentence-annotation refactor
 * (docs/sentence-annotation-refactor.md). Every reading-bearing surface
 * (in-app furigana, live overlay annotations, Anki sentence fields, words
 * panels, sentence TTS) is a projection of ONE annotation; no consumer
 * re-derives readings or re-joins analysis to text by string matching.
 *
 * Annotators come in three tiers:
 *  - FULL (JA, ZH): tiled spans with dictionary-resolved display readings
 *    and per-span ruby parts.
 *  - LEXICAL (KO, TH, Latin pipeline): spans carry [AnnotatedSpan.word] /
 *    [AnnotatedSpan.lookupForm] for the words feature; readings/parts stay
 *    empty and offsets may be absent — no consumer of a lexical-tier
 *    annotation renders ruby, so the tiling invariant applies only to the
 *    full tier.
 *  - PLAIN (no tokenizer support): one bare span.
 */

/**
 * Reference to a resolved dictionary object across BOTH stores. The pack and
 * imported Yomitan term dictionaries are peers: Yomitan can synthesize
 * entries on pack misses (YomitanEnrichment), so a pack row id alone cannot
 * key resolution or hydration.
 */
sealed interface EntryRef {
    data class Pack(val entryId: Long) : EntryRef
    data class Imported(val term: String, val reading: String?) : EntryRef
}

/** How much work [SourceLanguageEngine.annotate] performs. */
enum class AnnotationDepth {
    /**
     * Tokenizer readings only — no dictionary resolution, no re-glob. Spans
     * are per token; display output is byte-equivalent to the legacy
     * per-token furigana path. The live overlay stays on this depth until
     * its measurement gate passes (see the refactor doc §6).
     */
    TOKENS,

    /**
     * Re-glob without per-word resolution: spans carry lookup forms and
     * hints but no entry refs or display-reading overrides. Cost parity
     * with the legacy tokenize() — which is now a projection of this depth.
     */
    WORDS,

    /**
     * Dictionary-resolved: re-glob, two-store entry resolution with the
     * tokenizer reading as the narrowing hint, occurrence-validated display
     * readings, and the word-span override policy.
     */
    FULL,
}

/**
 * One ruby segment of a span: [text] with its [reading] floated above, or a
 * write-through segment when [reading] is null (okurigana, kana, punctuation
 * — anything ruby-less). JA parts come from kana-anchored splitFurigana; ZH
 * parts are per-character pinyin.
 */
data class ReadingPart(val text: String, val reading: String?)

/**
 * One span of analyzed text. In a FULL-tier annotation the spans tile the
 * text exactly: `spans[i].end == spans[i+1].start`, first start 0, last end
 * `text.length`. Lexical-tier spans may be offsetless (`start == end == -1`).
 */
data class AnnotatedSpan(
    val start: Int,
    val end: Int,
    /** Text as it appears in the input, including any folded conjugation
     *  glue (使わない for lookup form 使う). */
    val surface: String,
    /** Lemma / lookup form — populated for every CONTENT span regardless of
     *  dictionary resolution. Distinct from [word]: interactive paths
     *  (drag, tap-to-lookup) retry no-hit content words at lookup time with
     *  deinflection and fallback forms, so the lemma must survive a miss. */
    val lookupForm: String? = null,
    /** Dictionary form when a lookup RESOLVED (the words-projection key),
     *  else null. */
    val word: String? = null,
    val entryRef: EntryRef? = null,
    /** The narrowing hint this span's resolution used (and hydration must
     *  reuse, or the words row could land on a DIFFERENT entry than the
     *  annotation — the exact seam this model exists to kill). JA: the
     *  re-glob lookup hint; ZH: the heteronym correction, when any. */
    val lookupHint: String? = null,
    /**
     * The display reading for this span — what furigana shows and what JA
     * sentence TTS speaks. Kana for JA (occurrence-validated dictionary
     * reading where the override policy applied, tokenizer reading
     * otherwise); the word-level romanization for ZH. Null when the span
     * has no reading (punctuation, Latin, OOV).
     */
    val reading: String? = null,
    /** Ruby segmentation renderers draw verbatim. Empty on spans with
     *  nothing to annotate and on lexical-tier spans. */
    val furigana: List<ReadingPart> = emptyList(),
    /** Concatenated member-token readings (kana), kept for diagnostics and
     *  as the policy/TTS fallback. Null when any member lacks a reading. */
    val tokenReading: String? = null,
    val inflections: List<InflectionTag> = emptyList(),
    /** Pitch-accent downsteps, only on whole-word uninflected spans (the
     *  same eligibility rule the legacy hint path used). */
    val pitch: List<Int> = emptyList(),
)

data class SentenceAnnotation(
    /** The EXACT string analyzed — with [lang] and [importGeneration], the
     *  cache key. */
    val text: String,
    val lang: SourceLangId,
    /** Yomitan import generation at annotation time. Imported dictionaries
     *  change mid-session without engine eviction, so a cached annotation is
     *  valid only while the generation matches. 0 until the phase-4 cache
     *  wires the counter. */
    val importGeneration: Int,
    val spans: List<AnnotatedSpan>,
) {
    companion object {
        /** PLAIN-tier annotation: one bare span covering [text]. */
        fun plain(text: String, lang: SourceLangId): SentenceAnnotation =
            SentenceAnnotation(
                text = text, lang = lang, importGeneration = 0,
                spans = if (text.isEmpty()) emptyList() else listOf(
                    AnnotatedSpan(start = 0, end = text.length, surface = text)
                ),
            )
    }
}

/**
 * The annotation with the FRONTIER span's ruby withheld — the span touching
 * the text's end: the one word still being typed during a typewriter reveal.
 * Its reading may legitimately revise as glyphs arrive (大人 alone reads
 * おとな; 気 lands and the span becomes 大人気/だいにんき), so eager live
 * furigana renders every COMPLETED word's reading immediately and holds only
 * this one — early readings with zero visible revisions. No-op when the
 * final span carries no ruby (punctuation/kana end: the words before it are
 * boundary-confirmed and correctly show). Render-time only — cached
 * annotations stay whole.
 */
fun SentenceAnnotation.withFrontierHeld(): SentenceAnnotation {
    val last = spans.lastOrNull { it.start >= 0 } ?: return this
    if (last.end != text.length) return this
    if (last.furigana.none { it.reading != null }) return this
    return copy(spans = spans.map { s ->
        if (s === last) s.copy(furigana = listOf(ReadingPart(s.surface, null)))
        else s
    })
}

/**
 * Hint-text projection: one [HintTextAnnotation] per ruby part, offsets
 * computed by walking each span's parts from its start. Pitch rides only on
 * whole-span single parts (the annotator sets [AnnotatedSpan.pitch] under
 * the same eligibility rule the legacy hint path used). Offsetless
 * (lexical-tier) spans project nothing — there is no position to annotate.
 */
fun SentenceAnnotation.hintAnnotations(): List<HintTextAnnotation> {
    val out = mutableListOf<HintTextAnnotation>()
    for (s in spans) {
        if (s.start < 0) continue
        var at = s.start
        val wholeSpan = s.furigana.size == 1 && s.furigana[0].text == s.surface
        for (p in s.furigana) {
            if (p.reading != null) {
                out.add(HintTextAnnotation(
                    baseStart = at, baseEnd = at + p.text.length, hintText = p.reading,
                    pitchDownstep = if (wholeSpan) s.pitch.firstOrNull() else null,
                ))
            }
            at += p.text.length
        }
    }
    return out
}
