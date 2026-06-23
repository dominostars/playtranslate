package com.playtranslate.audio

/**
 * Bounds on *decoded* audio. The Commons download cap only bounds the
 * **compressed** bytes; a small compressed clip (Ogg/Opus/MP3) can expand into a
 * very large PCM buffer, so decoding it whole into memory is an OOM / IO-stall
 * vector on external input. These limits bound the decoded result — anything
 * over them is rejected so playback falls back to TTS instead of crashing.
 * Pure + unit-testable (no Android types).
 */
internal object AudioDecodeLimits {

    /** ~16 MB of 16-bit PCM ≈ 80s mono / 40s stereo @ 48 kHz — far beyond any
     *  pronunciation clip, but a hard ceiling against pathological expansion. */
    const val MAX_PCM_BYTES = 16 * 1024 * 1024

    /** Reject before decoding if the container reports a longer clip. */
    const val MAX_DURATION_US = 120_000_000L // 120s

    /** True if a container-reported duration is over the limit. A non-positive
     *  value means "unknown" (many clips omit it) and is NOT rejected — the byte
     *  cap during decode is the reliable backstop. */
    fun durationExceedsLimit(durationUs: Long): Boolean = durationUs > MAX_DURATION_US

    /** True once accumulated decoded PCM would exceed the in-memory ceiling. */
    fun pcmExceedsLimit(accumulatedBytes: Long): Boolean = accumulatedBytes > MAX_PCM_BYTES
}
