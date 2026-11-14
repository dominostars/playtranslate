package com.playtranslate.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Rect
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
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.core.view.doOnLayout
import androidx.core.widget.NestedScrollView
import com.playtranslate.CaptureService
import com.playtranslate.Prefs
import com.playtranslate.PtJson
import com.playtranslate.R
import com.playtranslate.audio.Attribution
import com.playtranslate.audio.AudioRequest
import com.playtranslate.audio.AudioSelection
import com.playtranslate.audio.AudioSelections
import com.playtranslate.audio.GameAudioClip
import com.playtranslate.audio.PlayOutcome
import com.playtranslate.audio.sources.RecordingAudioSource
import com.playtranslate.audio.vad.SpeechSnap
import com.playtranslate.audio.vad.VoiceLineSnap
import com.playtranslate.capture.GameAudioSnapshot
import com.playtranslate.language.ReadingOverride
import com.playtranslate.language.SourceLangId
import com.playtranslate.themeColor
import com.playtranslate.tts.ttsTextForWord
import com.playtranslate.vocab.HiddenWordsStore
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
 * Words always ship with the card unless the user hides them with the
 * eye on the word's row. Tapping the row toggles **target** state — target
 * words are highlighted on the rendered card front (the HTML builder
 * reads [selectedWords]) and carry their audio switch inside the row.
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
    /** The initial word rows as full [SentenceAnkiHtmlBuilder.WordEntry]s —
     *  the ATOMIC snapshot the host already holds. [buildArgs] flattens
     *  entries to word/reading/meaning/frequency (the Bundle is also saved
     *  state, where full enrichment is a size hazard), so without this the
     *  build re-derives surfaces/pitch/senses from [LastSentenceCache],
     *  which may have rotated to another sentence. Pass the same list the
     *  args were built from; null (a restore, or a host with no enrichment
     *  to give) falls back to a SENTENCE-GATED cache read. */
    private val initialWords: List<SentenceAnkiHtmlBuilder.WordEntry>? = null,
    /** The card's screenshot (the pin, the crop, the removal), opened and
     *  released by the host from its own args, and shared with a sibling
     *  tab when the host has one ([WordAnkiReviewBinder]); null = none. */
    private val screenshot: AnkiScreenshotState? = null,
) {
    /** The host seam. */
    interface Host {
        /** The host view tree is still live (fragment isAdded / page attached). */
        val isAlive: Boolean

        /** Present the audio-source picker for [intent] and deliver the
         *  picked selection (parse activity extras via [parsePickerResult]).
         *  Never called back on cancel. */
        fun openAudioPicker(intent: Intent, onPicked: (AudioSelection) -> Unit)

        /** Present the screenshot crop editor for [state] (a dialog window
         *  over the sheet, a workspace page); it writes to the state, which
         *  the Screenshot group follows. */
        fun openScreenshotCrop(state: AnkiScreenshotState)

        /** Hold point before the word rows are drawn — the workspace
         *  suspends until its enter animation settles so the rows' page
         *  (the first one of a process blocks the main thread while it
         *  loads) can't drop entrance frames. Default: no hold. Callers
         *  re-check [isAlive] after. */
        suspend fun awaitEnterSettled() {}

        /** A control of the editor took itself off the screen as it was
         *  pressed (the Notes box's X, gone with the note): a host that
         *  draws a controller's cursor of its own moves it to [next], the
         *  view that stands for what was acted on (the Notes field), or
         *  the cursor is left on nothing. Default: nothing to do, for a
         *  host whose window keeps Android's focus, which the control
         *  hands on itself. */
        fun onControlGone(next: View) {}
    }

    private val words = mutableListOf<SentenceAnkiHtmlBuilder.WordEntry>()
    val selectedWords = mutableSetOf<String>()

    /** How the card reads the stretch of the sentence its own word was found
     *  as, when [words] carry that word in place of the analysis's choice
     *  ([withCardTarget]). It arrives and leaves with the list it describes
     *  ([applyWords], an Original edit), is persisted beside it in [args],
     *  and every reader of the sentence's reading here follows it: the card
     *  ([getCardData]), the sentence audio chip and the audio picker. */
    private var readingOverride: ReadingOverride? = null

    /** The current word rows, for a host placing the card's own word into
     *  them ([withCardTarget]) and handing the result back to [applyWords]. */
    fun wordEntries(): List<SentenceAnkiHtmlBuilder.WordEntry> = words.toList()

    /** The word rows: ONE definitions document in [wordsSlot], under the
     *  card's helper row. Made with the first rows, released in [release]. */
    private var wordsDoc: DefinitionDocument? = null

    /** False until the host's entrance has settled. The rows are a page,
     *  and the first page of a process blocks the main thread while the
     *  WebView loads, so they are not drawn inside the entrance. */
    private var wordRowsAllowed = false

    /** The id of each word's row, by its place in [words] as last drawn. */
    private var wordRowIds: List<String> = emptyList()

    /** The line that stands in for the rows while their lookup runs. */
    private var wordsLoadingRow: View? = null

    /** [HiddenWordsStore.revision] the word rows were last built against;
     *  the collector in [buildInto] redraws only when it moves. */
    private var appliedRevision = -1L

    /** True once the current word LIST has been ordered hidden-last against
     *  a loaded set. A fresh list ([buildInto], [applyWords]) resets it, and
     *  [rebuildWordRows] orders once when the set is there; every later
     *  rebuild (a toggle, a load for another reason) leaves the order alone,
     *  so hiding or showing a word never moves its row. */
    private var hiddenOrderApplied = false

    /** Whether the sentence-audio switch is on. Read by the host sheet
     *  at send time; false when the toggle wasn't built. */
    val sentenceAudioEnabled: Boolean
        get() = sentenceAudioHandle?.switch?.isChecked == true

    private lateinit var root: LinearLayout
    private lateinit var etOriginal: EditText
    private lateinit var etTranslation: EditText
    private lateinit var etNotes: EditText
    private lateinit var wordsCard: LinearLayout
    private lateinit var wordsSlot: LinearLayout
    private lateinit var wordsHeaderTitle: TextView
    private var screenshotGroup: AnkiScreenshotGroup? = null
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
     *  added to [selectedWords]. Mutated by the switch on the word's audio
     *  row; pushed back to the pref on every change so the next card
     *  defaults to whatever the user picked last. */
    private val wordAudioEnabled = mutableMapOf<String, Boolean>()

    /** A target word's preview, made on its first tap and kept while the
     *  word has an audio row; [rebuildWordRows] stops and drops the preview
     *  of a word that lost its row. */
    private val wordPlayers = mutableMapOf<String, AnkiAudioPreviewPlayer>()

    /** The name on each target's audio-source pill and the selection it was
     *  read for (the read suspends; [ensureWordSourceLabels] asks once per
     *  selection, and the pill keeps the old name until the new one lands). */
    private val wordSourceLabels = mutableMapOf<String, SourceLabel>()

    private class SourceLabel(val selection: AudioSelection, val text: String)

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
                // The redraw asks for the new source's name.
                if (::wordsCard.isInitialized) rebuildWordRows()
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

    /** Three accent pulses over the cell background ([startAttentionFlash]),
     *  each fading up then back down; the background is cleared on
     *  completion so the cell returns to the card surface. */
    private fun flashGameAudioCell(panel: View) {
        gameAudioFlashAnimator?.cancel()
        val accentRgb = ctx.themeColor(R.attr.ptAccent) and 0x00FFFFFF
        gameAudioFlashAnimator = startAttentionFlash(
            onIntensity = { intensity ->
                val alpha = (0x4D * intensity).toInt() shl 24  // peak ~30% accent
                panel.setBackgroundColor(alpha or accentRgb)
            },
            onEnd = {
                panel.background = null
                gameAudioFlashAnimator = null
            },
        )
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
            AudioSourcePickerActivity.intent(
                ctx, lang, surface, reading, isWord, current,
                // The picker's sentence previews read the line as the card does.
                readingOverride = if (isWord) null else readingOverride,
            ),
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
     *  by the time this fires. Every host wires one; null = no re-fetch
     *  path, in which case Original edits commit without touching
     *  downstream state. */
    var onOriginalCommitted: ((newText: String) -> Unit)? = null

    data class CardData(
        val source: String,
        val target: String,
        val words: List<SentenceAnkiHtmlBuilder.WordEntry>,
        val selectedWords: Set<String>,
        /** The screenshot to attach, with its crop; null = none. */
        val screenshot: ScreenshotAttachment?,
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
        /** The card's own reading of its word's stretch of [source]
         *  ([SentenceSendInput.readingOverride]); null = the analysis's. */
        val readingOverride: ReadingOverride? = null,
        /** The note the user typed for this card, raw; "" for none
         *  ([SentenceSendInput.notes]). */
        val notes: String = "",
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
            screenshot = screenshot?.attachment,
            sourceLangId = SourceLangId.fromCode(args.getString(ARG_SOURCE_LANG)) ?: SourceLangId.JA,
            targetWordAudioWords = enabledTargets,
            // Only include selections for words whose audio is enabled — the
            // send path iterates targetWordAudioWords anyway.
            sentenceSelection = sentenceSelection,
            wordSelections = enabledTargets.associateWith { wordSelections[it] ?: AudioSelection.Auto },
            readingOverride = readingOverride,
            notes = etNotes.text.toString(),
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
        wordsDoc?.release()
        wordsDoc = null
        wordsLoadingRow = null
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
        screenshotGroup?.release()
        screenshotGroup = null
        sentenceAudioHandle?.release()
        sentenceAudioHandle = null
        stopWordPlayers()
    }

    /** Stops every word preview. The map empties first, so a player's last
     *  state change redraws nothing. */
    private fun stopWordPlayers() {
        val players = wordPlayers.values.toList()
        wordPlayers.clear()
        players.forEach { it.stop() }
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

        val original = args.getString(ARG_ORIGINAL) ?: ""
        if (initialWords != null) {
            // The host's atomic snapshot — the rich fields survive as-is,
            // with no dependence on where the global cache points now.
            words.addAll(initialWords)
        } else {
            val wordArr    = args.getStringArray(ARG_WORDS) ?: emptyArray()
            val readingArr = args.getStringArray(ARG_READINGS) ?: emptyArray()
            val meaningArr = args.getStringArray(ARG_MEANINGS) ?: emptyArray()
            val freqArr    = args.getIntArray(ARG_FREQ_SCORES) ?: IntArray(0)
            // Surfaces/enrichment fall back to the cache, GATED on this
            // card's own sentence: a rotated cache yields plain rows, never
            // another sentence's surfaces or senses misattributed to this
            // one. (snapshotFor is atomic; separate field reads could
            // straddle a rotation.)
            val snap = LastSentenceCache.snapshotFor(original)
            val surfaces = snap?.surfaces ?: emptyMap()
            val enrich   = snap?.enrichment ?: emptyMap()
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
        }

        // The override persisted beside the rows a fill applied (a restore);
        // none on a fresh build, whose rows arrive later through applyWords.
        readingOverride = args.getReadingOverride()

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
        // A fresh list: hidden words go last once the set is loaded
        // (rebuildWordRows orders it; a late load re-orders through the
        // revision collector below).
        hiddenOrderApplied = false

        val translation = args.getString(ARG_TRANSLATION) ?: ""
        buildContent(original, translation)

        // The word rows are drawn once the host's entrance has settled (the
        // workspace's; a sheet host holds nothing and they draw now).
        scope.launch {
            host.awaitEnterSettled()
            if (!host.isAlive || !::wordsCard.isInitialized) return@launch
            wordRowsAllowed = true
            rebuildWordRows()
        }

        // Hidden-word changes from anywhere — this card's own eye taps, the
        // results list on the other screen, a store load that lands after
        // the rows were drawn — redraw the rows. appliedRevision (recorded
        // in rebuildWordRows) skips the StateFlow's replay of the revision
        // the rows were already built against.
        scope.launch {
            HiddenWordsStore.revision.collect { rev ->
                if (rev != appliedRevision && host.isAlive && ::wordsCard.isInitialized) {
                    rebuildWordRows()
                }
            }
        }

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

    private fun buildContent(original: String, translation: String) {
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
        etOriginal = ankiEditField(ctx, initial = original).apply {
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
            // Multi-source: Auto resolves Commons-first, then the language's
            // voice; the resolver applies the kana spoken-form for JA itself.
            // Commons has no sentence recordings, so the sentence cell is
            // effectively spoken by that voice (a voice service's when one
            // is chosen, else the device's).
            selection = { sentenceSelection },
            audioRequest = { AudioRequest.sentence(etOriginal.text.toString(), lang, readingOverride) },
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
        etTranslation = ankiEditField(ctx, initial = translation).apply {
            id = R.id.etAnkiTranslation
            if (translation.isBlank()) {
                hint = ctx.getString(R.string.status_translating)
                setHintTextColor(ctx.themeColor(R.attr.ptTextMuted))
            }
        }
        attachTranslationTouchWatcher(etTranslation)
        translationCard.addView(buildEditableFrame(etTranslation))

        // Notes, under the translation: the user's own note for this card,
        // starting with what the opener had to say of its words
        // ([putAnkiNotes]), empty with nothing.
        etNotes = addAnkiNotesSection(root, R.id.etAnkiSentenceNotes, args.getAnkiNotes(), host::onControlGone)

        // Words on card. The host tells us whether a follow-up
        // `applyWords` call is coming (a lazy words fill: every host
        // handed no words for a fresh, non-blank sentence) vs. whether
        // an empty list is the final answer (no fill coming, e.g. a
        // blank sentence). Inferring "loading" from `words.isEmpty()`
        // would mis-render the latter as a permanent placeholder over
        // a zero-word card.
        wordsLoading = args.getBoolean(ARG_WORDS_LOADING, false)
        ankiGroupHeader(root, ctx.getString(R.string.anki_group_words_count, words.size))
        wordsHeaderTitle = (root.getChildAt(root.childCount - 1) as ViewGroup)
            .findViewById(R.id.tvGroupTitle)
        wordsCard = ankiGroupCard(root)
        addWordsHelperRow(wordsCard)
        // A rebuild into a new card starts a new page.
        wordsDoc?.release()
        wordsDoc = null
        wordsLoadingRow = null
        wordsSlot = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        wordsCard.addView(
            wordsSlot,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        rebuildWordRows()

        // Screenshot — the group follows the shared state: a removal in
        // either tab takes it down, a crop set in either shows here.
        screenshot?.let { s ->
            screenshotGroup = addAnkiScreenshotGroup(root, ctx, scope, s) { host.openScreenshotCrop(s) }
        }
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

    private fun addWordsHelperRow(card: LinearLayout) {
        val density = ctx.resources.displayMetrics.density
        card.addView(TextView(ctx).apply {
            text = inlineIconString(ctx, R.string.anki_words_helper_hide, R.drawable.ic_visibility)
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

    // ── Word rows ────────────────────────────────────────────────────────

    /**
     * Redraws the word rows from the card's state: the list, the targets,
     * the hidden set, each target's audio. Everything that changes a row
     * ends here; the document patches the rows whose value changed, so a
     * target toggle, a hide or a preview's spinner never rebuilds the list.
     */
    private fun rebuildWordRows() {
        val lang = SourceLangId.fromCode(args.getString(ARG_SOURCE_LANG))
            ?: SourceLangId.JA
        // Revision BEFORE snapshot: the store swaps the set and THEN bumps
        // the revision, so a change landing between these two reads leaves
        // appliedRevision behind and the collector in buildInto redraws.
        // The other order could record the new revision against the old
        // set and never redraw.
        appliedRevision = HiddenWordsStore.revision.value
        // isLoaded BEFORE snapshot, for the same reason: true here means the
        // snapshot taken next is the loaded set.
        val loaded = HiddenWordsStore.isLoaded(lang)
        val hidden = HiddenWordsStore.snapshot(ctx, lang)
        if (loaded && !hiddenOrderApplied) {
            // The list's one ordering pass: hidden words last, targets
            // exempt (a lens target whose un-hide hasn't landed stays on
            // top). Stable, so each group keeps its lookup order.
            hiddenOrderApplied = true
            val sorted = words.sortedBy { it.word in hidden && it.word !in selectedWords }
            words.clear()
            words.addAll(sorted)
        }

        val loading = words.isEmpty() && wordsLoading
        if (loading && wordsLoadingRow == null) {
            wordsLoadingRow = buildWordsLoadingRow().also { wordsSlot.addView(it, 0) }
        } else if (!loading) {
            wordsLoadingRow?.let { wordsSlot.removeView(it) }
            wordsLoadingRow = null
        }

        // A word that is no longer a target has no audio row: without this,
        // its preview would keep playing for a beat after the row is gone.
        wordPlayers.keys.filter { it !in selectedWords }.forEach { wordPlayers.remove(it)?.stop() }

        if (wordRowsAllowed) {
            wordRowIds = wordRowIdsOf(words)
            val rows = words.mapIndexed { i, entry ->
                // A TARGET is never a stub, hidden in the store or not: the
                // user is making a card for it (a lens target, a row they
                // tapped), the send funnel exempts it, and the send un-hides
                // it once the card lands. Un-targeting a store-hidden word
                // returns it to its stub.
                wordRow(wordRowIds[i], entry, hidden = entry.word in hidden && entry.word !in selectedWords)
            }
            // No page is loaded for a list that has no rows yet.
            if (rows.isNotEmpty() || wordsDoc != null) {
                val doc = wordsDoc ?: DefinitionDocument(
                    ctx, wordsSlot, scope,
                    theme = { cardDocTheme(ctx, WORD_DEFINITIONS_PX) },
                    onAction = ::onWordRowAction,
                ).also { wordsDoc = it }
                doc.setRows(rows, lang.yomitanConsumingLang())
            }
        }
        // Live count in the group header: the words that will be on the card.
        // As written; the header style capitalizes it in the UI's language.
        wordsHeaderTitle.text = ctx.getString(
            R.string.anki_group_words_count,
            words.count { it.word !in hidden || it.word in selectedWords },
        )
        ensureWordSourceLabels(lang)
    }

    /** [entry] as its row: a stub when [hidden], else the full row, with its
     *  audio row while it is a target. */
    private fun wordRow(id: String, entry: SentenceAnkiHtmlBuilder.WordEntry, hidden: Boolean): DefRow.SheetWord {
        val target = entry.word in selectedWords
        return DefRow.SheetWord(
            id = id,
            word = entry.word,
            reading = entry.reading,
            pitch = entry.pitch,
            stars = if (entry.freqScore > 0) SentenceAnkiHtmlBuilder.starsString(entry.freqScore) else "",
            target = target,
            hidden = hidden,
            meaning = entry.meaning,
            importedGroups = importedGroupsFromSenses(entry.senses.filter { it.imported }),
            audio = if (target && !hidden) wordAudio(entry.word) else null,
        )
    }

    /**
     * A target's audio row as it stands. The switch and the source live in
     * [wordAudioEnabled] and [wordSelections], seeded when the word first
     * becomes a target, so every redraw shows the user's choices.
     */
    private fun wordAudio(word: String): DefRow.Audio {
        val on = wordAudioEnabled.getOrPut(word) { Prefs(ctx).ankiWordAudioEnabled }
        // Per-word selection defaults to Auto (Commons-first → TTS).
        wordSelections.getOrPut(word) { AudioSelection.Auto }
        return DefRow.Audio(
            on = on,
            play = when (wordPlayers[word]?.state) {
                AnkiAudioPreviewPlayer.State.LOADING -> DefRow.Play.LOADING
                AnkiAudioPreviewPlayer.State.PLAYING -> DefRow.Play.PLAYING
                else -> DefRow.Play.IDLE
            },
            source = wordSourceLabels[word]?.text.orEmpty(),
        )
    }

    /** Reads the pill's label for every target whose selection it was not
     *  read for yet, and redraws when one lands. */
    private fun ensureWordSourceLabels(lang: SourceLangId) {
        for (word in selectedWords.toList()) {
            val selection = wordSelections[word] ?: continue
            val had = wordSourceLabels[word]
            if (had?.selection == selection) continue
            // Marked as asked for, under the name the pill shows meanwhile.
            wordSourceLabels[word] = SourceLabel(selection, had?.text.orEmpty())
            scope.launch {
                val label = AudioSelections.label(ctx, selection, lang)
                if (!host.isAlive || !::wordsCard.isInitialized) return@launch
                val now = wordSourceLabels[word]
                if (now?.selection != selection || now.text == label) return@launch
                wordSourceLabels[word] = SourceLabel(selection, label)
                rebuildWordRows()
            }
        }
    }

    /** [word]'s preview, made on first use. It redraws the row on every
     *  state change for as long as it is the word's player. */
    private fun wordPlayer(entry: SentenceAnkiHtmlBuilder.WordEntry): AnkiAudioPreviewPlayer {
        wordPlayers[entry.word]?.let { return it }
        val lang = SourceLangId.fromCode(args.getString(ARG_SOURCE_LANG)) ?: SourceLangId.JA
        val word = entry.word
        val reading = entry.reading
        var player: AnkiAudioPreviewPlayer? = null
        player = AnkiAudioPreviewPlayer(
            ctx, scope, lang,
            // Preview the kana reading (JA) so the audition matches the
            // audio the card will carry (see ttsTextForWord).
            previewText = { ttsTextForWord(word, reading.ifBlank { null }, lang) },
            selectionProvider = { wordSelections[word] ?: AudioSelection.Auto },
            requestProvider = { AudioRequest.word(word, reading.ifBlank { null }, lang) },
            onState = {
                if (wordPlayers[word] === player && host.isAlive && ::wordsCard.isInitialized) rebuildWordRows()
            },
        )
        wordPlayers[word] = player
        return player
    }

    /**
     * A tap in the word rows, as the page reports it. It arrives after the
     * tap (it crosses the page's bridge), so each branch reads the card's
     * state as it is now, and [row] is the row as it is now drawn.
     */
    private fun onWordRowAction(row: DefRow, action: String) {
        val shown = row as? DefRow.SheetWord ?: return
        val entry = words.getOrNull(wordRowIds.indexOf(row.id)) ?: return
        val word = entry.word
        when (action) {
            // The row toggles target. A stub's body is inert: a hidden word
            // is never highlighted on the card.
            DefAction.TARGET -> if (!shown.hidden) {
                if (!selectedWords.remove(word)) selectedWords.add(word)
                rebuildWordRows()
            }
            DefAction.TRAILING -> if (shown.hidden) showWord(entry) else hideWord(entry)
            // The audio row is its switch.
            DefAction.AUDIO -> if (word in selectedWords) {
                val checked = wordAudioEnabled[word] != true
                wordAudioEnabled[word] = checked
                // Mirror the existing sentence-audio pref semantics: the
                // last value the user picks becomes the default for the
                // next card.
                Prefs(ctx).ankiWordAudioEnabled = checked
                // Turning the switch off also stops the preview.
                val playing = wordPlayers[word]?.takeIf { it.state != AnkiAudioPreviewPlayer.State.IDLE }
                if (!checked && playing != null) playing.stop() else rebuildWordRows()
            }
            DefAction.SPEAK -> if (word in selectedWords) wordPlayer(entry).toggle()
            DefAction.SOURCE -> if (word in selectedWords) {
                launchAudioPicker(PickTarget.Word(word), wordSelections[word] ?: AudioSelection.Auto)
            }
        }
    }

    /**
     * The eye on a full row: hide the word, everywhere and for good (where
     * the per-card ✕ used to be: hiding is persisted across cards and
     * sentences, and the row stays as a stub the user can tap back).
     */
    private fun hideWord(entry: SentenceAnkiHtmlBuilder.WordEntry) {
        // Hiding un-targets: a hidden word is never highlighted on the
        // card, and its audio state goes with the target. Untap-to-
        // deselect (the row's own tap) leaves the audio slots in place;
        // only hiding drops them. Each removal is captured so a failed
        // write can put the row back exactly as it was: the toast promises
        // nothing changed, and that must hold for the card's own state, not
        // only the store's.
        val wasTarget = selectedWords.remove(entry.word)
        val audioEnabled = wordAudioEnabled.remove(entry.word)
        val audioSelection = wordSelections.remove(entry.word)
        wordSourceLabels.remove(entry.word)
        // Redraw now for the un-target. The store's revision redraws
        // again when the write lands, but a word ALREADY hidden in the
        // store (a target the sheet was showing in full) makes that
        // write a no-op with no bump, so this rebuild is the only one.
        rebuildWordRows()
        val lang = SourceLangId.fromCode(args.getString(ARG_SOURCE_LANG)) ?: SourceLangId.JA
        scope.launch {
            if (!HiddenWordsStore.setHidden(ctx, lang, entry.word, entry.reading, hidden = true)) {
                if (wasTarget) selectedWords.add(entry.word)
                audioEnabled?.let { wordAudioEnabled[entry.word] = it }
                audioSelection?.let { wordSelections[entry.word] = it }
                if (host.isAlive && ::wordsCard.isInitialized) rebuildWordRows()
                toastHiddenWordSaveFailed()
            }
        }
    }

    /** The eye-off on a stub: show the word again. The store's revision
     *  redraws the row when the write lands. */
    private fun showWord(entry: SentenceAnkiHtmlBuilder.WordEntry) {
        val lang = SourceLangId.fromCode(args.getString(ARG_SOURCE_LANG)) ?: SourceLangId.JA
        scope.launch {
            if (!HiddenWordsStore.setHidden(ctx, lang, entry.word, entry.reading, hidden = false)) {
                toastHiddenWordSaveFailed()
            }
        }
    }

    /** The word rows as last drawn, top to bottom. */
    @get:VisibleForTesting
    internal val shownWordRows: List<DefRow.SheetWord>
        get() = wordsDoc?.shownRows.orEmpty().filterIsInstance<DefRow.SheetWord>()

    /** A tap the page would report on [word]'s row. */
    @VisibleForTesting
    internal fun tapWordRowForTest(word: String, action: String) {
        shownWordRows.firstOrNull { it.word == word }?.let { onWordRowAction(it, action) }
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

    /** The store could not persist a hide/show tap (disk full, unopenable
     *  file); the rows did not change, so the user must hear why. */
    private fun toastHiddenWordSaveFailed() {
        if (!host.isAlive) return
        Toast.makeText(ctx, R.string.hidden_word_save_failed, Toast.LENGTH_SHORT).show()
    }

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
     *
     * The Notes box follows the same rule: a note still exactly as the
     * opener left it goes with the sentence it was about, and one the
     * user changed stays ([dropNoteOfEditedSentence]).
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
            args.putString(ARG_TRANSLATION, "")
        }

        // Notes: the opener's, untouched, was about the sentence just
        // edited away.
        dropNoteOfEditedSentence(args, if (::etNotes.isInitialized) etNotes else null)

        // Words: every per-word piece of state is keyed by surface form,
        // so a new sentence invalidates all of it — selections, per-word
        // audio toggles, per-word voice picks, and any in-flight preview,
        // which stops with the row it was playing for.
        selectedWords.clear()
        wordAudioEnabled.clear()
        stopWordPlayers()
        wordSelections.clear()
        wordSourceLabels.clear()
        words.clear()
        // It described the rows just cleared; the new sentence's fill brings
        // its own.
        readingOverride = null
        wordsLoading = true
        rebuildWordRows()

        // The edit is the card's state now, in the args a saved-state
        // restore rebuilds from: its text, no rows and no reading override
        // until the fill lands, so the restore owes that fill
        // ([wordsFillOwed]) for this text ([originalOf]), and a cleared
        // translation (above) is owed too. Left alone, a restore before the
        // fill landed showed the edit beside the old sentence's rows.
        args.putString(ARG_ORIGINAL, newText)
        args.remove(ARG_WORDS)
        args.remove(ARG_READINGS)
        args.remove(ARG_MEANINGS)
        args.remove(ARG_FREQ_SCORES)
        args.putBoolean(ARG_WORDS_LOADING, true)
        args.putReadingOverride(null)

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
     * [applyTranslation]'s guard. [readingOverride] is the one [entries]
     * came with ([withCardTarget]), and replaces the previous list's.
     */
    fun applyWords(
        forOriginal: String,
        entries: List<SentenceAnkiHtmlBuilder.WordEntry>,
        targetWord: String?,
        readingOverride: ReadingOverride? = null,
    ) {
        if (!::wordsCard.isInitialized) return
        if (forOriginal != etOriginal.text.toString()) return
        wordsLoading = false
        words.clear()
        words.addAll(entries)
        this.readingOverride = readingOverride
        args.putReadingOverride(readingOverride)
        if (targetWord != null && words.any { it.word == targetWord }) {
            selectedWords.add(targetWord)
        }
        if (selectedWords.isNotEmpty()) {
            val sorted = words.sortedByDescending { it.word in selectedWords }
            words.clear()
            words.addAll(sorted)
        }
        // A new list: it gets its hidden-last ordering in rebuildWordRows.
        hiddenOrderApplied = false
        args.putStringArray(ARG_WORDS, words.map { it.word }.toTypedArray())
        args.putStringArray(ARG_READINGS, words.map { it.reading }.toTypedArray())
        args.putStringArray(ARG_MEANINGS, words.map { it.meaning }.toTypedArray())
        args.putIntArray(ARG_FREQ_SCORES, words.map { it.freqScore }.toIntArray())
        // The fill has landed: a restore from these args must not show the
        // placeholder again (a definitive empty list stayed "Looking up
        // words…" forever).
        args.putBoolean(ARG_WORDS_LOADING, false)
        rebuildWordRows()
    }

    companion object {
        /** A game-audio snapshot shorter than this isn't a usable voice line;
         *  the cell stays on the TTS floor rather than offer an untrimmable clip. */
        private const val MIN_GAME_AUDIO_MS = 500L

        /** The body text of a word row's imported definitions, in sp; the
         *  rest of a row carries its own sizes. */
        private const val WORD_DEFINITIONS_PX = 13f

        /** A row id per word: the word and its reading, which every redraw
         *  of one list repeats (so it patches), with a counter for a list
         *  that holds the same pair twice. */
        internal fun wordRowIdsOf(words: List<SentenceAnkiHtmlBuilder.WordEntry>): List<String> {
            val seen = HashMap<String, Int>()
            return words.map { w ->
                val key = w.word + "\u0001" + w.reading
                val n = seen.merge(key, 1, Int::plus)!!
                if (n == 1) key else "$key\u0001$n"
            }
        }

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
        private const val ARG_TARGET_WORD     = "target_word"
        private const val ARG_SOURCE_LANG     = "source_lang"
        private const val ARG_WORDS_LOADING   = "words_loading"
        private const val ARG_AUDIO_ANCHOR_MS = "audio_anchor_ms"

        /** A words fill was in flight when [args] were saved: a restore owes
         *  it. [applyWords] clears the flag, so a landed fill never reads as owed. */
        fun wordsFillOwed(args: Bundle): Boolean =
            args.getBoolean(ARG_WORDS_LOADING, false) && args.getStringArray(ARG_WORDS).isNullOrEmpty()

        /** The sentence a content built from [args] shows: the launch
         *  sentence, or the last Original edit committed over it. A restore
         *  resumes its fills for this text, never the launch sentence the
         *  host remembers. */
        fun originalOf(args: Bundle): String = args.getString(ARG_ORIGINAL).orEmpty()

        /** The translation a content built from [args] shows: blank while
         *  one is owed (none at launch, or an edit cleared it). */
        fun translationOf(args: Bundle): String = args.getString(ARG_TRANSLATION).orEmpty()

        /** The launch-state bundle both hosts build — the fragment stores it
         *  as its arguments (so its mutation semantics are unchanged), the
         *  workspace page passes it straight to the view. */
        fun buildArgs(
            japanese: String,
            translation: String,
            words: List<SentenceAnkiHtmlBuilder.WordEntry>,
            targetWord: String? = null,
            sourceLangId: SourceLangId = SourceLangId.JA,
            wordsLoading: Boolean = false,
            audioAnchorMs: Long? = null,
            /** The text the Notes box starts with ([putAnkiNotes]). */
            notes: String = "",
        ): Bundle = Bundle().apply {
            putAnkiNotes(notes)
            putString(ARG_ORIGINAL, japanese)
            putString(ARG_TRANSLATION, translation)
            putStringArray(ARG_WORDS, words.map { it.word }.toTypedArray())
            putStringArray(ARG_READINGS, words.map { it.reading }.toTypedArray())
            putStringArray(ARG_MEANINGS, words.map { it.meaning }.toTypedArray())
            putIntArray(ARG_FREQ_SCORES, words.map { it.freqScore }.toIntArray())
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
