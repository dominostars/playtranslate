package com.playtranslate.ui

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.text.StaticLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.playtranslate.AnkiManager
import com.playtranslate.CaptureService
import com.playtranslate.Prefs
import com.playtranslate.language.DefinitionResolver
import com.playtranslate.language.DefinitionResult
import com.playtranslate.language.WordTranslator
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.language.TargetGlossDatabaseProvider
import com.playtranslate.language.TranslationManagerProvider
import com.playtranslate.R
import com.playtranslate.language.HintTextKind
import com.playtranslate.model.TranslationResult
import com.playtranslate.model.headwordDisplay
import com.playtranslate.themeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Reset [ScrollView] scroll to (0, 0) without firing the registered
 * scroll listener — i.e. without making a programmatic reset look like
 * user intent. Detach → scrollTo (synchronous, fires onScrollChanged
 * inline on the main thread, sees no listener) → reattach.
 *
 * Only safe with the synchronous [ScrollView.scrollTo]; do not use with
 * [ScrollView.smoothScrollTo] which dispatches asynchronously and would
 * fire onScrollChanged after the reattach.
 */
private fun ScrollView.scrollToTopSilently(listener: View.OnScrollChangeListener) {
    setOnScrollChangeListener(null)
    scrollTo(0, 0)
    setOnScrollChangeListener(listener)
}

/**
 * Shared fragment that displays translation results: original text, translation,
 * word lookups, copy/Anki buttons. Used by both MainActivity and TranslationResultActivity.
 */
class TranslationResultFragment : Fragment() {

    /**
     * Host interface for activities that embed this fragment. Bundles
     * service-binding queries, word-tap routing, ankiPermissionLauncher
     * access, and user-input event handlers into a single contract.
     * The compiler enforces implementation — there's no optional
     * "remember to wire this" var. Pure state actions (Clear → reset
     * to idle status) bypass this interface and call the VM directly,
     * since they don't need host context.
     */
    interface TranslationResultHost {
        fun getCaptureService(): CaptureService?
        fun onWordTapped(
            word: String,
            reading: String?,
            screenshotPath: String?,
            sentenceOriginal: String?,
            sentenceTranslation: String?,
            wordResults: Map<String, Triple<String, String, Int>>
        )
        fun onInteraction()
        fun getAnkiPermissionLauncher(): androidx.activity.result.ActivityResultLauncher<String>?

        /** User tapped Edit on the original-text card. The host opens
         *  its edit overlay UI. No-op for hosts without one. */
        fun onEditOriginalRequested()

        /** User scrolled the result content. The host can use this to
         *  pause live-mode capture, etc. No-op for hosts without
         *  live-mode behavior. */
        fun onUserScrolled()
    }

    // ── Views ─────────────────────────────────────────────────────────────
    private lateinit var tvStatus: TextView
    private lateinit var tvStatusHint: TextView
    private lateinit var tvLiveHint: TextView
    private lateinit var statusContainer: View
    private lateinit var resultsContent: ScrollView
    private lateinit var tvOriginal: ClickableTextView
    private lateinit var tvTranslation: TextView
    private lateinit var tvTranslationNote: TextView
    private lateinit var tvMainWordsLoading: TextView
    private lateinit var mainWordsContainer: LinearLayout
    private lateinit var btnCopyOriginal: ImageButton
    private lateinit var btnCopyTranslation: ImageButton
    private lateinit var btnEditOriginal: ImageButton
    private lateinit var btnToggleTranslation: ImageButton
    private lateinit var btnToggleOriginal: ImageButton
    private lateinit var btnToggleFurigana: ImageButton
    private lateinit var btnToggleWords: ImageButton
    private lateinit var translationContent: LinearLayout
    private lateinit var originalContent: LinearLayout
    private lateinit var wordsContent: LinearLayout
    private lateinit var cardTranslation: com.google.android.material.card.MaterialCardView
    private lateinit var cardOriginal: com.google.android.material.card.MaterialCardView
    private lateinit var cardWords: com.google.android.material.card.MaterialCardView
    private lateinit var labelOriginal: TextView
    private lateinit var labelTranslation: TextView
    private lateinit var tvNoWords: TextView
    private lateinit var resultActionButtons: View
    private lateinit var btnResultClear: View
    private lateinit var btnResultAnki: View

    /** Maps character ranges in original text to (displayWord, reading).
     *  Recomputed in [renderWordLookups] Settled branch from the VM's
     *  tokenSpans on each Settled emission, so it tracks the displayed
     *  text (which has OCR newlines). */
    private var wordSpans = mutableListOf<Triple<IntRange, String, String>>()
    private var furiganaPopup: PopupWindow? = null

    /** Char range currently highlighted with the accent background while a
     *  word-lookup popup is active. Tracked separately from the span object
     *  so [applyFurigana] can re-attach the highlight after rebuilding the
     *  spannable. */
    private var highlightedWordRange: IntRange? = null

    /** Reified scroll listener so [scrollToTopSilently] can detach + reattach
     *  it around programmatic scrolls — otherwise the framework's
     *  onScrollChanged callback for our own [resultsContent.scrollTo] would
     *  be misread as user intent and pause live mode the instant a fresh
     *  result lands. */
    private val scrollListener = View.OnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
        if (scrollY != oldScrollY) {
            dismissFurigana()
            dismissWordPopup()
            host?.onUserScrolled()
        }
    }

    /** Activity-scoped source of truth for the result + lookup state.
     *  Activities mutate via VM methods; this fragment observes
     *  [vm.result] and [vm.wordLookups] to render. */
    private val vm: TranslationResultViewModel by activityViewModels()

    private val host: TranslationResultHost?
        get() = activity as? TranslationResultHost

    private val prefs: Prefs by lazy { Prefs(requireContext()) }

    // ── Fragment lifecycle ─────────────────────────────────────────────────

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_translation_result, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupButtons()
        // Observe activity-scoped VM state. Both flows are activity-scoped
        // (survive fragment view recreation), so a rotation re-renders the
        // last state without re-running the pipeline. The collectors run
        // only while the fragment is STARTED, so they cleanly stop when
        // the view is destroyed and resume when recreated.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.result.collect { renderResult(it) } }
                launch { vm.wordLookups.collect { renderWordLookups(it) } }
            }
        }
    }

    override fun onDestroyView() {
        dismissFurigana()
        dismissWordPopup()
        super.onDestroyView()
    }

    private fun bindViews(view: View) {
        tvStatus             = view.findViewById(R.id.tvStatus)
        tvStatusHint         = view.findViewById(R.id.tvStatusHint)
        tvLiveHint           = view.findViewById(R.id.tvLiveHint)
        statusContainer      = view.findViewById(R.id.statusContainer)
        resultsContent       = view.findViewById(R.id.resultsContent)
        tvOriginal           = view.findViewById(R.id.tvOriginal)
        tvTranslation        = view.findViewById(R.id.tvTranslation)
        tvTranslationNote    = view.findViewById(R.id.tvTranslationNote)
        tvMainWordsLoading   = view.findViewById(R.id.tvMainWordsLoading)
        mainWordsContainer   = view.findViewById(R.id.mainWordsContainer)
        btnCopyOriginal      = view.findViewById(R.id.btnCopyOriginal)
        btnCopyTranslation   = view.findViewById(R.id.btnCopyTranslation)
        btnEditOriginal      = view.findViewById(R.id.btnEditOriginal)
        btnToggleTranslation = view.findViewById(R.id.btnToggleTranslation)
        btnToggleOriginal    = view.findViewById(R.id.btnToggleOriginal)
        btnToggleFurigana    = view.findViewById(R.id.btnToggleFurigana)
        btnToggleWords       = view.findViewById(R.id.btnToggleWords)
        translationContent   = view.findViewById(R.id.translationContent)
        originalContent      = view.findViewById(R.id.originalContent)
        wordsContent         = view.findViewById(R.id.wordsContent)
        cardTranslation      = view.findViewById(R.id.cardTranslation)
        cardOriginal         = view.findViewById(R.id.cardOriginal)
        cardWords            = view.findViewById(R.id.cardWords)
        labelOriginal        = view.findViewById(R.id.labelOriginal)
        labelTranslation     = view.findViewById(R.id.labelTranslation)
        tvNoWords            = view.findViewById(R.id.tvNoWords)
        resultActionButtons  = view.findViewById(R.id.resultActionButtons)
        btnResultClear       = view.findViewById(R.id.btnResultClear)
        btnResultAnki        = view.findViewById(R.id.btnResultAnki)
    }

    private fun setupButtons() {
        btnCopyOriginal.setOnClickListener {
            copyToClipboard(tvOriginal.text?.toString() ?: return@setOnClickListener)
        }
        btnCopyTranslation.setOnClickListener {
            copyToClipboard(tvTranslation.text?.toString() ?: return@setOnClickListener)
        }
        btnEditOriginal.setOnClickListener {
            dismissFurigana()
            dismissWordPopup()
            host?.onEditOriginalRequested()
        }
        resultsContent.setOnScrollChangeListener(scrollListener)
        btnToggleTranslation.setOnClickListener {
            prefs.hideTranslationSection = !prefs.hideTranslationSection
            applyTranslationVisibility()
        }
        btnToggleOriginal.setOnClickListener {
            prefs.hideOriginalSection = !prefs.hideOriginalSection
            applyOriginalVisibility()
        }
        btnToggleFurigana.setOnClickListener {
            prefs.showFuriganaInline = !prefs.showFuriganaInline
            applyFurigana()
        }
        btnToggleWords.setOnClickListener {
            prefs.hideWordsSection = !prefs.hideWordsSection
            applyWordsVisibility()
        }
        btnResultClear.setOnClickListener {
            // Pure state action — no host context needed. Reset directly
            // to idle status; the fragment will re-render from the VM.
            vm.showStatus(getString(R.string.status_idle), showHint = true)
        }
        btnResultAnki.setOnClickListener {
            onAnkiClicked()
        }
    }

    private fun applyTranslationVisibility() {
        val hidden = prefs.hideTranslationSection
        cardTranslation.visibility = if (hidden) View.GONE else View.VISIBLE
        btnCopyTranslation.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        btnToggleTranslation.setImageResource(if (hidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility)
    }

    private fun applyOriginalVisibility() {
        val hidden = prefs.hideOriginalSection
        cardOriginal.visibility = if (hidden) View.GONE else View.VISIBLE
        btnCopyOriginal.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        btnEditOriginal.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        val hintKind = SourceLanguageProfiles[prefs.sourceLangId].hintTextKind
        val hasHintText = hintKind != HintTextKind.NONE
        btnToggleFurigana.visibility = if (hidden || !hasHintText) View.GONE else View.VISIBLE
        if (hasHintText) {
            val label = when (hintKind) { HintTextKind.PINYIN -> "pinyin"; else -> "furigana" }
            btnToggleFurigana.contentDescription = "Toggle inline $label"
            btnToggleFurigana.setImageResource(
                if (hintKind == HintTextKind.PINYIN) R.drawable.ic_pinyin else R.drawable.ic_furigana
            )
        }
        btnToggleOriginal.setImageResource(if (hidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility)
    }

    private fun applyWordsVisibility() {
        val hidden = prefs.hideWordsSection
        cardWords.visibility = if (hidden) View.GONE else View.VISIBLE
        btnToggleWords.setImageResource(if (hidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility)
    }

    private fun applyFurigana() {
        val active = prefs.showFuriganaInline
        val ctx = context ?: return
        val accentColor = ctx.themeColor(R.attr.ptAccent)
        val secondaryColor = ctx.themeColor(R.attr.ptTextMuted)
        btnToggleFurigana.imageTintList = android.content.res.ColorStateList.valueOf(
            if (active) accentColor else secondaryColor
        )

        val plainText = tvOriginal.text.toString()
        if (active && plainText.isNotEmpty()) {
            val engine = SourceLanguageEngines.get(ctx.applicationContext, prefs.sourceLangId)
            val annotations = engine.annotateForHintText(plainText)
            if (annotations.isEmpty()) {
                tvOriginal.text = plainText
                return
            }
            val spannable = android.text.SpannableString(plainText)
            for (ann in annotations) {
                if (ann.baseEnd > plainText.length) continue
                spannable.setSpan(
                    FuriganaSpan(ann.hintText),
                    ann.baseStart, ann.baseEnd,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            tvOriginal.text = spannable
        } else {
            tvOriginal.text = plainText
        }
        // The text reference just got swapped, so any active accent
        // highlight span was dropped on the floor — re-attach it from the
        // tracked range so toggling furigana mid-popup doesn't lose it.
        highlightedWordRange?.let { setWordHighlight(it) }
    }

    // ── Result render (driven by vm.result observation) ──────────────────

    private fun renderResult(state: ResultState) {
        if (view == null) return
        when (state) {
            is ResultState.Idle -> {
                showStatusUi(getString(R.string.status_idle), showHint = true)
            }
            is ResultState.Status -> {
                showStatusUi(state.message, state.showHint)
            }
            is ResultState.Error -> {
                showStatusUi(getString(R.string.status_error, state.message), showHint = false)
            }
            is ResultState.Translating -> {
                tvOriginal.setSegments(state.segments)
                tvOriginal.onTapAtOffset = { offset -> onOriginalTapped(offset) }
                labelOriginal.text = sourceLangLocalizedDisplayName()
                labelTranslation.text = targetLangDisplayName()
                statusContainer.visibility = View.GONE
                resultsContent.visibility = View.VISIBLE
                resultActionButtons.visibility = View.VISIBLE
                resultsContent.scrollToTopSilently(scrollListener)
                tvTranslation.text = getString(R.string.status_translating)
                tvTranslationNote.text = ""
                tvTranslationNote.visibility = View.GONE
                applyTranslationVisibility()
                applyOriginalVisibility()
                applyWordsVisibility()
            }
            is ResultState.Ready -> {
                val result = state.result
                tvOriginal.setSegments(result.segments)
                tvOriginal.onTapAtOffset = { offset -> onOriginalTapped(offset) }
                tvTranslation.text = result.translatedText
                tvTranslationNote.text = result.note ?: ""
                tvTranslationNote.visibility = if (result.note != null) View.VISIBLE else View.GONE
                applyTranslationVisibility()
                applyOriginalVisibility()
                applyWordsVisibility()
                labelOriginal.text = sourceLangLocalizedDisplayName()
                labelTranslation.text = targetLangDisplayName()
                statusContainer.visibility = View.GONE
                resultsContent.visibility = View.INVISIBLE
                resultActionButtons.visibility = View.VISIBLE
                btnResultAnki.visibility = View.VISIBLE
                resultsContent.scrollToTopSilently(scrollListener)
                resultsContent.post {
                    fitTextSizes()
                    if (view != null) resultsContent.visibility = View.VISIBLE
                }
            }
        }
    }

    /** Shared status / error / idle layout — single status container,
     *  results hidden, Anki gone. [showHint] gates the
     *  "press X to start" hint line under the message. */
    private fun showStatusUi(message: String, showHint: Boolean) {
        tvStatus.text = message
        tvStatusHint.visibility = if (showHint) View.VISIBLE else View.GONE
        tvLiveHint.visibility = View.GONE
        statusContainer.visibility = View.VISIBLE
        resultsContent.visibility = View.GONE
        btnResultAnki.visibility = View.GONE
    }

    /** True iff the activity is currently showing a translation result
     *  (vs status/error/translating). View-state helper for the host. */
    val isShowingResults: Boolean
        get() = view != null && vm.result.value is ResultState.Ready

    private companion object {
        const val TEXT_SIZE_MAX_SP = 24f
        const val TEXT_SIZE_MIN_SP = 16f
        const val WORD_DIVIDER_TAG = "pt_word_divider"
    }

    /** 1dp ptDivider line inset from the start by pt_row_h_padding, matching
     *  `settings_row_divider` for word rows inside the Words card. */
    private fun inflateWordDivider(): View {
        val ctx = requireContext()
        val dp1 = ctx.resources.displayMetrics.density.toInt().coerceAtLeast(1)
        return View(ctx).apply {
            tag = WORD_DIVIDER_TAG
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp1
            ).apply {
                marginStart = ctx.resources.getDimensionPixelSize(R.dimen.pt_row_h_padding)
            }
            setBackgroundColor(ctx.themeColor(R.attr.ptDivider))
        }
    }

    /**
     * Shrink translation and original text so each tries to fit within
     * half the visible scroll area. Stops shrinking at [TEXT_SIZE_MIN_SP].
     */
    private fun fitTextSizes() {
        val scrollHeight = resultsContent.height.takeIf { it > 0 } ?: return
        val halfHeight = scrollHeight / 2
        fitTextView(tvTranslation, TEXT_SIZE_MAX_SP, TEXT_SIZE_MIN_SP, halfHeight)
        fitTextView(tvOriginal, TEXT_SIZE_MAX_SP, TEXT_SIZE_MIN_SP, halfHeight)
    }

    private fun fitTextView(tv: TextView, maxSp: Float, minSp: Float, targetHeightPx: Int) {
        val widthPx = tv.width.takeIf { it > 0 } ?: return
        var sizeSp = maxSp
        while (sizeSp > minSp) {
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            val height = StaticLayout.Builder
                .obtain(tv.text, 0, tv.text.length, tv.paint, widthPx)
                .setLineSpacing(tv.lineSpacingExtra, tv.lineSpacingMultiplier)
                .build()
                .height
            if (height <= targetHeightPx) break
            sizeSp -= 1f
        }
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    }

    /** Anki button tap handler — view-side dialog work, kept fragment-
     *  internal. Reads sentence + word data from VM state. */
    private fun onAnkiClicked() {
        host?.onInteraction()
        val result = (vm.result.value as? ResultState.Ready)?.result ?: return
        val ctx = context ?: return
        val ankiManager = AnkiManager(ctx)
        val wordResults = (vm.wordLookups.value as? WordLookupsState.Settled)
            ?.rows?.toLegacyMap() ?: emptyMap()
        when {
            !ankiManager.isAnkiDroidInstalled() ->
                showAnkiNotInstalledDialog(ctx)
            !ankiManager.hasPermission() ->
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.anki_permission_rationale_title)
                    .setMessage(R.string.anki_permission_rationale_message)
                    .setPositiveButton(R.string.btn_continue) { _, _ ->
                        host?.getAnkiPermissionLauncher()?.launch(AnkiManager.PERMISSION)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            else ->
                AnkiReviewBottomSheet.newInstance(
                    getDisplayedOriginalText(), result.translatedText, wordResults,
                    result.screenshotPath, prefs.sourceLangId
                ).show(childFragmentManager, AnkiReviewBottomSheet.TAG)
        }
    }

    private fun onOriginalTapped(offset: Int) {
        dismissFurigana()
        // Find which word span the tap falls in
        val span = wordSpans.firstOrNull { offset in it.first } ?: return
        val lookupForm = span.second
        val reading = span.third

        // Look up in dictionary and show the floating popup
        val ctx = context ?: return
        val activity = activity ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val appCtx = ctx.applicationContext
                val prefs = Prefs(appCtx)
                val engine = SourceLanguageEngines.get(appCtx, prefs.sourceLangId)
                val targetGlossDb = TargetGlossDatabaseProvider.get(appCtx, prefs.targetLang)
                val mlKitTranslator = TranslationManagerProvider.get(engine.profile.translationCode, prefs.targetLang)
                val enToTarget = TranslationManagerProvider.getEnToTarget(prefs.targetLang)
                val resolver = DefinitionResolver(engine, targetGlossDb,
                    mlKitTranslator?.let { WordTranslator(it::translate) }, prefs.targetLang,
                    enToTarget?.let { WordTranslator(it::translate) })
                val defResult = withContext(Dispatchers.IO) {
                    resolver.lookup(lookupForm, reading.ifEmpty { null })
                }
                val response = defResult?.response
                // See DragLookupController for the multi-entry rationale —
                // Wiktionary packs split POS into separate entries, JMdict
                // doesn't, [flatSenses] merges them safely for both.
                val entries = response?.entries.orEmpty()
                val entry = entries.firstOrNull()
                val flatSenses = entries.flatMap { it.senses }

                // Build popup data based on DefinitionResult tier.
                val word: String
                // Reading shown beneath the headword in the popup. Sourced
                // from headwordDisplay (which suppresses it for JMdict uk
                // entries) rather than the span's tokenizer reading, so
                // kana-only rows don't accidentally render reading=ナゼ
                // beneath word=なぜ via Kuromoji's katakana convention.
                val popupReading: String?
                val popupLabel: String?
                val senses: List<WordLookupPopup.SenseDisplay>
                val freqScore: Int
                val isCommon: Boolean
                when {
                    entry != null && defResult is DefinitionResult.Native -> {
                        val display = entry.headwordDisplay(lookupForm)
                        word = display.written
                        popupReading = display.reading
                        popupLabel = null
                        val targetSensesSorted = defResult.targetSenses.sortedBy { it.senseOrd }
                        val isTargetDriven = prefs.targetLang != "en" && targetSensesSorted.isNotEmpty()
                        senses = if (isTargetDriven) {
                            // Blank-pos target rows (PanLex) inherit the
                            // source-entry POS only when entries agree;
                            // multi-POS source yields an empty fallback so
                            // we don't mislabel verb/intj cells as NOUN.
                            val fallbackPos = com.playtranslate.model
                                .unambiguousFallbackPos(entries)
                                .joinToString(", ")
                            targetSensesSorted.map { target ->
                                val pos = target.pos.filter { it.isNotBlank() }
                                    .takeIf { it.isNotEmpty() }
                                    ?.joinToString(", ")
                                    ?: fallbackPos
                                WordLookupPopup.SenseDisplay(
                                    pos = pos,
                                    definition = target.glosses.joinToString("; "),
                                )
                            }
                        } else {
                            // Reached only when target == "en" (Native is not
                            // returned for English targets) or for the empty-
                            // target-senses defensive case. Both render straight
                            // off the flat sense list across every entry.
                            val targetByOrd = targetSensesSorted.associateBy { it.senseOrd }
                            flatSenses.mapIndexed { i, sense ->
                                val target = targetByOrd[i]
                                if (target != null) {
                                    WordLookupPopup.SenseDisplay(
                                        pos = target.pos.joinToString(", "),
                                        definition = target.glosses.joinToString("; "),
                                    )
                                } else {
                                    WordLookupPopup.SenseDisplay(
                                        pos = sense.partsOfSpeech.joinToString(", "),
                                        definition = sense.targetDefinitions.joinToString("; "),
                                    )
                                }
                            }
                        }
                        freqScore = entry.freqScore
                        isCommon = entry.isCommon == true
                    }
                    entry != null && defResult is DefinitionResult.MachineTranslated -> {
                        val display = entry.headwordDisplay(lookupForm)
                        word = display.written
                        popupReading = display.reading
                        popupLabel = "⚠ Machine translated"
                        val defs = defResult.translatedDefinitions
                        senses = if (defs != null) {
                            flatSenses.mapIndexed { i, sense ->
                                WordLookupPopup.SenseDisplay(
                                    pos = sense.partsOfSpeech.joinToString(", "),
                                    definition = defs.getOrElse(i) { sense.targetDefinitions.joinToString("; ") }
                                )
                            }
                        } else {
                            buildList {
                                add(WordLookupPopup.SenseDisplay(pos = "", definition = defResult.translatedHeadword))
                                flatSenses.forEach { sense ->
                                    add(WordLookupPopup.SenseDisplay(
                                        pos = sense.partsOfSpeech.joinToString(", "),
                                        definition = sense.targetDefinitions.joinToString("; ")
                                    ))
                                }
                            }
                        }
                        freqScore = entry.freqScore
                        isCommon = entry.isCommon == true
                    }
                    entry != null && defResult is DefinitionResult.EnglishFallback && defResult.translatedDefinitions != null -> {
                        val display = entry.headwordDisplay(lookupForm)
                        word = display.written
                        popupReading = display.reading
                        popupLabel = "⚠ Machine translated"
                        val defs = defResult.translatedDefinitions
                        senses = flatSenses.mapIndexed { i, sense ->
                            WordLookupPopup.SenseDisplay(
                                pos = sense.partsOfSpeech.joinToString(", "),
                                definition = defs.getOrElse(i) { sense.targetDefinitions.joinToString("; ") }
                            )
                        }
                        freqScore = entry.freqScore
                        isCommon = entry.isCommon == true
                    }
                    entry != null -> {
                        val display = entry.headwordDisplay(lookupForm)
                        word = display.written
                        popupReading = display.reading
                        popupLabel = null
                        senses = flatSenses.map { sense ->
                            WordLookupPopup.SenseDisplay(
                                pos = sense.partsOfSpeech.joinToString(", "),
                                definition = sense.targetDefinitions.joinToString("; ")
                            )
                        }
                        freqScore = entry.freqScore
                        isCommon = entry.isCommon == true
                    }
                    reading.isNotEmpty() -> {
                        word = lookupForm
                        popupReading = reading
                        popupLabel = null
                        senses = listOf(
                            WordLookupPopup.SenseDisplay(
                                pos = "",
                                definition = "Not in dictionary, may be a name"
                            )
                        )
                        freqScore = 0
                        isCommon = false
                    }
                    else -> return@launch
                }

                // Calculate position: center on the tapped word, above it
                val layout = tvOriginal.layout ?: return@launch
                val lineStart = layout.getLineForOffset(span.first.first)
                val xStart = layout.getPrimaryHorizontal(span.first.first)
                val xEnd = layout.getPrimaryHorizontal(span.first.last + 1)
                val wordCenterX = ((xStart + xEnd) / 2).toInt() + tvOriginal.paddingLeft
                val lineTop = layout.getLineTop(lineStart) - tvOriginal.scrollY + tvOriginal.paddingTop
                val lineH = layout.getLineBottom(lineStart) - layout.getLineTop(lineStart)

                val loc = IntArray(2)
                tvOriginal.getLocationOnScreen(loc)
                val screenX = loc[0] + wordCenterX
                val screenY = loc[1] + lineTop

                val dm = resources.displayMetrics
                dismissWordPopup()
                wordPopup = WordLookupPopup(activity, activity.windowManager).apply {
                    useActivityWindow = true
                    verticalMarginDp = 5
                    // Open-in-app would just re-run the same failing lookup,
                    // so suppress the button when we're in the fallback path.
                    showOpenButton = entry != null
                    onOpenTap = {
                        dismissWordPopup()
                        host?.onInteraction()
                        val ready = (vm.result.value as? ResultState.Ready)?.result
                        val wr = (vm.wordLookups.value as? WordLookupsState.Settled)
                            ?.rows?.toLegacyMap() ?: emptyMap()
                        host?.onWordTapped(
                            word, popupReading,
                            ready?.screenshotPath,
                            ready?.originalText,
                            ready?.translatedText,
                            wr,
                        )
                    }
                    onDismiss = { setWordHighlight(null) }
                }
                setWordHighlight(span.first)
                wordPopup?.show(word, popupReading, senses, freqScore,
                    isCommon, screenX, screenY, dm.widthPixels, dm.heightPixels,
                    anchorHeight = lineH, label = popupLabel)
            } catch (_: Exception) {}
        }
    }

    private var wordPopup: WordLookupPopup? = null

    private fun dismissWordPopup() {
        wordPopup?.dismiss()
        wordPopup = null
    }

    /**
     * Highlight the character [range] inside [tvOriginal] with the accent
     * background, or clear any active highlight when [range] is null.
     * Promotes the text to a [android.text.SpannableString] on first use so
     * the BackgroundColorSpan has somewhere to land — [ClickableTextView]'s
     * default text is a plain String.
     */
    private fun setWordHighlight(range: IntRange?) {
        if (view == null) return
        val ctx = context ?: return
        val current = tvOriginal.text ?: return
        // Rebuild a fresh Spannable from current so any FuriganaSpans
        // already on the text are preserved (SpannableString's copy
        // constructor brings spans across), and so we can strip prior
        // BackgroundColorSpans cleanly before adding the new one. Mutating
        // the existing text in place is unreliable: TextView's setText
        // routes Spannables through Spannable.Factory, which wraps them in
        // a new instance, so the reference we held would be orphaned and
        // subsequent setSpan calls wouldn't show up on screen.
        val rebuilt = android.text.SpannableString(current)
        rebuilt.getSpans(0, rebuilt.length, android.text.style.BackgroundColorSpan::class.java)
            .forEach { rebuilt.removeSpan(it) }
        highlightedWordRange = range
        if (range != null) {
            val safeEnd = (range.last + 1).coerceAtMost(rebuilt.length)
            val safeStart = range.first.coerceAtLeast(0).coerceAtMost(safeEnd)
            if (safeStart < safeEnd) {
                val accentBg = withAlpha(ctx.themeColor(R.attr.ptAccent), 0.30f)
                rebuilt.setSpan(
                    android.text.style.BackgroundColorSpan(accentBg),
                    safeStart, safeEnd,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        tvOriginal.text = rebuilt
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun showFurigana(range: IntRange, reading: String) {
        val ctx = context ?: return
        val layout = tvOriginal.layout ?: return
        val textLen = tvOriginal.text?.length ?: return
        val dm = resources.displayMetrics
        fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm).toInt()

        val bgColor = ctx.themeColor(R.attr.ptCard)
        val arrowW = dp(12f)
        val arrowH = dp(6f)

        val cornerR = dp(6f).toFloat()
        val tv = TextView(ctx).apply {
            text = reading
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ctx.themeColor(R.attr.ptText))
            setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = cornerR
            }
            elevation = dp(4f).toFloat()
        }

        // Small triangle arrow pointing down
        val arrowView = object : View(ctx) {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }
            private val path = android.graphics.Path()
            override fun onDraw(canvas: android.graphics.Canvas) {
                path.rewind()
                path.moveTo(0f, 0f)
                path.lineTo(width.toFloat(), 0f)
                path.lineTo(width / 2f, height.toFloat())
                path.close()
                canvas.drawPath(path, paint)
            }
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            addView(tv, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(arrowView, LinearLayout.LayoutParams(arrowW, arrowH).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })
        }

        val popup = PopupWindow(container, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            setOnDismissListener { furiganaPopup = null }
        }
        furiganaPopup = popup

        // Position above the tapped word, centered horizontally
        val safeEnd = (range.last + 1).coerceAtMost(textLen)
        val startLine = layout.getLineForOffset(range.first)
        val startX = layout.getPrimaryHorizontal(range.first)
        val endX = layout.getPrimaryHorizontal(safeEnd)
        val midX = ((startX + endX) / 2).toInt()
        val lineTop = layout.getLineTop(startLine)

        // Measure popup to center it
        container.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val popupW = container.measuredWidth
        val popupH = container.measuredHeight

        val loc = IntArray(2)
        tvOriginal.getLocationOnScreen(loc)
        val anchorX = loc[0] + tvOriginal.totalPaddingLeft + midX - popupW / 2
        val anchorY = loc[1] + tvOriginal.totalPaddingTop + lineTop - tvOriginal.scrollY - popupH

        popup.showAtLocation(tvOriginal, Gravity.NO_GRAVITY, anchorX.coerceAtLeast(0), anchorY.coerceAtLeast(0))
    }

    private fun dismissFurigana() {
        furiganaPopup?.dismiss()
        furiganaPopup = null
    }

    fun setLiveHintText(text: CharSequence) {
        if (view != null) tvLiveHint.text = text
    }

    /** Returns the displayed original text (with OCR line breaks preserved). */
    fun getDisplayedOriginalText(): String =
        if (view != null) tvOriginal.text?.toString() ?: "" else ""

    // ── Word lookups (rendering only — pipeline lives in VM) ─────────────

    /** Observation-driven render of [vm.wordLookups]. The pipeline
     *  itself runs on [viewModelScope] inside the VM so rotation
     *  mid-lookup preserves progress; this method just mirrors the
     *  current state into the views. */
    private fun renderWordLookups(state: WordLookupsState) {
        if (view == null) return
        when (state) {
            is WordLookupsState.Idle -> {
                tvMainWordsLoading.visibility = View.GONE
                tvNoWords.visibility = View.GONE
                mainWordsContainer.removeAllViews()
                wordSpans.clear()
            }
            is WordLookupsState.Loading -> {
                dismissFurigana()
                dismissWordPopup()
                mainWordsContainer.removeAllViews()
                wordSpans.clear()
                tvMainWordsLoading.visibility = View.VISIBLE
                tvMainWordsLoading.text = getString(R.string.words_loading)
                tvNoWords.visibility = View.GONE
            }
            is WordLookupsState.Settled -> {
                renderWordRows(state.rows)
                recomputeWordSpans(state.tokenSpans, state.lookupToReading)
                applyFurigana()
                tvMainWordsLoading.visibility = View.GONE
                tvNoWords.visibility = if (state.rows.isEmpty()) View.VISIBLE else View.GONE
                btnResultAnki.visibility = View.VISIBLE
            }
        }
    }

    private fun renderWordRows(rows: List<RowState>) {
        mainWordsContainer.removeAllViews()
        if (rows.isEmpty()) return
        val inflater = LayoutInflater.from(requireContext())
        rows.forEachIndexed { idx, rowState ->
            if (idx > 0) mainWordsContainer.addView(inflateWordDivider())
            val row = inflater.inflate(R.layout.item_word_lookup, mainWordsContainer, false)
            bindWordRow(row, rowState)
            mainWordsContainer.addView(row)
        }
    }

    private fun bindWordRow(row: View, rowState: RowState) {
        row.findViewById<TextView>(R.id.tvItemWord).text = rowState.displayWord
        row.findViewById<TextView>(R.id.tvItemReading).text = rowState.reading
        row.findViewById<TextView>(R.id.tvItemMeaning).text = rowState.meaning
        val tvFreq = row.findViewById<TextView>(R.id.tvItemFreq)
        if (rowState.freqScore > 0) {
            tvFreq.text = "★".repeat(rowState.freqScore)
            tvFreq.visibility = View.VISIBLE
        } else {
            tvFreq.visibility = View.GONE
        }
        row.setOnClickListener {
            host?.onInteraction()
            val ready = (vm.result.value as? ResultState.Ready)?.result
            val wr = (vm.wordLookups.value as? WordLookupsState.Settled)
                ?.rows?.toLegacyMap() ?: emptyMap()
            host?.onWordTapped(
                rowState.displayWord,
                rowState.reading.ifEmpty { null },
                ready?.screenshotPath,
                ready?.originalText,
                ready?.translatedText,
                wr,
            )
        }
    }

    /** Derive view-side word spans from the VM's per-occurrence
     *  tokenSpans plus the displayed text (which may have OCR
     *  newlines inserted that aren't in [TranslationResult.originalText]).
     *  The JMdict-resolved reading wins, then surface-keyed reading,
     *  then the tokenizer's own reading (Kuromoji) as a last fallback
     *  so out-of-dictionary tokens still carry a reading into the
     *  word-tap popup. */
    private fun recomputeWordSpans(
        tokenSpans: List<com.playtranslate.language.TokenSpan>,
        lookupToReading: Map<String, String>,
    ) {
        wordSpans.clear()
        val displayedText = tvOriginal.text?.toString() ?: return
        var searchFrom = 0
        for (tok in tokenSpans) {
            val idx = displayedText.indexOf(tok.surface, searchFrom)
            if (idx < 0) continue
            val range = idx until (idx + tok.surface.length)
            val reading = lookupToReading[tok.lookupForm]
                ?: lookupToReading[tok.surface]
                ?: tok.reading
                ?: ""
            wordSpans.add(Triple(range, tok.lookupForm, reading))
            searchFrom = idx + tok.surface.length
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun selectedTargetLang() =
        Prefs(requireContext().applicationContext).targetLang

    private fun sourceLangLocalizedDisplayName(): String {
        val appCtx = requireContext().applicationContext
        val p = Prefs(appCtx)
        return p.sourceLangId.displayName(Locale(p.targetLang))
    }

    private fun targetLangDisplayName(): String {
        val code = selectedTargetLang()
        val locale = Locale(code)
        return locale.getDisplayLanguage(locale)
            .replaceFirstChar { it.uppercase(locale) }
    }

    private fun copyToClipboard(text: String) {
        val ctx = context ?: return
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("PlayTranslate", text))
        Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
    }
}
