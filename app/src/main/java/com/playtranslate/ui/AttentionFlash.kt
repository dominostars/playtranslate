package com.playtranslate.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import kotlin.math.abs

/**
 * The app's attention flash, one cadence for every surface that points at a
 * view: three pulses of 400 ms, each a triangle 0 → 1 → 0 handed to
 * [onIntensity] frame by frame, so each is a full pulse and not a step; then
 * [onEnd], once they end or are cancelled, where the caller puts its view
 * back as it was. Starts after [startDelayMs] (a scroll bringing the view in
 * first). Returned for the caller to cancel, as the next flash and the
 * view's teardown do; with the system's animations off it ends at once.
 * The Anki sheet's game-audio cell and the cloud page's Join button flash
 * with it.
 */
internal fun startAttentionFlash(
    startDelayMs: Long = 0L,
    onIntensity: (Float) -> Unit,
    onEnd: () -> Unit,
): ValueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
    duration = ATTENTION_PULSE_MS
    repeatCount = ATTENTION_PULSES - 1
    startDelay = startDelayMs
    interpolator = LinearInterpolator()
    addUpdateListener { onIntensity(1f - abs(2f * it.animatedFraction - 1f)) }
    addListener(object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) = onEnd()
    })
    start()
}

private const val ATTENTION_PULSES = 3
private const val ATTENTION_PULSE_MS = 400L
