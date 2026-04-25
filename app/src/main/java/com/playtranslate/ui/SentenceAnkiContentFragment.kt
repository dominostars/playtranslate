package com.playtranslate.ui

import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.playtranslate.R
import com.playtranslate.language.SourceLangId
import com.playtranslate.themeColor
import java.io.File

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

    private lateinit var root: LinearLayout
    private lateinit var etOriginal: EditText
    private lateinit var etTranslation: EditText
    private lateinit var wordsCard: LinearLayout
    private lateinit var wordsHeaderTitle: TextView
    private var screenshotGroup: View? = null
    private var ivPhoto: ImageView? = null

    data class CardData(
        val source: String,
        val target: String,
        val words: List<SentenceAnkiHtmlBuilder.WordEntry>,
        val selectedWords: Set<String>,
        val screenshotPath: String?,
        val sourceLangId: SourceLangId,
    )

    fun getCardData(): CardData = CardData(
        source = etOriginal.text.toString(),
        target = etTranslation.text.toString(),
        words = words.toList(),
        selectedWords = selectedWords.toSet(),
        screenshotPath = if (includePhoto) arguments?.getString(ARG_SCREENSHOT_PATH) else null,
        sourceLangId = SourceLangId.fromCode(arguments?.getString(ARG_SOURCE_LANG)) ?: SourceLangId.JA,
    )

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
        wordArr.forEachIndexed { i, w ->
            words.add(SentenceAnkiHtmlBuilder.WordEntry(
                w,
                readingArr.getOrElse(i) { "" },
                meaningArr.getOrElse(i) { "" },
                freqArr.getOrElse(i) { 0 },
                surfaceForm = surfaces[w] ?: ""
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
    }

    // ── Build ────────────────────────────────────────────────────────────

    private fun buildContent(original: String, translation: String, screenshotPath: String?) {
        val ctx = requireContext()
        root.removeAllViews()

        // Original — id is pinned to a resource id (etAnkiOriginal) so
        // Android's automatic view-state save/restore can round-trip
        // the typed text across process death without us writing a
        // manual onSaveInstanceState pipeline.
        ankiGroupHeader(root, getString(R.string.anki_group_original))
        val originalCard = ankiGroupCard(root)
        etOriginal = buildEditField(initial = original).apply {
            id = R.id.etAnkiOriginal
        }
        originalCard.addView(buildEditableFrame(etOriginal))

        // Translation — same trick with R.id.etAnkiTranslation.
        ankiGroupHeader(root, getString(R.string.anki_group_translation))
        val translationCard = ankiGroupCard(root)
        etTranslation = buildEditField(initial = translation).apply {
            id = R.id.etAnkiTranslation
        }
        translationCard.addView(buildEditableFrame(etTranslation))

        // Words on card
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
                val screenshotHeader = root.getChildAt(root.childCount - 1)
                val screenshotCard = ankiGroupCard(root)
                val screenshotCardWrapper = root.getChildAt(root.childCount - 1)
                screenshotGroup = screenshotCardWrapper // remove this card on dismiss
                addScreenshotRow(screenshotCard, file) {
                    includePhoto = false
                    root.removeView(screenshotHeader)
                    root.removeView(screenshotCardWrapper)
                    screenshotGroup = null
                }
            }
        }
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
        val ctx = requireContext()
        // Strip everything after the helper row + its divider, then
        // re-emit current word rows. Helper row is at index 0; divider
        // at index 1; word rows live from index 2 onward.
        while (wordsCard.childCount > 2) {
            wordsCard.removeViewAt(wordsCard.childCount - 1)
        }
        words.forEachIndexed { i, entry ->
            if (i > 0) ankiInsetDivider(wordsCard, indentDp = 16)
            wordsCard.addView(buildWordRow(entry))
        }
        // Live count in the group header.
        wordsHeaderTitle.text = getString(R.string.anki_group_words_count, words.size)
            .uppercase(java.util.Locale.ROOT)
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
        if (entry.reading.isNotBlank()) {
            topLine.addView(TextView(ctx).apply {
                text = entry.reading
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
                rebuildWordRows()
            }
        })
        return row
    }

    companion object {
        private const val ARG_ORIGINAL        = "japanese"
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
            sourceLangId: SourceLangId = SourceLangId.JA,
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
            }
        }
    }
}
