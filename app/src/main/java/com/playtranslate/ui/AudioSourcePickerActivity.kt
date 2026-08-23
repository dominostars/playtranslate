package com.playtranslate.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.playtranslate.CaptureService
import com.playtranslate.Prefs
import com.playtranslate.PtJson
import com.playtranslate.R
import com.playtranslate.applyEdgeToEdge
import com.playtranslate.applyTheme
import com.playtranslate.audio.Attribution
import com.playtranslate.audio.AudioCandidate
import com.playtranslate.audio.AudioRequest
import com.playtranslate.audio.AudioSelection
import com.playtranslate.audio.AudioSelections
import com.playtranslate.audio.AudioSource
import com.playtranslate.audio.AudioSourceRegistry
import com.playtranslate.audio.PronunciationPlayer
import com.playtranslate.audio.SpokenText
import com.playtranslate.audio.sources.RecordingAudioSource
import com.playtranslate.audio.sources.TtsAudioSource
import com.playtranslate.language.SourceLangId
import com.playtranslate.tts.TtsEngine
import com.playtranslate.themeColor
import kotlinx.coroutines.launch

/**
 * Multi-section audio picker for an Anki cell: one section per [AudioSource]
 * (Wikimedia Commons first, then Text-to-speech), each listing its candidates
 * with a "No results" cell when empty and a "Loading…"/"Couldn't load" cell for
 * the async/error states. Fully source-agnostic — renders from the
 * [AudioSource]/[AudioCandidate] interface only, so new sources need no UI work.
 *
 * Returns the picked (sourceId, key) via [setResult]. Tapping a candidate
 * auditions it; for Commons that also downloads + caches the clip so the send
 * path attaches it without a second fetch.
 */
class AudioSourcePickerActivity : AppCompatActivity() {

    private lateinit var sections: LinearLayout
    private lateinit var req: AudioRequest
    private var selectedSourceId: String? = null
    private var selectedKey: String? = null
    private var selectedLocator: String? = null
    private var selectedAttribution: Attribution? = null

    private val loaded = HashMap<String, List<AudioCandidate>>()
    private val rowHosts = HashMap<String, LinearLayout>()

    /** RECORD_AUDIO for the in-picker game-audio enable switch (see
     *  [renderGameAudioEnableRow]); mirrors the Anki-settings toggle. */
    private var gameAudioSwitch: MaterialSwitch? = null
    private val requestRecordAudio =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                CaptureService.setRecordGameAudio(this, true)
            } else {
                gameAudioSwitch?.isChecked = false
                Toast.makeText(this, R.string.anki_game_audio_permission_denied, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme(this)
        applyEdgeToEdge(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio_source_picker)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val sys = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            WindowInsetsCompat.CONSUMED
        }

        val lang = SourceLangId.fromCode(intent.getStringExtra(EXTRA_LANG)) ?: SourceLangId.JA
        val surface = intent.getStringExtra(EXTRA_SURFACE).orEmpty()
        val reading = intent.getStringExtra(EXTRA_READING)
        req = if (intent.getBooleanExtra(EXTRA_IS_WORD, true)) {
            AudioRequest.word(surface, reading, lang)
        } else {
            AudioRequest.sentence(surface, lang)
        }
        selectedSourceId = intent.getStringExtra(EXTRA_INITIAL_SOURCE)
        selectedKey = intent.getStringExtra(EXTRA_INITIAL_KEY)
        selectedLocator = intent.getStringExtra(EXTRA_INITIAL_LOCATOR)
        selectedAttribution = intent.getStringExtra(EXTRA_INITIAL_ATTRIBUTION)
            ?.let { runCatching { PtJson.lenient.decodeFromString(Attribution.serializer(), it) }.getOrNull() }

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            title = getString(R.string.audio_source_picker_title)
            setNavigationOnClickListener { finish() }
        }
        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra(EXTRA_PICKED_SOURCE, selectedSourceId)
                    .putExtra(EXTRA_PICKED_KEY, selectedKey)
                    .putExtra(EXTRA_PICKED_LOCATOR, selectedLocator)
                    .putExtra(
                        EXTRA_PICKED_ATTRIBUTION,
                        selectedAttribution?.let {
                            PtJson.lenient.encodeToString(Attribution.serializer(), it)
                        },
                    ),
            )
            finish()
        }
        sections = findViewById(R.id.sourceSections)
        buildSections()
    }

    override fun onDestroy() {
        PronunciationPlayer.stop()
        super.onDestroy()
    }

    private fun buildSections() {
        val inflater = LayoutInflater.from(this)
        val strokePx = (1 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        // Only sources that serve this request's kind get a section — e.g. a
        // sentence request omits Commons (word-level only) rather than showing
        // it as a permanently-empty "No results" row and querying it for nothing.
        AudioSourceRegistry.all().filter { it.serves(req.kind) }.forEach { source ->
            // The global TTS speed rides directly above the Text-to-speech
            // section — the same section Settings' voice picker shows — so
            // the rate the saved card audio will be synthesized at is
            // adjustable from the Anki flow too.
            if (source.id == TtsAudioSource.ID) {
                val speed = inflater.inflate(R.layout.section_tts_speed, sections, false)
                TtsSpeedSection.bind(speed, Prefs(this)) { previewTtsSpeed() }
                sections.addView(speed)
            }
            val header = inflater.inflate(R.layout.settings_group_header, sections, false)
            header.findViewById<TextView>(R.id.tvGroupTitle).text = source.label(this).uppercase()
            sections.addView(header)

            val rows = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                setCardBackgroundColor(themeColor(R.attr.ptCard))
                radius = resources.getDimension(R.dimen.pt_radius)
                cardElevation = 0f
                strokeColor = themeColor(R.attr.ptDivider)
                strokeWidth = strokePx
                addView(rows)
            }
            sections.addView(card)
            rowHosts[source.id] = rows
            // Deliberate source-specific exception to this screen's otherwise
            // source-agnostic rendering: with recording opted out, the Game
            // audio section IS the opt-in (a switch, mirroring the Anki
            // settings row) rather than a permanently-empty candidate list.
            if (source.id == RecordingAudioSource.ID && !Prefs(this).recordGameAudio) {
                renderGameAudioEnableRow(rows)
            } else {
                renderStatus(rows, getString(R.string.audio_loading))
                loadSection(source)
            }
        }
    }

    /** The Game audio section body while the feature is off: an enable
     *  switch. Turning it on requests RECORD_AUDIO and flips the pref —
     *  recording starts with the next session/consent; the current card has
     *  no snapshot either way, so the row stays (checked) rather than
     *  re-rendering into a confusing empty candidate list. */
    private fun renderGameAudioEnableRow(host: LinearLayout) {
        host.removeAllViews()
        val row = LayoutInflater.from(this)
            .inflate(R.layout.settings_row_switch, host, false)
        row.findViewById<TextView>(R.id.tvRowTitle).text =
            getString(R.string.anki_game_audio_row_title)
        row.findViewById<TextView>(R.id.tvRowSubtitle).apply {
            text = getString(R.string.audio_source_game_enable_hint)
            isVisible = true
        }
        val switch = row.findViewById<MaterialSwitch>(R.id.switchRowToggle)
        gameAudioSwitch = switch
        switch.isChecked = false
        switch.setOnCheckedChangeListener { _, checked ->
            if (!checked) {
                CaptureService.setRecordGameAudio(this, false)
            } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                CaptureService.setRecordGameAudio(this, true)
            } else {
                requestRecordAudio.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        row.setOnClickListener { switch.toggle() }
        host.addView(row)
    }

    /** Audition a just-committed speed-slider value: speak this card's text
     *  at the new rate, with the picker's current TTS voice when one is
     *  selected, else the language's saved global voice — the voice the
     *  saved audio would actually use. */
    private fun previewTtsSpeed() {
        val voice = if (selectedSourceId == TtsAudioSource.ID) {
            selectedKey?.takeIf { it != TtsAudioSource.DEFAULT_KEY }
        } else {
            Prefs(this).ttsVoiceName(req.lang)
        }
        lifecycleScope.launch {
            TtsEngine.speak(
                this@AudioSourcePickerActivity,
                SpokenText.forRequest(this@AudioSourcePickerActivity, req),
                req.lang,
                voiceNameOverride = voice,
            )
        }
    }

    private fun loadSection(source: AudioSource) {
        lifecycleScope.launch {
            val host = rowHosts[source.id] ?: return@launch
            runCatching { source.candidates(this@AudioSourcePickerActivity, req) }
                .onSuccess { loaded[source.id] = it; renderRows(source, host) }
                .onFailure { renderStatus(host, getString(R.string.audio_error_loading)) }
        }
    }

    private fun renderRows(source: AudioSource, host: LinearLayout) {
        host.removeAllViews()
        val candidates = loaded[source.id].orEmpty()
        if (candidates.isEmpty()) {
            renderStatus(host, getString(R.string.audio_no_results))
            return
        }
        val inflater = LayoutInflater.from(this)
        candidates.forEachIndexed { i, c ->
            if (i > 0) host.addView(inflater.inflate(R.layout.settings_row_divider, host, false))
            val row = inflater.inflate(R.layout.tts_voice_row, host, false)
            row.findViewById<TextView>(R.id.tvRowTitle).text = c.title.resolve(this)
            row.findViewById<TextView>(R.id.tvRowSubtitle).apply {
                val sub = c.subtitle?.resolve(this@AudioSourcePickerActivity)
                text = sub.orEmpty()
                visibility = if (sub.isNullOrBlank()) View.GONE else View.VISIBLE
            }
            row.findViewById<ImageView>(R.id.ivCheck).visibility =
                if (selectedSourceId == source.id && selectedKey == c.key) View.VISIBLE else View.GONE
            row.setOnClickListener { select(source, c) }
            host.addView(row)
        }
    }

    private fun renderStatus(host: LinearLayout, text: String) {
        host.removeAllViews()
        val row = LayoutInflater.from(this).inflate(R.layout.tts_voice_row, host, false)
        row.findViewById<TextView>(R.id.tvRowTitle).text = text
        row.findViewById<TextView>(R.id.tvRowSubtitle).visibility = View.GONE
        row.findViewById<ImageView>(R.id.ivCheck).visibility = View.GONE
        host.addView(row)
    }

    private fun select(source: AudioSource, candidate: AudioCandidate) {
        selectedSourceId = source.id
        selectedKey = candidate.key
        selectedLocator = candidate.locator
        selectedAttribution = candidate.attribution
        // Re-render checks across loaded sections (from cache; no refetch).
        AudioSourceRegistry.all().forEach { s ->
            rowHosts[s.id]?.let { if (loaded.containsKey(s.id)) renderRows(s, it) }
        }
        // Audition the FULL candidate (it carries the URL + attribution), so for
        // Commons this downloads + caches the EXACT selected clip the send path
        // reuses — not a reconstructed, locator-less candidate.
        lifecycleScope.launch {
            source.play(this@AudioSourcePickerActivity, candidate, req, awaitCompletion = false, onStart = null)
        }
    }

    companion object {
        private const val EXTRA_LANG = "lang"
        private const val EXTRA_SURFACE = "surface"
        private const val EXTRA_READING = "reading"
        private const val EXTRA_IS_WORD = "is_word"
        private const val EXTRA_INITIAL_SOURCE = "initial_source"
        private const val EXTRA_INITIAL_KEY = "initial_key"
        private const val EXTRA_INITIAL_LOCATOR = "initial_locator"
        private const val EXTRA_INITIAL_ATTRIBUTION = "initial_attribution"
        const val EXTRA_PICKED_SOURCE = "picked_source"
        const val EXTRA_PICKED_KEY = "picked_key"
        const val EXTRA_PICKED_LOCATOR = "picked_locator"
        const val EXTRA_PICKED_ATTRIBUTION = "picked_attribution"

        fun intent(
            context: Context,
            lang: SourceLangId,
            surface: String,
            reading: String?,
            isWord: Boolean,
            current: AudioSelection,
        ): Intent {
            val explicit = current as? AudioSelection.Explicit
            return Intent(context, AudioSourcePickerActivity::class.java)
                .putExtra(EXTRA_LANG, lang.code)
                .putExtra(EXTRA_SURFACE, surface)
                .putExtra(EXTRA_READING, reading)
                .putExtra(EXTRA_IS_WORD, isWord)
                .putExtra(EXTRA_INITIAL_SOURCE, explicit?.sourceId)
                .putExtra(EXTRA_INITIAL_KEY, explicit?.key)
                .putExtra(EXTRA_INITIAL_LOCATOR, explicit?.locator)
                .putExtra(
                    EXTRA_INITIAL_ATTRIBUTION,
                    explicit?.attribution?.let {
                        PtJson.lenient.encodeToString(Attribution.serializer(), it)
                    },
                )
        }
    }
}
