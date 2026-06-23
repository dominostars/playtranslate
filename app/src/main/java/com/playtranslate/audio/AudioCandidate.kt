package com.playtranslate.audio

import android.content.Context

/**
 * A candidate's display title. Kept as data (a literal or a string resource) so
 * an [AudioCandidate] stays a pure value with no `Context` — resolved to text
 * only at render time.
 */
sealed interface CandidateLabel {
    data class Text(val value: String) : CandidateLabel
    data class Res(val resId: Int, val args: List<Any> = emptyList()) : CandidateLabel

    fun resolve(ctx: Context): String = when (this) {
        is Text -> value
        is Res -> if (args.isEmpty()) ctx.getString(resId) else ctx.getString(resId, *args.toTypedArray())
    }
}

/**
 * A single selectable/playable audio option (one TTS voice, or one Commons
 * recording). **Pure value**: safe to hold, persist as a selection, and unit-test
 * without a `Context`. All behavior (play / synthesize-to-file) lives on the
 * owning [AudioSource], which receives the candidate back — so the candidate
 * never closes over a request or a `Context`.
 */
data class AudioCandidate(
    val sourceId: String,
    /** Stable selection id within the source: a voice name, or a Commons filename. */
    val key: String,
    val title: CandidateLabel,
    val subtitle: CandidateLabel? = null,
    val attribution: Attribution? = null,
    /** Resolution hint for the source: audio URL, cached path, or voice name. */
    val locator: String? = null,
)
