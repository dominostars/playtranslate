package com.playtranslate.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.doOnLayout
import androidx.core.widget.NestedScrollView
import com.playtranslate.CaptureService
import com.playtranslate.Prefs
import com.playtranslate.PtJson
import com.playtranslate.R
import com.playtranslate.audio.Attribution
import com.playtranslate.audio.AudioRequest
import com.playtranslate.audio.AudioSelection
import com.playtranslate.audio.GameAudioClip
import com.playtranslate.audio.PlayOutcome
import com.playtranslate.audio.sources.RecordingAudioSource
import com.playtranslate.audio.vad.SpeechSnap
import com.playtranslate.audio.vad.VoiceLineSnap
import com.playtranslate.capture.GameAudioSnapshot
import com.playtranslate.dictionary.Deinflector
import com.playtranslate.language.SourceLangId
import com.playtranslate.themeColor
import com.playtranslate.tts.ttsTextForWord
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

private const val TAG = "SentenceAnkiContent"

/**
 * Sentence-card content for Anki review (Original, Translation, Words,
 * Screenshot) as a host-agnostic view class — extracted from
 * its former fragment host so the SAME card editor renders inside the
 * fragment-hosted review sheets AND the floating workspace's editor page.
 * Each section renders as a grouped [PtGroupCard] with the design-system
 * header on top, matching the Settings / Word Detail rhythm.
 *
 * Words always ship with the card unless the user removes them via the
 * row's `×` glyph. Tapping the row toggles **target** state — target
 * words are highlighted on the rendered card front (the HTML builder
 * reads [selectedWords]).
 *
 * [args] is the launch-state Bundle (built by [buildArgs]) and is the
 * MUTABLE state carrier the fragment flow has always used —
 * [applyTranslation]/[applyWords] write back into it so a re-created
 * fragment rebuilds from current state. The fragment shell passes its own
 * `arguments` so those semantics are unchanged; the workspace page passes
 * a fresh bundle it never re-reads.
 *
 * Lifetime: [buildInto] once, [release] exactly once when the host view
 * tree goes away — with the host's own teardown-finality decision for the
 * game-audio snapshot file. [scope] must be cancelled by the host.
 */
class SentenceAnkiContentView(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val args: Bundle,
    private val host: Host,
) {
    /** The host seam. */
    interface Host {
        /** The host view tree is still live (fragment isAdded / page attached). */
        val isAlive: Boolean

        /** Present the audio-source picker for [intent] and deliver the
         *  picked selection (parse activity extras via [parsePickerResult]).
         *  Never called back on cancel. */
        fun openAudioPicker(intent: Intent, onPicked: (AudioSelection) -> Unit)

        /** The screenshot was removed on this card — mirror into a sibling
         *  tab when one shares the media (the word/sentence tab pair). */
        fun onScreenshotRemoved() {}
    }

    private val words = mutableListOf<SentenceAnkiHtmlBuilder.WordEntry>()
    val selectedWords = mutableSetOf<String>()

    /** Styled payload for the word rows (structured glossaries + dict
     *  CSS), fetched once after the words list is built; null = every row
     *  keeps its one-line flat meaning. */
    private var sheetStyled: YomitanStyledData? = null

    /** One styled renderer per structured word, cached across
     *  [rebuildWordRows] passes (target toggles rebuild the whole list —
     *  WebViews must not churn per tap). Destroyed on removal (✕) and in
     *  [release]. */
    private val wordStyledViews = mutableMapOf<String, YomitanDefinitionsView>()

    /** Render process died once — every row stays native for the sheet's
     *  life. */
    private var wordStyledBroken = false

    /** Bumps on every styled-payload refresh so a slow fetch for a
     *  superseded words list can't install a stale payload. */
    private var styledFetchGen = 0

    /**
     * (Re)fetches the styled payload for the CURRENT words list. Called on
     * first build and again from [applyWords], because the deferred flows
     * replace the whole list after the sheet opens. Assigns
     * unconditionally — a new word set with nothing structured must CLEAR
     * the previous sentence's payload, not inherit it — and reaps cached
     * renderers for words no longer present.
     */
    private fun refreshStyledPayload() {
        if (!host.isAlive) return
        val gen = ++styledFetchGen
        val appCtx = ctx.applicationContext
        val styledLang = (SourceLangId.fromCode(args.getString(ARG_SOURCE_LANG))
            ?: SourceLangId.JA).yomitanConsumingLang()
        scope.launch {
            val payload = fetchStyledForSenses(appCtx, styledLang, words.flatMap { it.senses })
            if (!host.isAlive || gen != styledFetchGen) return@launch
            val hadOrHas = sheetStyled != null || payload != null
            sheetStyled = payload
            val current = words.mapTo(mutableSetOf()) { it.word }
            wordStyledViews.keys.filter { it !in current }.toList().forEach { w ->
                wordStyledViews.remove(w)?.destroy()
            }
            if (hadOrHas) rebuildWordRows()
        }
    }
    var includePhoto = true
        private set

    /** Whether the sentence-audio switch is on. Read by the host sheet
     *  at send time; false when the toggle wasn't built. */
    val sentenceAudioEnabled: Boolean
        get() = sentenceAudioHandle?.switch?.isChecked == true

    private lateinit var root: LinearLayout
    private lateinit var etOriginal: EditText
    private lateinit var etTranslation: EditText
    private lateinit var wordsCard: LinearLayout
    private lateinit var wordsHeaderTitle: TextView
    private var screenshotHeader: View? = null
    private var screenshotGroup: View? = null
    private var ivPhoto: ImageView? = null
    private var sentenceAudioHandle: AnkiAudioToggleHandle? = null

    // ── In-card game-audio panel (waveform; playback via the row chip) ───
    private var gameAudioPanel: View? = null
    private var gameAudioWave: WaveformTrimView? = null
    private var gameAudioSampleRate = 44_100
    private var gameAudioDurationMs = 0L

    /** THIS card's snapshot — a unique immutable file this card owns and
     *  deletes on provably-final teardown (see [release]'s
     *  deleteSnapshotFile contract). Other cards snapshot to their own
     *  files, so nothing external can invalidate this one (the churn-bug
     *  class fix). */
    private var gameAudioSnapshotFile: File? = null

    /** Where the launch anchor ([ARG_AUDIO_ANCHOR_MS] — the sentence's
     *  capture/display wall time) landed inside this card's snapshot, mapped
     *  by the recorder's ring clock. Seeds the default trim range at the
     *  sentence's own moment instead of the buffer tail. Null when no anchor
     *  was supplied or it missed the ring. Deliberately not saved: restores
     *  carry a committed range, which always takes precedence. */
    private var gameAudioAnchorOffsetMs: Long? = null

    /** The range [commitDefaultGameRange] seeded, so the VAD snap can tell
     *  "still the untouched default" from "the user (or a restore) moved it"
     *  — the snap only ever replaces the former. */
    private var gameAudioSeededRange: Pair<Long, Long>? = null

    /** Detected voice-line regions (snapshot ms), accumulated across the
     *  fast window pass and the background remainder blocks via
     *  [SpeechSnap.merge] — painted on the waveform in the warning color.
     *  Kept here because VAD emissions and the waveform decode land in any
     *  order. */
    private var gameAudioSpeechRegions: List<SpeechSnap.Segment> = emptyList()

    /** The snapshot file the panel last loaded — reload guard. */
    private var gameAudioLoadedFile: File? = null
    private var inlinePlayer: PcmAudioTrackPlayer? = null
    private var inlinePlaying = false
    /** The chip's suspended await while inline playback runs — resumed on
     *  natural completion AND by [stopInlinePlayback] (a handle drag), so
     *  the chip always returns to idle. */
    private var inlineCont: CancellableContinuation<Unit>? = null

    /** True once the user has interacted with the game-audio selection —
     *  dragged a handle or played it (listening counts as review). Reviewed
     *  audio sends directly; never-touched audio gets the save-time nudge
     *  (scroll the trim cell into view + flash it) once before it's allowed
     *  through. */
    private var gameAudioReviewed = false

    /** Independent per-target-word audio toggle state for THIS card.
     *  Seeded from [Prefs.ankiWordAudioEnabled] when a word is first
     *  added to [selectedWords]. Mutated by the word's sub-row toggle;
     *  pushed back to the pref on every change so the next card defaults
     *  to whatever the user picked last. */
    private val wordAudioEnabled = mutableMapOf<String, Boolean>()

    /** Per-word handle map — lets us release preview chips cleanly before
     *  each [rebuildWordRows] (otherwise an in-flight preview on a
     *  sub-row that's about to be removed keeps playing for a beat). */
    private val wordAudioHandles = mutableMapOf<String, AnkiAudioToggleHandle>()

    /** Audio source/voice for the sentence audio cell. [AudioSelection.Auto]
     *  (default) resolves Commons-first → TTS at preview/send time; an
     *  [AudioSelection.Explicit] is a specific pick from the audio picker. */
    private var sentenceSelection: AudioSelection = AudioSelection.Auto

    /** Same model, per target word. Entry is missing until the word first
     *  appears as a target; rebuildWordRows seeds it to [AudioSelection.Auto]. */
    private val wordSelections = mutableMapOf<String, AudioSelection>()

    /** Identifies the cell an audio-picker launch was for. */
    private sealed interface PickTarget {
        data object Sentence : PickTarget
        data class Word(val word: String) : PickTarget
    }

    private fun handleAudioPick(target: PickTarget, selection: AudioSelection) {
        val lang = SourceLangId.fromCode(args.getString(ARG_SOURCE_LANG))
            ?: SourceLangId.JA
        when (target) {
            is PickTarget.Sentence -> {
                val isGamePick = selection is AudioSelection.Explicit &&
                    selection.sourceId == RecordingAudioSource.ID
                if (isGamePick) {
                    // Commit a range BEFORE the pick goes live, so a Save right
                    // after the picker can't catch it provisional — no
                    // dependence on when the waveform decode lands. A re-pick
                    // keeps the on-screen trim; a first pick commits the default
                    // from a cheap header read. (Same invariant as card-open.)
                    val wav = gameAudioSnapshotFile
                    if (wav != null && GameAudioSnapshot.isUsable(wav)) {
                        scope.launch {
                            if (selectGameAudioCommitted(wav)) {
                                sentenceAudioHandle?.refreshPillLabel(scope, lang, sentenceSelection)
                                refreshSentenceAudioTitle()
                                updateGameAudioPanel()
                            } else {
                                Log.w(TAG, "game-audio pick dropped: clip too short to commit")
                            }
                        }
                    } else {
                        Log.w(TAG, "game-audio pick ignored: snapshot " +
                            (if (wav == null) "not ready" else "unusable"))
                    }
                    // No usable snapshot ⇒ ignore the pick (Game audio shouldn't
                    // be offered without one); never leave a rangeless selection.
                } else {
                    sentenceSelection = selection
                    sentenceAudioHandle?.refreshPillLabel(scope, lang, selection)
                    refreshSentenceAudioTitle()
                    // A non-game pick hides the panel.
                    updateGameAudioPanel()
                }
            }
            is PickTarget.Word -> {
                wordSelections[target.word] = selection
                wordAudioHandles[target.word]?.refreshPillLabel(scope, lang, selection)
            }
        }
    }

    /** True once we've nudged the user toward never-touched game audio at
     *  save time — scroll the trim cell into view + flash it. One-shot: a
     *  second Save sends the clip as-is, so the recording never hard-blocks
     *  the card. A new recording re-creates the card, resetting this. */
    private var gameAudioNudged = false

    /** The save-time attention flash on the game-audio cell. Held so
     *  [release] can cancel it before the view is torn down. */
    private var gameAudioFlashAnimator: ValueAnimator? = null

    /**
     * Save-time gate for game audio the user never reviewed (no handle drag,
     * no play). The live selection normally carries a committed default range
     * by the time Save can see it (see [commitDefaultGameRange]); a stray
     * rangeless pick degrades to the TTS floor here rather than dropping, so
     * the send always carries audio and this gate only decides whether to
     * *nudge*. The degrade logs loudly — no generator for an invalid key is
     * known (every commit site produces end>start over this card's own
     * immutable snapshot; WaveformTrimView enforces a 200 ms minimum gap), so
     * if the log ever fires it is news. Reviewed audio sends straight through. The first Save on
     * never-touched audio doesn't open the old full-screen editor: it reveals
     * the trim cell (even mid-decode — the cell is fixed-height), scrolls it to
     * mid-screen, flashes it, and holds that one Save. A second Save proceeds
     * with the default range. The nudge does NOT depend on the panel already
     * being on-screen, so a Save during the waveform decode still gets the
     * review prompt instead of silently shipping the default clip. Returns
     * whether the send may run.
     */
    fun resolveGameAudioForSend(): Boolean {
        if (!sentenceAudioEnabled) return true
        val sel = sentenceSelection
        if (sel !is AudioSelection.Explicit || sel.sourceId != RecordingAudioSource.ID) return true
        val wav = gameAudioSnapshotFile
        if (wav == null || !GameAudioSnapshot.isUsable(wav)) {
            // Under immutable per-card ownership the buffer only disappears to
            // an OS cache purge. Nothing to trim; the send path surfaces
            // "audio missing" honestly.
            return true
        }
        val validRange = RecordingAudioSource.parseRangeFor(sel.key, wav)
        if (validRange == null) {
            // Fail-safe: a game-audio pick should always carry a committed
            // range by save time (commitDefaultGameRange). If one ever doesn't
            // — no generator is currently known — drop to the TTS floor
            // instead of shipping the provisional key, which toFile rejects
            // into a silent no-audio card. The log is the point: the display
            // paths don't run this validation, so a downgrade here contradicts
            // a cell that still says "Game audio" — if this ever fires, the
            // logged state names the divergence.
            Log.w(TAG, "resolveGameAudioForSend: committed key invalid " +
                "(key=${sel.key} wavMtime=${wav.lastModified()} " +
                "waveLoaded=${gameAudioLoadedFile == wav} " +
                "waveSel=${gameAudioWave?.selStartMs}..${gameAudioWave?.selEndMs}) — TTS floor")
            sentenceSelection = AudioSelection.Auto
            return true
        }
        // Snapshot can't be clobbered by other cards, so "resolved" now
        // reduces to "was the committed range reviewed".
        if (gameAudioReviewed) return true
        // Never-touched game audio: nudge once and hold this Save. This does
        // NOT gate on panel visibility — the panel is revealed asynchronously
        // at the end of the waveform decode, so keying off it let a Save land
        // in the decode window and ship the default clip with no review.
        // nudgeGameAudioCell reveals the (fixed-height) cell itself.
        if (!gameAudioNudged) {
            gameAudioNudged = true
            nudgeGameAudioCell()
            return false
        }
        return true
    }

    /** Center the in-card game-audio trim cell in its scroll view, then flash
     *  it. Container-agnostic: walks up to whichever review surface's
     *  [NestedScrollView] hosts this card (all use one), falling back to
     *  a plain reveal if somehow there's no scroll parent.
     *
     *  The waveform decode may still be running, leaving the panel GONE. The
     *  cell is fixed-height regardless of data, so reveal it now (the decode
     *  re-affirms visibility and fills the wave when it lands) and defer the
     *  measure/scroll/flash to after layout — [doOnLayout] runs inline when the
     *  panel is already laid out, or on the next pass after the reveal. */
    private fun nudgeGameAudioCell() {
        val panel = gameAudioPanel ?: return
        panel.visibility = View.VISIBLE
        panel.doOnLayout {
            val scroller = panel.scrollParent()
            if (scroller != null) {
                // Panel top in the scroll content's own (pre-scroll) coordinates,
                // then back off by half the leftover viewport so the cell lands
                // mid-screen rather than flush against the top edge. smoothScrollTo
                // clamps the far end, so an over-tall target just pins to bottom.
                val rect = Rect(0, 0, panel.width, panel.height)
                scroller.offsetDescendantRectToMyCoords(panel, rect)
                val target = rect.top - (scroller.height - panel.height) / 2
                scroller.smoothScrollTo(0, target.coerceAtLeast(0))
            } else {
                panel.requestRectangleOnScreen(Rect(0, 0, panel.width, panel.height), false)
            }
            flashGameAudioCell(panel)
        }
    }

    /** Nearest [NestedScrollView] ancestor, or null if this view isn't in one. */
    private fun View.scrollParent(): NestedScrollView? {
        var p: ViewParent? = parent
        while (p != null) {
            if (p is NestedScrollView) return p
            p = p.parent
        }
        return null
    }

    /** Three accent pulses over the cell background, each fading up then back
     *  down; the background is cleared on completion so the cell returns to
     *  the card surface. */
    private fun flashGameAudioCell(panel: View) {
        gameAudioFlashAnimator?.cancel()
        val accentRgb = ctx.themeColor(R.attr.ptAccent) and 0x00FFFFFF
        gameAudioFlashAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 400L          // one fade-up/down; repeatCount 2 ⇒ three flashes
            repeatCount = 2
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                // Triangle 0 → 1 → 0 so each run is a full pulse, not a step.
                val intensity = 1f - kotlin.math.abs(2f * anim.animatedFraction - 1f)
                val alpha = (0x4D * intensity).toInt() shl 24  // peak ~30% accent
                panel.setBackgroundColor(alpha or accentRgb)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    panel.background = null
                    gameAudioFlashAnimator = null
                }
            })
            start()
        }
    }

    /** The sentence audio row's title: the sentence text normally, a
     *  game-audio status line when the recording is the selected source —
     *  in that mode the spoken-text label doesn't describe what the card
     *  will actually get. */
    private fun refreshSentenceAudioTitle() {
        val title = sentenceAudioHandle?.titleView ?: return
        val sel = sentenceSelection
        title.text = when {
            sel is AudioSelection.Explicit && sel.sourceId == RecordingAudioSource.ID -> {
                val range = RecordingAudioSource.parseRange(sel.key)
                if (range == null) {
                    ctx.getString(R.string.anki_game_audio_cell_untrimmed)
                } else {
                    // Same readout as the trim editor; the pill next to it
                    // already names the source. The max() covers the rare
                    // editor-return-before-panel-load case (duration 0).
                    ctx.getString(
                        R.string.game_audio_trim_duration,
                        String.format(Locale.getDefault(), "%.1f", (range.second - range.first) / 1000.0),
                        String.format(Locale.getDefault(), "%d", maxOf(gameAudioDurationMs, range.second) / 1000),
                    )
                }
            }
            else -> etOriginal.text.toString()
        }
    }

    private fun launchAudioPicker(target: PickTarget, current: AudioSelection) {
        val lang = SourceLangId.fromCode(args.getString(ARG_SOURCE_LANG))
            ?: SourceLangId.JA
        val (surface, reading, isWord) = when (target) {
            is PickTarget.Sentence -> Triple(etOriginal.text.toString(), null, false)
            is PickTarget.Word ->
                Triple(target.word, words.firstOrNull { it.word == target.word }?.reading, true)
        }
        host.openAudioPicker(
            AudioSourcePickerActivity.intent(ctx, lang, surface, reading, isWord, current),
        ) { selection -> handleAudioPick(target, selection) }
    }

    /** True while we wait for [applyWords] — drives the "Looking up words…"
     *  placeholder in the Words card. Flips to false the moment applyWords
     *  is called, even if the list it carries is empty (definitive empty). */
    private var wordsLoading: Boolean = false

    /** Set the first time the user types in [etTranslation]. Once true,
     *  [applyTranslation] becomes a no-op so a late-arriving translation
     *  doesn't stomp on what the user just typed. */
    private var translationUserTouched: Boolean = false

    /** Set before any programmatic [EditText.setText] on [etTranslation] so
     *  the TextWatcher doesn't mistake our own write for user input.
     *  Cleared by the watcher on the next callback. */
    private var translationSuppressNextEdit: Boolean = false

    /** The Original sentence as of the most recent focus-loss commit.
     *  Used only for the dedup check in [onOriginalEditCommitted] so a
     *  focus loss with no actual text change doesn't churn the fetch
     *  pipeline. The stale-result guard now lives in [applyTranslation]
     *  / [applyWords] and reads [etOriginal] directly, since the live
     *  EditText is the source of truth for "what's visible". */
    private var committedOriginal: String = ""

    /** Host-provided callback fired when the user finishes editing the
     *  Original field with a different sentence than the one whose
     *  translation/word breakdown was last fetched. The host kicks a
     *  fresh translation + word lookup pipeline; the card has already
     *  reset the Translation field and the Words card to a loading state
     *  by the time this fires. null = no re-fetch path wired (the
     *  sentence-only sheet doesn't have one), in which case Original
     *  edits commit without touching downstream state. */
    var onOriginalCommitted: ((newText: String) -> Unit)? = null

    data class CardData(
        val source: String,
        val target: String,
        val words: List<SentenceAnkiHtmlBuilder.WordEntry>,
        val selectedWords: Set<String>,
        val screenshotPath: String?,
        val sourceLangId: SourceLangId,
        /** Subset of [selectedWords] whose per-target-word audio toggle is
         *  on. Only enabled, currently-selected words are reported — the
         *  send path doesn't need false entries or stale ones. Defaults
         *  to empty for callers/tests that don't care. */
        val targetWordAudioWords: Set<String> = emptySet(),
        /** Multi-source audio selection for the sentence cell. */
        val sentenceSelection: AudioSelection = AudioSelection.Auto,
        /** Per-target-word audio selections (only [targetWordAudioWords]). */
        val wordSelections: Map<String, AudioSelection> = emptyMap(),
    )

    fun getCardData(): CardData {
        val enabledTargets = selectedWords
            .filter { wordAudioEnabled[it] == true }
            .toSet()
        return CardData(
            source = etOriginal.text.toString(),
            target = etTranslation.text.toString(),
            words = words.toList(),
            selectedWords = selectedWords.toSet(),
            screenshotPath = if (includePhoto) args.getString(ARG_SCREENSHOT_PATH) else null,
            sourceLangId = SourceLangId.fromCode(args.getString(ARG_SOURCE_LANG)) ?: SourceLangId.JA,
            targetWordAudioWords = enabledTargets,
            // Only include selections for words whose audio is enabled — the
            // send path iterates targetWordAudioWords anyway.
            sentenceSelection = sentenceSelection,
            wordSelections = enabledTargets.associateWith { wordSelections[it] ?: AudioSelection.Auto },
        )
    }

    /** The host is foregrounded again — this card's buffer is the "active"
     *  snapshot (the audio picker and trim-editor fallback resolve Game
     *  audio against it). The fragment shell calls this from onResume; the
     *  workspace page after a picker round-trip. */
    fun onHostResumed() {
        gameAudioSnapshotFile?.let { GameAudioSnapshot.active = it }
    }

    /** Game-audio state for process-death / saved-state restores — the
     *  fragment shell's onSaveInstanceState. The path + selection key +
     *  review flag fully reconstruct the cell; a trimmed clip must not
     *  silently degrade to Auto/TTS on restore. */
    fun saveState(outState: Bundle) {
        val wav = gameAudioSnapshotFile ?: return
        outState.putString(STATE_GAME_SNAPSHOT_PATH, wav.absolutePath)
        val sel = sentenceSelection
        if (sel is AudioSelection.Explicit && sel.sourceId == RecordingAudioSource.ID) {
            outState.putString(STATE_GAME_SEL_KEY, sel.key)
            outState.putBoolean(STATE_GAME_REVIEWED, gameAudioReviewed)
        }
    }

    /** Rebuild the game-audio cell after process death or a saved-state
     *  destroy (activity released while stopped, e.g. behind the trim
     *  editor). No-ops (cell stays Auto, like a card opened without
     *  recording) when nothing was saved or the snapshot didn't survive —
     *  realistic restores happen minutes later and find the file; only a
     *  >24 h zombie loses it to the orphan sweep or an OS cache purge. */
    private fun restoreGameAudioState(state: Bundle) {
        val path = state.getString(STATE_GAME_SNAPSHOT_PATH) ?: return
        val wav = File(path)
        if (!GameAudioSnapshot.isUsable(wav)) return
        gameAudioSnapshotFile = wav
        GameAudioSnapshot.active = wav
        // No saved key ⇒ the user had switched the cell AWAY from game audio
        // before death. Re-own the file (cleanup + picker availability) but
        // do not resurrect a game-audio selection over their choice.
        val key = state.getString(STATE_GAME_SEL_KEY) ?: return
        sentenceSelection =
            AudioSelection.Explicit(RecordingAudioSource.ID, key, locator = wav.absolutePath)
        gameAudioReviewed = state.getBoolean(STATE_GAME_REVIEWED, false)
        val lang = SourceLangId.fromCode(args.getString(ARG_SOURCE_LANG))
            ?: SourceLangId.JA
        sentenceAudioHandle?.refreshPillLabel(scope, lang, sentenceSelection)
        refreshSentenceAudioTitle()
        // Reloads the waveform; parseRangeFor re-validates the restored key
        // against the file (untouched across death ⇒ range preserved).
        updateGameAudioPanel()
    }

    /**
     * Host-view teardown. [deleteSnapshotFile] is the host's finality
     * decision for this card's game-audio snapshot: the fragment shell
     * passes `activity?.isFinishing == true || !isStateSaved` (see the
     * original onDestroyView's long rationale — a saved-state destroy MUST
     * keep the file for the restore); the workspace page always passes
     * true, since it has no saved state and its teardown is final. A
     * restore that never happens is the orphan sweep's job.
     */
    fun release(deleteSnapshotFile: Boolean) {
        // Native WebView teardown for every styled word row.
        wordStyledViews.values.forEach { it.destroy() }
        wordStyledViews.clear()
        stopInlinePlayback()
        gameAudioFlashAnimator?.cancel()
        gameAudioFlashAnimator = null
        inlinePlayer = null
        gameAudioPanel = null
        gameAudioWave = null
        gameAudioLoadedFile = null
        gameAudioSnapshotFile?.let { f ->
            if (GameAudioSnapshot.active == f) GameAudioSnapshot.active = null
            if (deleteSnapshotFile) f.delete()
        }
        gameAudioSnapshotFile = null
        ivPhoto?.setImageBitmap(null)
        ivPhoto = null
        sentenceAudioHandle?.release()
        sentenceAudioHandle = null
        wordAudioHandles.values.forEach { it.release() }
        wordAudioHandles.clear()
    }

    /** Build the card into [view] (the inflated
     *  [R.layout.fragment_sentence_anki_content] root). [savedInstanceState]
     *  restores the game-audio cell after process death / saved-state
     *  destroys (fragment hosting only). */
    fun buildInto(view: LinearLayout, savedInstanceState: Bundle?) {
        root = view

        // Defensive clears stay because the model collections are
        // class-level fields — if anything ever does cause a re-build we
        // don't want to accumulate duplicates.
        words.clear()
        selectedWords.clear()

        val wordArr    = args.getStringArray(ARG_WORDS) ?: emptyArray()
        val readingArr = args.getStringArray(ARG_READINGS) ?: emptyArray()
        val meaningArr = args.getStringArray(ARG_MEANINGS) ?: emptyArray()
        val freqArr    = args.getIntArray(ARG_FREQ_SCORES) ?: IntArray(0)
        val surfaces   = LastSentenceCache.surfaceForms ?: emptyMap()
        val enrich     = LastSentenceCache.wordEnrichment ?: emptyMap()
        wordArr.forEachIndexed { i, w ->
            words.add(SentenceAnkiHtmlBuilder.WordEntry(
                w,
                readingArr.getOrElse(i) { "" },
                meaningArr.getOrElse(i) { "" },
                freqArr.getOrElse(i) { 0 },
                surfaceForm = surfaces[w] ?: "",
                pitch = enrich[w]?.pitch.orEmpty(),
                frequencies = enrich[w]?.frequencies.orEmpty(),
                isCommon = enrich[w]?.isCommon ?: false,
                senses = enrich[w]?.senses.orEmpty(),
            ))
        }

        // Auto-target the looked-up word and float targets to the top.
        val targetWord = args.getString(ARG_TARGET_WORD)
        if (targetWord != null && words.any { it.word == targetWord }) {
            selectedWords.add(targetWord)
        }
        if (selectedWords.isNotEmpty()) {
            val sorted = words.sortedByDescending { it.word in selectedWords }
            words.clear()
            words.addAll(sorted)
        }

        val original = args.getString(ARG_ORIGINAL) ?: ""
        val translation = args.getString(ARG_TRANSLATION) ?: ""
        val screenshotPath = args.getString(ARG_SCREENSHOT_PATH)
        buildContent(original, translation, screenshotPath)

        // Styled word definitions for whatever words exist right now. The
        // deferred-fill flows (ARG_WORDS_LOADING open, sentence re-commit)
        // re-run this from applyWords — a one-shot here would race the
        // empty list and stay flat for the dialog's life (Codex catch).
        refreshStyledPayload()

        // Freeze the rolling game-audio buffer for THIS card the moment the
        // flow opens ("snapshot at card-open"). One-shot: never on restore,
        // so a post-process-death recreation can't clobber a good snapshot
        // with post-death silence. The cell commits a default trim range up
        // front (so it's sendable immediately, never provisional) and
        // [resolveGameAudioForSend] nudges toward it if it's never touched.
        if (savedInstanceState != null) {
            restoreGameAudioState(savedInstanceState)
        } else if (Prefs(ctx).recordGameAudio) {
            val anchorMs = args.takeIf { it.containsKey(ARG_AUDIO_ANCHOR_MS) }
                ?.getLong(ARG_AUDIO_ANCHOR_MS)
            scope.launch {
                val snap = withContext(Dispatchers.IO) {
                    CaptureService.instance?.gameAudioRecorder?.snapshotToFile(anchorMs)
                }
                if (snap == null) return@launch
                if (!host.isAlive) {
                    // Flow died while snapshotting — we own the file; reap it.
                    snap.file.delete()
                    return@launch
                }
                gameAudioSnapshotFile = snap.file
                GameAudioSnapshot.active = snap.file
                gameAudioAnchorOffsetMs = snap.anchorOffsetMs
                if (snap.anchorMissed) {
                    // The launch anchor predates the ring's oldest audio: the
                    // line's voice is provably not in this snapshot (e.g. a
                    // card from an old History row). Leave the cell on Auto
                    // (TTS) instead of defaulting to the last 5 s of unrelated
                    // audio; the snapshot stays available to an explicit
                    // Game-audio pick.
                    return@launch
                }
                // Commit the default range from a cheap header read BEFORE the
                // heavy waveform decode, so a Save landing in the decode window
                // ships the default clip instead of an unsendable provisional
                // key. Until this resolves the cell stays on Auto (the TTS
                // floor) — a fast Save then gets TTS, never dropped audio. A
                // too-short snapshot leaves the cell on Auto for good.
                if (commitDefaultGameRange(snap.file)) {
                    val lang = SourceLangId.fromCode(args.getString(ARG_SOURCE_LANG))
                        ?: SourceLangId.JA
                    sentenceAudioHandle?.refreshPillLabel(scope, lang, sentenceSelection)
                    refreshSentenceAudioTitle()
                    // Renders the waveform for the already-committed range.
                    updateGameAudioPanel()
                    launchVadScan(snap.file)
                }
            }
        }
    }

    // ── In-card game-audio panel ─────────────────────────────────────────

    /** Commit a default trim range (the last [DEFAULT_GAME_RANGE_MS]) over
     *  [wav] from a cheap header read and make it the live [sentenceSelection],
     *  so the cell is *sendable* the instant it becomes Game audio — never a
     *  provisional key a fast Save could ship as dropped audio. The heavy
     *  waveform decode still runs in [updateGameAudioPanel] (render only).
     *  Returns false, touching nothing, when the clip is too short to be a
     *  usable line — callers leave the cell on the TTS floor. */
    private suspend fun commitDefaultGameRange(wav: File): Boolean {
        val durationMs = withContext(Dispatchers.IO) { GameAudioClip.durationMs(wav) }
        if (durationMs < MIN_GAME_AUDIO_MS) return false
        gameAudioDurationMs = durationMs
        val (start, end) = defaultGameRange(durationMs)
        sentenceSelection = RecordingAudioSource.committedSelection(wav, start, end)
        gameAudioSeededRange = start to end
        gameAudioReviewed = false
        return true
    }

    /**
     * The VAD pass over this card's snapshot, two phases in one coroutine:
     *
     * 1. ANCHORED FAST PASS ([VoiceLineSnap.snap], anchor's window only) —
     *    finds the voice line the anchor names and moves the UNTOUCHED
     *    seeded default onto it ([applyVadSelection]).
     * 2. BACKGROUND REMAINDER ([VoiceLineSnap.scanRemainder]) — walks the
     *    rest of the snapshot in blocks, nearest-to-window first (tail-first
     *    when there's no anchor, which is also how unanchored cards get
     *    highlights at all), extending the warning-color highlight as each
     *    block lands. Highlight-only: phase 2 never touches the selection.
     *
     * Runs on the host scope — closing the card cancels the scan within a
     * block.
     */
    private fun launchVadScan(wav: File) {
        val durationMs = gameAudioDurationMs
        val appCtx = ctx.applicationContext
        val anchor = gameAudioAnchorOffsetMs
        scope.launch {
            // Unanchored: nothing is excluded and the backward walk starts
            // at the end — the tail is where the default selection sits.
            var scannedStart = durationMs
            var scannedEnd = durationMs
            if (anchor != null) {
                scannedStart = (anchor - VoiceLineSnap.WINDOW_PRE_MS).coerceAtLeast(0)
                scannedEnd = (anchor + VoiceLineSnap.WINDOW_POST_MS).coerceAtMost(durationMs)
                val result = VoiceLineSnap.snap(appCtx, wav, anchor, durationMs)
                if (result != null) {
                    if (!host.isAlive || gameAudioSnapshotFile != wav) return@launch
                    applySpeechRegions(result.segments)
                    applyVadSelection(wav, result.line)
                }
            }
            VoiceLineSnap.scanRemainder(
                appCtx, wav, durationMs, scannedStart, scannedEnd,
            ) { segments ->
                withContext(Dispatchers.Main.immediate) {
                    if (host.isAlive && gameAudioSnapshotFile == wav) {
                        applySpeechRegions(segments)
                    }
                }
            }
        }
    }

    /** Fold newly-scanned [segments] into the highlight set and repaint.
     *  The highlight applies unconditionally — it informs a user who already
     *  took the handles over; it overrides nothing. */
    private fun applySpeechRegions(segments: List<SpeechSnap.Segment>) {
        gameAudioSpeechRegions = SpeechSnap.merge(gameAudioSpeechRegions, segments)
        if (gameAudioLoadedFile != null && gameAudioLoadedFile == gameAudioSnapshotFile) {
            gameAudioWave?.setSpeechRegions(
                gameAudioSpeechRegions.map { it.startMs to it.endMs },
            )
        }
    }

    /**
     * The anchored snap's selection move — strictly loses to the user: a
     * handle drag, a play (reviewed), a source switch, or any range
     * differing from the seed leaves the snap on the floor. Does NOT mark
     * the range reviewed — an auto-placed clip still deserves the save-time
     * nudge.
     */
    private fun applyVadSelection(wav: File, snapped: SpeechSnap.Segment) {
        if (gameAudioReviewed) return
        val seeded = gameAudioSeededRange ?: return
        val current = (sentenceSelection as? AudioSelection.Explicit)
            ?.takeIf { it.sourceId == RecordingAudioSource.ID }
            ?.let { RecordingAudioSource.parseRangeFor(it.key, wav) }
        if (current != seeded) return
        sentenceSelection =
            RecordingAudioSource.committedSelection(wav, snapped.startMs, snapped.endMs)
        gameAudioSeededRange = snapped.startMs to snapped.endMs
        refreshSentenceAudioTitle()
        // Wave already rendered → move its selection silently (the callback
        // path would set the reviewed flag). Still decoding → the decode's
        // parseRangeFor picks up the snapped commit.
        if (gameAudioLoadedFile == wav) {
            gameAudioWave?.setSelection(snapped.startMs, snapped.endMs)
        }
    }

    /** The default trim range: brackets the launch anchor when one mapped
     *  into the snapshot — the anchor is the sentence's capture/display
     *  moment, which TRAILS its voice line by the OCR+MT latency, so most
     *  of the bracket sits before it — else the last [DEFAULT_GAME_RANGE_MS]
     *  (no anchor: the just-heard line sits at the buffer tail). */
    private fun defaultGameRange(durationMs: Long): Pair<Long, Long> {
        val anchor = gameAudioAnchorOffsetMs
            ?: return (durationMs - DEFAULT_GAME_RANGE_MS).coerceAtLeast(0) to durationMs
        var start = (anchor - ANCHOR_PRE_MS).coerceAtLeast(0)
        var end = (anchor + ANCHOR_POST_MS).coerceAtMost(durationMs)
        if (end - start < MIN_GAME_AUDIO_MS) {
            // Anchor pinned to a file edge: keep a usable minimum selection.
            end = (start + MIN_GAME_AUDIO_MS).coerceAtMost(durationMs)
            start = (end - MIN_GAME_AUDIO_MS).coerceAtLeast(0)
        }
        return start to end
    }

    /** Make Game audio the live [sentenceSelection] with a COMMITTED range —
     *  never a provisional one a fast Save could downgrade to TTS, regardless
     *  of when the waveform decode finishes. A re-pick of an already-loaded
     *  clip keeps the trim the user can see; a first pick commits the default
     *  from a cheap header read (via [commitDefaultGameRange]). Returns false,
     *  touching nothing, when the clip is too short to trim. */
    private suspend fun selectGameAudioCommitted(wav: File): Boolean {
        val wave = gameAudioWave
        if (gameAudioLoadedFile == wav && wave != null && wave.selEndMs > wave.selStartMs) {
            // Already loaded: preserve the user's on-screen trim rather than
            // resetting to the default (mirrors updateGameAudioPanel's re-pick
            // path, but synchronously — no rangeless gap before the panel load).
            sentenceSelection = RecordingAudioSource.committedSelection(
                wav, wave.selStartMs, wave.selEndMs,
            )
            return true
        }
        return commitDefaultGameRange(wav)
    }

    /** Sync the panel with [sentenceSelection]: load + show while the game
     *  recording is the selected source, hide (and silence) otherwise. */
    private fun updateGameAudioPanel() {
        val sel = sentenceSelection
        val isGameAudio = sel is AudioSelection.Explicit &&
            sel.sourceId == RecordingAudioSource.ID
        val wav = gameAudioSnapshotFile
        if (!isGameAudio || wav == null || !GameAudioSnapshot.isUsable(wav)) {
            stopInlinePlayback()
            gameAudioPanel?.visibility = View.GONE
            return
        }
        if (gameAudioLoadedFile == wav) {
            // Already loaded (e.g. switched to TTS and back). Callers now commit
            // the range before Game audio goes live (snapshot-open +
            // selectGameAudioCommitted), so `sel` normally arrives committed and
            // this just re-shows the panel. The rangeless re-commit stays as a
            // backstop — pin any stray provisional key to the wave's visible
            // selection rather than let toFile resolve it to silence.
            val rangeless = (sel as AudioSelection.Explicit)
                .let { RecordingAudioSource.parseRangeFor(it.key, wav) } == null
            val wave = gameAudioWave
            if (rangeless && wave != null && wave.selEndMs > wave.selStartMs) {
                sentenceSelection = RecordingAudioSource.committedSelection(
                    wav, wave.selStartMs, wave.selEndMs,
                )
                refreshSentenceAudioTitle()
            }
            gameAudioPanel?.visibility = View.VISIBLE
            return
        }
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val durationMs = GameAudioClip.durationMs(wav)
                if (durationMs < MIN_GAME_AUDIO_MS) return@withContext null
                val rate = GameAudioClip.sampleRate(wav)
                val pcm = GameAudioClip.readPcmRange(wav, 0, durationMs)
                Triple(durationMs, rate, rmsBucketsForStrip(pcm, rate))
            }
            if (!host.isAlive) return@launch
            // UI-race guard: the user may have switched source while the IO
            // ran. (The snapshot itself is immutable and card-owned —
            // file churn is structurally impossible now.)
            val selNow = sentenceSelection
            val stillGameAudio = selNow is AudioSelection.Explicit &&
                selNow.sourceId == RecordingAudioSource.ID
            if (!stillGameAudio || gameAudioSnapshotFile != wav) return@launch
            if (loaded == null) {
                gameAudioPanel?.visibility = View.GONE
                return@launch
            }
            val (durationMs, rate, buckets) = loaded
            gameAudioDurationMs = durationMs
            gameAudioSampleRate = rate
            gameAudioLoadedFile = wav
            // A rangeless (fresh) selection gets the default range now that
            // the duration is known; a committed range is preserved.
            val existing = (selNow as AudioSelection.Explicit)
                .let { RecordingAudioSource.parseRangeFor(it.key, wav) }
            val (start, end) = existing ?: defaultGameRange(durationMs)
            if (existing == null) {
                sentenceSelection = RecordingAudioSource.committedSelection(wav, start, end)
                // A freshly-defaulted range hasn't been seen by the user.
                gameAudioReviewed = false
            }
            gameAudioWave?.setData(buckets, 50L, durationMs, start, end)
            // VAD emissions that landed before this decode are re-painted
            // here; ones landing after paint via applySpeechRegions.
            if (gameAudioSpeechRegions.isNotEmpty()) {
                gameAudioWave?.setSpeechRegions(
                    gameAudioSpeechRegions.map { it.startMs to it.endMs },
                )
            }
            refreshSentenceAudioTitle()
            gameAudioPanel?.visibility = View.VISIBLE
        }
    }

    /** Per-bucket ABSOLUTE RMS in 0..1 (50 ms buckets). Deliberately NOT
     *  normalized to the file's loudest bucket: [WaveformTrimView] scales bars
     *  to what is on screen, and it can only refuse to inflate a near-silent
     *  window (its SILENT_FLOOR_RMS) if the levels it receives are absolute. */
    private fun rmsBucketsForStrip(pcm: ShortArray, rate: Int): FloatArray {
        val bucketFrames = (rate / 20).coerceAtLeast(1)
        val out = FloatArray((pcm.size + bucketFrames - 1) / bucketFrames)
        for (b in out.indices) {
            val from = b * bucketFrames
            val to = minOf(from + bucketFrames, pcm.size)
            var sumSq = 0.0
            for (i in from until to) {
                val s = pcm[i].toDouble()
                sumSq += s * s
            }
            out[b] = (kotlin.math.sqrt(sumSq / (to - from)) / Short.MAX_VALUE).toFloat()
        }
        return out
    }

    /** Touches landing on the panel's padding (outside the waveform child)
     *  are forwarded into the waveform, coordinate-shifted — so zoom/pan
     *  gestures work across the whole bottom region of the cell. Touches
     *  that start ON the waveform never reach this listener. */
    @SuppressLint("ClickableViewAccessibility")
    private fun forwardPanelTouchesToWave(panel: View) {
        panel.setOnTouchListener { _, ev ->
            val wave = gameAudioWave ?: return@setOnTouchListener false
            val copy = MotionEvent.obtain(ev)
            copy.offsetLocation(-wave.left.toFloat(), -wave.top.toFloat())
            val handled = wave.onTouchEvent(copy)
            copy.recycle()
            handled
        }
    }

    private fun onInlineSelectionChanged(startMs: Long, endMs: Long) {
        val wav = gameAudioSnapshotFile ?: return
        stopInlinePlayback()
        // end > start is WaveformTrimView's contract (it enforces a 200 ms
        // minimum gap at every mutation point), so the committed key is
        // always parseRange-valid.
        sentenceSelection = RecordingAudioSource.committedSelection(wav, startMs, endMs)
        gameAudioReviewed = true
        refreshSentenceAudioTitle()
    }

    /**
     * The row chip's playOverride in game-audio mode: play the selected
     * range as raw PCM with the cursor sweeping the inline waveform.
     * Suspends until playback completes (the chip shows the pause icon
     * meanwhile); the chip's tap-again cancellation lands in
     * invokeOnCancellation. Returns null for non-game selections so the
     * chip falls through to the registry path.
     */
    private suspend fun playGameAudioInline(onStart: (() -> Unit)?): PlayOutcome? {
        val sel = sentenceSelection as? AudioSelection.Explicit ?: return null
        if (sel.sourceId != RecordingAudioSource.ID) return null
        // This card's own immutable snapshot — churn from other cards is
        // structurally impossible, so no staleness handling is needed here.
        val wav = gameAudioSnapshotFile?.takeIf { GameAudioSnapshot.isUsable(it) }
            ?: return PlayOutcome.Failed(recoverable = false)
        val range = RecordingAudioSource.parseRangeFor(sel.key, wav)
            ?: gameAudioWave?.let { w ->
                if (w.selEndMs > w.selStartMs) w.selStartMs to w.selEndMs else null
            }
            ?: return PlayOutcome.Failed(recoverable = false)
        val (startMs, endMs) = range
        gameAudioReviewed = true // listening counts as review
        val (pcm, rate) = withContext(Dispatchers.IO) {
            GameAudioClip.readPcmRange(wav, startMs, endMs) to GameAudioClip.sampleRate(wav)
        }
        if (pcm.isEmpty()) return PlayOutcome.Failed(recoverable = false)
        stopInlinePlayback()
        val player = PcmAudioTrackPlayer(rate)
        inlinePlayer = player
        inlinePlaying = true
        try {
            suspendCancellableCoroutine { cont ->
                inlineCont = cont
                cont.invokeOnCancellation { player.stop() }
                player.play(
                    pcm,
                    0,
                    pcm.size,
                    onProgress = { frame ->
                        gameAudioWave?.setPlaybackCursorMs(startMs + frame * 1000L / rate)
                    },
                    onDone = {
                        inlineCont = null
                        if (cont.isActive) cont.resume(Unit)
                    },
                )
                onStart?.invoke()
            }
        } finally {
            inlineCont = null
            inlinePlaying = false
            gameAudioWave?.setPlaybackCursorMs(null)
        }
        return PlayOutcome.Played
    }

    private fun stopInlinePlayback() {
        inlinePlayer?.stop()
        inlinePlaying = false
        // Release the chip's await too (a handle drag mid-playback), so the
        // chip returns to idle instead of hanging on the pause icon.
        inlineCont?.let {
            inlineCont = null
            if (it.isActive) it.resume(Unit)
        }
        gameAudioWave?.setPlaybackCursorMs(null)
    }

    // ── Build ────────────────────────────────────────────────────────────

    private fun buildContent(original: String, translation: String, screenshotPath: String?) {
        root.removeAllViews()

        val prefs = Prefs(ctx)
        val lang = SourceLangId.fromCode(args.getString(ARG_SOURCE_LANG))
            ?: SourceLangId.JA

        // Original — id is pinned to a resource id (etAnkiOriginal) so
        // Android's automatic view-state save/restore can round-trip
        // the typed text across process death without us writing a
        // manual onSaveInstanceState pipeline (fragment hosting). The
        // compact 44dp audio toggle now sits inside the same card,
        // beneath the edit field.
        ankiGroupHeader(root, ctx.getString(R.string.anki_group_original))
        val originalCard = ankiGroupCard(root)
        etOriginal = buildEditField(initial = original).apply {
            id = R.id.etAnkiOriginal
        }
        committedOriginal = original
        // Done / Next on the IME shouldn't advance focus to Translation
        // (we want commit-and-dismiss, not auto-tab). Consume both action
        // ids, clear focus, and explicitly hide the IME — clearFocus()
        // alone doesn't always hide on every keyboard. Multi-line newline
        // insertion stays untouched because IMEs route Enter through the
        // EditText, not through onEditorAction.
        etOriginal.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_NEXT) {
                v.clearFocus()
                ctx.getSystemService(InputMethodManager::class.java)
                    ?.hideSoftInputFromWindow(v.windowToken, 0)
                true
            } else {
                false
            }
        }
        // Focus loss is the canonical "the user is done with this field"
        // signal — fires on Done press (via clearFocus above), on tap-
        // outside, and on focus shift to Translation. Triggers a single
        // re-fetch pass through onOriginalEditCommitted when the text
        // actually changed.
        etOriginal.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) onOriginalEditCommitted()
        }
        originalCard.addView(buildEditableFrame(etOriginal))
        ankiInsetDivider(originalCard)
        sentenceAudioHandle = addCompactAudioToggleRow(
            ctx, scope,
            parent = originalCard,
            lang = lang,
            label = original,
            previewText = { etOriginal.text.toString() },
            initialChecked = prefs.ankiSentenceAudioEnabled,
            onCheckedChange = { prefs.ankiSentenceAudioEnabled = it },
            onVoicePillTap = { launchAudioPicker(PickTarget.Sentence, sentenceSelection) },
            // Multi-source: Auto resolves Commons-first → TTS; the resolver
            // applies the kana spoken-form for JA itself. Commons has no
            // sentence recordings, so the sentence cell effectively uses TTS.
            selection = { sentenceSelection },
            audioRequest = { AudioRequest.sentence(etOriginal.text.toString(), lang) },
            // Game-audio mode: the chip drives the inline panel's playback
            // (raw PCM + cursor sweep on the waveform) instead of the
            // registry path; null for every other selection.
            playOverride = { onStart -> playGameAudioInline(onStart) },
        )
        // Track edits — the chip re-reads via its previewText lambda, but
        // the row's visible label is a one-shot text= and won't follow
        // keystrokes without an explicit watcher.
        etOriginal.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Routed through the refresher: in game-audio mode the row
                // title is a status line, not the (editable) sentence text.
                refreshSentenceAudioTitle()
            }
        })

        // In-card game-audio editing panel, beneath the audio row inside the
        // same card. Hidden until the selection is the game recording.
        // Playback is the row chip's job (playOverride above).
        val panel = LayoutInflater.from(ctx)
            .inflate(R.layout.anki_game_audio_panel, originalCard, false)
        gameAudioPanel = panel
        gameAudioWave = panel.findViewById<WaveformTrimView>(R.id.waveInline).apply {
            embedded = true
            // The panel sits on the group card, not the page background.
            fadeColor = ctx.themeColor(R.attr.ptCard)
            // A tap on empty waveform lays down the same clip length the card
            // seeds itself with (the anchored default brackets exactly this
            // much around the anchor), so "somewhere else" means the same
            // amount of audio, not a second idea of how long a line is.
            tapSelectionMs = DEFAULT_GAME_RANGE_MS
            onSelectionChanged = { s, e -> onInlineSelectionChanged(s, e) }
        }
        // Pinch anywhere in the panel (its padding included) zooms the
        // waveform, not just pinches that start on the strip itself.
        forwardPanelTouchesToWave(panel)
        panel.visibility = View.GONE
        gameAudioLoadedFile = null
        originalCard.addView(panel)

        // Translation — same trick with R.id.etAnkiTranslation.
        ankiGroupHeader(root, ctx.getString(R.string.anki_group_translation))
        val translationCard = ankiGroupCard(root)
        etTranslation = buildEditField(initial = translation).apply {
            id = R.id.etAnkiTranslation
            if (translation.isBlank()) {
                hint = ctx.getString(R.string.status_translating)
                setHintTextColor(ctx.themeColor(R.attr.ptTextMuted))
            }
        }
        attachTranslationTouchWatcher(etTranslation)
        translationCard.addView(buildEditableFrame(etTranslation))

        // Words on card. The host tells us whether a follow-up
        // `applyWords` call is coming (drag → Anki path) vs. whether
        // an empty list is the final answer (sentence-only sheet
        // tapped while VM lookups are still loading). Inferring
        // "loading" from `words.isEmpty()` would mis-render the latter
        // as a permanent placeholder over a zero-word card.
        wordsLoading = args.getBoolean(ARG_WORDS_LOADING, false)
        ankiGroupHeader(root, ctx.getString(R.string.anki_group_words_count, words.size))
        wordsHeaderTitle = (root.getChildAt(root.childCount - 1) as ViewGroup)
            .findViewById(R.id.tvGroupTitle)
        wordsCard = ankiGroupCard(root)
        addWordsHelperRow(wordsCard)
        rebuildWordRows()

        // Screenshot — built only when the file exists; collapses cleanly
        // on remove tap so the user gets immediate feedback that the
        // photo won't ship.
        if (screenshotPath != null) {
            val file = File(screenshotPath)
            if (file.exists()) {
                ankiGroupHeader(root, ctx.getString(R.string.anki_group_screenshot))
                screenshotHeader = root.getChildAt(root.childCount - 1)
                val screenshotCard = ankiGroupCard(root)
                screenshotGroup = root.getChildAt(root.childCount - 1)
                addScreenshotRow(screenshotCard, file) {
                    removeScreenshotFromUi()
                    // Mirror the removal into a sibling tab when one shares
                    // the same source media (the word/sentence tab pair
                    // would otherwise get out of sync).
                    host.onScreenshotRemoved()
                }
            }
        }
    }

    /** Tear down the screenshot group from the live view tree and flip
     *  [includePhoto] off so [getCardData] no longer reports a photo
     *  for this side. Public so the parent sheet can keep both tabs in
     *  sync — when the user removes the photo in word-mode, the
     *  sentence-tab screenshot needs to disappear too. */
    fun removeScreenshotFromUi() {
        if (!includePhoto) return
        includePhoto = false
        screenshotHeader?.let { root.removeView(it) }
        screenshotGroup?.let { root.removeView(it) }
        screenshotHeader = null
        screenshotGroup = null
    }

    /** Wrap an [EditText] in a FrameLayout with a small pencil icon
     *  overlaid at top-right, marking the field as editable. The pencil
     *  is purely decorative — tapping anywhere on the field still gives
     *  it focus. */
    private fun buildEditableFrame(editText: EditText): FrameLayout {
        val density = ctx.resources.displayMetrics.density
        val frame = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        // Reserve room on the right so the typed text doesn't run under
        // the pencil glyph.
        editText.setPadding(
            editText.paddingLeft,
            editText.paddingTop,
            (32 * density).toInt(),
            editText.paddingBottom,
        )
        frame.addView(editText)
        frame.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_edit)
            setColorFilter(ctx.themeColor(R.attr.ptTextHint))
            layoutParams = FrameLayout.LayoutParams(
                (14 * density).toInt(),
                (14 * density).toInt(),
                Gravity.TOP or Gravity.END,
            ).also {
                it.topMargin = (14 * density).toInt()
                it.marginEnd = (12 * density).toInt()
            }
            isClickable = false
        })
        return frame
    }

    /** Editable field used by both Original and Translation. Multi-line,
     *  inherits the card's surface, no underline. */
    private fun buildEditField(initial: String): EditText {
        val density = ctx.resources.displayMetrics.density
        return EditText(ctx).apply {
            setText(initial)
            setTextColor(ctx.themeColor(R.attr.ptText))
            setHintTextColor(ctx.themeColor(R.attr.ptTextHint))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(false)
            isVerticalScrollBarEnabled = false
            gravity = Gravity.TOP or Gravity.START
            minLines = 1
            setPadding((16 * density).toInt(), (12 * density).toInt(),
                (16 * density).toInt(), (12 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun addWordsHelperRow(card: LinearLayout) {
        val density = ctx.resources.displayMetrics.density
        card.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.anki_words_helper)
            textSize = 12f
            setTextColor(ctx.themeColor(R.attr.ptTextMuted))
            setBackgroundColor(ctx.themeColor(R.attr.ptSurface))
            setLineSpacing(0f, 1.35f)
            setPadding((16 * density).toInt(), (10 * density).toInt(),
                (16 * density).toInt(), (10 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })
        ankiInsetDivider(card, indentDp = 0)
    }

    private fun addScreenshotRow(card: LinearLayout, file: File, onRemove: () -> Unit) {
        val density = ctx.resources.displayMetrics.density
        val frame = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val img = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val bmp = BitmapFactory.decodeFile(file.absolutePath)
        if (bmp != null) img.setImageBitmap(bmp)
        ivPhoto = img
        frame.addView(img)

        // Semi-transparent black circle keeps the white "✕" legible
        // against bright frames; size is fixed so the hit target stays
        // consistent regardless of the glyph's intrinsic width.
        val removeSize = (32 * density).toInt()
        frame.addView(TextView(ctx).apply {
            text = "✕"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_screenshot_remove)
            isClickable = true
            isFocusable = true
            contentDescription = ctx.getString(R.string.anki_screenshot_remove_content_description)
            layoutParams = FrameLayout.LayoutParams(
                removeSize, removeSize,
                Gravity.TOP or Gravity.END,
            ).also {
                it.topMargin = (8 * density).toInt()
                it.marginEnd = (8 * density).toInt()
            }
            setOnClickListener { onRemove() }
        })
        card.addView(frame)
    }

    // ── Word rows ────────────────────────────────────────────────────────

    private fun rebuildWordRows() {
        // Release any in-flight preview audio on sub-rows we're about to
        // remove — without this, a chip mid-playback would keep playing
        // for a beat after its row vanishes.
        wordAudioHandles.values.forEach { it.release() }
        wordAudioHandles.clear()

        // Strip everything after the helper row + its divider, then
        // re-emit current word rows. Helper row is at index 0; divider
        // at index 1; word rows live from index 2 onward.
        while (wordsCard.childCount > 2) {
            wordsCard.removeViewAt(wordsCard.childCount - 1)
        }
        if (words.isEmpty() && wordsLoading) {
            wordsCard.addView(buildWordsLoadingRow())
        } else {
            val prefs = Prefs(ctx)
            val lang = SourceLangId.fromCode(args.getString(ARG_SOURCE_LANG))
                ?: SourceLangId.JA
            words.forEachIndexed { i, entry ->
                if (i > 0) ankiInsetDivider(wordsCard, indentDp = 16)
                wordsCard.addView(buildWordRow(entry))
                // Per-target-word audio sub-row, only when the user has
                // selected this word as a target. Inserted BEFORE the
                // next inter-word divider (handled at the top of the
                // next iteration), so the divider visually separates
                // word groups rather than splitting a word from its
                // own audio sub-row.
                if (entry.word in selectedWords) {
                    val seeded = wordAudioEnabled.getOrPut(entry.word) {
                        prefs.ankiWordAudioEnabled
                    }
                    // Per-word selection defaults to Auto (Commons-first → TTS).
                    wordSelections.getOrPut(entry.word) { AudioSelection.Auto }
                    val word = entry.word
                    val reading = entry.reading
                    val handle = addCompactAudioToggleRow(
                        ctx, scope,
                        parent = wordsCard,
                        lang = lang,
                        label = word,
                        // Preview the kana reading (JA) so the audition matches
                        // the audio the card will carry (see ttsTextForWord).
                        previewText = { ttsTextForWord(word, reading.ifBlank { null }, lang) },
                        initialChecked = seeded,
                        onCheckedChange = { checked ->
                            wordAudioEnabled[word] = checked
                            // Mirror the existing sentence-audio pref
                            // semantics: the last value the user picks
                            // becomes the default for the next card.
                            prefs.ankiWordAudioEnabled = checked
                        },
                        onVoicePillTap = {
                            launchAudioPicker(
                                PickTarget.Word(word), wordSelections[word] ?: AudioSelection.Auto,
                            )
                        },
                        selection = { wordSelections[word] ?: AudioSelection.Auto },
                        audioRequest = { AudioRequest.word(word, reading.ifBlank { null }, lang) },
                    )
                    wordAudioHandles[word] = handle
                }
            }
        }
        // Live count in the group header.
        wordsHeaderTitle.text = ctx.getString(R.string.anki_group_words_count, words.size)
            .uppercase(Locale.ROOT)
    }

    private fun buildWordsLoadingRow(): View {
        val density = ctx.resources.displayMetrics.density
        return TextView(ctx).apply {
            text = ctx.getString(R.string.words_loading)
            textSize = 14f
            setTextColor(ctx.themeColor(R.attr.ptTextMuted))
            setPadding((16 * density).toInt(), (12 * density).toInt(),
                (16 * density).toInt(), (12 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    /**
     * The full styled definitions for one word row (the word creation
     * page's treatment), replacing the one-line flat meaning when the word
     * has retained structured senses. One cached WebView per structured
     * word, reparented across rebuilds; null = keep the flat line.
     */
    private fun wordStyledBlock(entry: SentenceAnkiHtmlBuilder.WordEntry): View? {
        val st = sheetStyled ?: return null
        if (wordStyledBroken) return null
        val imported = entry.senses.filter { it.imported }
        if (imported.none { it.scRowid != null && st.structured.containsKey(it.scRowid) }) return null
        val density = ctx.resources.displayMetrics.density
        val v = wordStyledViews[entry.word] ?: run {
            val created = YomitanDefinitionsView(
                ctx,
                DefinitionsDocument.Tokens(
                    text = ctx.themeColor(R.attr.ptText),
                    textMuted = ctx.themeColor(R.attr.ptTextMuted),
                    textHint = ctx.themeColor(R.attr.ptTextHint),
                    accent = ctx.themeColor(R.attr.ptAccent),
                    panel = ctx.themeColor(R.attr.ptCard),
                    baseFontSizePx = 13f,
                ),
            )
            if (!created.isUsable()) {
                wordStyledBroken = true
                return null
            }
            // The row's tap toggles target state; without this the WebView
            // eats every tap landing on the definitions block and the row
            // click never fires. Link taps lose to the row here, by design.
            created.passThroughTouches = true
            created.onContentHeight = { h ->
                created.layoutParams?.let { lp ->
                    lp.height = h
                    created.layoutParams = lp
                }
            }
            created.onRendererGone = {
                // That instance destroyed itself; drop the rest and stay
                // native for the sheet's life.
                wordStyledViews.remove(entry.word)
                wordStyledViews.values.forEach { it.destroy() }
                wordStyledViews.clear()
                wordStyledBroken = true
                rebuildWordRows()
            }
            wordStyledViews[entry.word] = created
            created
        }
        (v.parent as? ViewGroup)?.removeView(v)
        v.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (v.layoutParams?.height ?: 1).coerceAtLeast(1),
        ).also { it.topMargin = (3 * density).toInt() }
        v.setContent(
            DefinitionsDocument.contentHtml(
                WordDefinitionData(
                    word = entry.word, reading = null, senses = emptyList(),
                    freqScore = 0, isCommon = false,
                    importedGroups = importedGroupsFromSenses(imported),
                ),
                st.structured,
                localizePos = { it.joinToString(" · ") },
            ),
            st.dictStyles,
            st.sourceLanguage,
        )
        return v
    }

    private fun buildWordRow(entry: SentenceAnkiHtmlBuilder.WordEntry): View {
        val density = ctx.resources.displayMetrics.density
        val isTarget = entry.word in selectedWords
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * density).toInt(), (12 * density).toInt(),
                (12 * density).toInt(), (12 * density).toInt())
            // Target rows pick up the accent tint as a peripheral signal —
            // no "Target" label, just a quiet accent wash + word colour
            // change so the user can see what'll be highlighted on the
            // generated card.
            setBackgroundColor(if (isTarget) ctx.themeColor(R.attr.ptAccentTint) else 0)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (isTarget) selectedWords.remove(entry.word)
                else selectedWords.add(entry.word)
                rebuildWordRows()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val topLine = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topLine.addView(TextView(ctx).apply {
            text = entry.word
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ctx.themeColor(if (isTarget) R.attr.ptAccent else R.attr.ptText))
        })
        // Reading hint — annotated with its pitch-accent contour when known,
        // the same display the word-detail header and result cells use. Kana-
        // only words carry no separate reading but may still have pitch, so the
        // kana is repeated as the contour surface (mirrors WordResultCell).
        val pitchKana = entry.reading.takeIf { it.isNotBlank() }
            ?: entry.word.takeIf { entry.pitch.isNotEmpty() && entry.word.all(Deinflector::isKana) }
        if (pitchKana != null) {
            topLine.addView(TextView(ctx).apply {
                if (entry.pitch.isNotEmpty()) {
                    text = buildPitchAnnotatedReading(pitchKana, entry.pitch)
                    // Headroom for the overline band; the horizontal row's
                    // baseline alignment lifts this padding above the shared
                    // baseline so the word and reading stay aligned.
                    // PitchAccentSpan leaves FontMetrics untouched by contract.
                    setPadding(0, (8 * density).toInt(), 0, 0)
                    // Optical nudge up 2dp: a pure render offset (no layout
                    // reflow), tightening the accented reading against the word.
                    // The overline keeps its slack inside the padding band.
                    translationY = -2f * density
                } else {
                    text = pitchKana
                }
                textSize = 12f
                setTextColor(ctx.themeColor(R.attr.ptTextHint))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginStart = (8 * density).toInt() }
            })
        }
        if (entry.freqScore > 0) {
            topLine.addView(TextView(ctx).apply {
                text = SentenceAnkiHtmlBuilder.starsString(entry.freqScore)
                textSize = 11f
                setTextColor(ctx.themeColor(R.attr.ptTextHint))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginStart = (8 * density).toInt() }
            })
        }
        col.addView(topLine)

        val styledBlock = wordStyledBlock(entry)
        if (styledBlock != null) {
            col.addView(styledBlock)
        } else if (entry.meaning.isNotBlank()) {
            col.addView(TextView(ctx).apply {
                text = entry.meaning.lines().firstOrNull { it.isNotBlank() } ?: entry.meaning
                textSize = 13f
                setTextColor(ctx.themeColor(R.attr.ptTextMuted))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = (3 * density).toInt() }
            })
        }

        row.addView(col)

        row.addView(TextView(ctx).apply {
            text = "✕"
            textSize = 16f
            setTextColor(ctx.themeColor(R.attr.ptTextMuted))
            isClickable = true
            isFocusable = true
            contentDescription = ctx.getString(R.string.anki_word_remove_content_description)
            setPadding((10 * density).toInt(), (4 * density).toInt(),
                (10 * density).toInt(), (4 * density).toInt())
            setOnClickListener {
                words.removeAll { it.word == entry.word }
                selectedWords.remove(entry.word)
                // The removed word's styled renderer dies with it.
                wordStyledViews.remove(entry.word)?.destroy()
                // Drop per-word audio state so the maps don't grow
                // across remove/re-add cycles. rebuildWordRows would
                // release the handle anyway, but the state slots need
                // explicit cleanup. Note: untap-to-deselect (handled
                // by the row's main click listener, not this ✕) leaves
                // these entries in place — only a hard remove drops them.
                wordAudioEnabled.remove(entry.word)
                wordSelections.remove(entry.word)
                rebuildWordRows()
            }
        })
        return row
    }

    // ── Async fill-in API ────────────────────────────────────────────

    /** Marks [translationUserTouched] the first time the user types anything
     *  we didn't write ourselves. [translationSuppressNextEdit] is flipped to
     *  true *immediately before* any programmatic write inside
     *  [applyTranslation], so the watcher swallows exactly that callback and
     *  treats the next callback (real user input) as touched. Note: the
     *  initial setText in [buildEditField] runs before this watcher attaches,
     *  so there is no callback to suppress at attach time — pre-arming here
     *  would silently consume the user's first real keystroke. */
    private fun attachTranslationTouchWatcher(field: EditText) {
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (translationSuppressNextEdit) {
                    translationSuppressNextEdit = false
                } else {
                    translationUserTouched = true
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    /**
     * Called from the focus-loss listener on [etOriginal] when the user
     * is done editing the Original sentence. If the text differs from
     * the last sentence we asked the host to fetch and a re-fetch path
     * is wired, this resets the Translation and Words sections to
     * their loading state and hands the new sentence to
     * [onOriginalCommitted] so the host can fire its async pipeline.
     *
     * The Translation field is only cleared when the user hasn't typed
     * their own translation (`translationUserTouched` is false) —
     * preserving user-typed translation is the safer default; they can
     * clear it manually to see the auto-translation if they want it.
     */
    private fun onOriginalEditCommitted() {
        if (!::etOriginal.isInitialized) return
        val newText = etOriginal.text.toString()
        if (newText == committedOriginal || newText.isBlank()) return
        val callback = onOriginalCommitted ?: return
        committedOriginal = newText

        // Translation: clear back to the loading hint only if the user
        // hasn't typed their own — preserve user work, even at the cost
        // of hiding the freshly-fetched translation behind their text.
        if (::etTranslation.isInitialized && !translationUserTouched) {
            translationSuppressNextEdit = true
            etTranslation.setText("")
            etTranslation.hint = ctx.getString(R.string.status_translating)
            etTranslation.setHintTextColor(ctx.themeColor(R.attr.ptTextMuted))
        }

        // Words: every per-word piece of state is keyed by surface form,
        // so a new sentence invalidates all of it — selections, per-word
        // audio toggles, per-word voice picks, and any in-flight preview
        // chips. Releasing the handles before clearing the map stops
        // any audio that was mid-play on a row about to be removed.
        selectedWords.clear()
        wordAudioEnabled.clear()
        wordAudioHandles.values.forEach { it.release() }
        wordAudioHandles.clear()
        wordSelections.clear()
        words.clear()
        wordsLoading = true
        rebuildWordRows()

        callback(newText)
    }

    /**
     * Replaces the placeholder Translation field with [text] when an
     * async fetch lands. [text] = null renders the error variant
     * ("Couldn't translate") without clobbering anything the user has
     * typed in the meantime.
     *
     * [forOriginal] is the sentence whose translation [text] is —
     * compared against the visible [etOriginal] text to discard
     * results that no longer match what's on screen (superseded
     * fetches, or fetches whose original was edited without focus
     * loss). Without this guard Save could ship a card whose source
     * and translation disagree.
     */
    fun applyTranslation(forOriginal: String, text: String?) {
        if (!::etTranslation.isInitialized) return
        if (forOriginal != etOriginal.text.toString()) return
        if (translationUserTouched) return
        if (text == null) {
            etTranslation.hint = ctx.getString(R.string.anki_translation_error)
            etTranslation.setHintTextColor(ctx.themeColor(R.attr.ptTextMuted))
            return
        }
        if (text.isBlank()) return
        translationSuppressNextEdit = true
        etTranslation.setText(text)
        etTranslation.hint = null
        args.putString(ARG_TRANSLATION, text)
    }

    /**
     * Replaces the placeholder Words rows with [entries] when the
     * sentence's word lookups complete. [targetWord] re-applies the
     * auto-target highlight from [buildInto] so the looked-up word
     * stays selected when it lands in the list.
     *
     * [forOriginal] is the sentence whose word breakdown [entries] is —
     * compared against the visible [etOriginal] text. Mirrors
     * [applyTranslation]'s guard.
     */
    fun applyWords(
        forOriginal: String,
        entries: List<SentenceAnkiHtmlBuilder.WordEntry>,
        targetWord: String?,
    ) {
        if (!::wordsCard.isInitialized) return
        if (forOriginal != etOriginal.text.toString()) return
        wordsLoading = false
        words.clear()
        words.addAll(entries)
        if (targetWord != null && words.any { it.word == targetWord }) {
            selectedWords.add(targetWord)
        }
        if (selectedWords.isNotEmpty()) {
            val sorted = words.sortedByDescending { it.word in selectedWords }
            words.clear()
            words.addAll(sorted)
        }
        args.putStringArray(ARG_WORDS, words.map { it.word }.toTypedArray())
        args.putStringArray(ARG_READINGS, words.map { it.reading }.toTypedArray())
        args.putStringArray(ARG_MEANINGS, words.map { it.meaning }.toTypedArray())
        args.putIntArray(ARG_FREQ_SCORES, words.map { it.freqScore }.toIntArray())
        rebuildWordRows()
        // The list just changed wholesale — the styled payload must follow
        // it (and the previous sentence's payload must not linger).
        refreshStyledPayload()
    }

    companion object {
        /** A game-audio snapshot shorter than this isn't a usable voice line;
         *  the cell stays on the TTS floor rather than offer an untrimmable clip. */
        private const val MIN_GAME_AUDIO_MS = 500L

        /** Default trim window for a fresh game-audio selection — the last few
         *  seconds of the snapshot, where the just-heard line sits. */
        private const val DEFAULT_GAME_RANGE_MS = 5_000L

        /** Anchor-seeded default range: how far the selection reaches back
         *  from the mapped anchor and past it. Back-weighted on purpose: the
         *  anchor is when the sentence was captured/displayed, and the voice
         *  line PRECEDES that by the pipeline latency (plus however long the
         *  user took to shutter). The 30 s opening viewport centered on this
         *  selection covers lines that start earlier still. */
        private const val ANCHOR_PRE_MS = 4_000L
        private const val ANCHOR_POST_MS = 1_000L

        /** Restore of the game-audio state after process death / a
         *  saved-state destroy (fragment hosting). */
        private const val STATE_GAME_SNAPSHOT_PATH = "game_snapshot_path"
        private const val STATE_GAME_SEL_KEY = "game_sel_key"
        private const val STATE_GAME_REVIEWED = "game_reviewed"

        private const val ARG_ORIGINAL        = "japanese"
        private const val ARG_TRANSLATION     = "translation"
        private const val ARG_WORDS           = "words"
        private const val ARG_READINGS        = "readings"
        private const val ARG_MEANINGS        = "meanings"
        private const val ARG_FREQ_SCORES     = "freq_scores"
        private const val ARG_SCREENSHOT_PATH = "screenshot_path"
        private const val ARG_TARGET_WORD     = "target_word"
        private const val ARG_SOURCE_LANG     = "source_lang"
        private const val ARG_WORDS_LOADING   = "words_loading"
        private const val ARG_AUDIO_ANCHOR_MS = "audio_anchor_ms"

        /** The launch-state bundle both hosts build — the fragment stores it
         *  as its arguments (so its mutation semantics are unchanged), the
         *  workspace page passes it straight to the view. */
        fun buildArgs(
            japanese: String,
            translation: String,
            words: List<SentenceAnkiHtmlBuilder.WordEntry>,
            screenshotPath: String?,
            targetWord: String? = null,
            sourceLangId: SourceLangId = SourceLangId.JA,
            wordsLoading: Boolean = false,
            audioAnchorMs: Long? = null,
        ): Bundle = Bundle().apply {
            putString(ARG_ORIGINAL, japanese)
            putString(ARG_TRANSLATION, translation)
            putStringArray(ARG_WORDS, words.map { it.word }.toTypedArray())
            putStringArray(ARG_READINGS, words.map { it.reading }.toTypedArray())
            putStringArray(ARG_MEANINGS, words.map { it.meaning }.toTypedArray())
            putIntArray(ARG_FREQ_SCORES, words.map { it.freqScore }.toIntArray())
            if (screenshotPath != null) putString(ARG_SCREENSHOT_PATH, screenshotPath)
            if (targetWord != null) putString(ARG_TARGET_WORD, targetWord)
            putString(ARG_SOURCE_LANG, sourceLangId.code)
            putBoolean(ARG_WORDS_LOADING, wordsLoading)
            if (audioAnchorMs != null) putLong(ARG_AUDIO_ANCHOR_MS, audioAnchorMs)
        }

        /** Decode the audio picker's result extras into an [AudioSelection]
         *  — shared by the fragment shells' ActivityResult callbacks and
         *  the workspace's picker gate. */
        fun parsePickerResult(data: Intent?): AudioSelection {
            val src = data?.getStringExtra(AudioSourcePickerActivity.EXTRA_PICKED_SOURCE)
            val key = data?.getStringExtra(AudioSourcePickerActivity.EXTRA_PICKED_KEY)
            val locator = data?.getStringExtra(AudioSourcePickerActivity.EXTRA_PICKED_LOCATOR)
            val attribution = data?.getStringExtra(AudioSourcePickerActivity.EXTRA_PICKED_ATTRIBUTION)
                ?.let { runCatching { PtJson.lenient.decodeFromString(Attribution.serializer(), it) }.getOrNull() }
            return if (src != null && key != null) {
                AudioSelection.Explicit(src, key, locator, attribution)
            } else {
                AudioSelection.Auto
            }
        }
    }
}
