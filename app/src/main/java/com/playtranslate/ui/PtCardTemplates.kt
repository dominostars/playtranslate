package com.playtranslate.ui

/**
 * Card-template sources (qfmt / afmt / CSS / template JS) for the
 * field-based PlayTranslate note types defined in [PtModels]. Pure
 * string constants so [com.playtranslate.AnkiManager.getOrCreatePtModel]
 * can ship them through AnkiDroid's content provider and unit tests can
 * pin their structure without a device.
 *
 * This is the v002 design (the 2026-07 card redesign handoff): a strict
 * hierarchy per face — the tested word/sentence, then the meaning, then
 * reading+pitch, then POS/frequency/examples/credits — panel surfaces
 * (`--pt-panel`) for the translation / definition / target-word cell,
 * hairline dividers (`--pt-hairline`) instead of `<hr>`, and a single
 * accent (`gl-hl`) reserved for the target-word underline.
 *
 * Front markup is wrapped in `pt-q`, back markup in `pt-a` — these are
 * load-bearing: [PtModels.classifyStoredTemplate] keys on them to decide
 * who owns a same-named model's templates. Because no afmt here includes
 * `{{FrontSide}}`, the back never contains front markup.
 */
internal object PtCardTemplates {

    /**
     * Theme-adaptive palette + shared content classes. The custom
     * properties carry the redesign's shared surfaces: `--pt-panel` (the
     * translation panel, the definition panel, and a target word's cell
     * — deliberately identical), `--pt-chip` (frequency chips and the
     * audio-glyph circle), `--pt-hairline` (every divider and panel
     * border), plus the four text roles. `.gl-hl` is the card's ONLY
     * accent — the target-word underline; `.gl-hl-bg` is no longer used
     * by the words table but stays defined for inline sentence
     * highlighting.
     *
     * DARK IS DOUBLE-KEYED. AnkiDroid's reviewer WebView does not
     * reliably report `prefers-color-scheme: dark` — its night theme is
     * signalled by stamping a `night_mode` (desktop: `nightMode`) class
     * on the card instead, so a media-query-only palette leaves the
     * card on the light colors under AnkiDroid's own dark chrome
     * (device-observed on Thor: washed-out near-black instead of
     * #1A1A1A). Dark tokens therefore apply under EITHER the media
     * query (AnkiWeb, desktop, WebViews that do report it) OR the
     * night-mode classes. The `.card` background/foreground stay
     * literal hex per selector — never `var()` — because AnkiDroid
     * reads the card background out of the CSS text to paint the
     * window behind the card.
     *
     * THE ACCENT IS INJECTED AT MODEL CREATION. `--pt-hl`/`--pt-hl-bg`
     * default to the app's default accent (Aqua) below, and
     * [com.playtranslate.AnkiManager.getOrCreatePtModel] appends a
     * `:root` override carrying the user's CURRENT accent when it
     * creates the note type (theme-invariant, like the app's own
     * accent). Changing the app accent later does NOT retint existing
     * note types — model CSS is never rewritten.
     */
    // --pt-target-bg is the panel colour at 35% alpha — the target-word
    // cells wear a borderless translucent surface; the fill and bigger
    // type do the marking (accent headword tried and rejected).
    private const val LIGHT_TOKENS =
        "--pt-fg:#1C1C1C;--pt-secondary:#505050;--pt-hint:#909090;" +
        "--pt-panel:#FFFFFF;--pt-target-bg:rgba(255,255,255,0.35);" +
        "--pt-chip:#E4E4E4;--pt-hairline:rgba(0,0,0,0.10);"

    private const val DARK_TOKENS =
        "--pt-fg:#EFEFEF;--pt-secondary:#A0A0A0;--pt-hint:#606060;" +
        "--pt-panel:#242424;--pt-target-bg:rgba(36,36,36,0.35);" +
        "--pt-chip:#2E2E2E;--pt-hairline:rgba(255,255,255,0.09);"

    /** Accent defaults ([com.playtranslate.ui.AccentColor.Default] = Aqua
     *  #00BCD4; tint = 0x1F alpha, the app's `pt_accent_*_tint`
     *  convention). One value for both themes, like the app. */
    private const val ACCENT_TOKENS = "--pt-hl:#00BCD4;--pt-hl-bg:#00BCD41F;"

    /**
     * Client-chrome reset. AnkiMobile styles the body with
     * `text-align:center`, `margin:15px` (sides under
     * `max(15px, safe-area-inset)`), and a translate3d transform. The
     * transform makes body the containing block for absolute AND fixed
     * descendants, so those margins inset the `.pt-brand` bar and shift
     * the fixed `.gl-tip` tooltips down-right on iOS — AnkiDroid's
     * WebView leaves the viewport as the anchor, which is why Android
     * never showed either. Zero the top/side margins (`!important`, in
     * case theirs land inline) but LEAVE THE BOTTOM ALONE — it is each
     * client's own reserve (100px on AnkiMobile, keeping long content
     * clear of the overlaid answer controls) — and own the content
     * gutter (8px top/bottom, 10px sides) as `.card` padding instead:
     * padding never offsets an
     * absolutely-positioned child, so the bar stays full-bleed
     * everywhere. The centering is countered at `.pt-a`, not here; the
     * fronts set their own alignment.
     */
    private const val RESET_CSS =
        "html,body{margin-top:0!important;margin-left:0!important;" +
            "margin-right:0!important;padding:0}" +
        "#content{margin-top:0;margin-left:0;margin-right:0}" +
        ".card{padding:8px 10px}"

    private const val COLOR_CSS =
        ":root{$LIGHT_TOKENS$ACCENT_TOKENS}" +
        "@media(prefers-color-scheme:dark){:root{$DARK_TOKENS}}" +
        ".night_mode,.nightMode{$DARK_TOKENS}" +
        ".card{background-color:#F0F0F0;color:#1C1C1C}" +
        "@media(prefers-color-scheme:dark){.card{background-color:#1A1A1A;color:#EFEFEF}}" +
        ".card.night_mode,.card.nightMode,.night_mode .card,.nightMode .card{" +
            "background-color:#1A1A1A;color:#EFEFEF}" +
        ".gl-secondary{color:var(--pt-secondary)}" +
        ".gl-hint{color:var(--pt-hint)}" +
        ".gl-hl{color:var(--pt-hl)}" +
        ".gl-hl-bg{background:var(--pt-hl-bg)}"

    /**
     * Word-back senses + examples: flex sense rows (right-aligned number
     * column beside the sense body) inside the `.gl-panel` surface, POS
     * below the gloss, per-sense examples on a hairline left border, and
     * the unpanelled `.gl-rows`/`.gl-row` "More examples" list. The
     * Definition/Examples fields carry [classStyler]-built HTML that
     * references these; [AnkiHtmlStylers.INLINE_STYLES] mirrors them for
     * the structured path.
     */
    private const val DEFINITION_CSS =
        ".gl-sense{display:flex;align-items:baseline;padding:14px 0;" +
            "border-top:1px solid var(--pt-hairline);}" +
        ".gl-sense:first-child{border-top:0;}" +
        ".gl-sense-n{min-width:16px;text-align:right;font-size:0.75em;line-height:1.6;}" +
        ".gl-sense-b{flex:1;margin-left:12px;}" +
        ".gl-gloss{font-size:1.1em;line-height:1.45;}" +
        ".gl-pos{font-size:0.6em;letter-spacing:0.1em;text-transform:uppercase;margin-top:5px;}" +
        ".gl-misc{font-size:0.65em;font-style:italic;margin-top:2px;}" +
        ".gl-ex{font-size:0.75em;line-height:1.6;margin:10px 0 0;padding-left:12px;" +
            "border-left:1px solid var(--pt-hairline);}" +
        ".gl-ex-tr{font-size:0.93em;font-style:italic;line-height:1.5;margin-top:2px;}" +
        ".gl-section{font-size:0.55em;font-weight:500;letter-spacing:0.12em;" +
            "text-transform:uppercase;margin:20px 4px 8px;color:var(--pt-hint);}" +
        ".gl-panel{background:var(--pt-panel);border:1px solid var(--pt-hairline);" +
            "border-radius:14px;padding:2px 16px;}" +
        ".gl-rows{margin:0 4px;}" +
        ".gl-row{padding:14px 0;border-top:1px solid var(--pt-hairline);" +
            "font-size:0.75em;line-height:1.6;color:var(--pt-secondary);}" +
        ".gl-row:first-child{border-top:0;}"

    /**
     * Meta-row chips shared by BOTH models: the word back's Frequency
     * field emits `.gl-stars`/`.gl-chip`, the sentence back's word cells
     * add the `.gl-pill` Common pill — so these can't live in the
     * sentence-only cell CSS.
     */
    private const val META_CSS =
        ".gl-pill{font-size:0.6em;font-weight:500;border:1px solid var(--pt-hairline);" +
            "border-radius:999px;padding:1px 9px;}" +
        ".gl-chip{font-size:0.6em;font-weight:500;background:var(--pt-chip);" +
            "border-radius:4px;padding:2px 8px;}" +
        ".gl-stars{font-size:0.65em;}"

    /**
     * Media/credit blocks shared by both models. The picture is a
     * full-width block (8px radius, no frame). The audio control
     * restyles Anki's own replay anchor: hide the SVG's built-in circle
     * and draw a `--pt-chip` disc behind the triangle. The anchor's
     * class name differs across AnkiDroid versions — both known
     * spellings are covered; if the circle can't be styled on device,
     * the fallback is Anki's default button in the same position.
     */
    private const val MEDIA_CSS =
        // The negative top pulls the picture into .pt-a's watermark-bar
        // reserve: .card top padding (8) + .pt-a reserve (38) − bar (30)
        // − 4 leaves a 12px bar gap — device-tuned 2px looser than the
        // 10px side gutter. Backs without a picture keep the full reserve.
        ".pt-pic{margin:-4px 0 18px;}" +
        ".pt-pic img{display:block;width:100%;max-width:100%;border-radius:8px;}" +
        ".pt-audio{display:inline-flex;align-items:center;justify-content:center;" +
            "vertical-align:middle;}" +
        // color is load-bearing: the anchor otherwise wears the WebView's
        // default link blue, which currentColor feeds to the play triangle.
        // line-height:1 + display:block on the svg kill baseline centering
        // drift: in the sentence back's 2.15em leading, a baseline-aligned
        // svg rides visibly high inside the circle.
        ".pt-audio a.replay-button,.pt-audio a.replaybutton{display:inline-flex;" +
            "align-items:center;justify-content:center;width:36px;height:36px;" +
            "border-radius:999px;background:var(--pt-chip);text-decoration:none;" +
            "color:var(--pt-secondary);line-height:1;}" +
        // The 2px optical-centering nudge corrects AnkiDroid's triangle
        // path, whose visual weight sits high in the disc when
        // geometrically centered. Other clients ship their own replay
        // SVG with different geometry (the same nudge rode visibly low
        // on AnkiMobile), so the nudge is Android-scoped.
        ".pt-audio svg{width:15px;height:15px;display:block;position:relative;}" +
        ".android .pt-audio svg{top:2px;}" +
        ".pt-audio svg circle{display:none;}" +
        ".pt-audio svg path{fill:currentColor;}" +
        ".pt-credit{font-size:0.55em;opacity:0.6;margin:16px 4px 8px;}"

    /**
     * The shipped launcher icon (mipmap `ic_launcher_img`, center-cropped
     * to the adaptive-icon safe zone users actually see, 72px source for
     * an 18px mark) as a data URI — no collection media, so the watermark
     * renders on AnkiDroid, desktop, and AnkiWeb alike and can't be swept
     * by Check Media. The asset ships in full colour; the bar CSS does
     * the desaturation, so a future partial-tint decision (the handoff's
     * held-in-reserve step) is a CSS change, not an asset change.
     */
    private const val BRAND_ICON_B64 =
        "/9j/4AAQSkZJRgABAQAASABIAAD/4QBMRXhpZgAATU0AKgAAAAgAAYdpAAQAAAABAAAAGgAAAAAAA6ABAAMAAAABAAEAAKAC" +
        "AAQAAAABAAAASKADAAQAAAABAAAASAAAAAD/wAARCABIAEgDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQF" +
        "BgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRol" +
        "JicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKz" +
        "tLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQF" +
        "BgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcY" +
        "GRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmq" +
        "srO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9sAQwACAgICAgIDAgIDBQMDAwUGBQUFBQYI" +
        "BgYGBgYICggICAgICAoKCgoKCgoKDAwMDAwMDg4ODg4PDw8PDw8PDw8P/9sAQwECAgIEBAQHBAQHEAsJCxAQEBAQEBAQEBAQ" +
        "EBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQ/90ABAAF/9oADAMBAAIRAxEAPwD8dl61ch+9j61RXrVt" +
        "DyDmv0lHyEloa9t2WuysND1aa3+1xWztCMAuFO0Z9T0qP4d+HZvFvi7SvDtv/rNRuYbdOM4aVwg4+pr+g7wp418JfD/4lWP7" +
        "HOnfDG+u/B39mMlxdz2QkN3O06xyXzsX2tZHJDS43LJhVAAFY4nFeztZXf6Hlype0k43tb+kfjHoXwyZvhvqPjvVZXgYXcVl" +
        "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"

    /**
     * The watermark — approved direction 7a ("Card watermark — the top
     * bar"): a 30px full-bleed strip pinned to the top of EVERY face,
     * panel-tone fill (no closing hairline), the shipped app
     * icon at 18px centred, fully desaturated at 60% strength. No text,
     * no accent, no interaction. The bar is out of flow (absolute), and
     * each face reserves its own fixed top offset — so adjusting the
     * bar's height shifts nothing (`.pt-a` covers both backs; the fronts
     * carry theirs in their own padding).
     */
    private const val BRAND_CSS =
        ".pt-brand{position:absolute;top:0;left:0;right:0;height:30px;" +
            "background:var(--pt-panel);" +
            "display:flex;align-items:center;justify-content:center;}" +
        ".pt-brand img{width:18px;height:18px;border-radius:4px;display:block;" +
            "filter:grayscale(1);opacity:0.6;}" +
        // 30px bar + 8px, atop .card's own 8px top padding (the picture
        // pulls itself up to a tuned 12px bar gap via its negative top
        // margin).
        // Explicit left alignment: the backs rely on left being the
        // ambient default, but AnkiMobile centers the body — everything
        // from the word back's Definitions header down rendered centered
        // there. The fronts and the sentence back's centered blocks all
        // set their own alignment, so this changes nothing elsewhere.
        ".pt-a{padding-top:38px;text-align:left;}"

    private const val BRAND_BAR =
        "<div class=\"pt-brand\"><img src=\"data:image/jpeg;base64,$BRAND_ICON_B64\"></div>"

    /**
     * Everything both models need. [PitchAccentHtml.PITCH_CSS] is
     * spliced by reference (never copied) so the template CSS can't
     * drift from the Kotlin renderer that bakes `pa-*` markup into the
     * sentence card's WordsTable field.
     */
    private val SHARED_CSS =
        RESET_CSS + COLOR_CSS + DEFINITION_CSS + META_CSS + MEDIA_CSS + BRAND_CSS +
            PitchAccentHtml.PITCH_CSS

    /**
     * Word-card layout. `.pt-rule` takes `currentColor`, so the accent
     * rule under the front's word is produced by `class="pt-rule gl-hl"`.
     * The old `.pt-freq` list rules are gone — frequency is chips in the
     * `.pt-meta` row now.
     */
    private const val WORD_LAYOUT_CSS =
        // Top padding includes the 30px watermark bar's reserve (the bar
        // is out of flow): the word sits ~20px below its bar-less spot.
        ".pt-word-front{text-align:center;font-size:3.2em;font-weight:700;" +
            "letter-spacing:-0.02em;line-height:1.15;padding:84px 16px 64px;}" +
        ".pt-rule{width:28px;height:2px;border-radius:2px;margin:24px auto 0;" +
            "background:currentColor;}" +
        ".pt-head{display:flex;align-items:center;gap:12px;margin:0 4px;}" +
        ".pt-head-text{flex:1;display:flex;align-items:baseline;gap:12px;flex-wrap:wrap;}" +
        ".pt-word{font-size:2.1em;font-weight:700;letter-spacing:-0.02em;line-height:1.1;}" +
        ".pt-reading{font-size:0.9em;}" +
        ".pt-meta{display:flex;flex-wrap:wrap;align-items:center;gap:8px;" +
            "font-size:0.65em;margin:8px 4px 0;}"

    /**
     * Sentence-card layout. The question side hides ruby readings
     * (`.pt-q ruby rt`) and reveals them via the tap tooltip
     * ([TOOLTIP_JS]); the answer side has no such rule, so readings
     * show. The back is one `.pt-back-panel` surface: audio row, then the
     * centered sentence, then the translation under a hairline. The
     * highlighted-word treatment is a 700 weight plus a 2px accent
     * underline — LONGHANDS ONLY in one rule: the `text-decoration`
     * shorthand resets `text-decoration-color` to the text colour,
     * which is exactly the bug that shipped when the colour lived in a
     * separate earlier rule. Offset 6px on the front, 5px on the
     * tighter-leaded back.
     */
    private const val SENTENCE_LAYOUT_CSS =
        // Top padding = the 30px watermark bar's reserve + tooltip
        // headroom: the reading-hint popup renders above the tapped ruby
        // (fixed-position, ~64px tall with the contour), so a first-line
        // tap must not clip at the viewport.
        ".pt-sentence-front{text-align:center;font-size:1.8em;" +
            "padding:100px 16px 24px;line-height:1.7em;}" +
        // Every section below the screenshot sits flush at the same 8px
        // .card gutter the picture gets — no extra side margin (the word
        // card keeps its own 4px text indents).
        ".pt-back-panel{background:var(--pt-panel);border:1px solid var(--pt-hairline);" +
            "border-radius:14px;padding:4px 16px;margin:0;}" +
        // Sits between the picture and the source/translation panel. The
        // negative top trims the gap under .pt-pic (18px, shared with the
        // word card, which keeps it) to match the button's own 10px
        // bottom gap.
        ".pt-sent-audio{text-align:center;margin:-8px 0 10px;}" +
        // Normal leading by default; the tall ruby headroom applies only
        // when the furigana branch (.pt-ruby) actually rendered.
        ".pt-sentence-back{text-align:center;font-size:1.4em;margin:0;" +
            "padding:8px 0 10px;line-height:1.55em;}" +
        ".pt-sentence-back .pt-ruby{line-height:2.15em;}" +
        // Lift the furigana slightly off its base text on the answer side.
        // transform, NOT position:relative — relative offsets on ruby
        // annotation boxes were silently ignored by the reviewer WebView
        // (three attempts device-observed as no-ops).
        ".pt-sentence-back rt{transform:translateY(-2px);}" +
        ".pt-sentence-back b{text-underline-offset:5px;}" +
        ".pt-translation{text-align:center;font-size:1.3em;font-weight:500;" +
            "line-height:1.4;border-top:1px solid var(--pt-hairline);" +
            "padding:14px 0 10px;margin:0;}" +
        ".pt-words{text-align:left;margin:0;}" +
        // Overrides the shared 4px credit indent (same-specificity, later
        // in SENTENCE_CSS) so the credit lines up with the panels above.
        ".pt-credit{margin-left:0;margin-right:0}" +
        ".pt-sentence b{font-weight:700;text-decoration-line:underline;" +
            "text-decoration-color:var(--pt-hl);" +
            "text-decoration-thickness:2px;text-underline-offset:6px;}" +
        ".pt-q ruby{cursor:pointer;-webkit-tap-highlight-color:transparent;}" +
        ".pt-q ruby rt{display:none;}" +
        ".gl-tip{position:fixed;background:#282828;color:#fff;" +
            "padding:8px 16px;border-radius:8px;font-size:28px;pointer-events:none;" +
            "z-index:9999;white-space:nowrap;box-shadow:0 2px 8px rgba(0,0,0,0.45);}" +
        // The contour's own overline reserve (.pa is 0.45em) plus the tip
        // padding left too much air above the accent marks — trim ~8px.
        ".gl-tip .pa{padding-top:0.16em;}" +
        // The caret rides --pt-tip-ax (set by TOOLTIP_JS after clamping
        // the tip to the viewport) so it stays aimed at the tapped ruby
        // when the tip itself can't center on it at a screen edge.
        ".gl-tip::after{content:'';position:absolute;top:100%;" +
            "left:var(--pt-tip-ax,50%);" +
            "transform:translateX(-50%);border:6px solid transparent;" +
            "border-top-color:#282828;}"

    /**
     * The sentence back's words table: `.gl-w-target` (panel surface,
     * hairline border) for target words, bare hairline-topped `.gl-w`
     * rows for context words. No accent fill and no accent headword —
     * with several targets an accent fill becomes the loudest thing on
     * the card; the underline in the sentence marks the targets.
     */
    private const val WORD_CELL_CSS =
        ".gl-w{padding:14px 14px 12px;border-top:1px solid var(--pt-hairline);}" +
        ".gl-w-target{background:var(--pt-target-bg);" +
            "border-radius:14px;padding:12px 14px;margin-bottom:8px;}" +
        ".gl-w-head{display:flex;align-items:center;gap:10px;}" +
        ".gl-w-word{font-size:1em;font-weight:700;line-height:1.2;}" +
        ".gl-w-read{flex:1;font-size:0.65em;}" +
        ".gl-meta{display:flex;flex-wrap:wrap;align-items:center;gap:8px;margin-top:8px;}" +
        ".gl-pos-h{font-size:0.55em;font-weight:500;letter-spacing:0.07em;" +
            "text-transform:uppercase;margin:12px 0 3px;}" +
        ".gl-def{display:flex;padding:3px 0;}" +
        ".gl-num{min-width:16px;text-align:right;font-size:0.85em;line-height:1.45;}" +
        ".gl-dtext{flex:1;margin-left:9px;font-size:0.85em;line-height:1.45;}" +
        // Back-side tap-to-scroll: wrapped words are tappable, and the
        // found cell flashes the accent tint (last in source so it wins
        // over .gl-w-target's own background at equal specificity).
        ".pt-sentence-back [data-pt-w]{cursor:pointer;" +
            "-webkit-tap-highlight-color:transparent;}" +
        ".pt-cell-hi{background:var(--pt-hl-bg);}"

    val WORD_CSS: String = SHARED_CSS + WORD_LAYOUT_CSS
    val SENTENCE_CSS: String = SHARED_CSS + SENTENCE_LAYOUT_CSS + WORD_CELL_CSS

    /**
     * Pitch-contour building blocks shared by [PITCH_JS] and
     * [TOOLTIP_JS] — a JS port of [Mora.segment], [Mora.contour], and
     * [PitchAccentHtml.pitchAccentHtml], sharing their `pa-*` CSS
     * (borders use `currentColor`, so the contour reads on the muted
     * reading and on the dark tooltip alike). Each consumer splices
     * this inside its own IIFE, so the helpers never collide.
     */
    // Built with string concatenation (matching the repo's existing
    // inline-script idiom) so there's no raw-string ${...} interpolation
    // hazard around JS template literals.
    private const val PITCH_HELPERS_JS =
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
        // The .pa contour span for kana + downsteps, or null on any bail
        // (non-kana, empty) — callers fall back to the plain reading.
        "function ptBuildPa(kana,pitch){" +
        "if(!kana||!ptIsKana(kana))return null;" +
        "var morae=ptMorae(kana);if(!morae.length)return null;" +
        "var n=morae.length,c=ptContour(pitch[0],n);" +
        "var pa=document.createElement('span');pa.className='pa';" +
        "for(var k=0;k<n;k++){" +
        "var drops=c.high[k]&&(k+1<n?!c.high[k+1]:!c.ghost);" +
        "var sp=document.createElement('span');" +
        "sp.className='pa-m'+(c.high[k]?' pa-h':'')+(drops?' pa-d':'');" +
        "sp.textContent=morae[k];pa.appendChild(sp);}" +
        "return pa;}" +
        // The ` [0]·[2]` all-variants suffix.
        "function ptPitchSuffix(pitch){" +
        "var s=document.createElement('span');s.className='pa-pos';" +
        "s.textContent=' '+pitch.map(function(p){return '['+p+']';}).join('·');" +
        "return s;}" +
        // Comma-separated downstep list → int array (empty on garbage).
        "function ptParsePitch(s){" +
        "return (s||'').split(',').map(function(t){return parseInt(t,10);})" +
        ".filter(function(v){return !isNaN(v);});}"

    /**
     * Draws the pitch-accent contour from the raw downstep list in the
     * hidden `#pt-pitch-pos` span over `#pt-reading`. Behavior contract
     * (pinned by PitchAccentHtmlTest on the Kotlin side, device-validated
     * here):
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
     * edits into the field can't break the markup. The mount points are
     * spans now (v002 puts word+reading on one baseline-aligned flex
     * row) — the script only uses `textContent`/`appendChild`, so the
     * element kind doesn't matter.
     */
    val PITCH_JS: String =
        "(function(){" +
        PITCH_HELPERS_JS +
        "var posEl=document.getElementById('pt-pitch-pos');" +
        "var readEl=document.getElementById('pt-reading');" +
        "var wordEl=document.getElementById('pt-word');" +
        "if(!posEl||!readEl)return;" +
        "var pitch=ptParsePitch(posEl.textContent);" +
        "if(!pitch.length)return;" +
        "var kana=readEl.textContent.trim();" +
        "if(!kana&&wordEl){var w=wordEl.textContent.trim();if(w&&ptIsKana(w))kana=w;}" +
        "var pa=ptBuildPa(kana,pitch);" +
        "if(!pa)return;" +
        "readEl.textContent='';readEl.appendChild(pa);readEl.appendChild(ptPitchSuffix(pitch));" +
        "})()"

    /**
     * Tap-to-reveal tooltip for the sentence card's question side. v002:
     * when the tapped ruby sits inside a `data-pt-kana`/`data-pt-pitch`
     * word wrapper (baked into SentenceFurigana by
     * [SentenceAnkiHtmlBuilder.buildSentenceFurigana] with
     * `wrapWordPitch`), the tooltip shows the WORD's reading as a pitch
     * contour; otherwise it falls back to the tapped ruby's own `rt`
     * text, the v001 behavior — which is also what non-pitch words and
     * pre-v002 wrapper-less fields get. Tap again, or tap outside, to
     * dismiss; hover works on pointer-capable devices.
     *
     * The tip is measured after insertion and clamped to the viewport's
     * sides (6px inset) instead of centered blindly — an edge word's
     * popup otherwise renders partly off-screen — and `--pt-tip-ax`
     * re-aims the caret at the tapped ruby's center so the clamp
     * doesn't visually detach the popup from its word. Ruby touchend
     * taps carry the same 10px drag slop as [SCROLL_JS]: WKWebView
     * delivers touchend for scroll releases too.
     */
    val TOOLTIP_JS: String =
        "(function(){" +
        PITCH_HELPERS_JS +
        "var tip=null,activeR=null;" +
        "function hide(){if(tip){tip.parentNode.removeChild(tip);tip=null;}activeR=null;}" +
        "function showTip(r,e){" +
        "e.stopPropagation();e.preventDefault();" +
        "if(activeR===r){hide();return;}" +
        "hide();" +
        "var rt=r.querySelector('rt');if(!rt)return;" +
        "var rect=r.getBoundingClientRect();" +
        "tip=document.createElement('div');tip.className='gl-tip';" +
        "var host=r.closest?r.closest('[data-pt-kana]'):null;" +
        "var pa=null,pitch=null;" +
        "if(host){" +
        "pitch=ptParsePitch(host.getAttribute('data-pt-pitch'));" +
        "if(pitch.length)pa=ptBuildPa(host.getAttribute('data-pt-kana'),pitch);" +
        "}" +
        "if(pa){tip.appendChild(pa);tip.appendChild(ptPitchSuffix(pitch));}" +
        "else{tip.textContent=rt.textContent;}" +
        "tip.style.top=rect.top+'px';" +
        "tip.style.transform='translateY(calc(-100% - 8px))';" +
        "document.body.appendChild(tip);" +
        "var cx=rect.left+rect.width/2,tw=tip.offsetWidth," +
        "vw=document.documentElement.clientWidth," +
        "left=cx-tw/2;" +
        "if(left+tw>vw-6)left=vw-6-tw;" +
        "if(left<6)left=6;" +
        "tip.style.left=left+'px';" +
        "var ax=cx-left;" +
        "if(ax<12)ax=12;if(ax>tw-12)ax=tw-12;" +
        "tip.style.setProperty('--pt-tip-ax',ax+'px');" +
        "activeR=r;" +
        "}" +
        "var hasHover=window.matchMedia('(hover:hover)').matches;" +
        "document.querySelectorAll('.pt-q ruby').forEach(function(r){" +
        "var sx=0,sy=0;" +
        "r.addEventListener('touchstart',function(e){" +
        "var t=e.touches[0];sx=t.clientX;sy=t.clientY;},{passive:true});" +
        "r.addEventListener('touchend',function(e){" +
        "var t=e.changedTouches[0];" +
        "if(Math.abs(t.clientX-sx)>10||Math.abs(t.clientY-sy)>10)return;" +
        "showTip(r,e);});" +
        "r.addEventListener('click',function(e){showTip(r,e);});" +
        "if(hasHover){" +
        "r.addEventListener('mouseenter',function(e){activeR=null;showTip(r,e);});" +
        "r.addEventListener('mouseleave',function(){hide();});" +
        "}" +
        "});" +
        "document.addEventListener('touchend',function(e){if(activeR&&!activeR.contains(e.target))hide();});" +
        "document.addEventListener('click',function(e){if(activeR&&!activeR.contains(e.target))hide();});" +
        "})()"

    /**
     * Back-side tap-to-scroll: tapping a wrapped word in the sentence
     * scrolls the words table to that word's cell and flashes it. Words
     * and cells share the `data-pt-w` key baked in by
     * [SentenceAnkiHtmlBuilder]; the cell is found by attribute
     * comparison, never selector interpolation, so a word carrying CSS
     * metacharacters can't break the lookup. Missing cell (or no JS —
     * AnkiWeb) degrades to a dead tap, the pre-feature behavior.
     *
     * Bound on BOTH touchend and click, the [TOOLTIP_JS] pattern:
     * AnkiMobile's tap-gesture layer swallows the synthesized click, so
     * a click-only binding is dead there (device-observed while the
     * dual-bound tooltip worked). preventDefault on the handled tap
     * keeps the click from double-firing where both are delivered; a
     * dead tap (no cell) prevents nothing, so the host app's own tap
     * gestures still work on unmapped words.
     *
     * The touchend path discriminates tap from drag by a 10px slop
     * against the touchstart point: WKWebView delivers touchend even
     * when the gesture scrolled the page (a scroll released over a word
     * navigated, device-observed on iOS), while Android's WebView turns
     * a scroll takeover into touchcancel — which is why AnkiDroid never
     * showed it.
     */
    val SCROLL_JS: String =
        "(function(){" +
        "var cells=document.querySelectorAll('.pt-words [data-pt-w]');" +
        "if(!cells.length)return;" +
        "function findCell(key){" +
        "for(var i=0;i<cells.length;i++){" +
        "if(cells[i].getAttribute('data-pt-w')===key)return cells[i];}" +
        "return null;}" +
        "var hiTimer=null;" +
        "function go(w,e){" +
        "var cell=findCell(w.getAttribute('data-pt-w'));" +
        "if(!cell)return;" +
        "e.stopPropagation();e.preventDefault();" +
        "cell.scrollIntoView({behavior:'smooth',block:'center'});" +
        "for(var i=0;i<cells.length;i++)cells[i].classList.remove('pt-cell-hi');" +
        "cell.classList.add('pt-cell-hi');" +
        "if(hiTimer)clearTimeout(hiTimer);" +
        "hiTimer=setTimeout(function(){cell.classList.remove('pt-cell-hi');},900);" +
        "}" +
        "document.querySelectorAll('.pt-sentence-back [data-pt-w]').forEach(function(w){" +
        "var sx=0,sy=0;" +
        "w.addEventListener('touchstart',function(e){" +
        "var t=e.touches[0];sx=t.clientX;sy=t.clientY;},{passive:true});" +
        "w.addEventListener('touchend',function(e){" +
        "var t=e.changedTouches[0];" +
        "if(Math.abs(t.clientX-sx)>10||Math.abs(t.clientY-sy)>10)return;" +
        "go(w,e);});" +
        "w.addEventListener('click',function(e){go(w,e);});" +
        "});" +
        "})()"

    /** Word front: the word alone at display size, an accent rule under
     *  it (`.pt-rule` takes `currentColor` from `gl-hl`). */
    val WORD_QFMT: String =
        BRAND_BAR +
        "<div class=\"pt-q pt-word-front\">{{Expression}}" +
        "<div class=\"pt-rule gl-hl\"></div></div>"

    /**
     * Word back, v002 order: picture, head row (word + reading on one
     * baseline, audio at the right), meta chips, definition panel,
     * more-examples rows, credit last. `#pt-word`, `#pt-reading`, and
     * `#pt-pitch-pos` keep their ids and relative order — [PITCH_JS]
     * mounts on them, and `#pt-reading` renders unconditionally (a
     * zero-height empty span is the script's stable mount point).
     * `{{PartOfSpeech}}` is deliberately not rendered — POS belongs to
     * each sense now; the field stays populated for users who reference
     * it in their own templates.
     */
    val WORD_AFMT: String =
        BRAND_BAR +
        "<div class=\"pt-a\">" +
        "{{#Picture}}<div class=\"pt-pic\">{{Picture}}</div>{{/Picture}}" +
        "<div class=\"pt-head\">" +
        "<div class=\"pt-head-text\">" +
        "<span class=\"pt-word\" id=\"pt-word\">{{Expression}}</span>" +
        "<span class=\"pt-reading gl-secondary\" id=\"pt-reading\">{{Reading}}</span>" +
        "</div>" +
        "{{#WordAudio}}<span class=\"pt-audio\">{{WordAudio}}</span>{{/WordAudio}}" +
        "</div>" +
        "<span id=\"pt-pitch-pos\" style=\"display:none\">{{PitchPosition}}</span>" +
        "{{#Frequency}}<div class=\"pt-meta gl-secondary\">{{Frequency}}</div>{{/Frequency}}" +
        "{{Definition}}" +
        "{{Examples}}" +
        "{{#AudioCredit}}<div class=\"pt-credit\">{{AudioCredit}}</div>{{/AudioCredit}}" +
        "</div>" +
        "<script>$PITCH_JS</script>"

    /**
     * Shared sentence body: the furigana bracket field through Anki's
     * `{{furigana:}}` filter when present, else the plain sentence.
     * Both variants carry the `<b>` highlight markup, so non-JA/ZH
     * cards (whose SentenceFurigana field is empty) keep their bolding.
     * The furigana branch wears `.pt-ruby` so ONLY it gets the tall
     * ruby leading — a plain-text source (English) keeps normal line
     * spacing instead of ruby headroom with nothing above it.
     */
    private const val SENTENCE_BODY =
        "{{#SentenceFurigana}}<div class=\"pt-ruby\">" +
        "{{furigana:SentenceFurigana}}</div>{{/SentenceFurigana}}" +
        "{{^SentenceFurigana}}{{Sentence}}{{/SentenceFurigana}}"

    val SENTENCE_QFMT: String =
        BRAND_BAR +
        "<div class=\"pt-q pt-sentence pt-sentence-front\">$SENTENCE_BODY</div>" +
        "<script>$TOOLTIP_JS</script>"

    /**
     * Sentence back, v002 order: picture, then one `.pt-back-panel`
     * holding the audio row, the centered sentence (readings visible —
     * no rt hiding outside `pt-q`), and the translation under a
     * hairline (the `{{#Translation}}` gate doubles as the divider
     * gate — no stray hairline when the field is empty); then the
     * words table, credit last.
     */
    val SENTENCE_AFMT: String =
        BRAND_BAR +
        "<div class=\"pt-a\">" +
        "{{#Picture}}<div class=\"pt-pic\">{{Picture}}</div>{{/Picture}}" +
        "{{#SentenceAudio}}<div class=\"pt-sent-audio\">" +
        "<span class=\"pt-audio\">{{SentenceAudio}}</span></div>{{/SentenceAudio}}" +
        "<div class=\"pt-back-panel\">" +
        "<div class=\"pt-sentence pt-sentence-back\">$SENTENCE_BODY</div>" +
        "{{#Translation}}<div class=\"pt-translation\">{{Translation}}</div>{{/Translation}}" +
        "</div>" +
        "{{#WordsTable}}<div class=\"pt-words\">{{WordsTable}}</div>{{/WordsTable}}" +
        "{{#AudioCredit}}<div class=\"pt-credit\">{{AudioCredit}}</div>{{/AudioCredit}}" +
        "</div>" +
        "<script>$SCROLL_JS</script>"
}
