package com.playtranslate.ui

import android.content.Context
import android.util.Log
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.playtranslate.AnkiManager
import com.playtranslate.PlayTranslateApplication
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.ask.Explanation
import com.playtranslate.capture.CaptureBackendResolver
import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.FrequencyTag
import com.playtranslate.model.headwordDisplay
import com.playtranslate.model.selectHeadword
import com.playtranslate.overlay.OverlayHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The looked-up word context the lens actions operate on, snapshotted at
 *  callback time (the lens nulls the source's word/entry state on dismiss). */
data class LensActionContext(
    val word: String?,
    /** The occurrence reading the lens displayed (Sudachi pick, e.g. 明日 → あす);
     *  null when none. Drives the open-detail bold + the Anki card reading. */
    val reading: String?,
    /** The text the word was found as (飲んでいなかった for 飲む), handed to
     *  the detail page so its header draws the lens's conjugation line;
     *  null for a phrase or member section, which is a lemma. */
    val surface: String?,
    val entry: DictionaryEntry?,
    val sentence: String?,
    val screenshotPath: String?,
    /** Game-audio ring anchor for the Anki flow: when the sentence's capture
     *  was produced ([com.playtranslate.model.TranslationResult.createdAtMs]).
     *  Null when the hosting surface has no capture moment (drag flow). */
    val audioAnchorMs: Long? = null,
    /** Every entry the lookup resolved ([entry] first) — POS-split packs
     *  return several, and the lens's sense rows span all of them, so the
     *  Anki card built from here must too. */
    val entries: List<DictionaryEntry> = listOfNotNull(entry),
    /** The dictionary form the lens resolved [word] under, which the detail
     *  page must resolve with ([WordDetailBinder.Args.lookupForm]). [word]
     *  is the display and can differ: a usually-kana entry resolved under
     *  its kanji form and seen in kana displays as the kana. Null with a
     *  null [word]. */
    val lookupForm: String?,
    /** The reading that narrowed [lookupForm]'s lookup, null when none. */
    val lookupReading: String?,
    /** What the explain question of the row acted on brought, when its
     *  answer stood in the row at the tap ([WordRowList.explanationOf]);
     *  null with none. Its text is the note a card made for [word] starts
     *  with: the editable review's, here or from the detail the action
     *  opens, which is handed the whole answer inside [sentence]'s context
     *  ([SentenceContext.explanations]) and shows it under the word's
     *  definitions. For a lens's row [SourceLensActions] reads it at the
     *  tap, whatever its provider gave; a Words row's opener hands in its
     *  row's. */
    val explanation: Explanation? = null,
)

/**
 * What the lens's open-detail tap hands the workspace page it presents:
 * the looked-up unit, the sentence it sits in, and the sentence context
 * snapshot (cached translation + words, when the drag flow produced them)
 * that seeds the page's Anki card before its own Sentence tab runs. The
 * answer that stood in the opened row rides in that snapshot, under the
 * word ([SentenceContext.explanations]).
 */
data class LensDetailArgs(
    val word: String,
    /** The occurrence reading the lens displayed (when it adds information;
     *  null when none or equal to the word). */
    val reading: String?,
    /** [LensActionContext.surface], for the detail page's conjugation line. */
    val surface: String?,
    /** The key the detail page resolves [word]'s entry with
     *  ([LensActionContext.lookupForm]). */
    val lookupForm: String,
    /** The reading that narrowed [lookupForm]'s lookup, null when none. */
    val lookupReading: String?,
    val sentence: String,
    val screenshotPath: String?,
    val audioAnchorMs: Long?,
    val sentenceContext: SentenceContext,
    /** The backend that produced [sentenceContext]'s translation, when the
     *  drag flow cached one — the "Translated by …" label the Sentence page
     *  binds beside it. */
    val cachedTranslationSource: String? = null,
)

/** The word page alone, seeded with the snapshot — the default detail
 *  presentation (the capture sheet's lens, nested drill-ins). */
fun WorkspaceWordDetailPage(args: LensDetailArgs): WorkspaceWordDetailPage =
    WorkspaceWordDetailPage(
        word = args.word,
        reading = args.reading,
        surface = args.surface,
        lookupForm = args.lookupForm,
        lookupReading = args.lookupReading,
        screenshotPath = args.screenshotPath,
        audioAnchorMs = args.audioAnchorMs,
        sentenceContext = { args.sentenceContext },
    )

/**
 * Opens the detail of one looked-up unit in its sentence: the workspace
 * detail page over the game when [route] can present it, else
 * [TranslationResultActivity] (the sentence, with its Sentence/Word toggle).
 * The one open path for every place a word is opened from beside its
 * sentence: the lens's open tap ([SourceLensActions]) and a Words row on
 * the over-game capture sheet, so the two cannot route differently.
 */
internal class SentenceDetailOpener(
    private val context: Context,
    private val displayId: Int,
    /** How the open reaches the floating workspace before falling back to
     *  the Activity launch ([SourceLensActions]' route). */
    private val route: WorkspaceRoute,
    /** The page the open presents in the workspace. */
    private val detailPage: (LensDetailArgs) -> WorkspacePage = { WorkspaceWordDetailPage(it) },
    /** When true (the capture sheet), the Activity launch is tagged so
     *  [TranslationResultActivity] can signal a return back to the sheet. */
    private val tagDetailReturn: Boolean = false,
    /** Fired after the detail was presented, page or Activity. */
    private val onLaunched: () -> Unit = {},
) {
    /** Open [cur]. [beforeLaunch] runs once the sentence cache has been
     *  snapshotted and before anything is presented: the lens dismisses
     *  itself there. A no-op without a sentence. */
    fun open(cur: LensActionContext, beforeLaunch: () -> Unit = {}) {
        val sentence = cur.sentence ?: return
        // Snapshot word context + cached sentence translation/words BEFORE
        // [beforeLaunch]: the lens's dismiss nulls the source's word/entry
        // and (drag flow) resumes live mode, which can race a fresh capture
        // and stomp LastSentenceCache before TranslationResultActivity.onCreate
        // runs.
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
        val wordsPayload = if (route !is WorkspaceRoute.None) LastSentenceCache.snapshotFor(sentence) else null
        CaptureBackendResolver.activeOverlayUi?.cancelLivePauseObligation()
        beforeLaunch()
        // Single-screen: the detail page opens in the floating workspace over
        // the game instead of leaving it — [detailPage] decides its shape
        // (the word page alone, or the drag flow's Sentence/word lookup
        // page); the sentence context travels either way for the Anki card.
        // A route that can't present (dual-screen coordinator posture) falls
        // through to the Activity launch.
        if (word != null) {
            val lookupForm = cur.lookupForm ?: word
            // The row's answer goes into the sentence's own context, under
            // the word the page is opened on: the page's card reads its
            // note where it reads its sentence.
            val snapshot = SentenceContext(
                original = sentence,
                translation = cachedTranslation,
                wordResults = wordsPayload?.results ?: cachedWordResults,
                surfaceForms = wordsPayload?.surfaces,
                wordEnrichment = wordsPayload?.enrichment,
            ).explaining(pageCardTarget(cur.surface, lookupForm, cur.lookupReading), cur.explanation)
            val args = LensDetailArgs(
                word = word,
                reading = reading,
                surface = cur.surface,
                lookupForm = lookupForm,
                lookupReading = cur.lookupReading,
                sentence = sentence,
                screenshotPath = cur.screenshotPath,
                audioAnchorMs = cur.audioAnchorMs,
                sentenceContext = snapshot,
                cachedTranslationSource = cachedTranslationSource,
            )
            val opened = route.present(cur.screenshotPath) { detailPage(args) }
            if (opened) {
                // The capture sheet maps Detail to its stash-for-reshow, so a
                // USER dismissal of the workspace brings the sheet back.
                onLaunched()
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
            // The key rides with the word: the embedded word page resolves
            // with it.
            word?.let {
                putExtra(TranslationResultActivity.EXTRA_DRAG_WORD, it)
                putExtra(TranslationResultActivity.EXTRA_DRAG_LOOKUP_FORM, cur.lookupForm ?: it)
                cur.lookupReading?.let { r -> putExtra(TranslationResultActivity.EXTRA_DRAG_LOOKUP_READING, r) }
            }
            if (!reading.isNullOrEmpty()) {
                putExtra(TranslationResultActivity.EXTRA_DRAG_READING, reading)
            }
            cur.surface?.let { putExtra(TranslationResultActivity.EXTRA_DRAG_SURFACE, it) }
            if (word != null) {
                putExtras(
                    Bundle().apply {
                        putExplanation(TranslationResultActivity.EXTRA_DRAG_EXPLANATION, cur.explanation)
                    },
                )
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
        route.prepareActivityLaunch()
        context.startActivity(intent, opts)
        onLaunched()
    }
}

/**
 * The magnifying-lens "open detail" tap + a row's Anki button, shared by the
 * floating-icon drag flow ([DragLookupController]) and the over-game capture
 * overlay ([CaptureResultOverlay]). Constructing it wires the lens's
 * onOpenTap / onAnkiTap / onAnkiLongPress; [current] supplies the tapped
 * unit's word/entry/sentence/screenshot and [currentSecondary] a secondary
 * row's at the moment of the tap — read ONCE up front in each handler,
 * before [MagnifierLens.dismiss] nulls the source's word/entry state. The
 * answer standing in the row is read with them ([contextOf]), for the same
 * reason: the dismissal puts the lens's answers away.
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
    /** How the open-detail tap and the Anki button reach the floating
     *  workspace (single-screen, word context present) before falling back
     *  to their Activity launches: a fresh workspace from a non-workspace
     *  surface, a push from a workspace page's own lens, or never for the
     *  in-activity hosts (camera / import), whose lens actions stay
     *  activity-routed. */
    private val route: WorkspaceRoute,
    /** The page the open-detail tap presents in the workspace. Default: the
     *  word-detail page alone (the capture sheet already shows the sentence;
     *  a page's own lens drills into a nested word). The floating-icon drag
     *  flow substitutes its Sentence/word lookup page. */
    private val detailPage: (LensDetailArgs) -> WorkspacePage = { WorkspaceWordDetailPage(it) },
    /** Context for the lens's split-body SECONDARY sections by index — the
     *  containing phrase (space-delimited surfaces) or a fused expression's
     *  member words (JA) — same sentence/screenshot as [current], with the
     *  secondary unit as the word: what that row's open and its Anki
     *  button act on. Null (the default) leaves
     *  [MagnifierLens.onSecondaryOpenTap] unwired; a wired provider may
     *  itself return null (no such secondary at the moment of the tap) for
     *  a no-op. */
    private val currentSecondary: ((Int) -> LensActionContext?)? = null,
    private val current: () -> LensActionContext,
) {
    /** Which Activity an action launched, so the caller can react differently. */
    enum class LaunchKind { Detail, Anki }

    /** The open-detail tap's path ([SentenceDetailOpener]), with this
     *  lens's route and page. */
    private val detailOpener = SentenceDetailOpener(
        context, displayId, route, detailPage, tagDetailReturn,
        onLaunched = { onLaunchedActivity(LaunchKind.Detail) },
    )

    init {
        // "Open in detail view": the workspace detail page over the game when
        // the route can present it, else TranslationResultActivity (sentence +
        // its Sentence/Word toggle). A row's Anki button: tap opens the
        // editable review; held is the headless one-tap shortcut.
        lens.onOpenTap = { contextOf(null)?.let { openSentenceInApp(it) } }
        lens.onAnkiTap = { secondary -> contextOf(secondary)?.let { openAnkiReviewForLens(it) } }
        lens.onAnkiLongPress = { secondary -> contextOf(secondary)?.let { oneTapFromLens(it) } }
        if (currentSecondary != null) {
            lens.onSecondaryOpenTap = { i -> contextOf(i)?.let { openSentenceInApp(it) } }
        }
    }

    /** The context of a row: the tapped unit's ([current]), or a
     *  secondary's by index, with the answer standing in that row of the
     *  lens now ([LensActionContext.explanation]); null when there is no
     *  such secondary now. */
    private fun contextOf(secondary: Int?): LensActionContext? =
        (if (secondary == null) current() else currentSecondary?.invoke(secondary))
            ?.copy(explanation = lens.explanationOf(secondary))

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
        /** The text the editor's Notes boxes start with
         *  ([LensActionContext.explanation]'s); "" with none. */
        val notes: String,
        /** The row's [lensRowCardTarget]: the word as an unlisted target for
         *  a row with no entry, null for one with an entry. */
        val target: CardTarget?,
    )

    private fun openSentenceInApp(cur: LensActionContext) =
        detailOpener.open(cur, beforeLaunch = { lens.dismiss() })

    private fun openAnkiReviewForLens(cur: LensActionContext) {
        val word = cur.word ?: return
        val ankiManager = AnkiManager(context)
        if (!ankiManager.isAnkiDroidInstalled()) {
            showAnkiNotInstalled?.invoke()
                ?: showAnkiNotInstalledDialog(lens.rawCtx, overlayHost, lens.wm, displayId)
            return
        }
        val snap = snapshotLensFieldsForAnki(word, cur.entry, cur)
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
        if (!AnkiManager(context).hasPermission()) return false
        val opened = route.present(snap.screenshotPath) {
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
                        target = snap.target,
                        notes = snap.notes,
                    ),
                )
            }
        if (opened) {
            // The capture sheet maps Anki to its dismiss — same as the
            // activity launch it replaces.
            onLaunchedActivity(LaunchKind.Anki)
        }
        return opened
    }

    private fun oneTapFromLens(cur: LensActionContext) {
        val word = cur.word ?: return
        val ankiManager = AnkiManager(context)
        if (!ankiManager.isAnkiDroidInstalled() || !ankiManager.hasPermission()) {
            openAnkiReviewForLens(cur)
            return
        }
        val prefs = Prefs(context)
        if (prefs.ankiDeckId < 0L) {
            openAnkiReviewForLens(cur)
            return
        }
        val snap = snapshotLensFieldsForAnki(word, cur.entry, cur)
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
            // The pipeline is exception-free by contract; a bug that escapes
            // anyway is contained here the way launchOneTapSend contains it
            // for fragment hosts (loud log + failure toast), instead of
            // crashing the app from a detached coroutine.
            val (result, mode) = try {
                context.oneTapSend(
                    word = snap.word,
                    reading = snap.reading,
                    pos = snap.pos,
                    entry = cur.entry,
                    entries = cur.entries,
                    fallbackDefinition = snap.definition,
                    freqScore = snap.freqScore,
                    pitch = snap.pitch,
                    frequencies = snap.frequencies,
                    sentenceOriginal = snap.sentence,
                    sentenceTranslation = snap.sentenceTranslation,
                    wordsPayload = null,
                    screenshotPath = snap.screenshotPath,
                    sourceLangId = sourceLangId,
                    target = snap.target,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(ONE_TAP_TAG, "lens one-tap send escaped an exception", e)
                oneTapSendFailedToast(context.applicationContext)
                return@launch
            }
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

    /** The card's fields from the row's [entry]. A row with none, a word no
     *  dictionary lists, gives the reading the lens showed and nothing else
     *  (no part of speech, definition, frequency or pitch), and names the
     *  word as an [CardTarget.unlisted] target for its sentence card. */
    private fun snapshotLensFieldsForAnki(
        word: String, entry: DictionaryEntry?, cur: LensActionContext,
    ): LensAnkiSnapshot {
        // Card reading + pitch/freq follow the occurrence reading the lens showed
        // (明日 → あす), falling back to the primary headword when there was none.
        val occHeadword = entry?.selectHeadword(word, word, cur.reading)
        // Take the reading from the SELECTED headword, not raw cur.reading, so the
        // card's reading stays consistent with its pitch/freq (same occHeadword)
        // and can't persist a reading the entry doesn't list if the occurrence
        // reading ever arrives unvalidated. With no entry the lens's reading is
        // all there is.
        val reading = if (entry == null) {
            cur.reading?.takeIf { it != word }.orEmpty()
        } else {
            occHeadword?.reading?.takeIf { it != occHeadword.written } ?: ""
        }
        val pos = entry?.let { context.firstSensePosLabel(it) }.orEmpty()
        val definition = entry?.let { flatCardDefinition(it) }.orEmpty()
        val headword = entry?.headwordDisplay(occHeadword, word)
        val target = lensRowCardTarget(entry, cur.surface, cur.lookupForm ?: word, cur.lookupReading)
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
            freqScore = entry?.freqScore ?: 0,
            pitch = headword?.pitch.orEmpty(),
            frequencies = headword?.frequencies.orEmpty(),
            screenshotPath = cur.screenshotPath,
            sentence = sentence,
            sentenceTranslation = sentenceTranslation,
            sentenceWordResults = sentenceWordResults,
            sourceLangCode = Prefs(context).sourceLangId.code,
            audioAnchorMs = cur.audioAnchorMs,
            notes = cur.explanation?.text.orEmpty(),
            target = target,
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
            // AnkiPermissionActivity forwards every extra.
            putExtras(
                android.os.Bundle().apply {
                    putAnkiNotes(snap.notes)
                    snap.target?.let { putCardTarget(it) }
                },
            )
        }
        val targetDisplay = PlayTranslateApplication.foregroundDisplayId() ?: displayId
        val opts = android.app.ActivityOptions.makeBasic()
            .setLaunchDisplayId(targetDisplay)
            .toBundle()
        route.prepareActivityLaunch()
        context.startActivity(intent, opts)
        onLaunchedActivity(LaunchKind.Anki)
    }

}
