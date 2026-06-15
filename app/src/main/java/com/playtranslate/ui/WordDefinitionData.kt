package com.playtranslate.ui

import com.playtranslate.model.FrequencyTag

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
    val imported: Boolean = false,
    /** Per-dictionary accent override (ARGB) for an imported row's title;
     *  null = the default muted header. */
    val accentColor: Int? = null,
)
