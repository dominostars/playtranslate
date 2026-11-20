package com.playtranslate.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.playtranslate.CaptureService
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.audio.Attribution
import com.playtranslate.audio.AudioCandidate
import com.playtranslate.audio.AudioRequest
import com.playtranslate.audio.AudioSelection
import com.playtranslate.audio.AudioSelections
import com.playtranslate.audio.AudioSource
import com.playtranslate.audio.AudioSourceRegistry
import com.playtranslate.audio.PlayOutcome
import com.playtranslate.audio.PronunciationPlayer
import com.playtranslate.audio.sources.OnlineTtsAudioSource
import com.playtranslate.audio.sources.RecordingAudioSource
import com.playtranslate.audio.sources.TtsAudioSource
import com.playtranslate.language.ReadingOverride
import com.playtranslate.language.SourceLangId
import com.playtranslate.themeColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The multi-section audio picker's content, host-agnostic: one section per
 * [AudioSource] (Wikimedia Commons first, then Text-to-speech, then Game
 * audio), each listing its candidates with a "No results" cell when empty
 * and a "Loading…"/"Couldn't load" cell for the async/error states. Fully
 * source-agnostic — renders from the [AudioSource]/[AudioCandidate]
 * interface only, so new sources need no UI work. Tapping a candidate
 * auditions it; for Commons that also downloads + caches the clip so the
 * send path attaches it without a second fetch.
 *
 * Hosted by [AudioSourcePickerActivity] (the in-app / dual-screen flow) and
 * by the floating workspace's audio picker page (over the game). The one
 * verb the content can't perform itself is the RECORD_AUDIO runtime grant
 * behind the game-audio enable switch — [Host.requestRecordAudio] hands it
 * to whichever host can run it. The host owns the Save affordance and reads
 * [pickedSelection] when it fires.
 */
class AudioSourcePickerView(
    private val ctx: Context,
    private val scope: CoroutineScope,
    args: Args,
    private val host: Host,
) {
    interface Host {
        /** Run the RECORD_AUDIO runtime request — an Activity-only verb
         *  (the activity shell launches its permission contract; the
         *  workspace parks under a translucent trampoline). */
        fun requestRecordAudio(onResult: (granted: Boolean) -> Unit)
    }

    /** The launch payload, decoded from the activity transport by
     *  [AudioSourcePickerActivity.argsFrom] or built directly. */
    data class Args(
        val lang: SourceLangId,
        val surface: String,
        val reading: String?,
        val isWord: Boolean,
        val initialSourceId: String?,
        val initialKey: String?,
        val initialLocator: String?,
        val initialAttribution: Attribution?,
        /** A sentence card's reading of its own word ([ReadingOverride]),
         *  so the previews read the line as the card's audio will; null
         *  for a word. */
        val readingOverride: ReadingOverride? = null,
    )

    private val req: AudioRequest = if (args.isWord) {
        AudioRequest.word(args.surface, args.reading, args.lang)
    } else {
        AudioRequest.sentence(args.surface, args.lang, args.readingOverride)
    }

    private var selectedSourceId: String? = args.initialSourceId
    private var selectedKey: String? = args.initialKey
    private var selectedLocator: String? = args.initialLocator
    private var selectedAttribution: Attribution? = args.initialAttribution

    private val loaded = HashMap<String, List<AudioCandidate>>()
    private val rowHosts = HashMap<String, LinearLayout>()
    private var gameAudioSwitch: MaterialSwitch? = null

    /** The current pick: [AudioSelection.Explicit] when a candidate is
     *  selected, [AudioSelection.Auto] otherwise — exactly the mapping
     *  [SentenceAnkiContentView.parsePickerResult] applies to the activity
     *  transport's extras. */
    fun pickedSelection(): AudioSelection {
        val src = selectedSourceId
        val key = selectedKey
        return if (src != null && key != null) {
            AudioSelection.Explicit(src, key, selectedLocator, selectedAttribution)
        } else {
            AudioSelection.Auto
        }
    }

    /** Stop any in-flight audition. Idempotent; call on host teardown. */
    fun release() {
        auditionJob?.cancel()
        PronunciationPlayer.stop()
    }

    fun buildInto(sections: LinearLayout) {
        val inflater = LayoutInflater.from(ctx)
        val strokePx = (1 * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        // Only sources that serve this request get a section — e.g. a
        // sentence request omits Commons and the imported recordings (both
        // word-level only), rather than showing them as a permanently-empty
        // "No results" row and querying them for nothing. And only sources
        // that are set up: the voice service has no section until a service
        // is connected, the imported recordings none until a file is loaded.
        AudioSourceRegistry.all().filter { it.serves(req) && it.isSetUp(ctx) }.forEach { source ->
            val header = inflater.inflate(R.layout.settings_group_header, sections, false)
            val title = header.findViewById<TextView>(R.id.tvGroupTitle)
            title.text = source.label(ctx)
            scope.launch { source.sectionTitle(ctx)?.let { title.text = it } }
            sections.addView(header)
            // The global TTS speed lives inside the Text-to-speech section,
            // as its own cell above the voice list — the same cell Settings'
            // voice picker shows — so the rate the saved card audio will be
            // synthesized at is adjustable from the Anki flow too.
            if (source.id == TtsAudioSource.ID) {
                val speed = inflater.inflate(R.layout.section_tts_speed, sections, false)
                TtsSpeedSection.bind(speed, Prefs(ctx)) { previewTtsSpeed() }
                sections.addView(speed)
            }

            val rows = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            val card = MaterialCardView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                setCardBackgroundColor(ctx.themeColor(R.attr.ptCard))
                radius = ctx.resources.getDimension(R.dimen.pt_radius)
                cardElevation = 0f
                strokeColor = ctx.themeColor(R.attr.ptDivider)
                strokeWidth = strokePx
                addView(rows)
            }
            sections.addView(card)
            rowHosts[source.id] = rows
            // Deliberate source-specific exception to this screen's otherwise
            // source-agnostic rendering: with recording opted out, the Game
            // audio section IS the opt-in (a switch, mirroring the Anki
            // settings row) rather than a permanently-empty candidate list.
            if (source.id == RecordingAudioSource.ID && !Prefs(ctx).recordGameAudio) {
                renderGameAudioEnableRow(rows)
            } else {
                renderStatus(rows, ctx.getString(R.string.audio_loading))
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
        val row = LayoutInflater.from(ctx)
            .inflate(R.layout.settings_row_switch, host, false)
        row.findViewById<TextView>(R.id.tvRowTitle).text =
            ctx.getString(R.string.anki_game_audio_row_title)
        row.findViewById<TextView>(R.id.tvRowSubtitle).apply {
            text = ctx.getString(R.string.audio_source_game_enable_hint)
            isVisible = true
        }
        val switch = row.findViewById<MaterialSwitch>(R.id.switchRowToggle)
        gameAudioSwitch = switch
        switch.isChecked = false
        switch.setOnCheckedChangeListener { _, checked ->
            if (!checked) {
                CaptureService.setRecordGameAudio(ctx, false)
            } else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                CaptureService.setRecordGameAudio(ctx, true)
            } else {
                // Already-granted handled above, so the host round trip
                // (permission contract / parked trampoline) only runs when
                // a system dialog is genuinely coming.
                this.host.requestRecordAudio { granted ->
                    if (granted) {
                        CaptureService.setRecordGameAudio(ctx, true)
                    } else {
                        gameAudioSwitch?.isChecked = false
                        Toast.makeText(
                            ctx, R.string.anki_game_audio_permission_denied, Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
        row.setOnClickListener { switch.toggle() }
        host.addView(row)
    }

    /** Audition a just-committed speed-slider value: speak this card's text
     *  at the new rate, in the voice the saved audio would actually be
     *  spoken by. That is the picked voice when the pick is one (a device
     *  voice or a voice service's), else the language's own voice, which
     *  the slider governs whatever recording is picked for this card. */
    private fun previewTtsSpeed() {
        val picked = pickedSelection()
        audition {
            val pickedVoice = (picked as? AudioSelection.Explicit)
                ?.takeIf { it.sourceId == TtsAudioSource.ID || it.sourceId == OnlineTtsAudioSource.ID }
            if (pickedVoice != null) {
                AudioSourceRegistry.sourceFor(pickedVoice.sourceId)
                    .play(ctx, AudioSelections.explicitCandidate(pickedVoice), req, awaitCompletion = false, onStart = null)
            } else {
                val service = OnlineTtsAudioSource.takeIf { it.isEnabled(ctx) }?.defaultCandidate(ctx, req)
                if (service != null) {
                    OnlineTtsAudioSource.play(ctx, service, req, awaitCompletion = false, onStart = null)
                } else {
                    TtsAudioSource.play(
                        ctx, TtsAudioSource.defaultCandidate(ctx, req), req, awaitCompletion = false, onStart = null,
                    )
                }
            }
        }
    }

    /** The one audition in flight: a row tap's or the speed slider's. A new
     *  one ends it and whatever it was playing, so a slow sample (a voice
     *  service's, a download) cannot land over a later pick. A failed one
     *  says so: a row that plays nothing with no word of why reads as a
     *  broken picker. */
    private var auditionJob: Job? = null

    private fun audition(play: suspend () -> PlayOutcome) {
        auditionJob?.cancel()
        PronunciationPlayer.stop()
        auditionJob = scope.launch {
            val outcome = try {
                play()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                PlayOutcome.Failed(recoverable = true)
            }
            if (outcome is PlayOutcome.Failed) {
                Toast.makeText(ctx, R.string.audio_error_playing, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadSection(source: AudioSource) {
        scope.launch {
            val host = rowHosts[source.id] ?: return@launch
            runCatching { source.candidates(ctx, req) }
                .onSuccess { loaded[source.id] = it; renderRows(source, host) }
                .onFailure { renderStatus(host, ctx.getString(R.string.audio_error_loading)) }
        }
    }

    private fun renderRows(source: AudioSource, host: LinearLayout) {
        host.removeAllViews()
        val candidates = loaded[source.id].orEmpty()
        if (candidates.isEmpty()) {
            renderStatus(host, ctx.getString(R.string.audio_no_results))
            return
        }
        val inflater = LayoutInflater.from(ctx)
        candidates.forEachIndexed { i, c ->
            if (i > 0) host.addView(inflater.inflate(R.layout.settings_row_divider, host, false))
            val row = inflater.inflate(R.layout.settings_row_choice, host, false)
            row.findViewById<TextView>(R.id.tvRowTitle).text = c.title.resolve(ctx)
            row.findViewById<TextView>(R.id.tvRowSubtitle).apply {
                val sub = c.subtitle?.resolve(ctx)
                text = sub.orEmpty()
                visibility = if (sub.isNullOrBlank()) View.GONE else View.VISIBLE
            }
            // Second deliberate source-specific exception (see the Game
            // audio enable row): game audio's selection key is state-bearing
            // (trim range + snapshot mtime) while its one candidate carries
            // the provisional "snapshot" key, so key equality can't
            // establish identity for it. Single-candidate source: source
            // match alone is candidate match.
            val isSelected = selectedSourceId == source.id &&
                (selectedKey == c.key || source.id == RecordingAudioSource.ID)
            row.findViewById<ImageView>(R.id.ivCheck).apply {
                // The layout's app:tint is AppCompat-only — tint in code so
                // the workspace's plain inflater shows the same accent check.
                imageTintList = ColorStateList.valueOf(ctx.themeColor(R.attr.ptAccent))
                visibility = if (isSelected) View.VISIBLE else View.GONE
            }
            row.setOnClickListener { select(source, c) }
            host.addView(row)
        }
    }

    private fun renderStatus(host: LinearLayout, text: String) {
        host.removeAllViews()
        val row = LayoutInflater.from(ctx).inflate(R.layout.settings_row_choice, host, false)
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
        audition { source.play(ctx, candidate, req, awaitCompletion = false, onStart = null) }
    }
}
