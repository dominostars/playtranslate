package com.playtranslate.ui

import android.view.View
import android.widget.TextView
import com.google.android.material.slider.Slider
import com.playtranslate.Prefs
import com.playtranslate.R
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow

/**
 * Wires a `section_tts_speed` layout: the global TTS speed slider shown at
 * the top of the voice picker (Settings) and above the Text-to-speech
 * section of the Anki audio picker.
 *
 * The track runs half speed to double speed on a log scale ("-2x" at the
 * start edge, "2x" at the end) with magnetic detents at 1x and 1.5x
 * slower/faster — each marked by a dot under the track. Releasing the thumb — a plain tap on the
 * track included — persists the rate as the app-wide default every
 * utterance is spoken at, synthesized Anki audio included (applied by
 * [com.playtranslate.tts.TtsEngine]), then fires the host's preview
 * callback so the user hears the result.
 */
object TtsSpeedSection {

    /** Track position of the ±1.5x detents: rate 1.5 (or 1/1.5) on the
     *  2^t scale. ≈0.585 — the dots in `section_tts_speed` sit at the
     *  matching layout biases. */
    private val DETENT_1_5X = log2(1.5f)

    /** The snap detents: 1.5x slower, 1x, 1.5x faster. */
    private val DETENTS = floatArrayOf(-DETENT_1_5X, 0f, DETENT_1_5X)

    /** Positions within this distance of a detent snap to it — a small
     *  magnetic catch (2.5% of the -1..1 track per side of each detent);
     *  the rest of the track moves freely. */
    private const val SNAP_RANGE = 0.05f

    fun bind(root: View, prefs: Prefs, onPreview: () -> Unit) {
        root.findViewById<View>(R.id.headerSpeed)
            .findViewById<TextView>(R.id.tvGroupTitle)
            .setText(R.string.tts_speed_section_header)

        val slider = root.findViewById<Slider>(R.id.speedSlider)
        slider.value = rateToPosition(prefs.ttsSpeechRate ?: 1f)
        slider.addOnChangeListener { s, value, fromUser ->
            // Magnetic detents: while the thumb is within SNAP_RANGE of the
            // nearest detent it rides at that detent. Guarded on fromUser —
            // the snap's own setValue re-enters this listener with
            // fromUser = false.
            if (!fromUser) return@addOnChangeListener
            val detent = DETENTS.minBy { abs(value - it) }
            if (value != detent && abs(value - detent) < SNAP_RANGE) s.value = detent
        }
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                // Persist BEFORE previewing — the preview path reads the pref.
                prefs.setTtsSpeechRate(positionToRate(slider.value))
                onPreview()
            }
        })
    }

    /** Slider position -1..1 → rate 0.5..2.0. 2^position: a log scale, so
     *  equal drags feel like equal speed-ups on both sides, and the centre
     *  maps to exactly 1.0. */
    private fun positionToRate(position: Float): Float = 2f.pow(position)

    private fun rateToPosition(rate: Float): Float =
        log2(rate).coerceIn(-1f, 1f)
}
