package com.playtranslate.tts

import android.content.Context
import android.content.res.Configuration
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/**
 * A device voice's subtitle ([TtsVoiceLabels.forVoice]) is written in the UI
 * language, every part of it: the quality the engine reports, Online or
 * Offline, and Needs download. The quality was once English in code, so an
 * Arabic subtitle opened with Latin text and its line ran left to right.
 */
@RunWith(RobolectricTestRunner::class)
class TtsVoiceLabelsTest {

    private val app: Context = ApplicationProvider.getApplicationContext()

    private fun voice(quality: Int, network: Boolean = false, notInstalled: Boolean = false) = Voice(
        "ja-jp-x-test", Locale.JAPAN, quality, Voice.LATENCY_NORMAL, network,
        if (notInstalled) setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) else emptySet(),
    )

    private fun subtitle(ctx: Context, v: Voice) = TtsVoiceLabels.forVoice(ctx, listOf(v), 0).subtitle

    private fun inLocale(tag: String): Context = app.createConfigurationContext(
        Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) },
    )

    @Test
    fun `each of the engine's five quality levels has its own string`() {
        val levels = listOf(
            Voice.QUALITY_VERY_HIGH to R.string.tts_voice_quality_very_high,
            Voice.QUALITY_HIGH to R.string.tts_voice_quality_high,
            Voice.QUALITY_NORMAL to R.string.tts_voice_quality_normal,
            Voice.QUALITY_LOW to R.string.tts_voice_quality_low,
            Voice.QUALITY_VERY_LOW to R.string.tts_voice_quality_very_low,
        )
        for ((quality, res) in levels) {
            assertEquals(
                "${app.getString(res)} · ${app.getString(R.string.tts_voice_offline)}",
                subtitle(app, voice(quality)),
            )
        }
    }

    @Test
    fun `the parts are quality, network, then Needs download`() {
        assertEquals(
            "High quality · Online · Needs download",
            subtitle(app, voice(Voice.QUALITY_HIGH, network = true, notInstalled = true)),
        )
    }

    @Test
    fun `an Arabic subtitle is Arabic from its first letter, so its line runs right to left`() {
        val text = subtitle(inLocale("ar"), voice(Voice.QUALITY_HIGH, network = true, notInstalled = true))
        assertEquals(Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC, Character.getDirectionality(text[0]))
        assertTrue("no English left in «$text»", text.none { it in 'A'..'Z' || it in 'a'..'z' })
    }

    @Test
    fun `every UI language names the five levels apart`() {
        for (tag in UI_LANGUAGES) {
            val ctx = inLocale(tag)
            val names = listOf(
                R.string.tts_voice_quality_very_high, R.string.tts_voice_quality_high,
                R.string.tts_voice_quality_normal, R.string.tts_voice_quality_low,
                R.string.tts_voice_quality_very_low,
            ).map(ctx::getString)
            assertEquals("$tag: $names", 5, names.toSet().size)
        }
    }

    private companion object {
        val UI_LANGUAGES = listOf(
            "en", "ar", "de", "es", "fr", "ja", "ko", "pt-BR", "ru", "th", "tr", "vi", "zh-CN", "zh-HK",
        )
    }
}
