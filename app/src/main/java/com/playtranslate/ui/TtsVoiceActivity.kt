package com.playtranslate.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.Voice
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.applyTheme
import com.playtranslate.language.SourceLangId
import com.playtranslate.tts.TtsEngine
import kotlinx.coroutines.launch

/**
 * Per-game-language Text-to-Speech voice picker.
 *
 * Lists the active engine's voices for the current source language, auditions
 * one when its row is tapped, and persists the choice via the bottom Save
 * button. Mirrors [DeepLSettingsActivity] for navigation/theming: the toolbar
 * X discards, Save commits. The chosen voice is stored per language by
 * [Prefs.setTtsVoiceName] and applied automatically by [TtsEngine.speak].
 */
class TtsVoiceActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var lang: SourceLangId
    private lateinit var voiceRows: LinearLayout

    private var voices: List<Voice> = emptyList()

    /** Voice name selected in the picker, or null for the engine default.
     *  Committed to [Prefs] only when Save is pressed. */
    private var selectedName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Theme before super so the first inflation resolves ?attr/pt* against
        // the user's accent + mode. Matches LanguageSetupActivity.
        applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tts_voice)

        prefs = Prefs(this)
        // Prefer the language passed by the launcher — the Anki Audio card
        // passes the card's language so the picker edits the same voice
        // preview and synthesis use. Falls back to the global source
        // language when opened from Settings (no extra).
        lang = intent.getStringExtra(EXTRA_LANG)
            ?.let { SourceLangId.fromCode(it) }
            ?: prefs.sourceLangId
        selectedName = prefs.ttsVoiceName(lang)
        voiceRows = findViewById(R.id.voiceRows)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = "Voice"
        toolbar.setNavigationOnClickListener { finish() }

        findViewById<View>(R.id.headerVoices)
            .findViewById<TextView>(R.id.tvGroupTitle).text =
            "${lang.displayName().uppercase()} VOICES"

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            prefs.setTtsVoiceName(lang, selectedName)
            finish()
        }

        loadVoices()
    }

    override fun onDestroy() {
        TtsEngine.stop()
        super.onDestroy()
    }

    private fun loadVoices() {
        lifecycleScope.launch {
            voices = TtsEngine.voicesFor(this@TtsVoiceActivity, lang)
            renderRows()
        }
    }

    private fun renderRows() {
        voiceRows.removeAllViews()
        val inflater = LayoutInflater.from(this)

        addRow(
            inflater,
            isFirst = true,
            title = "Default",
            subtitle = "The engine's default voice",
            selected = selectedName == null,
        ) { select(null) }

        voices.forEachIndexed { index, voice ->
            addRow(
                inflater,
                isFirst = false,
                title = "Voice ${index + 1}",
                subtitle = voiceInfo(voice),
                selected = selectedName == voice.name,
            ) { select(voice.name) }
        }
    }

    private fun addRow(
        inflater: LayoutInflater,
        isFirst: Boolean,
        title: String,
        subtitle: String,
        selected: Boolean,
        onClick: () -> Unit,
    ) {
        if (!isFirst) {
            voiceRows.addView(
                inflater.inflate(R.layout.settings_row_divider, voiceRows, false)
            )
        }
        val row = inflater.inflate(R.layout.tts_voice_row, voiceRows, false)
        row.findViewById<TextView>(R.id.tvRowTitle).text = title
        row.findViewById<TextView>(R.id.tvRowSubtitle).text = subtitle
        row.findViewById<ImageView>(R.id.ivCheck).visibility =
            if (selected) View.VISIBLE else View.GONE
        row.setOnClickListener { onClick() }
        voiceRows.addView(row)
    }

    /** Select [name] (null = engine default), refresh the checkmarks, and
     *  audition the choice. */
    private fun select(name: String?) {
        selectedName = name
        renderRows()
        val voice = name?.let { n -> voices.firstOrNull { it.name == n } }
        lifecycleScope.launch {
            TtsEngine.previewVoice(this@TtsVoiceActivity, voice, lang)
        }
    }

    /** One-line API summary for a voice row: region (when the voice carries
     *  one) + quality tier + offline/online. */
    private fun voiceInfo(voice: Voice): String {
        val quality = when {
            voice.quality >= Voice.QUALITY_VERY_HIGH -> "Very high quality"
            voice.quality >= Voice.QUALITY_HIGH -> "High quality"
            voice.quality >= Voice.QUALITY_NORMAL -> "Normal quality"
            voice.quality >= Voice.QUALITY_LOW -> "Low quality"
            else -> "Very low quality"
        }
        val network = if (voice.isNetworkConnectionRequired) "Online" else "Offline"
        val region = voice.locale.displayCountry
        return if (region.isNotBlank()) "$region · $quality · $network"
        else "$quality · $network"
    }

    companion object {
        private const val EXTRA_LANG = "lang"

        /** Intent that opens the picker for a specific [lang] — used by the
         *  Anki Audio card so the picker edits the card's language, not the
         *  global source language. */
        fun intent(context: Context, lang: SourceLangId): Intent =
            Intent(context, TtsVoiceActivity::class.java)
                .putExtra(EXTRA_LANG, lang.code)
    }
}
