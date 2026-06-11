package com.playtranslate.ui

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
)

/** A single rendered sense: its part(s) of speech and the gloss text. */
data class SenseDisplay(
    val pos: String,
    val definition: String,
)
