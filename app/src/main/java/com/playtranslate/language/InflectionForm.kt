package com.playtranslate.language

/**
 * One line of a Wiktionary pack's `inflection` table: a [surface] of the
 * entry's lemma and the kaikki [tags] it carries, spelled as kaikki spells
 * them (`first-person`, `plural`, `present`, `indicative`, `vos-form`…). The
 * pack stores the tags verbatim so one rebuild per language is enough; what
 * they mean on screen is the per-language [com.playtranslate.language.inflection.InflectionParadigm]'s
 * business. Surfaces are the build's lowercase, the same lowercase the
 * lookup key gets, so they compare equal to it directly.
 */
data class InflectionForm(val surface: String, val tags: Set<String>) {
    /** Whether the line belongs to the pronominal variant bundled on the
     *  lemma's page (the builder tags a later table whose finite forms are
     *  two words `pronominal`): kaikki flattens a page's tables into one list,
     *  so ir's blob carries irse's `vete` with ir's own imperative tags. Such
     *  lines are kept out of the lemma's readings and grid. A later table with
     *  one-word finite forms (acostar's regular conjugation beside its
     *  stem-changing one) is the lemma's own and stays in. */
    val fromPronominalVariant: Boolean get() = PRONOMINAL_TAG in tags

    companion object {
        /** build_latin_dict.PRONOMINAL_TABLE_TAG. */
        const val PRONOMINAL_TAG = "pronominal"
    }
}

/**
 * A looked-up surface's readings as forms of one entry, with the language
 * whose paradigm renders them: the pack's [forms] whose surface is the one
 * looked up (hablamos → the present and the preterite line of hablar). Rides
 * [InflectedForm.paradigm] into the conjugation line; built only by
 * [com.playtranslate.language.inflection.InflectionParadigms.readingOf],
 * which returns null for a language without a paradigm.
 */
data class ParadigmReading(
    val lang: SourceLangId,
    val forms: List<InflectionForm>,
    /** The entry's lemma when it is not the headword the surface is shown
     *  under: `vino` is drawn under the noun vino, and venir's reading of it
     *  names venir ("vino · venir · él, ella, usted · pretérito"). Null for
     *  the headword's own entry. */
    val lemma: String? = null,
)
