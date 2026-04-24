package com.playtranslate.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
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
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.TatoebaClient
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
import java.util.Locale

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

    override fun onDestroyView() {
        moreExamplesGroup = null
        moreExamplesBody = null
        super.onDestroyView()
    }

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

        val content     = view.findViewById<LinearLayout>(R.id.detailContent)
        val scrollView  = view.findViewById<NestedScrollView>(R.id.detailScrollView)
        val btnAddAnki  = view.findViewById<Button>(R.id.btnWordAddToAnki)
        val tvLangPair  = view.findViewById<TextView>(R.id.tvDetailLangPair)

        // Prefill the language-pair eyebrow synchronously so the toolbar
        // isn't blank during the (brief) async dictionary lookup.
        val prefs = Prefs(requireContext().applicationContext)
        val sourceLangId = prefs.sourceLangId
        val targetLangCode = prefs.targetLang
        tvLangPair.text = getString(
            R.string.word_detail_lang_pair,
            sourceLangId.displayName(),
            langDisplayName(targetLangCode),
        )

        viewLifecycleOwner.lifecycleScope.launch {
            val appCtx = requireContext().applicationContext
            val engine = com.playtranslate.language.SourceLanguageEngines.get(appCtx, sourceLangId)
            moreExamplesSourceLang = sourceLangId.code
            moreExamplesTargetLang = targetLangCode
            val targetGlossDb = TargetGlossDatabaseProvider.get(appCtx, targetLangCode)
            val mlKitTranslator = TranslationManagerProvider.get(engine.profile.translationCode, targetLangCode)
            val enToTarget = TranslationManagerProvider.getEnToTarget(targetLangCode)
            val resolver = DefinitionResolver(engine, targetGlossDb,
                mlKitTranslator?.let { WordTranslator(it::translate) }, targetLangCode,
                enToTarget?.let { WordTranslator(it::translate) })
            val defResult = withContext(Dispatchers.IO) { resolver.lookup(word, readingHint) }
            val response = defResult?.response
            val entry = response?.entries?.firstOrNull()
            if (!isAdded) return@launch
            if (entry == null) {
                addNotFoundNotice(content, getString(R.string.word_detail_not_found, word))
                return@launch
            }
            val initialTranslations: List<List<String>>? = if (targetLangCode == "en") {
                entry.senses.map { s -> s.examples.map { it.translation } }
            } else null
            val translationRegistry = mutableMapOf<Pair<Int, Int>, TextView>()
            buildContent(content, entry, engine, sourceLangId, defResult, initialTranslations, translationRegistry)
            scrollView?.scrollTo(0, 0)

            val ankiManager = AnkiManager(requireContext())
            btnAddAnki.visibility = View.VISIBLE
            btnAddAnki.setOnClickListener {
                if (!ankiManager.isAnkiDroidInstalled()) {
                    showAnkiNotInstalledDialog(requireContext())
                } else {
                    openWordAnkiReview(word, entry, screenshotPath, defResult)
                }
            }

            if (targetLangCode != "en") {
                launch {
                    val translated = runCatching {
                        withContext(Dispatchers.IO) { resolver.translateExamples(response) }
                    }.getOrNull() ?: return@launch
                    if (!isAdded) return@launch
                    translated.forEachIndexed { sIdx, perSense ->
                        perSense.forEachIndexed { eIdx, tr ->
                            if (tr.isBlank()) return@forEachIndexed
                            translationRegistry[sIdx to eIdx]?.let { tv ->
                                tv.text = tr
                                tv.visibility = View.VISIBLE
                            }
                        }
                    }
                }
            }

            if (moreExamplesGroup != null) {
                launch {
                    val lookupWord = entry.headwords.firstOrNull()?.written
                        ?: entry.slug
                    val pairs = TatoebaClient.fetch(
                        word = lookupWord,
                        sourceLang = moreExamplesSourceLang,
                        targetLang = moreExamplesTargetLang,
                    )
                    if (!isAdded) return@launch
                    applyMoreExamples(pairs)
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

    private var moreExamplesSourceLang: String = ""
    private var moreExamplesTargetLang: String = ""

    private var moreExamplesGroup: LinearLayout? = null
    private var moreExamplesBody: LinearLayout? = null

    private suspend fun buildContent(
        content: LinearLayout,
        entry: DictionaryEntry,
        engine: com.playtranslate.language.SourceLanguageEngine,
        sourceLangId: SourceLangId,
        defResult: DefinitionResult?,
        initialTranslations: List<List<String>>?,
        translationRegistry: MutableMap<Pair<Int, Int>, TextView>,
    ) {
        // ── Header block: headword + reading + badges ─────────────────────
        addHeaderBlock(content, entry, sourceLangId)

        // ── Definitions group ─────────────────────────────────────────────
        val translatedDefs = when (defResult) {
            is DefinitionResult.Native -> defResult.translatedDefinitions
            is DefinitionResult.MachineTranslated -> defResult.translatedDefinitions
            is DefinitionResult.EnglishFallback -> defResult.translatedDefinitions
            else -> null
        }
        val targetByOrd = if (defResult is DefinitionResult.Native)
            defResult.targetSenses.associateBy { it.senseOrd } else null
        val numSenses = entry.senses.count { it.targetDefinitions.isNotEmpty() }

        // "Machine translated" banner fires when the user will actually
        // see MT output — either because no Native target was available
        // (MachineTranslated headword), or because the Native result
        // didn't cover every source sense so MT is filling the gaps.
        val anyMtDisplayed = entry.senses.withIndex().any { (idx, s) ->
            if (s.targetDefinitions.isEmpty()) return@any false
            val target = targetByOrd?.get(idx)
            target == null && translatedDefs?.getOrNull(idx)?.isNotBlank() == true
        }
        val mtBannerText = when {
            defResult is DefinitionResult.MachineTranslated ->
                getString(R.string.word_detail_mt_banner_named, defResult.translatedHeadword)
            anyMtDisplayed ->
                getString(R.string.word_detail_mt_banner)
            else -> null
        }
        if (mtBannerText != null) addMachineTranslatedBanner(content, mtBannerText)

        val definitionsSuffix = if (numSenses > 1)
            getString(R.string.word_detail_senses_count, numSenses) else null
        addGroupHeader(content, getString(R.string.word_detail_group_definitions), definitionsSuffix)
        val definitionsCard = addGroupCard(content)

        var displayCount = 0
        entry.senses.forEachIndexed { origIdx, sense ->
            if (sense.targetDefinitions.isEmpty()) return@forEachIndexed
            val target = targetByOrd?.get(origIdx)
            val posLabels = (target?.pos ?: sense.partsOfSpeech).filter { it.isNotBlank() }
            val glossList = target?.glosses
                ?: translatedDefs?.getOrNull(origIdx)?.let { listOf(it) }
                ?: sense.targetDefinitions
            val senseNumber = if (numSenses > 1) displayCount + 1 else null

            if (displayCount > 0) {
                // Numbered rows indent divider to 42dp (16dp row padding +
                // 16dp number column + 10dp gap) to align with the gloss
                // column; single-sense rows use the standard 16dp inset.
                addInsetDivider(definitionsCard, indentPx = if (senseNumber != null) dp(42) else dpRes(R.dimen.pt_row_h_padding))
            }
            addSenseRow(
                parent = definitionsCard,
                posLabels = posLabels,
                glossList = glossList,
                senseNumber = senseNumber,
                miscText = sense.misc.takeIf { it.isNotEmpty() }?.joinToString(" · "),
                examples = sense.examples,
                exampleTranslations = initialTranslations?.getOrNull(origIdx),
                senseIndex = origIdx,
                translationRegistry = translationRegistry,
            )
            displayCount++
        }

        // ── More examples (Tatoeba, online) ──────────────────────────────
        if (TatoebaClient.supports(moreExamplesSourceLang, moreExamplesTargetLang)) {
            addMoreExamplesPlaceholder(content)
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
                val headerTitle = when (characterDetails.first()) {
                    is KanjiDetail -> getString(R.string.word_detail_group_kanji)
                    is HanziDetail -> getString(R.string.word_detail_group_hanzi)
                }
                val suffix = if (characterDetails.size == 1)
                    getString(R.string.word_detail_char_count_one)
                else
                    getString(R.string.word_detail_chars_count, characterDetails.size)
                addGroupHeader(content, headerTitle, suffix)
                val charCard = addGroupCard(content)
                characterDetails.forEachIndexed { index, detail ->
                    if (index > 0) addInsetDivider(charCard, indentPx = dpRes(R.dimen.pt_row_h_padding))
                    addCharacterRow(charCard, detail)
                }
            }
        }
    }

    // ── Section builders ──────────────────────────────────────────────────

    /**
     * Header block at the top of the scroll content: big headword
     * (serif for JA, sans for everything else), reading line (if
     * distinct from the headword), then a badge row with the Common
     * pill and star-rating row.
     */
    private fun addHeaderBlock(
        parent: LinearLayout,
        entry: DictionaryEntry,
        sourceLangId: SourceLangId,
    ) {
        val ctx = requireContext()
        val block = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(4), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val firstHeadword = entry.headwords.firstOrNull()
        val written = firstHeadword?.written?.takeIf { it.isNotBlank() } ?: entry.slug
        val reading = firstHeadword?.reading?.takeIf { it.isNotBlank() && it != written }

        block.addView(TextView(ctx).apply {
            text = written
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 38f)
            setTextColor(ctx.themeColor(R.attr.ptText))
            // The spec asks for Noto Serif JP / Noto Sans SC. We don't
            // ship those fonts, so approximate with Typeface.SERIF for JA
            // (Android's serif fallback defers to a CJK serif face when
            // available) and the system sans for everything else. BOLD
            // stands in for the requested 600 weight.
            val base = if (sourceLangId == SourceLangId.JA)
                Typeface.SERIF
            else
                Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTypeface(base, Typeface.BOLD)
            letterSpacing = -0.02f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })

        if (reading != null) {
            block.addView(TextView(ctx).apply {
                text = reading
                textSize = 16f
                setTextColor(ctx.themeColor(R.attr.ptTextMuted))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dp(8) }
            })
        }

        val isCommon = entry.isCommon == true
        val freqStars = entry.freqScore.coerceIn(0, 5)
        if (isCommon || freqStars > 0) {
            val badgeRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dp(12) }
            }
            if (isCommon) {
                badgeRow.addView(buildCommonPill())
            }
            if (freqStars > 0) {
                badgeRow.addView(buildStarRow(freqStars))
            }
            block.addView(badgeRow)
        }

        parent.addView(block)
    }

    private fun buildCommonPill(): TextView {
        val ctx = requireContext()
        return TextView(ctx).apply {
            text = getString(R.string.word_detail_common)
            textSize = 11f
            setTextColor(ctx.themeColor(R.attr.ptAccent))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setBackgroundResource(R.drawable.bg_word_common_pill)
            setPadding(dp(10), dp(3), dp(10), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = dp(6) }
        }
    }

    /**
     * Five-star row. Filled stars (U+2605) are tinted with [R.attr.ptAccent];
     * outline stars (U+2606) use [R.attr.ptOutline] so they sit just above
     * the hairline without pulling focus.
     */
    private fun buildStarRow(filled: Int): LinearLayout {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val accent = ctx.themeColor(R.attr.ptAccent)
        val outline = ctx.themeColor(R.attr.ptOutline)
        for (i in 0 until 5) {
            val isFilled = i < filled
            row.addView(TextView(ctx).apply {
                text = if (isFilled) "★" else "☆"
                textSize = 13f
                setTextColor(if (isFilled) accent else outline)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginEnd = dp(1) }
            })
        }
        return row
    }

    /**
     * Warning-tinted banner (10% alpha fill, 25% alpha stroke) with a
     * triangle icon and single-line warning message. Replaces the old
     * muted-italic notice — the tinted chrome makes it scannable without
     * competing with the headword for attention.
     */
    private fun addMachineTranslatedBanner(parent: LinearLayout, text: String) {
        val ctx = requireContext()
        val warning = ctx.themeColor(R.attr.ptWarning)
        val bg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(withAlpha(warning, 0.10f))
            setStroke(dp(1), withAlpha(warning, 0.25f))
        }
        val banner = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg
            setPadding(dp(10), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(4) }
        }
        banner.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_warning_triangle)
            setColorFilter(warning)
            layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).also {
                it.marginEnd = dp(8)
            }
        })
        banner.addView(TextView(ctx).apply {
            this.text = text
            textSize = 12f
            setTextColor(warning)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        })
        parent.addView(banner)
    }

    /**
     * Inflate [R.layout.settings_group_header] and optionally fill the
     * right-hand [tvGroupBadge] with a muted [suffix] (e.g. "2 senses",
     * "Tatoeba", "1 character"). The layout already sizes the title;
     * this helper just routes the suffix into the existing badge slot.
     */
    private fun addGroupHeader(parent: LinearLayout, title: String, suffix: String? = null) {
        val header = LayoutInflater.from(requireContext())
            .inflate(R.layout.settings_group_header, parent, false)
        header.findViewById<TextView>(R.id.tvGroupTitle).text = title.uppercase(Locale.ROOT)
        val badge = header.findViewById<TextView>(R.id.tvGroupBadge)
        if (!suffix.isNullOrBlank()) {
            badge.text = suffix
            badge.textSize = 10f
            badge.visibility = View.VISIBLE
        } else {
            badge.visibility = View.GONE
        }
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

    private fun addInsetDivider(parent: LinearLayout, indentPx: Int = dpRes(R.dimen.pt_row_h_padding)) {
        val ctx = requireContext()
        parent.addView(View(ctx).apply {
            setBackgroundColor(ctx.themeColor(R.attr.ptDivider))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).also { it.marginStart = indentPx }
        })
    }

    /**
     * Adds a "More examples" group (header + card with placeholder +
     * attribution) to [parent]. The outer group and the inner sentences
     * container are stashed in [moreExamplesGroup] / [moreExamplesBody]
     * so [applyMoreExamples] can replace the placeholder asynchronously
     * without rebuilding the hierarchy.
     */
    private fun addMoreExamplesPlaceholder(parent: LinearLayout) {
        val ctx = requireContext()
        val group = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        addGroupHeader(
            group,
            getString(R.string.word_detail_more_examples),
            getString(R.string.word_detail_group_tatoeba),
        )
        val card = addGroupCard(group)

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dpRes(R.dimen.pt_row_h_padding),
                dpRes(R.dimen.pt_row_v_padding),
                dpRes(R.dimen.pt_row_h_padding),
                dpRes(R.dimen.pt_row_v_padding),
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        body.addView(TextView(ctx).apply {
            text = getString(R.string.word_detail_more_examples_loading)
            textSize = 13f
            setTextColor(ctx.themeColor(R.attr.ptTextMuted))
            setTypeface(null, Typeface.ITALIC)
        })
        card.addView(body)

        // Attribution footer — fixed at card foot, muted surface panel with
        // external-link icon + plain-text link that opens tatoeba.org.
        card.addView(buildTatoebaAttributionRow())

        parent.addView(group)
        moreExamplesGroup = group
        moreExamplesBody = body
    }

    private fun buildTatoebaAttributionRow(): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_word_tatoeba_attribution)
            setPadding(
                dpRes(R.dimen.pt_row_h_padding),
                dp(10),
                dpRes(R.dimen.pt_row_h_padding),
                dp(10),
            )
            isClickable = true
            isFocusable = true
            setOnClickListener {
                runCatching {
                    val i = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://tatoeba.org/")
                    )
                    startActivity(i)
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_open_in_new)
            setColorFilter(ctx.themeColor(R.attr.ptTextHint))
            layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).also {
                it.marginEnd = dp(6)
            }
        })
        row.addView(TextView(ctx).apply {
            text = getString(R.string.word_detail_tatoeba_attribution)
            textSize = 11f
            setTextColor(ctx.themeColor(R.attr.ptTextHint))
        })
        return row
    }

    /**
     * Replace the placeholder rendered by [addMoreExamplesPlaceholder]
     * with the supplied [pairs]. A non-null empty list hides the whole
     * group (empty-state noise outweighs its value). A null [pairs]
     * means network/API failure — surface a muted error instead of
     * hiding so the user knows the feature exists.
     */
    private fun applyMoreExamples(pairs: List<TatoebaClient.SentencePair>?) {
        val body = moreExamplesBody ?: return
        val group = moreExamplesGroup ?: return
        val ctx = requireContext()
        body.removeAllViews()
        // Examples render as their own rows (padding lives in each row),
        // so drop the body's outer padding before inserting them.
        body.setPadding(0, 0, 0, 0)

        when {
            pairs == null -> {
                body.setPadding(
                    dpRes(R.dimen.pt_row_h_padding),
                    dpRes(R.dimen.pt_row_v_padding),
                    dpRes(R.dimen.pt_row_h_padding),
                    dpRes(R.dimen.pt_row_v_padding),
                )
                body.addView(TextView(ctx).apply {
                    text = getString(R.string.word_detail_more_examples_error)
                    textSize = 13f
                    setTextColor(ctx.themeColor(R.attr.ptTextHint))
                })
            }
            pairs.isEmpty() -> {
                group.visibility = View.GONE
            }
            else -> {
                pairs.forEachIndexed { i, p ->
                    if (i > 0) addInsetDivider(body, indentPx = dpRes(R.dimen.pt_row_h_padding))
                    body.addView(buildTatoebaRow(p.source, p.target))
                }
            }
        }
    }

    /** A single Tatoeba sentence pair: source 15sp/500 on top, target
     *  13sp muted below, standard row padding. */
    private fun buildTatoebaRow(source: String, target: String): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dpRes(R.dimen.pt_row_h_padding),
                dpRes(R.dimen.pt_row_v_padding),
                dpRes(R.dimen.pt_row_h_padding),
                dpRes(R.dimen.pt_row_v_padding),
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(TextView(ctx).apply {
            text = source
            textSize = 15f
            setTextColor(ctx.themeColor(R.attr.ptText))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        row.addView(TextView(ctx).apply {
            text = target
            textSize = 13f
            setTextColor(ctx.themeColor(R.attr.ptTextMuted))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(3) }
        })
        return row
    }

    /**
     * Render one definition row. [senseNumber] is drawn as a dedicated
     * left column (accent-tinted mono) when non-null so the gloss wraps
     * cleanly under its own column instead of inheriting the number's
     * hanging indent.
     */
    private fun addSenseRow(
        parent: LinearLayout,
        posLabels: List<String>,
        glossList: List<String>,
        senseNumber: Int?,
        miscText: String?,
        examples: List<com.playtranslate.model.Example> = emptyList(),
        exampleTranslations: List<String>? = null,
        senseIndex: Int = -1,
        translationRegistry: MutableMap<Pair<Int, Int>, TextView>? = null,
    ) {
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

        if (senseNumber != null) {
            row.addView(TextView(ctx).apply {
                text = senseNumber.toString()
                textSize = 12f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(ctx.themeColor(R.attr.ptAccent))
                minWidth = dp(16)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also {
                    it.marginEnd = dp(10)
                    // Nudge the number down one pixel so its baseline sits
                    // under the POS eyebrow (or the gloss if POS is empty)
                    // instead of above it.
                    it.topMargin = dp(2)
                }
            })
        }

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }

        if (posLabels.isNotEmpty()) {
            col.addView(TextView(ctx).apply {
                text = posLabels.joinToString(" · ").uppercase(Locale.ROOT)
                textSize = 10f
                letterSpacing = 0.12f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(ctx.themeColor(R.attr.ptTextMuted))
                isAllCaps = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
        }

        col.addView(TextView(ctx).apply {
            text = glossList.joinToString("; ")
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ctx.themeColor(R.attr.ptText))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { if (posLabels.isNotEmpty()) it.topMargin = dp(6) }
        })

        if (miscText != null) {
            col.addView(TextView(ctx).apply {
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

        examples.forEachIndexed { i, ex ->
            val initialTranslation = exampleTranslations?.getOrNull(i) ?: ""
            val (block, translationTv) = buildExampleBlock(ctx, ex.text, initialTranslation)
            // Extra 2dp on top of the block's internal 8dp = the spec's
            // 12dp-from-misc / 10dp-between-examples gap.
            val topGap = if (i == 0) dp(10) else dp(2)
            (block.layoutParams as LinearLayout.LayoutParams).topMargin = topGap
            col.addView(block)
            if (senseIndex >= 0 && translationRegistry != null) {
                translationRegistry[senseIndex to i] = translationTv
            }
        }

        row.addView(col)
        parent.addView(row)
    }

    /**
     * Example block: left-rail (2dp accent @ 35% α) + a column with the
     * source line on top and the (async) translation beneath. The italic
     * treatment from the old design is gone — the rail alone carries
     * the "quoted example" semantic, which keeps CJK glyphs upright and
     * scannable.
     */
    private fun buildExampleBlock(ctx: Context, text: String, initialTranslation: String): Pair<View, TextView> {
        val accentRing = withAlpha(ctx.themeColor(R.attr.ptAccent), 0.35f)
        val block = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(8) }
        }
        block.addView(View(ctx).apply {
            setBackgroundColor(accentRing)
            layoutParams = LinearLayout.LayoutParams(dp(2), LinearLayout.LayoutParams.MATCH_PARENT)
        })
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginStart = dp(12) }
        }
        inner.addView(TextView(ctx).apply {
            this.text = text
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ctx.themeColor(R.attr.ptText))
            setLineSpacing(0f, 1.5f)
        })
        val translationTv = TextView(ctx).apply {
            this.text = initialTranslation
            visibility = if (initialTranslation.isNotBlank()) View.VISIBLE else View.GONE
            textSize = 13f
            setTextColor(ctx.themeColor(R.attr.ptTextMuted))
            setLineSpacing(0f, 1.45f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(2) }
        }
        inner.addView(translationTv)
        block.addView(inner)
        return block to translationTv
    }

    /**
     * Kanji / Hanzi row: 56dp surface tile holding the character, a
     * flex meaning column with labelled readings and mono meta, and
     * (JA only, when stroke count is known) a 36dp accent-tinted
     * stroke pill on the right.
     */
    private fun addCharacterRow(parent: LinearLayout, detail: CharacterDetail) {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
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

        // Tile — 56dp square with the character centered in a 34sp CJK
        // face. Uses a FrameLayout so the TextView can measure wrap but
        // still sit dead-center inside the fixed-size tile.
        val tile = android.widget.FrameLayout(ctx).apply {
            setBackgroundResource(R.drawable.bg_word_kanji_tile)
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).also {
                it.marginEnd = dp(14)
            }
        }
        tile.addView(TextView(ctx).apply {
            text = detail.literal.toString()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ctx.themeColor(R.attr.ptText))
            gravity = Gravity.CENTER
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            )
        })
        row.addView(tile)

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .also { it.gravity = Gravity.CENTER_VERTICAL }
        }

        if (detail.meanings.isNotEmpty()) {
            col.addView(TextView(ctx).apply {
                text = detail.meanings.take(4).joinToString(", ")
                textSize = 14f
                setTextColor(ctx.themeColor(R.attr.ptText))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
        }

        // Labelled readings — the "on:" / "kun:" / "pinyin:" labels use a
        // small-caps Inter-ish label and the value itself sits in the
        // default sans for CJK compatibility.
        val readingLines = buildReadingLines(detail)
        if (readingLines.isNotEmpty()) {
            val readingsView = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dp(3) }
            }
            readingLines.forEachIndexed { idx, (label, value) ->
                if (idx > 0) {
                    readingsView.addView(View(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(10), 1)
                    })
                }
                readingsView.addView(TextView(ctx).apply {
                    text = label.uppercase(Locale.ROOT) + ":"
                    textSize = 10f
                    isAllCaps = true
                    letterSpacing = 0.08f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(ctx.themeColor(R.attr.ptTextHint))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.marginEnd = dp(4) }
                })
                readingsView.addView(TextView(ctx).apply {
                    text = value
                    textSize = 12f
                    setTextColor(ctx.themeColor(R.attr.ptTextMuted))
                })
            }
            col.addView(readingsView)
        }

        val meta = buildMetaLine(detail)
        if (meta.isNotEmpty()) {
            col.addView(TextView(ctx).apply {
                text = meta
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(ctx.themeColor(R.attr.ptTextHint))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dp(3) }
            })
        }

        row.addView(col)

        // Stroke pill — JA only. We don't carry stroke counts for ZH so
        // the pill is skipped for HanziDetail rows entirely.
        if (detail is KanjiDetail && detail.strokeCount > 0) {
            row.addView(buildStrokePill(detail.strokeCount))
        }

        parent.addView(row)
    }

    private fun buildReadingLines(detail: CharacterDetail): List<Pair<String, String>> = when (detail) {
        is KanjiDetail -> buildList {
            if (detail.onReadings.isNotEmpty())  add("on" to detail.onReadings.joinToString(", "))
            if (detail.kunReadings.isNotEmpty()) add("kun" to detail.kunReadings.take(3).joinToString(", "))
        }
        is HanziDetail -> if (!detail.pinyin.isNullOrBlank())
            listOf("pinyin" to detail.pinyin) else emptyList()
    }

    private fun buildMetaLine(detail: CharacterDetail): String = when (detail) {
        is KanjiDetail -> buildList {
            if (detail.jlpt > 0)       add("JLPT N${detail.jlpt}")
            if (detail.grade in 1..6)  add("Grade ${detail.grade}")
            else if (detail.grade == 8) add("Secondary")
            if (detail.strokeCount > 0) add("${detail.strokeCount} strokes")
        }.joinToString("  ·  ")
        is HanziDetail -> buildList {
            if (detail.isCommon) add("Common")
            if (detail.freqScore > 0) add("★".repeat(detail.freqScore))
        }.joinToString("  ·  ")
    }

    private fun buildStrokePill(strokeCount: Int): LinearLayout {
        val ctx = requireContext()
        val pill = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_word_stroke_pill)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).also {
                it.marginStart = dp(10)
            }
        }
        pill.addView(TextView(ctx).apply {
            text = strokeCount.toString()
            textSize = 13f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(ctx.themeColor(R.attr.ptAccent))
            gravity = Gravity.CENTER
        })
        pill.addView(TextView(ctx).apply {
            text = "STR"
            textSize = 7f
            letterSpacing = 0.12f
            setTextColor(ctx.themeColor(R.attr.ptAccent))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
        })
        return pill
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

    /** Display name for a target-language ML Kit code, rendered in the
     *  user's own locale (so an English user sees "Spanish", a Japanese
     *  user sees "スペイン語"). */
    private fun langDisplayName(code: String): String =
        Locale(code).getDisplayLanguage(Locale.getDefault())
            .replaceFirstChar { it.uppercase(Locale.getDefault()) }

    /** Returns [color] with its alpha channel replaced by [alpha] (0..1). */
    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun dpRes(resId: Int) = resources.getDimensionPixelSize(resId)
}
