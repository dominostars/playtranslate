package com.playtranslate.ui

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.NestedScrollView
import com.google.android.material.card.MaterialCardView
import com.playtranslate.themeColor

import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.playtranslate.AnkiManager
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.fullScreenDialogTheme
import com.playtranslate.language.DefinitionResolver
import com.playtranslate.language.DefinitionResult
import com.playtranslate.language.WordTranslator
import com.playtranslate.language.TargetGlossDatabaseProvider
import com.playtranslate.language.TranslationManagerProvider
import com.playtranslate.model.CharacterDetail
import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.HanziDetail
import com.playtranslate.model.KanjiDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WordDetailBottomSheet : DialogFragment() {

    companion object {
        const val TAG = "WordDetailBottomSheet"
        private const val ARG_WORD            = "word"
        private const val ARG_READING         = "reading"
        private const val ARG_SCREENSHOT_PATH = "screenshot_path"
        private const val ARG_SENTENCE_ORIGINAL     = "sentence_original"
        private const val ARG_SENTENCE_TRANSLATION  = "sentence_translation"
        private const val ARG_SENTENCE_WORDS        = "sentence_words"
        private const val ARG_SENTENCE_READINGS     = "sentence_readings"
        private const val ARG_SENTENCE_MEANINGS     = "sentence_meanings"
        private const val ARG_SENTENCE_FREQ_SCORES  = "sentence_freq_scores"

        fun newInstance(
            word: String,
            reading: String? = null,
            screenshotPath: String? = null,
            sentenceOriginal: String? = null,
            sentenceTranslation: String? = null,
            sentenceWordResults: Map<String, Triple<String, String, Int>>? = null
        ) = WordDetailBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_WORD, word)
                    if (reading != null) putString(ARG_READING, reading)
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
                }
            }
    }

    override fun getTheme(): Int = fullScreenDialogTheme(requireContext())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_word_detail, container, false)

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setWindowAnimations(R.style.AnimSlideBottom)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.btnBackDetail).setOnClickListener { dismiss() }

        val word           = arguments?.getString(ARG_WORD) ?: run { dismiss(); return }
        val readingHint    = arguments?.getString(ARG_READING)
        val screenshotPath = arguments?.getString(ARG_SCREENSHOT_PATH)
        view.findViewById<TextView>(R.id.tvDetailHeadword).text = word

        val content     = view.findViewById<LinearLayout>(R.id.detailContent)
        val scrollView  = view.findViewById<NestedScrollView>(R.id.detailScrollView)
        val btnAddAnki  = view.findViewById<Button>(R.id.btnWordAddToAnki)

        lifecycleScope.launch {
            val appCtx = requireContext().applicationContext
            val prefs = Prefs(appCtx)
            val engine = com.playtranslate.language.SourceLanguageEngines.get(appCtx, prefs.sourceLangId)
            val targetGlossDb = TargetGlossDatabaseProvider.get(appCtx, prefs.targetLang)
            val mlKitTranslator = TranslationManagerProvider.get(engine.profile.translationCode, prefs.targetLang)
            val enToTarget = TranslationManagerProvider.getEnToTarget(prefs.targetLang)
            val resolver = DefinitionResolver(engine, targetGlossDb,
                mlKitTranslator?.let { WordTranslator(it::translate) }, prefs.targetLang,
                enToTarget?.let { WordTranslator(it::translate) })
            val defResult = withContext(Dispatchers.IO) { resolver.lookup(word, readingHint) }
            val response = defResult?.response
            val entry = response?.entries?.firstOrNull()
            if (!isAdded) return@launch
            if (entry == null) {
                addNotFoundNotice(content, getString(R.string.word_detail_not_found, word))
                return@launch
            }
            buildContent(content, entry, engine, defResult)
            scrollView?.scrollTo(0, 0)

            // Show Add to Anki button once we have a valid entry
            val ankiManager = AnkiManager(requireContext())
            btnAddAnki.visibility = View.VISIBLE
            btnAddAnki.setOnClickListener {
                if (!ankiManager.isAnkiDroidInstalled()) {
                    showAnkiNotInstalledDialog(requireContext())
                } else {
                    openWordAnkiReview(word, entry, screenshotPath, defResult)
                }
            }
        }
    }

    private fun openWordAnkiReview(word: String, entry: DictionaryEntry, screenshotPath: String?, defResult: DefinitionResult?) {
        if (!AnkiManager(requireContext()).hasPermission()) {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.anki_permission_rationale_title)
                .setMessage(R.string.anki_permission_rationale_message)
                .setPositiveButton(R.string.btn_continue) { _, _ ->
                    // Can't register ActivityResultLauncher here; direct launch
                    androidx.core.app.ActivityCompat.requestPermissions(
                        requireActivity(),
                        arrayOf(AnkiManager.PERMISSION), 0
                    )
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        val reading = entry.headwords.firstOrNull()?.reading
            ?.takeIf { it != entry.headwords.firstOrNull()?.written } ?: ""

        val pos = entry.senses.firstOrNull()?.partsOfSpeech
            ?.filter { it.isNotBlank() }?.joinToString(" · ") ?: ""

        // Use resolved definitions (target-pack native or ML Kit translated)
        // instead of raw English glosses from the dictionary entry.
        val targetByOrd = if (defResult is DefinitionResult.Native)
            defResult.targetSenses.associateBy { it.senseOrd } else null
        val translatedDefs = when (defResult) {
            is DefinitionResult.MachineTranslated -> defResult.translatedDefinitions
            is DefinitionResult.EnglishFallback -> defResult.translatedDefinitions
            else -> null
        }
        val nonEmptySenseCount = entry.senses.count { it.targetDefinitions.isNotEmpty() }
        var displayNum = 0
        val definition = entry.senses
            .mapIndexedNotNull { i, sense ->
                if (sense.targetDefinitions.isEmpty()) return@mapIndexedNotNull null
                displayNum++
                val glosses = targetByOrd?.get(i)?.glosses?.joinToString("; ")
                    ?: translatedDefs?.getOrElse(i) { sense.targetDefinitions.joinToString("; ") }
                    ?: sense.targetDefinitions.joinToString("; ")
                val prefix = if (nonEmptySenseCount > 1) "$displayNum. " else ""
                prefix + glosses
            }
            .joinToString("\n")

        // Pass sentence context from parent if available
        val args = arguments
        val sentenceOriginal = args?.getString(ARG_SENTENCE_ORIGINAL)
        val sentenceTranslation = args?.getString(ARG_SENTENCE_TRANSLATION)
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

        val sourceLangId = com.playtranslate.Prefs(requireContext().applicationContext).sourceLangId
        WordAnkiReviewSheet.newInstance(
            word, reading, pos, definition, screenshotPath,
            freqScore = entry.freqScore,
            sentenceOriginal = sentenceOriginal,
            sentenceTranslation = sentenceTranslation,
            sentenceWordResults = sentenceWordResults,
            sourceLangId = sourceLangId
        ).show(childFragmentManager, WordAnkiReviewSheet.TAG)
    }

    private suspend fun buildContent(
        content: LinearLayout,
        entry: DictionaryEntry,
        engine: com.playtranslate.language.SourceLanguageEngine,
        defResult: DefinitionResult?
    ) {
        // ── Header block: readings + badges ───────────────────────────────
        val allReadings = entry.headwords.mapNotNull { f ->
            f.reading?.takeIf { it != f.written }
        }.distinct()
        val badges = buildList {
            if (entry.isCommon == true) add("Common")
            if (entry.freqScore > 0) add("★".repeat(entry.freqScore))
        }
        addHeaderBlock(content, allReadings, badges)

        // ── Machine-translated notice ─────────────────────────────────────
        val translatedDefs = when (defResult) {
            is DefinitionResult.MachineTranslated -> defResult.translatedDefinitions
            is DefinitionResult.EnglishFallback -> defResult.translatedDefinitions
            else -> null
        }
        val mtNotice = when {
            defResult is DefinitionResult.MachineTranslated ->
                "⚠ Machine translated: ${defResult.translatedHeadword}"
            translatedDefs != null ->
                "⚠ Machine translated"
            else -> null
        }
        if (mtNotice != null) addMachineTranslatedNotice(content, mtNotice)

        // ── Definitions group ─────────────────────────────────────────────
        addGroupHeader(content, "Definitions")
        val targetByOrd = if (defResult is DefinitionResult.Native)
            defResult.targetSenses.associateBy { it.senseOrd } else null
        val numSenses = entry.senses.count { it.targetDefinitions.isNotEmpty() }
        val definitionsCard = addGroupCard(content)

        var displayCount = 0
        entry.senses.forEachIndexed { origIdx, sense ->
            if (sense.targetDefinitions.isEmpty()) return@forEachIndexed
            val target = targetByOrd?.get(origIdx)
            val posLabels = (target?.pos ?: sense.partsOfSpeech).filter { it.isNotBlank() }
            val glosses = target?.glosses?.joinToString("; ")
                ?: translatedDefs?.getOrElse(origIdx) { sense.targetDefinitions.joinToString("; ") }
                ?: sense.targetDefinitions.joinToString("; ")
            val prefix = if (numSenses > 1) "${displayCount + 1}.  " else ""

            if (displayCount > 0) addInsetDivider(definitionsCard)
            addSenseRow(
                parent = definitionsCard,
                posLabels = posLabels,
                glossText = prefix + glosses,
                miscText = sense.misc.takeIf { it.isNotEmpty() }?.joinToString(" · ")
            )
            displayCount++
        }

        // ── Character breakdown group (Kanji / Hanzi) ────────────────────
        val cjkChars = (entry.headwords.firstOrNull()?.written ?: entry.slug)
            .filter { c -> c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF }
            .toList().distinct()

        if (cjkChars.isNotEmpty()) {
            val characterDetails = withContext(Dispatchers.IO) {
                cjkChars.mapNotNull { engine.lookupCharacter(it) }
            }
            if (isAdded && characterDetails.isNotEmpty()) {
                val header = when (characterDetails.first()) {
                    is KanjiDetail -> "Kanji"
                    is HanziDetail -> "Hanzi"
                }
                addGroupHeader(content, header)
                val charCard = addGroupCard(content)
                characterDetails.forEachIndexed { index, detail ->
                    if (index > 0) addInsetDivider(charCard)
                    addCharacterRow(charCard, detail)
                }
            }
        }
    }

    // ── Section builders ──────────────────────────────────────────────────

    private fun addHeaderBlock(
        parent: LinearLayout,
        readings: List<String>,
        badges: List<String>
    ) {
        if (readings.isEmpty() && badges.isEmpty()) return
        val ctx = requireContext()
        val block = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        if (readings.isNotEmpty()) {
            block.addView(TextView(ctx).apply {
                text = readings.joinToString("  /  ")
                textSize = 15f
                setTextColor(ctx.themeColor(R.attr.ptTextMuted))
            })
        }
        if (badges.isNotEmpty()) {
            val badgeRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { if (readings.isNotEmpty()) it.topMargin = dp(8) }
            }
            badges.forEach { badgeRow.addView(makeBadge(it)) }
            block.addView(badgeRow)
        }
        parent.addView(block)
    }

    private fun addMachineTranslatedNotice(parent: LinearLayout, text: String) {
        val ctx = requireContext()
        parent.addView(TextView(ctx).apply {
            this.text = text
            textSize = 12f
            setTextColor(ctx.themeColor(R.attr.ptTextHint))
            setTypeface(null, Typeface.ITALIC)
            setPadding(dp(4), 0, dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })
    }

    private fun addGroupHeader(parent: LinearLayout, title: String) {
        val header = LayoutInflater.from(requireContext())
            .inflate(R.layout.settings_group_header, parent, false)
        header.findViewById<TextView>(R.id.tvGroupTitle).text =
            title.uppercase(java.util.Locale.ROOT)
        parent.addView(header)
    }

    private fun addGroupCard(parent: LinearLayout): LinearLayout {
        val ctx = requireContext()
        val card = MaterialCardView(ctx).apply {
            setCardBackgroundColor(ctx.themeColor(R.attr.ptCard))
            radius = ctx.resources.getDimension(R.dimen.pt_radius)
            cardElevation = 0f
            strokeColor = ctx.themeColor(R.attr.ptDivider)
            strokeWidth = dp(1)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        card.addView(inner)
        parent.addView(card)
        return inner
    }

    private fun addInsetDivider(parent: LinearLayout) {
        val ctx = requireContext()
        parent.addView(View(ctx).apply {
            setBackgroundColor(ctx.themeColor(R.attr.ptDivider))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).also { it.marginStart = dpRes(R.dimen.pt_row_h_padding) }
        })
    }

    private fun addSenseRow(
        parent: LinearLayout,
        posLabels: List<String>,
        glossText: String,
        miscText: String?
    ) {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dpRes(R.dimen.pt_row_min_height)
            setPadding(
                dpRes(R.dimen.pt_row_h_padding),
                dpRes(R.dimen.pt_row_v_padding),
                dpRes(R.dimen.pt_row_h_padding),
                dpRes(R.dimen.pt_row_v_padding)
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        if (posLabels.isNotEmpty()) {
            row.addView(TextView(ctx).apply {
                text = posLabels.joinToString(" · ").uppercase(java.util.Locale.ROOT)
                textSize = 10f
                letterSpacing = 0.15f
                setTextColor(ctx.themeColor(R.attr.ptTextMuted))
                isAllCaps = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
        }

        row.addView(TextView(ctx).apply {
            text = glossText
            textSize = 16f
            setTextColor(ctx.themeColor(R.attr.ptText))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { if (posLabels.isNotEmpty()) it.topMargin = dp(6) }
        })

        if (miscText != null) {
            row.addView(TextView(ctx).apply {
                text = miscText
                textSize = 12f
                setTextColor(ctx.themeColor(R.attr.ptTextHint))
                setTypeface(null, Typeface.ITALIC)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dp(4) }
            })
        }

        parent.addView(row)
    }

    private fun addCharacterRow(parent: LinearLayout, detail: CharacterDetail) {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = dpRes(R.dimen.pt_row_min_height)
            setPadding(
                dpRes(R.dimen.pt_row_h_padding),
                dpRes(R.dimen.pt_row_v_padding),
                dpRes(R.dimen.pt_row_h_padding),
                dpRes(R.dimen.pt_row_v_padding)
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        row.addView(TextView(ctx).apply {
            text = detail.literal.toString()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
            setTextColor(ctx.themeColor(R.attr.ptText))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = dp(14); it.gravity = android.view.Gravity.CENTER_VERTICAL }
        })

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .also { it.gravity = android.view.Gravity.CENTER_VERTICAL }
        }

        if (detail.meanings.isNotEmpty()) {
            col.addView(TextView(ctx).apply {
                text = detail.meanings.take(4).joinToString(", ")
                textSize = 15f
                setTextColor(ctx.themeColor(R.attr.ptText))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
        }

        val readings = when (detail) {
            is KanjiDetail -> buildList {
                if (detail.onReadings.isNotEmpty())  add("on: ${detail.onReadings.joinToString(", ")}")
                if (detail.kunReadings.isNotEmpty()) add("kun: ${detail.kunReadings.take(3).joinToString(", ")}")
            }
            is HanziDetail -> listOfNotNull(detail.pinyin)
        }
        if (readings.isNotEmpty()) {
            col.addView(TextView(ctx).apply {
                text = readings.joinToString("  ")
                textSize = 12f
                setTextColor(ctx.themeColor(R.attr.ptTextMuted))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dp(2) }
            })
        }

        val meta = when (detail) {
            is KanjiDetail -> buildList {
                if (detail.jlpt > 0)       add("JLPT N${detail.jlpt}")
                if (detail.grade in 1..6)  add("Grade ${detail.grade}")
                else if (detail.grade == 8) add("Secondary")
                if (detail.strokeCount > 0) add("${detail.strokeCount} strokes")
            }
            is HanziDetail -> buildList {
                if (detail.isCommon) add("Common")
                if (detail.freqScore > 0) add("★".repeat(detail.freqScore))
            }
        }
        if (meta.isNotEmpty()) {
            col.addView(TextView(ctx).apply {
                text = meta.joinToString("  ·  ")
                textSize = 11f
                setTextColor(ctx.themeColor(R.attr.ptTextHint))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dp(2) }
            })
        }

        row.addView(col)
        parent.addView(row)
    }

    private fun addNotFoundNotice(parent: LinearLayout, text: String) {
        val ctx = requireContext()
        parent.addView(TextView(ctx).apply {
            this.text = text
            textSize = 14f
            setTextColor(ctx.themeColor(R.attr.ptTextHint))
            setPadding(dp(4), dp(12), dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })
    }

    private fun makeBadge(label: String, small: Boolean = false): TextView {
        val ctx = requireContext()
        return TextView(ctx).apply {
            text = label
            textSize = if (small) 11f else 12f
            setTextColor(ctx.themeColor(R.attr.ptTextMuted))
            setBackgroundResource(R.drawable.bg_badge)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = dp(6) }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun dpRes(resId: Int) = resources.getDimensionPixelSize(resId)
}
