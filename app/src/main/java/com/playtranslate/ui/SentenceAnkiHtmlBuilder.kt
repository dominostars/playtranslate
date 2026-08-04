package com.playtranslate.ui

import android.util.Log
import com.playtranslate.dictionary.Deinflector
import com.playtranslate.language.AnnotatedSpan
import com.playtranslate.language.SentenceAnnotation
import com.playtranslate.language.SourceLangId
import com.playtranslate.model.FrequencyTag

private const val TAG = "SentenceFurigana"

/**
 * Word-break-opportunity element used as an invisible separator between
 * each kanji bracket and its neighbouring kana. Anki's furigana regex
 * (` ?([^ >]+?)\[(.+?)\]`) can't span across `<wbr>` because the `>` is
 * excluded from `[^ >]`. Browsers render the element as zero-width
 * whitespace so the card has no visible inter-word spaces. Migaku's
 * `support.html` parser is expected (but not yet verified on a real
 * card) to treat `<wbr>` as a DOM-level word boundary.
 */
private const val WBR = "<wbr>"

/**
 * Shared HTML builder for sentence-mode Anki cards.
 * Used by both [AnkiReviewBottomSheet] and [WordAnkiReviewSheet].
 */
object SentenceAnkiHtmlBuilder {

    data class WordEntry(
        val word: String,
        val reading: String,
        val meaning: String,
        val freqScore: Int = 0,
        val surfaceForm: String = "",
        /** Pitch-accent downsteps for this word, for the Anki pitch-position
         *  field; empty when unknown. Populated on the sentence-send path from
         *  [LastSentenceCache] by word, like [surfaceForm]. */
        val pitch: List<Int> = emptyList(),
        /** Per-dictionary frequencies for this word, for the Anki frequency
         *  list/sort fields; empty when unknown. */
        val frequencies: List<FrequencyTag> = emptyList(),
        /** Common-entry flag; drives the word cell's Common pill. Rides
         *  [WordEnrichment] like [pitch]/[frequencies]. */
        val isCommon: Boolean = false,
        /** Structured senses (the lens's rows). When empty the cell falls back
         *  to splitting [meaning] on newlines, today's rendering. */
        val senses: List<SenseDisplay> = emptyList(),
    )

    fun starsString(score: Int) = "\u2605".repeat(score)

    /**
     * Emits the SENTENCE field value: plain Japanese text with `<b>`
     * around each highlighted-word surface form. For template fields
     * rendered raw via `{{Sentence}}` \u2014 JPMN renders Sentence that way
     * on every card type \u2014 putting bracket syntax here shows literal
     * `[reading]` markup. The bracketed variant lives in
     * [buildSentenceFurigana] / SENTENCE_FURIGANA for furigana-filtered
     * fields.
     *
     * Highlight resolution: each entry in [highlightedWords] is a
     * dictionary form. We resolve to the matching
     * [WordEntry.surfaceForm] when the word is conjugated (so \u5012\u308c\u3066\u3044\u308b
     * stays bold in the sentence, not the un-inflected \u5012\u308c\u308b). When no
     * surfaceForm exists, falls back to the dictionary form verbatim.
     * Newlines collapse to `<br>`.
     */
    fun buildSentencePlain(
        text: String,
        words: List<WordEntry>,
        highlightedWords: Set<String>,
    ): String {
        val targets = resolveHighlightTargets(words, highlightedWords)
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n' || c == '\r') {
                sb.append("<br>")
                i++
                while (i < text.length && (text[i] == '\n' || text[i] == '\r')) i++
                continue
            }
            val hit = targets.firstOrNull { text.startsWith(it, i) }
            if (hit != null) {
                sb.append("<b>").append(htmlEscape(hit)).append("</b>")
                i += hit.length
            } else {
                sb.appendEscaped(c)
                i++
            }
        }
        return sb.toString()
    }

    /**
     * Emits the SENTENCE_FURIGANA field value: Anki-native furigana
     * brackets (`kanji[reading]` for JA, `hanzi[pinyin]` for ZH) with
     * each bracket **isolated by `<wbr>` separators**. Plain text
     * passthrough for languages without a reading-annotation path.
     *
     * RENDERER, not analyzer: every reading decision was made once, in the
     * [annotation] (docs/sentence-annotation-refactor.md) — this function
     * draws the annotation's spans and never re-derives readings or re-joins
     * words to text by string matching. The furigana here is therefore the
     * SAME furigana the result screens display and the same readings
     * sentence TTS speaks.
     *
     * **Why `<wbr>` is the right separator.** Two downstream
     * consumers each need a boundary signal between bracket-words
     * and the kana that follows, in formats that don't show up as
     * visible whitespace:
     *
     *  1. **Anki's `{{furigana:}}` filter** regex
     *     ` ?([^ >]+?)\[(.+?)\]` reads everything before `[` (except
     *     space and `>`) as the ruby base. `<wbr>` works as an
     *     anchor because the trailing `>` in the tag is excluded
     *     from the `[^ >]` character class.
     *  2. **Migaku's `support.html` parser** treats everything from
     *     a kanji bracket until the next whitespace as the "word";
     *     a DOM-aware parser treats `<wbr>` as a word boundary.
     *
     * Examples:
     *  - JA 聞いた         → `聞[き]<wbr>いた`
     *  - JA 友達に聞いた   → `友達[ともだち]<wbr>に<wbr>聞[き]<wbr>いた`
     *  - JA 一泊した       → `一泊[いっぱく]<wbr>した` (the annotator's
     *    word-span override; per-token readings would give いち+はく)
     *  - ZH 今天天气很好 → `今[jīn]<wbr>天[tiān]<wbr>天[tiān]<wbr>气[qì]<wbr>很[hěn]<wbr>好[hǎo]`
     *
     * Highlight (`<b>`) and word wrappers (`data-pt-w`) are span-keyed:
     * a highlighted word is matched by its words-table key against each
     * span's resolved word/lookup form (with a surface fallback for
     * unresolved words), never by scanning the text — the interior-offset
     * highlight class dies with the walk. A null/mismatched [annotation]
     * (no analysis available, or the text was edited after annotation and
     * the caller didn't re-annotate) degrades to the PLAIN sentence with
     * `<b>` highlights — never-wrong beats sometimes-ruby.
     */
    fun buildSentenceFurigana(
        text: String,
        words: List<WordEntry> = emptyList(),
        highlightedWords: Set<String> = emptySet(),
        sourceLangId: SourceLangId = SourceLangId.JA,
        /** When true, wrap each dictionary word's span of the sentence in
         *  `<span data-pt-w="…">` (plus `data-pt-kana`/`data-pt-pitch` when
         *  the word has pitch data) so the PT sentence template's JS can
         *  find words: the front tooltip draws the pitch contour, the back
         *  tap scrolls to the word's cell in the words table. Default
         *  false — the structured path's SentenceFurigana output must stay
         *  byte-stable for third-party consumers. */
        wrapWords: Boolean = false,
        annotation: SentenceAnnotation? = null,
    ): String {
        val isJa = sourceLangId == SourceLangId.JA
        val isZh = sourceLangId == SourceLangId.ZH || sourceLangId == SourceLangId.ZH_HANT
        if (!isJa && !isZh) return plainBody(text)
        val ann = annotation?.takeIf { it.text == text }
            ?: return buildSentencePlain(text, words, highlightedWords)
        val targets = resolveHighlightTargets(words, highlightedWords)
        val sb = StringBuilder()
        for (span in ann.spans) {
            if (span.start < 0) continue // offsetless (ZH unanchored) — words-only span
            val entry = wordEntryFor(span, words)
            val highlighted =
                (entry != null && entry.word in highlightedWords) || span.surface in targets
            if (highlighted) sb.append("<b>")
            val kana = entry?.let { e ->
                when {
                    e.reading.isNotEmpty() -> e.reading
                    e.word.all(Deinflector::isKana) -> e.word
                    else -> ""
                }
            }.orEmpty()
            val wrap = wrapWords && entry != null
            if (wrap) {
                sb.append("<span data-pt-w=\"").append(htmlEscape(entry!!.word)).append("\"")
                if (entry.pitch.isNotEmpty() && kana.isNotEmpty()) {
                    sb.append(" data-pt-kana=\"").append(htmlEscape(kana))
                        .append("\" data-pt-pitch=\"")
                        .append(entry.pitch.joinToString(",")).append("\"")
                }
                sb.append(">")
            }
            when {
                isZh && entry != null && entry.reading.isNotEmpty() ->
                    emitPinyinParts(sb, span.surface, entry.reading)
                isZh -> appendPlain(sb, span.surface)
                else -> emitReadingParts(sb, span.furigana)
            }
            if (wrap) sb.append("</span>")
            if (highlighted) sb.append("</b>")
        }
        // Word wrappers enclose the bracket runs, so boundary `<wbr>`s that
        // the string-edge strip used to catch now sit just inside the span
        // tags — equally workless, stripped the same way.
        val out = SPAN_OPEN_WBR.replace(stripBoundarySeparators(sb.toString()), "$1")
            .replace("$WBR</span>", "</span>")
        Log.d(TAG, "buildSentenceFurigana: in='$text' out='$out'")
        return out
    }

    /** The words-table entry a span renders with: matched by the span's
     *  resolved word / lookup form first, surface as the fallback for
     *  entries whose lemma differs (inflected surfaceForm rows). This is a
     *  join WITHIN one annotation payload — word-keyed, per the refactor's
     *  edit-path rule — not a text scan. */
    private fun wordEntryFor(span: AnnotatedSpan, words: List<WordEntry>): WordEntry? {
        if (words.isEmpty()) return null
        return words.firstOrNull { e ->
            (span.word != null && e.word == span.word) ||
                (span.lookupForm != null && e.word == span.lookupForm)
        } ?: words.firstOrNull { e ->
            e.surfaceForm.ifEmpty { e.word } == span.surface
        }
    }

    /** JA bracket emission from the annotation's ruby parts: reading parts
     *  become `<wbr>base[reading]<wbr>` brackets, write-through parts stay
     *  plain (newlines → `<br>`). */
    private fun emitReadingParts(sb: StringBuilder, parts: List<com.playtranslate.language.ReadingPart>) {
        for (p in parts) {
            val r = p.reading
            if (r != null) {
                sb.append(WBR).append(htmlEscape(p.text))
                    .append('[').append(htmlEscape(r)).append(']').append(WBR)
            } else {
                appendPlain(sb, p.text)
            }
        }
    }

    /** A `<wbr>` immediately inside a word wrapper's opening tag. */
    private val SPAN_OPEN_WBR = Regex("(<span[^>]*>)<wbr>")

    /**
     * Appends one character of [text] starting at [i] to [sb],
     * collapsing a run of `\n`/`\r` into a single `<br>`. Returns the
     * new cursor position.
     */
    private fun appendOneCharOrBr(sb: StringBuilder, text: String, i: Int): Int {
        val c = text[i]
        if (c == '\n' || c == '\r') {
            sb.append("<br>")
            var j = i + 1
            while (j < text.length && (text[j] == '\n' || text[j] == '\r')) j++
            return j
        }
        sb.appendEscaped(c)
        return i + 1
    }


    /**
     * Chinese counterpart of [emitReadingParts]: emits per-hanzi
     * `<wbr>{c}[{syllable}]<wbr>` brackets when the reading's
     * whitespace-separated syllable count matches the word's hanzi
     * count. Non-hanzi chars (punctuation, embedded Latin) pass through
     * plain.
     *
     * Mismatched count (érhuà like 好玩儿/`hǎo wánr`, embedded digits,
     * irregular CC-CEDICT entries) falls back to a single
     * `<wbr>{word}[{reading}]<wbr>` bracket — still rendered as a
     * centered-block ruby by Anki's `{{furigana:}}` filter, just not
     * per-character aligned.
     *
     * Examples:
     *  - 今天 + "jīn tiān" → `今[jīn]<wbr>天[tiān]`
     *  - 好玩儿 + "hǎo wánr" → `好玩儿[hǎo wánr]` (count mismatch)
     */
    private fun emitPinyinParts(sb: StringBuilder, word: String, reading: String) {
        if (reading.isEmpty() || !word.any(::isKanjiChar)) {
            appendPlain(sb, word)
            return
        }
        val syllables = reading.trim().split(Regex("\\s+"))
        val hanziCount = word.count(::isKanjiChar)
        if (hanziCount != syllables.size) {
            sb.append(WBR).append(htmlEscape(word))
                .append('[').append(htmlEscape(reading)).append(']').append(WBR)
            return
        }
        var si = 0
        // Adjacent hanzi share a single boundary `<wbr>` rather than
        // emitting `<wbr>...<wbr><wbr>...<wbr>` (which is functionally
        // equivalent but uglier in raw HTML). Track whether the
        // previous emit already left a trailing `<wbr>` we can reuse.
        var prevWasBracket = false
        for (c in word) {
            if (isKanjiChar(c)) {
                if (!prevWasBracket) sb.append(WBR)
                sb.appendEscaped(c).append('[')
                    .append(htmlEscape(syllables[si])).append(']').append(WBR)
                si++
                prevWasBracket = true
            } else {
                sb.appendEscaped(c)
                prevWasBracket = false
            }
        }
    }

    /**
     * Resolves [highlightedWords] (dictionary forms) to the actual
     * surface forms present in [text], using each [WordEntry]'s
     * recorded surfaceForm when available. Sorted longest-first so a
     * longer target wins when multiple targets share a prefix. Shared
     * by `buildSentencePlain` and `buildSentenceFurigana`.
     */
    private fun resolveHighlightTargets(
        words: List<WordEntry>,
        highlightedWords: Set<String>,
    ): List<String> = buildSet {
        highlightedWords.forEach { dict ->
            if (dict.isEmpty()) return@forEach
            val surfaces = words.asSequence()
                .filter { it.word == dict && it.surfaceForm.isNotEmpty() }
                .map { it.surfaceForm }
                .toList()
            if (surfaces.isEmpty()) add(dict) else addAll(surfaces)
        }
    }.toList().sortedByDescending { it.length }

    /**
     * Emits the EXPRESSION field value for word-mode (single-word)
     * sends: per-kanji furigana brackets (JA) or per-hanzi pinyin
     * brackets (ZH/ZH_HANT) with the same `<wbr>` isolation rule as
     * [buildSentenceFurigana]. The caller already knows the headword's
     * dictionary form + reading so we skip Kuromoji and per-kanji-split
     * via [Deinflector.splitFurigana] for JA; ZH alignment is a direct
     * zip of hanzi chars with whitespace-separated pinyin syllables.
     *
     * Examples:
     *  - JA \u805e\u304f     \u2192 `\u805e[\u304d]<wbr>\u304f`
     *  - JA \u53d6\u308a\u51fa\u3059 \u2192 `\u53d6[\u3068]<wbr>\u308a<wbr>\u51fa[\u3060]<wbr>\u3059`
     *  - ZH \u4eca\u5929     \u2192 `\u4eca[j\u012bn]<wbr>\u5929[ti\u0101n]`
     */
    fun buildExpressionFurigana(
        word: String,
        reading: String,
        sourceLangId: SourceLangId = SourceLangId.JA,
    ): String {
        if (reading.isEmpty()) return htmlEscape(word)
        val isJa = sourceLangId == SourceLangId.JA
        val isZh = sourceLangId == SourceLangId.ZH || sourceLangId == SourceLangId.ZH_HANT
        if (!isJa && !isZh) return htmlEscape(word)
        if (!word.any(::isKanjiChar)) return htmlEscape(word)
        val out = if (isJa) {
            buildJaExpressionFurigana(word, reading)
        } else {
            stripBoundarySeparators(buildString { emitPinyinParts(this, word, reading) })
        }
        Log.d(TAG, "buildExpressionFurigana: word='$word' reading='$reading' out='$out'")
        return out
    }

    /**
     * JA expression-furigana for the word / target-word fields. Diverges from
     * [buildSentenceFurigana] on purpose: the sentence builder separates kana
     * *words* with `<wbr>` (a trailing space there would render as a visible
     * gap), but an expression's only boundary is kana→kanji, so it uses the
     * native Anki **leading space** before a kanji bracket that follows kana.
     * Anki's furigana/kana filters consume that single leading space, so the
     * ruby shows no gap AND `{{kana:ExpressionFurigana}}` reconstructs the
     * reading cleanly — which is what Lapis draws its pitch contour over.
     * `<wbr>` cannot be used here: `{{kana:…}}` strips `[…]` but not `<wbr>`, so
     * it would leak into the kana and garble Lapis's pitch.
     *
     * The leading space is load-bearing, NOT cosmetic: without it
     * `取[と]り出[だ]す` lets Anki's `[^ >]+?` swallow the okurigana —
     * `{{kana:}}` = `とだす` (り eaten) — whereas `取[と]り 出[だ]す` gives `とりだす`.
     *
     * Completeness guard: [Deinflector.splitFurigana] can drop a reading mora
     * when a kanji block's reading ends in the same kana as the following
     * okurigana (可愛い/かわいい → 可愛=かわ, the trailing い is lost). That would put
     * the wrong kana under the contour, so when the split doesn't reconstruct
     * [reading] we emit a single whole-word bracket instead — coarser ruby, but
     * `{{kana:…}}` == reading.
     */
    private fun buildJaExpressionFurigana(word: String, reading: String): String {
        val parts = Deinflector.splitFurigana(word, reading)
        val recombined = parts.joinToString("") { it.reading ?: it.text }
        if (Deinflector.katakanaToHiragana(recombined) !=
            Deinflector.katakanaToHiragana(reading)
        ) {
            return "${htmlEscape(word)}[${htmlEscape(reading)}]"
        }
        val sb = StringBuilder()
        var prevWasKana = false
        for (part in parts) {
            val r = part.reading
            if (r != null) {
                // Leading space before a kanji bracket that follows kana —
                // bounds Anki's furigana regex (so okurigana isn't absorbed)
                // and is consumed by the filter (no visible gap, clean kana).
                if (prevWasKana) sb.append(' ')
                sb.append(htmlEscape(part.text)).append('[').append(htmlEscape(r)).append(']')
                prevWasKana = false
            } else {
                appendPlain(sb, part.text)
                prevWasKana = true
            }
        }
        return sb.toString()
    }

    /**
     * Removes a leading or trailing `<wbr>` from the field-level
     * output. A boundary `<wbr>` does no work \u2014 there's no preceding
     * or following content for it to separate \u2014 and the slight
     * payload bloat is unhelpful.
     */
    private fun stripBoundarySeparators(s: String): String {
        var result = s
        if (result.startsWith(WBR)) result = result.substring(WBR.length)
        if (result.endsWith(WBR)) result = result.substring(0, result.length - WBR.length)
        return result
    }

    private fun isKanjiChar(c: Char): Boolean =
        c in '\u4e00'..'\u9fff' || c in '\u3400'..'\u4dbf'

    private fun plainBody(text: String): String {
        val sb = StringBuilder()
        appendPlain(sb, text)
        return sb.toString()
    }

    private fun appendPlain(sb: StringBuilder, text: String) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n' || c == '\r') {
                sb.append("<br>")
                i++
                while (i < text.length && (text[i] == '\n' || text[i] == '\r')) i++
            } else {
                sb.appendEscaped(c)
                i++
            }
        }
    }

    /**
     * Builds the per-word HTML table carried in the default Sentence
     * model's WordsTable field AND in the structured-path WORDS_TABLE
     * output. The [styler] callback decides whether each element
     * carries a `class=""` (default path — the model CSS supplies the
     * gl-* rules) or an inline `style=""` (structured path, no CSS
     * available). `internal` so [AnkiCardOutputBuilder] and
     * [PtNoteBuilder] can pass their stylers.
     *
     * Each word renders as a cell that ports [WordDefinitionsView.bind]
     * to HTML, so the card and the magnifying lens read alike:
     * head row (word · reading/pitch · audio) → meta row (Common pill ·
     * ★ run · frequency chips) → senses with a POS header only when the
     * POS changes from the previous sense. Target words get the
     * `.gl-w-target` panel surface, context words the bare hairline
     * `.gl-w` row — no accent fill; the accent underline in the
     * sentence body is what marks targets. ALL senses render — the
     * card is archival, and nothing in the app knows which sense is
     * the right one, so none may be dropped. When [WordEntry.senses]
     * is empty, falls back to the flattened meaning lines (which carry
     * their own baked numbering).
     */
    internal fun buildWordsHtmlWith(
        words: List<WordEntry>,
        highlightedWords: Set<String>,
        styler: HtmlStyler,
        /** Map of word → Anki media filename for per-target-word audio.
         *  When present, a `[sound:…]` tag is emitted in a `.pt-audio`
         *  span in the head row so Anki renders an inline play button.
         *  Words absent from this map get no audio tag. */
        wordAudioFilenames: Map<String, String> = emptyMap(),
        /** When true, render each word's reading with its pitch-accent contour
         *  (the legacy PT card back). Default false so the structured
         *  WORDS_TABLE path — which ships no pitch CSS and already gets pitch
         *  via the PitchPosition/PAOverride fields — emits no `pa-*` markup. */
        renderPitch: Boolean = false,
        /** Localized Common-pill label. Production callers pass
         *  R.string.word_detail_common; the default keeps plain JVM tests
         *  context-free. */
        commonLabel: String = "Common",
        /** POS localizer for non-imported senses (imported headers render
         *  verbatim, matching the lens). Production passes
         *  Context::localizePos (list overload). */
        localizePos: (List<String>) -> String = { it.joinToString(" · ") },
        /** Misc register-tag renderer; null = emit nothing. Production
         *  passes Context::renderMiscText (the render-side authority) —
         *  the default drops misc, acceptable only in tests. */
        renderMisc: (List<String>) -> String? = { null },
    ): String {
        if (words.isEmpty()) return ""
        val sb = StringBuilder()
        var prevWasTarget = false
        words.forEach { entry ->
            val isTarget = entry.word in highlightedWords
            // Target cells run bigger than context cells (title 23px vs
            // 20px, definitions 18px vs 17px at the 20px deck base). The
            // bumps ride inline per element because target/context share
            // classes and the structured path has no descendant selectors.
            // (An accent-coloured target headword was tried and rejected
            // on device — keep it text-coloured.)
            val titleSize = if (isTarget) "font-size:1.15em;" else ""
            val defSize = if (isTarget) "font-size:0.9em;" else ""
            // The first context row after the target block drops its
            // hairline — the cells' surfaces already separate the groups.
            val cellExtra = if (!isTarget && prevWasTarget) "border-top:0;" else ""
            // data-pt-w keys the cell to the sentence body's word wrappers
            // so the back's tap-to-scroll can find it. Inert everywhere else.
            sb.append("<div data-pt-w=\"").append(htmlEscape(entry.word)).append("\" ")
                .append(styler(if (isTarget) "gl-w-target" else "gl-w", cellExtra)).append(">")
            prevWasTarget = isTarget

            // Head row: word, reading (flex remainder), audio circle.
            sb.append("<div ${styler("gl-w-head", "")}>")
            sb.append("<span ${styler("gl-w-word", titleSize)}>")
                .append(htmlEscape(entry.word)).append("</span>")
            // Kana for the pitch contour: the reading, or (kana-only entries)
            // the all-kana word — mirrors the word card / WordResultCell. The
            // kana-only branch is gated on renderPitch so the structured
            // WORDS_TABLE path is unchanged; the all-kana guard keeps the
            // contour off kanji.
            val pitchKana = when {
                entry.reading.isNotEmpty() -> entry.reading
                renderPitch && entry.pitch.isNotEmpty() &&
                    entry.word.isNotEmpty() && entry.word.all(Deinflector::isKana) -> entry.word
                else -> ""
            }
            // The reading span renders even when empty — it carries the
            // flex:1 that pushes the audio circle to the cell's right edge.
            sb.append("<span ${styler("gl-w-read gl-hint", "")}>")
            if (pitchKana.isNotEmpty()) {
                // Pitch contour (default-model back only); the diagram
                // contains the kana, so it replaces the plain reading.
                val pitchHtml = if (renderPitch) {
                    PitchAccentHtml.pitchAccentHtml(pitchKana, entry.pitch)
                } else ""
                if (pitchHtml.isNotEmpty()) sb.append(pitchHtml)
                else sb.append(htmlEscape(entry.reading))
            }
            sb.append("</span>")
            wordAudioFilenames[entry.word]?.let {
                sb.append("<span ${styler("pt-audio", "")}>[sound:$it]</span>")
            }
            sb.append("</div>")

            // Meta row: Common pill, ★ run, one chip per frequency dict.
            if (entry.isCommon || entry.freqScore > 0 || entry.frequencies.isNotEmpty()) {
                sb.append("<div ${styler("gl-meta", "")}>")
                if (entry.isCommon) {
                    sb.append("<span ${styler("gl-pill gl-secondary", "")}>")
                        .append(htmlEscape(commonLabel)).append("</span>")
                }
                if (entry.freqScore > 0) {
                    // starsString emits only the ★ glyph repeated, so it's
                    // HTML-safe by construction.
                    sb.append("<span ${styler("gl-stars gl-secondary", "")}>")
                        .append(starsString(entry.freqScore)).append("</span>")
                }
                entry.frequencies.forEach { tag ->
                    sb.append("<span ${styler("gl-chip gl-secondary", "")}>")
                        .append(htmlEscape("${tag.source}: ${tag.display}")).append("</span>")
                }
                sb.append("</div>")
            }

            // Senses: numbered rows, POS header only on change (the lens's
            // rule — WordDefinitionsView keeps the same previousPos state).
            // Caps were rejected by design review: every sense renders.
            if (entry.senses.isNotEmpty()) {
                var previousPos: List<String>? = null
                entry.senses.forEachIndexed { i, sense ->
                    if (sense.pos.isNotEmpty() && sense.pos != previousPos) {
                        // Imported headers are display text (dictionary name ·
                        // tags), never localized; caps come from the CSS
                        // text-transform, not Kotlin.
                        val label =
                            if (sense.imported) sense.pos.joinToString(" · ")
                            else localizePos(sense.pos)
                        sb.append("<div ${styler("gl-pos-h gl-secondary", "")}>")
                            .append(htmlEscape(label)).append("</div>")
                        previousPos = sense.pos
                    }
                    sb.append("<div ${styler("gl-def", "")}>")
                        .append("<span ${styler("gl-num gl-hint", defSize)}>")
                        .append(i + 1).append(".</span>")
                        .append("<span ${styler("gl-dtext", defSize)}>")
                        .append(htmlEscape(sense.definition)).append("</span>")
                        .append("</div>")
                    renderMisc(sense.misc)?.let { misc ->
                        sb.append("<div ${styler("gl-misc gl-hint", "margin-left:25px;")}>")
                            .append(htmlEscape(misc)).append("</div>")
                    }
                }
            } else {
                // No structured senses (no dictionary entry, or a pre-senses
                // producer): today's flat meaning lines, already numbered.
                entry.meaning.split("\n").filter { it.isNotBlank() }.forEach { line ->
                    sb.append("<div ${styler("gl-dtext gl-secondary", defSize + "margin-top:6px;")}>")
                        .append(htmlEscape(line)).append("</div>")
                }
            }
            sb.append("</div>")
        }
        return sb.toString()
    }
}
