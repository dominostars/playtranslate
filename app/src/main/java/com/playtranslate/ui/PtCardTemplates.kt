package com.playtranslate.ui

/**
 * Card-template sources (qfmt / afmt / CSS / template JS) for the
 * field-based PlayTranslate note types defined in [PtModels]. Pure
 * string constants so [com.playtranslate.AnkiManager.getOrCreatePtModel]
 * can ship them through AnkiDroid's content provider and unit tests can
 * pin their structure without a device.
 *
 * Layout parity target is the FIELD-BLOB styling of the retired v005
 * cards (WordAnkiHtmlBuilder / SentenceAnkiHtmlBuilder), not the v005
 * model's qfmt/afmt — those were silently discarded by AnkiDroid's
 * model insert (the provider has no template columns on the models
 * table), so what users actually saw was Anki's auto-generated
 * template plus the blob's embedded `<style>`.
 *
 * Front markup is wrapped in `pt-q`, back markup in `pt-a`. Because no
 * afmt here includes `{{FrontSide}}`, the back never contains front
 * markup — the v005 blob's `body{visibility:hidden}` /
 * `#answer{display:none}` hacks (which existed to suppress the
 * auto-template's FrontSide echo) have no equivalent.
 */
internal object PtCardTemplates {

    /**
     * Theme-adaptive palette + shared content classes, verbatim from
     * the v005 model CSS so field HTML built with [classStyler]
     * (words table, per-sense definitions) renders identically.
     */
    private const val COLOR_CSS =
        "@media(prefers-color-scheme:light){" +
        ".card{background-color:#F0F0F0;color:#1C1C1C}" +
        ".gl-secondary{color:#505050}" +
        ".gl-hint{color:#909090}" +
        ".gl-hl{color:#B34700}" +
        ".gl-hl-bg{background:#B3470026}" +
        "}" +
        "@media(prefers-color-scheme:dark){" +
        ".card{background-color:#1A1A1A;color:#EFEFEF}" +
        ".gl-secondary{color:#A0A0A0}" +
        ".gl-hint{color:#606060}" +
        ".gl-hl{color:#E8C07A}" +
        ".gl-hl-bg{background:#E8C07A26}" +
        "}"

    /**
     * Per-sense definition classes, lifted from the `<style>` block the
     * v005 word-card back used to embed (WordAnkiHtmlBuilder). The
     * Definition/Examples fields carry [classStyler]-built HTML that
     * references these.
     */
    private const val DEFINITION_CSS =
        ".gl-sense{margin:14px 4px;}" +
        ".gl-pos{font-size:0.78em;letter-spacing:0.08em;color:#888;text-transform:uppercase;}" +
        ".gl-gloss{font-size:1.1em;margin-top:4px;}" +
        ".gl-misc{font-size:0.85em;color:#888;font-style:italic;margin-top:2px;}" +
        ".gl-ex{margin:8px 0 0 8px;padding-left:10px;border-left:2px solid #6cd1c2;}" +
        ".gl-ex-tr{font-size:0.92em;color:#888;margin-top:2px;}" +
        ".gl-section{font-size:0.78em;letter-spacing:0.08em;color:#888;text-transform:uppercase;margin:18px 4px 6px;}"

    /** Layout classes shared by both models (media/credit blocks). */
    private const val MEDIA_CSS =
        ".pt-pic{text-align:center;margin:12px 0;}" +
        ".pt-pic img{max-width:100%;border-radius:6px;}" +
        ".pt-audio{text-align:center;margin:8px 0;}" +
        ".pt-credit{text-align:center;font-size:0.7em;opacity:0.6;margin:0 4px 8px;}"

    /**
     * Everything both models need. [PitchAccentHtml.PITCH_CSS] is
     * spliced by reference (never copied) so the template CSS can't
     * drift from the Kotlin renderer that bakes `pa-*` markup into the
     * sentence card's WordsTable field.
     */
    private val SHARED_CSS =
        COLOR_CSS + DEFINITION_CSS + MEDIA_CSS + PitchAccentHtml.PITCH_CSS

    /** Word-card layout, mirroring the v005 word blob's inline styles. */
    private const val WORD_LAYOUT_CSS =
        ".pt-word-front{text-align:center;font-size:2.2em;padding:32px 16px;}" +
        ".pt-word{text-align:center;font-size:1.8em;padding:12px 4px;}" +
        ".pt-reading{text-align:center;font-size:1.1em;color:#888;}" +
        ".pt-pos{text-align:center;font-size:0.85em;color:#888;}" +
        ".pt-freq{text-align:center;font-size:0.9em;color:#888;margin-top:4px;}" +
        ".pt-freq ul{list-style:none;padding:0;text-align:center;margin:6px 0;}" +
        ".pt-gap{margin-bottom:12px;}"

    /**
     * Sentence-card layout. The question side hides ruby readings
     * (`.pt-q ruby rt`) and reveals them via the tap tooltip
     * ([TOOLTIP_JS]); the answer side has no such rule, so readings
     * show — same UX as the v005 blob front/back. `.pt-sentence b`
     * restores the 800 weight the blob used for highlighted words
     * (a bare `<b>` renders at 700).
     */
    // Front line-height is 1.6em, NOT the v005 blob's 2.8em: readings are
    // hidden on the question side, so nothing needs ruby headroom, and the
    // furigana field preserves source line breaks as <br> (the old front
    // flattened them to spaces) — at 2.8em those breaks read as huge gaps.
    private const val SENTENCE_LAYOUT_CSS =
        ".pt-sentence-front{text-align:center;font-size:1.5em;padding:20px;line-height:1.6em;}" +
        ".pt-sentence-back{text-align:center;font-size:1.5em;margin:12px 4px;line-height:2.2em;}" +
        ".pt-translation{text-align:center;font-size:1.2em;margin:12px 4px;}" +
        ".pt-words{text-align:left;margin-top:8px;}" +
        ".pt-sentence b{font-weight:800;}" +
        ".pt-q ruby{cursor:pointer;-webkit-tap-highlight-color:transparent;}" +
        ".pt-q ruby rt{display:none;}" +
        ".gl-tip{position:fixed;background:rgba(40,40,40,0.93);color:#fff;padding:6px 16px;border-radius:8px;font-size:20px;pointer-events:none;z-index:9999;white-space:nowrap;box-shadow:0 2px 8px rgba(0,0,0,0.45);}" +
        ".gl-tip::after{content:'';position:absolute;top:100%;left:50%;transform:translateX(-50%);border:6px solid transparent;border-top-color:rgba(40,40,40,0.93);}"

    val WORD_CSS: String = SHARED_CSS + WORD_LAYOUT_CSS
    val SENTENCE_CSS: String = SHARED_CSS + SENTENCE_LAYOUT_CSS

    /**
     * Draws the pitch-accent contour from the raw downstep list in the
     * hidden `#pt-pitch-pos` span — a JS port of [Mora.segment],
     * [Mora.contour], and [PitchAccentHtml.pitchAccentHtml], sharing
     * their `pa-*` CSS. Behavior contract (pinned by PitchAccentHtmlTest
     * on the Kotlin side, device-validated here):
     *  - kana source: `#pt-reading` text, else `#pt-word` when it is
     *    all-kana (kana-only entries carry no separate reading); never
     *    draw morae over kanji;
     *  - only the FIRST downstep is drawn; all variants are listed in a
     *    ` [0]·[2]` suffix;
     *  - mora spans are appended with no intervening whitespace (a gap
     *    breaks the continuous overline);
     *  - any bail (no pitch, no kana, kanji reading) leaves the plain
     *    reading untouched — which is also the no-JS/AnkiWeb fallback.
     *
     * `#pt-pitch-pos` is read via `textContent` from a display:none
     * span rather than an HTML attribute, so a stray quote a user
     * edits into the field can't break the markup.
     */
    // Built with string concatenation (matching TOOLTIP_JS below and the
    // repo's existing inline-script idiom) so there's no raw-string
    // ${...} interpolation hazard around JS template literals.
    val PITCH_JS: String =
        "(function(){" +
        "function ptIsKana(s){return /^[\\u3040-\\u309F\\u30A0-\\u30FF]+$/.test(s);}" +
        "function ptMorae(s){" +
        "var SMALL='ぁぃぅぇぉゃゅょゎァィゥェォャュョヮ';" +
        "var out=[],i=0;" +
        "while(i<s.length){var e=i+1;while(e<s.length&&SMALL.indexOf(s[e])>=0)e++;out.push(s.substring(i,e));i=e;}" +
        "return out;}" +
        "function ptContour(down,n){" +
        "var d=Math.max(0,Math.min(down,n));var high=[],k;" +
        "if(d===0){for(k=0;k<n;k++)high.push(k>0);return{high:high,ghost:true};}" +
        "for(k=0;k<n;k++){var m=k+1;high.push(d===1?m===1:(m>=2&&m<=d));}" +
        "return{high:high,ghost:false};}" +
        "var posEl=document.getElementById('pt-pitch-pos');" +
        "var readEl=document.getElementById('pt-reading');" +
        "var wordEl=document.getElementById('pt-word');" +
        "if(!posEl||!readEl)return;" +
        "var pitch=posEl.textContent.split(',').map(function(t){return parseInt(t,10);}).filter(function(v){return !isNaN(v);});" +
        "if(!pitch.length)return;" +
        "var kana=readEl.textContent.trim();" +
        "if(!kana&&wordEl){var w=wordEl.textContent.trim();if(w&&ptIsKana(w))kana=w;}" +
        "if(!kana||!ptIsKana(kana))return;" +
        "var morae=ptMorae(kana);if(!morae.length)return;" +
        "var n=morae.length,c=ptContour(pitch[0],n);" +
        "var pa=document.createElement('span');pa.className='pa';" +
        "for(var k=0;k<n;k++){" +
        "var drops=c.high[k]&&(k+1<n?!c.high[k+1]:!c.ghost);" +
        "var sp=document.createElement('span');" +
        "sp.className='pa-m'+(c.high[k]?' pa-h':'')+(drops?' pa-d':'');" +
        "sp.textContent=morae[k];pa.appendChild(sp);}" +
        "var suffix=document.createElement('span');suffix.className='pa-pos';" +
        "suffix.textContent=' '+pitch.map(function(p){return '['+p+']';}).join('·');" +
        "readEl.textContent='';readEl.appendChild(pa);readEl.appendChild(suffix);" +
        "})()"

    /**
     * Tap-to-reveal furigana tooltip for the sentence card's question
     * side — the v005 front blob's inline script (proven in AnkiDroid's
     * WebView), moved into the template so it isn't duplicated per
     * note, with the selector retargeted from `.gl-front ruby` to
     * `.pt-q ruby`.
     */
    val TOOLTIP_JS: String =
        "(function(){" +
        "var tip=null,activeR=null;" +
        "function hide(){if(tip){tip.parentNode.removeChild(tip);tip=null;}activeR=null;}" +
        "function showTip(r,e){" +
        "e.stopPropagation();e.preventDefault();" +
        "if(activeR===r){hide();return;}" +
        "hide();" +
        "var rt=r.querySelector('rt');if(!rt)return;" +
        "var rect=r.getBoundingClientRect();" +
        "tip=document.createElement('div');tip.className='gl-tip';" +
        "tip.textContent=rt.textContent;" +
        "tip.style.left=(rect.left+rect.width/2)+'px';" +
        "tip.style.top=rect.top+'px';" +
        "tip.style.transform='translate(-50%,calc(-100% - 8px))';" +
        "document.body.appendChild(tip);" +
        "activeR=r;" +
        "}" +
        "var hasHover=window.matchMedia('(hover:hover)').matches;" +
        "document.querySelectorAll('.pt-q ruby').forEach(function(r){" +
        "r.addEventListener('touchend',function(e){showTip(r,e);});" +
        "r.addEventListener('click',function(e){showTip(r,e);});" +
        "if(hasHover){" +
        "r.addEventListener('mouseenter',function(e){activeR=null;showTip(r,e);});" +
        "r.addEventListener('mouseleave',function(){hide();});" +
        "}" +
        "});" +
        "document.addEventListener('touchend',function(e){if(activeR&&!activeR.contains(e.target))hide();});" +
        "document.addEventListener('click',function(e){if(activeR&&!activeR.contains(e.target))hide();});" +
        "})()"

    val WORD_QFMT: String =
        "<div class=\"pt-q pt-word-front\">{{Expression}}</div>"

    /**
     * Word back, section order matching the v005 blob: picture, audio,
     * credit, word, reading (contour drawn over it by [PITCH_JS]), POS,
     * frequency list, rule, definition senses, more-examples. The
     * `pt-reading` div always renders — when Reading and PitchPosition
     * are both empty it's a zero-height div, and the JS needs a stable
     * mount point.
     */
    val WORD_AFMT: String =
        "<div class=\"pt-a\">" +
        "{{#Picture}}<div class=\"pt-pic\">{{Picture}}</div>{{/Picture}}" +
        "{{#WordAudio}}<div class=\"pt-audio\">{{WordAudio}}</div>{{/WordAudio}}" +
        "{{#AudioCredit}}<div class=\"pt-credit\">{{AudioCredit}}</div>{{/AudioCredit}}" +
        "<div class=\"pt-word\" id=\"pt-word\">{{Expression}}</div>" +
        "<div class=\"pt-reading\" id=\"pt-reading\">{{Reading}}</div>" +
        "<span id=\"pt-pitch-pos\" style=\"display:none\">{{PitchPosition}}</span>" +
        "{{#PartOfSpeech}}<div class=\"pt-pos\">{{PartOfSpeech}}</div>{{/PartOfSpeech}}" +
        "{{#Frequency}}<div class=\"pt-freq\">{{Frequency}}</div>{{/Frequency}}" +
        "<div class=\"pt-gap\"></div>" +
        "<hr>" +
        "{{Definition}}" +
        "{{Examples}}" +
        "</div>" +
        "<script>$PITCH_JS</script>"

    /**
     * Shared sentence body: the furigana bracket field through Anki's
     * `{{furigana:}}` filter when present, else the plain sentence.
     * Both variants carry the `<b>` highlight markup, so non-JA/ZH
     * cards (whose SentenceFurigana field is empty) keep their bolding.
     */
    private const val SENTENCE_BODY =
        "{{#SentenceFurigana}}{{furigana:SentenceFurigana}}{{/SentenceFurigana}}" +
        "{{^SentenceFurigana}}{{Sentence}}{{/SentenceFurigana}}"

    val SENTENCE_QFMT: String =
        "<div class=\"pt-q pt-sentence pt-sentence-front\">$SENTENCE_BODY</div>" +
        "<script>$TOOLTIP_JS</script>"

    /**
     * Sentence back, section order matching the v005 blob: picture,
     * audio, credit, furigana sentence (readings visible — no rt
     * hiding outside `pt-q`), translation, rule, words table.
     */
    val SENTENCE_AFMT: String =
        "<div class=\"pt-a\">" +
        "{{#Picture}}<div class=\"pt-pic\">{{Picture}}</div>{{/Picture}}" +
        "{{#SentenceAudio}}<div class=\"pt-audio\">{{SentenceAudio}}</div>{{/SentenceAudio}}" +
        "{{#AudioCredit}}<div class=\"pt-credit\">{{AudioCredit}}</div>{{/AudioCredit}}" +
        "<div class=\"pt-sentence pt-sentence-back\">$SENTENCE_BODY</div>" +
        "{{#Translation}}<div class=\"gl-secondary pt-translation\">{{Translation}}</div>{{/Translation}}" +
        "{{#WordsTable}}<hr><div class=\"pt-words\">{{WordsTable}}</div>{{/WordsTable}}" +
        "</div>"
}
