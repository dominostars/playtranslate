package com.playtranslate.ui

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.playtranslate.AnkiManager
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.fullScreenDialogTheme
import com.playtranslate.language.SourceLangId
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class WordAnkiReviewSheet : DialogFragment() {

    private var deckEntries: List<Map.Entry<Long, String>> = emptyList()
    private var isSentenceMode = false

    /** Optional listener called when this sheet is dismissed (used by WordAnkiReviewActivity). */
    var onDismissListener: DialogInterface.OnDismissListener? = null

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDismissListener?.onDismiss(dialog)
    }

    override fun getTheme(): Int = fullScreenDialogTheme(requireContext())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_word_anki_review, container, false)

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setWindowAnimations(R.style.AnimSlideRight)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.btnBackWordAnki).setOnClickListener { dismiss() }

        val args           = arguments ?: return
        val word           = args.getString(ARG_WORD) ?: return
        val reading        = args.getString(ARG_READING) ?: ""
        val pos            = args.getString(ARG_POS) ?: ""
        val definition     = args.getString(ARG_DEFINITION) ?: ""
        val freqScore      = args.getInt(ARG_FREQ_SCORE, 0)
        val screenshotPath = args.getString(ARG_SCREENSHOT_PATH)

        // Sentence data (optional)
        val sentenceOriginal    = args.getString(ARG_SENTENCE_ORIGINAL)
        val sentenceTranslation = args.getString(ARG_SENTENCE_TRANSLATION) ?: ""
        val hasSentenceData     = sentenceOriginal != null

        val spinnerDeck    = view.findViewById<Spinner>(R.id.spinnerWordAnkiDeck)
        val toggleGroup    = view.findViewById<MaterialButtonToggleGroup>(R.id.toggleCardType)
        val containerSentence = view.findViewById<View>(R.id.containerSentence)
        val containerWord     = view.findViewById<LinearLayout>(R.id.containerWord)

        // Word fields
        val tvHeadword     = view.findViewById<TextView>(R.id.tvWordAnkiHeadword)
        val readingRow     = view.findViewById<LinearLayout>(R.id.layoutWordAnkiReadingRow)
        val tvReading      = view.findViewById<TextView>(R.id.tvWordAnkiReading)
        val tvFreqStars    = view.findViewById<TextView>(R.id.tvWordAnkiFreqStars)
        val tvPos          = view.findViewById<TextView>(R.id.tvWordAnkiPos)
        val etDefinition   = view.findViewById<EditText>(R.id.etWordAnkiDefinition)

        // Populate word fields
        tvHeadword.text = word
        if (reading.isNotEmpty() || freqScore > 0) {
            readingRow.visibility = View.VISIBLE
            if (reading.isNotEmpty()) tvReading.text = reading
            if (freqScore > 0) {
                tvFreqStars.text = SentenceAnkiHtmlBuilder.starsString(freqScore)
                tvFreqStars.visibility = View.VISIBLE
            }
        }
        if (pos.isNotEmpty()) {
            tvPos.text = pos
            tvPos.visibility = View.VISIBLE
        }
        etDefinition.setText(definition)

        val sourceLangId = SourceLangId.fromCode(args.getString(ARG_SOURCE_LANG)) ?: SourceLangId.JA

        // Embed shared sentence content fragment
        if (hasSentenceData && savedInstanceState == null) {
            val sentenceWords = buildWordEntries(args)
            val contentFragment = SentenceAnkiContentFragment.newInstance(
                sentenceOriginal ?: return, sentenceTranslation, sentenceWords,
                screenshotPath, targetWord = word, sourceLangId = sourceLangId
            )
            childFragmentManager.beginTransaction()
                .replace(R.id.containerSentence, contentFragment, TAG_CONTENT)
                .commitNow()
        }

        // Toggle setup
        if (hasSentenceData) {
            toggleGroup.visibility = View.VISIBLE
            toggleGroup.check(R.id.btnModeSentence)
            isSentenceMode = true
            containerSentence.visibility = View.VISIBLE
            containerWord.visibility = View.GONE

            toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked) return@addOnButtonCheckedListener
                isSentenceMode = checkedId == R.id.btnModeSentence
                containerSentence.visibility = if (isSentenceMode) View.VISIBLE else View.GONE
                containerWord.visibility = if (isSentenceMode) View.GONE else View.VISIBLE
            }
        } else {
            containerWord.visibility = View.VISIBLE
        }

        loadAnkiDecksInto(spinnerDeck) { entries -> deckEntries = entries }

        view.findViewById<View>(R.id.btnWordAnkiSend).setOnClickListener { btn ->
            val deckId = deckEntries.getOrNull(spinnerDeck.selectedItemPosition)?.key
                ?: Prefs(requireContext()).ankiDeckId
            if (deckId < 0L) {
                Toast.makeText(requireContext(), getString(R.string.anki_no_deck_selected), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btn.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                if (isSentenceMode) {
                    sendSentenceToAnki(deckId)
                } else {
                    sendWordToAnki(word, reading, pos,
                        etDefinition.text.toString(), freqScore, deckId, screenshotPath)
                }
                btn.isEnabled = true
            }
        }
    }

    private fun getContentFragment(): SentenceAnkiContentFragment? =
        childFragmentManager.findFragmentByTag(TAG_CONTENT) as? SentenceAnkiContentFragment

    private fun buildWordEntries(args: Bundle): List<SentenceAnkiHtmlBuilder.WordEntry> {
        val wordArr    = args.getStringArray(ARG_SENTENCE_WORDS) ?: return emptyList()
        val readingArr = args.getStringArray(ARG_SENTENCE_READINGS) ?: emptyArray()
        val meaningArr = args.getStringArray(ARG_SENTENCE_MEANINGS) ?: emptyArray()
        val freqArr    = args.getIntArray(ARG_SENTENCE_FREQ_SCORES) ?: IntArray(0)
        val surfaces   = LastSentenceCache.surfaceForms ?: emptyMap()
        return wordArr.mapIndexed { i, w ->
            SentenceAnkiHtmlBuilder.WordEntry(
                w,
                readingArr.getOrElse(i) { "" },
                meaningArr.getOrElse(i) { "" },
                freqArr.getOrElse(i) { 0 },
                surfaceForm = surfaces[w] ?: ""
            )
        }
    }

    // ── Send: word mode ──────────────────────────────────────────────────────

    private suspend fun sendWordToAnki(
        word: String, reading: String, pos: String, definition: String,
        freqScore: Int, deckId: Long, screenshotPath: String?
    ) {
        val ankiManager = AnkiManager(requireContext())

        val imageFilename: String? = if (screenshotPath != null) {
            withContext(Dispatchers.IO) { ankiManager.addMediaFromFile(File(screenshotPath)) }
        } else null

        val front = buildWordFrontHtml(word)
        val back  = buildWordBackHtml(word, reading, pos, definition, freqScore, imageFilename)

        val success = withContext(Dispatchers.IO) { ankiManager.addNote(deckId, front, back) }
        val msg = if (success) getString(R.string.anki_added) else getString(R.string.anki_failed)
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        if (success) dismiss()
    }

    private fun buildWordFrontHtml(word: String): String = buildString {
        append("<style>")
        append("body{margin:0;padding:0;}")
        append("</style>")
        append("<div class=\"gl-front\" style=\"text-align:center;font-size:2.2em;padding:32px 16px;\">$word</div>")
    }

    private fun buildWordBackHtml(
        word: String, reading: String, pos: String,
        definition: String, freqScore: Int, imageFilename: String?
    ): String = buildString {
        append("<style>")
        append("body{visibility:hidden!important;white-space:normal!important;}")
        append(".gl-front{display:none!important;}")
        append("#answer{display:none!important;}")
        append(".gl-back{visibility:visible!important;}")
        append("</style>")
        append("<div class=\"gl-back\">")
        if (imageFilename != null) {
            append("<div style=\"text-align:center;margin:12px 0;\">")
            append("<img src=\"$imageFilename\" style=\"max-width:100%;border-radius:6px;\">")
            append("</div>")
        }
        append("<div style=\"text-align:center;font-size:1.8em;padding:12px 4px;\">$word</div>")
        if (reading.isNotEmpty()) {
            append("<div style=\"text-align:center;font-size:1.1em;color:#888;\">$reading</div>")
        }
        if (pos.isNotEmpty()) {
            append("<div style=\"text-align:center;font-size:0.85em;color:#888;\">$pos</div>")
        }
        if (freqScore > 0) {
            val stars = SentenceAnkiHtmlBuilder.starsString(freqScore)
            append("<div style=\"text-align:center;font-size:0.9em;color:#888;margin-top:4px;\">$stars</div>")
        }
        append("<div style=\"margin-bottom:12px;\"></div>")
        append("<hr>")
        val defHtml = definition.lines().filter { it.isNotBlank() }
            .joinToString("<br>") { it.trimStart() }
        append("<div style=\"font-size:1.1em;margin:12px 4px;\">$defHtml</div>")
        append("</div>")
    }

    // ── Send: sentence mode ──────────────────────────────────────────────────

    private suspend fun sendSentenceToAnki(deckId: Long) {
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
            parentFragmentManager.setFragmentResult(AnkiReviewBottomSheet.RESULT_ANKI_ADDED, bundleOf())
            dismiss()
        }
    }

    companion object {
        const val TAG = "WordAnkiReviewSheet"
        private const val TAG_CONTENT = "sentence_content"
        private const val ARG_WORD            = "word"
        private const val ARG_READING         = "reading"
        private const val ARG_POS             = "pos"
        private const val ARG_DEFINITION      = "definition"
        private const val ARG_SCREENSHOT_PATH = "screenshot_path"
        private const val ARG_FREQ_SCORE     = "freq_score"
        private const val ARG_SENTENCE_ORIGINAL     = "sentence_original"
        private const val ARG_SENTENCE_TRANSLATION  = "sentence_translation"
        private const val ARG_SENTENCE_WORDS        = "sentence_words"
        private const val ARG_SENTENCE_READINGS     = "sentence_readings"
        private const val ARG_SENTENCE_MEANINGS     = "sentence_meanings"
        private const val ARG_SENTENCE_FREQ_SCORES  = "sentence_freq_scores"
        private const val ARG_SOURCE_LANG     = "source_lang"

        fun newInstance(
            word: String,
            reading: String,
            pos: String,
            definition: String,
            screenshotPath: String?,
            freqScore: Int = 0,
            sentenceOriginal: String? = null,
            sentenceTranslation: String? = null,
            sentenceWordResults: Map<String, Triple<String, String, Int>>? = null,
            sourceLangId: SourceLangId = SourceLangId.JA
        ) = WordAnkiReviewSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_WORD, word)
                putString(ARG_READING, reading)
                putString(ARG_POS, pos)
                putString(ARG_DEFINITION, definition)
                putInt(ARG_FREQ_SCORE, freqScore)
                if (screenshotPath != null) putString(ARG_SCREENSHOT_PATH, screenshotPath)
                if (sentenceOriginal != null) {
                    putString(ARG_SENTENCE_ORIGINAL, sentenceOriginal)
                    putString(ARG_SENTENCE_TRANSLATION, sentenceTranslation ?: "")
                    if (sentenceWordResults != null) {
                        putStringArray(ARG_SENTENCE_WORDS, sentenceWordResults.keys.toTypedArray())
                        putStringArray(ARG_SENTENCE_READINGS, sentenceWordResults.values.map { it.first }.toTypedArray())
                        putStringArray(ARG_SENTENCE_MEANINGS, sentenceWordResults.values.map { it.second }.toTypedArray())
                        putIntArray(ARG_SENTENCE_FREQ_SCORES, sentenceWordResults.values.map { it.third }.toIntArray())
                    }
                }
                putString(ARG_SOURCE_LANG, sourceLangId.code)
            }
        }
    }
}
