package com.playtranslate.ui

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.playtranslate.themeColor

import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.playtranslate.AnkiManager
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.fullScreenDialogTheme
import com.playtranslate.dictionary.DictionaryManager
import com.playtranslate.language.DefinitionResolver
import com.playtranslate.language.DefinitionResult
import com.playtranslate.language.WordTranslator
import com.playtranslate.language.TargetGlossDatabaseProvider
import com.playtranslate.language.TranslationManagerProvider
import com.playtranslate.model.DictionaryEntry
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
        val scrollView  = view.findViewById<android.widget.ScrollView>(R.id.detailScrollView)
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
                addText(content, getString(R.string.word_detail_not_found, word), 14f, R.attr.ptTextHint)
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

    private suspend fun buildContent(content: LinearLayout, entry: DictionaryEntry, engine: com.playtranslate.language.SourceLanguageEngine, defResult: DefinitionResult?) {
        // ── Readings ─────────────────────────────────────────────────────
        val allReadings = entry.headwords.mapNotNull { f ->
            f.reading?.takeIf { it != f.written }
        }.distinct()
        if (allReadings.isNotEmpty()) {
            addText(content, allReadings.joinToString("  /  "), 15f, R.attr.ptTextHint)
        }

        // ── Badges: Common + frequency ────────────────────────────────────
        val badgeRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = rowParams(topMargin = 8)
        }
        if (entry.isCommon == true)  badgeRow.addView(makeBadge("Common"))
        if (entry.freqScore > 0)     badgeRow.addView(makeBadge("★".repeat(entry.freqScore)))
        if (badgeRow.childCount > 0) content.addView(badgeRow)

        addDivider(content, topMargin = 14)

        // ── Senses ────────────────────────────────────────────────────────
        // Build target-sense lookup for ordinal alignment (Native result)
        val targetByOrd = if (defResult is DefinitionResult.Native)
            defResult.targetSenses.associateBy { it.senseOrd } else null

        // Translated definitions from MachineTranslated or EnglishFallback
        val translatedDefs = when (defResult) {
            is DefinitionResult.MachineTranslated -> defResult.translatedDefinitions
            is DefinitionResult.EnglishFallback -> defResult.translatedDefinitions
            else -> null
        }

        // Machine-translated label
        val isMachineTranslated = defResult is DefinitionResult.MachineTranslated || translatedDefs != null
        if (defResult is DefinitionResult.MachineTranslated) {
            addText(content, "⚠ Machine translated: ${defResult.translatedHeadword}",
                13f, R.attr.ptTextHint, topMargin = 0, italic = true)
        } else if (translatedDefs != null) {
            addText(content, "⚠ Machine translated",
                13f, R.attr.ptTextHint, topMargin = 0, italic = true)
        }

        // Iterate with original index so targetByOrd and translatedDefs align correctly
        // (both are indexed against the unfiltered entry.senses list).
        var displayCount = 0
        entry.senses.forEachIndexed { origIdx, sense ->
            if (sense.targetDefinitions.isEmpty()) return@forEachIndexed
            val target = targetByOrd?.get(origIdx)
            val posLabels = (target?.pos ?: sense.partsOfSpeech).filter { it.isNotBlank() }
            if (posLabels.isNotEmpty()) {
                val posRow = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = rowParams(topMargin = if (displayCount == 0) 0 else 12)
                }
                posLabels.forEach { posRow.addView(makeBadge(it, small = true)) }
                content.addView(posRow)
            }

            val glosses = target?.glosses?.joinToString("; ")
                ?: translatedDefs?.getOrElse(origIdx) { sense.targetDefinitions.joinToString("; ") }
                ?: sense.targetDefinitions.joinToString("; ")
            val numSenses = entry.senses.count { it.targetDefinitions.isNotEmpty() }
            val prefix = if (numSenses > 1) "${displayCount + 1}.  " else ""
            addText(content, prefix + glosses, 16f, R.attr.ptText, topMargin = 4)

            if (sense.misc.isNotEmpty()) {
                addText(content, sense.misc.joinToString(" · "), 12f, R.attr.ptTextHint,
                    topMargin = 2, italic = true)
            }
            displayCount++
        }

        // ── Kanji breakdown ───────────────────────────────────────────────
        val cjkChars = (entry.headwords.firstOrNull()?.written ?: entry.slug)
            .filter { c -> c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF }
            .toList().distinct()

        if (cjkChars.isNotEmpty()) {
            val kanjiDetails = withContext(Dispatchers.IO) {
                cjkChars.mapNotNull { engine.lookupCharacter(it) }
            }
            if (isAdded && kanjiDetails.isNotEmpty()) {
                addDivider(content, topMargin = 14)
                addSectionLabel(content, "Kanji")
                kanjiDetails.forEach { addKanjiCard(content, it) }
            }
        }
    }

    // ── View helpers ──────────────────────────────────────────────────────

    private fun addText(
        parent: LinearLayout, text: String, sizeSp: Float, colorAttr: Int,
        topMargin: Int = 0, italic: Boolean = false
    ) {
        parent.addView(TextView(requireContext()).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(requireContext().themeColor(colorAttr))
            if (italic) setTypeface(null, Typeface.ITALIC)
            layoutParams = rowParams(topMargin)
        })
    }

    private fun addSectionLabel(parent: LinearLayout, label: String) {
        parent.addView(TextView(requireContext()).apply {
            text = label.uppercase()
            textSize = 10f
            letterSpacing = 0.15f
            setTextColor(requireContext().themeColor(R.attr.ptTextMuted))
            setTypeface(null, Typeface.BOLD)
            layoutParams = rowParams(topMargin = 4, bottomMargin = 4)
        })
    }

    private fun addDivider(parent: LinearLayout, topMargin: Int = 0) {
        parent.addView(View(requireContext()).apply {
            setBackgroundColor(requireContext().themeColor(R.attr.ptDivider))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).also { it.topMargin = dp(topMargin) }
        })
    }

    private fun makeBadge(label: String, small: Boolean = false): TextView {
        return TextView(requireContext()).apply {
            text = label
            textSize = if (small) 11f else 12f
            setTextColor(requireContext().themeColor(R.attr.ptTextMuted))
            setBackgroundResource(R.drawable.bg_badge)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = dp(6) }
        }
    }

    private fun addKanjiCard(parent: LinearLayout, detail: KanjiDetail) {
        val ctx = requireContext()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(ctx.themeColor(R.attr.ptCard))
            layoutParams = rowParams(topMargin = 8)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        card.addView(TextView(ctx).apply {
            text = detail.literal.toString()
            textSize = 36f
            setTextColor(ctx.themeColor(R.attr.ptText))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = dp(14) }
        })

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        if (detail.meanings.isNotEmpty()) {
            col.addView(TextView(ctx).apply {
                text = detail.meanings.take(4).joinToString(", ")
                textSize = 14f
                setTextColor(ctx.themeColor(R.attr.ptText))
            })
        }

        val readings = buildList {
            if (detail.onReadings.isNotEmpty())  add("on: ${detail.onReadings.joinToString(", ")}")
            if (detail.kunReadings.isNotEmpty()) add("kun: ${detail.kunReadings.take(3).joinToString(", ")}")
        }
        if (readings.isNotEmpty()) {
            col.addView(TextView(ctx).apply {
                text = readings.joinToString("  ")
                textSize = 12f
                setTextColor(ctx.themeColor(R.attr.ptTextHint))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dp(2) }
            })
        }

        val meta = buildList {
            if (detail.jlpt > 0)       add("JLPT N${detail.jlpt}")
            if (detail.grade in 1..6)  add("Grade ${detail.grade}")
            else if (detail.grade == 8) add("Secondary")
            if (detail.strokeCount > 0) add("${detail.strokeCount} strokes")
        }
        if (meta.isNotEmpty()) {
            col.addView(TextView(ctx).apply {
                text = meta.joinToString("  ·  ")
                textSize = 11f
                setTextColor(ctx.themeColor(R.attr.ptTextHint))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dp(2) }
            })
        }

        card.addView(col)
        parent.addView(card)
    }

    private fun rowParams(topMargin: Int = 0, bottomMargin: Int = 0) =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(topMargin); it.bottomMargin = dp(bottomMargin) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
