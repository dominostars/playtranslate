package com.playtranslate.ui

import android.app.Activity
import android.content.Context
import com.playtranslate.R
import com.playtranslate.audio.online.OnlineTtsException
import com.playtranslate.translation.BackendFailureKind

/**
 * What a speech request asked for, by the names the user picked them
 * under: the voice as its row reads, the model as the service page's Model
 * row reads, or null when the request names no model.
 */
internal class SpeechAsked(val voice: String, val model: String?)

/**
 * What a voice service's failure says to the user who is setting the
 * service up: one sentence for its kind, then the service's own words when
 * it answered in words. One wording for the places that show it: the voice
 * page (a voice that could not speak its sample, a list of voices that
 * would not load) and the service page (a list of models that would not
 * load). [ofList] says the failure is a list's, not a sample's.
 *
 * A speech request the service refused also says what it [asked] for. A
 * refusal is nearly always about the model or the voice the request names
 * (a model left over from another service, one the service retired, one
 * that does not speak, a voice it does not have), and the service's own
 * words may not say which, or anything a user can act on. The two names
 * and where each is changed are what the app does know.
 */
internal fun voiceServiceFailureMessage(
    ctx: Context,
    e: OnlineTtsException,
    ofList: Boolean = false,
    asked: SpeechAsked? = null,
): String {
    val code = e.failure.httpCode
    val sentence = when (e.failure.kind) {
        // What the service answered was not what was asked for. Which that
        // was has to be said: "no audio" under a list of voices that would
        // not load names the wrong thing.
        BackendFailureKind.BAD_RESPONSE ->
            if (ofList) ctx.getString(R.string.voice_service_failure_bad_list)
            else ctx.getString(R.string.voice_service_failure_bad_response)
        BackendFailureKind.AUTH -> ctx.getString(R.string.voice_service_failure_auth)
        BackendFailureKind.RATE_LIMITED -> ctx.getString(R.string.voice_service_failure_rate_limited)
        BackendFailureKind.TIMEOUT -> ctx.getString(R.string.voice_service_failure_timeout)
        BackendFailureKind.UNREACHABLE -> ctx.getString(R.string.voice_service_failure_unreachable)
        BackendFailureKind.SERVER_ERROR ->
            if (code != null) ctx.getString(R.string.voice_service_failure_server_error, code)
            else ctx.getString(R.string.voice_service_failure_unreachable)
        // REJECTED with a status is the service's refusal; without one, no
        // request was sent. The other kinds are not ones this client makes.
        else ->
            if (code != null) ctx.getString(R.string.voice_service_failure_rejected, code)
            else ctx.getString(R.string.voice_service_failure_no_request)
    }
    val refused = e.failure.kind == BackendFailureKind.REJECTED && code != null
    val named = asked?.takeIf { refused }?.let { it.model }?.let { model ->
        ctx.getString(R.string.voice_service_failure_rejected_asked, asked.voice, model)
    }
    return listOfNotNull(sentence, named, e.detail?.takeIf { it.isNotBlank() }).joinToString("\n\n")
}

/** The alert for [e], over [activity]. */
internal fun showVoiceServiceFailure(
    activity: Activity,
    e: OnlineTtsException,
    ofList: Boolean = false,
    asked: SpeechAsked? = null,
) {
    OverlayAlert.Builder(activity)
        .setTitle(activity.getString(R.string.voice_service_failed_title))
        .setMessage(voiceServiceFailureMessage(activity, e, ofList, asked))
        .addCancelButton(activity.getString(R.string.btn_ok))
        .show()
}
