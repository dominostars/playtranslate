package com.playtranslate.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.view.WindowManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.widget.Toast
import com.playtranslate.AnkiManager
import com.playtranslate.CaptureService
import com.playtranslate.R
import com.playtranslate.capture.CaptureBackendResolver
import com.playtranslate.displaySizePx
import com.playtranslate.MainActivity
import com.playtranslate.OcrManager
import com.playtranslate.PlayTranslateApplication
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.Prefs
import com.playtranslate.translation.ChineseScriptConverter
import com.playtranslate.language.DefinitionGlossTranslators
import com.playtranslate.language.DefinitionResolver
import com.playtranslate.language.DefinitionResult
import com.playtranslate.language.WordTranslator
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.language.TargetGlossDatabaseProvider
import com.playtranslate.language.TranslationManagerProvider
import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.headwordDisplay
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/**
 * Manages the drag-to-lookup workflow:
 * 1. On drag start: screenshot the full screen, run OCR, cache line positions
 * 2. On hold-still: hit-test finger against cached lines, tokenize, dictionary lookup
 * 3. Show/dismiss the WordLookupPopup
 *
 * The screenshot is taken once (when the icon switches to ring mode), not repeatedly.
 * Finger position is checked against cached OCR bounding boxes — essentially free.
 */
class DragLookupController(
    private val context: Context,
    private val displayId: Int,
    private val popup: WordLookupPopup,
    private val magnifier: MagnifierLens,
    private val overlayHost: OverlayHost,
) {
    /** Fires once per drag, on the main thread, when no popup will surface
     *  from this drag (release with no OCR / no hit / async lookup miss) or
     *  when an existing popup is dismissed post-drag. The service uses this
     *  to restore the region indicator + resume live mode. The release path
     *  that launches a lookup defers the signal — popup.onDismiss fires it
     *  later when the user closes the popup. */
    var onSettled: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(
        Dispatchers.Main + SupervisorJob() +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "Uncaught", e) }
    )
    private val ocrManager get() = com.playtranslate.OcrManager.instance

    /** Cached OCR lines from the initial screenshot. */
    private var ocrLines: List<OcrManager.OcrLine>? = null
    private var ocrJob: Job? = null
    private var lookupJob: Job? = null
    /** Wires the lens Speak chip to the TTS engine. Created in [init]. */
    private var speakChip: LensSpeakChip? = null
    private var lastWord: String? = null
    /** Current dictionary entry shown in the popup. */
    private var currentEntry: DictionaryEntry? = null
    /** Path to the screenshot captured at drag start. */
    private var screenshotPath: String? = null
    private var currentSentence: String? = null
    private var lastSentSentence: String? = null
    private var wordLookupJob: Job? = null

    /** Screenshot bitmap captured at drag start, kept alive for the magnifier
     *  through the entire drag. Recycled on drag end (or when superseded by a
     *  new drag). Originally [captureAndOcr] recycled it inline in a `finally`
     *  block; the magnifier needs it to outlive OCR. */
    private var dragBitmap: Bitmap? = null
    /** True between [onDragStart] and [onDragEnd]/[cancelDrag]. The
     *  popup-dismiss handler reads this to distinguish a dismissal triggered
     *  by a new drag starting (skip onSettled — drag2 wants the paused state)
     *  from a normal post-drag dismissal (fire onSettled). */
    private var dragInProgress = false
    /** Most recent finger position seen by [onDragMove]. The release point
     *  is read from these in [onDragEnd] for the lift-time lookup. */
    private var lastX = 0f
    private var lastY = 0f
    /** Capture-before-reveal gate. False until the drag's clean screenshot
     *  has been taken; [onDragStart]/[onDragMove] keep the lens off-screen
     *  while it's false so the lens can't appear in the captured frame.
     *  [revealLensAfterCapture] flips it true and brings the lens up once
     *  the capture returns. Reset on every drag-end path. */
    private var lensRevealed = false

    /** Cached per-line token info for the magnifier label readout. We store
     *  the visible surface (for hit-testing), the dictionary form (the
     *  word shown in the lens left panel), the reading (furigana/pinyin
     *  shown above the word), and the token's character offset within the
     *  line text — the offset disambiguates duplicate surfaces in the same
     *  line, which can resolve to different lemmas in context. Filled on
     *  the OCR coroutine after recognition completes because
     *  [engine.tokenize] is suspend; per-frame label detection then reads
     *  this map synchronously. */
    private data class LabelToken(
        val surface: String,
        val lookupForm: String,
        val reading: String?,
        val charOffset: Int,
    )
    /** A line + the LabelToken under the finger, returned from
     *  [detectLabelTokenAt]. The (line.text, token.charOffset) pair is the
     *  identity used by the dwell logic to detect "different word, same
     *  position" cases and to key cached lookup results. */
    private data class TokenHit(val line: OcrManager.OcrLine, val token: LabelToken)
    /** Result of [detectWordAt]. */
    private data class WordReadout(val word: String, val reading: String?)
    /** Cache key for the dwell-triggered lookup result so a release at the
     *  same word can reuse it instead of re-running tokenize + dictionary. */
    private data class DwellKey(val lineText: String, val charOffset: Int)
    private var lineTokensCache: Map<String, List<LabelToken>>? = null

    // Dwell-preview state. Drives the 1-second hold timer that fires
    // [runDwellLookup] when the finger is still over a word, plus the
    // cached result so [onDragEnd] can transition to the sticky lens
    // without re-running the lookup.
    private val dwellRunnable = Runnable { runDwellLookup() }
    private var dwellAnchorX = 0f
    private var dwellAnchorY = 0f
    private var dwellAnchorToken: TokenHit? = null
    private var dwellScheduled = false
    private var dwellLookupJob: Job? = null
    private var dwellResult: Pair<DwellKey, PopupData>? = null
    private val dwellTolerancePx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        DWELL_TOLERANCE_DP,
        popup.ctx.resources.displayMetrics,
    )

    init {
        // Lens absorbs the popup's role in the drag flow (see plan
        // "Lens-Replaces-Popup"). The popup constructor parameter stays
        // for non-drag consumers (TranslationResultFragment) and as a
        // context fallback below; drag-flow callbacks live on the lens.
        // "Open in detail view" always goes to TranslationResultActivity
        // — sentence + segmented Sentence/Word toggle — regardless of
        // single- or dual-screen mode, so the user lands on a consistent
        // surface with the switch available.
        magnifier.onOpenTap = { openSentenceInApp() }
        // Anki chip: tap opens the editable review sheet (default).
        // Long-press is the headless one-tap shortcut — documented by
        // the pro-tip footer in Settings → Anki.
        magnifier.onAnkiTap = { openAnkiReviewForLens() }
        magnifier.onAnkiLongPress = { oneTapFromLens() }
        // Speak chip → pronounce the looked-up headword via the system TTS
        // engine. LensSpeakChip installs the lens's onSpeakTap handler and
        // owns the speak coroutine + alert routing.
        speakChip = LensSpeakChip(
            magnifier,
            scope,
            TtsAlertTarget.Overlay(magnifier.rawCtx, overlayHost, magnifier.wm, displayId),
        ) {
            lastWord?.let { word ->
                LensSpeakChip.Request(
                    word,
                    Prefs(popup.ctx).sourceLangId,
                    // Speak the displayed kana reading (JA) so audio matches the lens.
                    reading = currentEntry?.headwordDisplay(word)?.reading,
                )
            }
        }
        // Lens dismissal post-drag fires [onSettled] so the service can
        // restore region indicator + live mode. If a new drag starts and
        // tears down a sticky lens, dragInProgress is true at that moment
        // and onSettled is suppressed — the new drag will fire its own
        // settle when its lens is eventually dismissed.
        magnifier.onDismiss = {
            lastWord = null
            currentEntry = null
            // Cancel a pending speak and stop any in-progress speech when
            // the lens goes away.
            speakChip?.release()
            if (!dragInProgress) onSettled?.invoke()
        }
    }

    /** True when the lens is in sticky-definitions mode (drag has ended,
     *  user is reading the result). Callers use this to decide whether
     *  game-input or live-mode toggles should dismiss it first. Property
     *  name kept as `isPopupShowing` for compat with callers wired during
     *  the popup-era; semantically it now means "is the drag-flow lookup
     *  surface attached and interactive". Reads directly from the lens —
     *  the lens is the single source of truth for its own mode. */
    val isPopupShowing: Boolean get() = magnifier.isInteractive

    companion object {
        private const val TAG = "DragLookup"
        /** Hold time before dwell triggers definitions. */
        private const val DWELL_MS = 1000L
        /** Movement (px equivalent of dp) tolerance during dwell — finger
         *  jitter under this threshold doesn't reset the timer or revert
         *  the lens from DEFINITIONS back to ZOOM. */
        private const val DWELL_TOLERANCE_DP = 8f
        /** Machine-translated label rendered above the senses, mirroring
         *  the popup's same-named warning. */
        private const val MACHINE_TRANSLATED_LABEL = "⚠ Machine translated"
        /** True if [s] contains any CJK ideograph (kanji / hanzi). Used as
         *  the gate for whether a reading is worth showing — for fully
         *  kana / hangul / Latin words a phonetic readout is redundant.
         *  The popup's dict-headword path rarely hits this case because
         *  JMdict stores written/reading consistently, but kuromoji's
         *  tokenize output can return a katakana reading for a hiragana
         *  word (or vice versa), which would otherwise show up as a
         *  redundant furigana in the lens. */
        private fun hasKanji(s: String): Boolean = s.any { c ->
            val code = c.code
            code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF
        }

        /** Expansion around the line bounds for hit-testing (3 tiers, tight
         *  to looser). Sized for the crosshair-magnifier feedback loop —
         *  the user sees exactly where the finger is via the lens, so we
         *  only need a tiny amount of slack for ML Kit bounds being snug
         *  to the glyphs and for finger jitter. The previous values were
         *  ~5× larger and tuned for blind dragging without visual feedback. */
        private const val HIT_EXPAND_X_PX_1 = 16
        private const val HIT_EXPAND_Y_PX_1 = 6
        private const val HIT_EXPAND_X_PX_2 = 40
        private const val HIT_EXPAND_Y_PX_2 = 16
        private const val HIT_EXPAND_X_PX_3 = 80
        private const val HIT_EXPAND_Y_PX_3 = 30

        /** Sentence-ending punctuation for extracting sentences from group text. */
        private val SENTENCE_END_PUNCTUATION = setOf(
            '.', '!', '?', '\u2026',   // Latin / general (… = \u2026)
            '\u3002', '\uFF01', '\uFF1F' // 。！？ CJK fullwidth
        )

        /**
         * Finds the token at [fingerX] in [lineText]. Uses [symbols] (per-
         * character bounds from ML Kit) when available — correct for non-
         * monospaced fonts like Latin. Falls back to `fallbackLineLeft +
         * idx * fallbackCharWidth` math when symbols are absent or
         * misaligned with [lineText], preserving the pre-Phase-3 CJK path.
         *
         * Preference order:
         *  1. Symbol-aware precise hit — finger within [left, right] of a token
         *  2. charWidth fallback precise hit
         *  3. Nearest-center (covers gaps between tokens)
         *
         * `internal` so the unit test in `FindClosestTokenTest` can exercise
         * it without needing an instance of the enclosing class.
         */
        /**
         * Finds the token closest to the finger position along the text flow axis.
         *
         * @param fingerPos The finger coordinate along the text flow axis:
         *   X for horizontal text, Y for vertical text.
         * @param vertical When true, token extents come from symbol top/bottom
         *   and fallback uses character height instead of width.
         */
        internal fun findClosestToken(
            lineText: String,
            tokens: List<String>,
            fingerPos: Int,
            symbols: List<OcrManager.SymbolBox>,
            fallbackLineStart: Int,
            fallbackCharExtent: Float,
            vertical: Boolean = false,
        ): Pair<String, Int>? {
            data class TokenPos(val token: String, val idx: Int, val start: Float, val end: Float)
            val positioned = mutableListOf<TokenPos>()

            var pos = 0
            for (token in tokens) {
                val idx = lineText.indexOf(token, pos)
                if (idx < 0) continue
                val endIdx = idx + token.length
                val tokenSymbols = symbols.filter { it.charOffset in idx until endIdx }
                val start: Float
                val end: Float
                if (tokenSymbols.isNotEmpty()) {
                    if (vertical) {
                        start = tokenSymbols.minOf { it.bounds.top }.toFloat()
                        end = tokenSymbols.maxOf { it.bounds.bottom }.toFloat()
                    } else {
                        start = tokenSymbols.minOf { it.bounds.left }.toFloat()
                        end = tokenSymbols.maxOf { it.bounds.right }.toFloat()
                    }
                } else {
                    start = fallbackLineStart + idx * fallbackCharExtent
                    end = fallbackLineStart + endIdx * fallbackCharExtent
                }
                positioned += TokenPos(token, idx, start, end)
                pos = endIdx
            }
            if (positioned.isEmpty()) return null

            // Prefer exact hit (finger within token span).
            val exact = positioned.firstOrNull { fingerPos >= it.start && fingerPos <= it.end }
            if (exact != null) return exact.token to exact.idx

            // Fallback: nearest center.
            val nearest = positioned.minByOrNull {
                val center = (it.start + it.end) / 2f
                abs(fingerPos - center)
            }
            return nearest?.let { it.token to it.idx }
        }
    }

    private fun queryScreenSize(): Point = popup.ctx.displaySizePx()

    // ── Public API (called from FloatingOverlayIcon callbacks) ───────────

    /**
     * Called once when the icon transitions to drag mode. Takes a screenshot
     * and runs full-screen OCR. If [existingScreenshotPath] is provided
     * (e.g. from a hold-to-preview capture), OCR runs on that file instead
     * of taking a new screenshot — avoids OS rate-limit failures.
     *
     * No popup surfaces during the drag — only the magnifier is live. The
     * popup is committed at release ([onDragEnd]).
     */
    fun onDragStart(existingScreenshotPath: String? = null) {
        // Tear down everything left over from the previous drag. Previous
        // drag's lift-time lookupJob may still be in flight; cancel it so it
        // doesn't transition the lens after this drag has started. Hand the
        // previous drag's bitmap off to its (now-cancelled) OCR job —
        // Job.cancel() is cooperative and ML Kit text recognition is non-
        // cancellable at the native layer, so the worker keeps the bitmap
        // alive until it returns; handOffDragBitmap waits via
        // invokeOnCompletion.
        handOffDragBitmap()
        ocrJob?.cancel()
        lookupJob?.cancel()
        handler.removeCallbacks(dwellRunnable)
        dwellLookupJob?.cancel()
        dwellScheduled = false
        dwellResult = null
        dwellAnchorToken = null
        // dragInProgress=true BEFORE any lens mutation that could fire
        // onDismiss — the suppression check in the onDismiss handler
        // depends on it.
        dragInProgress = true
        // A sticky lens from the previous drag carries focusable+touchable
        // flags + populated definitions panel + outside-touch listener —
        // none of which are correct for a fresh ZOOM-mode drag. Reset the
        // existing window in place rather than dismiss + re-add: the
        // dismiss + addOverlayWindow cycle races with in-flight clean
        // captures (the new window lands at alpha=1 after
        // prepareForCleanCapture's snapshot, then takeScreenshot picks
        // it up before restoreAfterCapture runs). resetToZoom mutates
        // the same registered handle, so its alpha state under capture
        // stays consistent.
        magnifier.resetToZoom()
        // Capture-before-reveal: keep the lens off-screen until the clean
        // screenshot has been taken — [revealLensAfterCapture] brings it up
        // once requestClean returns. The old code showed the lens here and
        // relied on the capture pipeline blanking every overlay during the
        // grab, but on the MediaProjection backend the mirror frame can lag
        // that blank, so the lens — even its blank placeholder — landed in
        // the screenshot and masked the very text under the finger. hide()
        // also takes down any sticky lens left from the previous drag so it
        // isn't in the frame either.
        lensRevealed = false
        magnifier.hide()
        ocrLines = null
        lastSentSentence = null
        lineTokensCache = null
        val thisJob = scope.launch {
            try {
                if (existingScreenshotPath != null) {
                    ocrFromFile(existingScreenshotPath)
                } else {
                    captureAndOcr()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "OCR failed", e)
            }
        }
        ocrJob = thisJob
        // Safety net for paths that don't reach the inline clear in
        // [ocrFromFile] / [captureAndOcr]: bitmap-null / lines-null
        // early returns, exceptions, and cancellation. On success the
        // inline clear has already fired and this is a no-op (the
        // setPillLoading guard makes it idempotent). invokeOnCompletion
        // fires on an arbitrary thread; post to main. The identity
        // guard protects against a stale cancelled job's late completion
        // clearing the new drag's spinner — ML Kit text recognition is
        // non-cancellable at the native layer, so a previous-drag job
        // can complete after the next onDragStart has armed its spinner.
        thisJob.invokeOnCompletion {
            handler.post {
                if (ocrJob === thisJob) magnifier.setPillLoading(false)
            }
        }
    }

    /** Capture-before-reveal: bring the lens on screen at the finger's
     *  current position once the clean screenshot is in hand. Until this
     *  runs, [onDragStart]/[onDragMove] keep the lens hidden so it can't
     *  appear in the captured frame. Idempotent, and a no-op if the drag
     *  already ended (user lifted before the capture finished) — [onDragEnd]'s
     *  dismiss path owns that case. Runs on the main thread (the OCR
     *  coroutine is Main-dispatched). */
    private fun revealLensAfterCapture() {
        if (lensRevealed || !dragInProgress) return
        lensRevealed = true
        val screen = queryScreenSize()
        magnifier.show(lastX.toInt(), lastY.toInt(), screen.x, screen.y)
        // Arm the spinner skin before the first setLabel so the first paint
        // of the pill is the loading look, not the magnifying-glass icon.
        magnifier.setPillLoading(true)
        magnifier.setLabel(null, null)
    }

    private suspend fun ocrFromFile(path: String) {
        val bitmap = withContext(Dispatchers.IO) {
            android.graphics.BitmapFactory.decodeFile(path)
        }
        if (bitmap == null) {
            Log.w(TAG, "Could not load screenshot from $path, falling back to capture")
            captureAndOcr()
            return
        }
        // Source bitmap loaded — reveal the lens (capture-before-reveal). The
        // file was captured clean upstream, so nothing leaked into it. Job-
        // identity guard: a stale drag's late job must not reveal into a newer
        // drag's capture window.
        if (ocrJob === coroutineContext[Job]) revealLensAfterCapture()
        onScreenshotCaptured(bitmap, path)
        val lines = withContext(Dispatchers.Default) {
            ocrManager.recogniseWithPositions(bitmap, Prefs(popup.ctx).sourceLang)
        }
        if (lines == null) {
            Log.d(TAG, "No text found in saved screenshot")
            return
        }
        Log.d(TAG, "OCR from file found ${lines.size} lines")
        // Publish ocrLines BEFORE pretokenizing — the release-time lookup
        // does its own [engine.tokenize] in performLookupInner and only
        // needs the recognized lines. Pretokenization fills the live-label
        // cache, which is purely for the magnifier's word readout; gating
        // ocrLines on it would drop release-time lookups during the ~50-
        // 300 ms pretokenize window.
        ocrLines = lines
        // The pill spinner tracks "release-time lookup is blocked" — clear
        // it here, not when the OCR coroutine ends. pretokenizeLines below
        // only refines the hover-time label readout; gating the spinner
        // on full job completion would keep "Processing…" up while the
        // user can already lift to look up a word. Identity-guarded
        // against a stale cancelled job's late return: ML Kit text
        // recognition is non-cancellable at the native layer.
        if (ocrJob === coroutineContext[Job]) {
            magnifier.setPillLoading(false)
        }
        pretokenizeLines(lines)
    }

    /**
     * Called on every ACTION_MOVE during a drag. Magnifier follows the
     * finger, shows a small readout of the word currently under the
     * finger, and schedules the 1-second dwell timer that triggers the
     * inline definitions preview when the finger holds still over a word.
     */
    fun onDragMove(rawX: Float, rawY: Float) {
        lastX = rawX
        lastY = rawY
        // Capture-before-reveal: while the clean screenshot is being taken the
        // lens stays off-screen so it can't contaminate the frame. Keep
        // tracking the finger ([revealLensAfterCapture] shows the lens at the
        // latest position once the capture returns); moves after that fall
        // through here normally.
        if (!lensRevealed) return
        val screen = queryScreenSize()
        magnifier.show(rawX.toInt(), rawY.toInt(), screen.x, screen.y)
        refreshLabelAndDwell()
    }

    /** Re-evaluate the token under the finger at the current [lastX]/[lastY],
     *  refresh the lens label, and (re-)arm the dwell timer.
     *
     *  Called from [onDragMove] for every ACTION_MOVE event AND from
     *  [pretokenizeLines] each time the cache is republished. The second
     *  call site is what keeps the dwell preview working when OCR / cache
     *  state lands AFTER the user has already stopped moving — without
     *  it, the last onDragMove ran with `currentHit == null`, no timer
     *  was scheduled, and the user holds still forever waiting for the
     *  inline definitions to appear.
     *
     *  No-op when the drag has ended; pretokenizeLines may continue to
     *  publish briefly between cancellation request and the coroutine
     *  actually exiting at its next suspension point. */
    private fun refreshLabelAndDwell() {
        if (!dragInProgress) return
        val currentHit = detectLabelTokenAt(lastX.toInt(), lastY.toInt())
        magnifier.setLabel(currentHit?.token?.lookupForm, currentHit?.token?.reading)

        // Dwell tracking: reset on movement past tolerance OR when the
        // token under the finger changes (rare — different word at the
        // same physical position, e.g. when scrolling text). When called
        // from pretokenize after the finger stopped moving, dx/dy are 0
        // and the reset is driven entirely by anchorKey != currentKey:
        // anchor was null pre-cache, becomes non-null post-cache, so we
        // arm the timer for the first time.
        val dx = lastX - dwellAnchorX
        val dy = lastY - dwellAnchorY
        val movedFar = (dx * dx + dy * dy) > dwellTolerancePx * dwellTolerancePx
        val anchorKey = dwellAnchorToken?.let { it.line.text to it.token.charOffset }
        val currentKey = currentHit?.let { it.line.text to it.token.charOffset }
        if (movedFar || anchorKey != currentKey) {
            handler.removeCallbacks(dwellRunnable)
            dwellLookupJob?.cancel()
            dwellLookupJob = null
            dwellResult = null
            dwellScheduled = false
            // Definitions panel was visible if dwell already fired; revert
            // the lens to ZOOM mode for the new anchor.
            magnifier.setDefinitions(null, null)
            dwellAnchorX = lastX
            dwellAnchorY = lastY
            dwellAnchorToken = currentHit
        }
        // Schedule the dwell timer only when we're over a word and not
        // already counting down. If a previous dwell fired and the lens
        // is in DEFINITIONS mode, dwellResult is still set — don't re-
        // schedule until movement clears it.
        if (currentHit != null && !dwellScheduled && dwellResult == null) {
            handler.postDelayed(dwellRunnable, DWELL_MS)
            dwellScheduled = true
        }
    }

    /** Runs on the main thread when the dwell timer fires. Re-resolves the
     *  token at the dwell anchor, runs the dictionary lookup, and feeds
     *  the result into the lens's definitions panel. The result is also
     *  cached in [dwellResult] so a release at the same word can reuse it
     *  without re-fetching. */
    private fun runDwellLookup() {
        dwellScheduled = false
        val anchor = dwellAnchorToken ?: return
        val lines = ocrLines ?: return
        val anchorX = dwellAnchorX.toInt()
        val anchorY = dwellAnchorY.toInt()
        val key = DwellKey(anchor.line.text, anchor.token.charOffset)
        Log.d(TAG, "Dwell fired at ($anchorX, $anchorY) over '${anchor.token.lookupForm}'")
        dwellLookupJob = scope.launch {
            try {
                val resolved = resolveLookupData(anchorX, anchorY, lines, isDwell = true)
                    ?: return@launch
                withContext(Dispatchers.Main) {
                    // Anchor may have changed while the lookup was in-
                    // flight; only publish if the user is still hovering
                    // the same word and the drag is still active.
                    val stillHere = dragInProgress && dwellAnchorToken?.let {
                        it.line.text == anchor.line.text &&
                            it.token.charOffset == anchor.token.charOffset
                    } == true
                    if (!stillHere) return@withContext
                    val (popupData, label) = resolved
                    dwellResult = key to popupData
                    magnifier.setDefinitions(popupData.toLensData(), label)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Dwell lookup failed: ${e.message}")
            }
        }
    }

    /** Synchronous "what token is under the finger right now" — line
     *  hit-test against [ocrLines] + lookup against the pre-tokenized
     *  [lineTokensCache] + nearest-token. Returns the matched line + token,
     *  or null if no text is targeted or the cache hasn't been populated
     *  yet. Disambiguates duplicate surfaces on the same line via
     *  charOffset, which is also the dwell logic's identity key. */
    private fun detectLabelTokenAt(rawX: Int, rawY: Int): TokenHit? {
        val lines = ocrLines ?: return null
        val cache = lineTokensCache ?: return null
        val hitLine = findLineAt(rawX, rawY, lines) ?: return null
        val lineText = hitLine.text
        if (lineText.isEmpty()) return null
        val tokens = cache[lineText] ?: return null
        if (tokens.isEmpty()) return null
        val isVertical = hitLine.orientation == com.playtranslate.language.TextOrientation.VERTICAL
        val lineExtent = if (isVertical) hitLine.bounds.height().toFloat()
            else hitLine.bounds.width().toFloat()
        val charExtent = lineExtent / lineText.length
        val match = findClosestToken(
            lineText = lineText,
            tokens = tokens.map { it.surface },
            fingerPos = if (isVertical) rawY else rawX,
            symbols = hitLine.symbols,
            fallbackLineStart = if (isVertical) hitLine.bounds.top else hitLine.bounds.left,
            fallbackCharExtent = charExtent,
            vertical = isVertical,
        ) ?: return null
        val matchedOffset = match.second
        val matched = tokens.firstOrNull { it.charOffset == matchedOffset }
            ?: return null
        return TokenHit(hitLine, matched)
    }

    /** Thin wrapper around [detectLabelTokenAt] for callers that only need
     *  the lookup form + reading for the lens label. */
    private fun detectWordAt(rawX: Int, rawY: Int): WordReadout? =
        detectLabelTokenAt(rawX, rawY)?.let { WordReadout(it.token.lookupForm, it.token.reading) }

    /** Pre-tokenize every OCR line so [detectLabelTokenAt] can run
     *  synchronously during onDragMove. Called from the OCR coroutine
     *  after recognition completes.
     *
     *  Two phases for speed + correctness:
     *
     *  **Phase 1** — kuromoji tokenize per line, cache the surface span /
     *  base form / surface-form reading. Cheap (single-digit ms per line)
     *  so the label can appear under the finger almost immediately after
     *  OCR finishes. Surface-form readings are sometimes wrong for
     *  inflected verbs/adjectives — kuromoji tags 住ん with reading スン
     *  even though the base form 住む reads すむ — and sometimes absent
     *  (n-gram phrase matches in `tokenizeWithSurfaces` carry null
     *  readings).
     *
     *  **Phase 2** — canonicalize each unique (lookupForm, reading) pair
     *  against the dictionary. Dedupe keys on the pair, not the form
     *  alone, so kuromoji's per-token reading can ride along as a
     *  disambiguation hint: a homograph kanji (人 → ひと "person" vs にん
     *  "counter for people") then resolves to the entry that matches the
     *  surface, instead of whichever entry happens to win the reading-
     *  blind ranking. The pair count barely exceeds the form count — a
     *  form appearing with two readings on one screen is uncommon — so a
     *  240-token screen still collapses to ~50-ish SQLite queries, not
     *  240. Each resolved pair patches every cache entry that uses it —
     *  fixes both the wrong-reading case (replaces kuromoji's surface
     *  reading with JMdict's lemma reading) and the missing-reading case
     *  (fills in what tokenizeWithSurfaces left null). Reader (onDragMove)
     *  re-reads the cache on every tick so the label updates in place as
     *  Phase 2 progresses.
     *
     *  Re-throws [CancellationException] before the generic catch so a
     *  cancelled drag's coroutine actually exits without overwriting
     *  [lineTokensCache] with stale data — without the explicit re-throw,
     *  the catch (Exception) at the bottom would swallow cancellation
     *  silently, the loop would run to completion, and the assignment at
     *  the end would clobber the next drag's reset. */
    private suspend fun pretokenizeLines(lines: List<OcrManager.OcrLine>) {
        val engine = SourceLanguageEngines.get(context, Prefs(context).sourceLangId)
        val cache = mutableMapOf<String, List<LabelToken>>()

        // Phase 1: kuromoji-only pass.
        for (line in lines) {
            if (line.text.isEmpty() || cache.containsKey(line.text)) continue
            try {
                val results = engine.tokenize(line.text)
                val labels = mutableListOf<LabelToken>()
                var pos = 0
                for (r in results) {
                    val idx = line.text.indexOf(r.surface, pos)
                    if (idx < 0) continue
                    // Same "show reading when it adds info" gate the
                    // popup applies internally: drop blanks, drop reading
                    // equal to the word, drop readings for words with no
                    // kanji (kuromoji can return a katakana reading for
                    // a hiragana word, etc.).
                    val reading = r.reading?.takeIf { readingAddsInfo(r.lookupForm, it) }
                    labels += LabelToken(
                        surface = r.surface,
                        lookupForm = r.lookupForm,
                        reading = reading,
                        charOffset = idx,
                    )
                    pos = idx + r.surface.length
                }
                cache[line.text] = labels
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "pretokenize phase 1 failed for line: ${e.message}")
            }
            // Publish progressively so labels for earlier lines become
            // hit-testable without waiting for the rest. Both reader
            // (onDragMove) and writer run on the controller's Main scope,
            // so the shared mutable map is safe to share by reference.
            lineTokensCache = cache
            // Re-evaluate at the finger's last position. If the user
            // stopped moving before this line's cache landed, this is
            // what arms the dwell timer that onDragMove couldn't.
            refreshLabelAndDwell()
        }

        // Phase 2: per-unique-(lookupForm, reading) canonicalization.
        // Keying on the pair — not the form alone — lets the lookup pass
        // kuromoji's reading as a hint so a homograph kanji resolves to
        // the matching entry instead of the top reading-blind one.
        val uniqueKeys = LinkedHashSet<Pair<String, String?>>()
        for (tokens in cache.values) for (t in tokens) uniqueKeys.add(t.lookupForm to t.reading)
        for ((form, hintReading) in uniqueKeys) {
            val (canonicalWord, canonicalReading) = try {
                val head = engine.lookup(form, hintReading)?.entries?.firstOrNull()
                    ?.headwords?.firstOrNull()
                if (head != null) (head.written ?: head.reading ?: form) to head.reading
                else continue  // not in dict — leave Phase 1 entry as-is
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "pretokenize phase 2 failed for $form [$hintReading]: ${e.message}")
                continue
            }
            val gatedReading = canonicalReading?.takeIf { readingAddsInfo(canonicalWord, it) }
            for ((lineText, tokens) in cache.toMap()) {
                var dirty = false
                val patched = tokens.map { t ->
                    if (t.lookupForm == form && t.reading == hintReading &&
                        (t.lookupForm != canonicalWord || t.reading != gatedReading)
                    ) {
                        dirty = true
                        t.copy(lookupForm = canonicalWord, reading = gatedReading)
                    } else t
                }
                if (dirty) cache[lineText] = patched
            }
            lineTokensCache = cache
            // Phase 2 patches the label's lookupForm/reading; refresh so
            // the visible label upgrades to the canonical form mid-drag.
            refreshLabelAndDwell()
        }
    }

    /** True when [reading] adds information beyond [word] — non-blank,
     *  not redundant, and the word actually contains kanji that the
     *  reading clarifies. Mirrors the popup's intent (`reading != word`
     *  on the TextView side) and the cache's hasKanji gate so both
     *  paths converge on the same display rule. */
    private fun readingAddsInfo(word: String, reading: String): Boolean =
        reading.isNotBlank() && reading != word && hasKanji(word)

    /** Called on ACTION_UP. Returns true if the icon should restore to its
     *  saved position (the lens is about to settle into sticky mode with
     *  definitions). Returns false when the drag ended cleanly with no
     *  word target (lens torn down, icon snaps to edge).
     *
     *  Release-commit: instead of opening a separate popup window, the
     *  lens stays up and is promoted to sticky mode (focusable, touchable,
     *  outside-watch) with the definitions panel rendered in place of the
     *  zoom. If the lookup misses, the lens dismisses and its onDismiss
     *  fires [onSettled] so the service can restore live mode. */
    fun onDragEnd(): Boolean {
        dragInProgress = false
        lensRevealed = false
        handler.removeCallbacks(dwellRunnable)
        dwellLookupJob?.cancel()
        dwellLookupJob = null
        dwellScheduled = false
        // NOTE: do NOT call magnifier.dismiss() unconditionally — the lens
        // may transition to STICKY mode below if the release is over a
        // word with a successful lookup.
        ocrJob?.cancel()
        lookupJob?.cancel()
        // handOffDragBitmap is deferred until the lens settles (definitions
        // shown OR dismissed). Calling it here would clear the lens's
        // bitmap immediately, causing a transparent flash in the zoom
        // region during the lookup gap (~100 ms). Keeping the screenshot
        // visible until the definitions panel renders is purely cosmetic
        // — the bitmap will still be recycled on every exit path below.

        val lines = ocrLines ?: run {
            // OCR didn't finish in time. Drag yielded nothing — lens
            // dismissal fires onSettled via onDismiss.
            magnifier.dismiss()
            handOffDragBitmap()
            return false
        }
        val hitLine = findLineAt(lastX.toInt(), lastY.toInt(), lines) ?: run {
            // Released somewhere with no text under the finger.
            magnifier.dismiss()
            handOffDragBitmap()
            return false
        }

        // Reuse a still-fresh dwell result if the release happens on the
        // same token we already looked up — saves an entire tokenize +
        // dictionary round-trip.
        val releaseHit = detectLabelTokenAt(lastX.toInt(), lastY.toInt())
        val cachedKey = dwellResult?.first
        val releaseKey = releaseHit?.let { DwellKey(it.line.text, it.token.charOffset) }
        val cachedData = if (cachedKey != null && cachedKey == releaseKey) dwellResult?.second else null

        // Cache miss → the dictionary lookup is about to run async (~100–
        // 300 ms). Flip the lens to LOADING so the user sees an immediate
        // "lookup is running" cue (filled panel + spinner) instead of the
        // unchanged zoom view. Cache hits skip this — the `setDefinitions`
        // call inside the launch resolves on the next main-thread tick, so
        // a flash of LOADING would be visually noisy.
        if (cachedData == null) {
            magnifier.setLoading(
                releaseHit?.token?.lookupForm,
                releaseHit?.token?.reading,
            )
        }

        Log.d(TAG, "Lift-time lookup at (${lastX.toInt()}, ${lastY.toInt()}), " +
            "line: ${hitLine.text}, cached=${cachedData != null}")
        lookupJob = scope.launch {
            try {
                val resolved = if (cachedData != null) {
                    cachedData to cachedData.machineTranslatedLabel()
                } else {
                    resolveLookupData(lastX.toInt(), lastY.toInt(), lines, isDwell = false)
                }
                if (resolved == null) {
                    withContext(Dispatchers.Main) {
                        magnifier.dismiss()
                        handOffDragBitmap()
                    }
                    return@launch
                }
                val (popupData, label) = resolved
                // Release-only side effects (only when lookup succeeded).
                lastWord = popupData.word
                currentEntry = popupData.entry
                currentSentence?.let { sent ->
                    if (sent != lastSentSentence) {
                        lastSentSentence = sent
                        sendLineToMainApp(sent)
                    }
                }
                withContext(Dispatchers.Main) {
                    magnifier.setDefinitions(popupData.toLensData(), label)
                    magnifier.makeInteractive()
                    // Lens is now in DEFINITIONS mode — the zoom no longer
                    // renders, so the bitmap can be released.
                    handOffDragBitmap()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Lift-time lookup failed", e)
                withContext(Dispatchers.Main) {
                    magnifier.dismiss()
                    handOffDragBitmap()
                }
            }
        }
        return true
    }

    /**
     * Tear everything down: OCR / lookup jobs, dwell timer, magnifier
     * (the drag-flow surface). Used both by [cancelDrag] (system gesture
     * cancellation) and by external callers (e.g. game button press
     * dismissing an open lens). magnifier.dismiss() fires the controller's
     * onDismiss handler, which fires [onSettled] — so this method does
     * NOT call onSettled directly. Clears dragInProgress *before* the
     * dismiss so the dismiss handler takes the post-drag branch.
     */
    fun dismiss() {
        dragInProgress = false
        lensRevealed = false
        ocrJob?.cancel()
        lookupJob?.cancel()
        handler.removeCallbacks(dwellRunnable)
        dwellLookupJob?.cancel()
        dwellScheduled = false
        magnifier.dismiss()
        handOffDragBitmap()
        // Popup is not the drag-flow surface anymore, but other callers
        // (TranslationResultFragment) might have it up — leave them alone
        // unless this controller's destroy specifically tears down.
        ocrLines = null
    }

    /** Called on ACTION_CANCEL while a drag was active. Same teardown as
     *  [dismiss]; the name signals intent for the icon's wiring. */
    fun cancelDrag() = dismiss()

    fun destroy() {
        // Clear dragInProgress BEFORE magnifier.dismiss so the lens-dismiss
        // handler fires onSettled (the post-drag branch). Otherwise a
        // destroy mid-drag with a sticky lens up would suppress settle.
        dragInProgress = false
        lensRevealed = false
        ocrJob?.cancel()
        lookupJob?.cancel()
        handler.removeCallbacks(dwellRunnable)
        dwellLookupJob?.cancel()
        dwellScheduled = false
        magnifier.dismiss()
        handOffDragBitmap()
        // Best-effort dismiss for any non-drag popup still attached
        // (TranslationResultFragment owns those). Benign no-op otherwise.
        popup.dismiss()
        ocrLines = null
        scope.cancel()
    }

    private fun attachDragBitmap(bitmap: Bitmap, path: String?) {
        // No prior bitmap should exist by this point — onDragStart handed
        // off the previous drag's bitmap before launching a new OCR job —
        // but defend against the unlikely case rather than leak the prior.
        handOffDragBitmap()
        dragBitmap = bitmap
        screenshotPath = path
        magnifier.setBitmap(bitmap)
        if (!dragInProgress) {
            // Drag ended between screenshot capture and this callback (user
            // released before OCR even produced a bitmap). Hand off again so
            // the bitmap is recycled when ocrJob exits — ML Kit may still be
            // reading the local `bitmap` reference, but invokeOnCompletion
            // only fires after the worker actually returns. Without this,
            // dragBitmap leaks until the next drag or controller destroy.
            handOffDragBitmap()
        }
    }

    /** Detach the current bitmap from the magnifier and schedule its recycle
     *  for when the OCR job that may still be reading it completes.
     *
     *  ML Kit text recognition runs on a worker thread and is not cancellable
     *  at the native layer — `Job.cancel()` only marks the coroutine, but
     *  ML Kit keeps the bitmap in use until its `process` call returns and
     *  the coroutine body finally exits. invokeOnCompletion fires at that
     *  exit point (whether normal completion or post-cancellation), so the
     *  recycle is safely serialized after the worker is done. */
    private fun handOffDragBitmap() {
        val bitmap = dragBitmap ?: return
        val job = ocrJob
        dragBitmap = null
        magnifier.setBitmap(null)
        if (job == null || job.isCompleted) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return
        }
        job.invokeOnCompletion {
            handler.post { if (!bitmap.isRecycled) bitmap.recycle() }
        }
    }

    // ── Internals ────────────────────────────────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun captureAndOcr() {
        Log.d(TAG, "Taking screenshot for full-screen OCR...")

        val bitmap = withTimeoutOrNull(3000L) {
            CaptureBackendResolver.active().captureSource?.requestClean(displayId)
        }
        // The clean frame was captured with the lens off-screen — now it's
        // safe to bring the lens up (capture-before-reveal). Reveal even on
        // failure so the drag still gets its UI, matching the old placeholder
        // behaviour (there's just no bitmap to zoom). Job-identity guard (same
        // as the spinner clears below): a previous drag's late-returning
        // capture must not reveal the lens into THIS drag's in-flight clean
        // capture.
        if (ocrJob === coroutineContext[Job]) revealLensAfterCapture()
        if (bitmap == null) {
            Log.w(TAG, "Screenshot failed or timed out")
            return
        }

        val savedPath = withContext(Dispatchers.IO) { saveScreenshot(bitmap) }
        onScreenshotCaptured(bitmap, savedPath)

        val lines = withContext(Dispatchers.Default) {
            ocrManager.recogniseWithPositions(bitmap, Prefs(context).sourceLang)
        }
        if (lines == null) {
            Log.d(TAG, "No text found on screen")
            return
        }
        Log.d(TAG, "OCR found ${lines.size} lines")
        // See ocrFromFile — publish ocrLines first so a quick release
        // doesn't get gated on the per-line pretokenization pass.
        ocrLines = lines
        // Same spinner boundary as ocrFromFile — see comment there.
        if (ocrJob === coroutineContext[Job]) {
            magnifier.setPillLoading(false)
        }
        pretokenizeLines(lines)
    }

    /** Run on the main thread once the drag-start bitmap is in hand. The
     *  magnifier window already exists (shown immediately at drag start);
     *  attaching the bitmap swaps it from a placeholder to the zoomed view. */
    private fun onScreenshotCaptured(bitmap: Bitmap, savedPath: String?) {
        attachDragBitmap(bitmap, savedPath)
    }

    /**
     * Tokenizes the line under (fingerX, fingerY), runs the dictionary
     * lookup, and returns a (PopupData, label) pair ready to feed into
     * the lens — or null if no word can be resolved.
     *
     * Idempotent side effects (`prefetchWordLookups`, `currentSentence`
     * write) run in both dwell and release branches. Release-only side
     * effects (`lastWord`, `currentEntry`, `sendLineToMainApp`) live in
     * the [onDragEnd] caller, NOT here — running them on dwell would push
     * sentence intents to MainActivity before the user committed by
     * releasing.
     */
    private suspend fun resolveLookupData(
        fingerX: Int,
        fingerY: Int,
        lines: List<OcrManager.OcrLine>,
        @Suppress("UNUSED_PARAMETER") isDwell: Boolean,
    ): Pair<PopupData, String?>? {
        // Find the line the finger is over
        val hitLine = findLineAt(fingerX, fingerY, lines)

        if (hitLine == null) {
            Log.d(TAG, "No line near ($fingerX, $fingerY)")
            return null
        }

        Log.d(TAG, "Hit line: \"${hitLine.text}\" at ($fingerX, $fingerY)")

        val lineText = hitLine.text
        val isVertical = hitLine.orientation == com.playtranslate.language.TextOrientation.VERTICAL
        // For vertical text, characters stack along the height; for horizontal, along the width.
        val lineExtent = if (isVertical) hitLine.bounds.height().toFloat() else hitLine.bounds.width().toFloat()
        val charExtent = lineExtent / lineText.length

        // Tokenize the line (surface spans for position mapping, lookup forms for dictionary)
        val engine = SourceLanguageEngines.get(context, Prefs(context).sourceLangId)
        val tokenResults = engine.tokenize(lineText)

        if (tokenResults.isEmpty()) return null

        // Find the token whose screen position is closest to the finger.
        // For vertical text, match along the Y axis; for horizontal, along X.
        val surfaceTokens = tokenResults.map { it.surface }
        val tokenMatch = findClosestToken(
            lineText = lineText,
            tokens = surfaceTokens,
            fingerPos = if (isVertical) fingerY else fingerX,
            symbols = hitLine.symbols,
            fallbackLineStart = if (isVertical) hitLine.bounds.top else hitLine.bounds.left,
            fallbackCharExtent = charExtent,
            vertical = isVertical,
        )
        if (tokenMatch == null) return null

        val matchedSurface = tokenMatch.first
        val matchedIdx = tokenMatch.second
        // Walk tokenResults the same way findClosestToken did and pick the
        // one whose start position equals matchedIdx. Disambiguates lines
        // where the same surface appears multiple times with potentially
        // different context-dependent lemmas.
        val matchedToken: com.playtranslate.language.TokenSpan? = run {
            var pos = 0
            for (t in tokenResults) {
                val idx = lineText.indexOf(t.surface, pos)
                if (idx < 0) continue
                if (idx == matchedIdx) return@run t
                pos = idx + t.surface.length
            }
            null
        }
        val lookupForm = matchedToken?.lookupForm ?: matchedSurface

        // Dictionary lookup using the base/dictionary form + reading hint
        val prefs = Prefs(context)
        val targetGlossDb = TargetGlossDatabaseProvider.get(context, prefs.targetLang)
        val mlKitTranslator = TranslationManagerProvider.get(engine.profile.translationCode, prefs.targetLang)
        val resolver = DefinitionResolver(engine, targetGlossDb,
            mlKitTranslator?.let { WordTranslator(it::translate) }, prefs.targetLang,
            DefinitionGlossTranslators.forTarget(prefs.targetLang),
            ChineseScriptConverter.forTarget(prefs.targetLang, prefs.targetChineseVariant))
        val defResult = withContext(Dispatchers.IO) { resolver.lookup(lookupForm, matchedToken?.reading) }
        val response = defResult?.response
        // Wiktionary source packs split each POS section into its own entry;
        // [primary] drives the popup's word/reading/freq fields while
        // [flatSenses] feeds the sense rows so multi-POS headwords (e.g.
        // English "man" — noun + verb) don't lose senses. JMdict (single
        // entry per surface) flatSenses == primary.senses, so behavior is
        // unchanged for JA.
        val entries = response?.entries.orEmpty()
        val entry = entries.firstOrNull()
        val flatSenses = entries.flatMap { it.senses }

        // Build popup data based on DefinitionResult tier.
        val reading = matchedToken?.reading
        val popupData: PopupData = when {
            entry != null && defResult is DefinitionResult.Native -> {
                val display = entry.headwordDisplay(entry.headwords.firstOrNull(), matchedSurface)
                // Target-driven for non-English targets: render the pack's
                // sense list directly, no JMdict-position alignment (which
                // is unrecoverable — see WordDetailBottomSheet for full
                // explanation). For English targets, keep the by-ordinal
                // alignment using English glosses + per-sense MT fallback.
                val targetSenses = defResult.targetSenses.sortedBy { it.senseOrd }
                val isTargetDriven = prefs.targetLang != "en" && targetSenses.isNotEmpty()
                val senses = if (isTargetDriven) {
                    // Blank-pos target rows (PanLex) inherit the source-
                    // entry POS only when entries agree; multi-POS source
                    // (e.g. "surprise" → noun + verb + intj) yields an
                    // empty fallback so we don't mislabel cells.
                    val fallbackPos = com.playtranslate.model.unambiguousFallbackPos(entries)
                        .joinToString(", ")
                    targetSenses.map { target ->
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
                    // English-target or empty-targetSenses defensive path —
                    // flat-sense ordinals across all entries, no MT bridge
                    // (Native no longer carries one).
                    val targetByOrd = targetSenses.associateBy { it.senseOrd }
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
                PopupData(
                    word = display.written,
                    reading = display.reading,
                    senses = senses,
                    freqScore = entry.freqScore,
                    isCommon = entry.isCommon == true,
                    entry = entry
                )
            }
            entry != null && defResult is DefinitionResult.MachineTranslated -> {
                val display = entry.headwordDisplay(entry.headwords.firstOrNull(), matchedSurface)
                val defs = defResult.translatedDefinitions
                PopupData(
                    word = display.written,
                    reading = display.reading,
                    senses = if (defs != null) {
                        // Translated definitions available — show them directly
                        flatSenses.mapIndexed { i, sense ->
                            WordLookupPopup.SenseDisplay(
                                pos = sense.partsOfSpeech.joinToString(", "),
                                definition = defs.getOrElse(i) { sense.targetDefinitions.joinToString("; ") }
                            )
                        }
                    } else {
                        // No translated definitions — headword + English context
                        buildList {
                            add(WordLookupPopup.SenseDisplay(pos = "", definition = defResult.translatedHeadword))
                            flatSenses.forEach { sense ->
                                add(WordLookupPopup.SenseDisplay(
                                    pos = sense.partsOfSpeech.joinToString(", "),
                                    definition = sense.targetDefinitions.joinToString("; ")
                                ))
                            }
                        }
                    },
                    freqScore = entry.freqScore,
                    isCommon = entry.isCommon == true,
                    entry = entry,
                    machineTranslated = true
                )
            }
            entry != null && defResult is DefinitionResult.EnglishFallback && defResult.translatedDefinitions != null -> {
                // Translated definitions without headword translation
                val display = entry.headwordDisplay(entry.headwords.firstOrNull(), matchedSurface)
                val defs = defResult.translatedDefinitions
                PopupData(
                    word = display.written,
                    reading = display.reading,
                    senses = flatSenses.mapIndexed { i, sense ->
                        WordLookupPopup.SenseDisplay(
                            pos = sense.partsOfSpeech.joinToString(", "),
                            definition = defs.getOrElse(i) { sense.targetDefinitions.joinToString("; ") }
                        )
                    },
                    freqScore = entry.freqScore,
                    isCommon = entry.isCommon == true,
                    entry = entry,
                    machineTranslated = true
                )
            }
            entry != null -> {
                // EnglishFallback with no translations — show English as-is
                val display = entry.headwordDisplay(entry.headwords.firstOrNull(), matchedSurface)
                PopupData(
                    word = display.written,
                    reading = display.reading,
                    senses = flatSenses.map { sense ->
                        WordLookupPopup.SenseDisplay(
                            pos = sense.partsOfSpeech.joinToString(", "),
                            definition = sense.targetDefinitions.joinToString("; ")
                        )
                    },
                    freqScore = entry.freqScore,
                    isCommon = entry.isCommon == true,
                    entry = entry
                )
            }
            !reading.isNullOrEmpty() -> {
                PopupData(
                    word = lookupForm,
                    reading = reading,
                    senses = listOf(
                        WordLookupPopup.SenseDisplay(
                            pos = "",
                            definition = "Not in dictionary, may be a name"
                        )
                    ),
                    freqScore = 0,
                    isCommon = false,
                    entry = null
                )
            }
            else -> return null
        }

        Log.d(TAG, "Found: $matchedSurface ($lookupForm) → ${entry?.slug ?: "(fallback)"}")

        // Sentence + cache prefetch — idempotent on dwell, so safe to run
        // here. The release-only side effects (lastWord, currentEntry,
        // sendLineToMainApp) live in onDragEnd, NOT here.
        val groupText = hitLine.groupText
        val sentence = extractSentence(groupText, hitLine.text, matchedSurface, matchedIdx)
        currentSentence = sentence
        prefetchWordLookups(sentence)

        return popupData to popupData.machineTranslatedLabel()
    }

    /** Convert the controller's popup-shaped data into the lens's data
     *  class. The lens doesn't carry `entry` or `machineTranslated`; the
     *  latter becomes the [machineTranslatedLabel]. The reading is run
     *  through the same [readingAddsInfo] gate the cache uses so the
     *  lens shows or hides furigana consistently across drag (cache-fed)
     *  and dwell/release (lookup-fed) paths. */
    private fun PopupData.toLensData(): MagnifierLens.LensDefinitionData =
        MagnifierLens.LensDefinitionData(
            word = word,
            reading = reading?.takeIf { readingAddsInfo(word, it) },
            senses = senses,
            freqScore = freqScore,
            isCommon = isCommon,
        )

    private fun PopupData.machineTranslatedLabel(): String? =
        if (machineTranslated) MACHINE_TRANSLATED_LABEL else null

    /** Resolved data for a single showPopup call — either a real JMdict entry
     *  or a reading-only fallback for tokens missing from the dictionary. */
    private data class PopupData(
        val word: String,
        val reading: String?,
        val senses: List<WordLookupPopup.SenseDisplay>,
        val freqScore: Int,
        val isCommon: Boolean,
        val entry: DictionaryEntry?,
        val machineTranslated: Boolean = false
    )

    private fun findLineAt(x: Int, y: Int, lines: List<OcrManager.OcrLine>): OcrManager.OcrLine? {
        // Try progressively wider search areas
        val tiers = arrayOf(
            intArrayOf(HIT_EXPAND_X_PX_1, HIT_EXPAND_Y_PX_1),
            intArrayOf(HIT_EXPAND_X_PX_2, HIT_EXPAND_Y_PX_2),
            intArrayOf(HIT_EXPAND_X_PX_3, HIT_EXPAND_Y_PX_3)
        )
        for ((expandX, expandY) in tiers) {
            var bestLine: OcrManager.OcrLine? = null
            var bestDist = Long.MAX_VALUE
            for (line in lines) {
                val expanded = Rect(line.bounds).apply {
                    top -= expandY
                    bottom += expandY
                    left -= expandX
                    right += expandX
                }
                if (!expanded.contains(x, y)) continue
                val cx = line.bounds.centerX()
                val cy = line.bounds.centerY()
                val dx = (x - cx).toLong()
                val dy = (y - cy).toLong()
                // Weight the cross-axis distance 3× to prefer the line/column
                // the finger is on. For horizontal text, weight vertical; for
                // vertical columns, weight horizontal.
                val isVertical = line.orientation == com.playtranslate.language.TextOrientation.VERTICAL
                val dist = if (isVertical) dx * dx * 9 + dy * dy
                           else dx * dx + dy * dy * 9
                if (dist < bestDist) {
                    bestDist = dist
                    bestLine = line
                }
            }
            if (bestLine != null) return bestLine
        }
        return null
    }


    /**
     * Extracts the sentence containing [word] from the combined [groupText].
     * Splits on sentence-ending punctuation (.!?…。！？) and finds the sentence
     * that contains the word at its position within [lineText] at [wordIdxInLine].
     */
    private fun extractSentence(
        groupText: String,
        lineText: String,
        word: String,
        wordIdxInLine: Int
    ): String {
        // Find where the line text appears in the group text
        val lineStart = groupText.indexOf(lineText)
        if (lineStart < 0) return groupText  // fallback: return full group

        // Absolute position of the word in the group text
        val wordPos = lineStart + wordIdxInLine

        // Find sentence boundaries by scanning for sentence-ending punctuation
        var sentenceStart = 0
        for (i in wordPos - 1 downTo 0) {
            if (groupText[i] in SENTENCE_END_PUNCTUATION) {
                sentenceStart = i + 1
                break
            }
        }

        var sentenceEnd = groupText.length
        for (i in wordPos until groupText.length) {
            if (groupText[i] in SENTENCE_END_PUNCTUATION) {
                sentenceEnd = i + 1  // include the punctuation
                break
            }
        }

        return groupText.substring(sentenceStart, sentenceEnd).trim()
    }

    private fun prefetchWordLookups(sentence: String) {
        // Fire-and-forget; the cache owns the in-flight Deferred and
        // the staleness gate. Cancelling the previous job is no longer
        // our concern — LastSentenceCache.awaitOrStartWordLookups flips
        // its own `original` and cancels stale pending jobs when the
        // sentence changes.
        wordLookupJob?.cancel()
        wordLookupJob = scope.launch {
            LastSentenceCache.awaitOrStartWordLookups(context, sentence)
        }
    }

    private fun openSentenceInApp() {
        val sentence = currentSentence ?: return
        // Capture word context BEFORE magnifier.dismiss() — the lens's
        // onDismiss handler nulls lastWord and currentEntry, so any read
        // after dismiss returns null and the intent loses EXTRA_DRAG_WORD,
        // which is what the activity uses to decide whether to show the
        // Sentence/Word pill toggle.
        val word = lastWord
        val headword = currentEntry?.headwords?.firstOrNull()
        val reading = headword?.reading?.takeIf { it != headword.written }
        // Snapshot translation AND wordResults from the cache before
        // dismissing the lens — magnifier.dismiss() resumes live mode,
        // which can race a fresh capture cycle and stomp this cache
        // before TranslationResultActivity's onCreate runs. By copying
        // into intent extras here, the activity sees a launch-scoped
        // snapshot that nothing else can overwrite.
        val cached = LastSentenceCache.takeIf { it.original == sentence }
        val cachedTranslation = cached?.translation
        val cachedTranslationSource = cached?.translationSource
        val cachedWordResults = cached?.wordResults?.takeIf { it.isNotEmpty() }
        // Tell the service to clear the drag-flow's pause obligation if
        // the detail view will cover the live-mode surface. The service
        // decides based on the same "effectively single-screen" predicate
        // it uses elsewhere — keeping the policy in one place so this
        // path can't drift from the routing logic in resumeLiveMode and
        // friends. Dual-screen with the in-app panel visible leaves
        // auto-resume intact since TRA lands separately from live mode.
        CaptureBackendResolver.activeOverlayUi?.cancelLivePauseObligation()
        // Tear down the lens (sticky drag-flow surface) before launching
        // the activity. Lens dismiss → onDismiss → onSettled, which is
        // what the service expects post-drag.
        magnifier.dismiss()
        // Finish any previously launched TranslationResultActivity so the
        // new launch fully replaces it visually (otherwise FLAG_ACTIVITY_MULTIPLE_TASK
        // leaves it alive in a hidden task).
        TranslationResultActivity.finishCurrentIfAny()
        val intent = Intent(context, TranslationResultActivity::class.java).apply {
            // MULTIPLE_TASK prevents Android from reusing the previous
            // activity's task and migrating it (and its stale content)
            // onto a different display — the bug repro on dual-screen.
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            putExtra(TranslationResultActivity.EXTRA_SENTENCE_TEXT, sentence)
            putExtra(TranslationResultActivity.EXTRA_SCREENSHOT_PATH, screenshotPath)
            // Word context — when present, the activity surfaces the
            // Sentence/Word pill toggle in the toolbar.
            word?.let { putExtra(TranslationResultActivity.EXTRA_DRAG_WORD, it) }
            if (!reading.isNullOrEmpty()) {
                putExtra(TranslationResultActivity.EXTRA_DRAG_READING, reading)
            }
            cachedTranslation?.let {
                putExtra(TranslationResultActivity.EXTRA_DRAG_SENTENCE_TRANSLATION, it)
            }
            cachedTranslationSource?.let {
                putExtra(TranslationResultActivity.EXTRA_DRAG_SENTENCE_TRANSLATION_SOURCE, it)
            }
            cachedWordResults?.let { wr ->
                putExtra(TranslationResultActivity.EXTRA_DRAG_SENTENCE_WORDS,
                    wr.keys.toTypedArray())
                putExtra(TranslationResultActivity.EXTRA_DRAG_SENTENCE_READINGS,
                    wr.values.map { it.first }.toTypedArray())
                putExtra(TranslationResultActivity.EXTRA_DRAG_SENTENCE_MEANINGS,
                    wr.values.map { it.second }.toTypedArray())
                putExtra(TranslationResultActivity.EXTRA_DRAG_SENTENCE_FREQ_SCORES,
                    wr.values.map { it.third }.toIntArray())
            }
        }
        // Always pass an explicit launch display:
        //   - When any PlayTranslate activity is resumed, route onto its
        //     display so the new activity replaces it visually (the user
        //     is already looking there).
        //   - When nothing is resumed, fall back to the icon's display so
        //     the activity doesn't land on DEFAULT_DISPLAY on dual-screen
        //     setups.
        // The previous `if (!MainActivity.isInForeground)` gate only
        // tracked MainActivity, so once an Anki/TRA activity was the
        // resumed one, the gate routed the next launch to the icon's
        // display — the dual-screen "moved to wrong display" bug.
        val targetDisplay = PlayTranslateApplication.foregroundDisplayId() ?: displayId
        val opts = android.app.ActivityOptions.makeBasic()
            .setLaunchDisplayId(targetDisplay)
            .toBundle()
        context.startActivity(intent, opts)
    }

    /**
     * Headless one-tap counterpart to [openAnkiReviewForLens]. When the
     * user has the one-tap pref on, the lens's Anki chip dismisses the
     * lens, fires off a [Context.oneTapSendWord] in [scope], and toasts
     * the result. Falls back to [openAnkiReviewForLens] (the existing
     * Activity launch) on any prerequisite failure so the user can
     * still resolve it from inside the sheet.
     *
     * Critically, the NeedsMapping recovery path launches the review
     * Activity from the pre-dismiss [LensAnkiSnapshot] — not via
     * [openAnkiReviewForLens] — because by the time the dispatcher
     * returns, the lens has been dismissed and `lastWord` /
     * `currentEntry` are null. Calling back into
     * [openAnkiReviewForLens] would silently return on those null
     * checks, leaving users with an unmapped custom card type stuck
     * with only the dispatcher's toast and no path to the mapping UI.
     */
    private fun oneTapFromLens() {
        val word = lastWord ?: return
        val entry = currentEntry ?: return
        val ankiManager = AnkiManager(context)
        if (!ankiManager.isAnkiDroidInstalled() || !ankiManager.hasPermission()) {
            openAnkiReviewForLens()
            return
        }
        val prefs = Prefs(context)
        if (prefs.ankiDeckId < 0L) {
            openAnkiReviewForLens()
            return
        }

        // Snapshot the lens's word context BEFORE dismiss (onDismiss
        // nulls lastWord / currentEntry) — same precaution
        // openAnkiReviewForLens takes. The snapshot is the only data
        // the Activity-launch fallback below can rely on.
        val snap = snapshotLensFieldsForAnki(word, entry)
        val sourceLangId = prefs.sourceLangId

        CaptureBackendResolver.activeOverlayUi?.cancelLivePauseObligation()
        magnifier.dismiss()
        // Initial "Adding to Anki…" toast so the user has feedback
        // while the send runs. Result toast follows on completion.
        Toast.makeText(
            context, R.string.anki_adding_in_progress, Toast.LENGTH_SHORT,
        ).show()
        scope.launch {
            // When the lens was dragged out of a sentence, the existing
            // sheet defaults to sentence mode with the dragged word
            // bolded (SentenceAnkiContentFragment.kt:227-231). Match
            // that default in one-tap: send a sentence card with the
            // target word selected. Without sentence context, fall
            // back to a word card.
            val sentence = snap.sentence
            val result = if (sentence != null) {
                context.oneTapSendSentence(
                    original = sentence,
                    translation = snap.sentenceTranslation,
                    wordsPayload = null,        // await the cache (atomic)
                    screenshotPath = snap.screenshotPath,
                    sourceLangId = sourceLangId,
                    targetWord = snap.word,
                )
            } else {
                context.oneTapSendWord(
                    word = snap.word,
                    reading = snap.reading,
                    pos = snap.pos,
                    fallbackDefinition = snap.definition,
                    freqScore = snap.freqScore,
                    screenshotPath = snap.screenshotPath,
                    sourceLangId = sourceLangId,
                )
            }
            when (result) {
                is AnkiSendResult.Success -> {
                    val msgRes = if (result.audioDropped || result.wordAudioDropped)
                        R.string.anki_added_no_audio
                    else
                        R.string.anki_added_success
                    Toast.makeText(context, msgRes, Toast.LENGTH_SHORT).show()
                }
                is AnkiSendResult.Failed -> {
                    Toast.makeText(context, result.messageRes, Toast.LENGTH_LONG).show()
                }
                is AnkiSendResult.NeedsMapping -> {
                    // Dispatcher already toasted "Configure fields…".
                    // Launch the review Activity from the snapshot —
                    // openAnkiReviewForLens reads now-null instance
                    // state and would no-op, stranding the user.
                    launchWordAnkiActivity(snap)
                }
            }
        }
    }

    /**
     * Launches the Anki word-review flow with the lens's current word
     * context. Snapshots state before dismissing the lens (onDismiss
     * nulls lastWord / currentEntry, so reads after dismiss would lose
     * the data). Gates on AnkiDroid being installed; [AnkiPermissionActivity]
     * handles the permission gate, then forwards to the review sheet.
     */
    private fun openAnkiReviewForLens() {
        val word = lastWord ?: return
        val entry = currentEntry ?: return

        val ankiManager = AnkiManager(context)
        if (!ankiManager.isAnkiDroidInstalled()) {
            // Service context — no Activity to attach to, so route the alert
            // through the capture-overlay path on the lens's display.
            showAnkiNotInstalledDialog(
                magnifier.rawCtx, overlayHost, magnifier.wm, displayId,
            )
            return
        }

        val snap = snapshotLensFieldsForAnki(word, entry)
        CaptureBackendResolver.activeOverlayUi?.cancelLivePauseObligation()
        magnifier.dismiss()
        launchWordAnkiActivity(snap)
    }

    /**
     * Snapshot of all the data needed to launch
     * [AnkiPermissionActivity] for this lens session, captured before
     * [magnifier.dismiss] is allowed to null the source-of-truth fields
     * (`lastWord`, `currentEntry`, `currentSentence`, `screenshotPath`).
     */
    private data class LensAnkiSnapshot(
        val word: String,
        val reading: String,
        val pos: String,
        val definition: String,
        val freqScore: Int,
        val screenshotPath: String?,
        val sentence: String?,
        val sentenceTranslation: String?,
        val sourceLangCode: String,
    )

    /** Build a [LensAnkiSnapshot] from the supplied word + entry plus
     *  the controller's current session fields. Call BEFORE
     *  [magnifier.dismiss]. */
    private fun snapshotLensFieldsForAnki(
        word: String, entry: DictionaryEntry,
    ): LensAnkiSnapshot {
        val primaryHeadword = entry.headwords.firstOrNull()
        val reading = primaryHeadword?.reading
            ?.takeIf { it != primaryHeadword.written } ?: ""
        val pos = entry.senses.firstOrNull()?.partsOfSpeech
            ?.filter { it.isNotBlank() }?.joinToString(" · ") ?: ""
        val nonEmptySenses = entry.senses.filter { it.targetDefinitions.isNotEmpty() }
        val definition = nonEmptySenses.mapIndexed { i, sense ->
            val prefix = if (nonEmptySenses.size > 1) "${i + 1}. " else ""
            prefix + sense.targetDefinitions.joinToString("; ")
        }.joinToString("\n")
        val sentence = currentSentence
        val sentenceTranslation = LastSentenceCache
            .takeIf { it.original == sentence }?.translation
        return LensAnkiSnapshot(
            word = word,
            reading = reading,
            pos = pos,
            definition = definition,
            freqScore = entry.freqScore,
            screenshotPath = screenshotPath,
            sentence = sentence,
            sentenceTranslation = sentenceTranslation,
            sourceLangCode = Prefs(context).sourceLangId.code,
        )
    }

    /**
     * Launches the [AnkiPermissionActivity] → [WordAnkiReviewActivity]
     * flow from a pre-captured [LensAnkiSnapshot]. Decoupled from
     * `lastWord` / `currentEntry` so the one-tap fallback path can
     * still launch the sheet after `magnifier.dismiss()` has cleared
     * those fields.
     */
    private fun launchWordAnkiActivity(snap: LensAnkiSnapshot) {
        // Finish any previously launched WordAnkiReviewActivity so the
        // new sheet visibly replaces the old one rather than stacking
        // behind it in a hidden task. Also cancel any in-flight
        // permission trampoline — otherwise a rapid second tap (e.g.
        // during a first-time permission flow) could let an older
        // trampoline forward its stale intent after the new sheet opens.
        WordAnkiReviewActivity.finishCurrentIfAny()
        AnkiPermissionActivity.finishCurrentIfAny()

        val intent = Intent(context, AnkiPermissionActivity::class.java).apply {
            // MULTIPLE_TASK — see openSentenceInApp for the rationale.
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            putExtra(WordAnkiReviewActivity.EXTRA_WORD, snap.word)
            putExtra(WordAnkiReviewActivity.EXTRA_READING, snap.reading)
            putExtra(WordAnkiReviewActivity.EXTRA_POS, snap.pos)
            putExtra(WordAnkiReviewActivity.EXTRA_DEFINITION, snap.definition)
            putExtra(WordAnkiReviewActivity.EXTRA_FREQ_SCORE, snap.freqScore)
            snap.screenshotPath?.let {
                putExtra(WordAnkiReviewActivity.EXTRA_SCREENSHOT_PATH, it)
            }
            snap.sentence?.let {
                putExtra(WordAnkiReviewActivity.EXTRA_SENTENCE_ORIGINAL, it)
            }
            snap.sentenceTranslation?.let {
                putExtra(WordAnkiReviewActivity.EXTRA_SENTENCE_TRANSLATION, it)
            }
            putExtra(WordAnkiReviewActivity.EXTRA_SOURCE_LANG, snap.sourceLangCode)
        }

        // Same routing rule as [openSentenceInApp]: prefer any resumed
        // PlayTranslate activity's display, fall back to the icon's display.
        val targetDisplay = PlayTranslateApplication.foregroundDisplayId() ?: displayId
        val opts = android.app.ActivityOptions.makeBasic()
            .setLaunchDisplayId(targetDisplay)
            .toBundle()
        context.startActivity(intent, opts)
    }

    private fun sendLineToMainApp(lineText: String) {
        if (Prefs.isSingleScreen(context)) return  // only in dual-screen mode
        if (!MainActivity.isInForeground) return    // don't foreground the app
        val intent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_DRAG_SENTENCE
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_DRAG_LINE_TEXT, lineText)
            putExtra(MainActivity.EXTRA_DRAG_SCREENSHOT_PATH, screenshotPath)
        }
        context.startActivity(intent)
    }

    private fun saveScreenshot(bitmap: Bitmap): String? {
        return try {
            val dir = File(context.cacheDir, "screenshots").apply { mkdirs() }
            val file = File(dir, "drag.jpg")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "saveScreenshot failed: ${e.message}")
            null
        }
    }
}
