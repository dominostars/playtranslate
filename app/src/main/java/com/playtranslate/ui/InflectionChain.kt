package com.playtranslate.ui

import android.content.Context
import com.playtranslate.language.InflectedForm
import com.playtranslate.language.InflectionTag
import com.playtranslate.language.ParadigmReading
import com.playtranslate.language.inflection.InflectionParadigms

/**
 * The conjugation line drawn under a word: the form as found, then what it
 * expresses. For Japanese that is the chain of [InflectionTag]s from the
 * dictionary form outward, joined with Yomitan's " « " (飲んでいなかった · -て «
 * -いる « negative « -た); for a Wiktionary-pack language it is the language's
 * paradigm label over the pack's tagged lines for the surface (hablamos ·
 * nosotros · presente / pretérito). The one formatter behind every surface
 * that shows the line: the results-page word cell ([WordResultCell]), the
 * lens's native body ([WordDefinitionsView]) and styled body
 * ([DefinitionsDocument]), and the word-detail header ([WordDetailBinder]).
 */
object InflectionChain {

    /** The line's text size in sp before a surface's own scale: the cell and
     *  the lens multiply it by the scale their body uses, the detail header
     *  by the cell's default scale. */
    const val TEXT_SP = 13f

    fun format(context: Context, form: InflectedForm): String {
        val paradigm = form.paradigm
        val paradigmLabel = paradigm?.let { InflectionParadigms[it.lang]?.label(it.forms) }
        val description = paradigmLabel ?: form.tags.joinToString(" « ") { it.label(context) }
        // A reading of another entry than the headword names its lemma first
        // (vino · venir · él, ella, usted · pretérito).
        return listOfNotNull(form.surface, paradigm?.lemma, description.ifEmpty { null }).joinToString(" · ")
    }

    /**
     * Every line for one looked-up occurrence: the Japanese chain line when
     * [deinflection] or [tokenTags] has a step ([compose]), then one line per
     * [paradigms] reading ([InflectionParadigms.readingsOf]: one per entry of
     * the response that reads [surface] as its form). Empty when there is
     * nothing to draw.
     */
    fun lines(
        surface: String,
        deinflection: List<InflectionTag>,
        tokenTags: List<InflectionTag>,
        paradigms: List<ParadigmReading>,
    ): List<InflectedForm> =
        listOfNotNull(compose(surface, deinflection, tokenTags)) +
            paradigms.map { InflectedForm(surface, emptyList(), it) }

    /**
     * The line for one looked-up occurrence. [deinflection] is the lookup's
     * own chain ([com.playtranslate.model.DictionaryResponse.deinflection]),
     * from the entry's dictionary form to the key that was looked up;
     * [tokenTags] is the tokenizer's chain from that key to [surface]
     * ([com.playtranslate.language.TokenSpan.inflections]). Both read
     * dictionary form outward, so the lookup's chain goes first. [paradigm]
     * is a Wiktionary-pack language's reading of [surface]
     * ([InflectionParadigms.readingOf]), which draws the line on its own.
     * Null when all three are empty: there is no line to draw.
     */
    fun compose(
        surface: String,
        deinflection: List<InflectionTag>,
        tokenTags: List<InflectionTag>,
        paradigm: ParadigmReading? = null,
    ): InflectedForm? =
        if (deinflection.isEmpty() && tokenTags.isEmpty() && paradigm == null) null
        else InflectedForm(surface, deinflection + tokenTags, paradigm)
}
