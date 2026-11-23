package com.playtranslate.ui

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/**
 * The detached bar's Capture button speaks a one-line name of its own
 * (cd_detached_capture_screen / _region), in every UI language: it holds the
 * pill's visible label (detached_bar_capture), so a voice command naming
 * what the user sees reaches the button, and it has no line break to join.
 * It used to be the icon menu's two-line label with its break turned into a
 * space: a space inside a word in Japanese, Chinese and Thai, and in Russian
 * and French a noun («Захват экрана», «Capture d'écran») that did not hold
 * the pill's verb.
 */
@RunWith(RobolectricTestRunner::class)
class DetachedCaptureSpokenNameTest {

    private val app: Context = ApplicationProvider.getApplicationContext()

    private fun inLocale(tag: String): Context = app.createConfigurationContext(
        Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) },
    )

    @Test
    fun `in every UI language each spoken name holds the visible label and no line break`() {
        for (tag in UI_LANGUAGES) {
            val ctx = inLocale(tag)
            val locale = Locale.forLanguageTag(tag)
            val label = ctx.getString(R.string.detached_bar_capture).lowercase(locale)
            for (res in listOf(R.string.cd_detached_capture_screen, R.string.cd_detached_capture_region)) {
                val name = ctx.getString(res)
                assertTrue("$tag: «$name» holds «$label»", name.lowercase(locale).contains(label))
                assertFalse("$tag: «$name» is one line", name.contains('\n'))
            }
        }
    }

    private companion object {
        val UI_LANGUAGES = listOf(
            "en", "ar", "de", "es", "fr", "ja", "ko", "pt-BR", "ru", "th", "tr", "vi", "zh-CN", "zh-HK",
        )
    }
}
