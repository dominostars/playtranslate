package com.playtranslate.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.playtranslate.R

/**
 * User-facing labels for the voices [TtsEngine.voicesFor] returns.
 *
 * Centralised here, beside the sort that defines voice ordering, so the three
 * surfaces that name a voice can't drift apart: the voice picker (title +
 * subtitle), the Settings TTS digest, and the Anki per-cell pill.
 *
 * Naming rules:
 *  - When a language's voices span more than one region (e.g. Chinese:
 *    Mainland / Taiwan / Hong Kong) the region is the meaningful
 *    differentiator, so it becomes the title; repeats within one region get a
 *    1-based suffix ("China 1", "China 2").
 *  - Otherwise (Japanese, Korean — a single region) the region says nothing
 *    the picker's section header doesn't, so the title stays the positional
 *    "Voice N".
 *  - The subtitle carries quality + online/offline, plus a "Needs download"
 *    tag for voices the engine enumerates but hasn't installed yet
 *    ([TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED]) — picking one otherwise
 *    silently falls back to another voice.
 *
 * Pure formatting: callers fetch the (suspending) voice list once and pass it
 * in, so these stay synchronous and unit-testable. The list MUST be the one
 * from [TtsEngine.voicesFor] — its order defines the numbering.
 */
object TtsVoiceLabels {

    /** Title + subtitle for the voice at [index] in [voices] — the picker's
     *  two-line row. */
    fun forVoice(context: Context, voices: List<Voice>, index: Int): VoiceLabel =
        VoiceLabel(title(context, voices, index), subtitle(context, voices[index]))

    /** Title for a saved voice [name] (null = engine default) resolved against
     *  [voices]; the Default title when [name] is null or no longer present in
     *  the engine's list. Used by the single-line surfaces — the Settings
     *  digest and the Anki pill. */
    fun titleFor(context: Context, voices: List<Voice>, name: String?): String {
        if (name == null) return context.getString(R.string.tts_voice_default)
        val index = voices.indexOfFirst { it.name == name }
        return if (index >= 0) title(context, voices, index)
        else context.getString(R.string.tts_voice_default)
    }

    private fun title(context: Context, voices: List<Voice>, index: Int): String {
        val voice = voices[index]
        val country = voice.locale.country
        val regionName = voice.locale.displayCountry
        val spansRegions = voices.mapTo(HashSet()) { it.locale.country }.size > 1
        // Single-region language, or a voice with no readable region: the
        // region adds nothing, so fall back to the positional number.
        if (!spansRegions || regionName.isBlank()) {
            return context.getString(R.string.tts_voice_numbered, index + 1)
        }
        val sameRegion = voices.count { it.locale.country == country }
        if (sameRegion <= 1) return regionName
        // More than one voice in this region — number them within the region.
        val nth = voices.take(index + 1).count { it.locale.country == country }
        return context.getString(R.string.tts_voice_region_numbered, regionName, nth)
    }

    private fun subtitle(context: Context, voice: Voice): String {
        val quality = when {
            voice.quality >= Voice.QUALITY_VERY_HIGH -> "Very high quality"
            voice.quality >= Voice.QUALITY_HIGH -> "High quality"
            voice.quality >= Voice.QUALITY_NORMAL -> "Normal quality"
            voice.quality >= Voice.QUALITY_LOW -> "Low quality"
            else -> "Very low quality"
        }
        val network = if (voice.isNetworkConnectionRequired) "Online" else "Offline"
        val parts = mutableListOf(quality, network)
        if (voice.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true) {
            parts += context.getString(R.string.tts_voice_needs_download)
        }
        return parts.joinToString(" · ")
    }
}

/** A voice's two-line description; single-line surfaces use [title] only. */
data class VoiceLabel(val title: String, val subtitle: String)
