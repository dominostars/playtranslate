package com.playtranslate.ui

import android.content.Context
import com.playtranslate.model.ImportedSenseGroup
import com.playtranslate.yomitan.YomitanDataStore
import kotlinx.coroutines.CancellationException

/**
 * Prefetched payload for the styled definitions path, resolved in the same
 * suspend pipeline that resolved the word ([SourceWordLookup.resolve],
 * [DragLookupController]'s lookup) so the render surfaces stay fully
 * synchronous — a lens bind never launches its own coroutine.
 *
 * Null (from [fetchYomitanStyledData]) means the flat tier renders: the
 * styling toggle is off, no group carries a retained structured glossary,
 * or the fetch produced nothing. Surfaces gate on
 * [WordDefinitionData.styled] being non-null with a non-empty [structured].
 */
class YomitanStyledData(
    /** [com.playtranslate.model.ImportedSense.scRowid] → glossary JSON. */
    val structured: Map<Long, String>,
    /** dict id → raw styles.css, for every dictionary in the content that
     *  has one (page-side scoping applies each once). */
    val dictStyles: Map<String, String>,
    /** Routes the WebView's media requests ([YomitanDefinitionsView]). */
    val sourceLanguage: String,
)

/**
 * Reconstructs [ImportedSenseGroup]s from flattened [SenseDisplay] rows —
 * the sentence sheet's shape, where only the flattened form survived the
 * enrichment transport. Groups by consecutive dictId; the group label is
 * the header's source prefix (the part before " · "), per-sense pos is the
 * suffix. Non-imported rows are skipped.
 */
internal fun importedGroupsFromSenses(
    senses: List<SenseDisplay>,
): List<com.playtranslate.model.ImportedSenseGroup> {
    val groups = mutableListOf<com.playtranslate.model.ImportedSenseGroup>()
    var curDict: String? = null
    var curSource = ""
    var curAccent: Int? = null
    var cur = mutableListOf<com.playtranslate.model.ImportedSense>()
    fun flush() {
        if (cur.isNotEmpty()) {
            groups.add(
                com.playtranslate.model.ImportedSenseGroup(
                    curSource, cur.toList(), curAccent, dictId = curDict.orEmpty(),
                ),
            )
        }
        cur = mutableListOf()
    }
    for (s in senses) {
        if (!s.imported) continue
        val header = s.pos.firstOrNull().orEmpty()
        val source = header.substringBefore(" · ")
        val pos = header.substringAfter(" · ", "")
        if (s.dictId != curDict) {
            flush()
            curDict = s.dictId
            curSource = source
            curAccent = s.accentColor
        }
        cur.add(com.playtranslate.model.ImportedSense(s.definition, pos, s.scRowid))
    }
    flush()
    return groups
}

/** The styled document's meta row, mirroring [WordDefinitionsView]'s
 *  Common pill / ★ run / frequency chips / deck badge so switching a panel
 *  to the WebView renderer never costs the meta row. */
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
            sourceLanguage = sourceLanguage,
        )
    }
}

/**
 * Runs a styled-payload fetch with datastore failure contained to null.
 * The flat tier is every caller's standing fallback — a lens bind, the
 * detail page, and both Anki send pipelines degrade to flat text on null —
 * and the Anki pipelines are exception-free by contract (their one-tap
 * hosts run detached coroutines with no handler), so a SQLite or
 * registry failure here must cost the styling, never the card or the
 * popup. Cancellation propagates.
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
        sourceLanguage = sourceLanguage,
    )
}

private const val TAG = "YomitanStyled"
