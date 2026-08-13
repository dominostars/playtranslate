package com.playtranslate.ui

import com.playtranslate.model.ImportedSenseGroup

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

    /** ARGB Int → CSS color. Alpha-aware: opaque renders #RRGGBB, else
     *  rgba(). */
    fun cssHex(argb: Int): String {
        val a = argb ushr 24
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return if (a == 0xFF) {
            "#%02X%02X%02X".format(r, g, b)
        } else {
            "rgba(%d,%d,%d,%.3f)".format(r, g, b, a / 255f)
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
  background: color-mix(in srgb, var(--pt-fg) 10%, transparent);
  border-radius: 4px; padding: .18em .55em;
}
.meta-chip.common {
  color: var(--pt-accent);
  background: color-mix(in srgb, var(--pt-accent) 16%, transparent);
  border-radius: 1em;
}
.meta-chip.stars { background: none; padding-left: 0; padding-right: 0; }
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

  window.ptApplyDictCss = function (dictId, cssText) {
    if (appliedDicts[dictId]) return;
    appliedDicts[dictId] = true;
    try {
      var scope = '[data-dictionary="' + dictId + '"]';
      var sheet = new CSSStyleSheet();
      sheet.replaceSync(scope + ' {\n' + cssText + '\n}');
      // Post-parse scope enforcement: legitimate CSS nests entirely inside
      // the one wrapper rule; anything else at the top level is an escape
      // attempt (stray '}') and is deleted.
      for (var i = sheet.cssRules.length - 1; i >= 0; i--) {
        if (sheet.cssRules[i].selectorText !== scope) sheet.deleteRule(i);
      }
      document.adoptedStyleSheets = document.adoptedStyleSheets.concat([sheet]);
    } catch (e) { /* engine without constructable sheets/nesting: unstyled */ }
  };

  function reportHeight() {
    if (window.PTBridge && window.PTBridge.onHeight) {
      window.PTBridge.onHeight(document.documentElement.scrollHeight);
    }
  }

  window.ptSwap = function (html) {
    document.getElementById('root').innerHTML = html;
    // Two frames: one for style/layout, one so images with declared
    // aspect-ratios have taken their space.
    requestAnimationFrame(function () { requestAnimationFrame(reportHeight); });
  };

  window.addEventListener('resize', function () {
    requestAnimationFrame(reportHeight);
  });
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
        /** Per-dictionary accent override (ARGB) for a frequency chip. */
        val accentColor: Int? = null,
    ) {
        enum class Kind { NEUTRAL, COMMON, STARS }
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
                val tint = chip.accentColor
                    ?.let { " style=\"background:${cssHex(it)}\"" }.orEmpty()
                sb.append("<span class=\"$cls\"$tint>")
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
