package com.playtranslate.ui

import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
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
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.playtranslate.AnkiManager
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.fullScreenDialogTheme
import com.playtranslate.language.DefinitionResolver
import com.playtranslate.language.DefinitionResult
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.language.TargetGlossDatabaseProvider
import com.playtranslate.language.TranslationManagerProvider
import com.playtranslate.language.WordTranslator
import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.Example
import com.playtranslate.themeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class WordAnkiReviewSheet : DialogFragment() {

    private var isSentenceMode = false

    private lateinit var titleView: TextView
    private lateinit var toggleHost: FrameLayout
    private lateinit var deckRowValue: TextView
    private lateinit var scrollContent: LinearLayout
    private lateinit var sentenceContainer: FrameLayout
    private lateinit var wordContainer: LinearLayout
    private var definitionsCard: LinearLayout? = null
    /** Cached resolved entry from the in-sheet dictionary lookup; the
     *  Definitions group renders from this and the Anki-card HTML is
     *  built from it on send. Null until the async lookup completes. */
    private var resolvedEntry: DictionaryEntry? = null
    private var resolvedDefResult: DefinitionResult? = null

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

    override fun onDestroyView() {
        definitionsCard = null
        super.onDestroyView()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.btnBackWordAnki).setOnClickListener { dismiss() }

        val args           = arguments ?: return
        val word           = args.getString(ARG_WORD) ?: return
        val reading        = args.getString(ARG_READING) ?: ""
        val pos            = args.getString(ARG_POS) ?: ""
        val fallbackDefinition = args.getString(ARG_DEFINITION) ?: ""
        val freqScore      = args.getInt(ARG_FREQ_SCORE, 0)
        val isCommon       = args.getBoolean(ARG_IS_COMMON, false)
        val screenshotPath = args.getString(ARG_SCREENSHOT_PATH)

        val sentenceOriginal    = args.getString(ARG_SENTENCE_ORIGINAL)
        val sentenceTranslation = args.getString(ARG_SENTENCE_TRANSLATION) ?: ""
        val hasSentenceData     = sentenceOriginal != null

        val sourceLangId = SourceLangId.fromCode(args.getString(ARG_SOURCE_LANG)) ?: SourceLangId.JA

        titleView = view.findViewById(R.id.tvWordAnkiSheetTitle)
        toggleHost = view.findViewById(R.id.wordAnkiToolbarToggle)
        scrollContent = view.findViewById(R.id.wordAnkiScrollContent)

        if (hasSentenceData) {
            titleView.visibility = View.GONE
            toggleHost.visibility = View.VISIBLE
            buildAnkiModeToggle(
                container = toggleHost,
                leftLabel = getString(R.string.anki_mode_sentence),
                rightLabel = getString(R.string.anki_mode_word),
                leftActive = true,
            ) { leftSelected -> setMode(sentenceMode = leftSelected) }
        }

        addDeckGroup(scrollContent)

        sentenceContainer = FrameLayout(requireContext()).apply {
            id = View.generateViewId()
            visibility = if (hasSentenceData) View.VISIBLE else View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        scrollContent.addView(sentenceContainer)

        wordContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (hasSentenceData) View.GONE else View.VISIBLE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        scrollContent.addView(wordContainer)
        buildWordContent(wordContainer, word, reading, pos, fallbackDefinition,
            freqScore, isCommon, sourceLangId, screenshotPath)

        if (hasSentenceData && savedInstanceState == null) {
            val sentenceWords = buildWordEntries(args)
            val contentFragment = SentenceAnkiContentFragment.newInstance(
                sentenceOriginal ?: return, sentenceTranslation, sentenceWords,
                screenshotPath, targetWord = word, sourceLangId = sourceLangId
            )
            childFragmentManager.beginTransaction()
                .replace(sentenceContainer.id, contentFragment, TAG_CONTENT)
                .commitNow()
        }

        isSentenceMode = hasSentenceData
        refreshTitle()

        // Kick off the same dictionary lookup the Word Detail sheet does.
        // Once it lands we replace the loading placeholder in the
        // Definitions card with per-sense rows, including ML-Kit-translated
        // glosses for non-English target languages and accent-bar example
        // blocks. The flat ARG_DEFINITION string remains a fallback for
        // failure / offline / dictionary-miss paths.
        viewLifecycleOwner.lifecycleScope.launch {
            runDictionaryLookup(word, reading.takeIf { it.isNotBlank() }, sourceLangId)
        }

        view.findViewById<View>(R.id.btnWordAnkiSend).setOnClickListener { btn ->
            val deckId = Prefs(requireContext()).ankiDeckId
            if (deckId < 0L) {
                Toast.makeText(requireContext(), getString(R.string.anki_no_deck_selected), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btn.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                if (isSentenceMode) {
                    sendSentenceToAnki(deckId)
                } else {
                    val definitionString = composeDefinitionString(fallbackDefinition)
                    sendWordToAnki(word, reading, pos,
                        definitionString, freqScore, deckId, screenshotPath)
                }
                btn.isEnabled = true
            }
        }
    }

    private fun setMode(sentenceMode: Boolean) {
        isSentenceMode = sentenceMode
        sentenceContainer.visibility = if (sentenceMode) View.VISIBLE else View.GONE
        wordContainer.visibility = if (sentenceMode) View.GONE else View.VISIBLE
    }

    // ── Headword + Definitions + Screenshot (Word mode) ─────────────────

    private fun buildWordContent(
        parent: LinearLayout,
        word: String,
        reading: String,
        pos: String,
        fallbackDefinition: String,
        freqScore: Int,
        isCommon: Boolean,
        sourceLangId: SourceLangId,
        screenshotPath: String?,
    ) {
        val ctx = requireContext()
        val density = resources.displayMetrics.density

        // ── Headword block (no group card). ──────────────────────────
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((4 * density).toInt(), (12 * density).toInt(),
                (4 * density).toInt(), (12 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        val headwordFace = if (sourceLangId == SourceLangId.JA)
            Typeface.SERIF
        else
            Typeface.create("sans-serif-medium", Typeface.NORMAL)
        header.addView(TextView(ctx).apply {
            text = word
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
            setTextColor(ctx.themeColor(R.attr.ptText))
            setTypeface(headwordFace, Typeface.BOLD)
            letterSpacing = -0.02f
        })
        if (reading.isNotBlank() && reading != word) {
            header.addView(TextView(ctx).apply {
                text = reading
                textSize = 16f
                setTextColor(ctx.themeColor(R.attr.ptTextMuted))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).also { it.topMargin = (8 * density).toInt() }
            })
        }
        if (isCommon || freqScore > 0) {
            val badgeRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).also { it.topMargin = (12 * density).toInt() }
            }
            if (isCommon) {
                badgeRow.addView(TextView(ctx).apply {
                    text = getString(R.string.word_detail_common)
                    textSize = 11f
                    setTextColor(ctx.themeColor(R.attr.ptAccent))
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setBackgroundResource(R.drawable.bg_word_common_pill)
                    setPadding((10 * density).toInt(), (3 * density).toInt(),
                        (10 * density).toInt(), (3 * density).toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).also { it.marginEnd = (6 * density).toInt() }
                })
            }
            if (freqScore > 0) {
                val accent = ctx.themeColor(R.attr.ptAccent)
                val outline = ctx.themeColor(R.attr.ptOutline)
                val starsRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val filled = freqScore.coerceIn(0, 5)
                for (i in 0 until 5) {
                    val isFilled = i < filled
                    starsRow.addView(TextView(ctx).apply {
                        text = if (isFilled) "★" else "☆"
                        textSize = 13f
                        setTextColor(if (isFilled) accent else outline)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).also { it.marginEnd = (1 * density).toInt() }
                    })
                }
                badgeRow.addView(starsRow)
            }
            header.addView(badgeRow)
        }
        if (pos.isNotBlank()) {
            header.addView(TextView(ctx).apply {
                text = pos.uppercase(Locale.ROOT)
                textSize = 10f
                isAllCaps = true
                letterSpacing = 0.12f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(ctx.themeColor(R.attr.ptTextMuted))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).also { it.topMargin = (10 * density).toInt() }
            })
        }
        parent.addView(header)

        // ── Definitions group: starts with a loading placeholder. The
        //    async lookup in onViewCreated replaces it with per-sense
        //    rows once defResult lands.
        ankiGroupHeader(parent, getString(R.string.anki_group_definitions))
        val defCard = ankiGroupCard(parent)
        definitionsCard = defCard
        defCard.addView(buildLoadingDefinitionsRow(fallbackDefinition))

        // ── Screenshot group (when present). ─────────────────────────
        if (screenshotPath != null) {
            val file = File(screenshotPath)
            if (file.exists()) {
                ankiGroupHeader(parent, getString(R.string.anki_group_screenshot))
                val ssCard = ankiGroupCard(parent)
                val ssHeaderRef = parent.getChildAt(parent.childCount - 2)
                val ssCardRef = parent.getChildAt(parent.childCount - 1)
                addWordScreenshotRow(ssCard, file) {
                    parent.removeView(ssHeaderRef)
                    parent.removeView(ssCardRef)
                    arguments?.remove(ARG_SCREENSHOT_PATH)
                }
            }
        }
    }

    /** Placeholder row shown in the Definitions card while the async
     *  dictionary lookup is in flight. Falls back to the flat
     *  ARG_DEFINITION string so the user always sees *something* without
     *  waiting on the resolver. */
    private fun buildLoadingDefinitionsRow(fallback: String): View {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        return TextView(ctx).apply {
            text = fallback.ifBlank { getString(R.string.words_loading) }
            textSize = 14f
            setTextColor(ctx.themeColor(R.attr.ptTextMuted))
            setLineSpacing(0f, 1.4f)
            setPadding((16 * density).toInt(), (12 * density).toInt(),
                (16 * density).toInt(), (12 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    private fun addWordScreenshotRow(card: LinearLayout, file: File, onRemove: () -> Unit) {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val frame = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        val img = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
        if (bmp != null) img.setImageBitmap(bmp)
        frame.addView(img)
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

    // ── Dictionary lookup + per-sense rendering ─────────────────────────

    private suspend fun runDictionaryLookup(
        word: String,
        readingHint: String?,
        sourceLangId: SourceLangId,
    ) {
        val appCtx = requireContext().applicationContext
        val prefs = Prefs(appCtx)
        val targetLangCode = prefs.targetLang
        val engine = SourceLanguageEngines.get(appCtx, sourceLangId)
        val targetGlossDb = TargetGlossDatabaseProvider.get(appCtx, targetLangCode)
        val mlKit = TranslationManagerProvider.get(engine.profile.translationCode, targetLangCode)
        val enToTarget = TranslationManagerProvider.getEnToTarget(targetLangCode)
        val resolver = DefinitionResolver(
            engine, targetGlossDb,
            mlKit?.let { WordTranslator(it::translate) },
            targetLangCode,
            enToTarget?.let { WordTranslator(it::translate) },
        )
        val defResult = withContext(Dispatchers.IO) { resolver.lookup(word, readingHint) }
        val response = defResult?.response
        val entry = response?.entries?.firstOrNull()
        if (!isAdded || entry == null) return
        resolvedEntry = entry
        resolvedDefResult = defResult

        // First paint of the per-sense rows. Stored English example
        // translations come from the pack for target=en; otherwise we
        // wait for ML Kit to fill them in.
        val initialTranslations: List<List<String>>? = if (targetLangCode == "en") {
            entry.senses.map { s -> s.examples.map { it.translation } }
        } else null
        val translationRegistry = mutableMapOf<Pair<Int, Int>, TextView>()
        renderDefinitions(entry, defResult, initialTranslations, translationRegistry)

        // Async translate examples for non-English targets so the user
        // doesn't stare at empty translation slots while ML Kit warms up.
        // Uses the view lifecycle scope (rather than the enclosing
        // suspend caller) so this child coroutine is independently
        // cancellable on view teardown.
        if (targetLangCode != "en") {
            viewLifecycleOwner.lifecycleScope.launch {
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
    }

    private fun renderDefinitions(
        entry: DictionaryEntry,
        defResult: DefinitionResult?,
        initialTranslations: List<List<String>>?,
        translationRegistry: MutableMap<Pair<Int, Int>, TextView>,
    ) {
        val card = definitionsCard ?: return
        card.removeAllViews()

        val translatedDefs = when (defResult) {
            is DefinitionResult.Native -> defResult.translatedDefinitions
            is DefinitionResult.MachineTranslated -> defResult.translatedDefinitions
            is DefinitionResult.EnglishFallback -> defResult.translatedDefinitions
            else -> null
        }
        val targetByOrd = if (defResult is DefinitionResult.Native)
            defResult.targetSenses.associateBy { it.senseOrd } else null
        val numSenses = entry.senses.count { it.targetDefinitions.isNotEmpty() }

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
                ankiInsetDivider(card, indentDp = if (senseNumber != null) 42 else 16)
            }
            addAnkiSenseRow(
                parent = card,
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

        // Defensive: if every sense was empty we'd have left the card
        // empty. Fall back to the muted "no definitions" line so the
        // user isn't staring at a blank card slot.
        if (displayCount == 0) {
            card.addView(buildLoadingDefinitionsRow(""))
        }
    }

    /** Mirror of WordDetailBottomSheet.addSenseRow: number column +
     *  POS eyebrow + gloss + misc + accent-bar example blocks. The
     *  registry pattern lets the async resolver patch each example's
     *  translation TextView in place after first paint. */
    private fun addAnkiSenseRow(
        parent: LinearLayout,
        posLabels: List<String>,
        glossList: List<String>,
        senseNumber: Int?,
        miscText: String?,
        examples: List<Example>,
        exampleTranslations: List<String>?,
        senseIndex: Int,
        translationRegistry: MutableMap<Pair<Int, Int>, TextView>,
    ) {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val rowH = (16 * density).toInt()
        val rowV = (14 * density).toInt()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(rowH, rowV, rowH, rowV)
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
                minWidth = (16 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).also {
                    it.marginEnd = (10 * density).toInt()
                    it.topMargin = (2 * density).toInt()
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
                    LinearLayout.LayoutParams.WRAP_CONTENT,
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
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { if (posLabels.isNotEmpty()) it.topMargin = (6 * density).toInt() }
        })
        if (miscText != null) {
            col.addView(TextView(ctx).apply {
                text = miscText
                textSize = 12f
                setTextColor(ctx.themeColor(R.attr.ptTextHint))
                setTypeface(null, Typeface.ITALIC)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).also { it.topMargin = (4 * density).toInt() }
            })
        }
        examples.forEachIndexed { i, ex ->
            val initial = exampleTranslations?.getOrNull(i) ?: ""
            val (block, translationTv) = buildAnkiExampleBlock(ctx, ex.text, initial)
            val topGap = if (i == 0) (10 * density).toInt() else (2 * density).toInt()
            (block.layoutParams as LinearLayout.LayoutParams).topMargin = topGap
            col.addView(block)
            translationRegistry[senseIndex to i] = translationTv
        }
        row.addView(col)
        parent.addView(row)
    }

    private fun buildAnkiExampleBlock(
        ctx: Context, text: String, initialTranslation: String,
    ): Pair<View, TextView> {
        val density = ctx.resources.displayMetrics.density
        val accent = ctx.themeColor(R.attr.ptAccent)
        val accentRing = Color.argb(
            (0.35f * 255).toInt(),
            Color.red(accent), Color.green(accent), Color.blue(accent),
        )
        val block = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.topMargin = (8 * density).toInt() }
        }
        block.addView(View(ctx).apply {
            setBackgroundColor(accentRing)
            layoutParams = LinearLayout.LayoutParams(
                (2 * density).toInt(), LinearLayout.LayoutParams.MATCH_PARENT,
            )
        })
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.marginStart = (12 * density).toInt() }
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
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.topMargin = (2 * density).toInt() }
        }
        inner.addView(translationTv)
        block.addView(inner)
        return block to translationTv
    }

    // ── Deck group ───────────────────────────────────────────────────────

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
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        row.addView(TextView(ctx).apply {
            text = getString(R.string.anki_deck_row_label)
            textSize = 15f
            setTextColor(ctx.themeColor(R.attr.ptText))
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
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.marginEnd = (4 * density).toInt() }
        }
        row.addView(deckRowValue)
        row.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_chevron_right)
            setColorFilter(ctx.themeColor(R.attr.ptTextMuted))
            layoutParams = LinearLayout.LayoutParams(
                (20 * density).toInt(), (20 * density).toInt(),
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

    private fun refreshTitle() {
        if (toggleHost.visibility == View.VISIBLE) return
        val deckName = Prefs(requireContext()).ankiDeckName
        titleView.text = if (deckName.isBlank())
            getString(R.string.anki_sheet_title_default)
        else
            getString(R.string.anki_sheet_add_to_deck, deckName)
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

    /** Build the flat definition string the Anki HTML builder wants
     *  from the resolved entry — same logic the Word Detail sheet uses
     *  when launching the review. Falls back to the original ARG_DEFINITION
     *  if the lookup never landed. */
    private fun composeDefinitionString(fallback: String): String {
        val entry = resolvedEntry ?: return fallback
        val defResult = resolvedDefResult
        val targetByOrd = if (defResult is DefinitionResult.Native)
            defResult.targetSenses.associateBy { it.senseOrd } else null
        val translatedDefs = when (defResult) {
            is DefinitionResult.MachineTranslated -> defResult.translatedDefinitions
            is DefinitionResult.EnglishFallback -> defResult.translatedDefinitions
            else -> null
        }
        val numSenses = entry.senses.count { it.targetDefinitions.isNotEmpty() }
        var displayNum = 0
        val pieces = entry.senses.mapIndexedNotNull { i, sense ->
            if (sense.targetDefinitions.isEmpty()) return@mapIndexedNotNull null
            displayNum++
            val glosses = targetByOrd?.get(i)?.glosses?.joinToString("; ")
                ?: translatedDefs?.getOrElse(i) { sense.targetDefinitions.joinToString("; ") }
                ?: sense.targetDefinitions.joinToString("; ")
            val prefix = if (numSenses > 1) "$displayNum. " else ""
            prefix + glosses
        }
        return pieces.joinToString("\n").ifBlank { fallback }
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
        private const val ARG_FREQ_SCORE      = "freq_score"
        private const val ARG_IS_COMMON       = "is_common"
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
            isCommon: Boolean = false,
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
                putBoolean(ARG_IS_COMMON, isCommon)
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
