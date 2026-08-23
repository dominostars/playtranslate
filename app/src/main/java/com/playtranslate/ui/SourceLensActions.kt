package com.playtranslate.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.playtranslate.AnkiManager
import com.playtranslate.PlayTranslateApplication
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.capture.CaptureBackendResolver
import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.FrequencyTag
import com.playtranslate.model.headwordDisplay
import com.playtranslate.model.selectHeadword
import com.playtranslate.overlay.OverlayHost
import kotlinx.coroutines.launch

/** The looked-up word context the lens actions operate on, snapshotted at
 *  callback time (the lens nulls the source's word/entry state on dismiss). */
data class LensActionContext(
    val word: String?,
    /** The occurrence reading the lens displayed (Sudachi pick, e.g. 明日 → あす);
     *  null when none. Drives the open-detail bold + the Anki card reading. */
    val reading: String?,
    val entry: DictionaryEntry?,
    val sentence: String?,
    val screenshotPath: String?,
    /** Game-audio ring anchor for the Anki flow: when the sentence's capture
     *  was produced ([com.playtranslate.model.TranslationResult.createdAtMs]).
     *  Null when the hosting surface has no capture moment (drag flow). */
    val audioAnchorMs: Long? = null,
)

/**
 * The magnifying-lens "open detail" tap + Anki chip actions, shared by the
 * floating-icon drag flow ([DragLookupController]) and the over-game capture
 * overlay ([CaptureResultOverlay]). Constructing it wires the lens's
 * onOpenTap / onAnkiTap / onAnkiLongPress; [current] supplies the
 * word/entry/sentence/screenshot at the moment a chip is tapped — read ONCE
 * up front in each handler, before [MagnifierLens.dismiss] nulls the source's
 * word/entry state.
 */
class SourceLensActions(
    private val context: Context,
    private val displayId: Int,
    private val overlayHost: OverlayHost,
    private val lens: MagnifierLens,
    /** Fired after an action LAUNCHES an Activity, tagged with the [LaunchKind]. The
     *  capture overlay dismisses its sheet for an Anki push but stashes-for-reshow on
     *  an open-detail push; the drag flow leaves it a no-op (its lens IS the surface). */
    private val onLaunchedActivity: (LaunchKind) -> Unit = {},
    /** When true (the capture overlay), the open-detail launch is tagged so
     *  [TranslationResultActivity] can signal a return back to the overlay on back. */
    private val tagDetailReturn: Boolean = false,
    /** In-activity hosts (the camera snapshot panel) route the "AnkiDroid
     *  not installed" dialog through their own presenter — the default
     *  overlay window needs a permission an activity flow may not have. */
    private val showAnkiNotInstalled: (() -> Unit)? = null,
    /** The open-detail tap prefers the floating workspace's word-detail page
     *  over the game (single-screen, word context present) before falling
     *  back to the [TranslationResultActivity] launch. False for in-activity
     *  hosts (camera / import), whose lens actions stay activity-routed. */
    private val workspaceRoute: Boolean = true,
    /** Context for the lens's split-body SECONDARY sections by index — the
     *  containing phrase (space-delimited surfaces) or a fused expression's
     *  member words (JA) — same sentence/screenshot as [current], with the
     *  secondary unit as the word. Null (the default) leaves
     *  [MagnifierLens.onSecondaryOpenTap] unwired; a wired provider may
     *  itself return null (no such secondary at the moment of the tap) for
     *  a no-op. */
    private val currentSecondary: ((Int) -> LensActionContext?)? = null,
    private val current: () -> LensActionContext,
) {
    /** Which Activity an action launched, so the caller can react differently. */
    enum class LaunchKind { Detail, Anki }

    init {
        // "Open in detail view" always goes to TranslationResultActivity —
        // sentence + segmented Sentence/Word toggle. Anki chip: tap opens the
        // editable review sheet; long-press is the headless one-tap shortcut.
        lens.onOpenTap = { openSentenceInApp(current()) }
        lens.onAnkiTap = { openAnkiReviewForLens() }
        lens.onAnkiLongPress = { oneTapFromLens() }
        currentSecondary?.let { secondary ->
            lens.onSecondaryOpenTap = { i -> secondary(i)?.let { openSentenceInApp(it) } }
        }
    }

    private data class LensAnkiSnapshot(
        val word: String,
        val reading: String,
        val pos: String,
        val definition: String,
        val freqScore: Int,
        val pitch: List<Int>,
        val frequencies: List<FrequencyTag>,
        val screenshotPath: String?,
        val sentence: String?,
        val sentenceTranslation: String?,
        /** The sentence's per-word breakdown, snapshotted with the
         *  translation (and for the same reason — see
         *  [snapshotLensFieldsForAnki]). The workspace editor builds from
         *  this; the Activity route re-reads the cache in its own onCreate. */
        val sentenceWordResults: Map<String, Triple<String, String, Int>>?,
        val sourceLangCode: String,
        val audioAnchorMs: Long?,
    )

    private fun openSentenceInApp(cur: LensActionContext) {
        val sentence = cur.sentence ?: return
        // Snapshot word context + cached sentence translation/words BEFORE
        // lens.dismiss() — dismiss nulls the source's word/entry and (drag flow)
        // resumes live mode, which can race a fresh capture and stomp
        // LastSentenceCache before TranslationResultActivity.onCreate runs.
        val word = cur.word
        // Prefer the occurrence reading the lens displayed (明日 → あす); fall back
        // to the entry's primary headword only when the host supplied none.
        val headword = cur.entry?.headwords?.firstOrNull()
        val reading = cur.reading?.takeIf { it != word }
            ?: headword?.reading?.takeIf { it != headword.written }
        val cached = LastSentenceCache.takeIf { it.original == sentence }
        val cachedTranslation = cached?.translation
        val cachedTranslationSource = cached?.translationSource
        val cachedWordResults = cached?.wordResults?.takeIf { it.isNotEmpty() }
        // The workspace page's Anki flow wants the ATOMIC words payload
        // (results + surfaces + enrichment) — snapshotted here for the same
        // stomp reason as the fields above.
        val wordsPayload = if (workspaceRoute) LastSentenceCache.snapshotFor(sentence) else null
        CaptureBackendResolver.activeOverlayUi?.cancelLivePauseObligation()
        lens.dismiss()
        // Single-screen: the word-detail page opens in the floating workspace
        // over the game instead of leaving it. Deliberately the WORD page —
        // the sentence is already rendered by the surface the tap came from;
        // its context still travels for the Anki card. openWorkspace()
        // returning false (dual-screen) falls through to the Activity launch.
        if (workspaceRoute && word != null) {
            val snapshot = SentenceContext(
                original = sentence,
                translation = cachedTranslation,
                wordResults = wordsPayload?.results ?: cachedWordResults,
                surfaceForms = wordsPayload?.surfaces,
                wordEnrichment = wordsPayload?.enrichment,
            )
            val opened = CaptureBackendResolver.activeOverlayUi?.openWorkspace(displayId) {
                WorkspaceWordDetailPage(
                    word = word,
                    reading = reading,
                    screenshotPath = cur.screenshotPath,
                    audioAnchorMs = cur.audioAnchorMs,
                    sentenceContext = { snapshot },
                )
            } == true
            if (opened) {
                // The capture sheet maps Detail to its stash-for-reshow, so a
                // USER dismissal of the workspace brings the sheet back.
                onLaunchedActivity(LaunchKind.Detail)
                return
            }
        }
        // Replace any previously launched TRA (MULTIPLE_TASK otherwise leaves it
        // alive in a hidden task on a possibly-wrong display).
        TranslationResultActivity.finishCurrentIfAny()
        val intent = Intent(context, TranslationResultActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            if (tagDetailReturn) {
                // Lets the detail screen call back so the overlay re-shows on return.
                putExtra(TranslationResultActivity.EXTRA_FROM_CAPTURE_OVERLAY, true)
                putExtra(TranslationResultActivity.EXTRA_TARGET_DISPLAY_ID, displayId)
            }
            putExtra(TranslationResultActivity.EXTRA_SENTENCE_TEXT, sentence)
            putExtra(TranslationResultActivity.EXTRA_SCREENSHOT_PATH, cur.screenshotPath)
            // Word context → the activity surfaces the Sentence/Word toggle.
            word?.let { putExtra(TranslationResultActivity.EXTRA_DRAG_WORD, it) }
            if (!reading.isNullOrEmpty()) {
                putExtra(TranslationResultActivity.EXTRA_DRAG_READING, reading)
            }
            cachedTranslation?.let {
                putExtra(TranslationResultActivity.EXTRA_DRAG_SENTENCE_TRANSLATION, it)
            }
            cachedTranslationSource?.let {
                putExtra(TranslationResultActivity.EXTRA_DRAG_SENTENCE_TRANSLATION_SOURCE, it)
            }
            cachedWordResults?.let { wr ->
                putExtra(TranslationResultActivity.EXTRA_DRAG_SENTENCE_WORDS,
                    wr.keys.toTypedArray())
                putExtra(TranslationResultActivity.EXTRA_DRAG_SENTENCE_READINGS,
                    wr.values.map { it.first }.toTypedArray())
                putExtra(TranslationResultActivity.EXTRA_DRAG_SENTENCE_MEANINGS,
                    wr.values.map { it.second }.toTypedArray())
                putExtra(TranslationResultActivity.EXTRA_DRAG_SENTENCE_FREQ_SCORES,
                    wr.values.map { it.third }.toIntArray())
            }
        }
        // Prefer any resumed PT activity's display, else the caller's display.
        val targetDisplay = PlayTranslateApplication.foregroundDisplayId() ?: displayId
        val opts = android.app.ActivityOptions.makeBasic()
            .setLaunchDisplayId(targetDisplay)
            .toBundle()
        context.startActivity(intent, opts)
        onLaunchedActivity(LaunchKind.Detail)
    }

    private fun openAnkiReviewForLens() {
        val cur = current()
        val word = cur.word ?: return
        val entry = cur.entry ?: return
        val ankiManager = AnkiManager(context)
        if (!ankiManager.isAnkiDroidInstalled()) {
            showAnkiNotInstalled?.invoke()
                ?: showAnkiNotInstalledDialog(lens.rawCtx, overlayHost, lens.wm, displayId)
            return
        }
        val snap = snapshotLensFieldsForAnki(word, entry, cur)
        CaptureBackendResolver.activeOverlayUi?.cancelLivePauseObligation()
        lens.dismiss()
        if (openAnkiEditorWorkspace(snap)) return
        launchWordAnkiActivity(snap)
    }

    /** Single-screen with the AnkiDroid permission already held: the editor
     *  opens as a floating-workspace page over the game. False (dual-screen,
     *  in-activity host, permission missing) leaves the caller on the
     *  Activity trampoline — which owns the runtime permission request. */
    private fun openAnkiEditorWorkspace(snap: LensAnkiSnapshot): Boolean {
        if (!workspaceRoute) return false
        if (!AnkiManager(context).hasPermission()) return false
        val opened = CaptureBackendResolver.activeOverlayUi?.openWorkspace(displayId) {
            AnkiEditorPage(
                WordAnkiReviewBinder.buildArgs(
                    word = snap.word,
                    reading = snap.reading,
                    pos = snap.pos,
                    definition = snap.definition,
                    screenshotPath = snap.screenshotPath,
                    freqScore = snap.freqScore,
                    sentenceOriginal = snap.sentence,
                    sentenceTranslation = snap.sentenceTranslation,
                    sentenceWordResults = snap.sentenceWordResults,
                    sourceLangId = Prefs(context).sourceLangId,
                    audioAnchorMs = snap.audioAnchorMs,
                ),
            )
        } == true
        if (opened) {
            // The capture sheet maps Anki to its dismiss — same as the
            // activity launch it replaces.
            onLaunchedActivity(LaunchKind.Anki)
        }
        return opened
    }

    private fun oneTapFromLens() {
        val cur = current()
        val word = cur.word ?: return
        val entry = cur.entry ?: return
        val ankiManager = AnkiManager(context)
        if (!ankiManager.isAnkiDroidInstalled() || !ankiManager.hasPermission()) {
            openAnkiReviewForLens()
            return
        }
        val prefs = Prefs(context)
        if (prefs.ankiDeckId < 0L) {
            openAnkiReviewForLens()
            return
        }
        val snap = snapshotLensFieldsForAnki(word, entry, cur)
        val sourceLangId = prefs.sourceLangId
        CaptureBackendResolver.activeOverlayUi?.cancelLivePauseObligation()
        lens.dismiss()
        Toast.makeText(context, R.string.anki_adding_in_progress, Toast.LENGTH_SHORT).show()
        // Run on the PROCESS-lived one-tap scope, not a caller scope — the
        // capture overlay cancels its scope on dismiss, which would silently
        // kill an in-flight send (no card, no result toast). The toast targets
        // the app context, so it still fires after any UI is gone.
        ankiOneTapSendScope.launch {
            // Sentence card (dragged word bolded) vs word card routing — incl.
            // the single-word-sentence rule — is shared via oneTapSend.
            val (result, mode) = context.oneTapSend(
                word = snap.word,
                reading = snap.reading,
                pos = snap.pos,
                fallbackDefinition = snap.definition,
                freqScore = snap.freqScore,
                pitch = snap.pitch,
                frequencies = snap.frequencies,
                sentenceOriginal = snap.sentence,
                sentenceTranslation = snap.sentenceTranslation,
                wordsPayload = null,
                screenshotPath = snap.screenshotPath,
                sourceLangId = sourceLangId,
            )
            when (result) {
                // The mapping needs UI: the workspace editor when available
                // (its NeedsMapping flow configures the mapping in-window),
                // else the review activity — the lens's existing parity.
                is AnkiSendResult.NeedsMapping ->
                    if (!openAnkiEditorWorkspace(snap)) launchWordAnkiActivity(snap)
                else -> oneTapResultToast(context.applicationContext, result, mode)
            }
        }
    }

    private fun snapshotLensFieldsForAnki(
        word: String, entry: DictionaryEntry, cur: LensActionContext,
    ): LensAnkiSnapshot {
        // Card reading + pitch/freq follow the occurrence reading the lens showed
        // (明日 → あす), falling back to the primary headword when there was none.
        val occHeadword = entry.selectHeadword(word, word, cur.reading)
        // Take the reading from the SELECTED headword, not raw cur.reading, so the
        // card's reading stays consistent with its pitch/freq (same occHeadword)
        // and can't persist a reading the entry doesn't list if the occurrence
        // reading ever arrives unvalidated.
        val reading = occHeadword?.reading?.takeIf { it != occHeadword.written } ?: ""
        val pos = entry.senses.firstOrNull()?.partsOfSpeech
            ?.filter { it.isNotBlank() }?.joinToString(" · ") ?: ""
        val definition = flatCardDefinition(entry)
        val headword = entry.headwordDisplay(occHeadword, word)
        val sentence = cur.sentence
        val sentenceTranslation = LastSentenceCache
            .takeIf { it.original == sentence }?.translation
        // Word breakdown for the editor's Sentence card, snapshotted BEFORE
        // lens.dismiss() like the translation above: dismiss resumes live
        // mode (drag flow), and a fresh capture can rotate the cache before
        // the editor binds. Null (cache already rotated) leaves the binder's
        // lazy words-fill to recompute — the same fallback the Activity
        // route has when ITS cache read misses.
        val sentenceWordResults = LastSentenceCache
            .takeIf { it.original == sentence }?.wordResults
        return LensAnkiSnapshot(
            word = word,
            reading = reading,
            pos = pos,
            definition = definition,
            freqScore = entry.freqScore,
            pitch = headword.pitch,
            frequencies = headword.frequencies,
            screenshotPath = cur.screenshotPath,
            sentence = sentence,
            sentenceTranslation = sentenceTranslation,
            sentenceWordResults = sentenceWordResults,
            sourceLangCode = Prefs(context).sourceLangId.code,
            audioAnchorMs = cur.audioAnchorMs,
        )
    }

    private fun launchWordAnkiActivity(snap: LensAnkiSnapshot) {
        WordAnkiReviewActivity.finishCurrentIfAny()
        AnkiPermissionActivity.finishCurrentIfAny()
        val intent = Intent(context, AnkiPermissionActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            putExtra(WordAnkiReviewActivity.EXTRA_WORD, snap.word)
            putExtra(WordAnkiReviewActivity.EXTRA_READING, snap.reading)
            putExtra(WordAnkiReviewActivity.EXTRA_POS, snap.pos)
            putExtra(WordAnkiReviewActivity.EXTRA_DEFINITION, snap.definition)
            putExtra(WordAnkiReviewActivity.EXTRA_FREQ_SCORE, snap.freqScore)
            snap.screenshotPath?.let {
                putExtra(WordAnkiReviewActivity.EXTRA_SCREENSHOT_PATH, it)
            }
            snap.sentence?.let {
                putExtra(WordAnkiReviewActivity.EXTRA_SENTENCE_ORIGINAL, it)
            }
            snap.sentenceTranslation?.let {
                putExtra(WordAnkiReviewActivity.EXTRA_SENTENCE_TRANSLATION, it)
            }
            putExtra(WordAnkiReviewActivity.EXTRA_SOURCE_LANG, snap.sourceLangCode)
            snap.audioAnchorMs?.let {
                putExtra(WordAnkiReviewActivity.EXTRA_AUDIO_ANCHOR_MS, it)
            }
        }
        val targetDisplay = PlayTranslateApplication.foregroundDisplayId() ?: displayId
        val opts = android.app.ActivityOptions.makeBasic()
            .setLaunchDisplayId(targetDisplay)
            .toBundle()
        context.startActivity(intent, opts)
        onLaunchedActivity(LaunchKind.Anki)
    }

}
