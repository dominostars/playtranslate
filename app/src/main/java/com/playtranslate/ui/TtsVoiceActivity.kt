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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.applyEdgeToEdge
import com.playtranslate.applyTheme
import com.playtranslate.audio.AudioRequest
import com.playtranslate.audio.PlayOutcome
import com.playtranslate.audio.PronunciationPlayer
import com.playtranslate.audio.RecordingPlayer
import com.playtranslate.audio.online.OnlineTtsChoice
import com.playtranslate.audio.online.OnlineTtsException
import com.playtranslate.audio.online.OnlineTtsService
import com.playtranslate.audio.sources.OnlineTtsAudioSource
import com.playtranslate.language.SourceLangId
import com.playtranslate.tts.TtsEngine
import com.playtranslate.tts.TtsVoiceLabels
import com.playtranslate.tts.VoiceChoice
import com.playtranslate.tts.voiceSampleText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The voice page: the one choice of the voice that speaks a game language
 * ([VoiceChoice]), made from two lists with one checkmark across them. The
 * connected voice service's voices come first, or the row that connects a
 * service; the device engine's voices second, or the row that says the
 * device has no engine.
 *
 * Tapping a voice selects it and speaks a short sample in it. Save writes the
 * choice for [lang] ([Prefs.setVoiceChoice]) and closes; Back and the toolbar
 * X leave the stored choice alone. The page reads the stored choice and
 * writes its own Save, so nothing depends on a result reaching whoever
 * opened it: the user leaves this page for the service's settings and, from
 * there, often for a browser and an API key, which is when a backgrounded
 * app is killed.
 *
 * The speed section at the top is the one thing that persists without Save:
 * the global speech rate has no per-page variant, so releasing the slider
 * stores it (see [TtsSpeedSection]).
 */
class TtsVoiceActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var lang: SourceLangId
    private lateinit var serviceRows: LinearLayout
    private lateinit var voiceRows: LinearLayout

    /** What the voice service's group has to show. */
    private sealed interface ServiceState {
        object NotConnected : ServiceState
        object Loading : ServiceState
        class Loaded(val voices: List<OnlineTtsChoice>) : ServiceState
        class Failed(val failure: OnlineTtsException) : ServiceState
    }

    private var deviceVoices: List<Voice> = emptyList()

    /** False once the engine check has answered that there is none. */
    private var engineAvailable = true
    private var serviceState: ServiceState = ServiceState.NotConnected

    /** The voice currently ticked. Stored only by Save. */
    private var selected: VoiceChoice = VoiceChoice.Device(null)

    /** The one sample in flight: a row tap's or the speed slider's. A new
     *  one cancels it, so a slow service sample never lands over a later
     *  pick. */
    private var sampleJob: Job? = null
    private var serviceJob: Job? = null

    /** Set when the service's settings page was opened from here, with the
     *  service it was opened on, so the return can tell whether the ticked
     *  service voice still belongs to the connected service. */
    private var awaitingServicePage = false
    private var serviceIdentityAtLaunch: String? = null

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

        prefs = OnlineTtsService.prefs(this)
        lang = intent.getStringExtra(EXTRA_LANG)
            ?.let { SourceLangId.fromCode(it) }
            ?: prefs.sourceLangId
        selected = savedInstanceState?.let(::restoredChoice) ?: prefs.voiceChoice(lang)
        awaitingServicePage = savedInstanceState?.getBoolean(STATE_AWAITING_SERVICE) ?: false
        serviceIdentityAtLaunch = savedInstanceState?.getString(STATE_SERVICE_IDENTITY)
        serviceRows = findViewById(R.id.serviceRows)
        voiceRows = findViewById(R.id.voiceRows)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.tts_voice_picker_title)
        toolbar.setNavigationOnClickListener { finish() }

        findViewById<View>(R.id.headerService)
            .findViewById<TextView>(R.id.tvGroupTitle).text =
            getString(R.string.audio_source_voice_service_name)
        findViewById<View>(R.id.headerVoices)
            .findViewById<TextView>(R.id.tvGroupTitle).text =
            getString(R.string.tts_voices_section_header, lang.displayName())

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            prefs.setVoiceChoice(lang, selected)
            setResult(RESULT_OK)
            finish()
        }

        // Audition the committed speed in the voice currently ticked (the
        // transient selection, not the stored one).
        TtsSpeedSection.bind(findViewById(R.id.speedSection), prefs) { sample(selected) }

        loadDeviceVoices()
        loadService()
    }

    override fun onResume() {
        super.onResume()
        if (!awaitingServicePage) return
        awaitingServicePage = false
        // Back from the service's settings. A ticked service voice belongs
        // to the service it was listed by: if that service was removed or
        // became another, the tick goes back to what is stored (which the
        // service page has already cleared), so Save cannot write a voice
        // of a service that is gone. The same for a voice the service's
        // model, as now saved, is known to refuse: the service page has
        // sent the stored one back to the device's voice, and this page's
        // Save must not write it in again.
        val connected = OnlineTtsService.config(prefs)
        val ticked = (selected as? VoiceChoice.Online)?.voice
        if (ticked != null &&
            (connected?.serviceIdentity != serviceIdentityAtLaunch || connected?.doesNotTake(ticked.id) == true)
        ) {
            selected = prefs.voiceChoice(lang)
            renderDeviceRows()
        }
        loadService()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        when (val choice = selected) {
            is VoiceChoice.Device -> outState.putString(STATE_DEVICE_VOICE, choice.name)
            is VoiceChoice.Online -> {
                outState.putString(STATE_ONLINE_ID, choice.voice.id)
                outState.putString(STATE_ONLINE_NAME, choice.voice.name)
            }
        }
        outState.putBoolean(STATE_AWAITING_SERVICE, awaitingServicePage)
        outState.putString(STATE_SERVICE_IDENTITY, serviceIdentityAtLaunch)
    }

    /** The tick a recreated page had. A service voice is taken back only
     *  while a service is still connected. */
    private fun restoredChoice(state: Bundle): VoiceChoice {
        val onlineId = state.getString(STATE_ONLINE_ID)
        return if (onlineId != null) {
            if (OnlineTtsService.config(prefs) == null) prefs.voiceChoice(lang)
            else VoiceChoice.Online(OnlineTtsChoice(onlineId, state.getString(STATE_ONLINE_NAME) ?: onlineId))
        } else {
            VoiceChoice.Device(state.getString(STATE_DEVICE_VOICE))
        }
    }

    /**
     * A sample lives while its page is in front. Leaving, for the service's
     * settings or out of the app, ends the one being fetched and the one
     * playing: a slow answer would otherwise play over the page the user
     * went to, and a failure's alert, which shows over whatever page is in
     * front, would land there. On pause, which is when this app's alerts
     * leave too, and is before the next page is shown; a page is paused
     * before it is destroyed, so this is the one place.
     *
     * Both players: a service voice's sample plays through the recording
     * player, whose playback outlives this page's scope.
     */
    override fun onPause() {
        sampleJob?.cancel()
        PronunciationPlayer.stop()
        super.onPause()
    }

    // ── Loading ──────────────────────────────────────────────────────────

    private fun loadDeviceVoices() {
        lifecycleScope.launch {
            engineAvailable = TtsEngine.isEngineAvailable(this@TtsVoiceActivity)
            deviceVoices = TtsEngine.voicesFor(this@TtsVoiceActivity, lang)
            renderDeviceRows()
        }
    }

    private fun loadService() {
        serviceJob?.cancel()
        if (OnlineTtsService.config(prefs) == null) {
            serviceState = ServiceState.NotConnected
            renderServiceRows()
            return
        }
        serviceState = ServiceState.Loading
        renderServiceRows()
        serviceJob = lifecycleScope.launch {
            serviceState = try {
                ServiceState.Loaded(OnlineTtsService.voices(this@TtsVoiceActivity))
            } catch (e: CancellationException) {
                throw e
            } catch (e: OnlineTtsException) {
                ServiceState.Failed(e)
            }
            renderServiceRows()
        }
    }

    // ── The two groups ───────────────────────────────────────────────────

    private fun renderServiceRows() {
        serviceRows.removeAllViews()
        val state = serviceState
        if (state is ServiceState.NotConnected) {
            addNavRow(
                serviceRows,
                getString(R.string.voice_service_connect_title),
                getString(R.string.voice_service_connect_subtitle),
            ) { openServicePage() }
            return
        }
        // The ticked service voice is always drawn, from the name it was
        // picked under: while the list loads, when it fails, and when the
        // list no longer holds it. A tick nobody can see is still what Save
        // stores.
        val ticked = (selected as? VoiceChoice.Online)?.voice
        val listed = (state as? ServiceState.Loaded)?.voices.orEmpty()
        val voices = if (ticked != null && listed.none { it.id == ticked.id }) listOf(ticked) + listed else listed
        voices.forEach { voice ->
            addChoiceRow(serviceRows, voice.name, null, selected = ticked?.id == voice.id) {
                select(VoiceChoice.Online(voice))
            }
        }
        when (state) {
            ServiceState.Loading -> addStatusRow(serviceRows, getString(R.string.audio_loading), null, onClick = null)
            // The failure in full, the service's own words included: they
            // are the only evidence of what the list call got back.
            is ServiceState.Failed -> addStatusRow(
                serviceRows,
                getString(R.string.audio_error_loading),
                voiceServiceFailureMessage(this, state.failure, ofList = true),
                onClick = { loadService() },
            )
            is ServiceState.Loaded ->
                if (voices.isEmpty()) {
                    addStatusRow(serviceRows, getString(R.string.voice_service_no_voices), null, onClick = null)
                }
            ServiceState.NotConnected -> Unit
        }
        // Where the requests go and which model speaks: the model may have
        // been picked for the user at Save, and this is where they see it.
        val connected = OnlineTtsService.config(prefs)
        addNavRow(
            serviceRows,
            getString(R.string.voice_service_settings_row),
            listOfNotNull(connected?.host, connected?.model?.name).joinToString(" · ").ifBlank { null },
        ) { openServicePage() }
    }

    private fun renderDeviceRows() {
        voiceRows.removeAllViews()
        if (!engineAvailable) {
            addNavRow(
                voiceRows,
                getString(R.string.tts_no_engine_row_title),
                getString(R.string.tts_no_engine_row_subtitle),
            ) { showTtsNoEngineDialog(TtsAlertTarget.InActivity(this)) { } }
            return
        }
        val tickedDevice = selected as? VoiceChoice.Device
        addChoiceRow(
            voiceRows,
            getString(R.string.tts_voice_default),
            getString(R.string.tts_voice_default_subtitle),
            selected = tickedDevice != null && tickedDevice.name == null,
        ) { select(VoiceChoice.Device(null)) }
        deviceVoices.forEachIndexed { index, voice ->
            val label = TtsVoiceLabels.forVoice(this, deviceVoices, index)
            addChoiceRow(
                voiceRows,
                label.title,
                label.subtitle,
                selected = tickedDevice?.name == voice.name,
            ) { select(VoiceChoice.Device(voice.name)) }
        }
    }

    private fun addDividerIfNeeded(host: LinearLayout) {
        if (host.childCount > 0) {
            host.addView(LayoutInflater.from(this).inflate(R.layout.settings_row_divider, host, false))
        }
    }

    private fun addChoiceRow(
        host: LinearLayout,
        title: String,
        subtitle: String?,
        selected: Boolean,
        onClick: () -> Unit,
    ) {
        addDividerIfNeeded(host)
        val row = LayoutInflater.from(this).inflate(R.layout.settings_row_choice, host, false)
        row.findViewById<TextView>(R.id.tvRowTitle).text = title
        row.findViewById<TextView>(R.id.tvRowSubtitle).apply {
            text = subtitle.orEmpty()
            visibility = if (subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        row.findViewById<ImageView>(R.id.ivCheck).visibility =
            if (selected) View.VISIBLE else View.GONE
        row.setOnClickListener { onClick() }
        host.addView(row)
    }

    /** A row that says something and ticks nothing: loading, an empty
     *  list, a failure. With [onClick] it is tappable (a failed list is
     *  asked for again). */
    private fun addStatusRow(host: LinearLayout, title: String, subtitle: String?, onClick: (() -> Unit)?) {
        addDividerIfNeeded(host)
        val row = LayoutInflater.from(this).inflate(R.layout.settings_row_choice, host, false)
        row.findViewById<TextView>(R.id.tvRowTitle).text = title
        row.findViewById<TextView>(R.id.tvRowSubtitle).apply {
            text = subtitle.orEmpty()
            visibility = if (subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        row.findViewById<ImageView>(R.id.ivCheck).visibility = View.GONE
        if (onClick != null) row.setOnClickListener { onClick() } else row.isClickable = false
        host.addView(row)
    }

    private fun addNavRow(host: LinearLayout, title: String, subtitle: String?, onClick: () -> Unit) {
        addDividerIfNeeded(host)
        val row = LayoutInflater.from(this).inflate(R.layout.settings_row_nav, host, false)
        row.findViewById<TextView>(R.id.tvRowTitle).text = title
        row.findViewById<TextView>(R.id.tvRowSubtitle).apply {
            text = subtitle.orEmpty()
            visibility = if (subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        row.setOnClickListener { onClick() }
        host.addView(row)
    }

    // ── Choosing and hearing ─────────────────────────────────────────────

    /** Tick [choice] across both lists and speak the sample in it. */
    private fun select(choice: VoiceChoice) {
        selected = choice
        renderServiceRows()
        renderDeviceRows()
        sample(choice)
    }

    /**
     * Speak the sample (the language's own name) in [choice]. A service
     * voice is asked of the service here, on the one path to a clip, so a
     * failure is shown with the service's own words: hearing a voice is
     * this feature's test of the service's key, model and request.
     *
     * A sample the service did send and that then would not play is said
     * too. There are no words of the service's for it, and silence would
     * pass for a voice that works: the tick is already on its row, and Save
     * would store it. A sample a newer tap replaced says nothing: its job
     * was cancelled before the newer one began.
     *
     * So is every other failure between the service's answer and the
     * speaker: a clip the cache could not write (no room left), a player
     * that could not be set up for it. The speaker buttons and the Anki
     * picker take any failure of a source as a play that failed, and a
     * sample does the same. Not caught here it would end the app, since
     * nothing stands above this job.
     */
    private fun sample(choice: VoiceChoice) {
        sampleJob?.cancel()
        PronunciationPlayer.stop()
        sampleJob = lifecycleScope.launch {
            when (choice) {
                is VoiceChoice.Device -> {
                    val voice = choice.name?.let { n -> deviceVoices.firstOrNull { it.name == n } }
                    TtsEngine.previewVoice(this@TtsVoiceActivity, voice, lang)
                }
                is VoiceChoice.Online -> try {
                    val clip = OnlineTtsAudioSource.clipFor(
                        this@TtsVoiceActivity,
                        choice.voice.id,
                        sampleRequest(),
                    )
                    val outcome = RecordingPlayer.playCached(
                        this@TtsVoiceActivity, clip, awaitCompletion = false, onStart = null,
                    )
                    if (outcome is PlayOutcome.Failed) sayNotPlayed()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: OnlineTtsException) {
                    showVoiceServiceFailure(
                        this@TtsVoiceActivity,
                        e,
                        asked = SpeechAsked(choice.voice.name, OnlineTtsService.config(prefs)?.model?.name),
                    )
                } catch (_: Exception) {
                    sayNotPlayed()
                }
            }
        }
    }

    private fun sayNotPlayed() =
        Toast.makeText(this, R.string.audio_error_playing, Toast.LENGTH_SHORT).show()

    /**
     * The sample as a request: the language's sample sentence
     * ([voiceSampleText]), sent as it is written. A word request, not a
     * sentence one, on purpose: a sentence request is first rewritten into
     * its reading by the language's engine, and the sample is already
     * written to be read as it stands.
     */
    private fun sampleRequest(): AudioRequest = AudioRequest.word(voiceSampleText(lang), null, lang)

    private fun openServicePage() {
        awaitingServicePage = true
        serviceIdentityAtLaunch = OnlineTtsService.config(prefs)?.serviceIdentity
        startActivity(OnlineTtsServiceActivity.intent(this))
    }

    companion object {
        private const val EXTRA_LANG = "lang"
        private const val STATE_DEVICE_VOICE = "selected_device_voice"
        private const val STATE_ONLINE_ID = "selected_online_id"
        private const val STATE_ONLINE_NAME = "selected_online_name"
        private const val STATE_AWAITING_SERVICE = "awaiting_service_page"
        private const val STATE_SERVICE_IDENTITY = "service_identity_at_launch"

        /** Intent that opens the page for [lang]. */
        fun intent(context: Context, lang: SourceLangId): Intent =
            Intent(context, TtsVoiceActivity::class.java).putExtra(EXTRA_LANG, lang.code)
    }
}
