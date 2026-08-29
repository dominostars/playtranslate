package com.playtranslate.ui

import android.content.Context
import com.playtranslate.R
import com.playtranslate.translation.ActiveCooldown
import com.playtranslate.translation.CooldownCause
import com.playtranslate.translation.CooldownMessageClass

/**
 * The single cause-to-wording mapping for online-failure degradation,
 * shared by the results note (CaptureService), the camera panel's note
 * (CameraTranslator), and the floating-icon pill (FloatingIconMenu) so
 * the three surfaces can never drift apart.
 *
 * Callers check for NO NETWORK first and use the no-internet strings —
 * a cooldown still ticking after connectivity died is not what the user
 * needs to fix. A null [ActiveCooldown.cause] (persisted state from a
 * build predating cause tracking) renders as the transient class.
 * ACCOUNT wording carries no retry time by design: billing re-probes
 * loop every few minutes, so an "until X" there would be a false
 * promise — see [CooldownMessageClass].
 */
object DegradedMessages {

    /** Inline note above degraded results when the network is up. Falls
     *  back to the generic service-unavailable line when nothing is in
     *  cooldown (a fresh failure, or a non-Cooldownable backend). */
    fun onlineFailureNote(context: Context, cooldown: ActiveCooldown?): String {
        if (cooldown == null) {
            return context.getString(R.string.note_mlkit_service_unavailable)
        }
        return when (messageClass(cooldown)) {
            CooldownMessageClass.QUOTA -> context.getString(
                R.string.note_mlkit_quota_exhausted,
                CooldownTimeLabel.format(context, cooldown.retryAt),
            )
            CooldownMessageClass.ACCOUNT ->
                context.getString(R.string.note_mlkit_account_issue)
            CooldownMessageClass.TRANSIENT -> context.getString(
                R.string.note_mlkit_service_cooldown,
                CooldownTimeLabel.format(context, cooldown.retryAt),
            )
        }
    }

    /** Floating-icon pill label for an Offline-kind degradation with an
     *  active cooldown. (No cooldown keeps the plain offline label —
     *  the pill caller handles that branch.) */
    fun pillText(context: Context, cooldown: ActiveCooldown): String =
        when (messageClass(cooldown)) {
            CooldownMessageClass.QUOTA -> context.getString(
                R.string.degraded_warning_quota,
                CooldownTimeLabel.format(context, cooldown.retryAt),
            )
            CooldownMessageClass.ACCOUNT ->
                context.getString(R.string.degraded_warning_account)
            CooldownMessageClass.TRANSIENT -> context.getString(
                R.string.degraded_warning_cooldown,
                CooldownTimeLabel.format(context, cooldown.retryAt),
            )
        }

    private fun messageClass(cooldown: ActiveCooldown): CooldownMessageClass =
        (cooldown.cause ?: CooldownCause.RATE_LIMITED).messageClass
}
