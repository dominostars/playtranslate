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
import com.playtranslate.CaptureService
import com.playtranslate.Prefs
import com.playtranslate.PtJson
import com.playtranslate.audio.Attribution
import com.playtranslate.audio.AudioRequest
import com.playtranslate.audio.AudioSelection
import com.playtranslate.translation.ChineseScriptConverter
import com.playtranslate.R
import com.playtranslate.applyAccentOverlay
import com.playtranslate.applyDialogEdgeToEdge
import com.playtranslate.fullScreenDialogTheme
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.playtranslate.dictionary.Deinflector
import com.playtranslate.language.DefinitionResolver
import com.playtranslate.language.DefinitionResult
import com.playtranslate.language.OfflineFallbackTranslators
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.tts.ttsTextForWord
import com.playtranslate.language.TargetGlossDatabaseProvider
import com.playtranslate.language.TatoebaClient
import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.Example
import com.playtranslate.model.headwordDisplay
import com.playtranslate.themeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import androidx.core.view.isVisible
import androidx.core.net.toUri
import androidx.core.view.isGone

class WordAnkiReviewSheet : DialogFragment() {

    private var isSentenceMode = false

    private lateinit var titleView: TextView
    private lateinit var toggleHost: FrameLayout
    private var deckSubtitleView: TextView? = null

    /** Controller for the Save button's idle ↔ loading swap. Bound in
     *  [onViewCreated], cleared in [onDestroyView]. */
    private var sendButton: AnkiSendButton? = null

    /** Mutable screenshot path. Initialised from [ARG_SCREENSHOT_PATH]
     *  at view creation — PINNED via [AnkiScreenshotPin], because the
     *  arg points at a fixed cache filename (`drag.jpg` /
     *  `capture-d{id}.jpg`) that any capture taken while this sheet is
     *  open overwrites (field report 2026-07-29: an accidental
     *  icon-drag replaced a card's screenshot with the Anki screen).
     *  Set to null when the user removes the photo via the screenshot
     *  card's ×. The Send handler reads from this field at click time
     *  so a deleted screenshot never gets uploaded to Anki. */
    private var currentScreenshotPath: String? = null

    /** The open-time pin backing [currentScreenshotPath], retained
     *  separately so photo-removal (which nulls the field) can't leak
     *  the file — released on provably-final teardown, same contract
     *  as the game-audio snapshot. */
    private var pinnedScreenshotPath: String? = null
    private lateinit var sentenceContainer: FrameLayout
    private lateinit var wordContainer: LinearLayout
    private var definitionsCard: LinearLayout? = null
    /** Handle to the word-tab Audio card. The switch state is read at
     *  send time; the pill label is refreshed after a picker pick. */
    private var wordAudioHandle: AnkiAudioToggleHandle? = null

    /** Multi-source audio selection for the word-tab headword cell. [Auto] is
     *  the registry's Commons-first → TTS resolution; [Explicit] is a pin from
     *  [AudioSourcePickerActivity]. Read at send time; mirrors the sentence
     *  tab's per-cell selection state. */
    private var wordSelection: AudioSelection = AudioSelection.Auto

    /** True while the word-tab pill's picker launch is in flight. The
     *  sheet doesn't host SentenceAnkiContentFragment's PickTarget
     *  machinery, so a simple boolean is enough — there's only one
     *  pill on the word tab. */
    private var pendingWordTabPick: Boolean = false

    private val audioPickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val wasOurs = pendingWordTabPick.also { pendingWordTabPick = false }
        if (!wasOurs) return@registerForActivityResult
        if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
        val src = result.data?.getStringExtra(AudioSourcePickerActivity.EXTRA_PICKED_SOURCE)
        val key = result.data?.getStringExtra(AudioSourcePickerActivity.EXTRA_PICKED_KEY)
        val locator = result.data?.getStringExtra(AudioSourcePickerActivity.EXTRA_PICKED_LOCATOR)
        val attribution = result.data?.getStringExtra(AudioSourcePickerActivity.EXTRA_PICKED_ATTRIBUTION)
            ?.let { runCatching { PtJson.lenient.decodeFromString(Attribution.serializer(), it) }.getOrNull() }
        wordSelection = if (src != null && key != null) {
            AudioSelection.Explicit(src, key, locator, attribution)
        } else {
            AudioSelection.Auto
        }
        // The word tab only ever has one sourceLangId — captured by the
        // outer onViewCreated; safe to read again from args here.
        val lang = SourceLangId.fromCode(arguments?.getString(ARG_SOURCE_LANG))
            ?: SourceLangId.JA
        wordAudioHandle?.refreshPillLabel(this, lang, wordSelection)
    }

    /** Opens the multi-source audio picker for the word-tab headword cell,
     *  seeded with the current [wordSelection]. The pick lands in
     *  [audioPickerLauncher]. Mirrors SentenceAnkiContentFragment's
     *  per-word launch (isWord = true → Commons is offered). */
    private fun launchWordAudioPicker(word: String, reading: String) {
        val ctx = context ?: return
        val lang = SourceLangId.fromCode(arguments?.getString(ARG_SOURCE_LANG))
            ?: SourceLangId.JA
        pendingWordTabPick = true
        audioPickerLauncher.launch(
            AudioSourcePickerActivity.intent(
                ctx, lang, word, reading.ifBlank { null }, isWord = true, current = wordSelection,
            ),
        )
    }

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
    /** Word-tab reading line. Held so the async dictionary lookup can
     *  re-render it with its pitch-accent contour once [resolvedEntry]
     *  lands. Null in sentence mode (the word header isn't built). */
    private var wordReadingView: TextView? = null
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

    /** Structured glossaries + dict ids for the card's Definition field —
     *  see the fetch beside [resolvedEntry]. */
    private var resolvedStyled: YomitanStyledData? = null

    /** The on-screen Definitions panel's styled renderer — ONE instance
     *  reused across [rebuildDefinitions] passes (a × tap must not churn
     *  WebViews), destroyed in [onDestroyView]. */
    private var ankiStyledView: YomitanDefinitionsView? = null

    /** Render process died once — stay native for the sheet's life. */
    private var ankiStyledBroken = false

    /**
     * Styled imported block for the ON-SCREEN Definitions panel (same
     * component as the lens/detail sheet). Returns false when the styled
     * path shouldn't/can't run — the caller renders the native rows.
     */
    private fun addStyledImportedPanel(card: android.widget.LinearLayout): Boolean {
        val styledData = resolvedStyled ?: return false
        if (styledData.structured.isEmpty() || ankiStyledBroken) return false
        val groups = resolvedEntry?.importedSenses.orEmpty()
        if (groups.isEmpty()) return false
        val ctx = requireContext()
        val v = ankiStyledView ?: YomitanDefinitionsView(
            ctx,
            DefinitionsDocument.Tokens(
                text = ctx.themeColor(R.attr.ptText),
                textMuted = ctx.themeColor(R.attr.ptTextMuted),
                textHint = ctx.themeColor(R.attr.ptTextHint),
                accent = ctx.themeColor(R.attr.ptAccent),
                panel = ctx.themeColor(R.attr.ptCard),
                baseFontSizePx = 15f,
            ),
        ).also { created ->
            if (!created.isUsable()) {
                ankiStyledBroken = true
                return false
            }
            val hPad = resources.getDimensionPixelSize(R.dimen.pt_row_h_padding)
            created.setPadding(hPad, dpi(8), hPad, dpi(8))
            created.onContentHeight = { h ->
                val lp = created.layoutParams
                    ?: android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1,
                    )
                lp.height = h + created.paddingTop + created.paddingBottom
                created.layoutParams = lp
            }
            created.onRendererGone = {
                ankiStyledView = null
                ankiStyledBroken = true
                rebuildDefinitions() // native rows take the block's place
            }
            ankiStyledView = created
        }
        // Re-parent for this rebuild pass (removeAllViews orphaned it).
        (v.parent as? android.view.ViewGroup)?.removeView(v)
        card.addView(
            v,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                (v.layoutParams?.height ?: 1).coerceAtLeast(1),
            ),
        )
        v.setContent(
            DefinitionsDocument.contentHtml(
                WordDefinitionData(
                    word = "", reading = null, senses = emptyList(),
                    freqScore = 0, isCommon = false,
                    importedGroups = groups,
                ),
                styledData.structured,
                localizePos = { it.joinToString(" · ") },
            ),
            styledData.dictStyles,
            styledData.sourceLanguage,
        )
        return true
    }

    private fun dpi(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

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

    /** Number of sentence-translation fetches currently in flight.
     *  A counter (rather than a boolean) because the user can commit an
     *  edit to Original while the prior sentence's fetch is still
     *  running, overlapping two coroutines — a boolean would clear on
     *  the stale one's finally and hide the indicator while the live
     *  request is still loading. Drives the left "fields loading"
     *  indicator on the Save button. */
    private var translationFillCount: Int = 0

    /** Number of word-lookup fetches currently in flight. Same
     *  overlap reasoning as [translationFillCount]. */
    private var wordsFillCount: Int = 0


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
            applyDialogEdgeToEdge(this, requireContext())
        }
    }

    override fun onDestroyView() {
        // Native WebView teardown for the styled Definitions panel — a
        // dropped-but-undestroyed WebView holds renderer resources until GC.
        ankiStyledView?.destroy()
        ankiStyledView = null
        definitionsCard = null
        moreExamplesGroup = null
        moreExamplesBody = null
        wordReadingView = null
        screenshotHeaderView = null
        screenshotCardView = null
        deckSubtitleView = null
        sendButton = null
        currentScreenshotPath = null
        // Release the open-time pin only on provably-final teardown —
        // the same guard as the game-audio snapshot in
        // SentenceAnkiContentFragment.onDestroyView: a saved-state
        // destroy must keep the file for the restored instance (whose
        // send-time pin copies it again anyway). Orphans from process
        // death go to AnkiScreenshotPin.sweepStale.
        if (activity?.isFinishing == true || !isStateSaved) {
            context?.let { AnkiScreenshotPin.release(it, pinnedScreenshotPath) }
        }
        pinnedScreenshotPath = null
        wordAudioHandle?.release()
        wordAudioHandle = null
        super.onDestroyView()
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(sys.left, sys.top, sys.right, maxOf(sys.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
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
        // restorations and the photo stays gone. Pinned immediately:
        // the arg is a fixed cache filename a capture during this
        // sheet's lifetime would overwrite. The pinned path is written
        // BACK into args (same in-place mutation the removal path uses)
        // so a saved-state recreation re-reads the immutable pin — not
        // the mutable cache file, which may hold a different frame by
        // then — and ADOPTS it rather than pinning a copy, so the
        // original pin keeps exactly one owner and is released on this
        // instance's final teardown instead of lingering for the sweep.
        val argScreenshotPath = args.getString(ARG_SCREENSHOT_PATH)
        pinnedScreenshotPath = if (AnkiScreenshotPin.isPin(requireContext(), argScreenshotPath)) {
            argScreenshotPath
        } else {
            AnkiScreenshotPin.pin(requireContext(), argScreenshotPath).also {
                args.putString(ARG_SCREENSHOT_PATH, it)
            }
        }
        currentScreenshotPath = pinnedScreenshotPath

        val sentenceOriginal    = args.getString(ARG_SENTENCE_ORIGINAL)
        val sentenceTranslation = args.getString(ARG_SENTENCE_TRANSLATION) ?: ""
        // The single shared gate every review-sheet entry point funnels through
        // (the per-cell Anki button, word-detail, the lens, the overlay, …). A
        // "sentence" that is just the headword — single-word lookups whose source
        // text equals the word — adds nothing over the word card, so treat it as
        // no-sentence: word-only, no toggle. Callers no longer each decide this.
        // meaningfulSentence is non-null exactly when a real sentence remains, so
        // it smart-casts to non-null at the use sites under `if (hasSentenceData)`.
        val meaningfulSentence  = sentenceOriginal?.takeUnless { sentenceIsJustTheWord(it, word) }
        val hasSentenceData     = meaningfulSentence != null

        val sourceLangId = SourceLangId.fromCode(args.getString(ARG_SOURCE_LANG)) ?: SourceLangId.JA

        // Curation sets always start empty: the dialog is locked to its
        // launch orientation (see onStart), so onViewCreated only fires
        // on the dialog's first open per fragment instance — there's no
        // saved-state restore path to honour.
        removedSenses.clear()
        removedExamples.clear()
        removedTatoebaIdx.clear()
        // Both modes possible → seed from the remembered preference (the
        // toggle below writes it back); word-only sheets stay word and
        // never touch the preference.
        isSentenceMode = hasSentenceData &&
            Prefs(requireContext()).ankiPreferredCardMode == CardMode.SENTENCE

        titleView = view.findViewById(R.id.tvWordAnkiSheetTitle)
        toggleHost = view.findViewById(R.id.wordAnkiToolbarToggle)

        if (hasSentenceData) {
            titleView.isGone = true
            toggleHost.isVisible = true
            buildAnkiModeToggle(
                container = toggleHost,
                leftLabel = getString(R.string.anki_mode_sentence),
                rightLabel = getString(R.string.anki_mode_word),
                leftActive = isSentenceMode,
            ) { leftSelected ->
                setMode(sentenceMode = leftSelected)
                // An explicit flip is the user's new default — for this
                // sheet's next open AND the one-tap long-press route.
                Prefs(requireContext()).ankiPreferredCardMode =
                    if (leftSelected) CardMode.SENTENCE else CardMode.WORD
            }
        }

        // Find the three stable hosts from XML. The fragment-host needs
        // a fixed ID so the FragmentManager can re-attach the
        // SentenceAnkiContentFragment to the same container after
        // rotation; the deck and word hosts are populated
        // programmatically every onViewCreated.
        val deckHost = view.findViewById<LinearLayout>(R.id.wordAnkiDeckHost)
        sentenceContainer = view.findViewById(R.id.wordAnkiSentenceHost)
        wordContainer = view.findViewById(R.id.wordAnkiWordHost)
        sentenceContainer.visibility = if (isSentenceMode) View.VISIBLE else View.GONE
        wordContainer.visibility = if (isSentenceMode) View.GONE else View.VISIBLE

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
            // wordsLoading = true tells the fragment "we'll call
            // applyWords later" so the empty list renders as
            // "Looking up words…" instead of zero rows. Stays false
            // when the host already has words in hand.
            val contentFragment = SentenceAnkiContentFragment.newInstance(
                meaningfulSentence, sentenceTranslation, sentenceWords,
                currentScreenshotPath, targetWord = word, sourceLangId = sourceLangId,
                wordsLoading = sentenceWords.isEmpty(),
                audioAnchorMs = args.takeIf { it.containsKey(ARG_AUDIO_ANCHOR_MS) }
                    ?.getLong(ARG_AUDIO_ANCHOR_MS),
            )
            childFragmentManager.beginTransaction()
                .replace(R.id.wordAnkiSentenceHost, contentFragment, TAG_CONTENT)
                .commitNow()

            // Fill in any missing pieces asynchronously. Drag → Anki
            // taps can race the prefetch: a fast tap arrives before
            // word lookups finish, and the drag flow never runs a
            // sentence translation. The fragment renders muted
            // placeholders until these calls land.
            // (hasSentenceData == (meaningfulSentence != null), so it
            // smart-casts to non-null here.)
            if (sentenceTranslation.isBlank()) {
                // The launcher's deferred pending (if any) rides the args —
                // the fill then COMPLETES the deferred capture (History rows
                // fill too) instead of translating around it.
                @Suppress("DEPRECATION")
                launchTranslationFill(
                    meaningfulSentence,
                    arguments?.getSerializable(ARG_SENTENCE_PENDING)
                        as? com.playtranslate.model.PendingTranslation,
                )
            }
            if (sentenceWords.isEmpty()) {
                launchWordsFill(meaningfulSentence, word)
            }
        }

        // Re-run the translation + words pipeline whenever the user
        // edits the Original sentence and commits (focus loss / Done).
        // The fragment has already reset its Translation field and Words
        // card to a loading state by the time this fires; we just kick
        // the same fills again. Empty target word: a prior pick may not
        // exist in the new sentence.
        //
        // Wired through getContentFragment() (rather than on the freshly-
        // created instance inside the `savedInstanceState == null` block)
        // so a fragment restored after a non-orientation configuration
        // change (locale / font-scale / etc.) still gets the callback.
        // Orientation itself is handled by the activity via the
        // manifest's `configChanges`, so rotation never lands here.
        if (hasSentenceData) {
            getContentFragment()?.onOriginalCommitted = { newOriginal ->
                // Edited text is NOT the deferred capture's text — never pass
                // the pending here (resolveAnkiTranslation's caller contract).
                launchTranslationFill(newOriginal)
                launchWordsFill(newOriginal, "")
            }
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

        val sendBtn = view.findViewById<FrameLayout>(R.id.btnWordAnkiSend)
        sendButton = AnkiSendButton(sendBtn)
        // launchTranslationFill / launchWordsFill above incremented
        // the in-flight counters before sendButton existed; apply them
        // now so the left indicator reflects current state.
        refreshFillingPendingIndicator()
        sendBtn.setOnClickListener {
            val deckId = Prefs(requireContext()).ankiDeckId
            if (deckId < 0L) {
                Toast.makeText(requireContext(), getString(R.string.anki_no_deck_selected), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendButton?.setLoading(true)
            viewLifecycleOwner.lifecycleScope.launch {
                if (isSentenceMode) {
                    sendSentenceToAnki(deckId)
                } else {
                    // Read currentScreenshotPath at click time, not
                    // capture it from the surrounding scope, so the
                    // screenshot's removed state actually wins.
                    sendWordToAnki(word, reading, pos,
                        fallbackDefinition, freqScore, deckId, currentScreenshotPath,
                        sourceLangId)
                }
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
        // Reading line — plain at first, then re-rendered with its pitch-accent
        // contour (the word-detail header's display) once the async lookup
        // resolves the entry. Always created and held so that update has a
        // target; hidden until there's a reading worth showing.
        val readingView = TextView(ctx).apply {
            text = reading
            textSize = 16f
            setTextColor(ctx.themeColor(R.attr.ptTextMuted))
            isVisible = reading.isNotBlank() && reading != word
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.topMargin = (8 * density).toInt() }
        }
        wordReadingView = readingView
        header.addView(readingView)
        // Apply now in case the entry already resolved (rebuilds); a no-op on
        // first build, where the async lookup calls it again once it lands.
        applyWordReadingPitch(word, reading)
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

        // ── Audio group: the headword's pronunciation, attached as [sound:].
        //    Multi-source (Commons recordings → TTS) via the same picker the
        //    sentence tab uses; the preview chip plays the cell's selection. ──
        wordAudioHandle = addAnkiAudioSection(
            parent = parent,
            lang = sourceLangId,
            rowLabel = word,
            // Preview text feeds the legacy TTS-only chip path; in selection
            // mode the resolver does its own text prep. Kept so the audition
            // matches the card's audio for the kana reading (JA; see ttsTextForWord).
            previewText = { ttsTextForWord(word, reading.ifBlank { null }, sourceLangId) },
            initialChecked = Prefs(ctx).ankiWordAudioEnabled,
            onCheckedChange = { Prefs(ctx).ankiWordAudioEnabled = it },
            selection = { wordSelection },
            audioRequest = { AudioRequest.word(word, reading.ifBlank { null }, sourceLangId) },
            onVoicePillTap = { launchWordAudioPicker(word, reading) },
        )

        // ── Definitions group. The resolve and this card build race in
        //    BOTH orders: built-first (the standalone word sheet) the
        //    async lookup's rebuildDefinitions replaces the placeholder;
        //    resolved-first (the sentence sheet's lazily-built word tab)
        //    the earlier rebuild hit a null definitionsCard and no-oped —
        //    so a card built AFTER the resolve must render immediately,
        //    or the placeholder stands forever (field-traced: lookup done
        //    ~160ms after launch, tab built later, "Looking up words…"
        //    stuck for the sheet's life).
        ankiGroupHeader(parent, getString(R.string.anki_group_definitions))
        val defCard = ankiGroupCard(parent)
        definitionsCard = defCard
        if (resolvedEntry != null) {
            rebuildDefinitions()
        } else {
            defCard.addView(buildLoadingDefinitionsRow(fallbackDefinition))
        }

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

    /**
     * Re-renders the word-tab reading line with its pitch-accent contour,
     * reusing the word-detail header's display ([buildPitchAnnotatedReading]).
     * Reads pitch from the same resolved headword the generated card uses, so
     * the screen and the card agree. Kana-only headwords carry no separate
     * reading but may still have pitch, so the kana is repeated as the contour
     * surface. No-op in sentence mode (the view is null) or with no pitch data
     * (the plain reading built in [buildWordContent] stands).
     */
    private fun applyWordReadingPitch(word: String, reading: String) {
        val view = wordReadingView ?: return
        val pitch = resolvedEntry?.headwordDisplay(word)?.pitch.orEmpty()
        if (pitch.isEmpty()) return
        val pitchKana = reading.takeIf { it.isNotBlank() }
            ?: word.takeIf { word.all(Deinflector::isKana) }
            ?: return
        val density = view.resources.displayMetrics.density
        view.text = buildPitchAnnotatedReading(pitchKana, pitch)
        // Headroom for the overline band — PitchAccentSpan leaves FontMetrics
        // alone by contract; the vertical header absorbs it as top padding.
        view.setPadding(0, (8 * density).toInt(), 0, 0)
        view.isVisible = true
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
        val enToTargetWrapper = OfflineFallbackTranslators.forTarget(targetLangCode)
        val charConverter = ChineseScriptConverter.forTarget(targetLangCode, prefs.targetChineseVariant)
        val resolver = DefinitionResolver(
            engine, targetGlossDb,
            OfflineFallbackTranslators.forPair(engine.profile.translationCode, targetLangCode),
            targetLangCode,
            enToTargetWrapper,
            charConverter,
        )
        android.util.Log.i(LOOKUP_TAG, "lookup start: '$word' reading=$readingHint lang=$sourceLangId")
        val defResult = withContext(Dispatchers.IO) { resolver.lookup(word, readingHint) }
        val response = defResult?.response
        val entries = response?.entries.orEmpty()
        val entry = entries.firstOrNull()
        if (!isAdded || entry == null) {
            // Field-trace: this silent return leaves the loading
            // placeholder standing forever — the exact symptom must name
            // its cause in the log.
            android.util.Log.i(
                LOOKUP_TAG,
                "lookup DEAD-END: '$word' isAdded=$isAdded entries=${entries.size} " +
                    "result=${defResult?.javaClass?.simpleName}",
            )
            return
        }
        resolvedEntry = entry
        resolvedEntries = entries
        resolvedFlatSenses = entries.flatMap { it.senses }
        resolvedDefResult = defResult
        // Structured-glossary payload for the CARD html (v005): fetched in
        // the same resolve, so the synchronous send-time builders can use
        // it. Null (flat dicts, styling off) leaves the flat text path.
        resolvedStyled = fetchYomitanStyledData(
            requireContext().applicationContext,
            (SourceLangId.fromCode(arguments?.getString(ARG_SOURCE_LANG)) ?: SourceLangId.JA)
                .yomitanConsumingLang(),
            entry.importedSenses,
        )
        // Now that the headword resolved, light up the reading's pitch contour.
        applyWordReadingPitch(word, readingHint.orEmpty())

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
        android.util.Log.i(
            LOOKUP_TAG,
            "lookup done: '$word' senses=${resolvedFlatSenses.size} " +
                "imported=${entry.importedSenses.sumOf { it.senses.size }} " +
                "styled=${resolvedStyled?.structured?.size ?: 0} -> rebuild",
        )
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
                        targetLangCode == "en" || enToTargetWrapper == null ->
                            raw.map { TatoebaClient.SentencePair(it.text, it.translation) }
                        else -> withContext(Dispatchers.IO) {
                            raw.map { ex ->
                                async {
                                    val translated = if (ex.translation.isBlank()) ""
                                    else try {
                                        enToTargetWrapper.translate(ex.translation)
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
                // Localize Tatoeba / fallback example targets to the chosen
                // Traditional variant (this path bypasses DefinitionResolver).
                val localized = charConverter?.let { c ->
                    effective?.map { it.copy(target = c.convert(it.target)) }
                } ?: effective
                tatoebaPairs = localized
                applyMoreExamples(localized)
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

        // Imported term-dictionary definitions lead, mirroring the detail
        // sheet and what buildWordDefinitionHtml puts on the card. Not
        // curatable in v1 (visibleSiblingCount = 1 suppresses the ×).
        // Styled path first (the same structured rendering the CARD gets,
        // so the creation page previews what will actually be sent);
        // native rows are the fallback.
        var importedRowCount = 0
        if (addStyledImportedPanel(card)) {
            importedRowCount = entry.importedSenses.sumOf { it.senses.size }
        } else {
            entry.importedSenses.forEach { group ->
                group.senses.forEachIndexed { defIdx, sense ->
                    if (importedRowCount > 0) ankiInsetDivider(card, indentDp = 16)
                    addAnkiSenseRow(
                        parent = card,
                        posLabels = buildList {
                            if (defIdx == 0) add(group.source)
                            if (sense.pos.isNotBlank()) add(sense.pos)
                        },
                        imported = true,
                        glossList = listOf(sense.definition),
                        senseNumber = null,
                        miscText = null,
                        examples = emptyList(),
                        senseIndex = -1,
                        visibleSiblingCount = 1,
                    )
                    importedRowCount++
                }
            }
        }
        val hasImportedRows = importedRowCount > 0

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
            val visibleTarget = nativeTargetSenses.withIndex()
                .filter { (idx, _) -> idx !in removedSenses }
            val numVisible = visibleTarget.size
            visibleTarget.forEachIndexed { displayIdx, (idx, target) ->
                val senseNumber = if (numVisible > 1) displayIdx + 1 else null
                if (displayIdx > 0 || hasImportedRows) {
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
                    miscText = requireContext().renderMiscText(target.misc),
                    examples = visibleExamples,
                    senseIndex = idx,
                    visibleSiblingCount = numVisible,
                )
            }
            if (visibleTarget.isEmpty() && !hasImportedRows) {
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
            if (displayCount > 0 || hasImportedRows) {
                ankiInsetDivider(card, indentDp = if (senseNumber != null) 42 else 16)
            }
            val visibleExamples = sense.examples.withIndex()
                .filter { (eIdx, _) -> (flatIdx to eIdx) !in removedExamples }
            addAnkiSenseRow(
                parent = card,
                posLabels = posLabels,
                glossList = glossList,
                senseNumber = senseNumber,
                miscText = requireContext().renderMiscText(sense.misc),
                examples = visibleExamples,
                senseIndex = flatIdx,
                visibleSiblingCount = numVisibleSenses,
            )
            displayCount++
        }

        if (displayCount == 0 && !hasImportedRows) {
            // Same guard as the target-driven branch above: an
            // imported-only word (senses=0, imported>0) HAS definitions —
            // appending the blank-fallback loading row here rendered a
            // literal "Looking up words…" under (or, with the styled
            // panel's async height, INSTEAD of) real content.
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
        /** Imported Yomitan rows pass a verbatim dictionary-name header in
         *  [posLabels] — never localized. Pack rows localize their POS. */
        imported: Boolean = false,
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
                text = String.format(Locale.getDefault(), "%d", senseNumber)
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
                text = (if (imported) posLabels.joinToString(" · ") else ctx.localizePos(posLabels))
                    .uppercase(Locale.ROOT)
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
                        "https://tatoeba.org/".toUri()
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
                group.isGone = true
            }
            else -> {
                val visible = pairs.withIndex()
                    .filter { (idx, _) -> idx !in removedTatoebaIdx }
                if (visible.isEmpty()) {
                    group.isGone = true
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

    /** Fetch a sentence translation and push the result into the embedded
     *  sentence fragment, via [resolveAnkiTranslation]: a deferred capture's
     *  [pending] runs the deferred completion (History rows fill,
     *  idempotently, never gated by the sentence-text cache); everything
     *  else keeps [LastSentenceCache.awaitOrStartTranslation]'s coalescing.
     *  A null outcome (no service alive, completion produced nothing)
     *  surfaces the "Couldn't translate" placeholder. */
    private fun launchTranslationFill(
        sentence: String,
        pending: com.playtranslate.model.PendingTranslation? = null,
    ) {
        translationFillCount++
        refreshFillingPendingIndicator()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val outcome = resolveAnkiTranslation(pending, sentence)
                getContentFragment()?.applyTranslation(sentence, outcome?.text)
            } finally {
                translationFillCount--
                refreshFillingPendingIndicator()
            }
        }
    }

    /** Fetch the sentence's per-word breakdown and push it into the
     *  embedded sentence fragment. Joins any in-flight word-lookup
     *  started by the drag controller's prefetch.
     *
     *  The payload's surfaces map is paired with its results at the
     *  point the deferred completed — reading
     *  [LastSentenceCache.surfaceForms] separately would race with
     *  live mode rotating the cache to a different sentence and
     *  attach the wrong inflected forms to this sheet's words. */
    private fun launchWordsFill(sentence: String, targetWord: String) {
        wordsFillCount++
        refreshFillingPendingIndicator()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val payload = LastSentenceCache.awaitOrStartWordLookups(
                    requireContext().applicationContext,
                    sentence,
                )
                val entries = payload.results.map { (w, triple) ->
                    SentenceAnkiHtmlBuilder.WordEntry(
                        w, triple.first, triple.second, triple.third,
                        surfaceForm = payload.surfaces[w].orEmpty(),
                        pitch = payload.enrichment[w]?.pitch.orEmpty(),
                        frequencies = payload.enrichment[w]?.frequencies.orEmpty(),
                        isCommon = payload.enrichment[w]?.isCommon ?: false,
                        senses = payload.enrichment[w]?.senses.orEmpty(),
                    )
                }
                getContentFragment()?.applyWords(sentence, entries, targetWord)
            } finally {
                wordsFillCount--
                refreshFillingPendingIndicator()
            }
        }
    }

    /** Drives the small left spinner on the Save button. Visible whenever
     *  either of the async sentence-fill jobs is still running. Safe to
     *  call after [onDestroyView] — [sendButton] is null and the call
     *  becomes a no-op. */
    private fun refreshFillingPendingIndicator() {
        sendButton?.setFillingPending(translationFillCount > 0 || wordsFillCount > 0)
    }

    private fun buildWordEntries(args: Bundle): List<SentenceAnkiHtmlBuilder.WordEntry> {
        val wordArr    = args.getStringArray(ARG_SENTENCE_WORDS) ?: return emptyList()
        val readingArr = args.getStringArray(ARG_SENTENCE_READINGS) ?: emptyArray()
        val meaningArr = args.getStringArray(ARG_SENTENCE_MEANINGS) ?: emptyArray()
        val freqArr    = args.getIntArray(ARG_SENTENCE_FREQ_SCORES) ?: IntArray(0)
        val surfaces   = LastSentenceCache.surfaceForms ?: emptyMap()
        val enrich     = LastSentenceCache.wordEnrichment ?: emptyMap()
        return wordArr.mapIndexed { i, w ->
            SentenceAnkiHtmlBuilder.WordEntry(
                w,
                readingArr.getOrElse(i) { "" },
                meaningArr.getOrElse(i) { "" },
                freqArr.getOrElse(i) { 0 },
                surfaceForm = surfaces[w] ?: "",
                pitch = enrich[w]?.pitch.orEmpty(),
                frequencies = enrich[w]?.frequencies.orEmpty(),
                isCommon = enrich[w]?.isCommon ?: false,
                senses = enrich[w]?.senses.orEmpty(),
            )
        }
    }

    // ── Send: word mode ──────────────────────────────────────────────────────

    private suspend fun sendWordToAnki(
        word: String, reading: String, pos: String, fallbackDefinition: String,
        freqScore: Int, deckId: Long, screenshotPath: String?,
        sourceLangId: SourceLangId,
    ) {
        // The default PlayTranslate model's Definition/Examples fields
        // use classStyler (the model CSS carries the gl-* classes) and
        // are SPLIT — senses in Definition, the "More examples" block
        // (with its section header) in Examples — so each is editable
        // on its own in Anki. The structured path uses inlineStyler
        // since the structured outputs ship with no CSS. Build all
        // eagerly so the pipeline can hand each to the right builder.
        val defaultDefinitionHtml = buildDefinitionPanel(classStyler)
        val defaultExamplesHtml = buildString {
            if (resolvedEntry != null) appendMoreExamplesHtml(classStyler)
        }
        // Pitch + per-dictionary frequencies for the structured path's
        // PITCH_POSITION / FREQUENCY_* sources, from the resolved entry's
        // headword matching this word (empty when no entry / no Yomitan data).
        val headword = resolvedEntry?.headwordDisplay(word)
        val input = WordSendInput(
            word = word,
            reading = reading,
            pos = pos,
            freqScore = freqScore,
            pitch = headword?.pitch.orEmpty(),
            frequencies = headword?.frequencies.orEmpty(),
            sourceLangId = sourceLangId,
            screenshotPath = screenshotPath,
            includeWordAudio = wordAudioHandle?.switch?.isChecked == true,
            // Multi-source selection (Commons-first → TTS) for the headword,
            // mirroring the sentence cell. Auto unless the user pinned a pick.
            wordSelection = wordSelection,
            defaultDefinitionHtml = defaultDefinitionHtml,
            defaultExamplesHtml = defaultExamplesHtml,
            inlineDefinitionHtml = buildWordDefinitionHtml(inlineStyler),
            inlineExamplesHtml = buildExamplesHtml(inlineStyler),
        )
        // Fragment receiver so NeedsMapping opens the mapping dialog
        // (Context.sendWordCard would skip it).
        val result = sendWordCard(input, deckId)
        val shortfallRes = (result as? AnkiSendResult.Success)?.mediaShortfallRes()
        applyAnkiSendResult(
            result,
            onSuccess = {
                shortfallRes?.let {
                    Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                }
                dismiss()
            },
            onRestore = { sendButton?.setLoading(false) },
        )
    }

    /**
     * Builds the Tatoeba example-sentences HTML for the structured-path
     * send (EXAMPLE_SENTENCES ContentSource). Omits the "More examples"
     * section header that [appendMoreExamplesHtml] emits — the
     * receiving field's template (e.g. Migaku's "Example Sentences")
     * already carries its own label. Honors [removedTatoebaIdx] so
     * user curation on the sheet is reflected in the card.
     */
    internal fun buildExamplesHtml(styler: HtmlStyler): String {
        val pairs = tatoebaPairs ?: return ""
        val visible = pairs.withIndex().filter { (idx, _) -> idx !in removedTatoebaIdx }
        if (visible.isEmpty()) return ""
        val sb = StringBuilder()
        visible.forEach { (_, p) ->
            sb.append("<div ${styler("gl-ex", "")}>")
            sb.append(htmlEscape(p.source))
            if (p.target.isNotBlank()) {
                sb.append("<div ${styler("gl-ex-tr", "")}>")
                sb.append(htmlEscape(p.target))
                sb.append("</div>")
            }
            sb.append("</div>")
        }
        return sb.toString()
    }

    /**
     * Builds the per-sense Definition HTML for the structured-path
     * word-card send (DEFINITION ContentSource). Mirrors the
     * `classStyler` branch in [sendWordToAnki]'s `defaultDefinitionHtml`
     * builder but emits inline styles (no CSS ships in the structured
     * path) via [inlineStyler], and keeps the "More examples" block
     * inline (the default model splits it into its own field). Honors
     * the same curation state ([removedSenses] / [removedExamples] /
     * [removedTatoebaIdx]) so what the user sees on the sheet is
     * what lands on the card.
     */
    internal fun buildWordDefinitionHtml(styler: HtmlStyler): String {
        val sb = StringBuilder()
        sb.append(buildDefinitionPanel(styler))
        if (resolvedEntry != null) sb.appendMoreExamplesHtml(styler)
        return sb.toString()
    }

    /**
     * The Definition field's full v002 chrome — localized "Definitions"
     * `.gl-section` header plus the `.gl-panel` holding the senses (or
     * the flat fallback when no entry resolved) — shared by the
     * default-model and structured sends so both read alike. "" when
     * there is nothing to show, keeping the field blank.
     */
    private fun buildDefinitionPanel(styler: HtmlStyler): String {
        val fallback = arguments?.getString(ARG_DEFINITION) ?: ""
        val entry = resolvedEntry
            ?: return WordAnkiHtmlBuilder.wrapFlatDefinitionHtml(
                fallback, styler, getString(R.string.anki_group_definitions))
        val body = buildString { appendSensesHtml(entry, fallback, styler) }
        if (body.isEmpty()) return ""
        // Tier 2: dictionaries whose structured senses render in this card
        // ship their styles.css, scoped, inline in the field (identity by
        // the data-dictionary the gl-sc blocks carry).
        val styles = resolvedStyled?.let { st ->
            val used = entry.importedSenses
                .filter { g -> g.senses.any { it.scRowid != null && st.structured.containsKey(it.scRowid) } }
                .map { it.dictId }
            AnkiCardCss.styleBlocks(used, st.dictStyles)
        }.orEmpty()
        return styles + "<div ${styler("gl-section", "")}>" +
            htmlEscape(getString(R.string.anki_group_definitions)) +
            "</div><div ${styler("gl-panel", "")}>" + body + "</div>"
    }

    private fun StringBuilder.appendSensesHtml(
        entry: DictionaryEntry, fallback: String, styler: HtmlStyler,
    ) {
        // Sense rows are flex: a right-aligned number column beside the
        // sense body (the lens's buildDefinitionRow, in HTML). One counter
        // across every branch keeps numbering continuous (imported rows
        // first, like the lens) and gives the structured path its
        // border-top:0 on row 0 (no :first-child inline equivalent; on the
        // default path it duplicates the .gl-sense:first-child rule,
        // harmlessly).
        var rowIdx = 0
        fun openSense() {
            append("<div ${styler("gl-sense", if (rowIdx == 0) "border-top:0;" else "")}>")
            append("<span ${styler("gl-sense-n gl-hint", "")}>")
            append(++rowIdx)
            append("</span><div ${styler("gl-sense-b", "")}>")
        }
        fun closeSense() {
            append("</div></div>")
        }
        // Imported term-dictionary definitions lead the card, final text.
        // The source label (and the entry's POS tags when present) sits
        // below the gloss like every other POS line in the v002 design.
        val hasImportedRows = entry.importedSenses.any { it.senses.isNotEmpty() }
        entry.importedSenses.forEach { group ->
            group.senses.forEachIndexed { defIdx, sense ->
                openSense()
                // Structured glossary when the sense retained one and the
                // resolve-time fetch delivered it (the Yomitan-standard
                // card shape: real lists/tables/chips, styled by the v005
                // GLOSSARY_CSS on the default model; browser-default
                // structure on user-picked note types). Images stay off
                // until dictionary media ships to collection.media. Flat
                // text remains the fallback for everything else.
                val structuredHtml = sense.scRowid
                    ?.let { resolvedStyled?.structured?.get(it) }
                    ?.let { YomitanContentHtml.glossaryHtml(it, group.dictId, includeImages = false) }
                if (structuredHtml != null) {
                    append("<div ${styler("gl-sc", "")} data-dictionary=\"")
                    append(htmlEscape(group.dictId))
                    append("\">")
                    append(structuredHtml)
                    append("</div>")
                } else {
                    append("<div ${styler("gl-gloss", "")}>")
                    append(htmlEscape(sense.definition).replace("\n", "<br>"))
                    append("</div>")
                }
                val label = buildList {
                    if (defIdx == 0) add(group.source)
                    if (sense.pos.isNotBlank()) add(sense.pos)
                }.joinToString(" · ")
                if (label.isNotEmpty()) {
                    append("<div ${styler("gl-pos gl-hint", "")}>")
                    append(htmlEscape(label))
                    append("</div>")
                }
                closeSense()
            }
        }

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
            val visibleTarget = nativeTargetSenses.withIndex()
                .filter { (idx, _) -> idx !in removedSenses }
            if (visibleTarget.isEmpty()) {
                // The flat fallback already contains the imported lines —
                // emitting it after the imported blocks would duplicate them.
                if (!hasImportedRows) {
                    val defHtml = fallback.lines().filter { it.isNotBlank() }
                        .joinToString("<br>") { htmlEscape(it.trimStart()) }
                    append("<div ${styler("gl-gloss", "padding:14px 0;")}>$defHtml</div>")
                }
                return
            }
            visibleTarget.forEach { (idx, target) ->
                val posLabels = target.pos.filter { it.isNotBlank() }
                    .takeIf { it.isNotEmpty() }
                    ?: fallbackPos
                openSense()
                append("<div ${styler("gl-gloss", "")}>")
                append(htmlEscape(target.glosses.joinToString("; ")))
                append("</div>")
                if (posLabels.isNotEmpty()) {
                    append("<div ${styler("gl-pos gl-hint", "")}>")
                    append(htmlEscape(posLabels.joinToString(" · ")))
                    append("</div>")
                }
                requireContext().renderMiscText(target.misc)?.let { misc ->
                    append("<div ${styler("gl-misc gl-hint", "")}>")
                    append(htmlEscape(misc))
                    append("</div>")
                }
                target.examples.withIndex()
                    .filter { (eIdx, _) -> (idx to eIdx) !in removedExamples }
                    .forEach { (_, ex) ->
                        append("<div ${styler("gl-ex gl-hint", "")}>")
                        append(htmlEscape(ex.text))
                        if (ex.translation.isNotBlank()) {
                            append("<div ${styler("gl-ex-tr", "")}>")
                            append(htmlEscape(ex.translation))
                            append("</div>")
                        }
                        append("</div>")
                    }
                closeSense()
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
            // so the card never goes empty. Skipped when imported blocks
            // already rendered (the fallback would duplicate them).
            if (!hasImportedRows) {
                val defHtml = fallback.lines().filter { it.isNotBlank() }
                    .joinToString("<br>") { htmlEscape(it.trimStart()) }
                append("<div ${styler("gl-gloss", "padding:14px 0;")}>$defHtml</div>")
            }
            return
        }
        visibleSenses.forEach { (flatIdx, sense) ->
            val target = targetByOrd?.get(flatIdx)
            val posLabels = (target?.pos ?: sense.partsOfSpeech).filter { it.isNotBlank() }
            val gloss = target?.glosses?.joinToString("; ")
                ?: translatedDefs?.getOrNull(flatIdx)
                ?: sense.targetDefinitions.joinToString("; ")
            openSense()
            append("<div ${styler("gl-gloss", "")}>")
            append(htmlEscape(gloss))
            append("</div>")
            if (posLabels.isNotEmpty()) {
                append("<div ${styler("gl-pos gl-hint", "")}>")
                append(htmlEscape(posLabels.joinToString(" · ")))
                append("</div>")
            }
            requireContext().renderMiscText(sense.misc)?.let { misc ->
                append("<div ${styler("gl-misc gl-hint", "")}>")
                append(htmlEscape(misc))
                append("</div>")
            }
            sense.examples.withIndex()
                .filter { (eIdx, _) -> (flatIdx to eIdx) !in removedExamples }
                .forEach { (eIdx, ex) ->
                    val tr = exampleTranslationCache[flatIdx to eIdx] ?: ex.translation
                    append("<div ${styler("gl-ex gl-hint", "")}>")
                    append(htmlEscape(ex.text))
                    if (tr.isNotBlank()) {
                        append("<div ${styler("gl-ex-tr", "")}>")
                        append(htmlEscape(tr))
                        append("</div>")
                    }
                    append("</div>")
                }
            closeSense()
        }
    }

    private fun StringBuilder.appendMoreExamplesHtml(styler: HtmlStyler) {
        val pairs = tatoebaPairs ?: return
        val visible = pairs.withIndex().filter { (idx, _) -> idx !in removedTatoebaIdx }
        if (visible.isEmpty()) return
        append("<div ${styler("gl-section", "")}>")
        append(getString(R.string.word_detail_more_examples))
        append("</div>")
        // .gl-rows: hairline-separated rows, no panel fill — this section is
        // reference, not answer. Row chrome rides the gl-row class: the
        // default model's CSS styles it (theme-adaptive hairline), the
        // structured path resolves it from INLINE_STYLES. Row 0 suppresses
        // its hairline inline on both paths (the structured path has no
        // :first-child equivalent; on the default path it duplicates the
        // CSS rule harmlessly).
        append("<div ${styler("gl-rows", "")}>")
        visible.forEachIndexed { i, (_, p) ->
            append("<div ${styler("gl-row", if (i == 0) "border-top:0;" else "")}>")
            append(htmlEscape(p.source))
            if (p.target.isNotBlank()) {
                append("<div ${styler("gl-ex-tr", "")}>")
                append(htmlEscape(p.target))
                append("</div>")
            }
            append("</div>")
        }
        append("</div>")
    }

    // ── Send: sentence mode ──────────────────────────────────────────────────

    private suspend fun sendSentenceToAnki(deckId: Long) {
        val content = getContentFragment() ?: run { sendButton?.setLoading(false); return }
        // Untrimmed game audio resolves here (trim editor opens once);
        // false = the user backed out of the editor — abort the send.
        if (!content.resolveGameAudioForSend()) {
            sendButton?.setLoading(false)
            return
        }
        val data = content.getCardData()
        val input = SentenceSendInput(
            original = data.source,
            translation = data.target,
            words = data.words,
            selectedWords = data.selectedWords,
            sourceLangId = data.sourceLangId,
            screenshotPath = data.screenshotPath,
            includeSentenceAudio = content.sentenceAudioEnabled,
            targetWordAudioWords = data.targetWordAudioWords,
            sentenceSelection = data.sentenceSelection,
            wordSelections = data.wordSelections,
            // Word-sheet's sentence tab carries Tatoeba "more examples"
            // for the structured path. Built with inlineStyler since the
            // structured outputs have no surrounding <style> block.
            examplesHtml = buildExamplesHtml(inlineStyler),
        )
        // Fragment receiver so NeedsMapping opens the mapping dialog
        // (Context.sendSentenceCard would skip it).
        val result = sendSentenceCard(input, deckId)
        val shortfallRes = (result as? AnkiSendResult.Success)?.mediaShortfallRes()
        applyAnkiSendResult(
            result,
            onSuccess = {
                shortfallRes?.let {
                    Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                }
                parentFragmentManager.setFragmentResult(
                    AnkiReviewBottomSheet.RESULT_ANKI_ADDED, bundleOf())
                dismiss()
            },
            onRestore = { sendButton?.setLoading(false) },
        )
    }

    companion object {
        const val TAG = "WordAnkiReviewSheet"

        /** Field-trace tag for the definitions resolve — the loading
         *  placeholder's replacement rides this coroutine, and every way
         *  it can fail is otherwise silent. */
        private const val LOOKUP_TAG = "WordSheetLookup"

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
        private const val ARG_SENTENCE_PENDING      = "sentence_pending"
        private const val ARG_SENTENCE_WORDS        = "sentence_words"
        private const val ARG_SENTENCE_READINGS     = "sentence_readings"
        private const val ARG_SENTENCE_MEANINGS     = "sentence_meanings"
        private const val ARG_SENTENCE_FREQ_SCORES  = "sentence_freq_scores"
        private const val ARG_SOURCE_LANG     = "source_lang"
        private const val ARG_AUDIO_ANCHOR_MS = "audio_anchor_ms"

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
            sourceLangId: SourceLangId = SourceLangId.JA,
            sentencePending: com.playtranslate.model.PendingTranslation? = null,
            audioAnchorMs: Long? = null,
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
                    // Only meaningful alongside its own sentenceOriginal
                    // (resolveAnkiTranslation's caller contract).
                    if (sentencePending != null) {
                        putSerializable(ARG_SENTENCE_PENDING, sentencePending)
                    }
                    if (sentenceWordResults != null) {
                        putStringArray(ARG_SENTENCE_WORDS, sentenceWordResults.keys.toTypedArray())
                        putStringArray(ARG_SENTENCE_READINGS, sentenceWordResults.values.map { it.first }.toTypedArray())
                        putStringArray(ARG_SENTENCE_MEANINGS, sentenceWordResults.values.map { it.second }.toTypedArray())
                        putIntArray(ARG_SENTENCE_FREQ_SCORES, sentenceWordResults.values.map { it.third }.toIntArray())
                    }
                }
                putString(ARG_SOURCE_LANG, sourceLangId.code)
                // Meaningful only alongside a sentence (the game-audio cell
                // lives in the hosted sentence fragment), but stored
                // unconditionally — the read side gates on the fragment.
                if (audioAnchorMs != null) putLong(ARG_AUDIO_ANCHOR_MS, audioAnchorMs)
            }
        }
    }
}
