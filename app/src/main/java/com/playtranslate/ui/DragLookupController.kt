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
import com.playtranslate.AnkiManager
import com.playtranslate.CaptureService
import com.playtranslate.MainActivity
import com.playtranslate.OcrManager
import com.playtranslate.PlayTranslateAccessibilityService
import com.playtranslate.Prefs
import com.playtranslate.language.DefinitionResolver
import com.playtranslate.language.DefinitionResult
import com.playtranslate.language.WordTranslator
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.language.TargetGlossDatabaseProvider
import com.playtranslate.language.TranslationManagerProvider
import com.playtranslate.model.DictionaryEntry
import kotlinx.coroutines.*
import java.io.File
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
    private val displayId: Int,
    private val popup: WordLookupPopup,
    private val magnifier: MagnifierLens
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
    /** Result of [detectWordAt]. */
    private data class WordReadout(val word: String, val reading: String?)
    private var lineTokensCache: Map<String, List<LabelToken>>? = null

    init {
        // Drag-popup always wears the "open in detail view" hat. Where
        // it leads depends on whether the main app is the active surface:
        //   - Dual-screen + MainActivity foregrounded → open the word
        //     detail sheet inside MainActivity (user is already looking
        //     at the app; don't launch a new activity).
        //   - Otherwise (single-screen or app backgrounded) → launch
        //     TranslationResultActivity with the sentence, which is the
        //     only way to surface the details when the app isn't visible.
        popup.showOpenButton = true
        popup.onOpenTap = {
            val service = PlayTranslateAccessibilityService.instance ?: popup.ctx
            if (!Prefs.isSingleScreen(service) && MainActivity.isInForeground) {
                openWordDetailInApp()
            } else {
                openSentenceInApp()
            }
        }
        // Popup only ever surfaces on release (see [onDragEnd]); dismissal
        // post-drag fires [onSettled] so the service can restore region
        // indicator + live mode. If a new drag starts and dismisses an
        // existing popup, dragInProgress is true at that moment and onSettled
        // is suppressed — drag2 wants the paused state and will fire its own
        // settle when its release-time popup is eventually dismissed.
        popup.onDismiss = {
            lastWord = null
            if (!dragInProgress) onSettled?.invoke()
        }
    }

    val isPopupShowing: Boolean get() = popup.isShowing

    companion object {
        private const val TAG = "DragLookup"
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

    private fun queryScreenSize(): Point {
        val wm = popup.ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return Point()
        val bounds = wm.currentWindowMetrics.bounds
        return Point(bounds.width(), bounds.height())
    }

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
        // doesn't surface a popup over this drag. Hand the previous drag's
        // bitmap off to its (now-cancelled) OCR job — Job.cancel() is
        // cooperative and ML Kit text recognition is non-cancellable at the
        // native layer, so the worker keeps the bitmap alive until it
        // returns; handOffDragBitmap waits via invokeOnCompletion.
        handOffDragBitmap()
        ocrJob?.cancel()
        lookupJob?.cancel()
        // dragInProgress=true BEFORE dismissing the stale popup so the
        // popup.onDismiss handler suppresses onSettled — drag2's onDragStart
        // wrapper has just paused live mode for this drag and we don't want
        // to immediately resume it.
        dragInProgress = true
        if (popup.isShowing) popup.dismiss()
        ocrLines = null
        lastSentSentence = null
        lineTokensCache = null
        // Show the magnifier immediately. It briefly displays a placeholder
        // pill until [onScreenshotCaptured] feeds in the bitmap (~50 ms).
        // The screenshot pipeline blanks every registered overlay window
        // during the actual capture, so showing the magnifier here doesn't
        // contaminate the captured pixels.
        val screen = queryScreenSize()
        magnifier.show(lastX.toInt(), lastY.toInt(), screen.x, screen.y)
        magnifier.setLabel(null, null)
        ocrJob = scope.launch {
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
        pretokenizeLines(lines)
    }

    /**
     * Called on every ACTION_MOVE during a drag. Magnifier follows the
     * finger and shows a small readout of the word currently under the
     * finger; the popup stays out of the way until release.
     */
    fun onDragMove(rawX: Float, rawY: Float) {
        lastX = rawX
        lastY = rawY
        val screen = queryScreenSize()
        magnifier.show(rawX.toInt(), rawY.toInt(), screen.x, screen.y)
        val readout = detectWordAt(rawX.toInt(), rawY.toInt())
        magnifier.setLabel(readout?.word, readout?.reading)
    }

    /** Synchronous "what's under the finger right now" — line hit-test +
     *  pre-tokenized cache + nearest-token. Returns the token's dictionary
     *  form (e.g. "住む" for "住んで") plus its reading (furigana/pinyin)
     *  for the lens panel, or null if no text is targeted, or the cache
     *  hasn't been populated yet by the OCR coroutine. */
    private fun detectWordAt(rawX: Int, rawY: Int): WordReadout? {
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
        // Disambiguate via character offset — the second of findClosestToken's
        // pair is the matched span's start in lineText. Without this, a line
        // with two occurrences of the same surface would always resolve to the
        // first occurrence's lookupForm even when the user aimed at the second.
        val matchedOffset = match.second
        val matched = tokens.firstOrNull { it.charOffset == matchedOffset }
        val word = matched?.lookupForm ?: match.first
        // reading is already filtered in pretokenizeLines (kanji check etc.).
        return WordReadout(word, matched?.reading)
    }

    /** Pre-tokenize every OCR line so [detectWordAt] can run synchronously
     *  during onDragMove. Called from the OCR coroutine after recognition
     *  completes (engine.tokenize is a suspend function).
     *
     *  Re-throws [CancellationException] before the generic catch so a
     *  cancelled drag's coroutine actually exits without overwriting
     *  [lineTokensCache] with stale data — without the explicit re-throw,
     *  the catch (Exception) at the bottom would swallow cancellation
     *  silently, the loop would run to completion, and the assignment at
     *  the end would clobber the next drag's reset. */
    private suspend fun pretokenizeLines(lines: List<OcrManager.OcrLine>) {
        val service = PlayTranslateAccessibilityService.instance ?: return
        val engine = SourceLanguageEngines.get(service, Prefs(service).sourceLangId)
        val cache = mutableMapOf<String, List<LabelToken>>()
        for (line in lines) {
            if (line.text.isEmpty() || cache.containsKey(line.text)) continue
            try {
                val results = engine.tokenize(line.text)
                val labels = mutableListOf<LabelToken>()
                var pos = 0
                for (r in results) {
                    val idx = line.text.indexOf(r.surface, pos)
                    if (idx < 0) continue
                    val word = r.lookupForm ?: r.surface
                    // Match the popup's "show reading when it adds info"
                    // intent: drop blanks, drop readings equal to the word
                    // (popup uses `reading != word`), and drop readings
                    // for words with no kanji — the popup is shielded
                    // from that via JMdict-stored headwords; we have to
                    // gate it ourselves on raw kuromoji output.
                    val reading = r.reading
                        ?.takeIf { it.isNotBlank() && it != word && hasKanji(word) }
                    labels += LabelToken(
                        surface = r.surface,
                        lookupForm = word,
                        reading = reading,
                        charOffset = idx,
                    )
                    pos = idx + r.surface.length
                }
                cache[line.text] = labels
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "pretokenize failed for line: ${e.message}")
            }
        }
        lineTokensCache = cache
    }

    /** Called on ACTION_UP. Returns true if the icon should restore to its
     *  saved position (popup is about to show via async lookup). Returns
     *  false when the drag ended cleanly with no popup target (icon snaps
     *  to edge).
     *
     *  Release-commit: this is the only path that surfaces the popup.
     *  Lookup is async; the icon restores immediately so the popup has a
     *  stable anchor when it appears. If the lookup misses, [onSettled]
     *  fires so the service can restore live mode / region overlay. */
    fun onDragEnd(): Boolean {
        dragInProgress = false
        magnifier.dismiss()
        // Cancel any pending OCR. If ocrLines is set the job is already
        // COMPLETED (no-op). Otherwise this stops wasted ML Kit work and
        // lets the orphan check in [attachDragBitmap] schedule a prompt
        // bitmap recycle.
        ocrJob?.cancel()
        lookupJob?.cancel()
        handOffDragBitmap()

        val lines = ocrLines ?: run {
            // OCR didn't finish in time. Drag yielded nothing.
            onSettled?.invoke()
            return false
        }
        val hitLine = findLineAt(lastX.toInt(), lastY.toInt(), lines) ?: run {
            // Released somewhere with no text under the finger.
            onSettled?.invoke()
            return false
        }

        Log.d(TAG, "Lift-time lookup at (${lastX.toInt()}, ${lastY.toInt()}), line: ${hitLine.text}")
        lookupJob = scope.launch {
            try {
                val showed = performLookup(lastX.toInt(), lastY.toInt(), lines, dismissOnMiss = false)
                if (!showed) onSettled?.invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Lift-time lookup failed", e)
                onSettled?.invoke()
            }
        }
        return true
    }

    /**
     * Tear everything down: OCR / lookup jobs, magnifier, popup. Used both
     * by [cancelDrag] (system gesture cancellation) and by external callers
     * (e.g. game button press dismissing an open popup). popup.dismiss()
     * fires the controller's onDismiss handler, which fires [onSettled] —
     * so this method does NOT call onSettled directly.
     */
    fun dismiss() {
        ocrJob?.cancel()
        lookupJob?.cancel()
        dragInProgress = false
        magnifier.dismiss()
        handOffDragBitmap()
        popup.dismiss()
        ocrLines = null
    }

    /** Called on ACTION_CANCEL while a drag was active. Same teardown as
     *  [dismiss]; the name signals intent for the icon's wiring. */
    fun cancelDrag() = dismiss()

    fun destroy() {
        // Clear dragInProgress BEFORE popup.dismiss so the popup-dismiss
        // handler fires onSettled (the post-drag branch). Otherwise a
        // destroy mid-drag with a popup somehow up would suppress settle.
        dragInProgress = false
        ocrJob?.cancel()
        lookupJob?.cancel()
        magnifier.dismiss()
        handOffDragBitmap()
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
        val service = PlayTranslateAccessibilityService.instance ?: return
        Log.d(TAG, "Taking screenshot for full-screen OCR...")

        val bitmap = withTimeoutOrNull(3000L) {
            service.screenshotManager?.requestClean(displayId)
        }
        if (bitmap == null) {
            Log.w(TAG, "Screenshot failed or timed out")
            return
        }

        val savedPath = withContext(Dispatchers.IO) { saveScreenshot(bitmap) }
        onScreenshotCaptured(bitmap, savedPath)

        val lines = withContext(Dispatchers.Default) {
            ocrManager.recogniseWithPositions(bitmap, Prefs(service).sourceLang)
        }
        if (lines == null) {
            Log.d(TAG, "No text found on screen")
            return
        }
        Log.d(TAG, "OCR found ${lines.size} lines")
        // See ocrFromFile — publish ocrLines first so a quick release
        // doesn't get gated on the per-line pretokenization pass.
        ocrLines = lines
        pretokenizeLines(lines)
    }

    /** Run on the main thread once the drag-start bitmap is in hand. The
     *  magnifier window already exists (shown immediately at drag start);
     *  attaching the bitmap swaps it from a placeholder to the zoomed view. */
    private fun onScreenshotCaptured(bitmap: Bitmap, savedPath: String?) {
        attachDragBitmap(bitmap, savedPath)
    }

    /**
     * Looks up the word at (fingerX, fingerY). If [dismissOnMiss] is true and no word
     * is found, dismisses the popup. Returns true if a word was found and shown.
     */
    private suspend fun performLookup(fingerX: Int, fingerY: Int, lines: List<OcrManager.OcrLine>, dismissOnMiss: Boolean = false): Boolean {
        val found = performLookupInner(fingerX, fingerY, lines)
        if (!found && dismissOnMiss) {
            withContext(Dispatchers.Main) {
                popup.dismiss()
                lastWord = null
            }
        }
        return found
    }

    private suspend fun performLookupInner(fingerX: Int, fingerY: Int, lines: List<OcrManager.OcrLine>): Boolean {
        // Find the line the finger is over
        val hitLine = findLineAt(fingerX, fingerY, lines)

        if (hitLine == null) {
            Log.d(TAG, "No line near ($fingerX, $fingerY)")
            return false
        }

        Log.d(TAG, "Hit line: \"${hitLine.text}\" at ($fingerX, $fingerY)")

        val lineText = hitLine.text
        val isVertical = hitLine.orientation == com.playtranslate.language.TextOrientation.VERTICAL
        // For vertical text, characters stack along the height; for horizontal, along the width.
        val lineExtent = if (isVertical) hitLine.bounds.height().toFloat() else hitLine.bounds.width().toFloat()
        val charExtent = lineExtent / lineText.length

        // Tokenize the line (surface spans for position mapping, lookup forms for dictionary)
        val service = PlayTranslateAccessibilityService.instance ?: return false
        val engine = SourceLanguageEngines.get(service, Prefs(service).sourceLangId)
        val tokenResults = engine.tokenize(lineText)

        if (tokenResults.isEmpty()) return false

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
        if (tokenMatch == null) return false

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

        // Skip if same word already showing (counts as "found")
        if (lookupForm == lastWord && popup.isShowing) return true

        // Dictionary lookup using the base/dictionary form + reading hint
        val prefs = Prefs(service)
        val targetGlossDb = TargetGlossDatabaseProvider.get(service, prefs.targetLang)
        val mlKitTranslator = TranslationManagerProvider.get(engine.profile.translationCode, prefs.targetLang)
        val enToTarget = TranslationManagerProvider.getEnToTarget(prefs.targetLang)
        val resolver = DefinitionResolver(engine, targetGlossDb,
            mlKitTranslator?.let { WordTranslator(it::translate) }, prefs.targetLang,
            enToTarget?.let { WordTranslator(it::translate) })
        val defResult = resolver.lookup(lookupForm, matchedToken?.reading)
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
                val form = entry.headwords.firstOrNull()
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
                    word = form?.written ?: form?.reading ?: entry.slug,
                    reading = form?.reading,
                    senses = senses,
                    freqScore = entry.freqScore,
                    isCommon = entry.isCommon == true,
                    entry = entry
                )
            }
            entry != null && defResult is DefinitionResult.MachineTranslated -> {
                val form = entry.headwords.firstOrNull()
                val defs = defResult.translatedDefinitions
                PopupData(
                    word = form?.written ?: form?.reading ?: entry.slug,
                    reading = form?.reading,
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
                val form = entry.headwords.firstOrNull()
                val defs = defResult.translatedDefinitions
                PopupData(
                    word = form?.written ?: form?.reading ?: entry.slug,
                    reading = form?.reading,
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
                val form = entry.headwords.firstOrNull()
                PopupData(
                    word = form?.written ?: form?.reading ?: entry.slug,
                    reading = form?.reading,
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
            else -> return false
        }

        // Estimate the word's center position along the text flow axis.
        val tokenCenterIdx = matchedIdx + matchedSurface.length / 2f
        val wordCenterX = if (isVertical) {
            // For vertical text, use the column's center X for popup positioning
            hitLine.bounds.centerX()
        } else {
            (hitLine.bounds.left + tokenCenterIdx * charExtent).toInt()
        }

        Log.d(TAG, "Found: $matchedSurface ($lookupForm) → ${entry?.slug ?: "(fallback)"}")
        lastWord = lookupForm

        // Use the pre-built group text (same combination logic as the main OCR pipeline)
        val groupText = hitLine.groupText
        val sentence = extractSentence(groupText, hitLine.text, matchedSurface, matchedIdx)

        currentSentence = sentence
        prefetchWordLookups(sentence)

        return withContext(Dispatchers.Main) {
            val popupShown = showPopup(popupData, wordCenterX, fingerY)
            if (sentence != lastSentSentence) {
                lastSentSentence = sentence
                sendLineToMainApp(sentence)
            }
            // Propagate add-failure so callers (lift-time lookup in onDragEnd)
            // don't strand the drag flow waiting on a popup that never attached.
            popupShown
        }
    }

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


    /** Returns true if the popup is attached after this call. */
    private fun showPopup(data: PopupData, fingerX: Int, fingerY: Int): Boolean {
        currentEntry = data.entry
        // Popup takes over the same real estate as the magnifier — hide the
        // lens so the two don't visually fight. If the user keeps dragging,
        // the popup-dismiss handler in [init] brings the magnifier back.
        magnifier.hide()

        val screen = queryScreenSize()
        return popup.show(
            word = data.word,
            reading = data.reading,
            senses = data.senses,
            freqScore = data.freqScore,
            isCommon = data.isCommon,
            screenX = fingerX,
            screenY = fingerY,
            screenW = screen.x,
            screenH = screen.y,
            label = if (data.machineTranslated) "⚠ Machine translated" else null
        )
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
        val cache = LastSentenceCache
        // Skip if the cache already has results for this exact sentence
        if (cache.original == sentence && cache.wordResults != null) return
        val service = PlayTranslateAccessibilityService.instance ?: return
        wordLookupJob?.cancel()
        wordLookupJob = scope.launch {
            val results = LastSentenceCache.lookupWords(service, sentence)
            // Only write cache if this sentence is still current
            if (currentSentence == sentence) {
                cache.original = sentence
                cache.translation = null  // clear stale translation from previous text
                cache.wordResults = results
            }
        }
    }

    private fun openSentenceInApp() {
        val sentence = currentSentence ?: return
        val service = PlayTranslateAccessibilityService.instance ?: return
        popup.dismiss()
        val intent = Intent(service, TranslationResultActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(TranslationResultActivity.EXTRA_SENTENCE_TEXT, sentence)
            putExtra(TranslationResultActivity.EXTRA_SCREENSHOT_PATH, screenshotPath)
        }
        service.startActivity(intent)
    }

    /**
     * Foreground the main app and open the word detail sheet over it.
     * Used when the user is already looking at MainActivity on a
     * dual-screen setup — launching TranslationResultActivity there would
     * shove a fresh full-screen activity on top of the app they just
     * paused to inspect a word.
     *
     * Needs at least the popup's currently-displayed word. Reading and
     * sentence context are best-effort — the detail sheet handles each
     * being null cleanly.
     */
    private fun openWordDetailInApp() {
        val word = popup.currentWord ?: return
        val service = PlayTranslateAccessibilityService.instance ?: return
        val reading = currentEntry?.headwords?.firstOrNull()
            ?.reading?.takeIf { it != currentEntry?.headwords?.firstOrNull()?.written }
        popup.dismiss()
        val intent = Intent(service, MainActivity::class.java).apply {
            action = MainActivity.ACTION_DRAG_WORD
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_DRAG_WORD, word)
            if (!reading.isNullOrEmpty()) {
                putExtra(MainActivity.EXTRA_DRAG_READING, reading)
            }
            screenshotPath?.let { putExtra(MainActivity.EXTRA_DRAG_SCREENSHOT_PATH, it) }
            currentSentence?.let { sent ->
                putExtra(MainActivity.EXTRA_DRAG_SENTENCE_ORIGINAL, sent)
                val cache = LastSentenceCache
                if (cache.original == sent && cache.translation != null) {
                    putExtra(MainActivity.EXTRA_DRAG_SENTENCE_TRANSLATION, cache.translation)
                }
            }
        }
        service.startActivity(intent)
    }

    private fun sendLineToMainApp(lineText: String) {
        val service = PlayTranslateAccessibilityService.instance ?: return
        if (Prefs.isSingleScreen(service)) return  // only in dual-screen mode
        if (!MainActivity.isInForeground) return    // don't foreground the app
        val intent = Intent(service, MainActivity::class.java).apply {
            action = MainActivity.ACTION_DRAG_SENTENCE
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_DRAG_LINE_TEXT, lineText)
            putExtra(MainActivity.EXTRA_DRAG_SCREENSHOT_PATH, screenshotPath)
        }
        service.startActivity(intent)
    }

    private fun saveScreenshot(bitmap: Bitmap): String? {
        val service = PlayTranslateAccessibilityService.instance ?: return null
        return try {
            val dir = File(service.cacheDir, "screenshots").apply { mkdirs() }
            val file = File(dir, "drag.jpg")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "saveScreenshot failed: ${e.message}")
            null
        }
    }
}
