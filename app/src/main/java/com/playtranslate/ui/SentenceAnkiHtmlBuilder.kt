package com.playtranslate.ui

import android.util.Log
import com.playtranslate.dictionary.Deinflector
import com.playtranslate.language.SourceLangId

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

    data class WordEntry(val word: String, val reading: String, val meaning: String, val freqScore: Int = 0, val surfaceForm: String = "")

    /**
     * @param highlightedWords words to bold in the sentence (font-weight:800)
     */
    fun buildFrontHtml(
        japanese: String, words: List<WordEntry>,
        highlightedWords: Set<String> = emptySet(),
        sourceLangId: SourceLangId = SourceLangId.JA
    ): String {
        val clean = japanese.replace(Regex("[\\n\\r]+"), " ").trim()
        val wordMap = words.associate { it.word to it.reading }.toMutableMap()
        val expanded = highlightedWords.toMutableSet()
        // Add conjugated surface forms so they get direct-matched and bolded
        for (entry in words) {
            if (entry.surfaceForm.isNotEmpty() && entry.surfaceForm != entry.word
                && entry.word in highlightedWords) {
                wordMap.putIfAbsent(entry.surfaceForm, "")
                expanded.add(entry.surfaceForm)
            }
        }
        val annotated = annotateText(clean, wordMap, newlineAsBr = false, highlightedWords = expanded, sourceLangId = sourceLangId)
        return buildString {
            append("<style>")
            append(".gl-front ruby{cursor:pointer;-webkit-tap-highlight-color:transparent;}")
            append(".gl-front ruby rt{display:none;}")
            append(".gl-tip{position:fixed;background:rgba(40,40,40,0.93);color:#fff;padding:6px 16px;border-radius:8px;font-size:20px;pointer-events:none;z-index:9999;white-space:nowrap;box-shadow:0 2px 8px rgba(0,0,0,0.45);}")
            append(".gl-tip::after{content:'';position:absolute;top:100%;left:50%;transform:translateX(-50%);border:6px solid transparent;border-top-color:rgba(40,40,40,0.93);}")
            append("</style>")
            append("<div class=\"gl-front\" style=\"text-align:center;font-size:1.5em;padding:20px;line-height:2.8em;\">$annotated</div>")
            append("<script>(function(){")
            append("var tip=null,activeR=null;")
            append("function hide(){if(tip){tip.parentNode.removeChild(tip);tip=null;}activeR=null;}")
            append("function showTip(r,e){")
            append("e.stopPropagation();e.preventDefault();")
            append("if(activeR===r){hide();return;}")
            append("hide();")
            append("var rt=r.querySelector('rt');if(!rt)return;")
            append("var rect=r.getBoundingClientRect();")
            append("tip=document.createElement('div');tip.className='gl-tip';")
            append("tip.textContent=rt.textContent;")
            append("tip.style.left=(rect.left+rect.width/2)+'px';")
            append("tip.style.top=rect.top+'px';")
            append("tip.style.transform='translate(-50%,calc(-100% - 8px))';")
            append("document.body.appendChild(tip);")
            append("activeR=r;")
            append("}")
            append("var hasHover=window.matchMedia('(hover:hover)').matches;")
            append("document.querySelectorAll('.gl-front ruby').forEach(function(r){")
            append("r.addEventListener('touchend',function(e){showTip(r,e);});")
            append("r.addEventListener('click',function(e){showTip(r,e);});")
            append("if(hasHover){")
            append("r.addEventListener('mouseenter',function(e){activeR=null;showTip(r,e);});")
            append("r.addEventListener('mouseleave',function(){hide();});")
            append("}")
            append("});")
            append("document.addEventListener('touchend',function(e){if(activeR&&!activeR.contains(e.target))hide();});")
            append("document.addEventListener('click',function(e){if(activeR&&!activeR.contains(e.target))hide();});")
            append("})()</script>")
        }
    }

    /**
     * @param highlightedWords words that get sorted to top and styled with highlight background
     * @param highlightColor CSS color for the highlighted word rows (e.g. "#E8C07A")
     */
    fun buildBackHtml(
        japanese: String, english: String, words: List<WordEntry>,
        imageFilename: String?, highlightedWords: Set<String> = emptySet(),
        sourceLangId: SourceLangId = SourceLangId.JA
    ): String {
        val wordMap = words.associate { it.word to it.reading }
        val furigana = annotateText(japanese, wordMap, newlineAsBr = true, sourceLangId = sourceLangId)
        val sorted = if (highlightedWords.isNotEmpty()) {
            words.sortedByDescending { it.word in highlightedWords }
        } else words
        // Legacy back HTML wraps a <style> block that defines the gl-*
        // classes. classStyler emits class refs that the surrounding
        // <style> applies — no inline duplication.
        val wordsHtml = buildWordsHtmlWith(sorted, highlightedWords, classStyler)
        return buildString {
            append("<style>")
            append("body{visibility:hidden!important;white-space:normal!important;}")
            append(".gl-front{display:none!important;}")
            append("#answer{display:none!important;}")
            append(".gl-back{visibility:visible!important;}")
            append("</style>")
            append("<div class=\"gl-back\">")
            if (imageFilename != null) {
                append("<div style=\"text-align:center;margin:12px 0;\">")
                append("<img src=\"$imageFilename\" style=\"max-width:100%;border-radius:6px;\">")
                append("</div>")
            }
            append("<div style=\"text-align:center;font-size:1.5em;margin:12px 4px;line-height:2.2em;\">$furigana</div>")
            append("<div class=\"gl-secondary\" style=\"text-align:center;font-size:1.2em;margin:12px 4px;\">")
            append(english.replace(Regex("[\\n\\r]+"), "<br>"))
            append("</div>")
            if (wordsHtml.isNotEmpty()) {
                append("<hr>")
                append("<div style=\"text-align:left;margin-top:8px;\">$wordsHtml</div>")
            }
            append("</div>")
        }
    }

    fun annotateText(
        text: String, wordMap: Map<String, String>,
        newlineAsBr: Boolean, highlightedWords: Set<String> = emptySet(),
        sourceLangId: SourceLangId = SourceLangId.JA
    ): String {
        if (wordMap.isEmpty()) return text
        val sortedWords = wordMap.entries
            .filter { it.key.isNotEmpty() }
            .sortedByDescending { it.key.length }
        val useDeinflection = sourceLangId == SourceLangId.JA
        val useRuby = sourceLangId == SourceLangId.JA || sourceLangId == SourceLangId.ZH || sourceLangId == SourceLangId.ZH_HANT
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n') {
                sb.append(if (newlineAsBr) "<br>" else " ")
                i++
                continue
            }
            val direct = sortedWords.firstOrNull { (word, _) -> text.startsWith(word, i) }
            if (direct != null) {
                val (w, r) = direct
                val isBold = w in highlightedWords
                if (isBold) sb.append("<span style=\"font-weight:800;\">")
                val hasCjk = w.any { it in '\u4e00'..'\u9fff' || it in '\u3400'..'\u4dbf' }
                if (useRuby && hasCjk && r.isNotEmpty() && r != w) {
                    sb.append("<ruby>$w<rt>$r</rt></ruby>")
                } else {
                    sb.append(w)
                }
                if (isBold) sb.append("</span>")
                i += w.length
                continue
            }
            // Deinflection fallback: only for Japanese (Kuromoji-based)
            if (useDeinflection) {
                val isCjk = c in '\u3000'..'\u9fff' || c in '\uf900'..'\ufaff'
                if (isCjk) {
                    val maxEnd = minOf(i + 12, text.length)
                    var deinflected = false
                    for (end in maxEnd downTo i + 1) {
                        val sub = text.substring(i, end)
                        val matchedEntry = Deinflector.candidates(sub)
                            .asSequence()
                            .mapNotNull { cand -> sortedWords.firstOrNull { it.key == cand.text } }
                            .firstOrNull()
                        if (matchedEntry != null) {
                            val isBold = matchedEntry.key in highlightedWords
                            val r = matchedEntry.value
                            val subHasCjk = sub.any { it in '\u4e00'..'\u9fff' || it in '\u3400'..'\u4dbf' }
                            if (isBold) sb.append("<span style=\"font-weight:800;\">")
                            if (subHasCjk && r.isNotEmpty() && r != sub) {
                                sb.append("<ruby>$sub<rt>$r</rt></ruby>")
                            } else {
                                sb.append(sub)
                            }
                            if (isBold) sb.append("</span>")
                            i = end
                            deinflected = true
                            break
                        }
                    }
                    if (deinflected) continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    fun starsString(score: Int) = "\u2605".repeat(score)

    /**
     * Emits the SENTENCE field value: Anki-native furigana brackets
     * (`kanji[reading]`) for Japanese with each kanji bracket
     * **isolated by `<wbr>` separators**. Plain text passthrough for
     * everything else.
     *
     * The goal is to match the semantics of PT's existing furigana
     * display (DictionaryManager.tokenizeForFurigana / FuriganaSpan
     * in TranslationResultFragment): furigana floats above ONLY the
     * kanji surface, never the okurigana. Tap \u805e in \u805e\u3044\u305f \u2192 see \u304d
     * (not \u304d\u3044, not \u304d\u3044\u305f).
     *
     * **Why `<wbr>` is the right separator.** Two downstream
     * consumers each need a boundary signal between bracket-words
     * and the kana that follows, and they need it in formats that
     * don't show up as visible whitespace in the rendered card:
     *
     *  1. **Anki's `{{furigana:}}` filter** regex
     *     ` ?([^ >]+?)\[(.+?)\]` reads everything before `[` (except
     *     space and `>`) as the ruby base. `<wbr>` works as an
     *     anchor because the trailing `>` in the tag is excluded
     *     from the `[^ >]` character class \u2014 the regex can't span
     *     across a `<wbr>` into the next bracket's base. So inserting
     *     `<wbr>` between bracket-words gives each kanji its own
     *     correct ruby base without inserting a visible space.
     *
     *  2. **Migaku's `support.html` parser** treats everything from
     *     a kanji bracket until the next whitespace as the "word",
     *     and surfaces `reading + word_post` in its tap-popup.
     *     `<wbr>` is a standard HTML word-break-opportunity element;
     *     if Migaku's parser is DOM-aware it treats `<wbr>` as a
     *     word boundary. (If Migaku uses a `\s` raw-text regex
     *     instead, `<wbr>` is literal text and this won't fix
     *     Migaku's popup \u2014 to be verified on the user's device.)
     *
     * `<wbr>` renders as zero-width in HTML, so the rendered card
     * has natural Japanese text with no visible inter-word spaces \u2014
     * works cleanly on Lapis, JPMN, custom templates, and Migaku
     * alike (assuming Migaku's DOM-awareness pans out).
     *
     * Examples:
     *  - \u805e\u3044\u305f       \u2192 `\u805e[\u304d]<wbr>\u3044\u305f`
     *  - \u53cb\u9054\u306b\u805e\u3044\u305f \u2192 `\u53cb\u9054[\u3068\u3082\u3060\u3061]<wbr>\u306b<wbr>\u805e[\u304d]<wbr>\u3044\u305f`
     *  - \u4eca\u5ea6\u306fC      \u2192 `\u4eca\u5ea6[\u3053\u3093\u3069]<wbr>\u306fC`
     *  - \u53d6\u308a\u51fa\u3059     \u2192 `\u53d6[\u3068]<wbr>\u308a<wbr>\u51fa[\u3060]<wbr>\u3059`
     *
     * Non-Japanese languages: plain text passthrough (newlines \u2192
     * `<br>`). ZH pinyin annotation would need a different
     * per-character source (follow-up).
     */
    fun buildSentenceFurigana(
        text: String,
        sourceLangId: SourceLangId = SourceLangId.JA,
    ): String {
        if (sourceLangId != SourceLangId.JA) return plainBody(text)
        val sb = StringBuilder()
        for (token in Deinflector.tokenizeWithReadings(text)) {
            emitFuriganaToken(sb, token)
        }
        val out = stripBoundarySeparators(sb.toString())
        Log.d(TAG, "buildSentenceFurigana: in='$text' out='$out'")
        return out
    }

    private fun emitFuriganaToken(sb: StringBuilder, token: Deinflector.ReadingToken) {
        val surface = token.surface
        val reading = token.reading
        if (!token.hasKanji || reading.isNullOrEmpty()) {
            // Pure-kana / non-CJK tokens emit as plain text. No
            // surrounding separators \u2014 Migaku doesn't tap-popup
            // non-bracket text, so there's no reason to give them
            // word boundaries.
            appendPlain(sb, surface)
            return
        }
        for (part in Deinflector.splitFurigana(surface, reading)) {
            val r = part.reading
            if (r != null) {
                // `<wbr>kanji[reading]<wbr>` \u2014 leading `<wbr>` anchors
                // Anki's regex (the `>` in the tag is excluded from
                // its base-text class so the regex can't span back
                // into preceding kana), trailing `<wbr>` isolates the
                // bracket-word from any following kana so Migaku's
                // word_post stays empty. See KDoc for full rationale.
                sb.append(WBR).append(part.text).append('[').append(r).append(']').append(WBR)
            } else {
                appendPlain(sb, part.text)
            }
        }
    }

    /**
     * Emits the EXPRESSION field value for word-mode (single-word)
     * sends: per-kanji furigana brackets with the same `<wbr>`
     * isolation rule as [buildSentenceFurigana]. The caller already
     * knows the headword's dictionary form + reading so we skip
     * Kuromoji and per-kanji-split via [Deinflector.splitFurigana].
     *
     * Examples:
     *  - \u805e\u304f     \u2192 `\u805e[\u304d]<wbr>\u304f`
     *  - \u53d6\u308a\u51fa\u3059 \u2192 `\u53d6[\u3068]<wbr>\u308a<wbr>\u51fa[\u3060]<wbr>\u3059`
     */
    fun buildExpressionFurigana(
        word: String,
        reading: String,
        sourceLangId: SourceLangId = SourceLangId.JA,
    ): String {
        if (sourceLangId != SourceLangId.JA || reading.isEmpty()) return word
        if (!word.any(::isKanjiChar)) return word
        val sb = StringBuilder()
        for (part in Deinflector.splitFurigana(word, reading)) {
            val r = part.reading
            if (r != null) sb.append(WBR).append(part.text).append('[').append(r).append(']').append(WBR)
            else sb.append(part.text)
        }
        val out = stripBoundarySeparators(sb.toString())
        Log.d(TAG, "buildExpressionFurigana: word='$word' reading='$reading' out='$out'")
        return out
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
                sb.append(c)
                i++
            }
        }
    }

    /**
     * Builds the per-word HTML table used at the bottom of the legacy
     * v004 back-side AND in the structured-path WORDS_TABLE output. The
     * [styler] callback decides whether each element carries a `class=""`
     * (legacy path, with the `<style>` block in the surrounding card
     * supplying CSS) or an inline `style=""` (structured path, no
     * surrounding CSS available). `internal` so [AnkiCardOutputBuilder]
     * can pass [inlineStyler] for the structured path.
     */
    internal fun buildWordsHtmlWith(
        words: List<WordEntry>,
        highlightedWords: Set<String>,
        styler: HtmlStyler,
    ): String {
        if (words.isEmpty()) return ""
        val sb = StringBuilder()
        words.forEach { entry ->
            val isHighlighted = entry.word in highlightedWords
            if (isHighlighted) {
                sb.append("<div ${styler("gl-hl-bg", "margin-bottom:14px;border-radius:6px;padding:8px 10px;")}>")
                sb.append("<div ${styler("gl-hl", "")}><b>${entry.word}</b></div>")
            } else {
                sb.append("<div ${styler(null, "margin-bottom:14px;")}>")
                sb.append("<div><b>${entry.word}</b></div>")
            }
            if (entry.reading.isNotEmpty() || entry.freqScore > 0) {
                sb.append("<div ${styler(null, "font-size:0.85em;")}>")
                if (entry.reading.isNotEmpty()) {
                    sb.append("<span ${styler("gl-hint", "")}>${entry.reading}</span>")
                }
                if (entry.freqScore > 0) {
                    sb.append(" <span ${styler(null, "color:#606060;")}>${starsString(entry.freqScore)}</span>")
                }
                sb.append("</div>")
            }
            val extra = if (isHighlighted) "margin-left:10px;font-weight:bold;" else "margin-left:10px;"
            entry.meaning.split("\n").filter { it.isNotBlank() }.forEach { line ->
                sb.append("<div ${styler("gl-secondary", extra)}>$line</div>")
            }
            sb.append("</div>")
        }
        return sb.toString()
    }
}
