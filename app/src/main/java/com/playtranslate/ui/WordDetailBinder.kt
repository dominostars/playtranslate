package com.playtranslate.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.net.toUri
import androidx.core.view.isGone
import androidx.core.view.isNotEmpty
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import com.playtranslate.AnkiManager
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.ask.AskSession
import com.playtranslate.ask.AskState
import com.playtranslate.ask.Explanation
import com.playtranslate.ask.WordInSentence
import com.playtranslate.ask.explanation
import com.playtranslate.audio.AudioRequest
import com.playtranslate.audio.PlayOutcome
import com.playtranslate.audio.PronunciationPlayer
import com.playtranslate.language.DefinitionResolver
import com.playtranslate.language.DefinitionResult
import com.playtranslate.language.InflectedForm
import com.playtranslate.language.InflectionTag
import com.playtranslate.language.inflection.ConjugationTable
import com.playtranslate.language.inflection.TableKind
import com.playtranslate.language.inflection.InflectionParadigms
import com.playtranslate.language.LanguagePackCatalogLoader
import com.playtranslate.language.OfflineFallbackTranslators
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.TatoebaClient
import com.playtranslate.language.TargetGlossDatabaseProvider
import com.playtranslate.language.TokenSpan
import com.playtranslate.language.memberWordsFor
import com.playtranslate.language.WordTranslator
import com.playtranslate.language.dedupeMtCsv
import com.playtranslate.model.CharacterDetail
import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.HanziDetail
import com.playtranslate.model.HeadwordDisplay
import com.playtranslate.model.KanjiDetail
import com.playtranslate.model.headwordDisplay
import com.playtranslate.model.headwordFor
import com.playtranslate.model.orderedReadingRows
import com.playtranslate.model.preferDisplayable
import com.playtranslate.model.selectHeadword
import com.playtranslate.model.unambiguousFallbackPos
import com.playtranslate.themeColor
import com.playtranslate.translation.ChineseScriptConverter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The full word-detail page (headword + readings + badges, the
 * definitions, Tatoeba examples, kanji/hanzi breakdown, member words, the
 * Anki pill with its one-tap long-press) as a host-agnostic binder —
 * extracted from [WordDetailBottomSheet] so the SAME page renders inside the
 * in-app DialogFragment AND inside the floating [OverlayWorkspace] over the
 * game. The layout is shared too ([R.layout.bottom_sheet_word_detail]);
 * hosts own only their chrome (dialog window / toolbar vs workspace header)
 * and the [Ui] seam below.
 *
 * [scope] is the host's view/page lifetime: the fragment passes its
 * viewLifecycleOwner scope, the workspace page its own page scope — either
 * way [release] must run when the view leaves (it lets the definitions
 * pages go and stops the player), and the scope must be cancelled by the host.
 */
class WordDetailBinder(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val ui: Ui,
) {
    /** Launch-time inputs — the fragment's arguments bundle, as a value. */
    data class Args(
        val word: String,
        val reading: String?,
        /** The text the opener found [word] as (飲んでいなかった for 飲む):
         *  re-tokenized for the header's conjugation line. Null when the
         *  opener has only the lemma. */
        val surface: String?,
        val screenshotPath: String?,
        val sentenceOriginal: String? = null,
        val sentenceTranslation: String? = null,
        val sentenceWordResults: Map<String, Triple<String, String, Int>>? = null,
        val sentencePending: com.playtranslate.model.PendingTranslation? = null,
        /** Toolbar-less hosting (the embedded Sentence/Word tab, the
         *  workspace page): the headword scrolls with the content instead of
         *  shrinking into a toolbar slot the host doesn't render. */
        val embedded: Boolean = false,
        /** The key the page resolves its entry with: the dictionary form the
         *  opener resolved the entry under, as opposed to [word], the display
         *  that seeds the header and the Anki card. The two differ where the
         *  opener displayed something other than its key, such as the kana
         *  of a usually-kana entry (あずかる for 与る), whose kana lookup can
         *  rank another entry (預かる) first. Defaults to [word] for an
         *  opener with no key of its own. */
        val lookupForm: String = word,
        /** The reading that narrowed [lookupForm]'s lookup, null when none;
         *  for the lookup only. For a conjugated occurrence it is the
         *  surface's reading (こだわっ for 拘って), which names no reading row
         *  of the entry: the header's bold row and the Anki and one-tap
         *  headwords follow [reading], what the opener showed. Defaults to
         *  [reading] for an opener with no key of its own. */
        val lookupReading: String? = reading,
        /** What the opener's explain question about [word] in
         *  [sentenceOriginal] brought, where the page was opened from a row
         *  with that answer standing in it ([WordRowList.explanationOf]);
         *  null with none. One of the launch sentence's fields, like the
         *  four above: it reaches the page through [pageSentence], under
         *  the page's own word ([target]), and so only while the page's
         *  sentence is still the one it was asked about; there the "In
         *  this sentence" group shows it and a card's note starts with its
         *  text. An opener that hands the page a context of its own puts
         *  the answer there instead ([SentenceContext.explanations]). */
        val explanation: Explanation? = null,
    ) {
        /** The page's own word as a card names it ([pageCardTarget]). */
        val target: CardTarget get() = pageCardTarget(surface, lookupForm, lookupReading)
    }

    /** Everything the editable word review needs — the host decides HOW to
     *  present it (child DialogFragment, or the Anki activity trampoline
     *  until the workspace grows its own editor page). [target] is [word] as
     *  it was found in the sentence, which the review's sentence card places
     *  among the sentence's words ([withCardTarget]). [notes] is the text
     *  the review's Notes boxes start with ([putAnkiNotes]): the text of
     *  what the page's sentence holds about [target]
     *  ([SentenceContext.explanations]), "" with nothing. Made by
     *  [wordAnkiArgs], which reads the sentence and the note together. */
    data class WordAnkiArgs(
        val word: String,
        val reading: String,
        val pos: String,
        val definition: String,
        val screenshotPath: String?,
        val freqScore: Int,
        val isCommon: Boolean,
        val sentenceOriginal: String?,
        val sentenceTranslation: String?,
        val sentenceWordResults: Map<String, Triple<String, String, Int>>?,
        val sourceLangId: SourceLangId,
        val sentencePending: com.playtranslate.model.PendingTranslation?,
        val target: CardTarget,
        val notes: String,
    )

    interface Ui {
        /** The host view tree is still live (the fragment's isAdded / the
         *  workspace page not yet destroyed) — guards every async re-entry. */
        val isAlive: Boolean

        /** Hold point before the lookup's completion bind: a host with an
         *  entrance transition (the workspace) suspends here until it has
         *  settled, so the content build, and the definitions page it
         *  loads, can't land its main-thread cost inside
         *  the entrance and drop its frames. Default: no hold. Callers
         *  re-check [isAlive] after resuming. */
        suspend fun awaitEnterSettled() {}

        /** Live sentence context at tap time (the embedded host activity's
         *  [SentenceContextProvider], or the workspace caller's snapshot);
         *  null when the host has none — [Args]' sentence fields then apply. */
        fun sentenceContext(): SentenceContext?

        /** A Words or Other matches cell's tap: open that word's own detail
         *  (nested sheet on childFragmentManager, or a workspace push).
         *  [surface] is [Args.surface] for the opened page, [lookupForm] and
         *  [lookupReading] its [Args.lookupForm] and [Args.lookupReading].
         *  The word is found inside this page's word, so inside the same
         *  sentence: the opened page carries this page's sentence and
         *  screenshot for its Anki card, from the host's own launch context
         *  (its arguments, or the workspace page's provider and anchor). */
        fun openWordDetail(
            word: String,
            reading: String?,
            surface: String?,
            lookupForm: String,
            lookupReading: String?,
        )

        /** Open the editable word review for [args]. The host owns the
         *  AnkiDroid-permission gate (rationale + request on an Activity;
         *  the trampoline handles it on the workspace path). */
        fun openAnkiReview(args: WordAnkiArgs)

        fun showAnkiNotInstalled()

        /** One-tap landed on NeedsMapping: the fragment host opens the
         *  field-mapping dialog; the workspace host falls back to the
         *  editable review ([fallback]) — the lens's NeedsMapping parity. */
        fun openFieldMapping(result: AnkiSendResult.NeedsMapping, mode: CardMode, fallback: WordAnkiArgs)

        /** Terminal for binder-built alerts (send-failed). */
        fun present(builder: OverlayAlert.Builder)

        /** The "In this sentence" group's row was tapped with no AI model
         *  to put the question to: show the alert that says so
         *  ([askNeedsLlm]) on this surface, with the way to Translation
         *  services from it. */
        fun onAskNeedsLlm()

        /** A control of the page took itself off the screen as it was
         *  pressed (the "In this sentence" group's, [WordExplainGroup]): a
         *  host that draws a controller's cursor of its own moves it to
         *  [next], the control standing in its place. Default: nothing to
         *  do, for a host whose window keeps Android's focus, which the
         *  control hands on itself. */
        fun onControlGone(next: View) {}

        /** Run [send] detached from this surface (the card must land even if
         *  the page is dismissed mid-send) and present the result — the
         *  fragment routes through [launchOneTapSend]'s structural rules;
         *  the workspace presents while alive, else degrades to the
         *  app-context toast ([oneTapResultToast]). */
        fun launchOneTap(
            send: suspend () -> Pair<AnkiSendResult, CardMode>,
            presentResult: (Pair<AnkiSendResult, CardMode>) -> Unit,
        )
    }

    companion object {
        private const val TAG = "WordDetailBinder"
        /** Scroll distance over which the overlay headword scales from
         *  full size to [TOOLBAR_SCALE]. By 40dp of scroll it's fully
         *  shrunk into the toolbar slot. */
        private const val COLLAPSE_DISTANCE_DP = 40
        /** Scale at the pinned end of the animation: 18sp / 38sp. */
        private const val TOOLBAR_SCALE = 0.47f

        /** The imported block's body text in the Definitions card, in sp;
         *  the sense rows carry their own sizes. */
        private const val DEFINITIONS_BASE_PX = 16.5f
        private const val IMPORTED_ROW_ID = "imported"

        /**
         * The sentence every Anki action on the page rides: [host], the
         * host's live context (the embedded host activity's provider, the
         * workspace caller's snapshot), field by field over [args]' launch
         * fields, except the pending, which rides only beside the original
         * of the source that supplied it, never crossed between them
         * ([resolveAnkiTranslation]'s caller contract). Surfaces and
         * enrichment are left out: they pair only with the words of the
         * host's own atomic snapshot, which the one-tap reads from [host].
         *
         * The words' explanations are both sources', each side's only when
         * the sentence the page ends up with is the one that side's were
         * asked about ([explanationsAbout]): [host]'s, and the answer the
         * page was opened with ([Args.explanation], about the page's own
         * word in [Args.sentenceOriginal]). Once the page has its "In this
         * sentence" group, whose session is [asked], the page's own word
         * ([Args.target]) is that session's alone: under it stands what the
         * session holds now, its answer while the sentence is the one it
         * was asked about and nothing otherwise, so an answer asked on the
         * page is the card's note and one put away is no note, whatever the
         * two sources still carry. Every other word's stands as they give
         * it. A card reads its note from here and nowhere else, so it never
         * has one sentence and an answer about another.
         */
        internal fun pageSentence(host: SentenceContext?, args: Args?, asked: AskSession? = null): SentenceContext {
            val original = host?.original ?: args?.sentenceOriginal
            val launch = args?.let {
                SentenceContext(it.sentenceOriginal, null, null).explaining(it.target, it.explanation)
            }
            val carried = host?.explanationsAbout(original).orEmpty() +
                launch?.explanationsAbout(original).orEmpty()
            val sentence = SentenceContext(
                original = original,
                translation = host?.translation ?: args?.sentenceTranslation,
                wordResults = host?.wordResults ?: args?.sentenceWordResults,
                pending = if (host != null) host.pending else args?.sentencePending,
                explanations = carried,
            )
            val own = args?.target ?: return sentence
            if (asked == null) return sentence
            return sentence.copy(explanations = carried - own).explaining(own, asked.answerAbout(original))
        }

        /** The finished answer this session holds while [sentence] is the
         *  text it asked about; null with none, or about another text. */
        private fun AskSession.answerAbout(sentence: String?): Explanation? =
            (state.value as? AskState.Answered)?.takeIf { this.sentence?.text == sentence }?.explanation

        /**
         * The editable review of one word on [sentence], [target] being
         * the word as it was found there. The card's sentence and its note
         * are read from the one context, the note being what that context
         * holds about [target] ([SentenceContext.explanations]), so no
         * card is made with one sentence and a note about another. Both
         * reviews a page opens are made here: its own word's and a cell's
         * ([rowAnkiArgs]).
         */
        internal fun wordAnkiArgs(
            sentence: SentenceContext,
            target: CardTarget,
            word: String,
            reading: String,
            pos: String,
            definition: String,
            screenshotPath: String?,
            freqScore: Int,
            isCommon: Boolean,
            sourceLangId: SourceLangId,
        ): WordAnkiArgs = WordAnkiArgs(
            word = word,
            reading = reading,
            pos = pos,
            definition = definition,
            screenshotPath = screenshotPath,
            freqScore = freqScore,
            isCommon = isCommon,
            sentenceOriginal = sentence.original,
            sentenceTranslation = sentence.translation,
            sentenceWordResults = sentence.wordResults,
            sourceLangId = sourceLangId,
            sentencePending = sentence.pending,
            target = target,
            notes = sentence.explanations[target]?.text.orEmpty(),
        )

        /**
         * The editable review for [row], a cell of the page's Words or Other
         * matches section: the row's own fields on the page's [sentence] and
         * [screenshotPath], since the row is a word found inside the page's
         * word, so inside the same sentence. The row as it was found is the
         * card's [CardTarget], so its sentence card is for this word even
         * where the sentence's analysis chose another there (弾く in 弾けた).
         * Its note is what [sentence] holds about that word, which is
         * nothing where only the page's own word was explained.
         */
        internal fun rowAnkiArgs(
            row: RowState,
            sentence: SentenceContext,
            screenshotPath: String?,
            sourceLangId: SourceLangId,
        ): WordAnkiArgs = wordAnkiArgs(
            sentence,
            target = CardTarget(row.surface, row.lookupForm, row.lookupReading),
            word = row.displayWord,
            reading = row.reading.takeIf { it.isNotEmpty() && it != row.displayWord } ?: "",
            pos = row.ankiPos,
            definition = row.meaning,
            screenshotPath = screenshotPath,
            freqScore = row.freqScore,
            isCommon = row.isCommon,
            sourceLangId = sourceLangId,
        )
    }

    private var args: Args? = null

    private var moreExamplesSourceLang: String = ""
    private var moreExamplesTargetLang: String = ""

    private var moreExamplesGroup: LinearLayout? = null
    private var moreExamplesBody: LinearLayout? = null

    /** Overlay headword that lives above the toolbar in z-order; the
     *  scroll listener drives its scale so it shrinks into the toolbar's
     *  empty left slot as the user scrolls (standalone-dialog mode only). */
    private var bigHeadwordView: TextView? = null

    /** Header badge row + its headword, retained so the deck badge can be
     *  re-queried when a card is added (via [AnkiManager.noteAddedTick]). */
    private var headerBadgeFlow: FlowLayout? = null
    private var headerWord: String? = null

    /** The reading the opener showed ([Args.reading], e.g. 明日 → あす):
     *  bolds the matching reading row and picks the Anki and one-tap
     *  headwords inside the entry the key resolved. Never the key's reading
     *  ([Args.lookupReading]), which for a conjugated occurrence is the
     *  surface's and matches no row; null on a cold lookup or a kana-only
     *  display. */
    private var readingHint: String? = null
    private val deckPillTag = "anki_deck_pill"

    /** In-flight TTS request from the header speak chip — cancelled when the
     *  view is torn down so a tapped pronunciation doesn't outlive the page. */
    private var speakJob: Job? = null

    /** The Definitions card: one document (the imported dictionaries' block,
     *  then a row per sense) and what a late example translation needs to
     *  patch its sense's row. Released with the page, and by a rebuild. */
    private var definitions: DefinitionsBlock? = null

    private class DefinitionsBlock(
        val document: DefinitionDocument,
        var rows: List<DefRow>,
        val language: String,
        /** A sense's index across the lookup's entries (the order
         *  [DefinitionResolver.translateExamples] answers in) to its row. */
        val rowOfSense: Map<Int, Int>,
    )

    /** The Words and Other matches sections' lists, each one definitions
     *  document: released with the page, and by [loadSecondarySections]
     *  before it builds those sections. */
    private val sectionLists = mutableListOf<WordRowList<RowState>>()

    /** The "In this sentence" group, while the page has a sentence
     *  ([addExplainGroup]); null on a page without one. Released with the
     *  page. */
    private var explain: WordExplainGroup? = null

    /** Host teardown: cancel audio, let the pages go. Idempotent; the
     *  host must also cancel [scope]. */
    fun release() {
        speakJob?.cancel()
        PronunciationPlayer.stop()
        moreExamplesGroup = null
        moreExamplesBody = null
        bigHeadwordView = null
        headerBadgeFlow = null
        releaseDefinitions()
        releaseSectionLists()
        explain?.release()
        explain = null
    }

    /** Lets the Definitions card's page go. */
    private fun releaseDefinitions() {
        definitions?.document?.release()
        definitions = null
    }

    /**
     * Example translations that arrived after the page was drawn, one list
     * per sense: each goes onto its example, and the document redraws only
     * the senses that gained one.
     */
    private fun applyExampleTranslations(translated: List<List<String>>) {
        val block = definitions ?: return
        val rows = block.rows.toMutableList()
        translated.forEachIndexed { senseIdx, perSense ->
            val at = block.rowOfSense[senseIdx] ?: return@forEachIndexed
            val row = rows[at] as? DefRow.Sense ?: return@forEachIndexed
            rows[at] = row.copy(
                examples = row.examples.mapIndexed { i, ex ->
                    val tr = perSense.getOrNull(i).orEmpty()
                    if (tr.isBlank()) ex else ex.copy(translation = tr)
                },
            )
        }
        block.rows = rows
        block.document.setRows(rows, block.language)
    }

    /** Let the word sections' pages go. */
    private fun releaseSectionLists() {
        sectionLists.forEach { it.release() }
        sectionLists.clear()
    }

    /** Build the page into [root] (the inflated
     *  [R.layout.bottom_sheet_word_detail]; the host has already decided the
     *  toolbar's visibility). */
    fun bind(root: View, args: Args) {
        this.args = args
        // [word] is what the page shows and saves (the header seed, the Anki
        // card's word); [lookupForm] is what it resolves and selects with.
        val word = args.word
        val lookupForm = args.lookupForm
        readingHint = args.reading
        val screenshotPath = args.screenshotPath
        val embedded = args.embedded

        // Keep the deck badge live if a card is added from within this page
        // (the review surface sits on top, but this page stays alive).
        scope.launch {
            AnkiManager.noteAddedTick.drop(1).collect {
                val flow = headerBadgeFlow ?: return@collect
                val w = headerWord ?: return@collect
                if (ui.isAlive) maybeAddAnkiDeckBadge(flow, w)
            }
        }

        val content = root.findViewById<LinearLayout>(R.id.detailContent)
        val scrollView = root.findViewById<NestedScrollView>(R.id.detailScrollView)
        // FrameLayout so PillAnkiButton can overlay a centered spinner
        // during one-tap sends.
        val btnAddAnki = root.findViewById<FrameLayout>(R.id.btnWordAddToAnki)
        // The card-stack glyph's app:tint is AppCompat-only — the workspace's
        // plain inflater drops it, leaving the icon unthemed. Tint in code
        // for both hosts.
        ((btnAddAnki.getChildAt(0) as? ViewGroup)?.getChildAt(0) as? ImageView)
            ?.imageTintList = ColorStateList.valueOf(ctx.themeColor(R.attr.ptAccentOn))
        val tvHeadword = root.findViewById<TextView>(R.id.tvDetailHeadword)
        // The detailContent paddingTop math below reserves the toolbar's
        // 56dp slot so the headword overlay can shrink into it on scroll.
        // When embedded, that slot doesn't exist — track 0dp instead so
        // the first row sits the expected 8dp under the headword.
        val toolbarSlotPx = if (embedded) 0 else dp(56)

        val prefs = Prefs(ctx.applicationContext)
        val sourceLangId = prefs.sourceLangId
        val targetLangCode = prefs.targetLang

        // Configure the overlay headword up front so the typeface and
        // pivot are right before the first measure. The text starts as
        // the queried [word] so something is on screen during the
        // (brief) async dictionary lookup; addHeaderBlock overwrites it
        // with the resolved canonical headword once the entry is back.
        bigHeadwordView = tvHeadword
        val headwordFace = if (sourceLangId == SourceLangId.JA)
            Typeface.SERIF
        else
            Typeface.create("sans-serif-medium", Typeface.NORMAL)
        tvHeadword.setTypeface(headwordFace, Typeface.BOLD)
        tvHeadword.text = word
        // Top-left pivot keeps the visible text anchored to the same
        // (x, y) point under translation while the rest of the glyph
        // shrinks toward it — matches where the toolbar's title slot
        // would natively sit.
        tvHeadword.pivotX = 0f
        tvHeadword.pivotY = 0f

        if (embedded) {
            // Toolbar-less host: no shrink-to-toolbar effect. Move the
            // headword out of the FrameLayout overlay and into the scroll
            // content so it scrolls naturally with the page. 4dp start
            // margin matches the overlay's 18dp inset minus the content's
            // 14dp horizontal padding.
            (tvHeadword.parent as? ViewGroup)?.removeView(tvHeadword)
            tvHeadword.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = dp(4)
                topMargin = dp(12)
                bottomMargin = dp(2)
            }
            content.addView(tvHeadword, 0)
        } else {
            // Reserve detailContent paddingTop equal to the overlay's
            // measured height + 2dp gap, so the reading/badges/definitions
            // sit just below the headword regardless of whether it wraps to
            // multiple lines. setText triggers requestLayout which fires
            // this listener, so the padding tracks text changes.
            tvHeadword.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                val target = (tvHeadword.bottom - toolbarSlotPx) + dp(2)
                if (content.paddingTop != target && target > 0) {
                    content.setPadding(
                        content.paddingStart,
                        target,
                        content.paddingEnd,
                        content.paddingBottom,
                    )
                }
            }

            scrollView.setOnScrollChangeListener(
                NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->
                    updateHeadwordCollapse(scrollY)
                }
            )
        }

        // Spinner shown in the body while the dictionary lookup resolves;
        // removed once the entry is back (whether found or not).
        val loadingView = ProgressBar(ctx).apply {
            isIndeterminate = true
            indeterminateTintList =
                ColorStateList.valueOf(ctx.themeColor(R.attr.ptAccent))
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(24)
            }
        }
        content.addView(loadingView)

        scope.launch {
            val appCtx = ctx.applicationContext
            val engine = com.playtranslate.language.SourceLanguageEngines.get(appCtx, sourceLangId)
            moreExamplesSourceLang = sourceLangId.code
            moreExamplesTargetLang = targetLangCode
            val targetGlossDb = TargetGlossDatabaseProvider.get(appCtx, targetLangCode)
            val enToTargetWrapper = OfflineFallbackTranslators.forTarget(targetLangCode)
            val charConverter =
                ChineseScriptConverter.forTarget(targetLangCode, Prefs(appCtx).targetChineseVariant)
            val resolver = DefinitionResolver(engine, targetGlossDb,
                OfflineFallbackTranslators.forPair(engine.profile.translationCode, targetLangCode), targetLangCode,
                enToTargetWrapper, charConverter)
            val defResult = withContext(Dispatchers.IO) { resolver.lookup(lookupForm, args.lookupReading) }
            val response = defResult?.response
            // The header's conjugation line: the lookup's own deinflection
            // chain, then the chain from the key to the surface the opener
            // found it as ([detailSurfaceTags]). A surface equal to the key
            // adds no chain of its own.
            val surface = args.surface
            val tokenTags = surface?.takeIf { it != lookupForm }?.let { s ->
                detailSurfaceTags(withContext(Dispatchers.IO) { engine.tokenize(s) }, s, lookupForm) { span ->
                    engine.alternativesOf(span, primaryIds = emptySet())
                }
            }.orEmpty()
            // Wiktionary-derived source packs (en/de/fr/es/...) split each
            // POS section into its own entry, so a lookup of "surprise"
            // returns three entries: noun, verb, intj. Render them all back
            // to back so the user sees every sense; the per-cell POS label
            // makes the boundaries visible without a manual divider.
            // [primary] is the first entry — used for the header block,
            // Anki, and character breakdown which are word-level (and the
            // headwords are duplicated across entries anyway).
            val entries = response?.entries.orEmpty()
            val primary = entries.firstOrNull()
            val inflectedForms = InflectionChain.lines(
                surface ?: lookupForm, response?.deinflection.orEmpty(), tokenTags,
                InflectionParadigms.readingsOf(sourceLangId, entries, primary?.slug),
            )
            // The conjugation cards: each entry's whole table from the pack
            // ([SourceLanguageEngine.inflectionTable]), laid out by the
            // language's paradigm with the form the user arrived from lit.
            // One per table-bearing entry: the page already renders every
            // entry's senses (fue lists ser and ir), so the ser page carries
            // ir's grid below ser's, labelled by lemma.
            val tables: List<Pair<DictionaryEntry, ConjugationTable>> = withContext(Dispatchers.IO) {
                val paradigm = InflectionParadigms[sourceLangId]
                if (paradigm == null) emptyList() else entries.mapNotNull { e ->
                    engine.inflectionTable(e)
                        ?.let { paradigm.table(e.slug, it, highlight = surface ?: lookupForm) }
                        ?.let { e to it }
                }
            }
            // Hold the whole completion bind until the host's entrance
            // settles: the Definitions card loads its page (the first one of
            // a process blocks the main thread), and that must not land in
            // the entrance. One reveal for the primary content and the Anki
            // button; the Words and Other matches sections follow it
            // ([loadSecondarySections]) behind a loading line.
            ui.awaitEnterSettled()
            if (!ui.isAlive) return@launch
            content.removeView(loadingView)
            if (primary == null) {
                addNotFoundNotice(content, ctx.getString(R.string.word_detail_no_definitions))
                // The page still descends, as the lens does under its own
                // placeholder: a fused unit no dictionary lists offers its
                // member words and the other entries it could be
                // ([loadSecondarySections] with no primary), so the parts
                // of an unlisted phrase stay one tap away.
                val wordSpan = TokenSpan(surface ?: lookupForm, lookupForm, args.lookupReading, tokenTags)
                val display = HeadwordDisplay(
                    word, (args.lookupReading ?: readingHint)?.takeIf { it.isNotBlank() && it != word },
                )
                // A word no dictionary lists can still be explained in its
                // sentence: the group goes under the notice, above what the
                // page descends to.
                addExplainGroup(content, display.written, display.reading)
                launch {
                    loadSecondarySections(
                        addSecondaryHost(content), primary = null, entries, wordSpan, engine, sourceLangId,
                        targetLangCode, display,
                    )
                }
                return@launch
            }
            val initialTranslations: List<List<String>>? = if (targetLangCode == "en") {
                entries.flatMap { it.senses }.map { s -> s.examples.map { it.translation } }
            } else null
            // The word as found, under its key, for its other matches: the
            // same chain the header draws from it.
            val wordSpan = TokenSpan(surface ?: lookupForm, lookupForm, args.lookupReading, tokenTags)
            val display = pageHeadwordDisplay(primary, word, lookupForm)
            val secondaryHost = buildContent(
                content, entries, engine, sourceLangId, defResult, initialTranslations,
                targetLangCode, enToTargetWrapper, display, inflectedForms, tables, lookupForm,
            )
            scrollView?.scrollTo(0, 0)

            val ankiManager = AnkiManager(ctx)
            btnAddAnki.isVisible = true
            val pill = PillAnkiButton(btnAddAnki)
            // Tap opens the editable review (default action). Long-press is
            // the headless one-tap shortcut — documented by the pro-tip
            // footer in Settings → Anki.
            btnAddAnki.setOnClickListener {
                if (!ankiManager.isAnkiDroidInstalled()) {
                    ui.showAnkiNotInstalled()
                } else {
                    ui.openAnkiReview(ankiReviewArgs(word, lookupForm, primary, screenshotPath, defResult))
                }
            }
            btnAddAnki.setOnLongClickListener {
                if (!ankiManager.isAnkiDroidInstalled()) {
                    ui.showAnkiNotInstalled()
                } else {
                    oneTapWordFromDetail(pill, word, lookupForm, primary, entries, screenshotPath, defResult)
                }
                true
            }

            // The Words and Other matches rows can machine-translate, so they
            // load after the Anki button is bound, into the host buildContent
            // left in their place.
            launch {
                loadSecondarySections(
                    secondaryHost, primary, entries, wordSpan, engine, sourceLangId, targetLangCode, display,
                )
            }

            if (targetLangCode != "en") {
                launch {
                    val translated = runCatching {
                        withContext(Dispatchers.IO) { resolver.translateExamples(response!!) }
                    }.getOrNull() ?: return@launch
                    if (!ui.isAlive) return@launch
                    applyExampleTranslations(translated)
                }
            }

            if (moreExamplesGroup != null) {
                launch {
                    val lookupWord = primary.headwords.firstOrNull()?.written
                        ?: primary.slug
                    val pairs = TatoebaClient.fetch(
                        word = lookupWord,
                        sourceLang = moreExamplesSourceLang,
                        targetLang = moreExamplesTargetLang,
                    )
                    if (!ui.isAlive) return@launch
                    // Build the entry-level fallback only if Tatoeba came up
                    // empty. Wiktionary stores the translation in English;
                    // for target≠en we ML-translate each in parallel so the
                    // user sees source + target-language sentence (instead
                    // of source + English). Pulling examples across every
                    // returned entry mirrors the multi-entry render above.
                    val entryExampleFallback = if (!pairs.isNullOrEmpty()) {
                        emptyList()
                    } else {
                        val raw = entries
                            .flatMap { it.senses }
                            .flatMap { it.examples }
                            .filter { it.text.isNotBlank() }
                        if (raw.isEmpty()) emptyList()
                        else if (targetLangCode == "en" || enToTargetWrapper == null) {
                            raw.map { TatoebaClient.SentencePair(it.text, it.translation) }
                        } else withContext(Dispatchers.IO) {
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
                    // Localize the target-language example sentences to the
                    // chosen Traditional variant. Tatoeba cmn is mostly Simplified
                    // and the en→target fallback emits Simplified; this path
                    // bypasses DefinitionResolver, so convert here.
                    val localizedPairs = charConverter?.let { c ->
                        pairs?.map { it.copy(target = c.convert(it.target)) }
                    } ?: pairs
                    val localizedFallback = charConverter?.let { c ->
                        entryExampleFallback.map { it.copy(target = c.convert(it.target)) }
                    } ?: entryExampleFallback
                    applyMoreExamples(localizedPairs, localizedFallback)
                }
            }
        }
    }

    /**
     * Computes the (reading, pos, definition) triple shared by both
     * the review-open path and the one-tap path. Pulling this out keeps
     * the two paths in lockstep on which definition the user sees and
     * what lands on the card. The reading is the one of the headword the
     * page's key [lookupForm] names.
     */
    private fun buildAnkiWordFields(
        entry: DictionaryEntry,
        defResult: DefinitionResult?,
        lookupForm: String,
    ): Triple<String, String, String> {
        val reading = pageReading(entry, lookupForm).orEmpty()

        val pos = ctx.firstSensePosLabel(entry)

        val targetLangCode = Prefs(ctx).targetLang
        val nativeTargetSenses = (defResult as? DefinitionResult.Native)
            ?.targetSenses
            ?.sortedBy { it.senseOrd }
            ?.takeIf { it.isNotEmpty() }
        val isTargetDriven = targetLangCode != "en" && nativeTargetSenses != null

        // Imported term-dictionary lines lead (one per line, source in
        // parens), numbered continuously with the pack's lines — the same
        // flat shape every Anki definition builder emits.
        val importedLines = importedFlatLines(entry.importedSenses)
        val packLines: List<String> = if (isTargetDriven) {
            nativeTargetSenses.map { it.glosses.joinToString("; ") }
        } else {
            val targetByOrd = if (defResult is DefinitionResult.Native)
                defResult.targetSenses.associateBy { it.senseOrd } else null
            val translatedDefs = when (defResult) {
                is DefinitionResult.MachineTranslated -> defResult.translatedDefinitions
                is DefinitionResult.EnglishFallback -> defResult.translatedDefinitions
                else -> null
            }
            entry.senses.mapIndexedNotNull { i, sense ->
                if (sense.targetDefinitions.isEmpty()) return@mapIndexedNotNull null
                targetByOrd?.get(i)?.glosses?.joinToString("; ")
                    ?: translatedDefs?.getOrElse(i) { sense.targetDefinitions.joinToString("; ") }
                    ?: sense.targetDefinitions.joinToString("; ")
            }
        }
        val rawLines = importedLines + packLines
        val definition =
            (if (rawLines.size > 1) rawLines.mapIndexed { i, l -> "${i + 1}. $l" } else rawLines)
                .joinToString("\n")
        return Triple(reading, pos, definition)
    }

    /** The reading the page gives its own word in [entry], the entry its
     *  key [lookupForm] resolved: the occurrence reading the opener showed
     *  ([readingHint], 明日 → あす) where the entry lists it for that
     *  spelling, else the reading of the headword [lookupForm] names. Null
     *  for a headword written in its reading. The word's reading on its
     *  card, and in the "In this sentence" group's question. */
    private fun pageReading(entry: DictionaryEntry, lookupForm: String): String? {
        val hw = entry.selectHeadword(lookupForm, lookupForm, readingHint)
        return hw?.reading?.takeIf { it != hw.written }
    }

    /** The page's own word as its opener found it ([CardTarget]): the text
     *  the opener found it as, else its key [lookupForm], which is the text
     *  for an opener that had only the lemma. */
    private fun pageTarget(lookupForm: String): CardTarget =
        pageCardTarget(args?.surface, lookupForm, args?.lookupReading)

    /** The page's sentence as its Anki actions read it now
     *  ([pageSentence]): [host], the host's live context, over the launch
     *  arguments, with the page's own word's answer the "In this sentence"
     *  group's. */
    private fun ankiSentence(host: SentenceContext? = ui.sentenceContext()): SentenceContext =
        pageSentence(host, args, explain?.session)

    /** The editable review for the page's own word, on the page's sentence
     *  ([ankiSentence]), its note what that sentence holds about the word.
     *  The card's word is the display [word]; its fields come from the
     *  headword [lookupForm] names. */
    private fun ankiReviewArgs(
        word: String,
        lookupForm: String,
        entry: DictionaryEntry,
        screenshotPath: String?,
        defResult: DefinitionResult?,
    ): WordAnkiArgs {
        val (reading, pos, definition) = buildAnkiWordFields(entry, defResult, lookupForm)
        return wordAnkiArgs(
            ankiSentence(),
            target = pageTarget(lookupForm),
            word = word,
            reading = reading,
            pos = pos,
            definition = definition,
            screenshotPath = screenshotPath,
            freqScore = entry.freqScore,
            isCommon = entry.isCommon == true,
            sourceLangId = Prefs(ctx.applicationContext).sourceLangId,
        )
    }

    /**
     * One-tap card-send from the word-detail Anki button. Mirrors the
     * sheet's mode default: when sentence context is available, the send
     * routes to the sentence pipeline with the looked-up word as target;
     * otherwise a word card. Falls back to the editable review on
     * permission gates / missing deck. NeedsMapping goes through
     * [Ui.openFieldMapping].
     */
    private fun oneTapWordFromDetail(
        pill: PillAnkiButton,
        word: String,
        lookupForm: String,
        entry: DictionaryEntry,
        entries: List<DictionaryEntry>,
        screenshotPath: String?,
        defResult: DefinitionResult?,
    ) {
        val fallback = ankiReviewArgs(word, lookupForm, entry, screenshotPath, defResult)
        val ankiManager = AnkiManager(ctx)
        if (!ankiManager.hasPermission()) {
            ui.openAnkiReview(fallback)
            return
        }
        val prefs = Prefs(ctx.applicationContext)
        if (prefs.ankiDeckId < 0L) {
            ui.openAnkiReview(fallback)
            return
        }
        val sourceLangId = prefs.sourceLangId

        // The page's sentence, as the review path reads it ([ankiSentence]).
        // When present, route to a sentence card with the word as target.
        val hostContext = ui.sentenceContext()
        val sentence = ankiSentence(hostContext)
        // Build a WordsPayload only when both halves come from the
        // same atomic source (the host's SentenceContext, populated
        // from a single Settled emission). Args-only fallback has no
        // surfaces — pass null and let oneTapSendSentence await the
        // per-sentence cache lookup, which is atomic.
        val sentenceWordsPayload: LastSentenceCache.WordsPayload? = run {
            val hostWords = hostContext?.wordResults
            val hostSurfaces = hostContext?.surfaceForms
            val hostEnrich = hostContext?.wordEnrichment
            if (hostWords != null && hostSurfaces != null) {
                LastSentenceCache.WordsPayload(hostWords, hostSurfaces, hostEnrich.orEmpty())
            } else null
        }

        pill.setLoading(true)
        val appCtx = ctx.applicationContext
        // Built before the send detaches from this surface's lifecycle — it
        // reads the target-lang pref, which must resolve now.
        val (reading, pos, definition) = buildAnkiWordFields(entry, defResult, lookupForm)
        // The headword the key names, with the display [word] as the seen
        // surface deciding the kana-only collapse.
        val hw = entry.headwordDisplay(entry.selectHeadword(lookupForm, lookupForm, readingHint), word)
        val target = pageTarget(lookupForm)
        // Ui.launchOneTap: dismissing the page mid-send must not cancel the
        // card; result handling runs only while the surface is live, else
        // degrades to an app-context toast.
        ui.launchOneTap(
            send = {
                // The word-vs-sentence decision (incl. the single-word-sentence
                // rule) lives in oneTapSend, shared by every long-press path.
                // mode informs the NeedsMapping dialog's defaults.
                appCtx.oneTapSend(
                    word = word,
                    reading = reading,
                    pos = pos,
                    entry = entry,
                    entries = entries,
                    fallbackDefinition = definition,
                    freqScore = entry.freqScore,
                    pitch = hw.pitch,
                    frequencies = hw.frequencies,
                    sentenceOriginal = sentence.original,
                    sentenceTranslation = sentence.translation,
                    wordsPayload = sentenceWordsPayload,
                    screenshotPath = screenshotPath,
                    sourceLangId = sourceLangId,
                    pendingTranslation = sentence.pending,
                    // The page's target-language tier rides to the card so
                    // its senses read as the page showed them.
                    defResult = defResult,
                    target = target,
                )
            },
            presentResult = { (result, mode) ->
                handleOneTapWordResult(result, pill, mode, fallback)
            },
        )
    }

    private fun handleOneTapWordResult(
        result: AnkiSendResult,
        pill: PillAnkiButton,
        mode: CardMode,
        fallback: WordAnkiArgs,
    ) {
        when (result) {
            is AnkiSendResult.Success -> {
                val msgRes = result.shortfallRes() ?: ankiAddedSuccessRes(mode)
                Toast.makeText(ctx, msgRes, Toast.LENGTH_SHORT).show()
                pill.setLoading(false)
            }
            is AnkiSendResult.Failed -> {
                ui.present(
                    OverlayAlert.Builder(ctx)
                        .setTitle(ctx.getString(R.string.anki_send_failed_title))
                        .setMessage(result.message ?: ctx.getString(result.messageRes))
                        .addButton(
                            ctx.getString(android.R.string.ok),
                            ctx.themeColor(R.attr.ptAccent),
                            ctx.themeColor(R.attr.ptAccentOn),
                        ) {},
                )
                pill.setLoading(false)
            }
            is AnkiSendResult.NeedsMapping -> {
                // Dispatcher already toasted; the host decides how mapping
                // continues (dialog, or the editable review fallback).
                ui.openFieldMapping(result, mode, fallback)
                pill.setLoading(false)
            }
            // Unreachable: one-tap passes no oversizePrompt.
            is AnkiSendResult.Declined -> pill.setLoading(false)
        }
    }

    /** Builds the page's primary content into [content] and returns the
     *  host [loadSecondarySections] fills with the Words and Other matches
     *  sections: appended last, holding a loading line until they land. */
    private suspend fun buildContent(
        content: LinearLayout,
        entries: List<DictionaryEntry>,
        engine: com.playtranslate.language.SourceLanguageEngine,
        sourceLangId: SourceLangId,
        defResult: DefinitionResult?,
        initialTranslations: List<List<String>>?,
        targetLangCode: String,
        enToTargetTranslator: WordTranslator?,
        display: HeadwordDisplay,
        inflectedForms: List<InflectedForm>,
        tables: List<Pair<DictionaryEntry, ConjugationTable>> = emptyList(),
        lookupForm: String,
    ): LinearLayout {
        // [primary] is the first entry. Header / Anki / character-breakdown
        // sections are word-level, so they pull from primary even when the
        // sense list below merges senses from sibling entries (typical for
        // Wiktionary-derived packs that POS-split into separate entries).
        val primary = entries.first()
        // ── Header block: headword + reading + badges ─────────────────────
        addHeaderBlock(content, primary, sourceLangId, display, inflectedForms)

        // ── Definitions group ─────────────────────────────────────────────
        // Target-driven render path: for non-English targets with a Native
        // pack hit, the target pack's sense list is the canonical structure
        // (JMdict's English-vs-target sense alignment is unrecoverable, so
        // we stop pretending it exists and render the German/Spanish/etc.
        // senses on their own terms — see the long discussion in commit
        // history). Falls back to the entry-driven path for English
        // targets, MachineTranslated/EnglishFallback results, and the
        // defensive case of an empty Native.targetSenses.
        val nativeTargetSenses = (defResult as? DefinitionResult.Native)
            ?.targetSenses
            ?.sortedBy { it.senseOrd }
            ?.takeIf { it.isNotEmpty() }
        val isTargetDriven = targetLangCode != "en" && nativeTargetSenses != null
        // Flat sense list: senses across all returned entries, in order.
        // The entry-driven render iterates this; for Wiktionary-derived
        // packs each (POS-distinct) entry contributes its own slice, so
        // "surprise" yields noun/verb/intj cells in sequence. Target-
        // driven render still uses primary.senses since `targetByOrd`
        // is keyed against the primary entry's sense ordinals.
        val flatSenses = entries.flatMap { it.senses }
        Log.d(TAG, "render entry=${primary.slug} target=$targetLangCode " +
            "defResult=${defResult?.let { it::class.simpleName } ?: "null"} " +
            "targetDriven=$isTargetDriven " +
            "(${nativeTargetSenses?.size ?: 0} target senses, ${flatSenses.size} source senses across ${entries.size} entries)")

        val translatedDefs = when (defResult) {
            // Native no longer carries per-sense MT fallback (target-driven
            // render handles it); only MT/English-fallback variants populate
            // translatedDefinitions for the entry-driven path below.
            is DefinitionResult.MachineTranslated -> defResult.translatedDefinitions
            is DefinitionResult.EnglishFallback -> defResult.translatedDefinitions
            else -> null
        }
        val targetByOrd = if (!isTargetDriven && defResult is DefinitionResult.Native)
            defResult.targetSenses.associateBy { it.senseOrd } else null
        val numSenses = if (isTargetDriven) nativeTargetSenses.size
            else flatSenses.count { it.targetDefinitions.isNotEmpty() }

        // "Machine translated" banner fires when the user will actually
        // see MT output — either because no Native target was available
        // (MachineTranslated headword), or because the Native result
        // didn't cover every source sense so MT is filling the gaps.
        // Target-driven rendering never falls back to MT, so the banner
        // is suppressed there.
        val anyMtDisplayed = !isTargetDriven && flatSenses.withIndex().any { (idx, s) ->
            if (s.targetDefinitions.isEmpty()) return@any false
            val target = targetByOrd?.get(idx)
            target == null && translatedDefs?.getOrNull(idx)?.isNotBlank() == true
        }
        val mtBannerText = when {
            defResult is DefinitionResult.MachineTranslated ->
                ctx.getString(R.string.word_detail_mt_banner_named, defResult.translatedHeadword)
            anyMtDisplayed ->
                ctx.getString(R.string.word_detail_mt_banner)
            else -> null
        }
        if (mtBannerText != null) addMachineTranslatedBanner(content, mtBannerText)

        val definitionsSuffix = if (numSenses > 1)
            ctx.resources.getQuantityString(R.plurals.word_detail_senses_count, numSenses, numSenses) else null
        addGroupHeader(content, ctx.getString(R.string.word_detail_group_definitions), definitionsSuffix)
        val definitionsCard = addGroupCard(content)

        // The card is ONE definitions document. The imported term
        // dictionaries' definitions lead, unnumbered and unclamped, one
        // labelled block per dictionary in the user's section order (final
        // text, never machine-translated); then a row per sense.
        val rows = mutableListOf<DefRow>()
        val rowOfSense = HashMap<Int, Int>()
        val importedGroups = primary.importedSenses
        if (importedGroups.any { it.senses.isNotEmpty() }) {
            rows += DefRow.Imported(IMPORTED_ROW_ID, importedGroups)
        }
        val italic = targetSupportsItalics(ctx)
        fun senseRow(
            id: String,
            number: Int?,
            posLabels: List<String>,
            glosses: List<String>,
            misc: String?,
            examples: List<com.playtranslate.model.Example>,
            translations: List<String>?,
        ) = DefRow.Sense(
            id = id,
            number = number,
            pos = if (posLabels.isEmpty()) "" else ctx.localizePos(posLabels).uppercase(Locale.ROOT),
            gloss = glosses.joinToString("; "),
            misc = misc,
            examples = examples.mapIndexed { i, ex ->
                DefRow.Example(ex.text, translations?.getOrNull(i).orEmpty(), italic)
            },
        )

        if (isTargetDriven) {
            // Each target sense carries its own POS (kaikki tags it per
            // sense and the FST format preserves it). When a target row's
            // pos is blank — only happens for PanLex-derived rows, which
            // don't ship POS metadata — fall back to the source entry's
            // POS *only if it's unambiguous*. Wiktionary multi-POS lookups
            // (e.g. "surprise" → noun + verb + intj) have no way to align
            // a blank-pos target sense to a specific source entry, so we
            // suppress the label rather than mislabel verb/intj rows as
            // the primary entry's POS.
            val fallbackPos = unambiguousFallbackPos(entries)
            nativeTargetSenses.forEachIndexed { idx, target ->
                rows += senseRow(
                    id = "t$idx",
                    number = if (nativeTargetSenses.size > 1) idx + 1 else null,
                    posLabels = target.pos.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
                        ?: fallbackPos,
                    glosses = target.glosses,
                    misc = ctx.renderMiscText(target.misc),
                    examples = target.examples,
                    translations = target.examples.map { it.translation },
                )
            }
        } else {
            var displayCount = 0
            flatSenses.forEachIndexed { flatIdx, sense ->
                if (sense.targetDefinitions.isEmpty()) return@forEachIndexed
                val target = targetByOrd?.get(flatIdx)
                // The row a late example translation of this sense patches.
                rowOfSense[flatIdx] = rows.size
                rows += senseRow(
                    id = "s$flatIdx",
                    number = if (numSenses > 1) displayCount + 1 else null,
                    posLabels = (target?.pos ?: sense.partsOfSpeech).filter { it.isNotBlank() },
                    glosses = target?.glosses
                        ?: translatedDefs?.getOrNull(flatIdx)?.let { listOf(it) }
                        ?: sense.targetDefinitions,
                    misc = ctx.renderMiscText(sense.misc),
                    examples = sense.examples,
                    translations = initialTranslations?.getOrNull(flatIdx),
                )
                displayCount++
            }
        }

        releaseDefinitions()
        if (rows.isNotEmpty()) {
            val language = sourceLangId.yomitanConsumingLang()
            val document = DefinitionDocument(
                ctx, definitionsCard, scope,
                theme = { cardDocTheme(ctx, DEFINITIONS_BASE_PX) },
                // Nothing in the block is a tap target; its links open
                // themselves.
                onAction = { _, _ -> },
            )
            document.setRows(rows, language)
            definitions = DefinitionsBlock(document, rows, language, rowOfSense)
        }

        // ── In this sentence (the user's AI model, about the page's sentence) ──
        // The word as the header shows it, read as its card reads it.
        addExplainGroup(content, display.written, pageReading(primary, lookupForm))

        // ── Conjugation / Declension / Forms (Wiktionary packs with inflection tables) ──
        // The header names what the table is of (a verb's conjugation, a German
        // noun's declension, an English word's forms), and the card names its
        // lemma whenever that is not the page's headword (vino's page lists
        // venir's senses and so carries venir's table) or when there are
        // several cards.
        for ((entry, table) in tables) {
            val title = when (table.kind) {
                TableKind.CONJUGATION -> R.string.word_detail_group_conjugation
                TableKind.DECLENSION -> R.string.word_detail_group_declension
                TableKind.FORMS -> R.string.word_detail_group_forms
            }
            addGroupHeader(
                content, ctx.getString(title),
                suffix = entry.slug.takeIf { it != primary.slug || tables.size > 1 },
            )
            addGroupCard(content).addView(ConjugationTableView(ctx).apply { bind(table) })
        }

        // ── More examples (Tatoeba, online) ──────────────────────────────
        if (TatoebaClient.supports(moreExamplesSourceLang, moreExamplesTargetLang)) {
            addMoreExamplesPlaceholder(content)
        }

        // ── Character breakdown group (Kanji / Hanzi) ────────────────────
        val cjkChars = (primary.headwords.firstOrNull()?.written ?: primary.slug)
            .filter { c -> c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF }
            .toList().distinct()

        if (cjkChars.isNotEmpty()) {
            val characterDetails = withContext(Dispatchers.IO) {
                cjkChars.mapNotNull { engine.lookupCharacter(it, targetLangCode) }
            }
            if (ui.isAlive && characterDetails.isNotEmpty()) {
                val headerTitle = when (characterDetails.first()) {
                    is KanjiDetail -> ctx.getString(R.string.word_detail_group_kanji)
                    is HanziDetail -> ctx.getString(R.string.word_detail_group_hanzi)
                }
                // Rows whose pack-resolved meanings are still in English while
                // the user asked for something else. CC-CEDICT hanzi always
                // land here; JA kanji only when KANJIDIC2 has no native gloss
                // in the user's target language (i.e. outside en/fr/es/pt).
                val needsMt = targetLangCode != "en" && characterDetails.any {
                    it.meaningsLang != targetLangCode
                }
                val countLabel = ctx.resources.getQuantityString(
                    R.plurals.word_detail_chars_count,
                    characterDetails.size,
                    characterDetails.size
                )
                val suffix = if (needsMt && enToTargetTranslator != null)
                    ctx.getString(R.string.word_detail_char_meanings_mt, countLabel)
                else
                    countLabel
                addGroupHeader(content, headerTitle, suffix)
                val charCard = addGroupCard(content)
                val meaningsRegistry = mutableMapOf<Int, TextView>()
                characterDetails.forEachIndexed { index, detail ->
                    if (index > 0) addInsetDivider(charCard, indentPx = dpRes(R.dimen.pt_row_h_padding))
                    addCharacterRow(charCard, detail, index, meaningsRegistry)
                }

                if (needsMt && enToTargetTranslator != null) {
                    // Localize Simplified MT output to the chosen Traditional
                    // variant (this path bypasses DefinitionResolver).
                    val charConverter = ChineseScriptConverter.forTarget(
                        targetLangCode, Prefs(ctx.applicationContext).targetChineseVariant,
                    )
                    // Launch translations on the host scope so buildContent
                    // returns immediately and the Anki button / more-examples
                    // setup isn't blocked behind ML Kit. Each row updates as
                    // its MT resolves.
                    characterDetails.forEachIndexed { index, detail ->
                        if (detail.meaningsLang == targetLangCode) return@forEachIndexed
                        if (detail.meanings.isEmpty()) return@forEachIndexed
                        scope.launch {
                            // Mirror addCharacterRow's display — the full
                            // meanings list joined by ", " — so the translator
                            // sees the same surface the user would otherwise read.
                            val source = detail.meanings.joinToString(", ")
                            val translated = runCatching {
                                withContext(Dispatchers.IO) { enToTargetTranslator.translate(source) }
                            }.getOrNull()
                            if (!translated.isNullOrBlank() && ui.isAlive) {
                                // MT emits Simplified; convert to the chosen
                                // Traditional variant. The kanji/hanzi breakdown
                                // bypasses DefinitionResolver, so convert here.
                                val localized = charConverter?.convert(translated) ?: translated
                                meaningsRegistry[index]?.text = dedupeMtCsv(localized)
                            }
                        }
                    }
                }
            }
        }

        // ── Words and Other matches (loaded after the Anki button) ───────
        return addSecondaryHost(content)
    }

    /**
     * Appends the "In this sentence" group to [content] when the page has a
     * sentence (its [pageSentence]'s text now), and nothing at all when it
     * has none: the page's group header over a card the group fills
     * ([WordExplainGroup]). Its question is about that sentence under the
     * pair the page reads in, naming [word] as the header shows it, its
     * [reading], and the form the opener found it as ([Args.surface]). The
     * answer the page was opened with about its own word, as a card would
     * read it, is taken up as if asked here. The found page places the
     * group under the definitions ([buildContent]), the not-found page
     * under its notice.
     */
    private fun addExplainGroup(content: LinearLayout, word: String, reading: String?) {
        val launch = args ?: return
        val sentence = pageSentence(ui.sentenceContext(), launch)
        val text = sentence.original?.takeIf { it.isNotBlank() } ?: return
        addGroupHeader(content, ctx.getString(R.string.word_explain_title))
        explain = WordExplainGroup(
            ctx, addGroupCard(content), scope,
            WordInSentence(
                askSentence(text, Prefs(ctx.applicationContext).langContext()),
                word, reading, listOfNotNull(launch.surface),
            ),
            carried = sentence.explanations[launch.target],
            host = object : WordExplainGroup.Host {
                override fun onAskNeedsLlm() = ui.onAskNeedsLlm()
                override fun onControlGone(next: View) = ui.onControlGone(next)
            },
        )
    }

    /** Appends to [content] and returns the host the Words and Other
     *  matches sections load into ([loadSecondarySections]). Their rows
     *  resolve through the full definition tier chain, which
     *  machine-translates on a non-English target without a native gloss,
     *  so [bind] loads them after the primary content; until they land the
     *  host holds a loading line styled like the More examples
     *  placeholder's. The found page places it under the definitions
     *  ([buildContent]), the not-found page under its notice. */
    private fun addSecondaryHost(content: LinearLayout): LinearLayout {
        val secondaryHost = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        secondaryHost.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.words_loading)
            textSize = 13f
            setTextColor(ctx.themeColor(R.attr.ptTextMuted))
            setTypeface(null, Typeface.ITALIC)
            setPadding(
                dpRes(R.dimen.pt_row_h_padding),
                dpRes(R.dimen.pt_row_v_padding),
                dpRes(R.dimen.pt_row_h_padding),
                dpRes(R.dimen.pt_row_v_padding),
            )
        })
        content.addView(secondaryHost)
        return secondaryHost
    }

    /** Loads the Words and Other matches sections into [host], the one
     *  [addSecondaryHost] appended, after [bind] has shown the page's
     *  primary content (and wired the Anki button, on a found page):
     *  releases the previous sections' pages
     *  ([releaseSectionLists]), appends Words then Other matches after
     *  the host's loading line, then removes the line, and the host as well
     *  when neither section rendered. [primary] is null on the not-found
     *  page, where the sections are all the page has to offer. */
    private suspend fun loadSecondarySections(
        host: LinearLayout,
        primary: DictionaryEntry?,
        entries: List<DictionaryEntry>,
        wordSpan: TokenSpan,
        engine: com.playtranslate.language.SourceLanguageEngine,
        sourceLangId: SourceLangId,
        targetLangCode: String,
        display: HeadwordDisplay,
    ) {
        // The host holds only buildContent's loading line until the
        // sections append after it.
        val loadingLine = host.getChildAt(0)
        releaseSectionLists()

        // ── Member words (multi-word expressions) ────────────────────────
        // For a multi-word expression ("a great deal", 気になる) the detail
        // page is where the member words resurface: one standard word-result
        // cell per member, tappable through to that word's own detail.
        // Members come from the engine's own split
        // ([SourceLanguageEngine.memberWordsOf]: whitespace on the
        // space-delimited languages, a raw fusing-free tokenizer pass on JA),
        // NOT engine.tokenize, whose phrase handling could re-fuse the
        // expression into one token.
        addMemberWordsSection(host, primary, engine, sourceLangId, targetLangCode, display)

        // ── Other matches ────────────────────────────────────────────────
        // The other dictionary entries the word could be, as it was found
        // ([SourceLanguageEngine.alternativesOf]): the lens offers them as
        // "Also matches" sections, and this page lists them as cells that
        // drill into their own pages, where the Anki button is.
        addAlternativesSection(host, entries, wordSpan, engine, sourceLangId, targetLangCode)

        if (!ui.isAlive) return
        host.removeView(loadingLine)
        if (host.childCount == 0) (host.parent as? ViewGroup)?.removeView(host)
    }

    /** Appends the "Words" section for a multi-word headword: each member
     *  word resolved through the shared row pipeline ([resolveWordRows]) and
     *  rendered as a row whose tap opens a nested detail via
     *  [Ui.openWordDetail]. No-op for single-word headwords or when no
     *  member resolves. The engine split runs on the DISPLAYED headword
     *  form, the header's [display]: for JA `uk` entries the kana form
     *  (かもしれない), whose members correctly fail the engine's kanji gate
     *  where the kanji variant's (かも知れない → 知れ) would pass. Built by
     *  [loadSecondarySections], which releases the previous sections'
     *  pages first. */
    private suspend fun addMemberWordsSection(
        content: LinearLayout,
        primary: DictionaryEntry?,
        engine: com.playtranslate.language.SourceLanguageEngine,
        sourceLangId: SourceLangId,
        targetLangCode: String,
        display: HeadwordDisplay,
    ) {
        // The engine's member policy through the one rule every surface
        // shares ([memberWordsFor]): spaced headwords and phrases
        // (exp-tagged, or glue-bearing by the engine's own reading of them,
        // which is how the not-found page's unlisted phrase qualifies) get
        // the loose gate, 気になる → 気, 瞬く間に → 瞬く and 間; transparent
        // compounds (放送番組/ペース配分) need every unit accounted for
        // (kanji words render, katakana words and particles are excused),
        // and 図書館-style compounds stay whole, listed or not. The display
        // reading rides along so the members' hints align with it.
        val members = engine.memberWordsFor(display.written, display.reading, primary)
        if (members.isEmpty()) return
        val appCtx = ctx.applicationContext
        val rows = resolveWordRows(
            appCtx,
            WordLookupContext(engine, targetLangCode, Prefs(appCtx).targetChineseVariant),
            members,
        ).rows
        if (!ui.isAlive || rows.isEmpty()) return
        addWordCellsSection(content, ctx.getString(R.string.section_words), rows, sourceLangId)
    }

    /** Appends the "Other matches" section: the other dictionary entries the
     *  page's word could be as found in [wordSpan]
     *  ([com.playtranslate.language.SourceLanguageEngine.alternativesOf],
     *  besides the page's own [entries]), each resolved through its own
     *  [resolveWordRows] call (the resolver de-duplicates tokens by lookup
     *  form, and two homographs share one) and rendered by
     *  [addWordCellsSection] with its own conjugation line. No-op when no
     *  alternative resolves. Built by [loadSecondarySections] after the
     *  Words section, under the one release that precedes both. */
    private suspend fun addAlternativesSection(
        content: LinearLayout,
        entries: List<DictionaryEntry>,
        wordSpan: TokenSpan,
        engine: com.playtranslate.language.SourceLanguageEngine,
        sourceLangId: SourceLangId,
        targetLangCode: String,
    ) {
        val primaryIds = entries.mapNotNullTo(mutableSetOf()) { it.packId }
        val alternatives = engine.alternativesOf(wordSpan, primaryIds)
        if (alternatives.isEmpty()) return
        val appCtx = ctx.applicationContext
        val lookupContext = WordLookupContext(engine, targetLangCode, Prefs(appCtx).targetChineseVariant)
        val rows = coroutineScope {
            alternatives.map { async { resolveWordRows(appCtx, lookupContext, listOf(it)).rows } }.awaitAll()
        }.flatten()
        if (!ui.isAlive || rows.isEmpty()) return
        addWordCellsSection(content, ctx.getString(R.string.section_other_matches), rows, sourceLangId)
    }

    /** One group of words under [title] (the Words and Other matches
     *  sections): a [WordRowList] in its own card, each row with its speak
     *  action and Anki button, whose tap opens a nested detail via
     *  [Ui.openWordDetail] with the row's surface and the key it was
     *  resolved under. The list is one definitions document, inside the
     *  page's native header and card like every other group. */
    private fun addWordCellsSection(
        content: LinearLayout,
        title: String,
        rows: List<RowState>,
        sourceLangId: SourceLangId,
    ) {
        addGroupHeader(content, title)
        val card = addGroupCard(content)
        val list = wordRowList(
            ctx, card, scope,
            object : WordRowList.Host<RowState> {
                override val isAlive: Boolean get() = ui.isAlive
                // These rows carry no deck pill (the page's own header does).
                override val deckBadges: Boolean get() = false
                override fun trailing(row: RowState): DefRow.Trailing = DefRow.Trailing.ANKI

                override fun onOpen(row: RowState) = ui.openWordDetail(
                    row.displayWord, row.reading.ifEmpty { null }, row.surface,
                    row.lookupForm, row.lookupReading,
                )

                override fun onTrailing(row: RowState, shown: DefRow.Trailing) {
                    if (!AnkiManager(ctx).isAnkiDroidInstalled()) {
                        ui.showAnkiNotInstalled()
                    } else {
                        ui.openAnkiReview(
                            rowAnkiArgs(
                                row, ankiSentence(),
                                args?.screenshotPath, sourceLangId,
                            ),
                        )
                    }
                }

                // The page's one speech job, shared with its header chips: a
                // row's tap starts it and returns, so the row draws no spinner.
                override suspend fun speak(row: RowState) =
                    speakHeadword(row.displayWord, row.reading, sourceLangId)
            },
            WordRowList.LARGE_SCALE,
        )
        sectionLists += list
        list.setRows(rows, sourceLangId.yomitanConsumingLang())
    }


    /** The page's headword for [entry], the entry its key [lookupForm]
     *  resolved: the variant the display [word] names, so the header shows
     *  what the opener showed (entry 2863328 groups 無下 + 無気; a page
     *  opened on 無気 must show 無気, not 無下), else the one [lookupForm]
     *  names, else the entry's displayable primary. [word] is the seen
     *  surface: for an entry marked "Kana only" (JMdict uk tag, なぜ over
     *  何故) the kanji is suppressed unless the opener showed it. With no key
     *  of the opener's own, [lookupForm] is [word] and this is
     *  [headwordDisplay] of [word]. */
    private fun pageHeadwordDisplay(entry: DictionaryEntry, word: String, lookupForm: String): HeadwordDisplay =
        entry.headwordDisplay(
            entry.headwordFor(word) ?: entry.headwordFor(lookupForm) ?: entry.headwords.preferDisplayable(),
            word,
        )

    /**
     * Reading + speak chip + badge block that lives in the scroll content
     * beneath the overlay headword. The overlay TextView itself is set up
     * in [bind] (typeface, pivot, scroll listener); here we just rewrite
     * its text to the canonical headword [display] ([pageHeadwordDisplay])
     * and emit the reading line (with its speak chip) plus the Common pill
     * and stars badge row. [inflectedForms], when there are any, are drawn as
     * the conjugation lines between the headword and the readings.
     */
    private fun addHeaderBlock(
        parent: LinearLayout,
        entry: DictionaryEntry,
        sourceLangId: SourceLangId,
        display: HeadwordDisplay,
        inflectedForms: List<InflectedForm>,
    ) {
        val written = display.written
        val readingRows = entry.orderedReadingRows(readingHint)
        // Kana-only: the (single) reading just repeats the kana title. Draw the
        // accent on the TITLE itself, inline the speak icon to its right, and drop
        // the reading rows below — instead of repeating the kana.
        val kanaOnly = readingRows.size == 1 && readingRows[0].reading == written

        // Replace the placeholder (the queried word) with the canonical headword.
        // For kana-only words the pitch contour rides on the title (the layout
        // listener in bind resyncs the scroll content's paddingTop).
        if (kanaOnly && readingRows[0].pitch.isNotEmpty()) {
            bigHeadwordView?.text = buildPitchAnnotatedReading(written, readingRows[0].pitch)
            bigHeadwordView?.setPadding(0, dp(10), 0, 0) // overline headroom
        } else {
            bigHeadwordView?.text = written
            bigHeadwordView?.setPadding(0, 0, 0, 0)
        }
        // Speak icon inline to the right of the title when there's no reading row to
        // host it (kana-only); the whole title becomes the tap-to-speak target.
        configureHeadwordSpeak(kanaOnly, written, sourceLangId)

        val isCommon = entry.isCommon == true
        val freqStars = entry.freqScore.coerceIn(0, 5)

        val block = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(4), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // The conjugation line (飲んでいなかった · -て « -いる « negative « -た),
        // muted, at the size the results cell draws the same line.
        for (form in inflectedForms) {
            block.addView(TextView(ctx).apply {
                text = InflectionChain.format(ctx, form)
                setTextColor(ctx.themeColor(R.attr.ptTextMuted))
                setTextSize(
                    TypedValue.COMPLEX_UNIT_SP,
                    InflectionChain.TEXT_SP * WordRowList.LARGE_SCALE,
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).also { it.bottomMargin = dp(4) }
            })
        }

        // Every reading, ordered by common use, flowing inline and wrapping to a
        // new line only when the width runs out. Each is its own tap target — tap
        // the reading OR its chip to hear it — carrying its pitch contour, with the
        // reading the lens highlighted ([readingHint], e.g. 明日 → あす) bolded in
        // place (a cold lookup bolds nothing). A written-only entry (non-JA) yields
        // a single speak chip.
        // Skipped entirely for kana-only — the accent + speak icon ride on the
        // title above instead of repeating the kana here.
        if (!kanaOnly) {
            val readingsFlow = FlowLayout(ctx).apply {
                lineSpacingPx = dp(6)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            if (readingRows.isEmpty()) {
                readingsFlow.addView(buildReadingUnit(written, null, emptyList(), bolded = false, sourceLangId))
            } else {
                for (row in readingRows) {
                    readingsFlow.addView(
                        buildReadingUnit(
                            row.written ?: written, row.reading, row.pitch, row.bolded, sourceLangId,
                        )
                    )
                }
            }
            block.addView(readingsFlow)
        }

        // Badges: Common pill, star rating, and — resolved asynchronously —
        // the "already in Anki" deck pill. Built unconditionally as a wrapping
        // FlowLayout so a word that is ONLY in a deck still has a row to attach
        // to, and a long deck name wraps to a second line instead of clipping.
        val badgeRow = FlowLayout(ctx).apply {
            lineSpacingPx = dp(4)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(8) }
        }
        if (isCommon) badgeRow.addView(buildCommonPill())
        if (freqStars > 0) badgeRow.addView(buildStarRow(freqStars))
        // Imported-dictionary frequency chips take their colours from the
        // shared contract — neutral data chip, or a filled pill when the
        // dictionary carries an accent override.
        val chipInk = BadgeChips.onAccentInk(ctx)
        for (tag in display.frequencies) {
            val colors = freqChipColors(
                tag.accentColor,
                chipFill = ctx.themeColor(R.attr.ptSurface),
                mutedText = ctx.themeColor(R.attr.ptTextMuted),
                ink = chipInk,
            )
            badgeRow.addView(
                BadgeChips.freqChip(
                    ctx,
                    tag,
                    textColor = colors.text,
                    // bg_meta_chip's programmatic equivalent (its ptSurface
                    // fill, 4dp corners), so a tinted and a neutral chip take
                    // the same shape from the same resolved fill.
                    background = GradientDrawable().apply {
                        setColor(colors.fill)
                        cornerRadius = dp(4).toFloat()
                    },
                    textSizeSp = 11f,
                    horizontalPadPx = dp(10),
                    verticalPadPx = dp(3),
                ).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).also { if (badgeRow.isNotEmpty()) it.marginStart = dp(6) }
                }
            )
        }
        badgeRow.isVisible = badgeRow.isNotEmpty()
        block.addView(badgeRow)
        headerBadgeFlow = badgeRow
        headerWord = written
        maybeAddAnkiDeckBadge(badgeRow, written)

        parent.addView(block)
    }

    /** Inline a tap-to-speak affordance ON the title: a trailing speaker icon and
     *  a click that pronounces [speakText]. Used for kana-only words, which carry
     *  no separate reading row to host a speak chip. [enabled] = false clears it. */
    private fun configureHeadwordSpeak(enabled: Boolean, speakText: String, lang: SourceLangId) {
        val hw = bigHeadwordView ?: return
        if (enabled) {
            val icon = AppCompatResources.getDrawable(ctx, R.drawable.ic_lens_speak)?.mutate()?.apply {
                setBounds(0, 0, dp(22), dp(22))
                setTint(ctx.themeColor(R.attr.ptTextMuted))
            }
            hw.setCompoundDrawablesRelative(null, null, icon, null)
            hw.compoundDrawablePadding = dp(8)
            hw.isClickable = true
            // No reading to send: the title is the word's kana already.
            hw.setOnClickListener { speakHeadword(speakText, null, lang) }
        } else {
            hw.setCompoundDrawablesRelative(null, null, null, null)
            hw.isClickable = false
            hw.setOnClickListener(null)
        }
    }

    /** Speaks the word [written], read as [reading] when one is known. Both
     *  go in the request: a voice is given the reading, so the audio matches
     *  the furigana, and a recording is found by the written word. */
    private fun speakHeadword(written: String, reading: String?, lang: SourceLangId) {
        if (speakJob?.isActive == true) return
        speakJob = scope.launch {
            val outcome = PronunciationPlayer.play(ctx, AudioRequest.word(written, reading, lang))
            val failure: String? = when (outcome) {
                PlayOutcome.TtsNoEngine -> "No text-to-speech engine is available"
                is PlayOutcome.TtsLanguageUnsupported ->
                    "Text-to-speech isn't available for ${lang.displayName()}"
                else -> null
            }
            if (failure != null) Toast.makeText(ctx, failure, Toast.LENGTH_SHORT).show()
        }
    }

    /** One reading row: an optional pitch-annotated [reading] (bold + full colour
     *  when [bolded], else muted) followed by a Speak chip that pronounces that
     *  reading. [reading] null → chip only (written-only entries); the chip then
     *  speaks [written]. */
    private fun buildReadingUnit(
        written: String,
        reading: String?,
        pitch: List<Int>,
        bolded: Boolean,
        sourceLangId: SourceLangId,
    ): View {
        // The request carries the written word and this unit's reading. A
        // voice speaks the kana reading (when known), not the kanji surface,
        // so the audio matches the unit (初夏 → はつか vs the engine's しょか
        // guess); a recording is looked up by the written word, and the
        // reading picks the right one of a word that has several.
        val chip = buildSpeakChip(written, reading, sourceLangId, leading = reading == null)
        val unit = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.marginEnd = dp(8) }
        }
        if (reading != null) {
            val ripple = TypedValue().also {
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
            }.resourceId
            unit.addView(TextView(ctx).apply {
                if (pitch.isNotEmpty()) {
                    // Headroom for the overline band — PitchAccentSpan leaves
                    // FontMetrics alone by contract.
                    text = buildPitchAnnotatedReading(reading, pitch)
                    setPadding(0, dp(8), 0, 0)
                } else {
                    text = reading
                }
                textSize = 18f
                setTextColor(ctx.themeColor(if (bolded) R.attr.ptText else R.attr.ptTextMuted))
                if (bolded) setTypeface(typeface, Typeface.BOLD)
                // Tapping the reading itself speaks it — delegate to the chip so
                // the icon/spinner feedback is identical to tapping the chip.
                isClickable = true
                setBackgroundResource(ripple)
                setOnClickListener { chip.performClick() }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            })
        }
        unit.addView(chip)
        return unit
    }

    /**
     * Asynchronously checks whether [word] already has an Anki note and, if
     * so, appends a passive deck pill to [badgeRow] (revealing the row even
     * when the word carries no Common/stars badge). Silent when AnkiDroid is
     * absent, unauthorized, or the word isn't mined.
     */
    private fun maybeAddAnkiDeckBadge(badgeRow: FlowLayout, word: String) {
        val anki = AnkiManager(ctx)
        if (!anki.isAnkiDroidInstalled() || !anki.hasPermission()) return
        scope.launch {
            val decks = withContext(Dispatchers.IO) {
                anki.decksByWord(listOf(word))[word].orEmpty()
            }
            if (!ui.isAlive) return@launch
            // Idempotent across refreshes (noteAddedTick can re-run this): drop
            // any prior deck pill before re-adding.
            for (i in badgeRow.childCount - 1 downTo 0) {
                if (badgeRow.getChildAt(i).tag == deckPillTag) badgeRow.removeViewAt(i)
            }
            if (decks.isNotEmpty()) {
                val pill = AnkiDeckBadge.buildPill(
                    ctx = ctx,
                    deckNames = decks,
                    textColor = ctx.themeColor(R.attr.ptAccent),
                    background = AppCompatResources.getDrawable(ctx, R.drawable.bg_word_common_pill)
                        ?: return@launch,
                    textSizeSp = 11f,
                    horizontalPadPx = dp(10),
                    verticalPadPx = dp(3),
                )
                if (pill != null) {
                    pill.tag = deckPillTag
                    pill.layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).also { it.marginStart = dp(6) }
                    badgeRow.addView(pill)
                }
            }
            badgeRow.isVisible = badgeRow.isNotEmpty()
        }
    }

    /**
     * Speak chip for the header reading row. Tapping it reads the word
     * [written] aloud, as [reading] when one is known (the request carries
     * both; each source takes what it needs), swapping the icon for an
     * indeterminate spinner while the request is in flight. A bordered
     * circle roughly the height of the reading line sits inside a larger
     * 44dp tap target.
     *
     * When [leading] — the chip starts the row, with no reading before it —
     * the circle is pinned to the start edge so it lines up under the
     * headword; otherwise it is centred in the tap target and offset a
     * little past the reading.
     */
    private fun buildSpeakChip(written: String, reading: String?, lang: SourceLangId, leading: Boolean): View {
        val muted = ctx.themeColor(R.attr.ptTextMuted)
        val tint = ColorStateList.valueOf(muted)
        val icon = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_lens_speak)
            imageTintList = tint
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams = FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER)
        }
        val spinner = ProgressBar(ctx, null, android.R.attr.progressBarStyleSmall).apply {
            isIndeterminate = true
            indeterminateTintList = tint
            layoutParams = FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER)
            visibility = View.GONE
        }
        // The icon/spinner ride inside the bordered circle, centred, so they
        // follow it when the circle shifts within the tap area.
        val circle = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dp(1).coerceAtLeast(1), muted)
            }
            layoutParams = FrameLayout.LayoutParams(
                dp(30), dp(30),
                if (leading) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER,
            )
            addView(icon)
            addView(spinner)
        }
        val rippleBg = TypedValue().also {
            ctx.theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, it, true,
            )
        }.resourceId
        return FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                // No leading inset when the chip starts the row, so the circle
                // lands flush under the headword.
                marginStart = if (leading) 0 else dp(8)
            }
            isClickable = true
            setBackgroundResource(rippleBg)
            contentDescription = "Read word aloud"
            addView(circle)
            setOnClickListener {
                if (speakJob?.isActive == true) return@setOnClickListener
                speakJob = scope.launch {
                    icon.isGone = true
                    spinner.isVisible = true
                    try {
                        // Default playback: recordings first (local audio,
                        // Commons when enabled) → the language's voice,
                        // resolved by PronunciationPlayer (a voice source
                        // prepares its own text and applies the voice pref).
                        val outcome = PronunciationPlayer.play(
                            ctx, AudioRequest.word(written, reading, lang),
                        )
                        val failure: String? = when (outcome) {
                            PlayOutcome.TtsNoEngine ->
                                "No text-to-speech engine is available"
                            is PlayOutcome.TtsLanguageUnsupported ->
                                "Text-to-speech isn't available for ${lang.displayName()}"
                            else -> null
                        }
                        if (failure != null) {
                            Toast.makeText(ctx, failure, Toast.LENGTH_SHORT).show()
                        }
                    } finally {
                        icon.isVisible = true
                        spinner.isGone = true
                    }
                }
            }
        }
    }

    private fun buildCommonPill(): TextView =
        BadgeChips.commonPill(
            ctx,
            textSizeSp = 11f,
            horizontalPadPx = dp(10),
            verticalPadPx = dp(3),
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = dp(6) }
        }

    /**
     * Five-star row. Filled stars (U+2605) are tinted with [R.attr.ptAccent];
     * outline stars (U+2606) use [R.attr.ptOutline] so they sit just above
     * the hairline without pulling focus.
     */
    private fun buildStarRow(filled: Int): LinearLayout =
        BadgeChips.starSlots(ctx, filled, textSizeSp = 13f)

    /**
     * Warning-tinted banner (10% alpha fill, 25% alpha stroke) with a
     * triangle icon and single-line warning message. Replaces the old
     * muted-italic notice — the tinted chrome makes it scannable without
     * competing with the headword for attention.
     */
    private fun addMachineTranslatedBanner(parent: LinearLayout, text: String) {
        val warning = ctx.themeColor(R.attr.ptWarning)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
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
        val header = LayoutInflater.from(ctx)
            .inflate(R.layout.settings_group_header, parent, false)
        // As written: the header style capitalizes it in the UI's language
        // (textAllCaps). Uppercasing here would apply the root locale's
        // rules first, and Turkish «Fiil» would show as FIIL, not FİİL.
        header.findViewById<TextView>(R.id.tvGroupTitle).text = title
        val badge = header.findViewById<TextView>(R.id.tvGroupBadge)
        if (!suffix.isNullOrBlank()) {
            badge.text = suffix
            badge.textSize = 10f
            badge.isVisible = true
        } else {
            badge.isGone = true
        }
        parent.addView(header)
    }

    private fun addGroupCard(parent: LinearLayout): LinearLayout {
        val card = PtGroupCard(ctx)
        parent.addView(card)
        return card
    }

    private fun addInsetDivider(parent: LinearLayout, indentPx: Int = dpRes(R.dimen.pt_row_h_padding)) {
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
        val group = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        addGroupHeader(
            group,
            ctx.getString(R.string.word_detail_more_examples),
            ctx.getString(R.string.word_detail_group_tatoeba),
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
            text = ctx.getString(R.string.word_detail_more_examples_loading)
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
                // NEW_TASK: some hosts are overlay windows (the workspace),
                // whose context is no Activity — same rule as a
                // definition's external links.
                runCatching {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW, "https://tatoeba.org/".toUri())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
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
            text = ctx.getString(R.string.word_detail_tatoeba_attribution)
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
    private fun applyMoreExamples(
        pairs: List<TatoebaClient.SentencePair>?,
        entryExampleFallback: List<TatoebaClient.SentencePair> = emptyList(),
    ) {
        val body = moreExamplesBody ?: return
        val group = moreExamplesGroup ?: return
        body.removeAllViews()
        // Examples render as their own rows (padding lives in each row),
        // so drop the body's outer padding before inserting them.
        body.setPadding(0, 0, 0, 0)

        // Tatoeba result OR fallback to entry-level examples (the JMdict
        // examples attached to source senses) when Tatoeba returns
        // nothing or fails — non-English targets lose per-sense example
        // alignment entirely, so this is the only place those examples
        // surface for them.
        val effective = when {
            !pairs.isNullOrEmpty() -> pairs
            entryExampleFallback.isNotEmpty() -> entryExampleFallback
            else -> null
        }
        when {
            effective != null -> {
                effective.forEachIndexed { i, p ->
                    if (i > 0) addInsetDivider(body, indentPx = dpRes(R.dimen.pt_row_h_padding))
                    body.addView(buildTatoebaRow(p.source, p.target))
                }
            }
            pairs == null -> {
                body.setPadding(
                    dpRes(R.dimen.pt_row_h_padding),
                    dpRes(R.dimen.pt_row_v_padding),
                    dpRes(R.dimen.pt_row_h_padding),
                    dpRes(R.dimen.pt_row_v_padding),
                )
                body.addView(TextView(ctx).apply {
                    text = ctx.getString(R.string.word_detail_more_examples_error)
                    textSize = 13f
                    setTextColor(ctx.themeColor(R.attr.ptTextHint))
                })
            }
            else -> group.isGone = true
        }
    }

    /** A single Tatoeba sentence pair: source 15sp/500 on top, target
     *  13sp muted below, standard row padding. */
    private fun buildTatoebaRow(source: String, target: String): View {
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

    /** True when the target gloss language renders italics legibly — i.e.,
     *  uses a Latin-derived script. Non-Latin scripts (CJK, Arabic, Cyrillic,
     *  Greek, Indic, Hebrew, Thai, Georgian) either have no italic forms or
     *  render the italic style as visually different glyphs (e.g., Russian
     *  italic т looks like Latin m), so we leave their translations upright.
     *  English has no target-pack catalog entry but is always Latin. */
    private fun targetSupportsItalics(ctx: Context): Boolean {
        val code = Prefs(ctx).targetLang
        if (code == "en") return true
        return LanguagePackCatalogLoader.entryForKey(ctx, "target-$code")?.script == "LATIN"
    }

    /**
     * Kanji / Hanzi row: 56dp surface tile holding the character and a
     * flex meaning column with labelled readings and a mono meta line
     * (JLPT / grade / strokes / imported frequency data).
     *
     * [meaningsRegistry] (when non-null) records the meanings [TextView]
     * by [index] so a background MT coroutine can swap its text once the
     * translator returns. [index] defaults to -1 for call sites that don't
     * need async updates.
     */
    private fun addCharacterRow(
        parent: LinearLayout,
        detail: CharacterDetail,
        index: Int = -1,
        meaningsRegistry: MutableMap<Int, TextView>? = null,
    ) {
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
        val tile = FrameLayout(ctx).apply {
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
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        })
        row.addView(tile)

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .also { it.gravity = Gravity.CENTER_VERTICAL }
        }

        if (detail.meanings.isNotEmpty()) {
            val meaningsTv = TextView(ctx).apply {
                text = detail.meanings.joinToString(", ")
                textSize = 14f
                setTextColor(ctx.themeColor(R.attr.ptText))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            col.addView(meaningsTv)
            if (index >= 0 && meaningsRegistry != null) {
                meaningsRegistry[index] = meaningsTv
            }
        }

        // Labelled readings — the "on:" / "kun:" / "pinyin:" labels use a
        // small-caps Inter-ish label and the value itself sits in the
        // default sans for CJK compatibility. One row per label: reading
        // lists are rendered in full (no truncation), so each value needs
        // the column's whole width to wrap into — sharing one horizontal
        // line would squeeze the later pair into a sliver.
        for ((label, value) in buildReadingLines(detail)) {
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dp(3) }
            }
            line.addView(TextView(ctx).apply {
                text = ctx.getString(R.string.word_detail_label_format, label.uppercase(Locale.ROOT))
                textSize = 10f
                isAllCaps = true
                letterSpacing = 0.08f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(ctx.themeColor(R.attr.ptTextHint))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also {
                    it.marginEnd = dp(4)
                    // Optically align the small-caps label with the taller
                    // value text's first line when the value wraps.
                    it.topMargin = dp(1)
                }
            })
            line.addView(TextView(ctx).apply {
                text = value
                textSize = 12f
                setTextColor(ctx.themeColor(R.attr.ptTextMuted))
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                )
            })
            col.addView(line)
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

        parent.addView(row)
    }

    private fun buildReadingLines(detail: CharacterDetail): List<Pair<String, String>> = when (detail) {
        is KanjiDetail -> buildList {
            // An imported dict that doesn't follow the on/kun convention
            // (JPDB's usage-ranked list) gets one neutral line — its
            // readings mix both kinds, so ON/KUN labels would mislabel.
            if (detail.combinedReadings.isNotEmpty()) {
                add("readings" to detail.combinedReadings.joinToString(", "))
            } else {
                if (detail.onReadings.isNotEmpty())  add("on" to detail.onReadings.joinToString(", "))
                if (detail.kunReadings.isNotEmpty()) add("kun" to detail.kunReadings.joinToString(", "))
            }
        }
        is HanziDetail -> if (!detail.pinyin.isNullOrBlank())
            listOf("pinyin" to detail.pinyin) else emptyList()
    }

    private fun buildMetaLine(detail: CharacterDetail): CharSequence {
        // Imported kanji-frequency chips lead the mono meta list (one segment
        // per dictionary in section order, each tinted by its per-dict accent
        // override), ahead of the built-in facts. Shared seg() so JA kanji and
        // ZH hanzi render identically.
        val sb = android.text.SpannableStringBuilder()
        fun seg(text: String, color: Int?) {
            if (sb.isNotEmpty()) sb.append("  ·  ")
            val start = sb.length
            sb.append(text)
            if (color != null) {
                sb.setSpan(
                    android.text.style.ForegroundColorSpan(color),
                    start, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        when (detail) {
            is KanjiDetail -> {
                detail.frequencies.forEach { seg("${it.source}: ${it.display}", it.accentColor) }
                if (detail.jlpt > 0)        seg("JLPT N${detail.jlpt}", null)
                if (detail.grade in 1..6)   seg("Grade ${detail.grade}", null)
                else if (detail.grade == 8) seg("Secondary", null)
                if (detail.strokeCount > 0) seg("${detail.strokeCount} strokes", null)
            }
            is HanziDetail -> {
                detail.frequencies.forEach { seg("${it.source}: ${it.display}", it.accentColor) }
                if (detail.isCommon) seg("Common", null)
                if (detail.freqScore > 0) seg("★".repeat(detail.freqScore), null)
            }
        }
        return sb
    }

    private fun addNotFoundNotice(parent: LinearLayout, text: String) {
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

    /**
     * Shrink the overlay headword in place as the user scrolls so it
     * collapses to fit inside the toolbar's left slot. The label is
     * already anchored to the top of the page (no gap above), so this
     * only animates scale — no translation. Linear interpolation
     * across [COLLAPSE_DISTANCE_DP] of scroll.
     */
    private fun updateHeadwordCollapse(scrollY: Int) {
        val hw = bigHeadwordView ?: return
        val threshold = dp(COLLAPSE_DISTANCE_DP)
        if (threshold <= 0) return
        val progress = (scrollY.toFloat() / threshold).coerceIn(0f, 1f)
        val scale = 1f + (TOOLBAR_SCALE - 1f) * progress
        hw.scaleX = scale
        hw.scaleY = scale
    }

    /** Returns [color] with its alpha channel replaced by [alpha] (0..1). */
    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun dp(value: Int) = (value * ctx.resources.displayMetrics.density).toInt()

    private fun dpRes(resId: Int) = ctx.resources.getDimensionPixelSize(resId)
}

/** The span of a detail page's tokenized surface that stands for the page's
 *  [word]: the one whose lookup form is [word], else the first. Null for an
 *  empty tokenization. */
internal fun detailSurfaceSpan(spans: List<TokenSpan>, word: String): TokenSpan? =
    spans.firstOrNull { it.lookupForm == word } ?: spans.firstOrNull()

/** The chain from a detail page's [word], the key it resolves
 *  ([WordDetailBinder.Args.lookupForm]), to the [surface] it was found as,
 *  given the surface's tokenization [spans]. The [detailSurfaceSpan]'s
 *  inflections when that span's lookup form is [word]; otherwise (the page
 *  is another entry the surface could be, 弾く for 弾けた, whose span reads
 *  弾ける) the inflections of the first of [alternativesOf] a span on
 *  [surface] keyed by [word] whose lookup form is [word], and the span's own
 *  inflections when there is none. Empty for an empty tokenization. */
internal suspend fun detailSurfaceTags(
    spans: List<TokenSpan>,
    surface: String,
    word: String,
    alternativesOf: suspend (TokenSpan) -> List<TokenSpan>,
): List<InflectionTag> {
    val span = detailSurfaceSpan(spans, word) ?: return emptyList()
    if (span.lookupForm == word) return span.inflections
    return alternativesOf(TokenSpan(surface, word)).firstOrNull { it.lookupForm == word }?.inflections
        ?: span.inflections
}
