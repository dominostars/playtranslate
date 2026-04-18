package com.playtranslate.ui

import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.playtranslate.R
import com.playtranslate.language.SourceLangId
import com.playtranslate.themeColor
import java.io.File

/**
 * Shared sentence content for Anki card creation.
 * Hosts: Japanese/translation EditTexts, word rows with tap-to-select, photo.
 *
 * Embedded by [AnkiReviewBottomSheet] (sentence-only) and
 * [WordAnkiReviewSheet] (sentence tab of the toggle).
 */
class SentenceAnkiContentFragment : Fragment() {

    private val words = mutableListOf<SentenceAnkiHtmlBuilder.WordEntry>()
    val selectedWords = mutableSetOf<String>()
    var includePhoto = true
        private set

    private var highlightColor = 0
    private lateinit var etJapanese: EditText
    private lateinit var etTranslation: EditText
    private lateinit var defsContainer: LinearLayout
    private var ivPhoto: ImageView? = null

    // ── Public API ────────────────────────────────────────────────────────

    data class CardData(
        val source: String,
        val target: String,
        val words: List<SentenceAnkiHtmlBuilder.WordEntry>,
        val selectedWords: Set<String>,
        val screenshotPath: String?,
        val sourceLangId: SourceLangId
    )

    fun getCardData(): CardData = CardData(
        source = etJapanese.text.toString(),
        target = etTranslation.text.toString(),
        words = words.toList(),
        selectedWords = selectedWords.toSet(),
        screenshotPath = if (includePhoto) arguments?.getString(ARG_SCREENSHOT_PATH) else null,
        sourceLangId = SourceLangId.fromCode(arguments?.getString(ARG_SOURCE_LANG)) ?: SourceLangId.JA
    )

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_sentence_anki_content, container, false)

    override fun onDestroyView() {
        ivPhoto?.setImageBitmap(null)
        ivPhoto = null
        super.onDestroyView()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        etJapanese     = view.findViewById(R.id.etSentenceJapanese)
        etTranslation  = view.findViewById(R.id.etSentenceTranslation)
        defsContainer  = view.findViewById(R.id.sentenceDefinitionsContainer)

        val tvPhotoLabel   = view.findViewById<TextView>(R.id.tvSentencePhotoLabel)
        val layoutPhoto    = view.findViewById<FrameLayout>(R.id.layoutSentencePhoto)
        ivPhoto            = view.findViewById(R.id.ivSentencePhoto)
        val btnRemovePhoto = view.findViewById<Button>(R.id.btnSentenceRemovePhoto)

        val args = arguments ?: return
        etJapanese.setText(args.getString(ARG_JAPANESE) ?: "")
        etTranslation.setText(args.getString(ARG_TRANSLATION) ?: "")

        // Build word list
        val wordArr    = args.getStringArray(ARG_WORDS) ?: emptyArray()
        val readingArr = args.getStringArray(ARG_READINGS) ?: emptyArray()
        val meaningArr = args.getStringArray(ARG_MEANINGS) ?: emptyArray()
        val freqArr    = args.getIntArray(ARG_FREQ_SCORES) ?: IntArray(0)
        val surfaces   = LastSentenceCache.surfaceForms ?: emptyMap()
        wordArr.forEachIndexed { i, w ->
            words.add(SentenceAnkiHtmlBuilder.WordEntry(
                w,
                readingArr.getOrElse(i) { "" },
                meaningArr.getOrElse(i) { "" },
                freqArr.getOrElse(i) { 0 },
                surfaceForm = surfaces[w] ?: ""
            ))
        }

        // Auto-select target word and sort selected to top (one-time)
        val targetWord = args.getString(ARG_TARGET_WORD)
        if (targetWord != null && words.any { it.word == targetWord }) {
            selectedWords.add(targetWord)
        }
        if (selectedWords.isNotEmpty()) {
            val sorted = words.sortedByDescending { it.word in selectedWords }
            words.clear()
            words.addAll(sorted)
        }

        // Photo setup
        val screenshotPath = args.getString(ARG_SCREENSHOT_PATH)
        if (screenshotPath != null) {
            val file = File(screenshotPath)
            if (file.exists()) {
                tvPhotoLabel.visibility = View.VISIBLE
                layoutPhoto.visibility  = View.VISIBLE
                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                if (bmp != null) ivPhoto?.setImageBitmap(bmp)
                btnRemovePhoto.setOnClickListener {
                    includePhoto = false
                    tvPhotoLabel.visibility = View.GONE
                    layoutPhoto.visibility  = View.GONE
                }
            }
        }

        rebuildWordRows()
    }

    // ── Word rows ─────────────────────────────────────────────────────────

    private fun rebuildWordRows() {
        defsContainer.removeAllViews()
        val density = resources.displayMetrics.density
        val dp8 = (8 * density).toInt()
        val ctx = view?.context ?: requireContext()
        val hlColor = ctx.themeColor(R.attr.colorTextTranslation)
        highlightColor = hlColor

        words.forEach { entry ->
            val isSelected = entry.word in selectedWords

            val dp2 = (2 * density).toInt()
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp8, dp8 / 2, dp8, dp8 + 4)
                if (isSelected) {
                    val lp = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp2 }
                    layoutParams = lp
                    background = GradientDrawable().apply {
                        setColor((hlColor and 0x00FFFFFF) or 0x26000000)
                        cornerRadius = 8 * density
                    }
                }
            }

            val textBlock = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            textBlock.addView(TextView(ctx).apply {
                text = entry.word
                textSize = if (isSelected) 16f else 14f
                setTextColor(if (isSelected) hlColor else ctx.themeColor(R.attr.colorTextPrimary))
                setTypeface(null, Typeface.BOLD)
            })

            if (entry.reading.isNotEmpty() || entry.freqScore > 0) {
                val readingLine = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                if (entry.reading.isNotEmpty()) {
                    readingLine.addView(TextView(ctx).apply {
                        text = entry.reading
                        textSize = if (isSelected) 12f else 11f
                        setTextColor(ContextCompat.getColor(ctx, R.color.text_hint))
                        if (isSelected) setTypeface(null, Typeface.BOLD)
                    })
                }
                if (entry.freqScore > 0) {
                    readingLine.addView(TextView(ctx).apply {
                        text = "  " + SentenceAnkiHtmlBuilder.starsString(entry.freqScore)
                        textSize = if (isSelected) 11f else 10f
                        setTextColor(ContextCompat.getColor(ctx, R.color.text_hint))
                    })
                }
                textBlock.addView(readingLine)
            }

            entry.meaning.split("\n").filter { it.isNotBlank() }.forEach { line ->
                textBlock.addView(TextView(ctx).apply {
                    text = line
                    textSize = if (isSelected) 13f else 12f
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                    setPadding(16, 0, 0, 0)
                    if (isSelected) setTypeface(null, Typeface.BOLD)
                })
            }

            val btnRemove = Button(ctx).apply {
                text = "\u2715"
                textSize = 14f
                setBackgroundResource(android.R.color.transparent)
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    selectedWords.remove(entry.word)
                    words.removeAll { it.word == entry.word }
                    rebuildWordRows()
                }
            }

            row.setOnClickListener {
                if (entry.word in selectedWords) selectedWords.remove(entry.word)
                else selectedWords.add(entry.word)
                rebuildWordRows()
            }

            row.addView(textBlock)
            row.addView(btnRemove)
            defsContainer.addView(row)
        }
    }

    companion object {
        private const val ARG_JAPANESE        = "japanese"
        private const val ARG_TRANSLATION     = "translation"
        private const val ARG_WORDS           = "words"
        private const val ARG_READINGS        = "readings"
        private const val ARG_MEANINGS        = "meanings"
        private const val ARG_FREQ_SCORES     = "freq_scores"
        private const val ARG_SCREENSHOT_PATH = "screenshot_path"
        private const val ARG_TARGET_WORD     = "target_word"
        private const val ARG_SOURCE_LANG     = "source_lang"

        fun newInstance(
            japanese: String,
            translation: String,
            words: List<SentenceAnkiHtmlBuilder.WordEntry>,
            screenshotPath: String?,
            targetWord: String? = null,
            sourceLangId: SourceLangId = SourceLangId.JA
        ) = SentenceAnkiContentFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_JAPANESE, japanese)
                putString(ARG_TRANSLATION, translation)
                putStringArray(ARG_WORDS, words.map { it.word }.toTypedArray())
                putStringArray(ARG_READINGS, words.map { it.reading }.toTypedArray())
                putStringArray(ARG_MEANINGS, words.map { it.meaning }.toTypedArray())
                putIntArray(ARG_FREQ_SCORES, words.map { it.freqScore }.toIntArray())
                if (screenshotPath != null) putString(ARG_SCREENSHOT_PATH, screenshotPath)
                if (targetWord != null) putString(ARG_TARGET_WORD, targetWord)
                putString(ARG_SOURCE_LANG, sourceLangId.code)
            }
        }
    }
}
