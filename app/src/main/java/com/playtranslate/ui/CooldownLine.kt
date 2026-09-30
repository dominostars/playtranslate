package com.playtranslate.ui

import android.content.Context
import com.playtranslate.R

/**
 * A service's cooldown line on a settings page (a Translation services
 * row, the PlayTranslate Cloud page): "Rate limited · Retry at 3:42 PM"
 * for a cooldown ending within ~24h, "Monthly quota used · Retry on Jun 1"
 * for a longer one, the time or date from [CooldownTimeLabel]. [nowMs]
 * decides both the word and the label, so the two always agree.
 * [description] is the cooldown's own
 * ([com.playtranslate.translation.Cooldownable.unavailableDescription]),
 * which answers null once the cooldown has expired, so one that expires
 * between the caller's two reads reads as the generic "Unavailable".
 */
fun cooldownLine(
    context: Context,
    description: String?,
    retryAt: Long,
    nowMs: Long = System.currentTimeMillis(),
): String {
    val word = context.getString(
        if (CooldownTimeLabel.isWithinDay(retryAt, nowMs)) R.string.backend_cooldown_retry_at
        else R.string.backend_cooldown_retry_on
    )
    return context.getString(
        R.string.backend_cooldown_status_fmt,
        description ?: context.getString(R.string.backend_status_unavailable_default),
        word,
        CooldownTimeLabel.format(context, retryAt, nowMs),
    )
}
