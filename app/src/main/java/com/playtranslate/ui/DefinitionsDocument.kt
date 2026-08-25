package com.playtranslate.ui

import com.playtranslate.model.ImportedSenseGroup
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Assembles the styled-definitions WebView document: a persistent SHELL
 * page loaded once per [YomitanDefinitionsView] (theme tokens, base
 * stylesheet, the swap/scoping script, CSP) and per-lookup CONTENT swapped
 * into it via `ptSwap`.
 *
 * Dictionary CSS scoping happens in the page, with the engine's own
 * parser: `ptApplyDictCss` wraps a dictionary's styles.css in
 * `[data-dictionary="<id>"] { … }`, parses it with a constructable
 * stylesheet (native CSS nesting keeps Jitendex-style `&` rules and
 * `@media` blocks intact — per-selector prefixing would lose them), then
 * DELETES every top-level rule other than the single scope wrapper. That
 * post-parse sweep is the scope-escape fix: a stylesheet carrying a stray
 * `}` parses into extra top-level rules instead of silently going global
 * (the known hole in Yomitan's string-concat scoping). Scope keys are our
 * opaque dict ids — no title escaping hazards.
 *
 * Engine floor: constructable stylesheets + CSS nesting = Chromium 120
 * (2023). On an older WebView the dictionary's own CSS quietly doesn't
 * apply; the structured layout + base stylesheet still render.
 *
 * Content building is pure (no Context) — theme colors arrive as a
 * [Tokens] value the host resolves from its themed context.
 */
internal object DefinitionsDocument {

    /** Theme palette for the shell, resolved by the host surface from its
     *  own themed context ([com.playtranslate.themeColor] + [cssHex]).
     *  [panel] must be the surface's real panel color (not transparent):
     *  it backs `--background-color`, which dictionary dark-mode CSS
     *  composites against (Jitendex mixes it into its info boxes). */
    class Tokens(
        val text: Int,
        val textMuted: Int,
        val textHint: Int,
        val accent: Int,
        val panel: Int,
        /** Root font size in CSS px (surface scale already applied). */
        val baseFontSizePx: Float,
    )

    /**
     * [argb]'s RGB at [alpha] (0..1), as a CSS color.
     *
     * The chip fills are washes of a theme color, which `color-mix(in srgb,
     * X p%, transparent)` expresses exactly — and which is also exactly X at
     * p% alpha, so we compute it here instead. That is not a style
     * preference: a value containing `var()` cannot be validated at PARSE
     * time, so the declaration wins the cascade and only fails later, at
     * computed-value time, where an unsupported `color-mix` leaves the
     * property at its INITIAL value. A second `background:` written above it
     * as a fallback is never consulted — it lost the cascade before the
     * failure happened. Field symptom: on the Thor's Chromium 109
     * (color-mix landed in 111) every meta chip rendered with NO fill at
     * all, visible the moment a structured dictionary put the panel on the
     * styled renderer. Same rule as [PtCardTemplates]' glossary CSS: the
     * page gets concrete colors, never an engine feature.
     */
    fun cssAlpha(argb: Int, alpha: Float): String =
        cssHex(((alpha.coerceIn(0f, 1f) * 255f).roundToInt() shl 24) or (argb and 0xFFFFFF))

    /** ARGB Int → CSS color. Alpha-aware: opaque renders #RRGGBB, else
     *  rgba(). */
    fun cssHex(argb: Int): String {
        val a = argb ushr 24
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        // Locale.ROOT is load-bearing, not hygiene: the default-locale
        // formatter localizes BOTH conversions here — '%.3f' takes a comma
        // decimal separator (de, fr, ru, pt) and '%d' can take non-Latin
        // digits (ar). Either turns the value into something the CSS parser
        // rejects, the declaration drops, and background falls back to
        // transparent — the same no-fill failure as the color-mix trap
        // above, reached by a different door and only on some devices.
        return if (a == 0xFF) {
            String.format(Locale.ROOT, "#%02X%02X%02X", r, g, b)
        } else {
            String.format(Locale.ROOT, "rgba(%d,%d,%d,%.3f)", r, g, b, a / 255f)
        }
    }

    // ── Shell ───────────────────────────────────────────────────────────

    fun shellHtml(tokens: Tokens): String {
        val fg = cssHex(tokens.text)
        val panel = cssHex(tokens.panel)
        return """<!doctype html>
<html><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src ${YomitanContentHtml.MEDIA_ORIGIN}; script-src 'unsafe-inline'">
<style>
:root {
  --pt-fg: $fg;
  --pt-secondary: ${cssHex(tokens.textMuted)};
  --pt-hint: ${cssHex(tokens.textHint)};
  --pt-accent: ${cssHex(tokens.accent)};
  --pt-panel: $panel;
  /* Yomitan theme contract — dictionary CSS adapts to dark mode through
     these (Jitendex: var(--text-color, var(--fg, #333)) etc.). */
  --text-color: $fg;
  --fg: $fg;
  --background-color: $panel;
  --canvas: $panel;
}
* { box-sizing: border-box; }
html, body { margin: 0; padding: 0; background: transparent; }
/* flow-root: children's margins stay inside #root's rect, so the height
   report can't crop the last block's bottom margin. */
#root { display: flow-root; }
body {
  color: var(--pt-fg);
  font-family: sans-serif;
  font-size: ${tokens.baseFontSizePx}px;
  line-height: 1.4;
  word-wrap: break-word;
  -webkit-tap-highlight-color: transparent;
}
.pos-h {
  font-size: .72em; font-weight: 600; letter-spacing: .07em;
  text-transform: uppercase; color: var(--pt-secondary);
  margin: .65em 0 .2em;
}
.pos-h:first-child { margin-top: .1em; }
.label-warn { font-size: .8em; color: var(--pt-secondary); margin: .1em 0 .3em; }
.meta-row {
  display: flex; flex-wrap: wrap; gap: .3em; align-items: center;
  margin: .1em 0 .45em;
}
.meta-chip {
  font-size: .7em; color: var(--pt-secondary);
  /* Concrete rgba, resolved Android-side — see [cssAlpha]. NEVER color-mix
     with a var() operand: the fallback declaration above it can't save an
     engine that lacks the function, and the chip loses its fill entirely. */
  background: ${cssAlpha(tokens.text, 0.10f)};
  border-radius: 4px; padding: .18em .55em;
}
.meta-chip.common {
  color: var(--pt-accent);
  background: ${cssAlpha(tokens.accent, 0.16f)};
  border-radius: 1em;
}
.meta-chip.stars { background: none; padding-left: 0; padding-right: 0; }
/* Icon-bearing chips only: a text-only chip stays a plain inline box so a
   long frequency source name can still wrap inside it. */
.meta-chip.with-icon { display: inline-flex; align-items: center; gap: .35em; }
/* 1em square, matching the native pill's icon-sized-to-its-text rule
   ([AnkiDeckBadge.buildPill] bounds the drawable at the text size). */
.chip-icon { width: 1em; height: 1em; flex: none; }
.def-row { display: flex; gap: .4em; margin: .18em 0; }
.def-num { color: var(--pt-hint); font-size: .85em; padding-top: .12em; }
.def-text { flex: 1; min-width: 0; }
.dict-group { margin: 0 0 .4em; }
.gloss-item + .gloss-item { margin-top: .4em; }
.gloss-link { color: var(--pt-accent); text-decoration: underline; }
.gloss-image { max-width: 100%; height: auto; border-radius: 4px; vertical-align: text-bottom; }
.gloss-sc-table-container { overflow-x: auto; max-width: 100%; }
.gloss-sc-table { border-collapse: collapse; }
.gloss-sc-th, .gloss-sc-td {
  border: 1px solid var(--pt-hint); padding: .25em .45em; vertical-align: top;
}
.gloss-sc-ul, .gloss-sc-ol { margin: .25em 0; padding-left: 1.5em; }
.gloss-sc-li { margin: .1em 0; }
.gloss-sc-details { margin: .2em 0; }
.gloss-sc-summary { cursor: pointer; color: var(--pt-secondary); }
ruby > rt { font-size: .5em; }
</style>
<script>
(function () {
  'use strict';
  var appliedDicts = {};

  // CSS nesting = Chromium 120+. NOT theoretical: AOSP-bundled WebViews in
  // the field sit far below that (AYN Thor ships Chromium 109), and on
  // such engines the nested wrapper parses to an EMPTY scope rule — every
  // dictionary rule silently vanishes. Those engines take the per-selector
  // prefix path instead.
  // BEHAVIOR probe, not a feature flag: the supports-query for the '&'
  // selector LIES on Chromium ~105-119 (the Thor's 109 says yes) — the
  // selector parser accepts '&' several versions before nested rules
  // actually parse, so the wrapper path would keep its scope rule and
  // silently drop every rule inside it. Parse a real nested rule and
  // count its children instead.
  function probeNesting() {
    try {
      var el = document.createElement('style');
      el.media = 'not all';
      el.textContent = '#pt-nest-probe { & span { color: red } }';
      document.head.appendChild(el);
      var r = el.sheet && el.sheet.cssRules;
      var ok = !!(r && r.length === 1 && r[0].cssRules && r[0].cssRules.length === 1);
      document.head.removeChild(el);
      return ok;
    } catch (e) { return false; }
  }
  var supportsNesting = probeNesting();
  // Field-trace seam 3/3 boots with the shell: console lines land in
  // logcat as chromium INFO:CONSOLE, so a single tap traces end to end.
  console.log('pt-shell: up, nesting=' + supportsNesting);

  // Everything below runs on <style> ELEMENTS and their .sheet CSSOM —
  // never the constructable-stylesheet APIs, which Android WebView gained
  // far later than desktop Chrome; on an AOSP WebView those throw and the
  // swallow-catch made every dictionary silently unstyled. Element sheets
  // parse synchronously on appendChild and are CSSOM Level 1 — the oldest
  // thing in the room supports them.
  // Legacy scoping: parse the RAW sheet through a detached-media temp
  // element (flat rules parse fine on old engines), then emit
  // per-selector-prefixed text — escape-proof by construction, since each
  // emitted selector carries the scope. The dictionary's own '&'-nested
  // rules are lost on this path; flat rules and @media survive.
  function applyLegacy(dictId, scope, cssText) {
    var tmp = document.createElement('style');
    tmp.media = 'not all'; // parsed but never applied while we read it
    tmp.textContent = cssText;
    document.head.appendChild(tmp);
    var out = '';
    var prefixed = function (rule) {
      return rule.selectorText.split(',').map(function (s) {
        return scope + ' ' + s.trim();
      }).join(', ') + ' { ' + rule.style.cssText + ' }\n';
    };
    try {
      var rules = (tmp.sheet && tmp.sheet.cssRules) || [];
      for (var j = 0; j < rules.length; j++) {
        var r = rules[j];
        if (r.type === CSSRule.STYLE_RULE) {
          out += prefixed(r);
        } else if (r.type === CSSRule.MEDIA_RULE) {
          var inner = '';
          for (var k = 0; k < r.cssRules.length; k++) {
            if (r.cssRules[k].type === CSSRule.STYLE_RULE) {
              inner += prefixed(r.cssRules[k]);
            }
          }
          if (inner) out += '@media ' + r.conditionText + ' {\n' + inner + '}\n';
        }
        // Other at-rules (@import, @font-face, @keyframes) drop — same
        // policy as Yomitan's legacy path.
      }
    } finally {
      document.head.removeChild(tmp);
    }
    var applied = document.createElement('style');
    applied.textContent = out;
    document.head.appendChild(applied);
    console.log('ptCss[' + dictId + ']: legacy, in=' + cssText.length +
      'ch rules=' + ((applied.sheet && applied.sheet.cssRules) || []).length +
      ' outChars=' + out.length);
  }

  window.ptApplyDictCss = function (dictId, cssText) {
    if (appliedDicts[dictId]) { console.log('ptCss[' + dictId + ']: dedup'); return; }
    appliedDicts[dictId] = true;
    try {
      var scope = '[data-dictionary="' + dictId + '"]';
      if (supportsNesting) {
        var el = document.createElement('style');
        el.textContent = scope + ' {\n' + cssText + '\n}';
        document.head.appendChild(el);
        // Post-parse scope enforcement: legitimate CSS nests entirely
        // inside the one wrapper rule; anything else at the top level is
        // an escape attempt (stray '}') and is deleted.
        var sheet = el.sheet;
        for (var i = sheet.cssRules.length - 1; i >= 0; i--) {
          if (sheet.cssRules[i].selectorText !== scope) sheet.deleteRule(i);
        }
        // Outcome verification, independent of any probe: if the engine
        // kept the wrapper but parsed NOTHING inside it (the exact shape
        // of the selector(&)-lies engines), the nested product is an
        // empty husk — tear it down and go legacy. No engine lie may
        // silently unstyle a dictionary again.
        var innerCount = 0;
        for (var n = 0; n < sheet.cssRules.length; n++) {
          var cr = sheet.cssRules[n].cssRules;
          if (cr) innerCount += cr.length;
        }
        if (innerCount === 0) {
          document.head.removeChild(el);
          console.log('ptCss[' + dictId + ']: nested kept ' +
            sheet.cssRules.length + ' but 0 inner rules; falling to legacy');
          applyLegacy(dictId, scope, cssText);
          return;
        }
        console.log('ptCss[' + dictId + ']: nested, in=' + cssText.length +
          'ch kept=' + sheet.cssRules.length + ' inner=' + innerCount);
      } else {
        applyLegacy(dictId, scope, cssText);
      }
    } catch (e) {
      // NEVER silent again: three device-refuted fixes in a row hid here.
      console.log('ptCss[' + dictId + '] FAILED: ' + e);
    }
  };

  // Render generation: stamped by each ptSwap and echoed with every
  // height report, so the Kotlin side can drop reports from a swap that
  // has since been superseded. Without it, a report scheduled two frames
  // after swap N can arrive after swap N+1 was issued and be mistaken
  // for proof that N+1 painted — revealing N's content under N+1's word.
  var gen = 0;

  function reportHeight() {
    // Content-intrinsic metric: the root DIV's rect. NEVER the document
    // element's scroll height — that is max(content, VIEWPORT), so once
    // the host sizes the view from one oversized report, every later
    // report echoes at least that viewport back: a ratchet that can't
    // shrink (the shipped huge-blank-space bug). The root rect tracks the
    // content both ways.
    var rootEl = document.getElementById('root');
    if (!rootEl) return;
    // Not laid out yet (zero-width viewport): content would wrap at every
    // character and report an enormous bogus height. Skip — the resize
    // listener re-reports the moment the real width arrives.
    if (document.documentElement.clientWidth < 1) return;
    if (window.PTBridge && window.PTBridge.onHeight) {
      window.PTBridge.onHeight(Math.ceil(rootEl.getBoundingClientRect().height), gen);
    }
  }

  // Semantic tap routing for hosts that overlay their own tap-anywhere
  // action on this view (the lens's open-detail tap): the page is the one
  // authority on what a tap actually hit. A click on an external anchor
  // navigates (the Kotlin client hands it to the browser); any other click
  // reports up as a plain body tap and the host acts on that instead of
  // second-guessing the WebView from outside.
  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
    if (a) return;
    if (window.PTBridge && window.PTBridge.onBodyTap) window.PTBridge.onBodyTap();
  });

  window.ptSwap = function (html, g) {
    gen = g || 0;
    document.getElementById('root').innerHTML = html;
    // Synchronous first report: getBoundingClientRect forces layout, and
    // this path works even while the view isn't composited — rAF doesn't
    // tick for a non-drawn WebView, which is exactly the state the hosts
    // keep this view in until the first height arrives.
    reportHeight();
    // Two-frame refinement: images with declared aspect-ratios have taken
    // their boxes by then.
    requestAnimationFrame(function () { requestAnimationFrame(reportHeight); });
  };

  // Direct, not via rAF: this is the recovery path that fires when the
  // hidden view first gets its real width.
  window.addEventListener('resize', reportHeight);
})();
</script>
</head><body><div id="root"></div></body></html>"""
    }

    // ── Content ─────────────────────────────────────────────────────────

    /**
     * The per-lookup markup handed to `ptSwap`: imported groups first (in
     * their given order — [TermMerge] already ordered them), each wrapped
     * in `<section data-dictionary>` so its dictionary's scoped CSS can
     * reach it, then the pack's own senses with the classic numbered
     * layout. [data]'s flattened imported rows are skipped here — the
     * groups ARE those rows in structured form.
     *
     * [structured] maps [com.playtranslate.model.ImportedSense.scRowid] →
     * glossary JSON; senses without an entry render their flat text.
     * [localizePos] is the pack-sense POS localizer (imported headers stay
     * verbatim, same rule as [WordDefinitionsView]).
     */
    /** One chip in the styled document's meta row — the HTML counterpart
     *  of [WordDefinitionsView]'s Common pill / ★ run / frequency chips /
     *  deck badge (built Android-side, where strings and theme live). */
    class MetaChip(
        val text: String,
        val kind: Kind = Kind.NEUTRAL,
        /** A frequency chip's accent-override colours from [freqChipColors]
         *  — BOTH halves, so this document can only ever paint the pair the
         *  native rows paint. Null = no override, and the stylesheet's
         *  neutral chip treatment stands. */
        val tint: MetaChipColors? = null,
        /** Leading glyph, drawn in the chip's own colour. Named, never
         *  markup: the page's icons stay this document's to author, so a
         *  chip builder can't inject into it. */
        val icon: Icon = Icon.NONE,
    ) {
        enum class Kind { NEUTRAL, COMMON, STARS }

        /** Glyphs the meta row can draw. Each has a native counterpart the
         *  flat renderer draws for the SAME chip — add one only alongside
         *  the builder that owns both mediums (see [AnkiDeckBadge]). */
        enum class Icon { NONE, CARD_STACK }
    }

    fun contentHtml(
        data: WordDefinitionData,
        structured: Map<Long, String>,
        localizePos: (List<String>) -> String,
        showMisc: Boolean = false,
        renderMisc: (List<String>) -> String? = { null },
        metaChips: List<MetaChip> = emptyList(),
        label: String? = null,
    ): String {
        val sb = StringBuilder()
        label?.takeIf { it.isNotBlank() }?.let {
            sb.append("<div class=\"label-warn\">").append(htmlEscape(it)).append("</div>")
        }
        if (metaChips.isNotEmpty()) {
            sb.append("<div class=\"meta-row\">")
            for (chip in metaChips) {
                val cls = when (chip.kind) {
                    MetaChip.Kind.COMMON -> "meta-chip common"
                    MetaChip.Kind.STARS -> "meta-chip stars"
                    MetaChip.Kind.NEUTRAL -> "meta-chip"
                }
                val tint = chip.tint?.let {
                    " style=\"background:${cssHex(it.fill)};color:${cssHex(it.text)}\""
                }.orEmpty()
                val withIcon = if (chip.icon == MetaChip.Icon.NONE) "" else " with-icon"
                sb.append("<span class=\"$cls$withIcon\"$tint>")
                    .append(iconSvg(chip.icon))
                    .append(htmlEscape(chip.text)).append("</span>")
            }
            sb.append("</div>")
        }
        for (group in data.importedGroups) {
            sb.append("<section class=\"dict-group\" data-dictionary=\"")
                .append(htmlEscape(group.dictId)).append("\">")
            val headerColor = group.accentColor?.let { " style=\"color:${cssHex(it)}\"" }.orEmpty()
            var previousHeader: String? = null
            for (sense in group.senses) {
                // Same collapse rule as WordDefinitionsView: consecutive
                // senses sharing a header emit it once.
                val header = importedHeader(group.source, sense.pos)
                if (header != previousHeader) {
                    sb.append("<div class=\"pos-h\"$headerColor>")
                        .append(htmlEscape(header)).append("</div>")
                    previousHeader = header
                }
                val glossary = sense.scRowid?.let { structured[it] }
                    ?.let { YomitanContentHtml.glossaryHtml(it, group.dictId) }
                if (glossary != null) {
                    sb.append(glossary)
                } else {
                    sb.append("<div class=\"def-row\"><div class=\"def-text\">")
                        .append(htmlEscape(sense.definition).replace("\n", "<br>"))
                        .append("</div></div>")
                }
            }
            sb.append("</section>")
        }
        val packSenses = data.senses.filter { !it.imported }
        var previousPos: List<String>? = null
        packSenses.forEachIndexed { i, sense ->
            if (sense.pos.isNotEmpty() && sense.pos != previousPos) {
                sb.append("<div class=\"pos-h\">")
                    .append(htmlEscape(localizePos(sense.pos))).append("</div>")
                previousPos = sense.pos
            }
            sb.append("<div class=\"def-row\">")
                .append("<div class=\"def-num\">").append(i + 1).append(".</div>")
                .append("<div class=\"def-text\">")
                .append(htmlEscape(sense.definition).replace("\n", "<br>"))
            if (showMisc) {
                renderMisc(sense.misc)?.let {
                    sb.append("<div class=\"pos-h\">").append(htmlEscape(it)).append("</div>")
                }
            }
            sb.append("</div></div>")
        }
        return sb.toString()
    }

    /**
     * [icon]'s markup: an INLINE `<svg>`, not an `<img>`. The shell's CSP
     * is `img-src ${YomitanContentHtml.MEDIA_ORIGIN}` — no `data:` — so a
     * rasterized drawable would be blocked outright and a broken chip is
     * exactly what an icon-less one already looks like. Inline markup also
     * takes the chip's own colour through `currentColor`, which is how the
     * native pill's icon gets tinted to the same ink.
     *
     * `aria-hidden`: the glyph is decorative, and the chip's visible text
     * already names the deck (the native pill's contentDescription has no
     * dependable span-level counterpart — see [AnkiDeckBadge.metaChip]).
     */
    private fun iconSvg(icon: MetaChip.Icon): String = when (icon) {
        MetaChip.Icon.NONE -> ""
        MetaChip.Icon.CARD_STACK ->
            "<svg class=\"chip-icon\" viewBox=\"0 -960 960 960\" aria-hidden=\"true\">" +
                "<path fill=\"currentColor\" d=\"$CARD_STACK_PATH\"/></svg>"
    }

    /**
     * The `cards_stack` glyph, in the Material source SVG's own negative-Y
     * viewBox — the very geometry `R.drawable.ic_card_stack` carries, which
     * only has to park it in a translated group because a vector drawable's
     * viewport must be positive-Y and an SVG's need not be.
     *
     * Copied rather than shared because nothing can hand one path to both
     * `res/` and a page string at build time; `DefinitionsDocumentIconTest`
     * fails the build if the drawable and this constant ever drift.
     */
    internal const val CARD_STACK_PATH =
        "M130,-189 81,-546q-5,-32 15.5,-58t52.5,-31l61,435 283,-40h267q-8,21 -24.5,35.5T695,-187l-477,66q-33,5 -58,-15t-30,-53Zm190,-127q-33,0 -56.5,-23.5T240,-396v-364q0,-33 23.5,-56.5T320,-840h480q33,0 56.5,23.5T880,-760v364q0,33 -23.5,56.5T800,-316H320Zm0,-80h480v-364H320v364Zm0,0v-364 364ZM210,-200Zm190,-400h320v-80H400v80Zm0,120h200v-80H400v80Z"

    /** Whether [data] warrants the styled path at all: at least one
     *  imported sense with a fetchable structured glossary. */
    fun hasStructuredContent(data: WordDefinitionData): Boolean =
        data.importedGroups.any { g -> g.senses.any { it.scRowid != null } }

    /** Rowids the content needs fetched ([YomitanDataStore.structuredGlossaries]). */
    fun structuredRowids(data: WordDefinitionData): List<Long> =
        data.importedGroups.flatMap { g -> g.senses.mapNotNull { it.scRowid } }

    /** Group label · pos — mirrored from [SenseDisplays.importedHeader] for
     *  the group-structured render path. */
    private fun importedHeader(source: String, pos: String): String =
        if (pos.isBlank()) source else "$source · $pos"
}
