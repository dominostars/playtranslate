package com.playtranslate.ui

import com.playtranslate.model.FrequencyTag
import com.playtranslate.model.ImportedSenseGroup
import com.playtranslate.model.ReadingRow

/**
 * The dictionary payload rendered by [WordDefinitionsView] — the meta row
 * (Common / frequency / part-of-speech / Anki deck), an optional warning
 * label, and the numbered senses. Shared by the magnifying lens
 * ([MagnifierLens]) and the translation-result word cell ([WordResultCell])
 * so the two surfaces render definitions from one renderer.
 *
 * Promoted out of `MagnifierLens.LensDefinitionData` (where it lived as a
 * nested type) so neither surface depends on the other.
 */
data class WordDefinitionData(
    val word: String,
    val reading: String?,
    val senses: List<SenseDisplay>,
    val freqScore: Int,
    val isCommon: Boolean,
    /** Names of Anki decks already containing this word; renders a passive
     *  deck pill in the meta row when non-empty. */
    val ankiDecks: List<String> = emptyList(),
    /** Pitch-accent downstep variants for [reading], empty when unknown.
     *  [WordResultCell] draws the contour over its reading when non-empty. */
    val pitch: List<Int> = emptyList(),
    /** Per-dictionary frequency chips from imported Yomitan frequency
     *  dictionaries, in the user's section order. */
    val frequencies: List<FrequencyTag> = emptyList(),
    /** Every reading in common-use order (shared ordering with the word detail
     *  page), occurrence flagged. [WordResultCell] lists these below the title
     *  when there's more than one or the inline reading won't fit. */
    val readingRows: List<ReadingRow> = emptyList(),
    /** The imported groups BEHIND the flattened imported rows in [senses] —
     *  same data, structured form, for the styled WebView surfaces
     *  ([YomitanDefinitionsView]). Flat surfaces ignore this. In-process
     *  only: [WordDefinitionData] never rides an intent. */
    val importedGroups: List<ImportedSenseGroup> = emptyList(),
    /** Prefetched structured glossaries + dictionary CSS for
     *  [importedGroups]; null = render flat (toggle off / nothing
     *  structured retained). See [fetchYomitanStyledData]. */
    val styled: YomitanStyledData? = null,
)

/** A single rendered sense: its part(s) of speech (whole English tokens) and
 *  the gloss text. Renderers localize [pos] via `Context.localizePos`, except
 *  when [imported] is set.
 *  [imported] marks rows from imported Yomitan term dictionaries (whose [pos]
 *  is a single display header carrying the dictionary name, not POS tags — so
 *  it is rendered verbatim, never localized) — the compact surfaces clamp
 *  these, since monolingual definitions run paragraph-length. */
data class SenseDisplay(
    val pos: List<String>,
    val definition: String,
    /** Curated misc register tags, raw as stored in the pack. The render layer
     *  ([com.playtranslate.ui.renderMisc]) is the cleanliness authority — it
     *  localizes / passes through / drops. REQUIRED (no default) so every
     *  construction site decides explicitly; imported rows pass emptyList(). */
    val misc: List<String>,
    val imported: Boolean = false,
    /** Per-dictionary accent override (ARGB) for an imported row's title;
     *  null = the default muted header. */
    val accentColor: Int? = null,
    /** `term_sc` rowid when this imported row's source entry retained a
     *  structured glossary — rides the enrichment transport so the Anki
     *  SENTENCE pipeline (which only ever sees flattened rows) can fetch
     *  structured card HTML at send time. Null everywhere else; flat
     *  renderers ignore it. */
    val scRowid: Long? = null,
    /** Owning dictionary id for [scRowid] rows (the card markup's
     *  data-dictionary + media key). */
    val dictId: String? = null,
    // Serializable so senses can ride [WordEnrichment] through the sentence
    // Anki review's intent/args snapshots (same transport as pitch/frequencies).
) : java.io.Serializable
