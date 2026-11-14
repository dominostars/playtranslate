package com.playtranslate.tts

import com.playtranslate.audio.online.OnlineTtsChoice

/**
 * The voice that speaks a game language by default: one of the device
 * engine's voices, or one of the connected voice service's. There is one
 * choice per language, made on the voice page from both lists, read through
 * [com.playtranslate.Prefs.voiceChoice] and written only by
 * [com.playtranslate.Prefs.setVoiceChoice].
 *
 * There is no switch beside it. Choosing a service voice is what puts the
 * service to use for that language, and choosing a device voice is what
 * stops it.
 */
sealed interface VoiceChoice {
    /** A device voice by its [android.speech.tts.Voice] name; null is the
     *  engine's own default voice for the language. */
    data class Device(val name: String?) : VoiceChoice

    /** One of the connected voice service's voices. */
    data class Online(val voice: OnlineTtsChoice) : VoiceChoice
}
