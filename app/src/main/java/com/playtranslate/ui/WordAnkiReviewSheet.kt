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
import com.playtranslate.applyAccentOverlay
import com.playtranslate.fullScreenDialogTheme
import com.playtranslate.language.DefinitionResolver
import com.playtranslate.language.DefinitionResult
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.language.TargetGlossDatabaseProvider
import com.playtranslate.language.TatoebaClient
import com.playtranslate.language.TranslationManagerProvider
import com.playtranslate.language.WordTranslator
import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.Example
import com.playtranslate.themeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class WordAnkiReviewSheet : DialogFragment() {

    private var isSentenceMode = false

    private lateinit var titleView: TextView
    private lateinit var toggleHost: FrameLayout
    private var deckSubtitleView: TextView? = null

    /** Mutable screenshot path. Initialised from [ARG_SCREENSHOT_PATH]
     *  at view creation, set to null when the user removes the photo
     *  via the screenshot card's ×. The Send handler reads from this
     *  field at click time so a deleted screenshot never gets uploaded
     *  to Anki — the previous code captured the original path in a
     *  local val and ignored later removals. */
    private var currentScreenshotPath: String? = null
    private lateinit var sentenceContainer: FrameLayout
    private lateinit var wordContainer: LinearLayout
    private var definitionsCard: LinearLayout? = null
    /** First child of the Screenshot group inside [wordContainer] (its
     *  header). Tracked so the lazy More examples group can be inserted
     *  immediately above the Screenshot group rather than appended to
     *  the bottom. Null when the entry has no screenshot. */
    private var screenshotHeaderView: View? = null
    /** Card-wrapper sibling of [screenshotHeaderView]. Held alongside so
     *  the screenshot group can be torn down from outside the
     *  user-initiated × callback (e.g., when the sentence tab removes
     *  the photo and we need to clear the word tab's mirror). */
    private var screenshotCardView: View? = null
    /** Outer wrapper of the More examples group (header + card). Stashed
     *  so we can hide the whole group when the user removes every row. */
    private var moreExamplesGroup: LinearLayout? = null
    /** Inner sentences container inside the More examples card; the
     *  Tatoeba fetch result and the per-row × handlers mutate this. */
    private var moreExamplesBody: LinearLayout? = null
    private var moreExamplesSourceLang: String = ""
    private var moreExamplesTargetLang: String = ""
    /** Cached resolved primary entry from the in-sheet dictionary lookup;
     *  Anki-card metadata (headword/freqScore/isCommon) reads from this.
     *  Null until the async lookup completes. */
    private var resolvedEntry: DictionaryEntry? = null
    /** Every entry the lookup returned. Used by the target-driven path's
     *  POS-fallback logic (blank-pos PanLex rows inherit the source-
     *  entry POS only when all entries agree). */
    private var resolvedEntries: List<DictionaryEntry> = emptyList()
    /** Flat sense list across every entry the lookup returned. Wiktionary
     *  packs split each POS section into its own entry, so multi-POS
     *  headwords ("man" → noun + verb) only render every sense when we
     *  iterate this. JMdict (single entry per surface) flatSenses ==
     *  resolvedEntry.senses, behavior unchanged. removedSenses /
     *  removedExamples key off positions in this list, so a sheet
     *  instance never juggles two index spaces. */
    private var resolvedFlatSenses: List<com.playtranslate.model.Sense> = emptyList()
    private var resolvedDefResult: DefinitionResult? = null

    /** Sense ords the user has removed via the row × — they're skipped
     *  in both the rendered Definitions card and the Anki HTML payload. */
    private val removedSenses = mutableSetOf<Int>()
    /** (sense ord, example index) pairs the user has removed via the
     *  per-example × inside a sense row. */
    private val removedExamples = mutableSetOf<Pair<Int, Int>>()
    /** Tatoeba pairs the user has removed via the More examples × . */
    private val removedTatoebaIdx = mutableSetOf<Int>()
    /** Per-sense per-example translation cache. Async ML-Kit results
     *  land here so subsequent re-renders (after ×-driven removals)
     *  pick up translations that already arrived. */
    private val exampleTranslationCache = mutableMapOf<Pair<Int, Int>, String>()
    /** Tatoeba "More examples" pairs (set after [TatoebaClient.fetch]
     *  resolves). Null = not yet fetched / unsupported / fetch failed. */
    private var tatoebaPairs: List<TatoebaClient.SentencePair>? = null

    /** Optional listener called when this sheet is dismissed (used by WordAnkiReviewActivity). */
    var onDismissListener: DialogInterface.OnDismissListener? = null

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDismissListener?.onDismiss(dialog)
    }

    override fun getTheme(): Int = fullScreenDialogTheme(requireContext())

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        applyAccentOverlay(dialog.context.theme, requireContext())
        return dialog
    }

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
        moreExamplesGroup = null
        moreExamplesBody = null
        screenshotHeaderView = null
        screenshotCardView = null
        deckSubtitleView = null
        currentScreenshotPath = null
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
        // Re-seed from args every time the view is created. If the user
        // removed the screenshot in a previous instance we also cleared
        // ARG_SCREENSHOT_PATH, so this returns null on subsequent
        // restorations and the photo stays gone.
        currentScreenshotPath = args.getString(ARG_SCREENSHOT_PATH)

        val sentenceOriginal    = args.getString(ARG_SENTENCE_ORIGINAL)
        val sentenceTranslation = args.getString(ARG_SENTENCE_TRANSLATION) ?: ""
        val hasSentenceData     = sentenceOriginal != null

        val sourceLangId = SourceLangId.fromCode(args.getString(ARG_SOURCE_LANG)) ?: SourceLangId.JA

        // Curation sets always start empty: the dialog is locked to its
        // launch orientation (see onStart), so onViewCreated only fires
        // on the dialog's first open per fragment instance — there's no
        // saved-state restore path to honour.
        removedSenses.clear()
        removedExamples.clear()
        removedTatoebaIdx.clear()
        isSentenceMode = hasSentenceData

        titleView = view.findViewById(R.id.tvWordAnkiSheetTitle)
        toggleHost = view.findViewById(R.id.wordAnkiToolbarToggle)

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

        // Find the three stable hosts from XML. The fragment-host needs
        // a fixed ID so the FragmentManager can re-attach the
        // SentenceAnkiContentFragment to the same container after
        // rotation; the deck and word hosts are populated
        // programmatically every onViewCreated.
        val deckHost = view.findViewById<LinearLayout>(R.id.wordAnkiDeckHost)
        sentenceContainer = view.findViewById(R.id.wordAnkiSentenceHost)
        wordContainer = view.findViewById(R.id.wordAnkiWordHost)
        sentenceContainer.visibility = if (hasSentenceData) View.VISIBLE else View.GONE
        wordContainer.visibility = if (hasSentenceData) View.GONE else View.VISIBLE

        deckSubtitleView = view.findViewById(R.id.tvWordAnkiSendSubtitle)
        addAnkiSection(
            parent = deckHost,
            mode = CardMode.WORD,
            onDeckChanged = { refreshDeckSubtitle() },
            onCardTypeChanged = { /* no visible affordance reflects card type */ },
        )
        refreshDeckSubtitle()

        buildWordContent(wordContainer, word, reading, pos, fallbackDefinition,
            freqScore, isCommon, sourceLangId, currentScreenshotPath)

        if (hasSentenceData && savedInstanceState == null) {
            val sentenceWords = buildWordEntries(args)
            val contentFragment = SentenceAnkiContentFragment.newInstance(
                sentenceOriginal ?: return, sentenceTranslation, sentenceWords,
                currentScreenshotPath, targetWord = word, sourceLangId = sourceLangId
            )
            childFragmentManager.beginTransaction()
                .replace(R.id.wordAnkiSentenceHost, contentFragment, TAG_CONTENT)
                .commitNow()
        }

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
                    // Read currentScreenshotPath at click time, not
                    // capture it from the surrounding scope, so the
                    // screenshot's removed state actually wins.
                    sendWordToAnki(word, reading, pos,
                        fallbackDefinition, freqScore, deckId, currentScreenshotPath)
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
                screenshotHeaderView = parent.getChildAt(parent.childCount - 2)
                screenshotCardView = parent.getChildAt(parent.childCount - 1)
                addWordScreenshotRow(ssCard, file) {
                    removeWordScreenshotFromUi()
                    // Keep the sentence tab in sync — its child fragment
                    // carries its own includePhoto flag and can leak the
                    // photo into the sentence card otherwise.
                    getContentFragment()?.removeScreenshotFromUi()
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

    /** Tear down the word-mode Screenshot group from the live view tree
     *  and clear the related state. Called both by the user-initiated ×
     *  on the word card and by [notifyScreenshotRemoved] when the
     *  sentence tab signals a removal. Idempotent — safe to call when
     *  the screenshot was never built or has already been cleared. */
    private fun removeWordScreenshotFromUi() {
        if (currentScreenshotPath == null && screenshotHeaderView == null) return
        screenshotHeaderView?.let { wordContainer.removeView(it) }
        screenshotCardView?.let { wordContainer.removeView(it) }
        screenshotHeaderView = null
        screenshotCardView = null
        // Clear both the live field (Send reads from here) and the
        // persisted argument (so a config-change recreate doesn't
        // resurrect the photo, and so addAnkiDeckRow / similar restarts
        // see the photo as gone).
        currentScreenshotPath = null
        arguments?.remove(ARG_SCREENSHOT_PATH)
    }

    /** Public hook for [SentenceAnkiContentFragment] to call when the
     *  user removes the photo from the sentence tab — keeps the word
     *  tab's mirror of the same shared media in sync. */
    fun notifyScreenshotRemoved() {
        removeWordScreenshotFromUi()
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
        moreExamplesSourceLang = sourceLangId.code
        moreExamplesTargetLang = targetLangCode
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
        val entries = response?.entries.orEmpty()
        val entry = entries.firstOrNull()
        if (!isAdded || entry == null) return
        resolvedEntry = entry
        resolvedEntries = entries
        resolvedFlatSenses = entries.flatMap { it.senses }
        resolvedDefResult = defResult

        // Seed the translation cache with stored pack translations for
        // target=en; ML-Kit fallback fills the rest in below. Indexed by
        // flat sense position so it lines up with the renderer.
        if (targetLangCode == "en") {
            resolvedFlatSenses.forEachIndexed { sIdx, s ->
                s.examples.forEachIndexed { eIdx, ex ->
                    if (ex.translation.isNotBlank()) {
                        exampleTranslationCache[sIdx to eIdx] = ex.translation
                    }
                }
            }
        }
        rebuildDefinitions()

        if (targetLangCode != "en") {
            viewLifecycleOwner.lifecycleScope.launch {
                val translated = runCatching {
                    withContext(Dispatchers.IO) { resolver.translateExamples(response!!) }
                }.getOrNull() ?: return@launch
                if (!isAdded) return@launch
                translated.forEachIndexed { sIdx, perSense ->
                    perSense.forEachIndexed { eIdx, tr ->
                        if (tr.isBlank()) return@forEachIndexed
                        exampleTranslationCache[sIdx to eIdx] = tr
                    }
                }
                rebuildDefinitions()
            }
        }

        // Tatoeba "More examples" — only when the API supports the pair.
        // Mirrors WordDetailBottomSheet's gating so we don't show a
        // placeholder that would later flip to "couldn't load examples"
        // for an unsupported language pair.
        if (TatoebaClient.supports(moreExamplesSourceLang, moreExamplesTargetLang)) {
            ensureMoreExamplesPlaceholder()
            viewLifecycleOwner.lifecycleScope.launch {
                val lookupWord = entry.headwords.firstOrNull()?.written ?: entry.slug
                val pairs = TatoebaClient.fetch(
                    word = lookupWord,
                    sourceLang = moreExamplesSourceLang,
                    targetLang = moreExamplesTargetLang,
                )
                if (!isAdded) return@launch
                // When Tatoeba returns nothing (no results / network), fall
                // back to the entry's per-sense examples. Wiktionary stores
                // translations in English; for target≠en we ML-translate
                // them in parallel before showing. Pulling from every
                // returned entry mirrors the multi-entry render below.
                val effective = if (!pairs.isNullOrEmpty()) pairs
                else {
                    val raw = entries
                        .flatMap { it.senses }
                        .flatMap { it.examples }
                        .filter { it.text.isNotBlank() }
                    when {
                        raw.isEmpty() -> null
                        targetLangCode == "en" || enToTarget == null ->
                            raw.map { TatoebaClient.SentencePair(it.text, it.translation) }
                        else -> withContext(Dispatchers.IO) {
                            raw.map { ex ->
                                async {
                                    val translated = if (ex.translation.isBlank()) ""
                                    else try {
                                        enToTarget.translate(ex.translation)
                                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        ex.translation
                                    }
                                    TatoebaClient.SentencePair(ex.text, translated)
                                }
                            }.awaitAll()
                        }
                    }
                }
                tatoebaPairs = effective
                applyMoreExamples(effective)
            }
        }
    }

    /**
     * Rebuilds the Definitions card from the resolved entry, the current
     * removal sets ([removedSenses], [removedExamples]) and the latest
     * cached example translations. Called both on initial paint and
     * after every × tap so the visible state stays in sync.
     */
    private fun rebuildDefinitions() {
        val entry = resolvedEntry ?: return
        val flatSenses = resolvedFlatSenses
        val card = definitionsCard ?: return
        card.removeAllViews()

        val defResult = resolvedDefResult
        val translatedDefs = when (defResult) {
            // Native renders target-driven and doesn't surface per-sense MT
            // fallback; only MT/English-fallback variants populate the field.
            is DefinitionResult.MachineTranslated -> defResult.translatedDefinitions
            is DefinitionResult.EnglishFallback -> defResult.translatedDefinitions
            else -> null
        }

        // Target-driven render path mirrors WordDetailBottomSheet — for
        // non-English targets with a Native pack hit, iterate the target
        // pack's senses directly. removedSenses keys on the target sense
        // index in this mode (entry-driven mode keeps using entry sense
        // indices); a sheet instance never switches modes mid-session, so
        // the two index spaces don't collide.
        val nativeTargetSenses = (defResult as? DefinitionResult.Native)
            ?.targetSenses
            ?.sortedBy { it.senseOrd }
            ?.takeIf { it.isNotEmpty() }
        val isTargetDriven = moreExamplesTargetLang != "en" && nativeTargetSenses != null

        if (isTargetDriven) {
            // Blank-pos target rows (PanLex) inherit the source-entry POS
            // only when every entry agrees; multi-POS source words yield
            // an empty fallback so blank rows render without a label.
            val fallbackPos = com.playtranslate.model
                .unambiguousFallbackPos(resolvedEntries)
            val visibleTarget = nativeTargetSenses!!.withIndex()
                .filter { (idx, _) -> idx !in removedSenses }
            val numVisible = visibleTarget.size
            visibleTarget.forEachIndexed { displayIdx, (idx, target) ->
                val senseNumber = if (numVisible > 1) displayIdx + 1 else null
                if (displayIdx > 0) {
                    ankiInsetDivider(card, indentDp = if (senseNumber != null) 42 else 16)
                }
                val posLabels = target.pos.filter { it.isNotBlank() }
                    .takeIf { it.isNotEmpty() }
                    ?: fallbackPos
                // removedExamples is keyed (senseIndex, exampleIndex);
                // target-driven mode uses the target sense ordinal as
                // senseIndex (the entry-driven branch uses flat-sense
                // ordinals). Filter by the same key so the × glyph's
                // removal is durable across rebuilds.
                val visibleExamples = target.examples.withIndex()
                    .filter { (eIdx, _) -> (idx to eIdx) !in removedExamples }
                addAnkiSenseRow(
                    parent = card,
                    posLabels = posLabels,
                    glossList = target.glosses,
                    senseNumber = senseNumber,
                    miscText = target.misc.takeIf { it.isNotEmpty() }?.joinToString(" · "),
                    examples = visibleExamples,
                    senseIndex = idx,
                    visibleSiblingCount = numVisible,
                )
            }
            if (visibleTarget.isEmpty()) {
                card.addView(buildLoadingDefinitionsRow(""))
            }
            return
        }

        val targetByOrd = if (defResult is DefinitionResult.Native)
            defResult.targetSenses.associateBy { it.senseOrd } else null

        val visibleSenses = flatSenses.withIndex().filter { (idx, s) ->
            s.targetDefinitions.isNotEmpty() && idx !in removedSenses
        }
        val numVisibleSenses = visibleSenses.size

        var displayCount = 0
        visibleSenses.forEach { (flatIdx, sense) ->
            val target = targetByOrd?.get(flatIdx)
            val posLabels = (target?.pos ?: sense.partsOfSpeech).filter { it.isNotBlank() }
            val glossList = target?.glosses
                ?: translatedDefs?.getOrNull(flatIdx)?.let { listOf(it) }
                ?: sense.targetDefinitions
            val senseNumber = if (numVisibleSenses > 1) displayCount + 1 else null
            if (displayCount > 0) {
                ankiInsetDivider(card, indentDp = if (senseNumber != null) 42 else 16)
            }
            val visibleExamples = sense.examples.withIndex()
                .filter { (eIdx, _) -> (flatIdx to eIdx) !in removedExamples }
            addAnkiSenseRow(
                parent = card,
                posLabels = posLabels,
                glossList = glossList,
                senseNumber = senseNumber,
                miscText = sense.misc.takeIf { it.isNotEmpty() }?.joinToString(" · "),
                examples = visibleExamples,
                senseIndex = flatIdx,
                visibleSiblingCount = numVisibleSenses,
            )
            displayCount++
        }

        if (displayCount == 0) {
            card.addView(buildLoadingDefinitionsRow(""))
        }
    }

    /** Per-sense row: number column + POS eyebrow + gloss + misc +
     *  accent-bar example blocks (each with its own × to remove that
     *  example), plus a trailing × on the row that drops the entire
     *  sense from the card. */
    private fun addAnkiSenseRow(
        parent: LinearLayout,
        posLabels: List<String>,
        glossList: List<String>,
        senseNumber: Int?,
        miscText: String?,
        examples: List<IndexedValue<Example>>,
        senseIndex: Int,
        visibleSiblingCount: Int,
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
        examples.forEachIndexed { displayIdx, indexedEx ->
            val originalIdx = indexedEx.index
            val ex = indexedEx.value
            // Prefer the ML-Kit-translated text when present; otherwise
            // fall back to the example's stored translation. Target-driven
            // mode has no cache entry (cache is keyed by flat source-sense
            // ord, but here senseIndex is a target-sense ord) and relies
            // on the stored target-pack `Example.translation`. Entry-
            // driven mode uses the cache once translateExamples lands.
            val cached = exampleTranslationCache[senseIndex to originalIdx]
                ?: ex.translation
            val block = buildAnkiExampleBlock(ctx, ex.text, cached) {
                removedExamples.add(senseIndex to originalIdx)
                rebuildDefinitions()
            }
            val topGap = if (displayIdx == 0) (10 * density).toInt() else (2 * density).toInt()
            (block.layoutParams as LinearLayout.LayoutParams).topMargin = topGap
            col.addView(block)
        }
        row.addView(col)
        // Trailing × — only render it when there's more than one
        // visible sense, so removing the only sense doesn't strand the
        // card with no definition. The count is supplied by the caller
        // because the relevant universe differs per render mode: target-
        // driven counts target senses, entry-driven counts the flat
        // source senses across every Wiktionary entry. Using the wrong
        // list strands the user with no remove glyph (or with one that
        // would empty the card).
        if (visibleSiblingCount > 1) {
            row.addView(buildPlainRemoveGlyph(ctx, leadingMargin = 8) {
                removedSenses.add(senseIndex)
                rebuildDefinitions()
            })
        }
        parent.addView(row)
    }

    /** Plain "✕" used by the in-card row removers (sense, example,
     *  Tatoeba). Pads to keep the hit target reasonable; no styled
     *  background so it stays subordinate to the content. */
    private fun buildPlainRemoveGlyph(
        ctx: Context,
        leadingMargin: Int = 0,
        onRemove: () -> Unit,
    ): TextView {
        val density = ctx.resources.displayMetrics.density
        return TextView(ctx).apply {
            text = "✕"
            textSize = 14f
            setTextColor(ctx.themeColor(R.attr.ptTextMuted))
            isClickable = true
            isFocusable = true
            contentDescription = ctx.getString(R.string.anki_word_remove_content_description)
            setPadding((10 * density).toInt(), (4 * density).toInt(),
                (10 * density).toInt(), (4 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.marginStart = (leadingMargin * density).toInt() }
            setOnClickListener { onRemove() }
        }
    }

    private fun buildAnkiExampleBlock(
        ctx: Context, text: String, initialTranslation: String,
        onRemove: () -> Unit,
    ): View {
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
        // Per-example × — strips just this example from the card
        // without dropping the surrounding sense.
        block.addView(buildPlainRemoveGlyph(ctx, leadingMargin = 4, onRemove))
        return block
    }

    // ── Tatoeba "More examples" ─────────────────────────────────────────

    /** Adds the More examples group (header + card with placeholder +
     *  attribution) to the word container if it isn't already there.
     *  Calling more than once is a no-op so we can lazily create it on
     *  first paint and re-use the same body on subsequent rebuilds. */
    private fun ensureMoreExamplesPlaceholder() {
        if (moreExamplesGroup != null) return
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val group = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        ankiGroupHeader(
            group,
            getString(R.string.word_detail_more_examples),
            getString(R.string.word_detail_group_tatoeba),
        )
        val card = ankiGroupCard(group)
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (16 * density).toInt(), (14 * density).toInt(),
                (16 * density).toInt(), (14 * density).toInt(),
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        body.addView(TextView(ctx).apply {
            text = getString(R.string.word_detail_more_examples_loading)
            textSize = 13f
            setTextColor(ctx.themeColor(R.attr.ptTextMuted))
            setTypeface(null, Typeface.ITALIC)
        })
        card.addView(body)
        card.addView(buildTatoebaAttributionFooter())
        // Park the group just above the Screenshot group when one's
        // present; otherwise append to the bottom. Insertion-by-index
        // beats appending so the user sees the natural reading order:
        // Definitions → More examples → Screenshot.
        val anchor = screenshotHeaderView
        if (anchor != null) {
            val anchorIdx = wordContainer.indexOfChild(anchor).coerceAtLeast(0)
            wordContainer.addView(group, anchorIdx)
        } else {
            wordContainer.addView(group)
        }
        moreExamplesGroup = group
        moreExamplesBody = body
    }

    private fun buildTatoebaAttributionFooter(): View {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_word_tatoeba_attribution)
            setPadding(
                (16 * density).toInt(), (10 * density).toInt(),
                (16 * density).toInt(), (10 * density).toInt(),
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
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        row.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_open_in_new)
            setColorFilter(ctx.themeColor(R.attr.ptTextHint))
            layoutParams = LinearLayout.LayoutParams(
                (12 * density).toInt(), (12 * density).toInt(),
            ).also { it.marginEnd = (6 * density).toInt() }
        })
        row.addView(TextView(ctx).apply {
            text = getString(R.string.word_detail_tatoeba_attribution)
            textSize = 11f
            setTextColor(ctx.themeColor(R.attr.ptTextHint))
        })
        return row
    }

    /** Replaces the placeholder body with the supplied [pairs] (or an
     *  error/empty state). Each rendered row carries a × that drops just
     *  that example from the card without taking down the whole group. */
    private fun applyMoreExamples(pairs: List<TatoebaClient.SentencePair>?) {
        val body = moreExamplesBody ?: return
        val group = moreExamplesGroup ?: return
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        body.removeAllViews()
        body.setPadding(0, 0, 0, 0)

        when {
            pairs == null -> {
                body.setPadding(
                    (16 * density).toInt(), (14 * density).toInt(),
                    (16 * density).toInt(), (14 * density).toInt(),
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
                val visible = pairs.withIndex()
                    .filter { (idx, _) -> idx !in removedTatoebaIdx }
                if (visible.isEmpty()) {
                    group.visibility = View.GONE
                    return
                }
                visible.forEachIndexed { displayIdx, indexed ->
                    val origIdx = indexed.index
                    val pair = indexed.value
                    if (displayIdx > 0) ankiInsetDivider(body, indentDp = 16)
                    body.addView(buildTatoebaRow(pair) {
                        removedTatoebaIdx.add(origIdx)
                        applyMoreExamples(tatoebaPairs)
                    })
                }
            }
        }
    }

    private fun buildTatoebaRow(
        pair: TatoebaClient.SentencePair,
        onRemove: () -> Unit,
    ): View {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (16 * density).toInt(), (12 * density).toInt(),
                (12 * density).toInt(), (12 * density).toInt(),
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            )
        }
        col.addView(TextView(ctx).apply {
            text = pair.source
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ctx.themeColor(R.attr.ptText))
        })
        col.addView(TextView(ctx).apply {
            text = pair.target
            textSize = 13f
            setTextColor(ctx.themeColor(R.attr.ptTextMuted))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.topMargin = (3 * density).toInt() }
        })
        row.addView(col)
        row.addView(buildPlainRemoveGlyph(ctx, leadingMargin = 4, onRemove))
        return row
    }

    // ── Deck group ───────────────────────────────────────────────────────

    /** Updates the save button's "Deck: <name>" subtitle whenever the
     *  user picks a different deck. Plain `?attr/ptAccentOn` text — the
     *  button's accent background would swallow an accent-tinted span. */
    private fun refreshDeckSubtitle() {
        val sub = deckSubtitleView ?: return
        val ctx = requireContext()
        val deckName = Prefs(ctx).ankiDeckName.ifBlank { ctx.getString(R.string.anki_deck_row_empty) }
        sub.text = ctx.getString(R.string.anki_deck_label_format, deckName)
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

    // ── Send: word mode ──────────────────────────────────────────────────────

    private suspend fun sendWordToAnki(
        word: String, reading: String, pos: String, fallbackDefinition: String,
        freqScore: Int, deckId: Long, screenshotPath: String?
    ) {
        val result = dispatchSendToAnki(
            deckId = deckId,
            mode = CardMode.WORD,
            screenshotPath = screenshotPath,
            legacyFront = { buildWordFrontHtml(word) },
            legacyBack = { imageFilename ->
                buildWordBackHtml(word, reading, pos, fallbackDefinition,
                    freqScore, imageFilename)
            },
            structured = { imageFilename ->
                AnkiCardOutputBuilder.forWord(
                    word = word,
                    reading = reading,
                    pos = pos,
                    definitionHtml = buildWordDefinitionHtml(inlineStyler),
                    freqScore = freqScore,
                    imageFilename = imageFilename,
                )
            },
        )
        when (result) {
            AnkiSendResult.Success -> {
                Toast.makeText(requireContext(), R.string.anki_added, Toast.LENGTH_SHORT).show()
                dismiss()
            }
            AnkiSendResult.Failed -> {
                Toast.makeText(requireContext(), R.string.anki_failed, Toast.LENGTH_SHORT).show()
            }
            AnkiSendResult.NeedsMapping -> { /* dispatcher already handled */ }
        }
    }

    private fun buildWordFrontHtml(word: String): String = buildString {
        append("<style>")
        append("body{margin:0;padding:0;}")
        append("</style>")
        append("<div class=\"gl-front\" style=\"text-align:center;font-size:2.2em;padding:32px 16px;\">$word</div>")
    }

    /**
     * Renders the back of the Anki word card. Prefers the resolved
     * dictionary entry (per-sense glosses + accent-rail example blocks
     * + Tatoeba "More examples"), filtered by the [removedSenses],
     * [removedExamples], and [removedTatoebaIdx] sets so what the user
     * sees on screen is exactly what lands on the card. Falls back to
     * the flat [fallbackDefinition] string when no entry is resolved.
     */
    private fun buildWordBackHtml(
        word: String, reading: String, pos: String,
        fallbackDefinition: String, freqScore: Int, imageFilename: String?,
    ): String = buildString {
        append("<style>")
        append("body{visibility:hidden!important;white-space:normal!important;}")
        append(".gl-front{display:none!important;}")
        append("#answer{display:none!important;}")
        append(".gl-back{visibility:visible!important;}")
        append(".gl-sense{margin:14px 4px;}")
        append(".gl-pos{font-size:0.78em;letter-spacing:0.08em;color:#888;text-transform:uppercase;}")
        append(".gl-gloss{font-size:1.1em;margin-top:4px;}")
        append(".gl-misc{font-size:0.85em;color:#888;font-style:italic;margin-top:2px;}")
        append(".gl-ex{margin:8px 0 0 8px;padding-left:10px;border-left:2px solid #6cd1c2;}")
        append(".gl-ex-tr{font-size:0.92em;color:#888;margin-top:2px;}")
        append(".gl-section{font-size:0.78em;letter-spacing:0.08em;color:#888;text-transform:uppercase;margin:18px 4px 6px;}")
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

        val entry = resolvedEntry
        if (entry != null) {
            // Legacy v004 path — surrounding <style> block provides the
            // gl-* CSS, so emit class refs.
            appendSensesHtml(entry, fallbackDefinition, classStyler)
            appendMoreExamplesHtml(classStyler)
        } else {
            val defHtml = fallbackDefinition.lines().filter { it.isNotBlank() }
                .joinToString("<br>") { it.trimStart() }
            append("<div style=\"font-size:1.1em;margin:12px 4px;\">$defHtml</div>")
        }
        append("</div>")
    }

    /**
     * Builds the per-sense Definition HTML for the structured-path
     * word-card send (DEFINITION ContentSource). Mirrors the legacy
     * branch logic in [buildWordBackHtml] but emits inline styles (no
     * surrounding `<style>` block ships in the structured path) via
     * [inlineStyler]. Honors the same curation state
     * ([removedSenses] / [removedExamples] / [removedTatoebaIdx]) so
     * what the user sees on the sheet is what lands on the card.
     */
    internal fun buildWordDefinitionHtml(styler: HtmlStyler): String {
        val entry = resolvedEntry
        val fallback = arguments?.getString(ARG_DEFINITION) ?: ""
        val sb = StringBuilder()
        if (entry != null) {
            sb.appendSensesHtml(entry, fallback, styler)
            sb.appendMoreExamplesHtml(styler)
        } else if (fallback.isNotBlank()) {
            val defHtml = fallback.lines().filter { it.isNotBlank() }
                .joinToString("<br>") { it.trimStart() }
            sb.append("<div ${styler("gl-gloss", "")}>$defHtml</div>")
        }
        return sb.toString()
    }

    private fun StringBuilder.appendSensesHtml(
        entry: DictionaryEntry, fallback: String, styler: HtmlStyler,
    ) {
        val flatSenses = resolvedFlatSenses
        val defResult = resolvedDefResult
        val translatedDefs = when (defResult) {
            is DefinitionResult.MachineTranslated -> defResult.translatedDefinitions
            is DefinitionResult.EnglishFallback -> defResult.translatedDefinitions
            else -> null
        }

        val nativeTargetSenses = (defResult as? DefinitionResult.Native)
            ?.targetSenses
            ?.sortedBy { it.senseOrd }
            ?.takeIf { it.isNotEmpty() }
        val isTargetDriven = moreExamplesTargetLang != "en" && nativeTargetSenses != null

        if (isTargetDriven) {
            // See rebuildDefinitions for the unambiguous-fallback rationale.
            val fallbackPos = com.playtranslate.model
                .unambiguousFallbackPos(resolvedEntries)
            val visibleTarget = nativeTargetSenses!!.withIndex()
                .filter { (idx, _) -> idx !in removedSenses }
            if (visibleTarget.isEmpty()) {
                val defHtml = fallback.lines().filter { it.isNotBlank() }
                    .joinToString("<br>") { it.trimStart() }
                append("<div style=\"font-size:1.1em;margin:12px 4px;\">$defHtml</div>")
                return
            }
            val numVisible = visibleTarget.size
            visibleTarget.forEachIndexed { displayIdx, (idx, target) ->
                val numberPrefix = if (numVisible > 1) "${displayIdx + 1}. " else ""
                val posLabels = target.pos.filter { it.isNotBlank() }
                    .takeIf { it.isNotEmpty() }
                    ?: fallbackPos
                append("<div ${styler("gl-sense", "")}>")
                if (posLabels.isNotEmpty()) {
                    append("<div ${styler("gl-pos", "")}>")
                    append(htmlEscape(posLabels.joinToString(" · ")))
                    append("</div>")
                }
                append("<div ${styler("gl-gloss", "")}>")
                append(numberPrefix)
                append(htmlEscape(target.glosses.joinToString("; ")))
                append("</div>")
                if (target.misc.isNotEmpty()) {
                    append("<div ${styler("gl-misc", "")}>")
                    append(htmlEscape(target.misc.joinToString(" · ")))
                    append("</div>")
                }
                target.examples.withIndex()
                    .filter { (eIdx, _) -> (idx to eIdx) !in removedExamples }
                    .forEach { (_, ex) ->
                        append("<div ${styler("gl-ex", "")}>")
                        append(htmlEscape(ex.text))
                        if (ex.translation.isNotBlank()) {
                            append("<div ${styler("gl-ex-tr", "")}>")
                            append(htmlEscape(ex.translation))
                            append("</div>")
                        }
                        append("</div>")
                    }
                append("</div>")
            }
            return
        }

        val targetByOrd = if (defResult is DefinitionResult.Native)
            defResult.targetSenses.associateBy { it.senseOrd } else null
        val visibleSenses = flatSenses.withIndex().filter { (idx, s) ->
            s.targetDefinitions.isNotEmpty() && idx !in removedSenses
        }
        if (visibleSenses.isEmpty()) {
            // User stripped every sense — the renderer hides the × on
            // the last visible sense, but defensive: emit the fallback
            // so the card never goes empty.
            val defHtml = fallback.lines().filter { it.isNotBlank() }
                .joinToString("<br>") { it.trimStart() }
            append("<div style=\"font-size:1.1em;margin:12px 4px;\">$defHtml</div>")
            return
        }
        val numVisible = visibleSenses.size
        visibleSenses.forEachIndexed { displayIdx, (flatIdx, sense) ->
            val target = targetByOrd?.get(flatIdx)
            val posLabels = (target?.pos ?: sense.partsOfSpeech).filter { it.isNotBlank() }
            val gloss = target?.glosses?.joinToString("; ")
                ?: translatedDefs?.getOrNull(flatIdx)
                ?: sense.targetDefinitions.joinToString("; ")
            val numberPrefix = if (numVisible > 1) "${displayIdx + 1}. " else ""
            append("<div ${styler("gl-sense", "")}>")
            if (posLabels.isNotEmpty()) {
                append("<div ${styler("gl-pos", "")}>")
                append(htmlEscape(posLabels.joinToString(" · ")))
                append("</div>")
            }
            append("<div ${styler("gl-gloss", "")}>")
            append(numberPrefix)
            append(htmlEscape(gloss))
            append("</div>")
            val miscText = sense.misc.takeIf { it.isNotEmpty() }?.joinToString(" · ")
            if (miscText != null) {
                append("<div ${styler("gl-misc", "")}>")
                append(htmlEscape(miscText))
                append("</div>")
            }
            sense.examples.withIndex()
                .filter { (eIdx, _) -> (flatIdx to eIdx) !in removedExamples }
                .forEach { (eIdx, ex) ->
                    val tr = exampleTranslationCache[flatIdx to eIdx] ?: ex.translation
                    append("<div ${styler("gl-ex", "")}>")
                    append(htmlEscape(ex.text))
                    if (tr.isNotBlank()) {
                        append("<div ${styler("gl-ex-tr", "")}>")
                        append(htmlEscape(tr))
                        append("</div>")
                    }
                    append("</div>")
                }
            append("</div>")
        }
    }

    private fun StringBuilder.appendMoreExamplesHtml(styler: HtmlStyler) {
        val pairs = tatoebaPairs ?: return
        val visible = pairs.withIndex().filter { (idx, _) -> idx !in removedTatoebaIdx }
        if (visible.isEmpty()) return
        append("<div ${styler("gl-section", "")}>")
        append(getString(R.string.word_detail_more_examples))
        append("</div>")
        visible.forEach { (_, p) ->
            append("<div ${styler("gl-ex", "")}>")
            append(htmlEscape(p.source))
            if (p.target.isNotBlank()) {
                append("<div ${styler("gl-ex-tr", "")}>")
                append(htmlEscape(p.target))
                append("</div>")
            }
            append("</div>")
        }
    }

    private fun htmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    // ── Send: sentence mode ──────────────────────────────────────────────────

    private suspend fun sendSentenceToAnki(deckId: Long) {
        val data = getContentFragment()?.getCardData() ?: return
        val result = dispatchSendToAnki(
            deckId = deckId,
            mode = CardMode.SENTENCE,
            screenshotPath = data.screenshotPath,
            legacyFront = {
                SentenceAnkiHtmlBuilder.buildFrontHtml(
                    data.source, data.words, data.selectedWords, data.sourceLangId,
                )
            },
            legacyBack = { imageFilename ->
                SentenceAnkiHtmlBuilder.buildBackHtml(
                    data.source, data.target, data.words,
                    imageFilename, data.selectedWords, data.sourceLangId,
                )
            },
            structured = { imageFilename ->
                AnkiCardOutputBuilder.forSentence(data, imageFilename)
            },
        )
        when (result) {
            AnkiSendResult.Success -> {
                Toast.makeText(requireContext(), R.string.anki_added, Toast.LENGTH_SHORT).show()
                parentFragmentManager.setFragmentResult(
                    AnkiReviewBottomSheet.RESULT_ANKI_ADDED, bundleOf())
                dismiss()
            }
            AnkiSendResult.Failed -> {
                Toast.makeText(requireContext(), R.string.anki_failed, Toast.LENGTH_SHORT).show()
            }
            AnkiSendResult.NeedsMapping -> { /* dispatcher already handled */ }
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
