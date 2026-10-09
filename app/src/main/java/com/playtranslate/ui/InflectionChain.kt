package com.playtranslate.ui

import android.content.Context
import com.playtranslate.language.InflectedForm
import com.playtranslate.language.InflectionTag

/**
 * The conjugation line drawn under a word: the form as found, then its
 * chain of [InflectionTag]s from the dictionary form outward, joined with
 * Yomitan's " « " (飲んでいなかった · -て « -いる « negative « -た). The one
 * formatter behind every surface that shows the line: the results-page word
 * cell ([WordResultCell]), the lens's native body ([WordDefinitionsView])
 * and styled body ([DefinitionsDocument]), and the word-detail header
 * ([WordDetailBinder]).
 */
object InflectionChain {

    /** The line's text size in sp before a surface's own scale: the cell and
     *  the lens multiply it by the scale their body uses, the detail header
     *  by the cell's default scale. */
    const val TEXT_SP = 13f

    fun format(context: Context, form: InflectedForm): String =
        form.surface + " · " + form.tags.joinToString(" « ") { it.label(context) }

    /**
     * The line for one looked-up occurrence. [deinflection] is the lookup's
     * own chain ([com.playtranslate.model.DictionaryResponse.deinflection]),
     * from the entry's dictionary form to the key that was looked up;
     * [tokenTags] is the tokenizer's chain from that key to [surface]
     * ([com.playtranslate.language.TokenSpan.inflections]). Both read
     * dictionary form outward, so the lookup's chain goes first. Null when
     * both are empty: there is no line to draw.
     */
    fun compose(
        surface: String,
        deinflection: List<InflectionTag>,
        tokenTags: List<InflectionTag>,
    ): InflectedForm? =
        if (deinflection.isEmpty() && tokenTags.isEmpty()) null
        else InflectedForm(surface, deinflection + tokenTags)
}
