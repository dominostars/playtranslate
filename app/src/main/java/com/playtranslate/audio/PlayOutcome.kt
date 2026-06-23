package com.playtranslate.audio

/**
 * Result of attempting to play a candidate. Deliberately a generic audio type,
 * not the TTS-only `com.playtranslate.tts.SpeakResult` — recording sources have
 * their own failure modes (download/decode/playback) that the TTS vocabulary
 * can't express.
 *
 * The two `Tts*` cases are the exception: TTS is the always-on floor, so its
 * engine-availability is a system-level terminal outcome that callers like
 * `OriginalSpeakButton` surface as alerts. They map 1:1 from `SpeakResult`.
 */
sealed interface PlayOutcome {
    /** Audio started (or completed, when awaited) successfully. */
    data object Played : PlayOutcome

    /** Source had nothing to play for this request. */
    data object NoResult : PlayOutcome

    /**
     * A recording source failed (network/download/decode/playback). When
     * [recoverable] the resolver should try the next source (→ TTS floor).
     */
    data class Failed(val recoverable: Boolean) : PlayOutcome

    /** TTS engine is present but can't speak this language. */
    data class TtsLanguageUnsupported(val engineLabel: String?) : PlayOutcome

    /** No usable TTS engine is installed. */
    data object TtsNoEngine : PlayOutcome
}
