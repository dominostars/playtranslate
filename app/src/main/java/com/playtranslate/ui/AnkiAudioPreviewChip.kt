package com.playtranslate.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.playtranslate.R
import com.playtranslate.language.SourceLangId
import com.playtranslate.themeColor
import com.playtranslate.tts.TtsEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The preview chip at the start of an Audio-section row — a bordered circle
 * that plays [previewText] aloud and reflects the request lifecycle:
 *  - idle:    speaker icon and ring, tinted accent when the include
 *             switch is on, muted when it is off
 *  - loading: a small ring spinner (the widget the magnifier lens uses)
 *  - playing: a pause icon; tapping it stops playback
 * Playback returns to idle when it finishes, errors, or is stopped.
 *
 * [previewText] is read at tap time so an edited sentence previews its
 * current text. One instance per row; call [stop] on host-view teardown so
 * audio doesn't outlive the sheet.
 */
class AnkiAudioPreviewChip(
    private val fragment: Fragment,
    private val lang: SourceLangId,
    private val previewText: () -> String,
    /** Per-call voice override. Read on every tap so a host that lets
     *  the user change voice mid-card (Anki per-cell flow) picks up
     *  the latest pick without needing to swap chips. Null = engine
     *  default (NOT a pref fallback — the chip's owner controls that). */
    private val voiceOverride: () -> String? = { null },
    /** Async transform applied to [previewText] before it is spoken, so the
     *  preview matches the audio the card will carry — a JA sentence cell
     *  passes the engine's kana pronunciation. Default is identity. */
    private val prepare: suspend (String) -> String = { it },
) {
    private enum class State { IDLE, LOADING, PLAYING }

    private val ctx = fragment.requireContext()
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val accent = ctx.themeColor(R.attr.ptAccent)
    /** "Disabled" colour for the chip's ring + icon when the include
     *  switch is off. Pulled to `ptOutline` so the chip blends further
     *  into the surface, matching the cell title and the voice pill —
     *  single muted attr across all three for visual cohesion. */
    private val muted = ctx.themeColor(R.attr.ptOutline)

    private var state = State.IDLE
    private var switchOn = true
    private var job: Job? = null

    /** Bumped by every [onTap] and [stop] so a late onStart / finally
     *  callback from a superseded request can tell it is stale. Only
     *  touched on the main thread. */
    private var generation = 0

    private val icon = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        layoutParams = FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER)
    }
    private val spinner = ProgressBar(ctx, null, android.R.attr.progressBarStyleSmall).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(accent)
        layoutParams = FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER)
        visibility = View.GONE
    }
    private val strokeWidthPx = dp(1).coerceAtLeast(1)

    /** The chip's ring. Held so [render] can recolour the stroke alongside
     *  the icon — accent when active, muted when the switch is off. */
    private val circleBg = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.TRANSPARENT)
        setStroke(strokeWidthPx, muted)
    }
    private val circle = FrameLayout(ctx).apply {
        background = circleBg
        layoutParams = FrameLayout.LayoutParams(
            dp(30), dp(30), Gravity.CENTER,
        )
        addView(icon)
        addView(spinner)
    }

    /** The chip view — a 44dp tap cell with the 30dp visible circle
     *  centred inside it, hung over the row's left padding via
     *  `marginStart = -7dp` and balanced with `marginEnd = 7dp` so the
     *  row's spacing to the next element (and the visible circle's
     *  position within the row) stays identical to the original
     *  layout. Net effect:
     *   - visible circle: unchanged
     *   - touch region: 44dp, centred on the visible circle
     *   - 14dp visual gap to the next element: unchanged
     *
     *  The row sets `clipToPadding = false` so the chip's ripple
     *  draws naturally into the left padding slack on a tap there.
     *  Add the view at index 0 of the audio row. */
    val view: FrameLayout = FrameLayout(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).also {
            it.marginStart = -dp(7)
            it.marginEnd = dp(7)
        }
        isClickable = true
        val ripple = TypedValue().also {
            ctx.theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, it, true,
            )
        }.resourceId
        setBackgroundResource(ripple)
        contentDescription = ctx.getString(R.string.anki_audio_preview_content_description)
        addView(circle)
        setOnClickListener { onTap() }
    }

    init { render() }

    /** Reflect the include switch: the idle speaker tints accent when on,
     *  muted when off. Turning the switch off also stops any preview. */
    fun setSwitchOn(on: Boolean) {
        switchOn = on
        if (!on && state != State.IDLE) stop() else render()
    }

    /** Halt any in-flight preview and return to the idle speaker. Used by
     *  the pause tap, by switching the include toggle off, and by the host
     *  on view teardown so audio doesn't outlive the sheet. */
    fun stop() {
        generation++
        job?.cancel()
        job = null
        TtsEngine.stop()
        state = State.IDLE
        render()
    }

    private fun onTap() {
        when (state) {
            State.PLAYING -> { stop(); return }   // pause icon → stop
            State.LOADING -> return               // ignore taps mid-load
            State.IDLE -> Unit
        }
        val raw = previewText().trim()
        if (raw.isEmpty()) return
        val gen = ++generation
        job = fragment.viewLifecycleOwner.lifecycleScope.launch {
            state = State.LOADING
            render()
            try {
                // Speak what the card will carry — a JA sentence cell renders
                // [raw] to its kana pronunciation; default identity otherwise.
                val text = prepare(raw)
                // The chip is an Anki per-cell component — null in
                // voiceOverride() means "user picked Default for this
                // cell", and that's exactly what TtsEngine treats null
                // as (engine default, no pref fallback).
                val result = TtsEngine.speak(
                    ctx, text, lang, awaitCompletion = true,
                    voiceNameOverride = voiceOverride(),
                    onStart = {
                        // Fires on the engine thread; marshal to the UI and
                        // ignore it if a newer request has superseded this.
                        view.post {
                            if (gen == generation) {
                                state = State.PLAYING
                                render()
                            }
                        }
                    },
                )
                val failure: String? = when (result) {
                    TtsEngine.SpeakResult.Spoken -> null
                    TtsEngine.SpeakResult.NoEngine ->
                        "No text-to-speech engine is available"
                    is TtsEngine.SpeakResult.LanguageUnsupported ->
                        "Text-to-speech isn't available for ${lang.displayName()}"
                }
                if (failure != null) Toast.makeText(ctx, failure, Toast.LENGTH_SHORT).show()
            } finally {
                // Skip if a newer request (or stop()) has taken over — it
                // owns the current visual state.
                if (gen == generation) {
                    state = State.IDLE
                    render()
                }
            }
        }
    }

    private fun render() {
        // Ring and icon share one colour: accent while active (loading,
        // playing, or idle with the switch on), muted only when idle with
        // the switch off.
        val color = if (state == State.IDLE && !switchOn) muted else accent
        circleBg.setStroke(strokeWidthPx, color)
        when (state) {
            State.IDLE -> {
                spinner.visibility = View.GONE
                icon.visibility = View.VISIBLE
                icon.setImageResource(R.drawable.ic_lens_speak)
                icon.imageTintList = ColorStateList.valueOf(color)
            }
            State.LOADING -> {
                icon.visibility = View.GONE
                spinner.visibility = View.VISIBLE
            }
            State.PLAYING -> {
                spinner.visibility = View.GONE
                icon.visibility = View.VISIBLE
                icon.setImageResource(R.drawable.ic_pause)
                icon.imageTintList = ColorStateList.valueOf(color)
            }
        }
    }
}
