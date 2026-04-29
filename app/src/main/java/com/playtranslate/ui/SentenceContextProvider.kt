package com.playtranslate.ui

/**
 * Implemented by activities that host an embedded
 * [WordDetailBottomSheet] and can supply the current sentence
 * context for Anki export on demand. Replaces the timed push
 * pipeline (`updateSentenceContext`) that previously fed shadow
 * fields on the embedded sheet.
 *
 * The embedded sheet calls [currentSentenceContext] at Anki-button
 * tap time. Implementations read live state (e.g. their
 * [TranslationResultViewModel]) and fall back to launch-time intent
 * extras when the live state hasn't settled yet.
 */
interface SentenceContextProvider {
    fun currentSentenceContext(): SentenceContext
}

data class SentenceContext(
    val original: String?,
    val translation: String?,
    val wordResults: Map<String, Triple<String, String, Int>>?,
)
