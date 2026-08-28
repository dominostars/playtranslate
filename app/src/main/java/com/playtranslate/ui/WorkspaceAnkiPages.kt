package com.playtranslate.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.playtranslate.AnkiManager
import com.playtranslate.PlayTranslateApplication
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.audio.AudioSelection
import com.playtranslate.language.SourceLangId
import com.playtranslate.themeColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The Anki editors and their pickers as floating-workspace pages — the
 * headline of the overlay rehost: from a capture over the game, the card
 * editor, deck selection, card-type/field-mapping configuration, and the
 * send all run inside the workspace's own back stack, never leaving the
 * game. [AnkiEditorPage] is the word/sentence toggle editor
 * ([WordAnkiReviewBinder]); [AnkiSentenceEditorPage] is the sentence-only
 * editor (the [AnkiReviewBottomSheet] shape, receiving its payload as
 * OBJECTS — the intent-extras transport marshalling is bypassed on this
 * path). The picker pages host the 3a view classes.
 *
 * The audio-source picker is a page too ([AudioPickerPage], hosting
 * [AudioSourcePickerView]); only its RECORD_AUDIO grant still leaves the
 * window — the workspace PARKS while the translucent
 * [RecordAudioPermissionActivity] runs ([WorkspaceHost.setParkedForActivity],
 * un-parked from the trampoline's one-shot gate). Entry points front the
 * AnkiDroid permission BEFORE opening a workspace editor (missing
 * permission falls back to the Activity flow, whose trampoline owns the
 * runtime request).
 */

/** Every clickable, visible view under [root] as a controller target,
 *  excluding styled-definition WebViews (a dead A-press). [holdFor] marks
 *  hold-capable targets (long-press semantics via [ConfirmKeyPress]). */
internal fun collectWorkspaceNavActions(
    root: View?,
    holdFor: (View) -> Boolean = { false },
): List<NavAction> {
    root ?: return emptyList()
    val out = ArrayList<NavAction>()
    fun walk(v: View) {
        if (v is YomitanDefinitionsView) return
        if (v.isClickable && v.isShown) out.add(NavAction(v, holdActivates = holdFor(v)))
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
    }
    walk(root)
    return out
}

/** First descendant of type [T] (depth-first walk), for layouts whose
 *  scroller carries no id. */
private fun <T : View> View.firstDescendantOf(cls: Class<T>): T? {
    if (cls.isInstance(this)) return cls.cast(this)
    if (this !is ViewGroup) return null
    for (i in 0 until childCount) {
        getChildAt(i).firstDescendantOf(cls)?.let { return it }
    }
    return null
}

private inline fun <reified T : View> View.firstDescendant(): T? =
    firstDescendantOf(T::class.java)

/** The dialogs' scroll chrome: a ScrollView over a vertically-padded
 *  column (pt_section_h_padding horizontal, 4dp top, 20dp bottom). */
private fun pickerScroll(ctx: Context, content: View): ScrollView {
    val density = ctx.resources.displayMetrics.density
    val column = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val h = ctx.resources.getDimensionPixelSize(R.dimen.pt_section_h_padding)
        setPadding(h, (4 * density).toInt(), h, (20 * density).toInt())
        addView(content)
    }
    return ScrollView(ctx).apply {
        isFillViewport = true
        addView(
            column,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }
}

/** Tint the Save button's icon in code — its `app:tint` is AppCompat-only
 *  and the workspace's plain inflater drops it. */
private fun tintSaveIcon(sendBtn: View, ctx: Context) {
    (sendBtn as? ViewGroup)?.firstDescendant<ImageView>()?.imageTintList =
        ColorStateList.valueOf(ctx.themeColor(R.attr.ptAccentOn))
}

/** Window-level focus watcher: any EditText gaining focus flips the
 *  workspace into IME mode; focus landing anywhere else drops it. Attached
 *  per page while its view is up — this is what lets the editors' fields
 *  raise the keyboard from a normally non-focusable overlay window without
 *  touching the content's own focus listeners (the Original field's
 *  commit-on-focus-loss must survive). */
private class ImeFocusWatcher(
    private val pageView: View,
    private val host: WorkspaceHost,
) : android.view.ViewTreeObserver.OnGlobalFocusChangeListener {
    override fun onGlobalFocusChanged(oldFocus: View?, newFocus: View?) {
        host.setImeMode(newFocus is EditText)
    }

    fun attach() {
        pageView.viewTreeObserver.addOnGlobalFocusChangeListener(this)
    }

    fun detach() {
        runCatching { pageView.viewTreeObserver.removeOnGlobalFocusChangeListener(this) }
    }
}

/** Shared RECORD_AUDIO round trip for the audio picker page's game-audio
 *  enable switch: park the workspace under the translucent
 *  [RecordAudioPermissionActivity] while the system dialog is up, deliver
 *  the grant, un-park either way. */
private fun requestRecordAudioParked(
    host: WorkspaceHost,
    onResult: (granted: Boolean) -> Unit,
) {
    host.setParkedForActivity(true)
    RecordAudioPermissionActivity.resultGate = { granted ->
        host.setParkedForActivity(false)
        onResult(granted)
    }
    val target = PlayTranslateApplication.foregroundDisplayId() ?: host.displayId
    val opts = android.app.ActivityOptions.makeBasic()
        .setLaunchDisplayId(target)
        .toBundle()
    host.ctx.startActivity(
        Intent(host.ctx, RecordAudioPermissionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        opts,
    )
}

// ── The audio-source picker ──────────────────────────────────────────────

/** The Anki cell's audio picker over the game — [AudioSourcePickerView]
 *  (Commons/TTS/game-audio sections + the global speed cell) as a workspace
 *  page with Save as fixed footer chrome, so changing a card's audio no
 *  longer leaves the window. Only the RECORD_AUDIO grant still does (the
 *  enable switch parks under [RecordAudioPermissionActivity]).
 *
 *  [args] rides the same Intent transport the activity flow uses, decoded
 *  via [AudioSourcePickerActivity.argsFrom]. Delivery keeps the retired
 *  activity gate's discipline — exactly once, on destroy, whatever the
 *  close path: Save marks the pick then pops; a plain pop or workspace
 *  dismissal delivers [onReturned] alone. */
class AudioPickerPage(
    private val args: AudioSourcePickerView.Args,
    private val onReturned: () -> Unit,
    private val onPicked: (AudioSelection) -> Unit,
) : WorkspacePage {

    private var pageScope: CoroutineScope? = null
    private var pageView: View? = null
    private var picker: AudioSourcePickerView? = null
    private var saved: AudioSelection? = null
    private var delivered = false

    override fun title(ctx: Context): CharSequence =
        ctx.getString(R.string.audio_source_picker_title)

    override fun onCreateView(ctx: Context, parent: ViewGroup, host: WorkspaceHost): View {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        pageScope = scope
        val density = ctx.resources.displayMetrics.density
        // The activity_audio_source_picker shape: scrolling sections over a
        // hairline and a full-width Save — built in code (the workspace's
        // plain inflater drops app: attrs on framework tags; the Material
        // classes the content uses style themselves from the M3 theme).
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val sections = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val h = ctx.resources.getDimensionPixelSize(R.dimen.pt_section_h_padding)
            setPadding(h, (4 * density).toInt(), h, (20 * density).toInt())
        }
        val scroll = NestedScrollView(ctx).apply {
            isFillViewport = true
            addView(
                sections,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        // Footer — the editor pages' Save bar, in code: ptBg strip over a
        // hairline, the accent pill (bg_anki_save_button) inset 12dp/8dp,
        // and the editors' label typography (16sp bold sans-serif-medium on
        // ptAccentOn), so the picker's Save reads as the same control one
        // page down the stack.
        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ctx.themeColor(R.attr.ptBg))
        }
        footer.addView(
            View(ctx).apply { setBackgroundColor(ctx.themeColor(R.attr.ptDivider)) },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (1 * density).toInt().coerceAtLeast(1),
            ),
        )
        val save = FrameLayout(ctx).apply {
            minimumHeight = (52 * density).toInt()
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_anki_save_button)
            isClickable = true
            isFocusable = true
        }
        save.addView(
            TextView(ctx).apply {
                text = ctx.getString(R.string.btn_save)
                textSize = 16f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(ctx.themeColor(R.attr.ptAccentOn))
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        footer.addView(
            save,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = (12 * density).toInt()
                marginEnd = (12 * density).toInt()
                topMargin = (8 * density).toInt()
                bottomMargin = (8 * density).toInt()
            },
        )
        root.addView(
            footer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val p = AudioSourcePickerView(
            ctx, scope, args,
            object : AudioSourcePickerView.Host {
                override fun requestRecordAudio(onResult: (Boolean) -> Unit) =
                    requestRecordAudioParked(host, onResult)
            },
        )
        picker = p
        p.buildInto(sections)
        save.setOnClickListener {
            saved = p.pickedSelection()
            host.pop()
        }
        pageView = root
        return root
    }

    override fun navActions(): List<NavAction> = collectWorkspaceNavActions(pageView)

    override fun scrollView(): ViewGroup? =
        pageView?.firstDescendant<NestedScrollView>()

    override fun onDestroy() {
        picker?.release()
        picker = null
        pageScope?.cancel()
        pageScope = null
        pageView = null
        if (!delivered) {
            delivered = true
            onReturned()
            saved?.let(onPicked)
        }
    }
}

// ── The word/sentence editor ─────────────────────────────────────────────

/** The full review editor (Sentence/Word toggle, deck section, curatable
 *  definitions, audio cells, send) over the game. [args] is
 *  [WordAnkiReviewBinder.buildArgs]. */
class AnkiEditorPage(private val args: Bundle) : WorkspacePage {

    private var pageScope: CoroutineScope? = null
    private var binder: WordAnkiReviewBinder? = null
    private var pageView: View? = null
    private var hostRef: WorkspaceHost? = null
    private var imeWatcher: ImeFocusWatcher? = null

    override fun title(ctx: Context): CharSequence =
        args.getString(WordAnkiReviewBinder.ARG_WORD) ?: ctx.getString(R.string.anki_mode_word)

    override fun onCreateView(ctx: Context, parent: ViewGroup, host: WorkspaceHost): View {
        hostRef = host
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        pageScope = scope
        val view = LayoutInflater.from(ctx)
            .inflate(R.layout.sheet_word_anki_review, parent, false)
        tintSaveIcon(view.findViewById(R.id.btnWordAnkiSend), ctx)
        pageView = view
        val b = WordAnkiReviewBinder(ctx, scope, args, EditorHost(ctx, host))
        binder = b
        b.bind(view, null)
        // ONE navigation bar: the workspace header hosts what the sheet's
        // toolbar hosted. The binder built the Sentence/Word toggle into the
        // sheet's toolbar slot (visible only with sentence data) — reparent
        // it into the header; word-only editors keep the word title. Then
        // the sheet's whole toolbar row AND its hairline disappear (the
        // header carries its own).
        val toggle = view.findViewById<FrameLayout>(R.id.wordAnkiToolbarToggle)
        val toolbarRow = view.findViewById<View>(R.id.btnBackWordAnki).parent as View
        val sheetRoot = toolbarRow.parent as ViewGroup
        val rowIdx = sheetRoot.indexOfChild(toolbarRow)
        if (toggle.isVisible) {
            (toggle.parent as ViewGroup).removeView(toggle)
            // The toggle's track fills its container — give it the fixed
            // centre slot the sheet's weighted toolbar half approximated.
            toggle.layoutParams = FrameLayout.LayoutParams(
                (220 * ctx.resources.displayMetrics.density).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
            // No track restyle: the workspace header rides the window
            // ground, so the default ptSurface track reads against it the
            // same way every PillToggle does on a ptBg settings page.
            host.setHeaderView(toggle)
        }
        toolbarRow.isGone = true
        sheetRoot.getChildAt(rowIdx + 1)?.isGone = true
        imeWatcher = ImeFocusWatcher(view, host).also { it.attach() }
        return view
    }

    override fun navActions(): List<NavAction> {
        val root = pageView ?: return emptyList()
        return collectWorkspaceNavActions(root)
    }

    override fun scrollView(): ViewGroup? =
        pageView?.firstDescendant<NestedScrollView>()

    override fun onBack(): Boolean {
        val focused = (pageView?.findFocus() as? EditText) ?: return false
        focused.clearFocus()
        return true
    }

    override fun onDestroy() {
        imeWatcher?.detach()
        imeWatcher = null
        // Workspace teardown is always final: no saved state exists that
        // could restore this editor, so the screenshot pin and the
        // game-audio snapshot are reclaimed now.
        binder?.release(releasePin = true)
        binder = null
        pageScope?.cancel()
        pageScope = null
        pageView = null
        hostRef = null
    }

    private inner class EditorHost(
        private val ctx: Context,
        private val host: WorkspaceHost,
    ) : WordAnkiReviewBinder.Host {
        override val isAlive: Boolean get() = pageView != null

        override suspend fun awaitEnterSettled() = host.awaitEnterSettled()

        override fun openAudioPicker(intent: Intent, onPicked: (AudioSelection) -> Unit) {
            host.push(
                AudioPickerPage(
                    AudioSourcePickerActivity.argsFrom(intent),
                    onReturned = { binder?.onHostResumed() },
                    onPicked = onPicked,
                ),
            )
        }

        override fun openDeckPicker(onPicked: (Long, String) -> Unit) {
            host.push(AnkiDeckPickerPage { id, name ->
                onPicked(id, name)
            })
        }

        override fun openCardTypePicker(onPicked: (Long, String) -> Unit) {
            host.push(AnkiCardTypePickerPage(CardMode.WORD, onPicked))
        }

        override fun openFieldMapping(model: AnkiManager.ModelInfo, mode: CardMode) {
            // NeedsMapping after a send: configure the mapping in-window,
            // then land back on the editor for the re-send.
            host.push(
                AnkiFieldMappingPage(model.id, model.name, model.fieldNames, mode) { _, _ ->
                    host.pop()
                },
            )
        }

        override fun presentAlert(builder: OverlayAlert.Builder) {
            builder.showInParent(host.modalLayer)
        }

        override fun dismiss() = host.dismiss()
    }
}

// ── The sentence-only editor ─────────────────────────────────────────────

/** Sentence-card editor over the game — the [AnkiReviewBottomSheet] shape
 *  with the payload passed as objects (no transport marshalling): deck
 *  section, the shared [SentenceAnkiContentView], lazy translation fill,
 *  and the send. */
class AnkiSentenceEditorPage(
    private val original: String,
    private val translation: String,
    private val wordResults: Map<String, Triple<String, String, Int>>,
    private val surfaceForms: Map<String, String>,
    private val wordEnrichment: Map<String, WordEnrichment>,
    private val screenshotPath: String?,
    private val sourceLangId: SourceLangId,
    private val pendingTranslation: com.playtranslate.model.PendingTranslation? = null,
    private val audioAnchorMs: Long? = null,
) : WorkspacePage {

    private var pageScope: CoroutineScope? = null
    private var pageView: View? = null
    private var hostRef: WorkspaceHost? = null
    private var contentView: SentenceAnkiContentView? = null
    private var sendButton: AnkiSendButton? = null
    private var deckSubtitleView: TextView? = null
    private var pinnedScreenshotPath: String? = null
    private var imeWatcher: ImeFocusWatcher? = null

    override fun title(ctx: Context): CharSequence =
        ctx.getString(R.string.anki_sheet_title_new_card)

    override fun onCreateView(ctx: Context, parent: ViewGroup, host: WorkspaceHost): View {
        hostRef = host
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        pageScope = scope
        val view = LayoutInflater.from(ctx)
            .inflate(R.layout.bottom_sheet_anki_review, parent, false)
        // ONE navigation bar: the workspace header carries the sheet
        // toolbar's title ("New Anki card"), so the sheet's own toolbar row
        // and its hairline disappear entirely.
        val toolbarRow = view.findViewById<View>(R.id.btnBackReview).parent as View
        val sheetRoot = toolbarRow.parent as ViewGroup
        sheetRoot.getChildAt(sheetRoot.indexOfChild(toolbarRow) + 1)?.isGone = true
        toolbarRow.isGone = true
        tintSaveIcon(view.findViewById(R.id.btnSendToAnki), ctx)
        pageView = view

        // Pin at open — the incoming path is a fixed cache filename a
        // capture during this editor's lifetime would overwrite. Released
        // on destroy (workspace teardown is always final).
        pinnedScreenshotPath = AnkiScreenshotPin.pin(ctx, screenshotPath)

        val words = wordResults.map { (w, triple) ->
            SentenceAnkiHtmlBuilder.WordEntry(
                w, triple.first, triple.second, triple.third,
                surfaceForm = surfaceForms[w].orEmpty(),
                pitch = wordEnrichment[w]?.pitch.orEmpty(),
                frequencies = wordEnrichment[w]?.frequencies.orEmpty(),
                isCommon = wordEnrichment[w]?.isCommon ?: false,
                senses = wordEnrichment[w]?.senses.orEmpty(),
            )
        }

        val deckHost = view.findViewById<LinearLayout>(R.id.sentenceAnkiDeckHost)
        deckSubtitleView = view.findViewById(R.id.tvAnkiSendSubtitle)
        addAnkiSection(
            ctx, scope,
            parent = deckHost,
            openDeckPicker = { onPicked -> host.push(AnkiDeckPickerPage(onPicked)) },
            openCardTypePicker = { onPicked ->
                host.push(AnkiCardTypePickerPage(CardMode.SENTENCE, onPicked))
            },
            onDeckChanged = { refreshDeckSubtitle(ctx) },
            onCardTypeChanged = { /* no visible affordance reflects card type */ },
        )
        refreshDeckSubtitle(ctx)

        val cArgs = SentenceAnkiContentView.buildArgs(
            original, translation, words, pinnedScreenshotPath,
            sourceLangId = sourceLangId,
            audioAnchorMs = audioAnchorMs,
        )
        val contentHost = view.findViewById<ViewGroup>(R.id.sentenceAnkiFragmentHost)
        val contentRoot = LayoutInflater.from(ctx)
            .inflate(R.layout.fragment_sentence_anki_content, contentHost, false) as LinearLayout
        // initialWords carries the rich entries (surfaces, pitch, senses)
        // past buildArgs' flattening — the build must render THIS page's
        // snapshot, not whatever sentence the global cache holds by now.
        val content = SentenceAnkiContentView(
            ctx, scope, cArgs, ContentHost(host),
            initialWords = words,
        )
        contentView = content
        contentHost.addView(contentRoot)
        content.buildInto(contentRoot, null)

        val sendBtn = view.findViewById<FrameLayout>(R.id.btnSendToAnki)
        sendButton = AnkiSendButton(sendBtn)
        sendBtn.setOnClickListener {
            val deckId = Prefs(ctx).ankiDeckId
            if (deckId < 0L) {
                Toast.makeText(ctx, ctx.getString(R.string.anki_no_deck_selected), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendButton?.setLoading(true)
            scope.launch { sendToAnki(ctx, host, deckId) }
        }

        // Lazy translation fill — the blank-incoming-translation mirror of
        // the sheet host's, completing a deferred capture's pending when
        // one rides in.
        if (translation.isBlank() && original.isNotBlank()) {
            scope.launch {
                val outcome = resolveAnkiTranslation(pendingTranslation, original)
                contentView?.applyTranslation(original, outcome?.text)
            }
        }

        imeWatcher = ImeFocusWatcher(view, host).also { it.attach() }
        return view
    }

    private inner class ContentHost(
        private val host: WorkspaceHost,
    ) : SentenceAnkiContentView.Host {
        override val isAlive: Boolean get() = pageView != null

        override suspend fun awaitEnterSettled() = host.awaitEnterSettled()

        override fun openAudioPicker(intent: Intent, onPicked: (AudioSelection) -> Unit) {
            host.push(
                AudioPickerPage(
                    AudioSourcePickerActivity.argsFrom(intent),
                    onReturned = { contentView?.onHostResumed() },
                    onPicked = onPicked,
                ),
            )
        }
        // No sibling tab to mirror screenshot removal into.
    }

    private fun refreshDeckSubtitle(ctx: Context) {
        val sub = deckSubtitleView ?: return
        val deckName = Prefs(ctx).ankiDeckName.ifBlank { ctx.getString(R.string.anki_deck_row_empty) }
        sub.text = ctx.getString(R.string.anki_deck_label_format, deckName)
    }

    private suspend fun sendToAnki(ctx: Context, host: WorkspaceHost, deckId: Long) {
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
        )
        val result = ctx.sendSentenceCard(input, deckId)
        if (result is AnkiSendResult.NeedsMapping) {
            host.push(
                AnkiFieldMappingPage(
                    result.model.id, result.model.name, result.model.fieldNames,
                    CardMode.SENTENCE,
                ) { _, _ -> host.pop() },
            )
        }
        val shortfallRes = (result as? AnkiSendResult.Success)?.mediaShortfallRes()
        applyAnkiSendResult(
            ctx, result,
            presentAlert = { it.showInParent(host.modalLayer) },
            onSuccess = {
                shortfallRes?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
                host.dismiss()
            },
            onRestore = { sendButton?.setLoading(false) },
        )
    }

    override fun navActions(): List<NavAction> = collectWorkspaceNavActions(pageView)

    override fun scrollView(): ViewGroup? =
        pageView?.firstDescendant<NestedScrollView>()

    override fun onBack(): Boolean {
        val focused = (pageView?.findFocus() as? EditText) ?: return false
        focused.clearFocus()
        return true
    }

    override fun onDestroy() {
        imeWatcher?.detach()
        imeWatcher = null
        // Workspace teardown is always final — reclaim both media files.
        hostRef?.ctx?.let { AnkiScreenshotPin.release(it, pinnedScreenshotPath) }
        pinnedScreenshotPath = null
        contentView?.release(deleteSnapshotFile = true)
        contentView = null
        sendButton = null
        deckSubtitleView = null
        pageScope?.cancel()
        pageScope = null
        pageView = null
        hostRef = null
    }
}

// ── Picker pages ─────────────────────────────────────────────────────────

/** Deck list, pushed from an editor's Deck row. Pops itself on pick. */
class AnkiDeckPickerPage(
    private val onPicked: (deckId: Long, deckName: String) -> Unit,
) : WorkspacePage {

    private var pageScope: CoroutineScope? = null
    private var pageView: View? = null

    override fun title(ctx: Context): CharSequence =
        ctx.getString(R.string.anki_deck_picker_title)

    override fun onCreateView(ctx: Context, parent: ViewGroup, host: WorkspaceHost): View {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        pageScope = scope
        val content = AnkiDeckPickerView(
            ctx, scope,
            isAlive = { pageView != null },
            onDeckSelected = { id, name ->
                onPicked(id, name)
                host.pop()
            },
        ).build(parent)
        return pickerScroll(ctx, content).also { pageView = it }
    }

    override fun navActions(): List<NavAction> = collectWorkspaceNavActions(pageView)

    override fun scrollView(): ViewGroup? = pageView as? ViewGroup

    override fun onDestroy() {
        pageScope?.cancel()
        pageScope = null
        pageView = null
    }
}

/** Card-type list, pushed from an editor's Card Type row. A direct commit
 *  (Default / basic-shape) pops back; a non-basic model pushes the
 *  field-mapping page, whose Save pops both. */
class AnkiCardTypePickerPage(
    private val mode: CardMode,
    private val onPicked: (modelId: Long, modelName: String) -> Unit,
) : WorkspacePage {

    private var pageScope: CoroutineScope? = null
    private var pageView: View? = null

    override fun title(ctx: Context): CharSequence =
        ctx.getString(R.string.anki_card_type_pick_title)

    override fun onCreateView(ctx: Context, parent: ViewGroup, host: WorkspaceHost): View {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        pageScope = scope
        val content = AnkiCardTypePickerView(
            ctx, scope,
            isAlive = { pageView != null },
            onCardTypePicked = { id, name ->
                onPicked(id, name)
                host.pop()
            },
            openFieldMapping = { model ->
                host.push(
                    AnkiFieldMappingPage(model.id, model.name, model.fieldNames, mode) { id, name ->
                        onPicked(id, name)
                        host.pop()   // the mapping page
                        host.pop()   // this picker — back to the editor
                    },
                )
            },
        ).build(parent)
        return pickerScroll(ctx, content).also { pageView = it }
    }

    override fun navActions(): List<NavAction> = collectWorkspaceNavActions(pageView)

    override fun scrollView(): ViewGroup? = pageView as? ViewGroup

    override fun onDestroy() {
        pageScope?.cancel()
        pageScope = null
        pageView = null
    }
}

/** Per-field mapping editor with its Save footer. [onSaved] fires after
 *  the prefs commit; the PUSHER owns the pops (one for the NeedsMapping
 *  flow, two for the card-type flow). Back without Save commits nothing. */
class AnkiFieldMappingPage(
    private val modelId: Long,
    private val modelName: String,
    private val fieldNames: List<String>,
    private val mode: CardMode,
    private val onSaved: (modelId: Long, modelName: String) -> Unit,
) : WorkspacePage {

    private var pageView: View? = null

    override fun title(ctx: Context): CharSequence =
        ctx.getString(R.string.anki_field_mapping_title, modelName)

    override fun onCreateView(ctx: Context, parent: ViewGroup, host: WorkspaceHost): View {
        val density = ctx.resources.displayMetrics.density
        val mappingView = AnkiFieldMappingView(
            ctx, modelId, modelName, fieldNames, mode,
            openSourcePicker = { fieldName, current, onFieldPicked ->
                host.push(
                    AnkiContentSourcePage(fieldName, current) { picked ->
                        onFieldPicked(picked)
                        host.pop()
                    },
                )
            },
            onSaved = onSaved,
        )
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        column.addView(
            pickerScroll(ctx, mappingView.build(column)),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        // Save footer — the mapping dialog's CTA chrome, code-built.
        column.addView(
            View(ctx).apply { setBackgroundColor(ctx.themeColor(R.attr.ptDivider)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1 * density).toInt()),
        )
        val save = FrameLayout(ctx).apply {
            minimumHeight = (60 * density).toInt()
            setBackgroundResource(R.drawable.bg_anki_save_button)
            isClickable = true
            isFocusable = true
            addView(TextView(ctx).apply {
                text = ctx.getString(R.string.anki_field_mapping_save)
                textSize = 16f
                setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL), Typeface.BOLD)
                setTextColor(ctx.themeColor(R.attr.ptAccentOn))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                )
            })
            setOnClickListener { mappingView.save() }
        }
        column.addView(
            save,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                leftMargin = (12 * density).toInt()
                rightMargin = (12 * density).toInt()
                topMargin = (8 * density).toInt()
                bottomMargin = (8 * density).toInt()
            },
        )
        pageView = column
        return column
    }

    override fun navActions(): List<NavAction> = collectWorkspaceNavActions(pageView)

    override fun scrollView(): ViewGroup? = pageView?.firstDescendant<ScrollView>()

    override fun onDestroy() {
        pageView = null
    }
}

/** Content-source list for a single field. Pops itself on pick via the
 *  caller's closure. */
class AnkiContentSourcePage(
    private val fieldName: String,
    private val current: ContentSource,
    private val onPicked: (ContentSource) -> Unit,
) : WorkspacePage {

    private var pageView: View? = null

    override fun title(ctx: Context): CharSequence =
        ctx.getString(R.string.anki_content_source_pick_title, fieldName)

    override fun onCreateView(ctx: Context, parent: ViewGroup, host: WorkspaceHost): View {
        val content = AnkiContentSourcePickerView(ctx, current, onPicked).build(parent)
        return pickerScroll(ctx, content).also { pageView = it }
    }

    override fun navActions(): List<NavAction> = collectWorkspaceNavActions(pageView)

    override fun scrollView(): ViewGroup? = pageView as? ViewGroup

    override fun onDestroy() {
        pageView = null
    }
}
