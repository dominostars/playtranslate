package com.playtranslate.language

import android.content.Context
import androidx.annotation.StringRes
import com.playtranslate.R

/**
 * One step of a Japanese conjugation chain, named on Yomitan's convention. A
 * suffix or auxiliary is labeled by its form (-て, -た, -ます, -いる): kana, the
 * same in every locale, so it has no string resource. Only the grammar names
 * are localized: negative, causative, passive, potential, potential or
 * passive, volitional, imperative and continuative (Yomitan's "short causative"
 * and "volitional slang" reuse the causative and volitional strings). Each
 * entry has exactly one of [literal] and [labelRes]; [label] returns it.
 *
 * [key] is Yomitan's transform name where one exists, spelled exactly as
 * Yomitan's Japanese transforms spell it, and [fromKey] is how a deinflection
 * trace becomes tags. Those are the 55 transform names in Yomitan's test suite
 * (app/src/test/resources/yomitan/japanese-transforms-tests.json); every one of
 * them resolves through [fromKey]. A key that is not a form is shown as a fixed
 * literal: the eight kansai-ben keys as "kansai-ben", "imperative negative
 * slang" as -んな. The entries from [DESU] on are not among those 55 names and
 * use their form as their key.
 *
 * A form's tags are listed in morpheme order, dictionary form outward, the
 * order the fixture lists its reasons in (愛しくありません: -ます, then negative).
 * The word-result cell renders them under the dictionary form joined with
 * " « " (言わせて · Causative « -て).
 */
enum class InflectionTag(
    val key: String,
    internal val literal: String?,
    @StringRes internal val labelRes: Int?,
) {
    // Grammar names, localized.
    NEGATIVE("negative", R.string.inflection_negative),
    CAUSATIVE("causative", R.string.inflection_causative),
    SHORT_CAUSATIVE("short causative", R.string.inflection_causative),
    PASSIVE("passive", R.string.inflection_passive),
    POTENTIAL("potential", R.string.inflection_potential),
    POTENTIAL_OR_PASSIVE("potential or passive", R.string.inflection_potential_or_passive),
    VOLITIONAL("volitional", R.string.inflection_volitional),
    VOLITIONAL_SLANG("volitional slang", R.string.inflection_volitional),
    IMPERATIVE("imperative", R.string.inflection_imperative),
    CONTINUATIVE("continuative", R.string.inflection_continuative),

    // Yomitan form labels, shown as their key.
    BA("-ば"),
    YA("-ゃ"),
    CHA("-ちゃ"),
    CHAU("-ちゃう"),
    CHIMAU("-ちまう"),
    SHIMAU("-しまう"),
    NASAI("-なさい"),
    SOU("-そう"),
    SUGIRU("-すぎる"),
    SUGIRU_KANJI("-過ぎる"),
    TAI("-たい"),
    TARA("-たら"),
    TARI("-たり"),
    TE("-て"),
    ZU("-ず"),
    NU("-ぬ"),
    N("-ん"),
    NBAKARI("-んばかり"),
    NTOSURU("-んとする"),
    MU("-む"),
    ZARU("-ざる"),
    NEBA("-ねば"),
    KU("-く"),
    SA("-さ"),
    TA("-た"),
    MASU("-ます"),
    MAI("-まい"),
    OKU("-おく"),
    IRU("-いる"),
    FU("-ふ"),
    KI("-き"),
    GE("-げ"),
    GARU("-がる"),
    YAGARU("-やがる"),
    E("-え"),
    N_SLANG("n-slang"),

    // Yomitan keys shown as a fixed literal.
    IMPERATIVE_NEGATIVE_SLANG("imperative negative slang", "-んな"),
    KANSAI_BEN_NEGATIVE("kansai-ben negative", "kansai-ben"),
    KANSAI_BEN_TE("kansai-ben -て", "kansai-ben"),
    KANSAI_BEN_TA("kansai-ben -た", "kansai-ben"),
    KANSAI_BEN_TARA("kansai-ben -たら", "kansai-ben"),
    KANSAI_BEN_TARI("kansai-ben -たり", "kansai-ben"),
    KANSAI_BEN_KU("kansai-ben -く", "kansai-ben"),
    KANSAI_BEN_ADJ_TE("kansai-ben adjective -て", "kansai-ben"),
    KANSAI_BEN_ADJ_NEGATIVE("kansai-ben adjective negative", "kansai-ben"),

    // Not Yomitan transform names; shown as their key.
    DESU("-です"),
    NARA("-なら"),
    ARU("-ある"),
    MIRU("-みる"),
    IKU("-いく"),
    KURU("-くる"),
    AGERU("-あげる"),
    KURERU("-くれる"),
    MORAU("-もらう"),
    YARU("-やる"),
    KUDASARU("-くださる"),
    ITADAKU("-いただく"),
    ;

    /** A form label: shown as [literal], which is the key unless given. */
    constructor(key: String, literal: String = key) : this(key, literal, null)

    /** A grammar name: shown as the localized [labelRes]. */
    constructor(key: String, @StringRes labelRes: Int) : this(key, null, labelRes)

    fun label(context: Context): String = literal ?: context.getString(labelRes!!)

    companion object {
        private val byKey: Map<String, InflectionTag> = entries.associateBy { it.key }

        fun fromKey(key: String): InflectionTag? = byKey[key]
    }
}

/**
 * One distinct inflected occurrence of a lemma in the source: the [surface] as
 * found plus the [tags] it expresses. A single lemma can surface in several
 * forms within one passage (食べたい / 食べられない), so a word-result row carries a
 * list of these rather than collapsing to the first occurrence's form.
 */
data class InflectedForm(val surface: String, val tags: List<InflectionTag>)
