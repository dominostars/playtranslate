package com.playtranslate.yomitan

/**
 * A dictionary we offer for one-tap download in the Yomitan settings page's
 * RECOMMENDED section. Tapping its row downloads [url] and runs it through the
 * normal import pipeline; once installed it drops out of the list (see
 * [RecommendedYomitanDictionaries.notInstalled]).
 *
 * [displayTitle]/[description] are hardcoded because there's no index.json to
 * read until the zip is downloaded. They are deliberately NOT used to detect
 * "already installed": JMnedict's index.json title carries the build date
 * ("JMnedict [2026-06-14]") and changes daily, so [matchesInstalled] — not a
 * title-equality check — decides whether it's already present.
 */
class RecommendedYomitanDictionary(
    val displayTitle: String,
    val description: String,
    val url: String,
    /** True when an already-imported dictionary's [YomitanDictionary.title]
     *  identifies it as this recommended dictionary. */
    val matchesInstalled: (title: String) -> Boolean,
)

/**
 * The curated, license-clean dictionaries surfaced in the Yomitan page's
 * RECOMMENDED section.
 *
 * URLs point straight at upstream (the chosen no-self-hosting path). Upstream
 * regenerates this content (JMnedict daily, Jiten periodically), so the URLs
 * are *mutable*: downloads must not resume a stale partial, and there's no
 * stable SHA-256 to pin — the importer's structural validation is the
 * integrity check.
 */
object RecommendedYomitanDictionaries {

    val all: List<RecommendedYomitanDictionary> = listOf(
        RecommendedYomitanDictionary(
            displayTitle = "JMnedict",
            // yomidevs' JMnedict ships an empty index.json description, so this
            // copy is ours (the installed row under Terms shows no subtitle).
            description = "Japanese proper names dictionary (EDRDG).",
            url = "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/JMnedict.zip",
            // index.json title is "JMnedict [<build-date>]" (or a bare "JMnedict").
            matchesInstalled = { it == "JMnedict" || it.startsWith("JMnedict [") },
        ),
        RecommendedYomitanDictionary(
            displayTitle = "Jiten",
            description = "Dictionary based on frequency data of all media from jiten.moe",
            url = "https://api.jiten.moe/api/frequency-list/download",
            // Exact match — a "Jiten" prefix would also catch the popular "Jitendex".
            matchesInstalled = { it == "Jiten" },
        ),
    )

    /** The recommended dictionaries not yet present in [registry]. */
    fun notInstalled(registry: YomitanRegistry): List<RecommendedYomitanDictionary> =
        all.filter { rec -> registry.dictionaries.none { rec.matchesInstalled(it.title) } }
}
