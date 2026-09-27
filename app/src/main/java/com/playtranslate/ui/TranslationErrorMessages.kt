package com.playtranslate.ui

import android.content.Context
import com.playtranslate.R
import com.playtranslate.translation.BackendFailureKind
import com.playtranslate.translation.TranslationError

/**
 * The one mapping from a [TranslationError] to the words on its pill.
 * Every message is a string resource: never the provider's own error text,
 * which is English-only, can echo a request URL carrying the captured text
 * (the translation diagnostics' privacy rule), and would land in the
 * user's own screenshots.
 */
object TranslationErrorMessages {

    fun text(context: Context, error: TranslationError): String = when (error) {
        TranslationError.Connection -> context.getString(R.string.translation_error_connection)
        is TranslationError.Service -> {
            val name = error.serviceName
            when (error.failure.kind) {
                BackendFailureKind.AUTH ->
                    context.getString(R.string.translation_error_auth, name)
                BackendFailureKind.BILLING ->
                    context.getString(R.string.translation_error_billing, name)
                BackendFailureKind.DAILY_QUOTA ->
                    context.getString(R.string.translation_error_daily_quota, name)
                BackendFailureKind.MONTHLY_QUOTA ->
                    context.getString(R.string.translation_error_monthly_quota, name)
                BackendFailureKind.RATE_LIMITED ->
                    context.getString(R.string.translation_error_rate_limited, name)
                BackendFailureKind.SERVER_ERROR ->
                    context.getString(R.string.translation_error_server, name)
                BackendFailureKind.REJECTED -> {
                    // The status as plain ASCII digits (a protocol code, not
                    // a count), so every locale shows the number a support
                    // thread or the provider's docs would use.
                    val code = error.failure.httpCode
                    if (code != null) {
                        context.getString(R.string.translation_error_rejected_code, name, code.toString())
                    } else {
                        context.getString(R.string.translation_error_rejected, name)
                    }
                }
                BackendFailureKind.BAD_RESPONSE ->
                    context.getString(R.string.translation_error_bad_response, name)
                BackendFailureKind.TIMEOUT ->
                    context.getString(R.string.translation_error_timeout, name)
                BackendFailureKind.UNREACHABLE ->
                    context.getString(R.string.translation_error_unreachable, name)
            }
        }
    }
}
