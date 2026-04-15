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
import androidx.lifecycle.lifecycleScope
import com.playtranslate.AnkiManager
import com.playtranslate.CaptureService
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.dictionary.Deinflector
import com.playtranslate.dictionary.DictionaryManager
import com.playtranslate.model.TranslationResult
import com.playtranslate.themeColor
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Shared fragment that displays translation results: original text, translation,
 * word lookups, copy/Anki buttons. Used by both MainActivity and TranslationResultActivity.
 */
class TranslationResultFragment : Fragment() {

    /**
     * Host interface for activities that embed this fragment.
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
    private lateinit var labelOriginal: TextView
    private lateinit var labelTranslation: TextView
    private lateinit var tvNoWords: TextView
    private lateinit var tvTransliteration: TextView
    private lateinit var resultActionButtons: View
    private lateinit var btnResultClear: View
    private lateinit var btnResultAnki: View

    private var wordLookupJob: Job? = null
    val mainWordResults = mutableMapOf<String, Triple<String, String, Int>>()
    var lastResult: TranslationResult? = null
        private set

    /** Maps character ranges in original text to (displayWord, reading). */
    private var wordSpans = mutableListOf<Triple<IntRange, String, String>>()
    private var furiganaPopup: PopupWindow? = null

    /** Called when Anki button enabled state changes (e.g. after word lookups complete). */
    var onAnkiEnabledChanged: ((Boolean) -> Unit)? = null

    private val romajiTransliterator by lazy {
        try { android.icu.text.Transliterator.getInstance("Any-Latin; NFD; [:Nonspacing Mark:] Remove; NFC") }
        catch (_: Exception) { null }
    }

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
    }

    override fun onDestroyView() {
        dismissFurigana()
        dismissWordPopup()
        wordLookupJob?.cancel()
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
        labelOriginal        = view.findViewById(R.id.labelOriginal)
        labelTranslation     = view.findViewById(R.id.labelTranslation)
        tvNoWords            = view.findViewById(R.id.tvNoWords)
        tvTransliteration    = view.findViewById(R.id.tvTransliteration)
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
            onEditOriginalListener?.invoke()
        }
        resultsContent.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            if (scrollY != oldScrollY) { dismissFurigana(); dismissWordPopup() }
        }
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
            showStatus(getString(R.string.status_idle))
        }
        btnResultAnki.setOnClickListener {
            onAnkiClicked()
        }
    }

    private fun applyTranslationVisibility() {
        val hidden = prefs.hideTranslationSection
        translationContent.visibility = if (hidden) View.GONE else View.VISIBLE
        btnCopyTranslation.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        btnToggleTranslation.setImageResource(if (hidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility)
    }

    private fun applyOriginalVisibility() {
        val hidden = prefs.hideOriginalSection
        originalContent.visibility = if (hidden) View.GONE else View.VISIBLE
        btnCopyOriginal.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        btnEditOriginal.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        btnToggleFurigana.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        btnToggleOriginal.setImageResource(if (hidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility)
    }

    private fun applyWordsVisibility() {
        val hidden = prefs.hideWordsSection
        wordsContent.visibility = if (hidden) View.GONE else View.VISIBLE
        btnToggleWords.setImageResource(if (hidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility)
    }

    private fun applyFurigana() {
        val active = prefs.showFuriganaInline
        val ctx = context ?: return
        val accentColor = ctx.themeColor(R.attr.colorAccentPrimary)
        val secondaryColor = ctx.themeColor(R.attr.colorTextSecondary)
        btnToggleFurigana.imageTintList = android.content.res.ColorStateList.valueOf(
            if (active) accentColor else secondaryColor
        )

        val plainText = tvOriginal.text.toString()
        if (active && plainText.isNotEmpty()) {
            val dict = DictionaryManager.get(ctx.applicationContext)
            val tokens = dict.tokenizeForFurigana(plainText)
            if (tokens.isEmpty()) {
                tvOriginal.text = plainText
                return
            }
            val spannable = android.text.SpannableString(plainText)
            for (ft in tokens) {
                if (ft.endOffset > plainText.length) continue
                spannable.setSpan(
                    FuriganaSpan(ft.reading),
                    ft.startOffset, ft.endOffset,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            tvOriginal.text = spannable
        } else {
            tvOriginal.text = plainText
        }
    }

    // ── Public API ────────────────────────────────────────────────────────

    fun displayResult(result: TranslationResult) {
        if (!isAdded || view == null) return
        lastResult = result
        tvOriginal.setSegments(result.segments)
        tvOriginal.onTapAtOffset = { offset -> onOriginalTapped(offset) }
        tvTranslation.text = result.translatedText
        tvTranslationNote.text = result.note ?: ""
        tvTranslationNote.visibility = if (result.note != null) View.VISIBLE else View.GONE
        applyTranslationVisibility()
        applyOriginalVisibility()
        applyWordsVisibility()
        labelOriginal.text    = langDisplayName(selectedSourceLang())
        labelTranslation.text = langDisplayName(selectedTargetLang())
        statusContainer.visibility = View.GONE
        resultsContent.visibility  = View.INVISIBLE
        resultActionButtons.visibility = View.VISIBLE
        btnResultAnki.visibility = View.VISIBLE
        resultsContent.scrollTo(0, 0)
        onAnkiEnabledChanged?.invoke(false)
        // Fit text sizes after layout so view widths are available, then reveal
        resultsContent.post {
            fitTextSizes()
            resultsContent.visibility = View.VISIBLE
        }
        startWordLookups(result.originalText)
    }

    private companion object {
        const val TEXT_SIZE_MAX_SP = 24f
        const val TEXT_SIZE_MIN_SP = 16f
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

    /** Called by the host activity when its Anki button is tapped. */
    fun onAnkiClicked() {
        host?.onInteraction()
        val result = lastResult ?: return
        val ctx = context ?: return
        val ankiManager = AnkiManager(ctx)
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
                    getDisplayedOriginalText(), result.translatedText, mainWordResults, result.screenshotPath
                ).show(childFragmentManager, AnkiReviewBottomSheet.TAG)
        }
    }

    /** Called by the host when the edit button is tapped. */
    private var onEditOriginalListener: (() -> Unit)? = null

    fun setOnEditOriginalListener(listener: () -> Unit) {
        onEditOriginalListener = listener
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
                val dict = DictionaryManager.get(ctx.applicationContext)
                val response = withContext(Dispatchers.IO) {
                    dict.lookup(lookupForm, reading.ifEmpty { null })
                }
                val entry = response?.data?.firstOrNull()

                // Build popup data from the JMdict entry when we have one,
                // or fall back to a reading-only display when the tokenizer
                // produced a reading but no dictionary entry exists (proper
                // nouns live in ENAMDICT, which isn't bundled — same for
                // other out-of-dictionary tokens). If we don't even have a
                // reading there's nothing meaningful to show, so return.
                val word: String
                val senses: List<WordLookupPopup.SenseDisplay>
                val freqScore: Int
                val isCommon: Boolean
                if (entry != null) {
                    val form = entry.japanese.firstOrNull()
                    word = form?.word ?: form?.reading ?: entry.slug
                    senses = entry.senses.map { sense ->
                        WordLookupPopup.SenseDisplay(
                            pos = sense.partsOfSpeech.joinToString(", "),
                            definition = sense.englishDefinitions.joinToString("; ")
                        )
                    }
                    freqScore = entry.freqScore
                    isCommon = entry.isCommon == true
                } else if (reading.isNotEmpty()) {
                    word = lookupForm
                    senses = listOf(
                        WordLookupPopup.SenseDisplay(
                            pos = "",
                            definition = "Not in dictionary, may be a name"
                        )
                    )
                    freqScore = 0
                    isCommon = false
                } else {
                    return@launch
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
                val lookupReading = reading.ifEmpty { null }
                wordPopup = WordLookupPopup(activity, activity.windowManager).apply {
                    useActivityWindow = true
                    verticalMarginDp = 5
                    // Open-in-app would just re-run the same failing lookup,
                    // so suppress the button when we're in the fallback path.
                    showOpenButton = entry != null
                    onOpenTap = {
                        dismissWordPopup()
                        host?.onInteraction()
                        host?.onWordTapped(
                            word, lookupReading,
                            lastResult?.screenshotPath,
                            lastResult?.originalText,
                            lastResult?.translatedText,
                            mainWordResults.toMap()
                        )
                    }
                }
                wordPopup?.show(word, lookupReading, senses, freqScore,
                    isCommon, screenX, screenY, dm.widthPixels, dm.heightPixels,
                    anchorHeight = lineH)
            } catch (_: Exception) {}
        }
    }

    private var wordPopup: WordLookupPopup? = null

    private fun dismissWordPopup() {
        wordPopup?.dismiss()
        wordPopup = null
    }

    private fun showFurigana(range: IntRange, reading: String) {
        val ctx = context ?: return
        val layout = tvOriginal.layout ?: return
        val textLen = tvOriginal.text?.length ?: return
        val dm = resources.displayMetrics
        fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm).toInt()

        val bgColor = ctx.themeColor(R.attr.colorBgCard)
        val arrowW = dp(12f)
        val arrowH = dp(6f)

        val cornerR = dp(6f).toFloat()
        val tv = TextView(ctx).apply {
            text = reading
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ctx.themeColor(R.attr.colorTextPrimary))
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

    fun clearResult() {
        showStatus(getString(R.string.status_idle))
    }

    fun showStatus(msg: String) {
        if (!isAdded || view == null) return
        tvStatus.text = msg
        val isIdle = msg == getString(R.string.status_idle)
        tvStatusHint.visibility = if (isIdle) View.VISIBLE else View.GONE
        tvLiveHint.visibility   = View.GONE
        statusContainer.visibility = View.VISIBLE
        resultsContent.visibility  = View.GONE
        btnResultAnki.visibility = View.GONE
    }

    fun showError(msg: String) {
        showStatus(getString(R.string.status_error, msg))
    }

    /** Whether the results scroll view is currently visible. */
    val isShowingResults: Boolean
        get() = view != null && resultsContent.visibility == View.VISIBLE

    /** Access to the scroll view for scroll-pause detection. */
    fun getResultsScrollView(): ScrollView? = if (view != null) resultsContent else null

    fun setLiveHintText(text: CharSequence) {
        if (view != null) tvLiveHint.text = text
    }

    fun setLiveHintVisibility(visible: Boolean) {
        if (view != null) tvLiveHint.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun setStatusHintVisibility(visible: Boolean) {
        if (view != null) tvStatusHint.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun getStatusText(): String = if (view != null) tvStatus.text.toString() else ""

    /** Returns the displayed original text (with OCR line breaks preserved). */
    fun getDisplayedOriginalText(): String = if (view != null) tvOriginal.text?.toString() ?: "" else ""

    fun isStatusVisible(): Boolean = view != null && statusContainer.visibility == View.VISIBLE

    /** Update original text directly (for edit overlay commit). */
    fun updateOriginalText(newText: String) {
        if (view == null) return
        tvOriginal.text = newText
        tvTranslation.text = "…"
        tvTranslationNote.visibility = View.GONE
        lastResult = lastResult?.copy(originalText = newText, translatedText = "")
        startWordLookups(newText)
    }

    fun updateTranslation(translated: String) {
        if (view == null) return
        tvTranslation.text = translated
        lastResult = lastResult?.copy(translatedText = translated)
    }

    /** Show translating placeholder for drag-sentence flow. */
    fun showTranslatingPlaceholder(originalText: String, segments: List<com.playtranslate.model.TextSegment>) {
        if (!isAdded || view == null) return
        tvOriginal.setSegments(segments)
        tvOriginal.onTapAtOffset = { offset -> onOriginalTapped(offset) }
        labelOriginal.text = langDisplayName(selectedSourceLang())
        labelTranslation.text = langDisplayName(selectedTargetLang())
        statusContainer.visibility = View.GONE
        resultsContent.visibility = View.VISIBLE
        resultActionButtons.visibility = View.VISIBLE
        resultsContent.scrollTo(0, 0)
        onAnkiEnabledChanged?.invoke(false)

        tvTranslation.text = getString(R.string.status_translating)
        tvTranslationNote.text = ""
        tvTranslationNote.visibility = View.GONE
        applyTranslationVisibility()
        applyOriginalVisibility()
        applyWordsVisibility()
        startWordLookups(originalText)
    }

    // ── Word lookups ──────────────────────────────────────────────────────

    fun startWordLookups(text: String) {
        wordLookupJob?.cancel()
        mainWordsContainer.removeAllViews()
        mainWordResults.clear()
        wordSpans.clear()
        dismissFurigana()
        dismissWordPopup()
        tvMainWordsLoading.visibility = View.VISIBLE
        tvMainWordsLoading.text = getString(R.string.words_loading)
        tvNoWords.visibility = View.GONE
        tvTransliteration.visibility = View.GONE

        wordLookupJob = viewLifecycleOwner.lifecycleScope.launch {
            val romajiDeferred = async { buildRomaji(text) }

            val ctx = context ?: return@launch
            val allTokens = withContext(Dispatchers.IO) {
                DictionaryManager.get(ctx.applicationContext).tokenizeWithSurfaces(text)
            }

            // Build surface → character range mapping for furigana taps.
            // Use the displayed text (with OCR newlines) so offsets match tap positions.
            val displayedText = tvOriginal.text?.toString() ?: text
            val pendingSpans = mutableListOf<Triple<IntRange, String, String>>() // range, surface, lookupForm
            var searchFrom = 0
            for (tok in allTokens) {
                val idx = displayedText.indexOf(tok.surface, searchFrom)
                if (idx >= 0) {
                    pendingSpans.add(Triple(idx until idx + tok.surface.length, tok.surface, tok.lookupForm))
                    searchFrom = idx + tok.surface.length
                }
            }

            val seen = mutableSetOf<String>()
            val uniqueTokens = allTokens.filter { seen.add(it.lookupForm) }
            val tokens = uniqueTokens.map { it.lookupForm }

            if (tokens.isEmpty()) {
                tvMainWordsLoading.visibility = View.GONE
                tvNoWords.visibility = View.VISIBLE
                onAnkiEnabledChanged?.invoke(true)
                val romaji = romajiDeferred.await()
                if (romaji.isNotBlank() && romaji != text && false /* transliteration disabled */) {
                    tvTransliteration.text = romaji
                    tvTransliteration.visibility = View.VISIBLE
                }
                return@launch
            }

            val inflater = LayoutInflater.from(context)
            val surfaceByToken = uniqueTokens.associate { it.lookupForm to it.surface }
            val readingByToken = uniqueTokens.associate { it.lookupForm to it.reading }
            val rows = tokens.map { word ->
                val row = inflater.inflate(R.layout.item_word_lookup, mainWordsContainer, false)
                row.findViewById<TextView>(R.id.tvItemWord).text = word
                row.findViewById<TextView>(R.id.tvItemMeaning).text = "…"
                mainWordsContainer.addView(row)
                Pair(word, row)
            }

            val resultsArr = arrayOfNulls<Pair<String, Triple<String, String, Int>>>(rows.size)
            val surfaceArr = arrayOfNulls<Pair<String, String>>(rows.size)

            supervisorScope {
                rows.forEachIndexed { idx, (word, row) ->
                    launch {
                        val tvWord    = row.findViewById<TextView>(R.id.tvItemWord)
                        val tvReading = row.findViewById<TextView>(R.id.tvItemReading)
                        val tvFreq    = row.findViewById<TextView>(R.id.tvItemFreq)
                        val tvMeaning = row.findViewById<TextView>(R.id.tvItemMeaning)
                        var reading = ""
                        var meaning = ""
                        var displayWord = word
                        var freqScore = 0
                        try {
                            val appCtx = context?.applicationContext ?: return@launch
                            val response = withContext(Dispatchers.IO) {
                                DictionaryManager.get(appCtx).lookup(word, readingByToken[word])
                            }
                            if (response != null && response.data.isNotEmpty()) {
                                val entry   = response.data.first()
                                val primary = entry.japanese.firstOrNull()
                                displayWord = primary?.word ?: primary?.reading ?: word
                                tvWord.text = displayWord
                                reading = primary?.reading?.takeIf { it != primary.word } ?: ""
                                freqScore = entry.freqScore
                                meaning = entry.senses.mapIndexed { i, sense ->
                                    val glosses = sense.englishDefinitions.joinToString("; ")
                                    if (entry.senses.size > 1) "${i + 1}. $glosses" else glosses
                                }.joinToString("\n")
                            }
                        } catch (_: Exception) {}
                        if (meaning.isNotEmpty()) {
                            tvReading.text = reading
                            tvMeaning.text = meaning
                            if (freqScore > 0) {
                                tvFreq.text = "★".repeat(freqScore)
                                tvFreq.visibility = View.VISIBLE
                            }
                            val lookupWord = displayWord
                            val lookupReading = readingByToken[word]
                            row.setOnClickListener {
                                host?.onInteraction()
                                host?.onWordTapped(
                                    lookupWord,
                                    lookupReading,
                                    lastResult?.screenshotPath,
                                    lastResult?.originalText,
                                    lastResult?.translatedText,
                                    mainWordResults.toMap()
                                )
                            }
                            resultsArr[idx] = Pair(displayWord, Triple(reading, meaning, freqScore))
                            val surface = surfaceByToken[word]
                            if (surface != null && surface != displayWord) {
                                surfaceArr[idx] = Pair(displayWord, surface)
                            }
                        } else {
                            mainWordsContainer.removeView(row)
                        }
                    }
                }
            }

            resultsArr.filterNotNull().forEach { (dw, rmt) ->
                mainWordResults[dw] = rmt
            }
            val surfaces = surfaceArr.filterNotNull().toMap()

            // Build furigana spans: map lookupForm → reading from completed lookups.
            // tokens[idx] is the lookupForm; resultsArr[idx] has (displayWord, (reading, ...)).
            val lookupToReading = mutableMapOf<String, String>()
            tokens.forEachIndexed { idx, lookupForm ->
                val result = resultsArr[idx] ?: return@forEachIndexed
                val reading = result.second.first
                if (reading.isNotEmpty()) {
                    lookupToReading[lookupForm] = reading
                    // Also map the surface form so conjugated forms resolve
                    val surface = surfaceByToken[lookupForm]
                    if (surface != null && surface != lookupForm) {
                        lookupToReading[surface] = reading
                    }
                }
            }
            wordSpans.clear()
            for ((range, surface, lookupForm) in pendingSpans) {
                // Fall back to readingByToken (the tokenizer's direct Kuromoji
                // reading) when the JMdict lookup didn't produce one — this is
                // what lets proper nouns and other out-of-dictionary tokens
                // still carry a reading into the tap-popup fallback below.
                val reading = lookupToReading[lookupForm]
                    ?: lookupToReading[surface]
                    ?: readingByToken[lookupForm]
                    ?: ""
                wordSpans.add(Triple(range, lookupForm, reading))
            }

            applyFurigana()

            tvMainWordsLoading.visibility = View.GONE
            tvNoWords.visibility = if (mainWordResults.isEmpty()) View.VISIBLE else View.GONE
            btnResultAnki.visibility = View.VISIBLE
            onAnkiEnabledChanged?.invoke(true)

            LastSentenceCache.original = lastResult?.originalText
            LastSentenceCache.translation = lastResult?.translatedText
            LastSentenceCache.wordResults = mainWordResults.toMap()
            LastSentenceCache.surfaceForms = surfaces

            val romaji = romajiDeferred.await()
            if (romaji.isNotBlank() && romaji != text && false /* transliteration disabled */) {
                tvTransliteration.text = romaji
                tvTransliteration.visibility = View.VISIBLE
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private suspend fun buildRomaji(text: String): String = withContext(Dispatchers.IO) {
        val t = romajiTransliterator ?: return@withContext ""
        Deinflector.toKanaTokens(text).joinToString(" ") { t.transliterate(it) }
    }

    private fun selectedSourceLang() = TranslateLanguage.JAPANESE
    private fun selectedTargetLang() = TranslateLanguage.ENGLISH

    private fun langDisplayName(langCode: String): String =
        Locale(langCode).getDisplayLanguage(Locale.ENGLISH)
            .replaceFirstChar { it.uppercase() }

    private fun copyToClipboard(text: String) {
        val ctx = context ?: return
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("PlayTranslate", text))
        Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
    }
}
