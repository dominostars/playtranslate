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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.applyEdgeToEdge
import com.playtranslate.applyTheme
import com.playtranslate.language.SourceLangId
import com.playtranslate.tts.TtsEngine
import com.playtranslate.tts.TtsVoiceLabels
import kotlinx.coroutines.launch

/**
 * Per-game-language Text-to-Speech voice picker — pure picker variant.
 *
 * Lists the active engine's voices for [lang], auditions one when its row
 * is tapped, and returns the picked voice via `setResult` when the bottom
 * Save button is pressed. The activity does NOT persist anything itself —
 * each caller decides what to do with the result:
 *  - Settings's TTS row writes [Prefs.setTtsVoiceName] from its
 *    [ActivityResultLauncher] callback.
 *  - The Anki sheet's per-cell pickers write to the fragment's transient
 *    per-cell voice state and never touch [Prefs].
 *
 * Back press / toolbar X → default [RESULT_CANCELED]; callers ignore.
 */
class TtsVoiceActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var lang: SourceLangId
    private lateinit var voiceRows: LinearLayout

    private var voices: List<Voice> = emptyList()

    /** Voice name currently selected in the picker, or null for the
     *  engine default. Returned via [setResult] when Save is pressed —
     *  the activity itself never writes [Prefs.ttsVoiceName]. */
    private var selectedName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Theme before super so the first inflation resolves ?attr/pt* against
        // the user's accent + mode. Matches LanguageSetupActivity.
        applyTheme(this)
        applyEdgeToEdge(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tts_voice)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(sys.left, sys.top, sys.right, maxOf(sys.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        prefs = Prefs(this)
        // Prefer the language passed by the launcher — the Anki cell
        // pickers pass the card's language so the picker shows the right
        // voice list. Falls back to the global source language for
        // legacy entries.
        lang = intent.getStringExtra(EXTRA_LANG)
            ?.let { SourceLangId.fromCode(it) }
            ?: prefs.sourceLangId
        // Seed selection: if the caller passed an initial voice (even
        // an explicit null, signalled by hasExtra), use it. Otherwise
        // fall back to the global pref. The Anki per-cell flow always
        // passes EXTRA_INITIAL_VOICE so the picker opens on the cell's
        // current voice; Settings can pass it explicitly too.
        selectedName = if (intent.hasExtra(EXTRA_INITIAL_VOICE)) {
            intent.getStringExtra(EXTRA_INITIAL_VOICE)
        } else {
            prefs.ttsVoiceName(lang)
        }
        voiceRows = findViewById(R.id.voiceRows)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.tts_voice_picker_title)
        toolbar.setNavigationOnClickListener { finish() }

        findViewById<View>(R.id.headerVoices)
            .findViewById<TextView>(R.id.tvGroupTitle).text =
            getString(R.string.tts_voices_section_header, lang.displayName().uppercase())

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            setResult(
                RESULT_OK,
                Intent().putExtra(EXTRA_PICKED_VOICE, selectedName),
            )
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
            title = getString(R.string.tts_voice_default),
            subtitle = getString(R.string.tts_voice_default_subtitle),
            selected = selectedName == null,
        ) { select(null) }

        voices.forEachIndexed { index, voice ->
            val label = TtsVoiceLabels.forVoice(this, voices, index)
            addRow(
                inflater,
                isFirst = false,
                title = label.title,
                subtitle = label.subtitle,
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

    companion object {
        private const val EXTRA_LANG = "lang"

        /** Optional seed for [selectedName]. When the caller sets this (even
         *  to null, treated as "Default"), the picker opens with that voice
         *  selected. When absent, the picker falls back to the global pref. */
        const val EXTRA_INITIAL_VOICE = "initial_voice"

        /** Result extra: the picked voice name, or null for "Default". */
        const val EXTRA_PICKED_VOICE = "picked_voice"

        /** Intent that opens the picker for [lang]. [initialVoice] is
         *  always written into the intent — pass the cell's current
         *  voice (per-cell flow) or the saved pref (Settings flow). */
        fun intent(
            context: Context,
            lang: SourceLangId,
            initialVoice: String?,
        ): Intent = Intent(context, TtsVoiceActivity::class.java)
            .putExtra(EXTRA_LANG, lang.code)
            .putExtra(EXTRA_INITIAL_VOICE, initialVoice)
    }
}
