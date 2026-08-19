package com.playtranslate.audio

import com.playtranslate.language.SourceLangId

/**
 * Maps the app's [SourceLangId] to the codes Wikimedia uses for pronunciation
 * audio. Its own mapping (it mirrors the *style* of `TatoebaClient`'s private
 * ISO-639-3 table but needs different values — wiki codes + Lingua Libre QIDs).
 *
 * The QID table is the load-bearing "iterate-and-tune" surface for recording
 * matching. A wrong/absent QID only causes a **miss** (no `LL-Q…` filename is
 * constructed), never a false match, so the resolver simply falls back to TTS.
 */
object WikimediaLangCodes {

    /** Wiktionary/Wikipedia language code, used for `{code}-{word}.ogg` filename
     *  guesses and Commons search. Mostly the app code; a few normalizations. */
    fun wikiCode(lang: SourceLangId): String = when (lang) {
        SourceLangId.ZH, SourceLangId.ZH_HANT -> "zh"
        SourceLangId.NO -> "nb"
        else -> lang.code
    }

    /**
     * Wikidata language QID used in Lingua Libre filenames (`LL-Q{qid}-…`).
     * Null when unknown — only confident values are listed; expand as matching
     * is tuned against real Commons coverage.
     */
    fun linguaLibreQid(lang: SourceLangId): String? = when (lang) {
        SourceLangId.EN -> "Q1860"
        SourceLangId.FR -> "Q150"
        SourceLangId.DE -> "Q188"
        SourceLangId.ES -> "Q1321"
        SourceLangId.IT -> "Q652"
        SourceLangId.PT -> "Q5146"
        SourceLangId.NL -> "Q7411"
        SourceLangId.RU -> "Q7737"
        SourceLangId.JA -> "Q5287"
        SourceLangId.KO -> "Q9176"
        SourceLangId.AR -> "Q13955"
        SourceLangId.ZH, SourceLangId.ZH_HANT -> "Q9192" // Mandarin
        SourceLangId.VI -> "Q9199"
        SourceLangId.TH -> "Q9217"
        SourceLangId.TR -> "Q256"
        SourceLangId.SV -> "Q9027"
        SourceLangId.DA -> "Q9035"
        SourceLangId.NO -> "Q9043"
        SourceLangId.FI -> "Q1412"
        SourceLangId.HU -> "Q9067"
        SourceLangId.RO -> "Q7913"
        SourceLangId.CA -> "Q7026"
        SourceLangId.ID -> "Q9240"
        SourceLangId.HI -> "Q1568"
        SourceLangId.PL -> "Q809"
    }
}
