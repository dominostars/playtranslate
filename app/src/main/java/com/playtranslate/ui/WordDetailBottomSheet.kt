package com.playtranslate.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.playtranslate.AnkiManager
import com.playtranslate.R
import com.playtranslate.applyAccentOverlay
import com.playtranslate.applyDialogEdgeToEdge
import com.playtranslate.fullScreenDialogTheme

/**
 * DialogFragment shell for the word-detail page. The page itself —
 * headword/readings/badges, definitions (styled + native), examples,
 * kanji/hanzi breakdown, member words, the Anki pill — lives in
 * [WordDetailBinder], shared with the floating [OverlayWorkspace]'s
 * over-game presentation ([WorkspaceWordDetailPage]). This shell keeps the
 * Fragment work: the full-screen dialog window, insets, the toolbar (hidden
 * when embedded in a host that provides its own), the childFragmentManager
 * stacking (nested details, the Anki review sheet), and the Activity-bound
 * permission request.
 */
class WordDetailBottomSheet : DialogFragment() {

    companion object {
        const val TAG = "WordDetailBottomSheet"
        private const val ARG_WORD            = "word"
        private const val ARG_READING         = "reading"
        private const val ARG_SCREENSHOT_PATH = "screenshot_path"
        private const val ARG_SENTENCE_ORIGINAL     = "sentence_original"
        private const val ARG_SENTENCE_TRANSLATION  = "sentence_translation"
        private const val ARG_SENTENCE_PENDING      = "sentence_pending"
        private const val ARG_SENTENCE_WORDS        = "sentence_words"
        private const val ARG_SENTENCE_READINGS     = "sentence_readings"
        private const val ARG_SENTENCE_MEANINGS     = "sentence_meanings"
        private const val ARG_SENTENCE_FREQ_SCORES  = "sentence_freq_scores"
        /** When true, this fragment is being embedded inside a host activity
         *  (drag-flow Sentence/Word tab in TranslationResultActivity) and
         *  should hide its own toolbar — the host already provides one. */
        private const val ARG_EMBEDDED        = "embedded"

        fun newInstance(
            word: String,
            reading: String? = null,
            screenshotPath: String? = null,
            sentenceOriginal: String? = null,
            sentenceTranslation: String? = null,
            sentenceWordResults: Map<String, Triple<String, String, Int>>? = null,
            embedded: Boolean = false,
            sentencePending: com.playtranslate.model.PendingTranslation? = null,
        ) = WordDetailBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_WORD, word)
                    if (reading != null) putString(ARG_READING, reading)
                    if (screenshotPath != null) putString(ARG_SCREENSHOT_PATH, screenshotPath)
                    if (sentenceOriginal != null) {
                        putString(ARG_SENTENCE_ORIGINAL, sentenceOriginal)
                        putString(ARG_SENTENCE_TRANSLATION, sentenceTranslation ?: "")
                        // Only meaningful alongside its own sentenceOriginal
                        // (resolveAnkiTranslation's caller contract).
                        if (sentencePending != null) {
                            putSerializable(ARG_SENTENCE_PENDING, sentencePending)
                        }
                        if (sentenceWordResults != null) {
                            putStringArray(ARG_SENTENCE_WORDS, sentenceWordResults.keys.toTypedArray())
                            putStringArray(ARG_SENTENCE_READINGS, sentenceWordResults.values.map { it.first }.toTypedArray())
                            putStringArray(ARG_SENTENCE_MEANINGS, sentenceWordResults.values.map { it.second }.toTypedArray())
                            putIntArray(ARG_SENTENCE_FREQ_SCORES, sentenceWordResults.values.map { it.third }.toIntArray())
                        }
                    }
                    if (embedded) putBoolean(ARG_EMBEDDED, true)
                }
            }
    }

    private var binder: WordDetailBinder? = null

    override fun getTheme(): Int = fullScreenDialogTheme(requireContext())

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        applyAccentOverlay(dialog.context.theme, requireContext())
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_word_detail, container, false)

    override fun onDestroyView() {
        binder?.release()
        binder = null
        super.onDestroyView()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setWindowAnimations(R.style.AnimSlideBottom)
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
        // Embedded mode (Sentence/Word tab in TranslationResultActivity)
        // hides the internal toolbar — the host activity already shows
        // a back button + segmented pill. Standalone (dialog) mode keeps
        // its own toolbar with the close button.
        val embedded = arguments?.getBoolean(ARG_EMBEDDED, false) == true
        val toolbar = view.findViewById<View>(R.id.wordDetailToolbar)
        if (embedded) {
            toolbar.isGone = true
        } else {
            view.findViewById<View>(R.id.btnBackDetail).setOnClickListener { dismiss() }
        }

        val word = arguments?.getString(ARG_WORD) ?: run {
            if (!embedded) dismiss()
            return
        }
        val args = arguments
        @Suppress("DEPRECATION")
        val sentencePending = args?.getSerializable(ARG_SENTENCE_PENDING)
            as? com.playtranslate.model.PendingTranslation
        val sentenceWordResults: Map<String, Triple<String, String, Int>>? =
            args?.getStringArray(ARG_SENTENCE_WORDS)?.let { words ->
                val readings = args.getStringArray(ARG_SENTENCE_READINGS) ?: emptyArray()
                val meanings = args.getStringArray(ARG_SENTENCE_MEANINGS) ?: emptyArray()
                val freqScores = args.getIntArray(ARG_SENTENCE_FREQ_SCORES) ?: IntArray(0)
                words.mapIndexed { i, w ->
                    w to Triple(
                        readings.getOrElse(i) { "" },
                        meanings.getOrElse(i) { "" },
                        freqScores.getOrElse(i) { 0 }
                    )
                }.toMap()
            }

        val b = WordDetailBinder(requireContext(), viewLifecycleOwner.lifecycleScope, FragmentUi())
        binder = b
        b.bind(
            view,
            WordDetailBinder.Args(
                word = word,
                reading = args?.getString(ARG_READING),
                screenshotPath = args?.getString(ARG_SCREENSHOT_PATH),
                sentenceOriginal = args?.getString(ARG_SENTENCE_ORIGINAL),
                sentenceTranslation = args?.getString(ARG_SENTENCE_TRANSLATION),
                sentenceWordResults = sentenceWordResults,
                sentencePending = sentencePending,
                embedded = embedded,
            ),
        )
    }

    /** The DialogFragment host's side of the binder seam. */
    private inner class FragmentUi : WordDetailBinder.Ui {
        override val isAlive: Boolean get() = isAdded

        // Embedded mode: the host activity implements [SentenceContextProvider]
        // and supplies live sentence context (VM-driven); the binder falls
        // back to launch-time args when there is none.
        override fun sentenceContext(): SentenceContext? =
            (activity as? SentenceContextProvider)?.currentSentenceContext()

        override fun openWordDetail(word: String, reading: String?) {
            newInstance(word = word, reading = reading).show(childFragmentManager, TAG)
        }

        override fun openAnkiReview(args: WordDetailBinder.WordAnkiArgs) {
            if (!AnkiManager(requireContext()).hasPermission()) {
                showAnkiPermissionRationaleDialog(requireActivity()) {
                    androidx.core.app.ActivityCompat.requestPermissions(
                        requireActivity(),
                        arrayOf(AnkiManager.PERMISSION), 0
                    )
                }
                return
            }
            WordAnkiReviewSheet.newInstance(
                args.word, args.reading, args.pos, args.definition, args.screenshotPath,
                freqScore = args.freqScore,
                isCommon = args.isCommon,
                sentenceOriginal = args.sentenceOriginal,
                sentenceTranslation = args.sentenceTranslation,
                sentenceWordResults = args.sentenceWordResults,
                sourceLangId = args.sourceLangId,
                sentencePending = args.sentencePending,
            ).show(childFragmentManager, WordAnkiReviewSheet.TAG)
        }

        override fun showAnkiNotInstalled() {
            showAnkiNotInstalledDialog(requireActivity())
        }

        override fun openFieldMapping(
            result: AnkiSendResult.NeedsMapping,
            mode: CardMode,
            fallback: WordDetailBinder.WordAnkiArgs,
        ) {
            // Dispatcher already toasted; open the mapping dialog.
            showAnkiCardTypeMappingDialog(result.model, mode) { _, _ -> }
        }

        override fun present(builder: OverlayAlert.Builder) {
            builder.show()
        }

        override fun launchOneTap(
            send: suspend () -> Pair<AnkiSendResult, CardMode>,
            presentResult: (Pair<AnkiSendResult, CardMode>) -> Unit,
        ) {
            // launchOneTapSend: dismissing the sheet mid-send must not cancel
            // the card; result handling runs only while the sheet's view is
            // STARTED, else degrades to an app-context toast.
            launchOneTapSend(
                appCtx = requireContext().applicationContext,
                send = send,
                resultOf = { it.first },
                modeOf = { it.second },
                presentResult = presentResult,
            )
        }
    }
}
