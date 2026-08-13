package com.playtranslate.ui

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.applyAccentOverlay
import com.playtranslate.applyDialogEdgeToEdge
import com.playtranslate.fullScreenDialogTheme
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.playtranslate.language.SourceLangId
import kotlinx.coroutines.launch

class AnkiReviewBottomSheet : DialogFragment() {

    private var deckSubtitleView: TextView? = null

    /** Controller for the Save button's idle ↔ loading swap. */
    private var sendButton: AnkiSendButton? = null

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

    override fun onDestroyView() {
        deckSubtitleView = null
        sendButton = null
        if (activity?.isFinishing == true || !isStateSaved) {
            context?.let { AnkiScreenshotPin.release(it, pinnedScreenshotPath) }
        }
        pinnedScreenshotPath = null
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
        // acted on, not whatever a later capture wrote there. The
        // pinned path is written back into args so a restored instance
        // can RE-OWN the pin (the child fragment keeps rendering it
        // from its own args either way) and release it on final
        // teardown instead of leaving it for the stale sweep.
        val screenshotPath = if (savedInstanceState == null) {
            AnkiScreenshotPin.pin(requireContext(), args.getString(ARG_SCREENSHOT_PATH))
                .also {
                    pinnedScreenshotPath = it
                    args.putString(ARG_SCREENSHOT_PATH, it)
                }
        } else {
            pinnedScreenshotPath = args.getString(ARG_SCREENSHOT_PATH)
                ?.takeIf { AnkiScreenshotPin.isPin(requireContext(), it) }
            null  // child already exists; no new child gets created
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

        // Both child hosts come from the layout XML — fragment-host IDs
        // need to stay stable so FragmentManager can re-attach the
        // SentenceAnkiContentFragment to the same container after
        // rotation / process recreation. Generated IDs change every
        // inflation and break the restore path.
        val deckHost = view.findViewById<LinearLayout>(R.id.sentenceAnkiDeckHost)
        deckSubtitleView = view.findViewById(R.id.tvAnkiSendSubtitle)
        addAnkiSection(
            parent = deckHost,
            mode = CardMode.SENTENCE,
            onDeckChanged = { refreshDeckSubtitle() },
            onCardTypeChanged = { /* no visible affordance reflects card type */ },
        )
        refreshDeckSubtitle()

        if (savedInstanceState == null) {
            val contentFragment = SentenceAnkiContentFragment.newInstance(
                original, translation, words, screenshotPath, sourceLangId = sourceLangId,
                audioAnchorMs = args.takeIf { it.containsKey(ARG_AUDIO_ANCHOR_MS) }
                    ?.getLong(ARG_AUDIO_ANCHOR_MS),
            )
            childFragmentManager.beginTransaction()
                .replace(R.id.sentenceAnkiFragmentHost, contentFragment, TAG_CONTENT)
                .commitNow()
        }

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

        // Lazy translation fill (mirror of WordAnkiReviewSheet's): a blank
        // incoming translation means the sheet was opened from a result whose
        // translation never ran — the hidden-section deferral — or hasn't
        // landed yet, and the content fragment's field would otherwise sit on
        // its placeholder forever. A deferred capture's pending rides the
        // args, and resolveAnkiTranslation routes it through the deferred
        // completion (History rows fill, idempotently, and NEVER gated by
        // the sentence-text cache — see its KDoc) instead of a bare
        // translateOnce. Failures are contained (null → applyTranslation
        // renders the error variant without clobbering user edits). Safe
        // after restore too: applyTranslation guards on the visible original
        // and on user-touched state.
        if (translation.isBlank() && original.isNotBlank()) {
            @Suppress("DEPRECATION")
            val pending = args.getSerializable(ARG_PENDING_TRANSLATION)
                as? com.playtranslate.model.PendingTranslation
            viewLifecycleOwner.lifecycleScope.launch {
                val outcome = resolveAnkiTranslation(pending, original)
                getContentFragment()?.applyTranslation(original, outcome?.text)
            }
        }
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

    private fun getContentFragment(): SentenceAnkiContentFragment? =
        childFragmentManager.findFragmentByTag(TAG_CONTENT) as? SentenceAnkiContentFragment

    private suspend fun sendToAnki(deckId: Long) {
        val content = getContentFragment() ?: run { sendButton?.setLoading(false); return }
        // Untrimmed game audio resolves here (trim editor opens once);
        // false = the user backed out of the editor — abort the send.
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
        private const val TAG_CONTENT = "sentence_content"

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
