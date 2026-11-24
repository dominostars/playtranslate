package com.playtranslate.ui

import android.content.Context
import com.playtranslate.model.ImportedSenseGroup
import com.playtranslate.yomitan.YomitanDataStore
import kotlinx.coroutines.CancellationException

/**
 * The structured glossaries and dictionary stylesheets behind imported
 * definitions. Two readers: the ANKI CARD (the send pipelines and the word
 * sheet resolve it in the same suspend pass that resolved the word, so
 * their synchronous card builders can draw the dictionaries' own markup),
 * and a definitions document on screen, which fetches it for each chunk of
 * rows it sends ([DefinitionDocument]).
 *
 * Null (from [fetchYomitanStyledData]) means flat text: the styling toggle
 * is off, no group carries a retained structured glossary, or the fetch
 * produced nothing or failed.
 */
class YomitanStyledData(
    /** [com.playtranslate.model.ImportedSense.scRowid] → glossary JSON. */
    val structured: Map<Long, String>,
    /** dict id → raw styles.css, for every dictionary in the content that
     *  has one. */
    val dictStyles: Map<String, String>,
)

/**
 * Reconstructs [ImportedSenseGroup]s from flattened [SenseDisplay] rows —
 * the sentence sheet's shape, where only the flattened form survived the
 * enrichment transport. Groups by consecutive dictId; the group label is
 * the header's source prefix (the part before " · "), per-sense pos is the
 * suffix. The first imported row always opens a group: its dictId can be
 * null (a legacy or synthetic group), which is also what "no group yet"
 * would compare equal to, and a run that never opened would keep no label.
 *
 * A non-imported row is the built-in dictionary's, and is not a group: it
 * is where the built-in dictionary's block stands among the dictionaries
 * ([buildSenseDisplays] lists the rows in drawn order), so it ends the
 * group before it and every group after it is marked
 * [com.playtranslate.model.ImportedSenseGroup.belowBuiltIn]. Only a row
 * with a definition counts: a blank one is a sense the card skips
 * ([WordCardDefinition.fromSenses]), and a built-in dictionary with no
 * definition to show stands nowhere. Pass the word's WHOLE row list: one
 * filtered to its imported rows has lost that place and reads as every
 * dictionary above the built-in one.
 */
internal fun importedGroupsFromSenses(
    senses: List<SenseDisplay>,
): List<com.playtranslate.model.ImportedSenseGroup> {
    val groups = mutableListOf<com.playtranslate.model.ImportedSenseGroup>()
    var opened = false
    var belowBuiltIn = false
    var curDict: String? = null
    var curSource = ""
    var curAccent: Int? = null
    var curBelow = false
    var cur = mutableListOf<com.playtranslate.model.ImportedSense>()
    fun flush() {
        if (cur.isNotEmpty()) {
            groups.add(
                com.playtranslate.model.ImportedSenseGroup(
                    curSource, cur.toList(), curAccent, dictId = curDict.orEmpty(),
                    belowBuiltIn = curBelow,
                ),
            )
        }
        cur = mutableListOf()
    }
    for (s in senses) {
        if (!s.imported) {
            // The built-in block: whatever follows is another group, below it.
            if (s.definition.isNotBlank()) {
                flush()
                opened = false
                belowBuiltIn = true
            }
            continue
        }
        val header = s.pos.firstOrNull().orEmpty()
        val source = header.substringBefore(" · ")
        val pos = header.substringAfter(" · ", "")
        if (!opened || s.dictId != curDict) {
            flush()
            opened = true
            curDict = s.dictId
            curSource = source
            curAccent = s.accentColor
            curBelow = belowBuiltIn
        }
        cur.add(com.playtranslate.model.ImportedSense(s.definition, pos, s.scRowid))
    }
    flush()
    return groups
}

/** A definitions document's meta row: the Common pill, the ★ run, the
 *  frequency chips and the deck badge, in that order. */
internal fun styledMetaChips(
    ctx: Context,
    data: WordDefinitionData,
): List<DefinitionsDocument.MetaChip> = buildList {
    if (data.isCommon) {
        add(
            DefinitionsDocument.MetaChip(
                ctx.getString(com.playtranslate.R.string.word_detail_common),
                DefinitionsDocument.MetaChip.Kind.COMMON,
            ),
        )
    }
    if (data.freqScore > 0) {
        add(
            DefinitionsDocument.MetaChip(
                "★".repeat(data.freqScore.coerceAtMost(5)),
                DefinitionsDocument.MetaChip.Kind.STARS,
            ),
        )
    }
    val ink = BadgeChips.onAccentInk(ctx)
    for (tag in data.frequencies) {
        add(
            DefinitionsDocument.MetaChip(
                "${tag.source}: ${tag.display}",
                // Inline colours only for an accent override: an untinted
                // chip already renders muted-on-translucent from the shell
                // stylesheet, which is the neutral chip's own treatment —
                // so this renderer needs only the override half of the
                // contract, and never a surface fill.
                tint = tag.accentColor?.let { accentChipColors(it, ink) },
            ),
        )
    }
    // The whole badge from its owner, never [AnkiDeckBadge.label] alone —
    // the label-only assembly this replaced is what dropped the icon.
    AnkiDeckBadge.metaChip(ctx, data.ankiDecks)?.let { add(it) }
}

/**
 * Styled payload for already-FLATTENED rows — the Anki sentence pipeline's
 * and sentence sheet's shape, where only [SenseDisplay]s survive the
 * enrichment transport. Same gates as [fetchYomitanStyledData]; null =
 * flat rendering throughout.
 */
internal suspend fun fetchStyledForSenses(
    ctx: Context,
    sourceLanguage: String,
    senses: Iterable<SenseDisplay>,
): YomitanStyledData? {
    val rowids = senses.mapNotNull { it.scRowid }
    if (rowids.isEmpty()) return null
    return containedStyledFetch(sourceLanguage) {
        val caps = YomitanDataStore.stylingFor(ctx, sourceLanguage)
        if (!caps.stylingActive) return@containedStyledFetch null
        val structured = YomitanDataStore.structuredGlossaries(ctx, sourceLanguage, rowids)
        if (structured.isEmpty()) return@containedStyledFetch null
        val dictIds = senses.mapNotNullTo(mutableSetOf()) { it.dictId }
        YomitanStyledData(
            structured = structured,
            dictStyles = caps.stylesByDict.filterKeys { it in dictIds },
        )
    }
}

/**
 * Runs a styled-payload fetch with datastore failure contained to null.
 * Flat text is every caller's standing fallback: the Anki word sheet's
 * card and both send pipelines carry it on null, and a definitions
 * document draws its rows with it. The pipelines are exception-free by
 * contract (their one-tap hosts run detached coroutines with no handler),
 * and a document that got no answer would send no rows at all, so a SQLite
 * or registry failure here must cost the styling, never the card or the
 * rows. Cancellation propagates.
 */
private suspend fun containedStyledFetch(
    sourceLanguage: String,
    fetch: suspend () -> YomitanStyledData?,
): YomitanStyledData? = try {
    fetch()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    // Class only: no message (it may carry a path), no content.
    android.util.Log.w(TAG, "fetch($sourceLanguage) failed, rendering flat: ${e.javaClass.simpleName}")
    null
}

/**
 * Each of [dictIds]' own dictionary title (its index.json `title`), the
 * value a card's `data-dictionary` carries ([CardGlossary]).
 */
internal suspend fun fetchDictionaryTitles(
    ctx: Context,
    sourceLanguage: String,
    dictIds: Collection<String>,
): Map<String, String> = containedTitles(sourceLanguage, dictIds) {
    YomitanDataStore.termDictionaryTitles(ctx, sourceLanguage)
}

/**
 * The titles [read] returns for [dictIds], with the two rules a send
 * depends on. Empty ids are skipped, and with none left [read] is never
 * called, so a send with no imported definition does not touch the store.
 * And the read is contained like the styled fetch, for the same reason (the
 * pipelines' one-tap hosts run detached with no handler): any failure but
 * cancellation is an empty map, and a definition whose title is missing is
 * tagged with its dictionary's label instead. A title must never cost the
 * card.
 */
internal suspend fun containedTitles(
    sourceLanguage: String,
    dictIds: Collection<String>,
    read: suspend () -> Map<String, String>,
): Map<String, String> {
    val wanted = dictIds.filterTo(mutableSetOf()) { it.isNotEmpty() }
    if (wanted.isEmpty()) return emptyMap()
    return try {
        read().filterKeys { it in wanted }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Class only: no message (it may carry a path), no content.
        android.util.Log.w(TAG, "titles($sourceLanguage) failed, using labels: ${e.javaClass.simpleName}")
        emptyMap()
    }
}

/** Fetches the styled payload for [groups], or null when the styled path
 *  shouldn't run. Cheap when inactive (one cached capability read). */
internal suspend fun fetchYomitanStyledData(
    ctx: Context,
    sourceLanguage: String,
    groups: List<ImportedSenseGroup>,
): YomitanStyledData? {
    val rowids = groups.flatMap { g -> g.senses.mapNotNull { it.scRowid } }
    if (rowids.isEmpty()) {
        if (groups.isNotEmpty()) android.util.Log.i(TAG, "fetch($sourceLanguage): ${groups.size} groups, no scRowids")
        return null
    }
    return containedStyledFetch(sourceLanguage) { fetchUncontained(ctx, sourceLanguage, groups, rowids) }
}

private suspend fun fetchUncontained(
    ctx: Context,
    sourceLanguage: String,
    groups: List<ImportedSenseGroup>,
    rowids: List<Long>,
): YomitanStyledData? {
    val caps = YomitanDataStore.stylingFor(ctx, sourceLanguage)
    if (!caps.stylingActive) {
        android.util.Log.i(TAG, "fetch($sourceLanguage): styling inactive")
        return null
    }
    val structured = YomitanDataStore.structuredGlossaries(ctx, sourceLanguage, rowids)
    if (structured.isEmpty()) {
        android.util.Log.i(TAG, "fetch($sourceLanguage): ${rowids.size} rowids, 0 structured")
        return null
    }
    val dictIds = groups.mapTo(mutableSetOf()) { it.dictId }
    val dictStyles = caps.stylesByDict.filterKeys { it in dictIds }
    // Field-trace seam 1/3: what the DATA layer produced. If css=[] here
    // while the dictionary has a stylesheet, the loss is in the
    // capability/registry plumbing, not the page.
    android.util.Log.i(
        TAG,
        "fetch($sourceLanguage): structured=${structured.size}/${rowids.size} " +
            "groups=[${dictIds.joinToString()}] " +
            "capsCss=[${caps.stylesByDict.keys.joinToString()}] " +
            "css=[${dictStyles.entries.joinToString { "${it.key}:${it.value.length}ch" }}]",
    )
    return YomitanStyledData(
        structured = structured,
        dictStyles = dictStyles,
    )
}

private const val TAG = "YomitanStyled"
