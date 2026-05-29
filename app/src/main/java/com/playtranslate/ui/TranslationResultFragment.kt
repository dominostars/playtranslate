package com.playtranslate.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.text.StaticLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.playtranslate.AnkiManager
import com.playtranslate.CaptureService
import com.playtranslate.Prefs
import com.playtranslate.language.DefinitionGlossTranslators
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
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import androidx.core.view.isGone

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
    private lateinit var btnSpeakOriginal: ImageButton
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
    // FrameLayout so PillAnkiButton can overlay a centered spinner during
    // one-tap sends without breaking the pill's horizontal icon+label.
    private lateinit var btnResultAnki: FrameLayout
    private var pillAnkiButton: PillAnkiButton? = null

    /** Maps character ranges in original text to (displayWord, reading).
     *  Recomputed in [renderWordLookups] Settled branch from the VM's
     *  tokenSpans on each Settled emission, so it tracks the displayed
     *  text (which has OCR newlines). */
    private var wordSpans = mutableListOf<Triple<IntRange, String, String>>()
    private var furiganaPopup: PopupWindow? = null

    /** Drives the original-text speak button — TTS playback plus the icon
     *  highlight. Created per view in [setupButtons], released in
     *  [onDestroyView]. */
    private var speakButton: OriginalSpeakButton? = null

    /** Char range currently highlighted with the accent background while a
     *  word-lookup popup is active. Tracked separately from the span object
     *  so [applyFurigana] can re-attach the highlight after rebuilding the
     *  spannable. */
    private var highlightedWordRange: IntRange? = null

    /** Bumped on every [applyFurigana] call so an in-flight async render can tell
     *  it's been superseded (e.g. user toggled furigana off, or a newer render
     *  started) and bail before stamping stale spans. Main-thread-only. */
    private var furiganaRenderToken = 0

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
        speakButton?.release()
        speakButton = null
        pillAnkiButton = null
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
        btnSpeakOriginal     = view.findViewById(R.id.btnSpeakOriginal)
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
        // Tap opens the editable review sheet — the default and
        // discoverable action. Long-press is the power-user shortcut
        // that auto-creates the card with no review, documented by the
        // pro-tip footer in Settings → Anki.
        btnResultAnki.setOnClickListener {
            onAnkiClicked()
        }
        btnResultAnki.setOnLongClickListener {
            oneTapSentenceFromResult()
            true
        }
        pillAnkiButton = PillAnkiButton(btnResultAnki)
        speakButton = OriginalSpeakButton(
            btnSpeakOriginal,
            viewLifecycleOwner.lifecycleScope,
            TtsAlertTarget.InActivity(requireActivity()),
        ) {
            val text = getDisplayedOriginalText()
            if (text.isBlank()) null
            else OriginalSpeakButton.Request(text, prefs.sourceLangId)
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
        btnSpeakOriginal.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
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

        // Every call represents the latest desired furigana state; bump the token
        // so any async render still in flight from a prior call bails out.
        val token = ++furiganaRenderToken
        val plainText = tvOriginal.text.toString()
        if (!active || plainText.isEmpty()) {
            tvOriginal.text = plainText
            // The text reference just got swapped, so any active accent highlight
            // span was dropped — re-attach it from the tracked range.
            highlightedWordRange?.let { setWordHighlight(it) }
            return
        }
        // annotateForHintText tokenizes off the main thread (it's suspend); apply
        // the furigana spans back on the main thread. Bail if a newer applyFurigana
        // superseded us (toggle-off / re-render → token), or the displayed text
        // changed out from under us (new result → text guard).
        viewLifecycleOwner.lifecycleScope.launch {
            val engine = SourceLanguageEngines.get(ctx.applicationContext, prefs.sourceLangId)
            val annotations = engine.annotateForHintText(plainText)
            if (token != furiganaRenderToken || tvOriginal.text.toString() != plainText) return@launch
            if (annotations.isEmpty()) {
                tvOriginal.text = plainText
            } else {
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
            }
            // Re-attach the accent highlight dropped by the text swap.
            highlightedWordRange?.let { setWordHighlight(it) }
        }
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
                statusContainer.isGone = true
                resultsContent.isVisible = true
                resultActionButtons.isVisible = true
                resultsContent.scrollToTopSilently(scrollListener)
                tvTranslation.text = getString(R.string.status_translating)
                tvTranslationNote.text = ""
                tvTranslationNote.isGone = true
                applyTranslationVisibility()
                applyOriginalVisibility()
                applyWordsVisibility()
            }
            is ResultState.Ready -> {
                val result = state.result
                tvOriginal.setSegments(result.segments)
                tvOriginal.onTapAtOffset = { offset -> onOriginalTapped(offset) }
                tvTranslation.text = result.translatedText
                val warning = result.note
                val sourceLabel = result.backendDisplayName?.let {
                    getString(R.string.translation_source_label, it)
                }
                val bottomLabel = warning ?: sourceLabel
                tvTranslationNote.text = bottomLabel ?: ""
                tvTranslationNote.visibility = if (bottomLabel != null) View.VISIBLE else View.GONE
                tvTranslationNote.setTypeface(
                    null,
                    if (warning == null && sourceLabel != null) Typeface.ITALIC else Typeface.NORMAL,
                )
                applyTranslationVisibility()
                applyOriginalVisibility()
                applyWordsVisibility()
                labelOriginal.text = sourceLangLocalizedDisplayName()
                labelTranslation.text = targetLangDisplayName()
                statusContainer.isGone = true
                resultsContent.visibility = View.INVISIBLE
                resultActionButtons.isVisible = true
                btnResultAnki.isVisible = true
                resultsContent.scrollToTopSilently(scrollListener)
                resultsContent.post {
                    fitTextSizes()
                    if (view != null) resultsContent.isVisible = true
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
        tvLiveHint.isGone = true
        statusContainer.isVisible = true
        resultsContent.isGone = true
        btnResultAnki.isGone = true
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

    /** Lens Anki chip handler — adds the tapped word (not the sentence)
     *  to Anki. Mirrors [DragLookupController.openAnkiReviewForLens]:
     *  installation gate here, permission gate handled by the launched
     *  [AnkiPermissionActivity]. Sentence context comes from the current
     *  VM result so the card carries the source sentence + translation +
     *  screenshot. */
    private fun launchWordAnki(
        activity: Activity,
        word: String,
        reading: String?,
        entry: com.playtranslate.model.DictionaryEntry?,
    ) {
        val ankiManager = AnkiManager(activity)
        if (!ankiManager.isAnkiDroidInstalled()) {
            showAnkiNotInstalledDialog(activity)
            return
        }
        val pos = entry?.senses?.firstOrNull()?.partsOfSpeech
            ?.filter { it.isNotBlank() }?.joinToString(" · ") ?: ""
        val nonEmptySenses = entry?.senses
            ?.filter { it.targetDefinitions.isNotEmpty() }
            ?: emptyList()
        val definition = nonEmptySenses.mapIndexed { i, sense ->
            val prefix = if (nonEmptySenses.size > 1) "${i + 1}. " else ""
            prefix + sense.targetDefinitions.joinToString("; ")
        }.joinToString("\n")
        val ready = (vm.result.value as? ResultState.Ready)?.result
        val readingForExtra = reading?.takeIf { it != word } ?: ""
        dismissWordPopup()
        val intent = Intent(activity, AnkiPermissionActivity::class.java).apply {
            putExtra(WordAnkiReviewActivity.EXTRA_WORD, word)
            putExtra(WordAnkiReviewActivity.EXTRA_READING, readingForExtra)
            putExtra(WordAnkiReviewActivity.EXTRA_POS, pos)
            putExtra(WordAnkiReviewActivity.EXTRA_DEFINITION, definition)
            putExtra(WordAnkiReviewActivity.EXTRA_FREQ_SCORE, entry?.freqScore ?: 0)
            ready?.screenshotPath?.let {
                putExtra(WordAnkiReviewActivity.EXTRA_SCREENSHOT_PATH, it)
            }
            ready?.originalText?.let {
                putExtra(WordAnkiReviewActivity.EXTRA_SENTENCE_ORIGINAL, it)
            }
            ready?.translatedText?.let {
                putExtra(WordAnkiReviewActivity.EXTRA_SENTENCE_TRANSLATION, it)
            }
            putExtra(WordAnkiReviewActivity.EXTRA_SOURCE_LANG, prefs.sourceLangId.code)
        }
        activity.startActivity(intent)
    }

    /**
     * One-tap sentence-card send from the result-screen Anki button.
     * Falls back to the existing sheet flow ([onAnkiClicked]) on any
     * gate failure (AnkiDroid missing, permission denied, no deck
     * picked) so the user can still resolve the prerequisite. On
     * success/failure the button restores; NeedsMapping opens the
     * field-mapping dialog inline so the user can configure their
     * custom card type without leaving the result screen.
     */
    private fun oneTapSentenceFromResult() {
        host?.onInteraction()
        val result = (vm.result.value as? ResultState.Ready)?.result ?: return
        val activity = activity ?: return
        val ankiManager = AnkiManager(activity)
        if (!ankiManager.isAnkiDroidInstalled() || !ankiManager.hasPermission()) {
            onAnkiClicked()  // existing dialogs handle these gates
            return
        }
        if (prefs.ankiDeckId < 0L) {
            onAnkiClicked()  // sheet shows the deck picker
            return
        }
        val original = getDisplayedOriginalText()
        val translation = result.translatedText.takeIf { it.isNotEmpty() }
        // Snapshot rows ONCE so the words map and the surface map
        // come from the same Settled emission — no surfaceForms race
        // (see LastSentenceCache.awaitOrStartWordLookups docs).
        val settledRows = (vm.wordLookups.value as? WordLookupsState.Settled)?.rows
        val wordsPayload = settledRows?.let {
            LastSentenceCache.WordsPayload(it.toLegacyMap(), it.toSurfaceMap())
        }
        val screenshotPath = result.screenshotPath
        val pill = pillAnkiButton ?: return
        pill.setLoading(true)
        viewLifecycleOwner.lifecycleScope.launch {
            val sendResult = requireContext().oneTapSendSentence(
                original = original,
                translation = translation,
                wordsPayload = wordsPayload,
                screenshotPath = screenshotPath,
                sourceLangId = prefs.sourceLangId,
            )
            handleOneTapResult(sendResult, pill, CardMode.SENTENCE)
        }
    }

    /** Maps a one-tap [AnkiSendResult] to the result-screen UX. */
    private fun handleOneTapResult(
        sendResult: AnkiSendResult,
        pill: PillAnkiButton,
        mode: CardMode,
    ) {
        when (sendResult) {
            is AnkiSendResult.Success -> {
                val msgRes = if (sendResult.audioDropped || sendResult.wordAudioDropped)
                    R.string.anki_added_no_audio
                else
                    R.string.anki_added_success
                Toast.makeText(requireContext(), msgRes, Toast.LENGTH_SHORT).show()
                pill.setLoading(false)
            }
            is AnkiSendResult.Failed -> {
                val ctx = requireContext()
                OverlayAlert.Builder(requireActivity())
                    .hideIcon()
                    .setTitle(getString(R.string.anki_send_failed_title))
                    .setMessage(getString(sendResult.messageRes))
                    .addButton(
                        getString(android.R.string.ok),
                        ctx.themeColor(R.attr.ptAccent),
                        ctx.themeColor(R.attr.ptAccentOn),
                    ) {}
                    .show()
                pill.setLoading(false)
            }
            is AnkiSendResult.NeedsMapping -> {
                // Dispatcher already toasted; open the mapping dialog
                // so the user can fix the unmapped card type.
                showAnkiCardTypeMappingDialog(sendResult.model, mode) { _, _ -> }
                pill.setLoading(false)
            }
        }
    }

    /**
     * Headless one-tap counterpart to [launchWordAnki] for the in-app
     * word popup. Same data extraction (POS, joined definition) and
     * the same fallback to the existing Activity flow on gate failure.
     * Result Toast lands on the result screen so the user has feedback
     * without the popup needing to stay open during the send.
     */
    private fun oneTapWordFromPopup(
        activity: Activity,
        word: String,
        reading: String?,
        entry: com.playtranslate.model.DictionaryEntry?,
    ) {
        val ankiManager = AnkiManager(activity)
        if (!ankiManager.isAnkiDroidInstalled() || !ankiManager.hasPermission()) {
            launchWordAnki(activity, word, reading, entry)
            return
        }
        if (prefs.ankiDeckId < 0L) {
            launchWordAnki(activity, word, reading, entry)
            return
        }
        if (entry == null) {
            // No resolved entry — fall back so the user sees the error
            // path from inside the sheet rather than silently failing.
            launchWordAnki(activity, word, reading, entry)
            return
        }
        val pos = entry.senses.firstOrNull()?.partsOfSpeech
            ?.filter { it.isNotBlank() }?.joinToString(" · ") ?: ""
        val nonEmptySenses = entry.senses.filter { it.targetDefinitions.isNotEmpty() }
        val definition = nonEmptySenses.mapIndexed { i, sense ->
            val prefix = if (nonEmptySenses.size > 1) "${i + 1}. " else ""
            prefix + sense.targetDefinitions.joinToString("; ")
        }.joinToString("\n")
        val ready = (vm.result.value as? ResultState.Ready)?.result
        val screenshotPath = ready?.screenshotPath
        val readingClean = reading?.takeIf { it != word } ?: ""
        // The popup is anchored inside a translated sentence on the
        // result screen — the same context the lens chip has. Match
        // the lens behavior: send a sentence card with the tapped
        // word highlighted when sentence context is available, and
        // only fall back to a word card when the source text isn't a
        // sentence we have.
        val ready_sentence = ready?.originalText?.takeIf { it.isNotEmpty() }
        val ready_translation = ready?.translatedText?.takeIf { it.isNotEmpty() }
        // Atomic snapshot — see oneTapSentenceFromResult for the
        // surface-forms-race rationale.
        val settledRows = (vm.wordLookups.value as? WordLookupsState.Settled)?.rows
        val wordsPayload = settledRows?.let {
            LastSentenceCache.WordsPayload(it.toLegacyMap(), it.toSurfaceMap())
        }
        dismissWordPopup()
        Toast.makeText(
            activity, R.string.anki_adding_in_progress, Toast.LENGTH_SHORT,
        ).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = if (ready_sentence != null) {
                requireContext().oneTapSendSentence(
                    original = ready_sentence,
                    translation = ready_translation,
                    wordsPayload = wordsPayload,
                    screenshotPath = screenshotPath,
                    sourceLangId = prefs.sourceLangId,
                    targetWord = word,
                )
            } else {
                requireContext().oneTapSendWord(
                    word = word,
                    reading = readingClean,
                    pos = pos,
                    fallbackDefinition = definition,
                    freqScore = entry.freqScore,
                    screenshotPath = screenshotPath,
                    sourceLangId = prefs.sourceLangId,
                )
            }
            when (result) {
                is AnkiSendResult.Success -> {
                    // Sentence-mode one-tap can drop per-target-word
                    // audio (the target word may fail TTS or upload);
                    // surface that the same way the other handlers do.
                    val msgRes = if (result.audioDropped || result.wordAudioDropped)
                        R.string.anki_added_no_audio
                    else
                        R.string.anki_added_success
                    Toast.makeText(requireContext(), msgRes, Toast.LENGTH_SHORT).show()
                }
                is AnkiSendResult.Failed -> {
                    Toast.makeText(requireContext(), result.messageRes,
                        Toast.LENGTH_LONG).show()
                }
                is AnkiSendResult.NeedsMapping -> {
                    // Re-launch the Activity so the user can configure
                    // the mapping inside the sheet (dialog needs
                    // Fragment infrastructure).
                    launchWordAnki(activity, word, reading, entry)
                }
            }
        }
    }

    /** Anki button tap handler — view-side dialog work, kept fragment-
     *  internal. Reads sentence + word data from VM state. */
    private fun onAnkiClicked() {
        host?.onInteraction()
        val result = (vm.result.value as? ResultState.Ready)?.result ?: return
        val activity = activity ?: return
        val ankiManager = AnkiManager(activity)
        val wordResults = (vm.wordLookups.value as? WordLookupsState.Settled)
            ?.rows?.toLegacyMap() ?: emptyMap()
        when {
            !ankiManager.isAnkiDroidInstalled() ->
                showAnkiNotInstalledDialog(activity)
            !ankiManager.hasPermission() ->
                showAnkiPermissionRationaleDialog(activity) {
                    host?.getAnkiPermissionLauncher()?.launch(AnkiManager.PERMISSION)
                }
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
                val resolver = DefinitionResolver(engine, targetGlossDb,
                    mlKitTranslator?.let { WordTranslator(it::translate) }, prefs.targetLang,
                    DefinitionGlossTranslators.forTarget(prefs.targetLang))
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

                val dm = resources.displayMetrics
                // Anchor on the tapped line's top edge — paired with
                // [anchorHeight] = lineH, the lens lands cleanly above
                // the line when there's room and cleanly below the
                // line when it has to flip. Passing center + height=0
                // (the drag-flow default) lands the flipped lens on
                // top of the line itself.
                val anchorY = loc[1] + lineTop
                dismissWordPopup()
                val canOpen = entry != null
                val displayEntry = entry
                val lensData = MagnifierLens.LensDefinitionData(
                    word = word,
                    reading = popupReading?.takeIf { it != word },
                    senses = senses,
                    freqScore = freqScore,
                    isCommon = isCommon,
                )
                wordLens = MagnifierLens(
                    activity,
                    activity.windowManager,
                    android.view.Display.DEFAULT_DISPLAY,
                ).apply {
                    if (canOpen) {
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
                    }
                    // Tap opens the editable review sheet (default).
                    // Long-press is the headless one-tap shortcut —
                    // documented by the pro-tip footer in Settings.
                    onAnkiTap = {
                        host?.onInteraction()
                        launchWordAnki(activity, word, popupReading, displayEntry)
                    }
                    onAnkiLongPress = {
                        host?.onInteraction()
                        oneTapWordFromPopup(activity, word, popupReading, displayEntry)
                    }
                    // onDismiss is the single funnel for every teardown path
                    // (tap-outside, LensSpeakChip's no-engine action,
                    // dismissWordPopup), so speak-chip + lens cleanup lives
                    // here, not only in dismissWordPopup.
                    onDismiss = {
                        setWordHighlight(null)
                        wordSpeakChip?.release()
                        wordSpeakChip = null
                        wordLens = null
                    }
                }
                wordSpeakChip = wordLens?.let { lens ->
                    LensSpeakChip(
                        lens,
                        viewLifecycleOwner.lifecycleScope,
                        TtsAlertTarget.InActivity(activity),
                    ) { LensSpeakChip.Request(word, prefs.sourceLangId, reading = popupReading) }
                }
                setWordHighlight(span.first)
                wordLens?.show(
                    screenX, anchorY,
                    dm.widthPixels, dm.heightPixels,
                    anchorHeight = lineH,
                )
                wordLens?.setDefinitions(lensData, popupLabel)
                wordLens?.makeInteractive()
            } catch (_: Exception) {}
        }
    }

    private var wordLens: MagnifierLens? = null
    private var wordSpeakChip: LensSpeakChip? = null

    private fun dismissWordPopup() {
        // dismiss() fires the lens's onDismiss, which releases the speak chip
        // and clears wordLens / wordSpeakChip.
        wordLens?.dismiss()
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
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
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
                tvMainWordsLoading.isGone = true
                tvNoWords.isGone = true
                mainWordsContainer.removeAllViews()
                wordSpans.clear()
            }
            is WordLookupsState.Loading -> {
                dismissFurigana()
                dismissWordPopup()
                mainWordsContainer.removeAllViews()
                wordSpans.clear()
                tvMainWordsLoading.isVisible = true
                tvMainWordsLoading.text = getString(R.string.words_loading)
                tvNoWords.isGone = true
            }
            is WordLookupsState.Settled -> {
                renderWordRows(state.rows)
                recomputeWordSpans(state.tokenSpans, state.lookupToReading)
                applyFurigana()
                tvMainWordsLoading.isGone = true
                tvNoWords.visibility = if (state.rows.isEmpty()) View.VISIBLE else View.GONE
                btnResultAnki.isVisible = true
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
            tvFreq.isVisible = true
        } else {
            tvFreq.isGone = true
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
        return p.sourceLangId.displayName(Locale.forLanguageTag(p.targetLang))
    }

    private fun targetLangDisplayName(): String {
        val code = selectedTargetLang()
        val locale = Locale.forLanguageTag(code)
        return locale.getDisplayLanguage(locale)
            .replaceFirstChar { it.uppercase(locale) }
    }

    private fun copyToClipboard(text: String) {
        val ctx = context ?: return
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("PlayTranslate", text))
        Toast.makeText(ctx, ctx.getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
    }
}
