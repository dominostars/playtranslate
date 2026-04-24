package com.playtranslate.ui

import android.os.Bundle
import android.view.Gravity
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
import com.playtranslate.fullScreenDialogTheme
import com.playtranslate.language.SourceLangId
import com.playtranslate.themeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AnkiReviewBottomSheet : DialogFragment() {

    private lateinit var deckRowValue: TextView
    private lateinit var titleView: TextView
    private var sentenceContainer: FrameLayout? = null

    override fun getTheme(): Int = fullScreenDialogTheme(requireContext())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_anki_review, container, false)

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
        titleView = view.findViewById(R.id.tvAnkiSheetTitle)

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

        // Build the scroll content: Deck group on top, then a host
        // FrameLayout for the SentenceAnkiContentFragment's groups.
        val scrollContent = view.findViewById<FrameLayout>(R.id.sentenceContentContainer)
        val column = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        scrollContent.addView(column)

        addDeckGroup(column)
        sentenceContainer = FrameLayout(requireContext()).apply {
            id = View.generateViewId()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        column.addView(sentenceContainer)

        if (savedInstanceState == null) {
            val contentFragment = SentenceAnkiContentFragment.newInstance(
                original, translation, words, screenshotPath, sourceLangId = sourceLangId
            )
            childFragmentManager.beginTransaction()
                .replace(sentenceContainer!!.id, contentFragment, TAG_CONTENT)
                .commitNow()
        }

        refreshTitle()

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

    private fun addDeckGroup(parent: LinearLayout) {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        ankiGroupHeader(parent, getString(R.string.anki_group_deck))
        val card = ankiGroupCard(parent)

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (56 * density).toInt()
            setPadding((16 * density).toInt(), (14 * density).toInt(),
                (16 * density).toInt(), (14 * density).toInt())
            background = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).run {
                val d = getDrawable(0)
                recycle()
                d
            }
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(TextView(ctx).apply {
            text = getString(R.string.anki_deck_row_label)
            textSize = 15f
            setTextColor(ctx.themeColor(R.attr.ptText))
            setTypeface(typeface, android.graphics.Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        deckRowValue = TextView(ctx).apply {
            text = Prefs(ctx).ankiDeckName.ifBlank { getString(R.string.anki_deck_row_empty) }
            textSize = 13f
            setTextColor(ctx.themeColor(R.attr.ptTextMuted))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = (4 * density).toInt() }
        }
        row.addView(deckRowValue)
        row.addView(android.widget.ImageView(ctx).apply {
            setImageResource(R.drawable.ic_chevron_right)
            setColorFilter(ctx.themeColor(R.attr.ptTextMuted))
            layoutParams = LinearLayout.LayoutParams(
                (20 * density).toInt(), (20 * density).toInt()
            )
        })
        row.setOnClickListener {
            showAnkiDeckPicker { _, name ->
                deckRowValue.text = name
                refreshTitle()
            }
        }
        card.addView(row)
    }

    /** Updates the toolbar title to "Add to <Deck>" once a deck is known. */
    private fun refreshTitle() {
        val deckName = Prefs(requireContext()).ankiDeckName
        titleView.text = if (deckName.isBlank())
            getString(R.string.anki_sheet_title_default)
        else
            getString(R.string.anki_sheet_add_to_deck, deckName)
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
