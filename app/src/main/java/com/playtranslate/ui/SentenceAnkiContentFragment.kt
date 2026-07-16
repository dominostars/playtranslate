package com.playtranslate.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.app.Activity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
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
import com.playtranslate.capture.GameAudioSnapshot
import com.playtranslate.dictionary.Deinflector
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.themeColor
import com.playtranslate.tts.ttsTextForWord
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Sentence-card content for Anki review (Original, Translation, Words,
 * Screenshot). Embedded by [AnkiReviewBottomSheet] (sentence-only) and
 * the Sentence side of [WordAnkiReviewSheet]. Each section renders as a
 * grouped MaterialCardView with the design-system header on top, matching
 * the Settings / Word Detail rhythm.
 *
 * Words always ship with the card unless the user removes them via the
 * row's `×` glyph. Tapping the row toggles **target** state — target
 * words are highlighted on the rendered card front (the HTML builder
 * reads [selectedWords]). The target carries no "Target" label in the
 * row UI; the row just tints accent and the word text re-colours.
 */
class SentenceAnkiContentFragment : Fragment() {

    private val words = mutableListOf<SentenceAnkiHtmlBuilder.WordEntry>()
    val selectedWords = mutableSetOf<String>()
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

    /** THIS card's snapshot — a unique immutable file this fragment owns and
     *  deletes on provably-final teardown (see onDestroyView: finishing
     *  activity, or dismissal with no saved state). Other cards snapshot to
     *  their own files, so nothing external can invalidate this one (the
     *  churn-bug class fix). */
    private var gameAudioSnapshotFile: File? = null

    /** The snapshot file the panel last loaded — reload guard. */
    private var gameAudioLoadedFile: File? = null
    private var inlinePlayer: PcmAudioTrackPlayer? = null
    private var inlinePlaying = false
    /** The chip's suspended await while inline playback runs — resumed on
     *  natural completion AND by [stopInlinePlayback] (a handle drag), so
     *  the chip always returns to idle. */
    private var inlineCont: CancellableContinuation<Unit>? = null

    /** True once the user has interacted with the game-audio selection —
     *  dragged a handle, played it (listening counts as review), or been
     *  through the full editor. Reviewed audio sends directly; unreviewed
     *  audio still gets the save-time editor as the safety net. */
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

    /** Identifies the cell that the active picker launch was for.
     *  Cleared when the result lands. */
    private sealed interface PickTarget {
        data object Sentence : PickTarget
        data class Word(val word: String) : PickTarget
    }
    private var pendingPick: PickTarget? = null

    private val audioPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val target = pendingPick.also { pendingPick = null }
            ?: return@registerForActivityResult
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val src = result.data?.getStringExtra(AudioSourcePickerActivity.EXTRA_PICKED_SOURCE)
        val key = result.data?.getStringExtra(AudioSourcePickerActivity.EXTRA_PICKED_KEY)
        val locator = result.data?.getStringExtra(AudioSourcePickerActivity.EXTRA_PICKED_LOCATOR)
        val attribution = result.data?.getStringExtra(AudioSourcePickerActivity.EXTRA_PICKED_ATTRIBUTION)
            ?.let { runCatching { PtJson.lenient.decodeFromString(Attribution.serializer(), it) }.getOrNull() }
        val selection = if (src != null && key != null) {
            AudioSelection.Explicit(src, key, locator, attribution)
        } else {
            AudioSelection.Auto
        }
        val lang = SourceLangId.fromCode(arguments?.getString(ARG_SOURCE_LANG))
            ?: SourceLangId.JA
        when (target) {
            is PickTarget.Sentence -> {
                sentenceSelection = selection
                sentenceAudioHandle?.refreshPillLabel(this, lang, selection)
                refreshSentenceAudioTitle()
                // A pick of "Game audio" arrives rangeless — the panel load
                // commits the default range; any other source hides the panel.
                updateGameAudioPanel()
            }
            is PickTarget.Word -> {
                wordSelections[target.word] = selection
                wordAudioHandles[target.word]?.refreshPillLabel(this, lang, selection)
            }
        }
    }

    /** Non-null while the save-time trim editor is up; resumed by its result. */
    private var trimContinuation: CancellableContinuation<Boolean>? = null

    /** Shared by the save-time gate and the panel's "Open editor" button —
     *  the launcher applies the result either way; the continuation exists
     *  only in gate mode. */
    private val trimEditorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cont = trimContinuation.also { trimContinuation = null }
        val proceed = applyTrimEditorResult(result.resultCode, result.data)
        cont?.resume(proceed)
    }

    /** Returns whether a pending send should proceed. Any RESULT_OK action
     *  counts as review — the user saw the editor and chose. */
    private fun applyTrimEditorResult(resultCode: Int, data: android.content.Intent?): Boolean {
        val action = if (resultCode == Activity.RESULT_OK) {
            data?.getStringExtra(GameAudioTrimActivity.EXTRA_ACTION)
        } else {
            null
        }
        val lang = SourceLangId.fromCode(arguments?.getString(ARG_SOURCE_LANG))
            ?: SourceLangId.JA
        return when (action) {
            GameAudioTrimActivity.ACTION_TRIM -> {
                val s = data?.getLongExtra(GameAudioTrimActivity.EXTRA_START_MS, -1L) ?: -1L
                val e = data?.getLongExtra(GameAudioTrimActivity.EXTRA_END_MS, -1L) ?: -1L
                val wav = gameAudioSnapshotFile
                if (wav != null && s >= 0 && e > s) {
                    stopInlinePlayback()
                    sentenceSelection = RecordingAudioSource.committedSelection(wav, s, e)
                    gameAudioReviewed = true
                    sentenceAudioHandle?.refreshPillLabel(this, lang, sentenceSelection)
                    refreshSentenceAudioTitle()
                    gameAudioWave?.setSelection(s, e)
                    true
                } else {
                    false
                }
            }
            GameAudioTrimActivity.ACTION_TTS -> {
                sentenceSelection = AudioSelection.Auto
                gameAudioReviewed = true
                sentenceAudioHandle?.refreshPillLabel(this, lang, sentenceSelection)
                refreshSentenceAudioTitle()
                updateGameAudioPanel()
                true
            }
            GameAudioTrimActivity.ACTION_NONE -> {
                // Switch off ⇒ sentenceAudioEnabled false ⇒ the gate passes
                // and the send simply carries no sentence audio.
                gameAudioReviewed = true
                sentenceAudioHandle?.switch?.isChecked = false
                true
            }
            else -> false // back/cancel — abort a pending send, change nothing
        }
    }

    /**
     * Save-time gate: game audio the user never reviewed (no handle drag, no
     * play, no editor visit) opens the trim editor once, seeded with the
     * current selection. Reviewed audio — the normal case with the in-card
     * panel — sends directly. Returns false when the user backed out.
     */
    suspend fun resolveGameAudioForSend(): Boolean {
        if (!sentenceAudioEnabled) return true
        val sel = sentenceSelection
        if (sel !is AudioSelection.Explicit || sel.sourceId != RecordingAudioSource.ID) return true
        val ctx = context ?: return true
        val wav = gameAudioSnapshotFile
        if (wav == null || !GameAudioSnapshot.isUsable(wav)) {
            // Under immutable per-card ownership the buffer only disappears
            // to an OS cache purge. Nothing to trim; the send path surfaces
            // "audio missing" honestly.
            return true
        }
        // The snapshot can't be clobbered by other cards anymore, so this
        // check reduces to "was a range reviewed" plus the purge backstop.
        val validRange = RecordingAudioSource.parseRangeFor(sel.key, wav)
        if (gameAudioReviewed && validRange != null) return true
        gameAudioReviewed = false
        return suspendCancellableCoroutine { cont ->
            trimContinuation = cont
            cont.invokeOnCancellation { trimContinuation = null }
            trimEditorLauncher.launch(
                GameAudioTrimActivity.intent(
                    ctx,
                    wav.absolutePath,
                    initialStartMs = validRange?.first ?: -1L,
                    initialEndMs = validRange?.second ?: -1L,
                ),
            )
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
                    getString(R.string.anki_game_audio_cell_untrimmed)
                } else {
                    // Same readout as the trim editor; the pill next to it
                    // already names the source. The max() covers the rare
                    // editor-return-before-panel-load case (duration 0).
                    getString(
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
        val ctx = context ?: return
        val lang = SourceLangId.fromCode(arguments?.getString(ARG_SOURCE_LANG))
            ?: SourceLangId.JA
        pendingPick = target
        val (surface, reading, isWord) = when (target) {
            is PickTarget.Sentence -> Triple(etOriginal.text.toString(), null, false)
            is PickTarget.Word ->
                Triple(target.word, words.firstOrNull { it.word == target.word }?.reading, true)
        }
        audioPickerLauncher.launch(
            AudioSourcePickerActivity.intent(ctx, lang, surface, reading, isWord, current),
        )
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
     *  fresh translation + word lookup pipeline; the fragment has
     *  already reset the Translation field and the Words card to a
     *  loading state by the time this fires. null = no re-fetch path
     *  wired (the sentence-only sheet doesn't have one), in which case
     *  Original edits commit without touching downstream state. */
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
            screenshotPath = if (includePhoto) arguments?.getString(ARG_SCREENSHOT_PATH) else null,
            sourceLangId = SourceLangId.fromCode(arguments?.getString(ARG_SOURCE_LANG)) ?: SourceLangId.JA,
            targetWordAudioWords = enabledTargets,
            // Only include selections for words whose audio is enabled — the
            // send path iterates targetWordAudioWords anyway.
            sentenceSelection = sentenceSelection,
            wordSelections = enabledTargets.associateWith { wordSelections[it] ?: AudioSelection.Auto },
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_sentence_anki_content, container, false)

    override fun onResume() {
        super.onResume()
        // This card's buffer is the "active" snapshot again — the audio
        // picker and trim-editor fallback resolve Game audio against it.
        gameAudioSnapshotFile?.let { GameAudioSnapshot.active = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Game-audio state survives process death AND saved-state destroys:
        // once this bundle exists onDestroyView keeps the file (isStateSaved
        // gate), and process death never runs onDestroyView at all — either
        // way the path + selection key + review flag fully reconstruct the
        // cell. A trimmed clip must not silently degrade to Auto/TTS on
        // restore (adversarial-review finding).
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
        val lang = SourceLangId.fromCode(arguments?.getString(ARG_SOURCE_LANG))
            ?: SourceLangId.JA
        sentenceAudioHandle?.refreshPillLabel(this, lang, sentenceSelection)
        refreshSentenceAudioTitle()
        // Reloads the waveform; parseRangeFor re-validates the restored key
        // against the file (untouched across death ⇒ range preserved).
        updateGameAudioPanel()
    }

    override fun onDestroyView() {
        stopInlinePlayback()
        inlinePlayer = null
        gameAudioPanel = null
        gameAudioWave = null
        gameAudioLoadedFile = null
        // We own this card's snapshot. Delete it only on provably-final
        // teardown: the activity is finishing (finished activities are never
        // restored), or the teardown ran with no state saved AND the host
        // not even stopped (plain dismissal while resumed — no bundle exists
        // that could recreate this card). Everything else is a potential
        // saved-state destroy — the activity released while stopped behind
        // the trim editor / audio picker, memory pressure, don't-keep-
        // activities — and MUST keep the file: the just-saved bundle
        // references it and the restored fragment re-owns it. Both clauses
        // matter: isStateSaved alone is not "a bundle exists" (FragmentManager
        // reports true for a merely-stopped host, so finish-from-stopped
        // would leak every file to the sweep), and isFinishing alone misses
        // resumed-state dismissal. Process death skips this method entirely;
        // a restore that never happens is the orphan sweep's job. Don't
        // replace this with a hand-tracked flag or an isChangingConfigurations
        // proxy — those enumerate single recreation paths and re-open the
        // deleted-snapshot-on-restore hole.
        gameAudioSnapshotFile?.let { f ->
            if (GameAudioSnapshot.active == f) GameAudioSnapshot.active = null
            if (activity?.isFinishing == true || !isStateSaved) f.delete()
        }
        gameAudioSnapshotFile = null
        ivPhoto?.setImageBitmap(null)
        ivPhoto = null
        sentenceAudioHandle?.release()
        sentenceAudioHandle = null
        wordAudioHandles.values.forEach { it.release() }
        wordAudioHandles.clear()
        super.onDestroyView()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        root = view as LinearLayout
        val args = arguments ?: return

        // The hosting Anki dialog locks orientation, so onViewCreated
        // only runs once per fragment open. Defensive clears stay
        // because the model collections are class-level fields — if
        // anything ever does cause a re-attach we don't want to
        // accumulate duplicates.
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

        // Freeze the rolling game-audio buffer for THIS card the moment the
        // flow opens ("snapshot at card-open"). One-shot: never on restore,
        // so a post-process-death recreation can't clobber a good snapshot
        // with post-death silence. When a snapshot lands, the cell defaults
        // to provisional Game audio — the trim commits at save via
        // [resolveGameAudioForSend].
        if (savedInstanceState != null) {
            restoreGameAudioState(savedInstanceState)
        } else if (Prefs(requireContext()).recordGameAudio) {
            viewLifecycleOwner.lifecycleScope.launch {
                val snap = withContext(Dispatchers.IO) {
                    CaptureService.instance?.gameAudioRecorder?.snapshotToFile()
                }
                if (snap == null) return@launch
                if (!isAdded) {
                    // Flow died while snapshotting — we own the file; reap it.
                    snap.delete()
                    return@launch
                }
                gameAudioSnapshotFile = snap
                GameAudioSnapshot.active = snap
                sentenceSelection = RecordingAudioSource.provisionalSelection(snap)
                val lang = SourceLangId.fromCode(arguments?.getString(ARG_SOURCE_LANG))
                    ?: SourceLangId.JA
                sentenceAudioHandle?.refreshPillLabel(
                    this@SentenceAnkiContentFragment, lang, sentenceSelection,
                )
                // The panel load commits the default range (last few seconds)
                // and shows the inline editor, expanded by default.
                updateGameAudioPanel()
            }
        }
    }

    // ── In-card game-audio panel ─────────────────────────────────────────

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
            // Already loaded (e.g. the user switched to TTS and back). A
            // re-pick arrives RANGELESS — re-commit from the wave's current
            // selection, or Save-after-play would ship a key that toFile
            // resolves to no audio at all.
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
        viewLifecycleOwner.lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val durationMs = GameAudioClip.durationMs(wav)
                if (durationMs < 500) return@withContext null
                val rate = GameAudioClip.sampleRate(wav)
                val pcm = GameAudioClip.readPcmRange(wav, 0, durationMs)
                Triple(durationMs, rate, rmsBucketsForStrip(pcm, rate))
            }
            if (!isAdded) return@launch
            // UI-race guard: the user may have switched source while the IO
            // ran. (The snapshot itself is immutable and fragment-owned —
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
            val start = existing?.first ?: (durationMs - 5_000L).coerceAtLeast(0)
            val end = existing?.second ?: durationMs
            if (existing == null) {
                sentenceSelection = RecordingAudioSource.committedSelection(wav, start, end)
                // A freshly-defaulted range hasn't been seen by the user.
                gameAudioReviewed = false
            }
            gameAudioWave?.setData(buckets, 50L, durationMs, start, end)
            refreshSentenceAudioTitle()
            gameAudioPanel?.visibility = View.VISIBLE
        }
    }

    /** Per-bucket RMS normalized to the loudest bucket (50 ms buckets). */
    private fun rmsBucketsForStrip(pcm: ShortArray, rate: Int): FloatArray {
        val bucketFrames = (rate / 20).coerceAtLeast(1)
        val out = FloatArray((pcm.size + bucketFrames - 1) / bucketFrames)
        var maxRms = 0f
        for (b in out.indices) {
            val from = b * bucketFrames
            val to = minOf(from + bucketFrames, pcm.size)
            var sumSq = 0.0
            for (i in from until to) {
                val s = pcm[i].toDouble()
                sumSq += s * s
            }
            val rms = (kotlin.math.sqrt(sumSq / (to - from)) / Short.MAX_VALUE).toFloat()
            out[b] = rms
            if (rms > maxRms) maxRms = rms
        }
        if (maxRms > 0f) for (b in out.indices) out[b] = (out[b] / maxRms).coerceAtMost(1f)
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
        // This fragment's own immutable snapshot — churn from other cards is
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
        val ctx = requireContext()
        root.removeAllViews()

        val prefs = Prefs(ctx)
        val lang = SourceLangId.fromCode(arguments?.getString(ARG_SOURCE_LANG))
            ?: SourceLangId.JA

        // Original — id is pinned to a resource id (etAnkiOriginal) so
        // Android's automatic view-state save/restore can round-trip
        // the typed text across process death without us writing a
        // manual onSaveInstanceState pipeline. The compact 44dp audio
        // toggle now sits inside the same card, beneath the edit field.
        ankiGroupHeader(root, getString(R.string.anki_group_original))
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
                (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(v.windowToken, 0)
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
            onSelectionChanged = { s, e -> onInlineSelectionChanged(s, e) }
        }
        // Pinch anywhere in the panel (its padding included) zooms the
        // waveform, not just pinches that start on the strip itself.
        forwardPanelTouchesToWave(panel)
        panel.visibility = View.GONE
        gameAudioLoadedFile = null
        originalCard.addView(panel)

        // Translation — same trick with R.id.etAnkiTranslation.
        ankiGroupHeader(root, getString(R.string.anki_group_translation))
        val translationCard = ankiGroupCard(root)
        etTranslation = buildEditField(initial = translation).apply {
            id = R.id.etAnkiTranslation
            if (translation.isBlank()) {
                hint = getString(R.string.status_translating)
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
        wordsLoading = arguments?.getBoolean(ARG_WORDS_LOADING, false) ?: false
        ankiGroupHeader(root, getString(R.string.anki_group_words_count, words.size))
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
                ankiGroupHeader(root, getString(R.string.anki_group_screenshot))
                screenshotHeader = root.getChildAt(root.childCount - 1)
                val screenshotCard = ankiGroupCard(root)
                screenshotGroup = root.getChildAt(root.childCount - 1)
                addScreenshotRow(screenshotCard, file) {
                    removeScreenshotFromUi()
                    // Mirror the removal back into the word tab when this
                    // fragment lives under WordAnkiReviewSheet — the two
                    // tabs share the same source media and would otherwise
                    // get out of sync.
                    (parentFragment as? WordAnkiReviewSheet)?.notifyScreenshotRemoved()
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
        val ctx = requireContext()
        val density = resources.displayMetrics.density
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
        val ctx = requireContext()
        val density = resources.displayMetrics.density
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
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        card.addView(TextView(ctx).apply {
            text = getString(R.string.anki_words_helper)
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
        val ctx = requireContext()
        val density = resources.displayMetrics.density
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
            contentDescription = getString(R.string.anki_screenshot_remove_content_description)
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
            val ctx = requireContext()
            val prefs = Prefs(ctx)
            val lang = SourceLangId.fromCode(arguments?.getString(ARG_SOURCE_LANG))
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
        wordsHeaderTitle.text = getString(R.string.anki_group_words_count, words.size)
            .uppercase(java.util.Locale.ROOT)
    }

    private fun buildWordsLoadingRow(): View {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        return TextView(ctx).apply {
            text = getString(R.string.words_loading)
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

    private fun buildWordRow(entry: SentenceAnkiHtmlBuilder.WordEntry): View {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
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

        if (entry.meaning.isNotBlank()) {
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
            contentDescription = getString(R.string.anki_word_remove_content_description)
            setPadding((10 * density).toInt(), (4 * density).toInt(),
                (10 * density).toInt(), (4 * density).toInt())
            setOnClickListener {
                words.removeAll { it.word == entry.word }
                selectedWords.remove(entry.word)
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
        val ctx = requireContext()

        // Translation: clear back to the loading hint only if the user
        // hasn't typed their own — preserve user work, even at the cost
        // of hiding the freshly-fetched translation behind their text.
        if (::etTranslation.isInitialized && !translationUserTouched) {
            translationSuppressNextEdit = true
            etTranslation.setText("")
            etTranslation.hint = getString(R.string.status_translating)
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
        val ctx = context ?: return
        if (text == null) {
            etTranslation.hint = getString(R.string.anki_translation_error)
            etTranslation.setHintTextColor(ctx.themeColor(R.attr.ptTextMuted))
            return
        }
        if (text.isBlank()) return
        translationSuppressNextEdit = true
        etTranslation.setText(text)
        etTranslation.hint = null
        arguments?.putString(ARG_TRANSLATION, text)
    }

    /**
     * Replaces the placeholder Words rows with [entries] when the
     * sentence's word lookups complete. [targetWord] re-applies the
     * auto-target highlight from [onViewCreated] so the looked-up word
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
        arguments?.let { args ->
            args.putStringArray(ARG_WORDS, words.map { it.word }.toTypedArray())
            args.putStringArray(ARG_READINGS, words.map { it.reading }.toTypedArray())
            args.putStringArray(ARG_MEANINGS, words.map { it.meaning }.toTypedArray())
            args.putIntArray(ARG_FREQ_SCORES, words.map { it.freqScore }.toIntArray())
        }
        rebuildWordRows()
    }

    companion object {
        /** Restore of the game-audio state after process death (onDestroyView
         *  never ran) or a saved-state destroy (onDestroyView ran but kept
         *  the file — see the isStateSaved gate there). */
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

        fun newInstance(
            japanese: String,
            translation: String,
            words: List<SentenceAnkiHtmlBuilder.WordEntry>,
            screenshotPath: String?,
            targetWord: String? = null,
            sourceLangId: SourceLangId = SourceLangId.JA,
            wordsLoading: Boolean = false,
        ) = SentenceAnkiContentFragment().apply {
            arguments = Bundle().apply {
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
            }
        }
    }
}
