package com.playtranslate.ui

import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isGone
import com.playtranslate.AnkiManager
import com.playtranslate.PlayTranslateApplication
import com.playtranslate.R
import com.playtranslate.capture.CaptureBackendResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The word-detail page as a floating-workspace page — the over-game
 * presentation of [WordDetailBinder], so the lens's "open detail" tap no
 * longer leaves the game for [TranslationResultActivity]. Deliberately the
 * WORD page only, not the full sentence screen: the sentence is already on
 * screen in the surface the tap came from (the capture sheet / the lens
 * body), and the sentence context still travels for the Anki card via
 * [sentenceContext].
 *
 * Cross-reference and member-word taps PUSH another instance — the nested
 * detail becomes a real back stack (the DialogFragment host stacks child
 * fragments instead). The Anki actions trampoline to the review Activity
 * for now — the workspace dismisses first (programmatically, so no stashed
 * capture sheet re-shows underneath the launched activity); an in-window
 * editor page replaces that trampoline when the Anki stack is de-fragmented.
 */
class WorkspaceWordDetailPage(
    private val word: String,
    private val reading: String? = null,
    private val screenshotPath: String? = null,
    /** Game-audio ring anchor for the Anki flow (the capture moment); null
     *  when the launching surface has no capture (drag flow). */
    private val audioAnchorMs: Long? = null,
    /** Sentence-context snapshot from the launching surface — taken at open
     *  time, which is correct over the game: the capture is frozen. */
    private val sentenceContext: () -> SentenceContext? = { null },
) : WorkspacePage {

    private var pageScope: CoroutineScope? = null
    private var binder: WordDetailBinder? = null
    private var pageView: View? = null
    private var hostRef: WorkspaceHost? = null

    override fun title(ctx: Context): CharSequence = word

    override fun onCreateView(ctx: Context, parent: ViewGroup, host: WorkspaceHost): View {
        hostRef = host
        // Page-scoped, not workspace-scoped: the deck-badge collector and
        // speak jobs must die on POP, not only on workspace dismissal.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        pageScope = scope
        val view = LayoutInflater.from(ctx)
            .inflate(R.layout.bottom_sheet_word_detail, parent, false)
        // The workspace header carries the title + back chevron; the page
        // runs the binder's embedded (toolbar-less) layout.
        view.findViewById<View>(R.id.wordDetailToolbar).isGone = true
        pageView = view
        val b = WordDetailBinder(ctx, scope, WorkspaceUi(ctx, host))
        binder = b
        b.bind(
            view,
            WordDetailBinder.Args(
                word = word,
                reading = reading,
                screenshotPath = screenshotPath,
                embedded = true,
            ),
        )
        return view
    }

    /** Every clickable view is a controller target (speak chips, readings,
     *  the Anki pill, member cells, the Tatoeba row) — collected per
     *  keypress. The styled-definitions WebView is excluded: A on it would
     *  be a dead press. The Anki pill holds (long-press = one-tap). */
    override fun navActions(): List<NavAction> {
        val root = pageView ?: return emptyList()
        val ankiPill = root.findViewById<View>(R.id.btnWordAddToAnki)
        val out = ArrayList<NavAction>()
        fun walk(v: View) {
            if (v is YomitanDefinitionsView) return
            if (v.isClickable && v.isShown) {
                out.add(NavAction(v, holdActivates = v === ankiPill))
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
        return out
    }

    override fun scrollView(): ViewGroup? =
        pageView?.findViewById(R.id.detailScrollView)

    override fun onDestroy() {
        binder?.release()
        binder = null
        pageScope?.cancel()
        pageScope = null
        pageView = null
        hostRef = null
    }

    /** The workspace host's side of the binder seam. */
    private inner class WorkspaceUi(
        private val ctx: Context,
        private val host: WorkspaceHost,
    ) : WordDetailBinder.Ui {
        override val isAlive: Boolean get() = pageView != null

        override fun sentenceContext(): SentenceContext? =
            this@WorkspaceWordDetailPage.sentenceContext.invoke()

        override fun openWordDetail(word: String, reading: String?) {
            // Cross-reference drill-down as a real back stack — the nested
            // detail carries no sentence/screenshot, mirroring the
            // fragment host's bare nested sheet.
            host.push(WorkspaceWordDetailPage(word, reading))
        }

        override fun openAnkiReview(args: WordDetailBinder.WordAnkiArgs) {
            // In-window editor when the AnkiDroid permission is already held
            // — the headline flow: editor, deck picker, save, all without
            // leaving the game. Missing permission falls back to the
            // trampoline (an Activity must front the runtime request).
            val h = hostRef
            if (h != null && AnkiManager(ctx).hasPermission()) {
                h.push(
                    AnkiEditorPage(
                        WordAnkiReviewBinder.buildArgs(
                            word = args.word,
                            reading = args.reading,
                            pos = args.pos,
                            definition = args.definition,
                            screenshotPath = args.screenshotPath,
                            freqScore = args.freqScore,
                            isCommon = args.isCommon,
                            sentenceOriginal = args.sentenceOriginal,
                            sentenceTranslation = args.sentenceTranslation,
                            sentenceWordResults = args.sentenceWordResults,
                            sourceLangId = args.sourceLangId,
                            sentencePending = args.sentencePending,
                            audioAnchorMs = audioAnchorMs,
                        ),
                    ),
                )
                return
            }
            launchWordAnkiTrampoline(args)
        }

        override fun showAnkiNotInstalled() {
            showAnkiNotInstalledDialog(ctx, host.modalLayer)
        }

        override fun openFieldMapping(
            result: AnkiSendResult.NeedsMapping,
            mode: CardMode,
            fallback: WordDetailBinder.WordAnkiArgs,
        ) {
            // The lens's NeedsMapping parity: fall back to the editable
            // review (which hosts the mapping dialog itself).
            launchWordAnkiTrampoline(fallback)
        }

        override fun present(builder: OverlayAlert.Builder) {
            builder.showInParent(host.modalLayer)
        }

        override fun launchOneTap(
            send: suspend () -> Pair<AnkiSendResult, CardMode>,
            presentResult: (Pair<AnkiSendResult, CardMode>) -> Unit,
        ) {
            // The overlay-caller pattern from AnkiOneTapDispatch: run on the
            // process-lived scope (the card must land even if this page is
            // popped mid-send), present here while the page is live, else
            // degrade to the app-context toast.
            val appCtx = ctx.applicationContext
            ankiOneTapSendScope.launch {
                val payload = try {
                    send()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "one-tap send escaped an exception", e)
                    Toast.makeText(appCtx, R.string.anki_send_failed_message, Toast.LENGTH_LONG).show()
                    return@launch
                }
                if (isAlive) {
                    presentResult(payload)
                } else {
                    oneTapResultToast(appCtx, payload.first, payload.second)
                }
            }
        }
    }

    /** The editable review, until the workspace grows its own editor page:
     *  the AnkiPermissionActivity → WordAnkiReviewActivity trampoline the
     *  lens uses. The workspace is torn down PROGRAMMATICALLY first
     *  ([com.playtranslate.OverlayUiController.dismissWorkspace]) — its
     *  overlay window would otherwise sit above the launched activity, and
     *  a USER-dismissal path would re-show a stashed capture sheet under
     *  it for the same reason. */
    private fun launchWordAnkiTrampoline(args: WordDetailBinder.WordAnkiArgs) {
        val host = hostRef ?: return
        val ctx = host.ctx
        WordAnkiReviewActivity.finishCurrentIfAny()
        AnkiPermissionActivity.finishCurrentIfAny()
        val intent = Intent(ctx, AnkiPermissionActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            putExtra(WordAnkiReviewActivity.EXTRA_WORD, args.word)
            putExtra(WordAnkiReviewActivity.EXTRA_READING, args.reading)
            putExtra(WordAnkiReviewActivity.EXTRA_POS, args.pos)
            putExtra(WordAnkiReviewActivity.EXTRA_DEFINITION, args.definition)
            putExtra(WordAnkiReviewActivity.EXTRA_FREQ_SCORE, args.freqScore)
            args.screenshotPath?.let {
                putExtra(WordAnkiReviewActivity.EXTRA_SCREENSHOT_PATH, it)
            }
            args.sentenceOriginal?.let {
                putExtra(WordAnkiReviewActivity.EXTRA_SENTENCE_ORIGINAL, it)
            }
            args.sentenceTranslation?.let {
                putExtra(WordAnkiReviewActivity.EXTRA_SENTENCE_TRANSLATION, it)
            }
            putExtra(WordAnkiReviewActivity.EXTRA_SOURCE_LANG, args.sourceLangId.code)
            audioAnchorMs?.let {
                putExtra(WordAnkiReviewActivity.EXTRA_AUDIO_ANCHOR_MS, it)
            }
        }
        val targetDisplay = PlayTranslateApplication.foregroundDisplayId() ?: host.displayId
        val opts = android.app.ActivityOptions.makeBasic()
            .setLaunchDisplayId(targetDisplay)
            .toBundle()
        CaptureBackendResolver.activeOverlayUi?.dismissWorkspace()
        ctx.startActivity(intent, opts)
    }

    private companion object {
        const val TAG = "WorkspaceWordDetail"
    }
}
