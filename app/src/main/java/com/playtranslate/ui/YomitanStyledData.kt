package com.playtranslate.ui

import android.content.Context
import com.playtranslate.model.ImportedSenseGroup
import com.playtranslate.yomitan.YomitanDataStore

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
    for (tag in data.frequencies) {
        add(DefinitionsDocument.MetaChip("${tag.source}: ${tag.display}", accentColor = tag.accentColor))
    }
    if (data.ankiDecks.isNotEmpty()) {
        add(DefinitionsDocument.MetaChip(AnkiDeckBadge.label(ctx, data.ankiDecks)))
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
