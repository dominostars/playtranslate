package com.playtranslate.ui

/**
 * User-input events emitted by [TranslationResultFragment] and
 * handled by its host activity. Replaces the per-listener setter
 * pattern (`setOnEditOriginalListener`, etc.) and the host-method
 * calls the fragment used to make directly into the activity.
 *
 * The fragment owns rendering + view-state-only popups; everything
 * that requires activity context (re-translate, open word detail
 * dialog, pause live mode, launch Anki review) flows through this
 * sink so the activity decides.
 */
sealed class TranslationResultEvent {
    object EditOriginalRequested : TranslationResultEvent()
    object AnkiClicked : TranslationResultEvent()
    object ClearRequested : TranslationResultEvent()
    object UserScrolled : TranslationResultEvent()
    /** Fired when a word inside the result panel is tapped (via the
     *  word-tap popup's "open" button). The activity opens the word
     *  detail sheet and supplies sentence context from VM state. */
    data class WordTapped(
        val word: String,
        val reading: String?,
    ) : TranslationResultEvent()
}

interface TranslationResultEventSink {
    fun onEvent(event: TranslationResultEvent)
}
