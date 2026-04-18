package com.playtranslate.ui

import com.playtranslate.dictionary.Deinflector
import com.playtranslate.language.SourceLangId

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
        val wordsHtml = buildWordsHtml(sorted, highlightedWords)
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

    private fun buildWordsHtml(
        words: List<WordEntry>,
        highlightedWords: Set<String> = emptySet()
    ): String {
        if (words.isEmpty()) return ""
        val sb = StringBuilder()
        words.forEach { entry ->
            val isHighlighted = entry.word in highlightedWords
            if (isHighlighted) {
                sb.append("<div class=\"gl-hl-bg\" style=\"margin-bottom:14px;border-radius:6px;padding:8px 10px;\">")
                sb.append("<div class=\"gl-hl\"><b>${entry.word}</b></div>")
            } else {
                sb.append("<div style=\"margin-bottom:14px;\">")
                sb.append("<div><b>${entry.word}</b></div>")
            }
            if (entry.reading.isNotEmpty() || entry.freqScore > 0) {
                sb.append("<div style=\"font-size:0.85em;\">")
                if (entry.reading.isNotEmpty()) sb.append("<span class=\"gl-hint\">${entry.reading}</span>")
                if (entry.freqScore > 0) sb.append(" <span style=\"color:#606060;\">${starsString(entry.freqScore)}</span>")
                sb.append("</div>")
            }
            val style = if (isHighlighted) "margin-left:10px;font-weight:bold;" else "margin-left:10px;"
            entry.meaning.split("\n").filter { it.isNotBlank() }.forEach { line ->
                sb.append("<div class=\"gl-secondary\" style=\"$style\">$line</div>")
            }
            sb.append("</div>")
        }
        return sb.toString()
    }
}
