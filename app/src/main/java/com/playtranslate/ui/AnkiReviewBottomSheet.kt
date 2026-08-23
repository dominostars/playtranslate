package com.playtranslate.ui

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.applyAccentOverlay
import com.playtranslate.applyDialogEdgeToEdge
import com.playtranslate.audio.AudioSelection
import com.playtranslate.fullScreenDialogTheme
import com.playtranslate.language.SourceLangId
import kotlinx.coroutines.launch

/**
 * Sentence-only Anki review sheet (the in-app results page's card-level
 * "Add to Anki"). The card editor itself is a directly-hosted
 * [SentenceAnkiContentView] — shared with [WordAnkiReviewBinder]'s sentence
 * tab and the floating workspace's editor page; this DialogFragment keeps
 * the dialog window, the deck section, the screenshot pin, the lazy
 * translation fill, and the send.
 */
class AnkiReviewBottomSheet : DialogFragment() {

    private var deckSubtitleView: TextView? = null

    /** Controller for the Save button's idle ↔ loading swap. */
    private var sendButton: AnkiSendButton? = null

    private var contentView: SentenceAnkiContentView? = null

    /** The content's launch-state bundle — fresh at first creation, the
     *  persisted bundle on a saved-state recreation (applied translation
     *  survives; the old child-fragment auto-restore semantics). */
    private var contentArgs: Bundle? = null

    /** Optional listener called when this sheet is dismissed (used by
     *  [SentenceAnkiReviewActivity] to finish the host activity). */
    var onDismissListener: DialogInterface.OnDismissListener? = null

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDismissListener?.onDismiss(dialog)
    }

    override fun getTheme(): Int = fullScreenDialogTheme(requireContext())

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        applyAccentOverlay(dialog.context.theme, requireContext())
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_anki_review, container, false)

    /** Open-time screenshot pin (see onViewCreated) — released on
     *  provably-final teardown, same contract as the word sheet. */
    private var pinnedScreenshotPath: String? = null

    override fun onResume() {
        super.onResume()
        contentView?.onHostResumed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        contentArgs?.let { outState.putBundle(STATE_CONTENT_ARGS, it) }
        contentView?.saveState(outState)
    }

    override fun onDestroyView() {
        deckSubtitleView = null
        sendButton = null
        val finalTeardown = isFinalMediaTeardown()
        if (finalTeardown) {
            context?.let { AnkiScreenshotPin.release(it, pinnedScreenshotPath) }
        }
        pinnedScreenshotPath = null
        contentView?.release(deleteSnapshotFile = finalTeardown)
        contentView = null
        super.onDestroyView()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setWindowAnimations(R.style.AnimSlideRight)
            applyDialogEdgeToEdge(this, requireContext())
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(sys.left, sys.top, sys.right, maxOf(sys.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
        view.findViewById<View>(R.id.btnBackReview).setOnClickListener { dismiss() }

        val args = arguments ?: return
        val original       = args.getString(ARG_ORIGINAL) ?: ""
        val translation    = args.getString(ARG_TRANSLATION) ?: ""
        // Pin at open: the arg is a fixed cache filename
        // (capture-d{id}.jpg) that any capture taken while this sheet
        // is open overwrites — the card must keep the frame the user
        // acted on. The pinned path is written back into args so a
        // restored instance RE-OWNS the pin and releases it on final
        // teardown instead of leaving it for the stale sweep.
        val restoredContentArgs = savedInstanceState?.getBundle(STATE_CONTENT_ARGS)
        val screenshotPath = if (restoredContentArgs == null) {
            AnkiScreenshotPin.pin(requireContext(), args.getString(ARG_SCREENSHOT_PATH))
                .also {
                    pinnedScreenshotPath = it
                    args.putString(ARG_SCREENSHOT_PATH, it)
                }
        } else {
            pinnedScreenshotPath = args.getString(ARG_SCREENSHOT_PATH)
                ?.takeIf { AnkiScreenshotPin.isPin(requireContext(), it) }
            // The restored content args already carry the pinned path (or
            // its removal) — nothing new gets pinned.
            null
        }

        val words = mutableListOf<SentenceAnkiHtmlBuilder.WordEntry>()
        val wordArr    = args.getStringArray(ARG_WORDS) ?: emptyArray()
        val readingArr = args.getStringArray(ARG_READINGS) ?: emptyArray()
        val meaningArr = args.getStringArray(ARG_MEANINGS) ?: emptyArray()
        val freqArr    = args.getIntArray(ARG_FREQ_SCORES) ?: IntArray(0)
        // Surfaces (parallel to ARG_WORDS) + enrichment (keyed map) come from the
        // atomic snapshot the caller passed — never the live LastSentenceCache,
        // whose global fields may have rotated to another sentence by now (see
        // newInstance / LastSentenceCache.awaitOrStartWordLookups docs).
        val surfaceArr = args.getStringArray(ARG_SURFACES) ?: emptyArray()
        @Suppress("DEPRECATION", "UNCHECKED_CAST")
        val enrich = (args.getSerializable(ARG_ENRICHMENT) as? HashMap<String, WordEnrichment>)
            ?: hashMapOf()
        wordArr.forEachIndexed { i, w ->
            words.add(SentenceAnkiHtmlBuilder.WordEntry(
                w, readingArr.getOrElse(i) { "" },
                // Blank slot = sense-bearing word; the flat text re-derives
                // from the senses that crossed in ARG_ENRICHMENT.
                meaningFromTransport(meaningArr.getOrElse(i) { "" }, enrich[w]),
                freqArr.getOrElse(i) { 0 },
                surfaceForm = surfaceArr.getOrElse(i) { "" },
                pitch = enrich[w]?.pitch.orEmpty(),
                frequencies = enrich[w]?.frequencies.orEmpty(),
                isCommon = enrich[w]?.isCommon ?: false,
                senses = enrich[w]?.senses.orEmpty(),
            ))
        }

        val sourceLangId = SourceLangId.fromCode(args.getString(ARG_SOURCE_LANG)) ?: SourceLangId.JA

        val deckHost = view.findViewById<LinearLayout>(R.id.sentenceAnkiDeckHost)
        deckSubtitleView = view.findViewById(R.id.tvAnkiSendSubtitle)
        addAnkiSection(
            parent = deckHost,
            mode = CardMode.SENTENCE,
            onDeckChanged = { refreshDeckSubtitle() },
            onCardTypeChanged = { /* no visible affordance reflects card type */ },
        )
        refreshDeckSubtitle()

        // The card editor, hosted directly (the child-fragment hop is gone).
        val cArgs = restoredContentArgs ?: SentenceAnkiContentView.buildArgs(
            original, translation, words, screenshotPath, sourceLangId = sourceLangId,
            audioAnchorMs = args.takeIf { it.containsKey(ARG_AUDIO_ANCHOR_MS) }
                ?.getLong(ARG_AUDIO_ANCHOR_MS),
        )
        contentArgs = cArgs
        val host = view.findViewById<ViewGroup>(R.id.sentenceAnkiFragmentHost)
        val contentRoot = LayoutInflater.from(requireContext())
            .inflate(R.layout.fragment_sentence_anki_content, host, false) as LinearLayout
        val content = SentenceAnkiContentView(
            requireContext(), viewLifecycleOwner.lifecycleScope, cArgs, ContentHost(),
            // Fresh bind: hand the rich entries (built from this sheet's own
            // atomic args) past buildArgs' flattening. On restore the
            // restored arrays are the truth — an edited sentence's rows may
            // have replaced these — so the build falls back to them plus the
            // sentence-gated cache read.
            initialWords = words.takeIf { restoredContentArgs == null },
        )
        contentView = content
        host.addView(contentRoot)
        content.buildInto(contentRoot, savedInstanceState)

        val sendBtn = view.findViewById<FrameLayout>(R.id.btnSendToAnki)
        sendButton = AnkiSendButton(sendBtn)
        sendBtn.setOnClickListener {
            val deckId = Prefs(requireContext()).ankiDeckId
            if (deckId < 0L) {
                Toast.makeText(requireContext(), getString(R.string.anki_no_deck_selected), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendButton?.setLoading(true)
            viewLifecycleOwner.lifecycleScope.launch {
                sendToAnki(deckId)
            }
        }

        // Lazy translation fill (mirror of the word editor's): a blank
        // incoming translation means the sheet was opened from a result whose
        // translation never ran — the hidden-section deferral — or hasn't
        // landed yet. A deferred capture's pending rides the args, and
        // resolveAnkiTranslation routes it through the deferred completion
        // (History rows fill, idempotently). Failures are contained (null →
        // applyTranslation renders the error variant without clobbering user
        // edits). Safe after restore too: applyTranslation guards on the
        // visible original and on user-touched state.
        if (translation.isBlank() && original.isNotBlank()) {
            @Suppress("DEPRECATION")
            val pending = args.getSerializable(ARG_PENDING_TRANSLATION)
                as? com.playtranslate.model.PendingTranslation
            viewLifecycleOwner.lifecycleScope.launch {
                val outcome = resolveAnkiTranslation(pending, original)
                contentView?.applyTranslation(original, outcome?.text)
            }
        }
    }

    // ── Audio picker (the content's cells, via the shared Host seam) ─────

    private var pendingPickerCallback: ((AudioSelection) -> Unit)? = null

    private val audioPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = pendingPickerCallback.also { pendingPickerCallback = null }
            ?: return@registerForActivityResult
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        cb(SentenceAnkiContentView.parsePickerResult(result.data))
    }

    private inner class ContentHost : SentenceAnkiContentView.Host {
        override val isAlive: Boolean get() = isAdded

        override fun openAudioPicker(intent: Intent, onPicked: (AudioSelection) -> Unit) {
            pendingPickerCallback = onPicked
            audioPickerLauncher.launch(intent)
        }
        // No sibling tab to mirror screenshot removal into.
    }

    /** Updates the save button's "Deck: <name>" subtitle whenever the
     *  user picks a different deck. Renders as plain `?attr/ptAccentOn`
     *  text so the deck name reads against the button's accent fill —
     *  spannable accent highlighting would vanish into the bg. */
    private fun refreshDeckSubtitle() {
        val sub = deckSubtitleView ?: return
        val ctx = requireContext()
        val deckName = Prefs(ctx).ankiDeckName.ifBlank { ctx.getString(R.string.anki_deck_row_empty) }
        sub.text = ctx.getString(R.string.anki_deck_label_format, deckName)
    }

    private suspend fun sendToAnki(deckId: Long) {
        val content = contentView ?: run { sendButton?.setLoading(false); return }
        // Untrimmed game audio resolves here (the review nudge fires once);
        // false = the nudge held this Save — abort the send.
        if (!content.resolveGameAudioForSend()) {
            sendButton?.setLoading(false)
            return
        }
        val data = content.getCardData()
        val input = SentenceSendInput(
            original = data.source,
            translation = data.target,
            words = data.words,
            selectedWords = data.selectedWords,
            sourceLangId = data.sourceLangId,
            screenshotPath = data.screenshotPath,
            includeSentenceAudio = content.sentenceAudioEnabled,
            targetWordAudioWords = data.targetWordAudioWords,
            sentenceSelection = data.sentenceSelection,
            wordSelections = data.wordSelections,
        )
        // Fragment receiver so NeedsMapping opens the mapping dialog
        // (Context.sendSentenceCard would skip it).
        val result = sendSentenceCard(input, deckId)
        // The pipeline folds local synth failures into Success.audioDropped
        // / wordAudioDropped, so one resolver covers synth-fail, audio
        // upload-fail, and a screenshot AnkiDroid wouldn't take. Null =
        // the card landed whole, and the sheet stays silent.
        val shortfallRes = (result as? AnkiSendResult.Success)?.mediaShortfallRes()
        applyAnkiSendResult(
            result,
            onSuccess = {
                shortfallRes?.let {
                    Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                }
                parentFragmentManager.setFragmentResult(RESULT_ANKI_ADDED, bundleOf())
                dismiss()
            },
            onRestore = { sendButton?.setLoading(false) },
        )
    }

    companion object {
        const val RESULT_ANKI_ADDED = "anki_added"
        const val TAG = "AnkiReviewBottomSheet"
        private const val STATE_CONTENT_ARGS = "sentence_content_args"

        private const val ARG_ORIGINAL        = "original"
        private const val ARG_TRANSLATION     = "translation"
        private const val ARG_WORDS           = "words"
        private const val ARG_READINGS        = "readings"
        private const val ARG_MEANINGS        = "meanings"
        private const val ARG_FREQ_SCORES     = "freq_scores"
        private const val ARG_SURFACES        = "surfaces"
        private const val ARG_ENRICHMENT      = "enrichment"
        private const val ARG_SCREENSHOT_PATH = "screenshot_path"
        private const val ARG_SOURCE_LANG     = "source_lang"
        private const val ARG_PENDING_TRANSLATION = "pending_translation"
        private const val ARG_AUDIO_ANCHOR_MS = "audio_anchor_ms"

        /**
         * [surfaceForms] (display-word → surface in the sentence) and
         * [wordEnrichment] (display-word → pitch + frequencies) travel as an
         * atomic snapshot in the args — they are deliberately NOT re-read from
         * [LastSentenceCache] at render time. The global cache fields can rotate
         * to a different sentence before this sheet renders (e.g. behind the
         * Anki permission trampoline on the capture overlay), which would pair
         * one sentence's words with another's enrichment. Callers pass the same
         * snapshot their word rows came from.
         */
        fun newInstance(
            original: String,
            translation: String,
            wordResults: Map<String, Triple<String, String, Int>>,
            surfaceForms: Map<String, String>,
            wordEnrichment: Map<String, WordEnrichment>,
            screenshotPath: String?,
            sourceLangId: SourceLangId = SourceLangId.JA,
            pendingTranslation: com.playtranslate.model.PendingTranslation? = null,
            audioAnchorMs: Long? = null,
        ): AnkiReviewBottomSheet {
            return AnkiReviewBottomSheet().apply {
                val wordKeys = wordResults.keys.toTypedArray()
                val transport = transportPayloadFor(wordKeys, wordResults, wordEnrichment)
                arguments = Bundle().apply {
                    putString(ARG_ORIGINAL, original)
                    putString(ARG_TRANSLATION, translation)
                    // The launching result's deferred payload — the lazy fill
                    // completes it (rows + card in one batch) instead of
                    // translating alongside it.
                    if (pendingTranslation != null) {
                        putSerializable(ARG_PENDING_TRANSLATION, pendingTranslation)
                    }
                    putStringArray(ARG_WORDS,    wordKeys)
                    putStringArray(ARG_READINGS, wordResults.values.map { it.first }.toTypedArray())
                    // Size-gated pair: normally senses ride ARG_ENRICHMENT and
                    // sense-bearing meaning slots are blanked (definition text
                    // crosses once; meaningFromTransport re-derives). An
                    // oversized senses payload ships stripped enrichment +
                    // real flat meanings instead — see transportPayloadFor.
                    putStringArray(ARG_MEANINGS, transport.meanings)
                    putIntArray(ARG_FREQ_SCORES, wordResults.values.map { it.third }.toIntArray())
                    // Surfaces ride parallel to ARG_WORDS; enrichment as one
                    // Serializable map keyed by display word.
                    putStringArray(ARG_SURFACES, wordKeys.map { surfaceForms[it] ?: "" }.toTypedArray())
                    putSerializable(ARG_ENRICHMENT, transport.enrichment)
                    if (screenshotPath != null) putString(ARG_SCREENSHOT_PATH, screenshotPath)
                    putString(ARG_SOURCE_LANG, sourceLangId.code)
                    if (audioAnchorMs != null) putLong(ARG_AUDIO_ANCHOR_MS, audioAnchorMs)
                }
            }
        }
    }
}
