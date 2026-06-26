package com.playtranslate.model

import android.util.Log
import com.playtranslate.PtJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

/**
 * Canonical misc register-tag vocabulary — the misc analog of [PosVocabulary].
 *
 * The data lives ONCE in `resources/misc_vocabulary.json` (also read by the
 * build filter, `scripts/wiktionary_filters.py` `filter_misc`), so the build
 * and render layers can't drift. It's a **classpath resource**, so it loads
 * lazily with no [android.content.Context] and works identically in the app and
 * in plain JVM unit tests (the latter was why a Context-loaded global broke
 * `isKanaOnly` tests). The small, stable [MiscCode] enum is compile-checked
 * against string resources by `MiscLabels.stringRes`.
 *
 * Render is the cleanliness AUTHORITY (see `MiscLabels.renderMisc`): a token is
 * localized if [canonical], passed through raw if [isPassthrough] (domain /
 * region), else dropped — so surfaces stay clean regardless of which pack
 * version is installed. `PROVERB` / `ABBREVIATION` are deliberately NOT
 * [MiscCode]s; [PosVocabulary] owns them.
 */
object MiscVocabulary {

    /** Register/usage + dialect codes we localize. Kept in sync with the JSON
     *  `register[].code` values by `MiscVocabularyTest`. */
    enum class MiscCode {
        KANA_ONLY, KANJI_ONLY,
        COLLOQUIAL, INFORMAL, FORMAL, LITERARY,
        HONORIFIC, HUMBLE, POLITE, FAMILIAR, ENDEARING, CHILDRENS,
        FEMALE_SPEECH, MALE_SPEECH,
        ARCHAIC, OBSOLETE, DATED, HISTORICAL, RARE, NEOLOGISM,
        SLANG, INTERNET_SLANG, MANGA_SLANG, IDIOMATIC, FIGURATIVE,
        ONOMATOPOEIA, POETIC, HUMOROUS,
        DEROGATORY, OFFENSIVE, VULGAR, SLUR, SENSITIVE, EUPHEMISTIC, SARCASTIC,
        NONSTANDARD, YOJIJUKUGO, DIALECTAL,
    }

    @Serializable
    internal data class VocabFile(
        val register: List<RegisterEntry> = emptyList(),
        val domainAllowlist: List<String> = emptyList(),
        val regionGazetteer: List<String> = emptyList(),
    )

    @Serializable
    internal data class RegisterEntry(
        val code: String,
        val label: String,
        val aliases: List<String> = emptyList(),
    )

    private class Vocab(
        val alias: Map<String, MiscCode>,
        val passthrough: Set<String>,
        val label: Map<MiscCode, String>,
    )

    @Volatile private var vocab: Vocab? = null

    /** Lazily parse the classpath resource once. Degrades to an empty
     *  vocabulary on any error (render then shows no misc — never crashes); the
     *  parity test catches a malformed file before ship. */
    private fun current(): Vocab {
        vocab?.let { return it }
        return synchronized(this) {
            vocab ?: run {
                val parsed = try {
                    val json = MiscVocabulary::class.java
                        .getResourceAsStream(RESOURCE_PATH)
                        ?.bufferedReader()?.use { it.readText() }
                        ?: error("$RESOURCE_PATH not found on classpath")
                    parse(json)
                } catch (e: Exception) {
                    Log.w(TAG, "misc_vocabulary.json unavailable; misc tags disabled", e)
                    Vocab(emptyMap(), emptySet(), emptyMap())
                }
                vocab = parsed
                parsed
            }
        }
    }

    /** Testable override: parse a specific JSON string. Throws on an unknown
     *  `code` (a JSON↔enum mismatch) so the parity test fails loudly. */
    internal fun loadFromJson(json: String) {
        vocab = parse(json)
    }

    private fun parse(json: String): Vocab {
        val file = PtJson.lenient.decodeFromString<VocabFile>(json)
        val alias = HashMap<String, MiscCode>()
        val label = HashMap<MiscCode, String>()
        for (e in file.register) {
            val code = MiscCode.valueOf(e.code)
            label[code] = e.label
            (e.aliases + e.label).forEach { alias[normalize(it)] = code }
        }
        val passthrough = HashSet<String>()
        (file.domainAllowlist + file.regionGazetteer).forEach { passthrough.add(normalize(it)) }
        return Vocab(alias, passthrough, label)
    }

    /** Register/dialect code for a stored misc token, or null. */
    fun canonical(token: String): MiscCode? = current().alias[normalize(token)]

    /** True for allowlisted domain or gazetteer-region tokens (rendered raw). */
    fun isPassthrough(token: String): Boolean = normalize(token) in current().passthrough

    /** English label for a code (Anki export stays English, never localized). */
    fun englishLabel(code: MiscCode): String = current().label[code] ?: code.name

    /** Expand a legacy (pre-tab) target pack's comma-joined misc blob into its
     *  individual tokens. Such a blob is unrecognized as a whole, so splitting
     *  it recovers the cleaned tags without a forced re-download; recognized
     *  tokens — including curated domain labels that contain commas, like
     *  "food, cooking" — are left intact, and current tab-delimited packs (whose
     *  tokens are all recognized) are a no-op here. Shared by [renderMisc] and
     *  [englishMisc] so both paths stay backward-compatible. */
    fun expandLegacyMisc(tokens: List<String>): List<String> =
        tokens.flatMap { token ->
            val t = token.trim()
            if (canonical(t) == null && !isPassthrough(t) && ',' in t) {
                t.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            } else {
                listOf(t)
            }
        }

    /** Same filter as `renderMisc` but English-only and Context-free — for Anki
     *  card HTML, which must stay portable. Register/dialect → English label,
     *  domain/region → raw, everything else dropped. */
    fun englishMisc(tokens: List<String>): List<String> =
        expandLegacyMisc(tokens).mapNotNull { token ->
            canonical(token)?.let { englishLabel(it) }
                ?: if (isPassthrough(token)) token else null
        }.distinct()

    private fun normalize(token: String): String = token.trim().lowercase()

    private const val RESOURCE_PATH = "/misc_vocabulary.json"
    private const val TAG = "MiscVocabulary"
}
