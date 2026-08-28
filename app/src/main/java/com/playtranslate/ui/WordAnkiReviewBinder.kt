package com.playtranslate.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.net.toUri
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.playtranslate.AnkiManager
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.audio.AudioRequest
import com.playtranslate.audio.AudioSelection
import com.playtranslate.dictionary.Deinflector
import com.playtranslate.language.DefinitionResolver
import com.playtranslate.language.DefinitionResult
import com.playtranslate.language.OfflineFallbackTranslators
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.language.TargetGlossDatabaseProvider
import com.playtranslate.language.TatoebaClient
import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.Example
import com.playtranslate.model.headwordDisplay
import com.playtranslate.themeColor
import com.playtranslate.translation.ChineseScriptConverter
import com.playtranslate.tts.ttsTextForWord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * The word/sentence Anki review editor — headword header, multi-source
 * audio cell, curatable Definitions (styled + native), Tatoeba "More
 * examples", screenshot, the Sentence/Word mode toggle, the deck section,
 * and both send paths with their card-HTML builders — as a host-agnostic
 * binder extracted from [WordAnkiReviewSheet]. The DialogFragment hosts it
 * inside its dialog window; the floating workspace's editor page hosts the
 * SAME binder over the game.
 *
 * The sentence side is a [SentenceAnkiContentView] driven DIRECTLY (the
 * childFragmentManager hop is gone): its launch-state bundle is created
 * here ([contentArgs]) and persisted by the fragment shell across
 * saved-state destroys via [saveState]/the savedInstanceState handed to
 * [bind] — preserving the old child-fragment auto-restore semantics
 * (applied translation/words survive; curation resets, as it always did).
 *
 * [args] is the sheet's launch bundle (the [WordAnkiReviewSheet.newInstance]
 * shape), kept as the mutable carrier for the screenshot pin write-back.
 */
class WordAnkiReviewBinder(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val args: Bundle,
    private val host: Host,
) {
    /** The host seam. */
    interface Host {
        val isAlive: Boolean

        /** Present the audio-source picker; deliver the parsed selection
         *  (never called back on cancel). One seam for the word tab AND the
         *  sentence content's cells. */
        fun openAudioPicker(intent: Intent, onPicked: (AudioSelection) -> Unit)

        /** Present the deck / card-type pickers (dialogs, or workspace
         *  pushes). Both no-op silently when AnkiDroid is absent or the
         *  permission is missing — the fragment helpers' contract. */
        fun openDeckPicker(onPicked: (deckId: Long, deckName: String) -> Unit)
        fun openCardTypePicker(onPicked: (modelId: Long, modelName: String) -> Unit)

        /** A send came back NeedsMapping — present the field-mapping editor
         *  (the dispatcher already toasted the explanation). */
        fun openFieldMapping(model: AnkiManager.ModelInfo, mode: CardMode)

        /** Terminal for the send-failed alert. */
        fun presentAlert(builder: OverlayAlert.Builder)

        /** A sentence card landed — the sheet host raises its fragment
         *  result so the results page refreshes; the workspace no-ops. */
        fun onSentenceCardAdded() {}

        /** Hold point before the resolve's completion bind — the workspace
         *  suspends until its enter animation settles so the definitions
         *  rebuild (and its styled WebView) can't drop entrance frames; the
         *  sheet host holds nothing. Callers re-check [isAlive] after. */
        suspend fun awaitEnterSettled() {}

        /** Close the hosting surface (a card was sent, or back). */
        fun dismiss()
    }

    private var isSentenceMode = false

    private lateinit var rootView: View
    private lateinit var titleView: TextView
    private lateinit var toggleHost: FrameLayout
    private var deckSubtitleView: TextView? = null

    /** Controller for the Save button's idle ↔ loading swap. Bound in
     *  [bind], cleared in [release]. */
    private var sendButton: AnkiSendButton? = null

    /** Mutable screenshot path. Initialised from [ARG_SCREENSHOT_PATH]
     *  at bind — PINNED via [AnkiScreenshotPin], because the arg points
     *  at a fixed cache filename that any capture taken while this
     *  editor is open overwrites (field report 2026-07-29). Set to null
     *  when the user removes the photo. The Send handler reads from this
     *  field at click time so a deleted screenshot never gets uploaded. */
    private var currentScreenshotPath: String? = null

    /** The open-time pin backing [currentScreenshotPath], retained
     *  separately so photo-removal (which nulls the field) can't leak
     *  the file — released on provably-final teardown ([release]). */
    private var pinnedScreenshotPath: String? = null

    private lateinit var sentenceContainer: FrameLayout
    private lateinit var wordContainer: LinearLayout
    private var definitionsCard: LinearLayout? = null

    /** Handle to the word-tab Audio card. The switch state is read at
     *  send time; the pill label is refreshed after a picker pick. */
    private var wordAudioHandle: AnkiAudioToggleHandle? = null

    /** Multi-source audio selection for the word-tab headword cell. */
    private var wordSelection: AudioSelection = AudioSelection.Auto

    /** The directly-hosted sentence content (null for word-only sheets). */
    private var contentView: SentenceAnkiContentView? = null

    /** The content's launch-state bundle — created fresh at first bind,
     *  restored from the shell's saved state on recreation so applied
     *  translation/words survive (the old child-fragment semantics). */
    private var contentArgs: Bundle? = null

    private fun launchWordAudioPicker(word: String, reading: String) {
        val lang = SourceLangId.fromCode(args.getString(ARG_SOURCE_LANG))
            ?: SourceLangId.JA
        host.openAudioPicker(
            AudioSourcePickerActivity.intent(
                ctx, lang, word, reading.ifBlank { null }, isWord = true, current = wordSelection,
            ),
        ) { selection ->
            wordSelection = selection
            wordAudioHandle?.refreshPillLabel(scope, lang, wordSelection)
        }
    }

    /** First child of the Screenshot group inside [wordContainer] (its
     *  header). Tracked so the lazy More examples group can be inserted
     *  immediately above the Screenshot group rather than appended to
     *  the bottom. Null when the entry has no screenshot. */
    private var screenshotHeaderView: View? = null
    /** Card-wrapper sibling of [screenshotHeaderView]. */
    private var screenshotCardView: View? = null
    /** Outer wrapper of the More examples group (header + card). */
    private var moreExamplesGroup: LinearLayout? = null
    /** Inner sentences container inside the More examples card. */
    private var moreExamplesBody: LinearLayout? = null
    private var moreExamplesSourceLang: String = ""
    private var moreExamplesTargetLang: String = ""
    /** Word-tab reading line — re-rendered with its pitch contour once
     *  [resolvedEntry] lands. Null in sentence mode. */
    private var wordReadingView: TextView? = null
    /** Cached resolved primary entry from the in-sheet dictionary lookup. */
    private var resolvedEntry: DictionaryEntry? = null
    /** Every entry the lookup returned (multi-POS Wiktionary packs). */
    private var resolvedEntries: List<DictionaryEntry> = emptyList()
    /** Flat sense list across every entry the lookup returned.
     *  removedSenses / removedExamples key off positions in this list. */
    private var resolvedFlatSenses: List<com.playtranslate.model.Sense> = emptyList()
    private var resolvedDefResult: DefinitionResult? = null

    /** Structured glossaries + dict ids for the card's Definition field. */
    private var resolvedStyled: YomitanStyledData? = null

    /** The on-screen Definitions panel's styled renderer — ONE instance
     *  reused across [rebuildDefinitions] passes (a × tap must not churn
     *  WebViews), destroyed in [release]. */
    private var ankiStyledView: YomitanDefinitionsView? = null

    /** Render process died once — stay native for the editor's life. */
    private var ankiStyledBroken = false

    /**
     * Styled imported block for the ON-SCREEN Definitions panel (same
     * component as the lens/detail sheet). Returns false when the styled
     * path shouldn't/can't run — the caller renders the native rows.
     */
    private fun addStyledImportedPanel(card: LinearLayout): Boolean {
        val styledData = resolvedStyled ?: return false
        if (styledData.structured.isEmpty() || ankiStyledBroken) return false
        val groups = resolvedEntry?.importedSenses.orEmpty()
        if (groups.isEmpty()) return false
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
            val hPad = ctx.resources.getDimensionPixelSize(R.dimen.pt_row_h_padding)
            created.setPadding(hPad, dpi(8), hPad, dpi(8))
            created.onContentHeight = { h ->
                val lp = created.layoutParams
                    ?: LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
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
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
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
        (v * ctx.resources.displayMetrics.density).toInt()

    /** Sense ords the user has removed via the row × — they're skipped
     *  in both the rendered Definitions card and the Anki HTML payload. */
    private val removedSenses = mutableSetOf<Int>()
    /** (sense ord, example index) pairs removed via the per-example ×. */
    private val removedExamples = mutableSetOf<Pair<Int, Int>>()
    /** Tatoeba pairs removed via the More examples ×. */
    private val removedTatoebaIdx = mutableSetOf<Int>()
    /** Per-sense per-example translation cache — async ML-Kit results
     *  land here so re-renders after ×-driven removals keep them. */
    private val exampleTranslationCache = mutableMapOf<Pair<Int, Int>, String>()
    /** Tatoeba "More examples" pairs (set after [TatoebaClient.fetch]
     *  resolves). Null = not yet fetched / unsupported / fetch failed. */
    private var tatoebaPairs: List<TatoebaClient.SentencePair>? = null

    /** Number of sentence-translation fetches currently in flight — a
     *  counter (edits can overlap two coroutines). Drives the Save
     *  button's left "fields loading" indicator. */
    private var translationFillCount: Int = 0

    /** Number of word-lookup fetches currently in flight. */
    private var wordsFillCount: Int = 0

    /** Host-view teardown. [releasePin] is the host's finality decision for
     *  the screenshot pin (the same clause as the game-audio snapshot;
     *  see [WordAnkiReviewSheet]'s onDestroyView). The content view's own
     *  release runs with the same verdict. */
    fun release(releasePin: Boolean) {
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
        if (releasePin) {
            AnkiScreenshotPin.release(ctx, pinnedScreenshotPath)
        }
        pinnedScreenshotPath = null
        wordAudioHandle?.release()
        wordAudioHandle = null
        contentView?.release(deleteSnapshotFile = releasePin)
        contentView = null
    }

    /** The host is foregrounded again (picker round-trips) — forward to
     *  the sentence content's snapshot re-activation. */
    fun onHostResumed() {
        contentView?.onHostResumed()
    }

    /** Persist the content's launch-state bundle + game-audio cell for a
     *  saved-state destroy (fragment hosting; the workspace never calls). */
    fun saveState(outState: Bundle) {
        contentArgs?.let { outState.putBundle(STATE_CONTENT_ARGS, it) }
        contentView?.saveState(outState)
    }

    fun bind(root: View, savedInstanceState: Bundle?) {
        rootView = root
        val word           = args.getString(ARG_WORD) ?: return
        val reading        = args.getString(ARG_READING) ?: ""
        val pos            = args.getString(ARG_POS) ?: ""
        val fallbackDefinition = args.getString(ARG_DEFINITION) ?: ""
        val freqScore      = args.getInt(ARG_FREQ_SCORE, 0)
        val isCommon       = args.getBoolean(ARG_IS_COMMON, false)
        // Re-seed from args every bind. If the user removed the screenshot
        // in a previous instance we also cleared ARG_SCREENSHOT_PATH, so
        // this returns null on subsequent restorations and the photo stays
        // gone. Pinned immediately: the arg is a fixed cache filename a
        // capture during this editor's lifetime would overwrite. The pinned
        // path is written BACK into args so a saved-state recreation
        // re-reads the immutable pin and ADOPTS it rather than pinning a
        // copy — one owner, released on this instance's final teardown.
        val argScreenshotPath = args.getString(ARG_SCREENSHOT_PATH)
        pinnedScreenshotPath = if (AnkiScreenshotPin.isPin(ctx, argScreenshotPath)) {
            argScreenshotPath
        } else {
            AnkiScreenshotPin.pin(ctx, argScreenshotPath).also {
                args.putString(ARG_SCREENSHOT_PATH, it)
            }
        }
        currentScreenshotPath = pinnedScreenshotPath

        val sentenceOriginal    = args.getString(ARG_SENTENCE_ORIGINAL)
        val sentenceTranslation = args.getString(ARG_SENTENCE_TRANSLATION) ?: ""
        // The single shared gate every review-editor entry point funnels
        // through. A "sentence" that is just the headword adds nothing over
        // the word card, so treat it as no-sentence: word-only, no toggle.
        val meaningfulSentence  = sentenceOriginal?.takeUnless { sentenceIsJustTheWord(it, word) }
        val hasSentenceData     = meaningfulSentence != null

        val sourceLangId = SourceLangId.fromCode(args.getString(ARG_SOURCE_LANG)) ?: SourceLangId.JA

        // Curation sets always start empty — a rebuild is a new editor
        // instance (the old sheet's per-instance semantics).
        removedSenses.clear()
        removedExamples.clear()
        removedTatoebaIdx.clear()
        // Both modes possible → seed from the remembered preference (the
        // toggle below writes it back); word-only editors stay word and
        // never touch the preference.
        isSentenceMode = hasSentenceData &&
            Prefs(ctx).ankiPreferredCardMode == CardMode.SENTENCE

        titleView = root.findViewById(R.id.tvWordAnkiSheetTitle)
        toggleHost = root.findViewById(R.id.wordAnkiToolbarToggle)

        if (hasSentenceData) {
            titleView.isGone = true
            toggleHost.isVisible = true
            buildAnkiModeToggle(
                container = toggleHost,
                leftLabel = ctx.getString(R.string.anki_mode_sentence),
                rightLabel = ctx.getString(R.string.anki_mode_word),
                leftActive = isSentenceMode,
            ) { leftSelected ->
                setMode(sentenceMode = leftSelected)
                // An explicit flip is the user's new default — for this
                // editor's next open AND the one-tap long-press route.
                Prefs(ctx).ankiPreferredCardMode =
                    if (leftSelected) CardMode.SENTENCE else CardMode.WORD
            }
        }

        val deckHost = root.findViewById<LinearLayout>(R.id.wordAnkiDeckHost)
        sentenceContainer = root.findViewById(R.id.wordAnkiSentenceHost)
        wordContainer = root.findViewById(R.id.wordAnkiWordHost)
        sentenceContainer.visibility = if (isSentenceMode) View.VISIBLE else View.GONE
        wordContainer.visibility = if (isSentenceMode) View.GONE else View.VISIBLE

        deckSubtitleView = root.findViewById(R.id.tvWordAnkiSendSubtitle)
        addAnkiSection(
            ctx, scope,
            parent = deckHost,
            openDeckPicker = host::openDeckPicker,
            openCardTypePicker = host::openCardTypePicker,
            onDeckChanged = { refreshDeckSubtitle() },
            onCardTypeChanged = { /* no visible affordance reflects card type */ },
        )
        refreshDeckSubtitle()

        buildWordContent(wordContainer, word, reading, pos, fallbackDefinition,
            freqScore, isCommon, sourceLangId, currentScreenshotPath)

        if (hasSentenceData) {
            // The content's launch state: fresh at first bind, the persisted
            // bundle (carrying applied translation/words + the pinned
            // screenshot) on a saved-state recreation.
            val restoredArgs = savedInstanceState?.getBundle(STATE_CONTENT_ARGS)
            val cArgs = restoredArgs ?: run {
                val sentenceWords = buildWordEntries(args)
                SentenceAnkiContentView.buildArgs(
                    meaningfulSentence, sentenceTranslation, sentenceWords,
                    currentScreenshotPath, targetWord = word, sourceLangId = sourceLangId,
                    // wordsLoading = true tells the content "we'll call
                    // applyWords later" so the empty list renders as
                    // "Looking up words…" instead of zero rows.
                    wordsLoading = sentenceWords.isEmpty(),
                    audioAnchorMs = args.takeIf { it.containsKey(ARG_AUDIO_ANCHOR_MS) }
                        ?.getLong(ARG_AUDIO_ANCHOR_MS),
                )
            }
            contentArgs = cArgs
            val contentRoot = LayoutInflater.from(ctx)
                .inflate(R.layout.fragment_sentence_anki_content, sentenceContainer, false)
                    as LinearLayout
            val content = SentenceAnkiContentView(ctx, scope, cArgs, ContentHost())
            contentView = content
            sentenceContainer.addView(contentRoot)
            content.buildInto(contentRoot, savedInstanceState)
            content.onOriginalCommitted = { newOriginal ->
                // Edited text is NOT the deferred capture's text — never pass
                // the pending here (resolveAnkiTranslation's caller contract).
                launchTranslationFill(newOriginal)
                launchWordsFill(newOriginal, "")
            }

            // Fill in any missing pieces asynchronously (fresh binds only —
            // a restored bundle already carries whatever was applied).
            if (restoredArgs == null) {
                if (sentenceTranslation.isBlank()) {
                    // The launcher's deferred pending (if any) rides the args —
                    // the fill then COMPLETES the deferred capture (History rows
                    // fill too) instead of translating around it.
                    @Suppress("DEPRECATION")
                    launchTranslationFill(
                        meaningfulSentence,
                        args.getSerializable(ARG_SENTENCE_PENDING)
                            as? com.playtranslate.model.PendingTranslation,
                    )
                }
                if (args.getStringArray(ARG_SENTENCE_WORDS).isNullOrEmpty()) {
                    launchWordsFill(meaningfulSentence, word)
                }
            }
        }

        // Kick off the same dictionary lookup the Word Detail page does.
        // Once it lands we replace the loading placeholder in the
        // Definitions card with per-sense rows. The flat ARG_DEFINITION
        // string remains a fallback for failure / offline / miss paths.
        scope.launch {
            runDictionaryLookup(word, reading.takeIf { it.isNotBlank() }, sourceLangId)
        }

        val sendBtn = root.findViewById<FrameLayout>(R.id.btnWordAnkiSend)
        sendButton = AnkiSendButton(sendBtn)
        // launchTranslationFill / launchWordsFill above incremented
        // the in-flight counters before sendButton existed; apply them
        // now so the left indicator reflects current state.
        refreshFillingPendingIndicator()
        sendBtn.setOnClickListener {
            val deckId = Prefs(ctx).ankiDeckId
            if (deckId < 0L) {
                Toast.makeText(ctx, ctx.getString(R.string.anki_no_deck_selected), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendButton?.setLoading(true)
            scope.launch {
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

    /** The sentence content's side of this binder. */
    private inner class ContentHost : SentenceAnkiContentView.Host {
        override val isAlive: Boolean get() = host.isAlive

        override suspend fun awaitEnterSettled() = host.awaitEnterSettled()

        override fun openAudioPicker(intent: Intent, onPicked: (AudioSelection) -> Unit) =
            host.openAudioPicker(intent, onPicked)

        override fun onScreenshotRemoved() {
            // Keep the word tab in sync — the two tabs share the same
            // source media.
            removeWordScreenshotFromUi()
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
        val density = ctx.resources.displayMetrics.density

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
        // contour once the async lookup resolves the entry.
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
                    text = ctx.getString(R.string.word_detail_common)
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
            ctx, scope,
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
        //    BOTH orders — a card built AFTER the resolve must render
        //    immediately, or the placeholder stands forever.
        ankiGroupHeader(parent, ctx.getString(R.string.anki_group_definitions))
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
                ankiGroupHeader(parent, ctx.getString(R.string.anki_group_screenshot))
                val ssCard = ankiGroupCard(parent)
                screenshotHeaderView = parent.getChildAt(parent.childCount - 2)
                screenshotCardView = parent.getChildAt(parent.childCount - 1)
                addWordScreenshotRow(ssCard, file) {
                    removeWordScreenshotFromUi()
                    // Keep the sentence tab in sync — the directly-hosted
                    // content carries its own includePhoto flag and can leak
                    // the photo into the sentence card otherwise.
                    contentView?.removeScreenshotFromUi()
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
        val density = ctx.resources.displayMetrics.density
        return TextView(ctx).apply {
            text = fallback.ifBlank { ctx.getString(R.string.words_loading) }
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
     *  on the word card and by the sentence content's removal mirror.
     *  Idempotent. */
    private fun removeWordScreenshotFromUi() {
        if (currentScreenshotPath == null && screenshotHeaderView == null) return
        screenshotHeaderView?.let { wordContainer.removeView(it) }
        screenshotCardView?.let { wordContainer.removeView(it) }
        screenshotHeaderView = null
        screenshotCardView = null
        // Clear both the live field (Send reads from here) and the
        // persisted argument (so a recreate doesn't resurrect the photo).
        currentScreenshotPath = null
        args.remove(ARG_SCREENSHOT_PATH)
    }

    private fun addWordScreenshotRow(card: LinearLayout, file: File, onRemove: () -> Unit) {
        val density = ctx.resources.displayMetrics.density
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
        // Decoded off the main thread: bind runs synchronously inside the
        // workspace push, and a full-screen decode there stalls the window's
        // enter animation. Display-only — the card gets the file itself
        // (addMediaFromFile), never this bitmap.
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                decodeScreenshotForDisplay(file, ctx.resources.displayMetrics.widthPixels)
            }
            if (bmp != null) img.setImageBitmap(bmp)
        }
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
            contentDescription = ctx.getString(R.string.anki_screenshot_remove_content_description)
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
        val appCtx = ctx.applicationContext
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
        // Everything below touches the view tree (pitch line, definitions
        // rebuild, the styled WebView) — hold it out of the entrance window.
        host.awaitEnterSettled()
        val response = defResult?.response
        val entries = response?.entries.orEmpty()
        val entry = entries.firstOrNull()
        if (!host.isAlive || entry == null) {
            // Field-trace: this silent return leaves the loading
            // placeholder standing forever — the exact symptom must name
            // its cause in the log.
            android.util.Log.i(
                LOOKUP_TAG,
                "lookup DEAD-END: '$word' isAlive=${host.isAlive} entries=${entries.size} " +
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
            appCtx,
            (SourceLangId.fromCode(args.getString(ARG_SOURCE_LANG)) ?: SourceLangId.JA)
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
            scope.launch {
                val translated = runCatching {
                    withContext(Dispatchers.IO) { resolver.translateExamples(response!!) }
                }.getOrNull() ?: return@launch
                if (!host.isAlive) return@launch
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
        if (TatoebaClient.supports(moreExamplesSourceLang, moreExamplesTargetLang)) {
            ensureMoreExamplesPlaceholder()
            scope.launch {
                val lookupWord = entry.headwords.firstOrNull()?.written ?: entry.slug
                val pairs = TatoebaClient.fetch(
                    word = lookupWord,
                    sourceLang = moreExamplesSourceLang,
                    targetLang = moreExamplesTargetLang,
                )
                if (!host.isAlive) return@launch
                // When Tatoeba returns nothing (no results / network), fall
                // back to the entry's per-sense examples. Wiktionary stores
                // translations in English; for target≠en we ML-translate
                // them in parallel before showing.
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
     * removal sets and the latest cached example translations. Called both
     * on initial paint and after every × tap so the visible state stays
     * in sync.
     */
    private fun rebuildDefinitions() {
        val entry = resolvedEntry ?: return
        val flatSenses = resolvedFlatSenses
        val card = definitionsCard ?: return
        card.removeAllViews()

        // Imported term-dictionary definitions lead, mirroring the detail
        // sheet and what buildWordDefinitionHtml puts on the card. Not
        // curatable in v1 (visibleSiblingCount = 1 suppresses the ×).
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
            is DefinitionResult.MachineTranslated -> defResult.translatedDefinitions
            is DefinitionResult.EnglishFallback -> defResult.translatedDefinitions
            else -> null
        }

        // Target-driven render path mirrors the word-detail page — for
        // non-English targets with a Native pack hit, iterate the target
        // pack's senses directly. removedSenses keys on the target sense
        // index in this mode; a binder instance never switches modes
        // mid-session, so the two index spaces don't collide.
        val nativeTargetSenses = (defResult as? DefinitionResult.Native)
            ?.targetSenses
            ?.sortedBy { it.senseOrd }
            ?.takeIf { it.isNotEmpty() }
        val isTargetDriven = moreExamplesTargetLang != "en" && nativeTargetSenses != null

        if (isTargetDriven) {
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
                val visibleExamples = target.examples.withIndex()
                    .filter { (eIdx, _) -> (idx to eIdx) !in removedExamples }
                addAnkiSenseRow(
                    parent = card,
                    posLabels = posLabels,
                    glossList = target.glosses,
                    senseNumber = senseNumber,
                    miscText = ctx.renderMiscText(target.misc),
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
                miscText = ctx.renderMiscText(sense.misc),
                examples = visibleExamples,
                senseIndex = flatIdx,
                visibleSiblingCount = numVisibleSenses,
            )
            displayCount++
        }

        if (displayCount == 0 && !hasImportedRows) {
            // An imported-only word (senses=0, imported>0) HAS definitions —
            // appending the blank-fallback loading row here rendered a
            // literal "Looking up words…" under (or INSTEAD of) real content.
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
        val density = ctx.resources.displayMetrics.density
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
            // mode has no cache entry and relies on the stored target-pack
            // Example.translation; entry-driven mode uses the cache once
            // translateExamples lands.
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
        // Trailing × — only render it when there's more than one visible
        // sense, so removing the only sense doesn't strand the card with
        // no definition. The count is supplied by the caller because the
        // relevant universe differs per render mode.
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
        val density = ctx.resources.displayMetrics.density
        val group = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        ankiGroupHeader(
            group,
            ctx.getString(R.string.word_detail_more_examples),
            ctx.getString(R.string.word_detail_group_tatoeba),
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
            text = ctx.getString(R.string.word_detail_more_examples_loading)
            textSize = 13f
            setTextColor(ctx.themeColor(R.attr.ptTextMuted))
            setTypeface(null, Typeface.ITALIC)
        })
        card.addView(body)
        card.addView(buildTatoebaAttributionFooter())
        // Park the group just above the Screenshot group when one's
        // present; otherwise append to the bottom — the natural reading
        // order: Definitions → More examples → Screenshot.
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
        val density = ctx.resources.displayMetrics.density
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
                // NEW_TASK: the workspace host's context is no Activity.
                runCatching {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW, "https://tatoeba.org/".toUri())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
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
            text = ctx.getString(R.string.word_detail_tatoeba_attribution)
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
        val density = ctx.resources.displayMetrics.density
        body.removeAllViews()
        body.setPadding(0, 0, 0, 0)

        when {
            pairs == null -> {
                body.setPadding(
                    (16 * density).toInt(), (14 * density).toInt(),
                    (16 * density).toInt(), (14 * density).toInt(),
                )
                body.addView(TextView(ctx).apply {
                    text = ctx.getString(R.string.word_detail_more_examples_error)
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
        val density = ctx.resources.displayMetrics.density
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
        val deckName = Prefs(ctx).ankiDeckName.ifBlank { ctx.getString(R.string.anki_deck_row_empty) }
        sub.text = ctx.getString(R.string.anki_deck_label_format, deckName)
    }

    /** Fetch a sentence translation and push the result into the sentence
     *  content, via [resolveAnkiTranslation]: a deferred capture's
     *  [pending] runs the deferred completion (History rows fill,
     *  idempotently); everything else keeps
     *  [LastSentenceCache.awaitOrStartTranslation]'s coalescing. A null
     *  outcome surfaces the "Couldn't translate" placeholder. */
    private fun launchTranslationFill(
        sentence: String,
        pending: com.playtranslate.model.PendingTranslation? = null,
    ) {
        translationFillCount++
        refreshFillingPendingIndicator()
        scope.launch {
            try {
                val outcome = resolveAnkiTranslation(pending, sentence)
                contentView?.applyTranslation(sentence, outcome?.text)
            } finally {
                translationFillCount--
                refreshFillingPendingIndicator()
            }
        }
    }

    /** Fetch the sentence's per-word breakdown and push it into the
     *  sentence content. Joins any in-flight word-lookup started by the
     *  drag controller's prefetch. The payload's surfaces map is paired
     *  with its results at the point the deferred completed — reading
     *  [LastSentenceCache.surfaceForms] separately would race live mode
     *  rotating the cache to a different sentence. */
    private fun launchWordsFill(sentence: String, targetWord: String) {
        wordsFillCount++
        refreshFillingPendingIndicator()
        scope.launch {
            try {
                val payload = LastSentenceCache.awaitOrStartWordLookups(
                    ctx.applicationContext,
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
                contentView?.applyWords(sentence, entries, targetWord)
            } finally {
                wordsFillCount--
                refreshFillingPendingIndicator()
            }
        }
    }

    /** Drives the small left spinner on the Save button. Visible whenever
     *  either of the async sentence-fill jobs is still running. Safe
     *  post-teardown — [sendButton] is null and the call no-ops. */
    private fun refreshFillingPendingIndicator() {
        sendButton?.setFillingPending(translationFillCount > 0 || wordsFillCount > 0)
    }

    /** Flat entries from the launch args (which carry no enrichment).
     *  Deliberately NOT enriched from [LastSentenceCache] here: these are
     *  flattened by [SentenceAnkiContentView.buildArgs] anyway, and the
     *  content's build supplies surfaces/pitch/senses from a
     *  sentence-GATED cache read — the ungated read this used to do could
     *  misattribute a rotated cache's data to this sentence. */
    private fun buildWordEntries(args: Bundle): List<SentenceAnkiHtmlBuilder.WordEntry> {
        val wordArr    = args.getStringArray(ARG_SENTENCE_WORDS) ?: return emptyList()
        val readingArr = args.getStringArray(ARG_SENTENCE_READINGS) ?: emptyArray()
        val meaningArr = args.getStringArray(ARG_SENTENCE_MEANINGS) ?: emptyArray()
        val freqArr    = args.getIntArray(ARG_SENTENCE_FREQ_SCORES) ?: IntArray(0)
        return wordArr.mapIndexed { i, w ->
            SentenceAnkiHtmlBuilder.WordEntry(
                w,
                readingArr.getOrElse(i) { "" },
                meaningArr.getOrElse(i) { "" },
                freqArr.getOrElse(i) { 0 },
            )
        }
    }

    // ── Send: word mode ──────────────────────────────────────────────────

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
        // since the structured outputs ship with no CSS.
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
        val result = ctx.sendWordCard(input, deckId)
        // NeedsMapping presentation is the host's (the Fragment wrapper's
        // mapping dialog / the workspace's mapping page).
        if (result is AnkiSendResult.NeedsMapping) {
            host.openFieldMapping(result.model, CardMode.WORD)
        }
        val shortfallRes = (result as? AnkiSendResult.Success)?.mediaShortfallRes()
        applyAnkiSendResult(
            ctx, result,
            presentAlert = host::presentAlert,
            onSuccess = {
                shortfallRes?.let {
                    Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show()
                }
                host.dismiss()
            },
            onRestore = { sendButton?.setLoading(false) },
        )
    }

    /**
     * Builds the Tatoeba example-sentences HTML for the structured-path
     * send (EXAMPLE_SENTENCES ContentSource). Omits the "More examples"
     * section header that [appendMoreExamplesHtml] emits — the receiving
     * field's template already carries its own label. Honors
     * [removedTatoebaIdx] so user curation is reflected in the card.
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
     * word-card send (DEFINITION ContentSource). Mirrors the `classStyler`
     * branch in [sendWordToAnki]'s `defaultDefinitionHtml` builder but
     * emits inline styles via [inlineStyler], and keeps the "More examples"
     * block inline. Honors the same curation state so what the user sees
     * on the editor is what lands on the card.
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
        val fallback = args.getString(ARG_DEFINITION) ?: ""
        val entry = resolvedEntry
            ?: return WordAnkiHtmlBuilder.wrapFlatDefinitionHtml(
                fallback, styler, ctx.getString(R.string.anki_group_definitions))
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
            htmlEscape(ctx.getString(R.string.anki_group_definitions)) +
            "</div><div ${styler("gl-panel", "")}>" + body + "</div>"
    }

    private fun StringBuilder.appendSensesHtml(
        entry: DictionaryEntry, fallback: String, styler: HtmlStyler,
    ) {
        // Sense rows are flex: a right-aligned number column beside the
        // sense body (the lens's buildDefinitionRow, in HTML). One counter
        // across every branch keeps numbering continuous (imported rows
        // first, like the lens) and gives the structured path its
        // border-top:0 on row 0.
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
        val hasImportedRows = entry.importedSenses.any { it.senses.isNotEmpty() }
        entry.importedSenses.forEach { group ->
            group.senses.forEachIndexed { defIdx, sense ->
                openSense()
                // Structured glossary when the sense retained one and the
                // resolve-time fetch delivered it; flat text otherwise.
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
                ctx.renderMiscText(target.misc)?.let { misc ->
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
            ctx.renderMiscText(sense.misc)?.let { misc ->
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
        append(ctx.getString(R.string.word_detail_more_examples))
        append("</div>")
        // .gl-rows: hairline-separated rows, no panel fill — this section is
        // reference, not answer. Row 0 suppresses its hairline inline on
        // both paths (no :first-child equivalent on the structured path).
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

    // ── Send: sentence mode ──────────────────────────────────────────────

    private suspend fun sendSentenceToAnki(deckId: Long) {
        val content = contentView ?: run { sendButton?.setLoading(false); return }
        // Untrimmed game audio resolves here (the review nudge fires once);
        // false = the nudge held this Save — abort the send.
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
            // The word editor's sentence tab carries Tatoeba "more examples"
            // for the structured path. Built with inlineStyler since the
            // structured outputs have no surrounding <style> block.
            examplesHtml = buildExamplesHtml(inlineStyler),
        )
        val result = ctx.sendSentenceCard(input, deckId)
        if (result is AnkiSendResult.NeedsMapping) {
            host.openFieldMapping(result.model, CardMode.SENTENCE)
        }
        val shortfallRes = (result as? AnkiSendResult.Success)?.mediaShortfallRes()
        applyAnkiSendResult(
            ctx, result,
            presentAlert = host::presentAlert,
            onSuccess = {
                shortfallRes?.let {
                    Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show()
                }
                host.onSentenceCardAdded()
                host.dismiss()
            },
            onRestore = { sendButton?.setLoading(false) },
        )
    }

    companion object {
        /** Field-trace tag for the definitions resolve — the loading
         *  placeholder's replacement rides this coroutine, and every way
         *  it can fail is otherwise silent. */
        private const val LOOKUP_TAG = "WordSheetLookup"

        private const val STATE_CONTENT_ARGS = "word_anki_content_args"

        internal const val ARG_WORD            = "word"
        internal const val ARG_READING         = "reading"
        internal const val ARG_POS             = "pos"
        internal const val ARG_DEFINITION      = "definition"
        internal const val ARG_SCREENSHOT_PATH = "screenshot_path"
        internal const val ARG_FREQ_SCORE      = "freq_score"
        internal const val ARG_IS_COMMON       = "is_common"
        internal const val ARG_SENTENCE_ORIGINAL     = "sentence_original"
        internal const val ARG_SENTENCE_TRANSLATION  = "sentence_translation"
        internal const val ARG_SENTENCE_PENDING      = "sentence_pending"
        internal const val ARG_SENTENCE_WORDS        = "sentence_words"
        internal const val ARG_SENTENCE_READINGS     = "sentence_readings"
        internal const val ARG_SENTENCE_MEANINGS     = "sentence_meanings"
        internal const val ARG_SENTENCE_FREQ_SCORES  = "sentence_freq_scores"
        internal const val ARG_SOURCE_LANG     = "source_lang"
        internal const val ARG_AUDIO_ANCHOR_MS = "audio_anchor_ms"

        /** The launch-state bundle both hosts build (the old
         *  [WordAnkiReviewSheet.newInstance] shape). */
        fun buildArgs(
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
        ): Bundle = Bundle().apply {
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
            // Meaningful only alongside a sentence (the game-audio cell lives
            // in the sentence content), but stored unconditionally — the read
            // side gates on the content's presence.
            if (audioAnchorMs != null) putLong(ARG_AUDIO_ANCHOR_MS, audioAnchorMs)
        }
    }
}

/** Decode [file] for an in-editor screenshot preview, sampled down
 *  (power-of-2) toward [targetW] instead of always inflating the full file.
 *  Conservative: the decoded width never drops below [targetW], so a
 *  same-display screenshot resolves to inSampleSize 1 and the cap only bites
 *  when the source is at least twice the width it will be displayed at. Call
 *  off the main thread; the bounds pass alone is a header parse. */
internal fun decodeScreenshotForDisplay(file: File, targetW: Int): android.graphics.Bitmap? {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    if (bounds.outWidth > 0 && targetW > 0) {
        while (bounds.outWidth / (sample * 2) >= targetW) sample *= 2
    }
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    return android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
}
