package com.playtranslate.ui

import com.playtranslate.language.DefinitionResult
import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.Example
import com.playtranslate.model.ImportedSenseGroup
import com.playtranslate.model.Sense
import com.playtranslate.model.aroundBuiltIn
import com.playtranslate.model.unambiguousFallbackPos

/**
 * The word card's Definition field, as DATA. Every word-card send — the
 * review sheet and the hold-to-create one-tap alike — describes what it
 * wants rendered and lets [sendWordCard] fetch the structured-glossary
 * payload and emit the markup, the way [sendSentenceCard] already owns the
 * sentence card's words table. One producer: the sheet cannot ship a styled
 * Definition the one-tap silently lacks. (That drift is what this replaced:
 * the styled fetch and the gl-sc builder were private to the sheet, so a
 * hold-created word card stayed on the flattened text no matter what the
 * lens or the detail page had just shown.)
 *
 * The sheet fills the curation fields from its editor state; one-tap passes
 * the bare [entry] its surface already held and leaves them at their
 * "nothing removed, nothing translated" defaults — so for an unedited entry
 * both paths emit byte-identical HTML.
 */
data class WordCardDefinition(
    /** Flat definition text: the whole field when [entry] is null, the
     *  body when every pack sense was curated away. */
    val fallback: String,
    /** The resolved entry (imported groups + pack senses); null renders
     *  [fallback] alone. */
    val entry: DictionaryEntry? = null,
    /** Every entry the lookup resolved (Wiktionary packs split POS into
     *  separate entries); pack senses flatten across all of them. */
    val entries: List<DictionaryEntry> = listOfNotNull(entry),
    /** Target-language definition tier, when the surface resolved one. */
    val defResult: DefinitionResult? = null,
    /** Target-language code; a non-English target with Native senses
     *  renders those instead of the pack's glosses. */
    val targetLang: String = "en",
    /** Curation: indexes (flat pack senses, or Native target senses when
     *  target-driven) the user removed in the editor. */
    val removedSenses: Set<Int> = emptySet(),
    /** Curation: flat indexes across [entry]'s imported senses, in group
     *  order, removed in the editor. */
    val removedImported: Set<Int> = emptySet(),
    /** Curation: (sense, example) indexes the user removed. */
    val removedExamples: Set<Pair<Int, Int>> = emptySet(),
    /** (sense, example) → translation the editor produced for a pack
     *  example; absent = the example's own translation. */
    val exampleTranslations: Map<Pair<Int, Int>, String> = emptyMap(),
) {
    /**
     * The imported groups this card keeps: the ones [glossary] turns into rows and
     * whose structured glossaries the pipeline fetches, so every rule about
     * imported rows reads this one view. Each group drops its
     * [removedImported] senses, and a group left with none drops out. With
     * nothing removed it is the entry's own list, by identity: that identity
     * is what keeps an uncurated card's HTML (one-tap's and the sentence
     * card's, which never curate) structurally byte-identical.
     */
    val importedGroups: List<ImportedSenseGroup>
        get() {
            val groups = entry?.importedSenses.orEmpty()
            if (removedImported.isEmpty()) return groups
            var base = 0
            return groups.mapNotNull { g ->
                val kept = g.senses.filterIndexed { i, _ -> base + i !in removedImported }
                base += g.senses.size
                if (kept.isEmpty()) null else g.copy(senses = kept)
            }
        }

    /**
     * This card's definitions as rows ([CardGlossary]), THE one sense loop
     * every card field that draws definitions goes through: the word
     * card's panel, the words table's cells and Yomitan's markup are three
     * layouts of what this returns.
     *
     * The rows are in the user's dictionary order: the imported
     * dictionaries that order puts above the built-in dictionary's row,
     * the built-in dictionary's rows, the imported ones below
     * ([aroundBuiltIn]); each block is one [CardGlossary.Item.group],
     * numbered in that drawn order, so the card's first dictionary is the
     * first row of the user's list that has the word. Imported
     * term-dictionary definitions are final text: the kept rows only
     * ([importedGroups]), each as its dictionary's structured glossary
     * when the sense retained one and [styled] delivered it, as its flat
     * text otherwise (null [styled] = flat throughout). The built-in
     * dictionary's rows are the target-language senses when the
     * surface resolved them for a non-English target, the pack's own
     * senses otherwise, each minus what the editor removed. A pack sense
     * with no definition is skipped. With no row at all, which is no entry
     * resolved or every row removed (kept imported rows already carry the
     * [fallback]'s lines, so it never follows them), the result is the
     * [fallback] alone.
     *
     * [titles] is dict id to the dictionary's own title, the value a row's
     * `data-dictionary` carries; a dictionary missing from it (the read
     * failed, or the dictionary changed since the lookup) is tagged with
     * its label. [localizePos] turns a built-in row's part-of-speech labels
     * into display text, and [renderMisc] is the register-tag renderer
     * (production: `Context::renderMiscText`).
     */
    internal fun glossary(
        styled: YomitanStyledData?,
        titles: Map<String, String>,
        localizePos: (List<String>) -> String,
        renderMisc: (List<String>) -> String?,
    ): CardGlossary {
        val dictStyles = styled?.dictStyles.orEmpty()
        if (entry == null) return CardGlossary(emptyList(), fallback, dictStyles)
        val items = mutableListOf<CardGlossary.Item>()
        // Each dictionary's rows are one group, numbered as the blocks are
        // drawn: the imported ones above the built-in dictionary, it, the
        // ones below. Only a block with a row takes a number.
        var nextGroup = 0
        fun imported(groups: List<ImportedSenseGroup>) {
            for (group in groups) {
                if (group.senses.isEmpty()) continue
                val groupIndex = nextGroup++
                val dictionary = titles[group.dictId] ?: group.source
                for (sense in group.senses) {
                    items += CardGlossary.Item(
                        imported = true,
                        dictionary = dictionary,
                        dictId = group.dictId,
                        label = group.source,
                        group = groupIndex,
                        pos = sense.pos,
                        structuredHtml = sense.scRowid
                            ?.let { styled?.structured?.get(it) }
                            ?.let { YomitanContentHtml.glossaryHtml(it, group.dictId, includeImages = false) },
                        text = sense.definition,
                    )
                }
            }
        }
        val (above, below) = importedGroups.aroundBuiltIn()
        imported(above)
        // One dictionary, at the built-in dictionary's place among them.
        var builtInGroup = -1
        fun builtIn(pos: List<String>, gloss: String, misc: List<String>, examples: List<Example>) {
            if (builtInGroup < 0) builtInGroup = nextGroup++
            items += CardGlossary.Item(
                imported = false,
                dictionary = CardGlossary.BUILT_IN_DICTIONARY,
                dictId = "",
                label = "",
                group = builtInGroup,
                pos = if (pos.isEmpty()) "" else localizePos(pos),
                structuredHtml = null,
                text = gloss,
                misc = renderMisc(misc),
                examples = examples,
            )
        }

        val defResult = defResult
        val nativeTargetSenses = (defResult as? DefinitionResult.Native)
            ?.targetSenses
            ?.sortedBy { it.senseOrd }
            ?.takeIf { it.isNotEmpty() }
        if (targetLang != "en" && nativeTargetSenses != null) {
            // Target-driven. Blank-pos target rows (PanLex) inherit the
            // source-entry POS only when entries agree; multi-POS source
            // yields an empty fallback so we don't mislabel verb/intj
            // cells as NOUN.
            val fallbackPos = unambiguousFallbackPos(entries)
            nativeTargetSenses.forEachIndexed { index, target ->
                if (index in removedSenses) return@forEachIndexed
                builtIn(
                    pos = target.pos.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() } ?: fallbackPos,
                    gloss = target.glosses.joinToString("; "),
                    misc = target.misc,
                    examples = target.examples.filterIndexed { e, _ -> (index to e) !in removedExamples },
                )
            }
            imported(below)
            return CardGlossary(items, fallback, dictStyles)
        }

        val translatedDefs = when (defResult) {
            is DefinitionResult.MachineTranslated -> defResult.translatedDefinitions
            is DefinitionResult.EnglishFallback -> defResult.translatedDefinitions
            else -> null
        }
        val targetByOrd = if (defResult is DefinitionResult.Native)
            defResult.targetSenses.associateBy { it.senseOrd } else null
        // The flat index counts every pack sense of every entry (Wiktionary
        // packs split POS into separate entries): it is the identity the
        // editor's removals and example translations name a sense by.
        entries.flatMap { it.senses }.forEachIndexed { flatIndex, sense ->
            if (sense.targetDefinitions.isEmpty() || flatIndex in removedSenses) return@forEachIndexed
            val target = targetByOrd?.get(flatIndex)
            builtIn(
                pos = (target?.pos ?: sense.partsOfSpeech).filter { it.isNotBlank() },
                gloss = target?.glosses?.joinToString("; ")
                    ?: translatedDefs?.getOrNull(flatIndex)
                    ?: sense.targetDefinitions.joinToString("; "),
                misc = sense.misc,
                examples = sense.examples.mapIndexedNotNull { e, example ->
                    if ((flatIndex to e) in removedExamples) null
                    else exampleTranslations[flatIndex to e]
                        ?.let { example.copy(translation = it) } ?: example
                },
            )
        }
        imported(below)
        return CardGlossary(items, fallback, dictStyles)
    }

    companion object {
        /**
         * The sentence card's shape. The sentence pipeline never holds a
         * [DictionaryEntry] for a word, only the flattened [SenseDisplay]
         * rows that crossed the enrichment transport, so every definition
         * a sentence card draws (each cell of its words table, and the
         * highlighted word's `DEFINITION` and `GLOSSARY` sources) is
         * described by rebuilding the two sense lists [glossary] reads:
         * imported rows regrouped by dictionary the way the sentence
         * sheet's preview does ([importedGroupsFromSenses]), pack rows as
         * one-gloss [Sense]s carrying their POS and misc. The rows already
         * reflect the target-language tier (the transport's builder applied
         * it), so the description carries no [defResult] and no curation:
         * for the same senses it yields the rows of an uncurated word card.
         * A blank pack row becomes a definition-less sense, which
         * [glossary] skips exactly as it skips an entry sense with no
         * target definitions. [fallback] is the flat meaning, drawn when
         * no row is.
         */
        fun fromSenses(
            word: String,
            senses: List<SenseDisplay>,
            fallback: String,
        ): WordCardDefinition {
            if (senses.isEmpty()) return WordCardDefinition(fallback = fallback)
            val packSenses = senses.filterNot { it.imported }.map { s ->
                Sense(
                    targetDefinitions = listOf(s.definition).filter { it.isNotBlank() },
                    partsOfSpeech = s.pos,
                    tags = emptyList(),
                    restrictions = emptyList(),
                    info = emptyList(),
                    misc = s.misc,
                )
            }
            val entry = DictionaryEntry(
                slug = word,
                isCommon = null,
                tags = emptyList(),
                jlpt = emptyList(),
                headwords = emptyList(),
                senses = packSenses,
                importedSenses = importedGroupsFromSenses(senses),
            )
            return WordCardDefinition(fallback = fallback, entry = entry)
        }
    }
}
