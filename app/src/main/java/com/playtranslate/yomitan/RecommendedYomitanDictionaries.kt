package com.playtranslate.yomitan

/**
 * A dictionary we offer for one-tap download in the Yomitan settings page's
 * RECOMMENDED section. Tapping its row downloads [url] and runs it through the
 * normal import pipeline; once installed it drops out of the list (see
 * [RecommendedYomitanDictionaries.offered]).
 *
 * Every field is hardcoded because there's no index.json to read until the
 * zip is downloaded. [displayTitle] is display copy only and never decides
 * "already installed": upstream titles carry a release date that changes with
 * every release ("JMnedict [2026-10-14]", "Jitendex.org [2026-10-03]"), so
 * [matchesInstalled] compares [identityTitle] under the updater's
 * release-independent title rule.
 */
class RecommendedYomitanDictionary(
    val displayTitle: String,
    val description: String,
    val url: String,
    /** The index.json title without its release-date stamp. */
    val identityTitle: String,
    /** The language the dictionary is for, as an index.json sourceLanguage
     *  tag; offered only to users reading that source language. */
    val sourceLanguage: String,
    /** The language of the dictionary's definitions, when the row is offered
     *  only to users translating into it: imported definitions are shown
     *  untranslated, above the target-language ones. Null offers it for every
     *  target. */
    val targetLanguage: String?,
) {
    /** True when an already-imported dictionary's [YomitanDictionary.title]
     *  is this dictionary, by the rule the updater uses to accept a new
     *  release as the dictionary it replaces. */
    fun matchesInstalled(title: String): Boolean =
        YomitanDictionaryStore.isSameTitleIdentity(title, identityTitle)
}

/**
 * The curated, license-clean dictionaries surfaced in the Yomitan page's
 * RECOMMENDED section, in display order.
 *
 * URLs point straight at upstream (the chosen no-self-hosting path). Upstream
 * regenerates this content (JMnedict daily, Jitendex and Jiten periodically),
 * so the URLs are *mutable*: downloads must not resume a stale partial, and
 * there's no stable SHA-256 to pin; the importer's structural validation is
 * the integrity check.
 */
object RecommendedYomitanDictionaries {

    val all: List<RecommendedYomitanDictionary> = listOf(
        RecommendedYomitanDictionary(
            displayTitle = "Jitendex.org",
            description = "Japanese-English dictionary with example sentences and usage notes.",
            url = "https://github.com/stephenmk/stephenmk.github.io/releases/latest/download/jitendex-yomitan.zip",
            // index.json title is "Jitendex.org [<release-date>]".
            identityTitle = "Jitendex.org",
            sourceLanguage = "ja",
            // Its definitions are English only (index.json targetLanguage "en").
            targetLanguage = "en",
        ),
        RecommendedYomitanDictionary(
            displayTitle = "JMnedict",
            // yomidevs' JMnedict ships an empty index.json description, so this
            // copy is ours (the installed row under Terms shows no subtitle).
            description = "Japanese proper names dictionary (EDRDG).",
            url = "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/JMnedict.zip",
            // index.json title is "JMnedict [<build-date>]".
            identityTitle = "JMnedict",
            sourceLanguage = "ja",
            // English, but mostly romanized names, so offered for every target.
            targetLanguage = null,
        ),
        RecommendedYomitanDictionary(
            displayTitle = "Jiten",
            description = "Dictionary based on frequency data of all media from jiten.moe",
            url = "https://api.jiten.moe/api/frequency-list/download",
            identityTitle = "Jiten",
            sourceLanguage = "ja",
            // Frequency data, no definitions.
            targetLanguage = null,
        ),
    )

    /** The recommended dictionaries to show a user translating [sourceLanguage]
     *  into [targetLanguage]: those for that source, and for that target when
     *  they name one, that [registry] doesn't hold yet. */
    fun offered(
        registry: YomitanRegistry,
        sourceLanguage: String,
        targetLanguage: String,
    ): List<RecommendedYomitanDictionary> =
        all.filter { rec ->
            isSamePrimaryLanguage(rec.sourceLanguage, sourceLanguage) &&
                rec.targetLanguage.let { it == null || isSamePrimaryLanguage(it, targetLanguage) } &&
                registry.dictionaries.none { rec.matchesInstalled(it.title) }
        }
}
