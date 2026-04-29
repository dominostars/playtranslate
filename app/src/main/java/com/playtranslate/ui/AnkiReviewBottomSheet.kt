package com.playtranslate.ui

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
import com.playtranslate.AnkiManager
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.applyAccentOverlay
import com.playtranslate.fullScreenDialogTheme
import com.playtranslate.language.SourceLangId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AnkiReviewBottomSheet : DialogFragment() {

    private var deckSubtitleView: TextView? = null

    override fun getTheme(): Int = fullScreenDialogTheme(requireContext())

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        applyAccentOverlay(dialog.context.theme, requireContext())
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_anki_review, container, false)

    override fun onDestroyView() {
        deckSubtitleView = null
        super.onDestroyView()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setWindowAnimations(R.style.AnimSlideRight)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.btnBackReview).setOnClickListener { dismiss() }

        val args = arguments ?: return
        val original       = args.getString(ARG_ORIGINAL) ?: ""
        val translation    = args.getString(ARG_TRANSLATION) ?: ""
        val screenshotPath = args.getString(ARG_SCREENSHOT_PATH)

        val words = mutableListOf<SentenceAnkiHtmlBuilder.WordEntry>()
        val wordArr    = args.getStringArray(ARG_WORDS) ?: emptyArray()
        val readingArr = args.getStringArray(ARG_READINGS) ?: emptyArray()
        val meaningArr = args.getStringArray(ARG_MEANINGS) ?: emptyArray()
        val freqArr    = args.getIntArray(ARG_FREQ_SCORES) ?: IntArray(0)
        val surfaces = LastSentenceCache.surfaceForms ?: emptyMap()
        wordArr.forEachIndexed { i, w ->
            words.add(SentenceAnkiHtmlBuilder.WordEntry(
                w, readingArr.getOrElse(i) { "" },
                meaningArr.getOrElse(i) { "" },
                freqArr.getOrElse(i) { 0 },
                surfaceForm = surfaces[w] ?: ""
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
        addAnkiDeckRow(deckHost) { refreshDeckSubtitle() }
        refreshDeckSubtitle()

        if (savedInstanceState == null) {
            val contentFragment = SentenceAnkiContentFragment.newInstance(
                original, translation, words, screenshotPath, sourceLangId = sourceLangId
            )
            childFragmentManager.beginTransaction()
                .replace(R.id.sentenceAnkiFragmentHost, contentFragment, TAG_CONTENT)
                .commitNow()
        }

        view.findViewById<View>(R.id.btnSendToAnki).setOnClickListener { btn ->
            val deckId = Prefs(requireContext()).ankiDeckId
            if (deckId < 0L) {
                Toast.makeText(requireContext(), getString(R.string.anki_no_deck_selected), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btn.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                sendToAnki(deckId)
                btn.isEnabled = true
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
        val data = getContentFragment()?.getCardData() ?: return
        val ankiManager = AnkiManager(requireContext())

        val imageFilename: String? = if (data.screenshotPath != null) {
            withContext(Dispatchers.IO) { ankiManager.addMediaFromFile(File(data.screenshotPath)) }
        } else null

        val front = SentenceAnkiHtmlBuilder.buildFrontHtml(data.source, data.words, data.selectedWords, data.sourceLangId)
        val back  = SentenceAnkiHtmlBuilder.buildBackHtml(data.source, data.target, data.words,
            imageFilename, data.selectedWords, data.sourceLangId)

        val success = withContext(Dispatchers.IO) { ankiManager.addNote(deckId, front, back) }
        val msg = if (success) getString(R.string.anki_added) else getString(R.string.anki_failed)
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

        if (success) {
            parentFragmentManager.setFragmentResult(RESULT_ANKI_ADDED, bundleOf())
            dismiss()
        }
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
        private const val ARG_SCREENSHOT_PATH = "screenshot_path"
        private const val ARG_SOURCE_LANG     = "source_lang"

        fun newInstance(
            original: String,
            translation: String,
            wordResults: Map<String, Triple<String, String, Int>>,
            screenshotPath: String?,
            sourceLangId: SourceLangId = SourceLangId.JA,
        ): AnkiReviewBottomSheet {
            return AnkiReviewBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_ORIGINAL, original)
                    putString(ARG_TRANSLATION, translation)
                    putStringArray(ARG_WORDS,    wordResults.keys.toTypedArray())
                    putStringArray(ARG_READINGS, wordResults.values.map { it.first }.toTypedArray())
                    putStringArray(ARG_MEANINGS, wordResults.values.map { it.second }.toTypedArray())
                    putIntArray(ARG_FREQ_SCORES, wordResults.values.map { it.third }.toIntArray())
                    if (screenshotPath != null) putString(ARG_SCREENSHOT_PATH, screenshotPath)
                    putString(ARG_SOURCE_LANG, sourceLangId.code)
                }
            }
        }
    }
}
