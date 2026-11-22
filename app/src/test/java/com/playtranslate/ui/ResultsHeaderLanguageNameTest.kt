package com.playtranslate.ui

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.language.ChineseScriptVariant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * What a results page's two section headers call the languages, on the real
 * headers (fragment_translation_result.xml under [TranslationSectionBinder]).
 *
 * A header names the language alone: "Chinese" for a Simplified and a
 * Traditional source alike, and for every Chinese target variant. The header
 * shares its row with the section's actions, which fold behind ⋯ as the name
 * grows. The whole name, script and region included, is still what the binder
 * hands a host with room for it ([TranslationSectionBinder.sourceLanguageName],
 * read by the detached window's menu rows).
 */
@RunWith(RobolectricTestRunner::class)
class ResultsHeaderLanguageNameTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private val app: Context = ApplicationProvider.getApplicationContext()
    private lateinit var controller: ActivityController<Host>
    private lateinit var prefs: Prefs
    private lateinit var root: View
    private lateinit var binder: TranslationSectionBinder
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Before
    fun setUp() {
        clearPrefs()
        controller = Robolectric.buildActivity(Host::class.java).setup()
        val activity = controller.get()
        prefs = Prefs(activity)
        root = LayoutInflater.from(activity).inflate(R.layout.fragment_translation_result, null)
        binder = TranslationSectionBinder(
            root, activity, prefs, scope, TtsAlertTarget.InActivity(activity), PopoverHost(root as FrameLayout),
        )
    }

    @After
    fun tearDown() {
        binder.release()
        scope.cancel()
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        clearPrefs()
    }

    private fun original(): String = root.findViewById<TextView>(R.id.labelOriginal).text.toString()
    private fun translation(): String = root.findViewById<TextView>(R.id.labelTranslation).text.toString()

    @Test fun `a Simplified source is headed Chinese, and its whole name is kept for a host with room`() {
        prefs.sourceLang = "zh"
        prefs.setTarget("en")
        binder.updateLabels()

        assertEquals("Chinese", original())
        assertEquals("English", translation())
        assertEquals("Chinese (Simplified)", binder.sourceLanguageName.toString())
        assertEquals("English", binder.targetLanguageName.toString())
    }

    @Test fun `a Traditional source is headed Chinese too`() {
        prefs.sourceLang = "zh-Hant"
        prefs.setTarget("en")
        binder.updateLabels()

        assertEquals("Chinese", original())
        assertEquals("Chinese (Traditional)", binder.sourceLanguageName.toString())
    }

    @Test fun `a Chinese target is headed by the language alone, whichever variant`() {
        prefs.sourceLang = "en"
        for (variant in ChineseScriptVariant.all) {
            prefs.setTarget("zh", variant)
            binder.updateLabels()
            assertEquals("the header for ${variant.code}", "中文", translation())
        }
        prefs.setTarget("zh", ChineseScriptVariant.TRADITIONAL_TW)
        assertEquals("中文（繁体，台湾）", binder.targetLanguageName.toString())
    }

    @Test fun `a pair with no Chinese in it reads as it did`() {
        prefs.sourceLang = "ja"
        prefs.setTarget("en")
        binder.updateLabels()

        assertEquals("Japanese", original())
        assertEquals("English", translation())
        assertEquals("Japanese", binder.sourceLanguageName.toString())
    }

    private fun clearPrefs() {
        app.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }
}
