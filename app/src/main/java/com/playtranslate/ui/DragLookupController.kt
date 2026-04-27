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
    /** True between [onDragStart] and [onDragEnd]. Used by [onPopupDismissed]
     *  to know whether a mid-drag dismissal should re-show the magnifier. */
    private var dragInProgress = false
    /** Most recent finger position seen by [onDragMove]. The release point
     *  is read from these in [onDragEnd] for the lift-time lookup. */
    private var lastX = 0f
    private var lastY = 0f

    // Hold-still detection with wobble tolerance
    private var anchorX = 0f
    private var anchorY = 0f
    private var holdTimerScheduled = false
    private var holdRetryCount = 0
    private val MAX_HOLD_RETRIES = 30

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
    }

    val isPopupShowing: Boolean get() = popup.isShowing

    companion object {
        private const val TAG = "DragLookup"
        private const val HOLD_STILL_MS = 200L
        /** Wobble radius — finger movement within this distance doesn't reset the timer. */
        private const val WOBBLE_RADIUS_DP = 8f
        /** Horizontal expansion around finger for line hit-testing (3 tiers). */
        private const val HIT_EXPAND_X_PX_1 = 80
        private const val HIT_EXPAND_Y_PX_1 = 30
        private const val HIT_EXPAND_X_PX_2 = 160
        private const val HIT_EXPAND_Y_PX_2 = 60
        private const val HIT_EXPAND_X_PX_3 = 300
        private const val HIT_EXPAND_Y_PX_3 = 100

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

    private val wobbleRadiusPx: Float by lazy {
        WOBBLE_RADIUS_DP * popup.ctx.resources.displayMetrics.density
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
     */
    fun onDragStart(existingScreenshotPath: String? = null) {
        ocrLines = null
        lastSentSentence = null
        ocrJob?.cancel()
        recycleDragBitmap()
        dragInProgress = true
        // Magnifier appears immediately with a placeholder; the bitmap lands
        // a few hundred ms later when [captureAndOcr] settles.
        val screen = queryScreenSize()
        magnifier.show(lastX.toInt(), lastY.toInt(), screen.x, screen.y)
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
        // Hand off to the magnifier before kicking off OCR — same lifetime
        // contract as [captureAndOcr]: bitmap survives until drag end.
        attachDragBitmap(bitmap, path)
        val lines = withContext(Dispatchers.Default) {
            ocrManager.recogniseWithPositions(bitmap, Prefs(popup.ctx).sourceLang)
        }
        if (lines == null) {
            Log.d(TAG, "No text found in saved screenshot")
            return
        }
        Log.d(TAG, "OCR from file found ${lines.size} lines")
        ocrLines = lines
    }

    /**
     * Called on every ACTION_MOVE during a drag.
     * Uses wobble-tolerant hold-still detection: the timer only resets when
     * the finger moves beyond [wobbleRadiusPx] from the anchor point.
     */
    fun onDragMove(rawX: Float, rawY: Float) {
        lastX = rawX
        lastY = rawY
        // Reposition the lens on every move (and re-anchor the source point
        // it magnifies). Skip while the popup is up — magnifier is hidden then,
        // and re-shown when the popup dismisses mid-drag.
        if (!popup.isShowing) {
            val screen = queryScreenSize()
            magnifier.show(rawX.toInt(), rawY.toInt(), screen.x, screen.y)
        }

        val dx = rawX - anchorX
        val dy = rawY - anchorY
        val movedBeyondWobble = dx * dx + dy * dy > wobbleRadiusPx * wobbleRadiusPx

        if (movedBeyondWobble) {
            anchorX = rawX
            anchorY = rawY
            handler.removeCallbacks(holdStillRunnable)
            holdTimerScheduled = false

            // Immediately try a lookup at the new position (keeps popup up during transition)
            if (popup.isShowing) {
                lookupJob?.cancel()
                lookupJob = scope.launch {
                    try {
                        val lines = ocrLines ?: return@launch
                        performLookup(rawX.toInt(), rawY.toInt(), lines, dismissOnMiss = true)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Move-lookup failed", e)
                    }
                }
            }
        }

        if (!holdTimerScheduled && lookupJob?.isActive != true) {
            handler.postDelayed(holdStillRunnable, HOLD_STILL_MS)
            holdTimerScheduled = true
        }
    }

    /** Called on ACTION_UP. Returns true if a popup is showing OR is about to
     *  be shown by a lift-time lookup — caller uses this to decide whether the
     *  icon should restore to its saved position (true) or snap to edge (false). */
    fun onDragEnd(): Boolean {
        cancelTimers()
        dragInProgress = false
        magnifier.dismiss()
        // Bitmap is no longer needed by the magnifier; OCR still uses it
        // until ocrJob completes, so defer recycle until then.
        scheduleBitmapRecycleAfterOcr()

        if (popup.isShowing) return true
        val lines = ocrLines ?: return false

        // Lift-time lookup: the user may have released their finger over a
        // word before the hold timer fired. Hit-test synchronously to decide
        // whether a popup is plausible (so the caller can restore the icon
        // immediately), then dispatch the dictionary lookup async.
        val hitLine = findLineAt(lastX.toInt(), lastY.toInt(), lines) ?: return false
        Log.d(TAG, "Lift-time lookup at (${lastX.toInt()}, ${lastY.toInt()}), line: ${hitLine.text}")
        lookupJob?.cancel()
        lookupJob = scope.launch {
            try {
                performLookup(lastX.toInt(), lastY.toInt(), lines, dismissOnMiss = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Lift-time lookup failed", e)
            }
        }
        return true
    }

    fun dismiss() {
        cancelTimers()
        ocrJob?.cancel()
        dragInProgress = false
        magnifier.dismiss()
        popup.dismiss()
        lastWord = null
        ocrLines = null
        recycleDragBitmap()
    }

    /** Called by popup's onDismiss — resets state without re-calling popup.dismiss(). */
    fun onPopupDismissed() {
        cancelTimers()
        lastWord = null
        // Mid-drag popup dismissal (the user moved off the word) — bring the
        // magnifier back so they keep their visual reference.
        if (dragInProgress) {
            val screen = queryScreenSize()
            magnifier.show(lastX.toInt(), lastY.toInt(), screen.x, screen.y)
        }
    }

    fun destroy() {
        cancelTimers()
        scope.cancel()
        magnifier.dismiss()
        popup.dismiss()
        ocrLines = null
        recycleDragBitmap()
    }

    private fun attachDragBitmap(bitmap: Bitmap, path: String?) {
        recycleDragBitmap()
        dragBitmap = bitmap
        screenshotPath = path
        magnifier.setBitmap(bitmap)
    }

    private fun recycleDragBitmap() {
        magnifier.setBitmap(null)
        val bm = dragBitmap
        dragBitmap = null
        bm?.takeIf { !it.isRecycled }?.recycle()
    }

    /** OCR may still be reading the bitmap when drag ends. Wait for the job
     *  to wind down before recycling, so we don't yank the bitmap out from
     *  under ML Kit. */
    private fun scheduleBitmapRecycleAfterOcr() {
        val job = ocrJob
        if (job == null || job.isCompleted) {
            recycleDragBitmap()
            return
        }
        job.invokeOnCompletion { handler.post { recycleDragBitmap() } }
    }

    // ── Internals ────────────────────────────────────────────────────────

    private fun cancelTimers() {
        handler.removeCallbacks(holdStillRunnable)
        holdTimerScheduled = false
        lookupJob?.cancel()
    }

    private val holdStillRunnable: Runnable = Runnable { onHoldStill() }

    private fun onHoldStill() {
        holdTimerScheduled = false
        val lines = ocrLines
        if (lines == null) {
            // OCR not ready yet — retry shortly (with limit to avoid infinite loop)
            if (holdRetryCount++ < MAX_HOLD_RETRIES) {
                handler.postDelayed(holdStillRunnable, 100)
                holdTimerScheduled = true
            }
            return
        }
        holdRetryCount = 0
        lookupJob?.cancel()
        lookupJob = scope.launch {
            try {
                performLookup(anchorX.toInt(), anchorY.toInt(), lines)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Lookup failed", e)
            }
        }
    }

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

        // Hand off to the magnifier first, then run OCR. The bitmap is owned
        // by [dragBitmap] from here on and is recycled when the drag ends —
        // OCR using the same bitmap is safe because we're not racing a recycle.
        val savedPath = withContext(Dispatchers.IO) { saveScreenshot(bitmap) }
        attachDragBitmap(bitmap, savedPath)

        val lines = withContext(Dispatchers.Default) {
            ocrManager.recogniseWithPositions(bitmap, Prefs(service).sourceLang)
        }
        if (lines == null) {
            Log.d(TAG, "No text found on screen")
            return
        }
        Log.d(TAG, "OCR found ${lines.size} lines")
        ocrLines = lines
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
        // Find the corresponding lookup form and reading
        val matchedToken = tokenResults.firstOrNull { it.surface == matchedSurface }
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

        withContext(Dispatchers.Main) {
            showPopup(popupData, wordCenterX, fingerY)
            if (sentence != lastSentSentence) {
                lastSentSentence = sentence
                sendLineToMainApp(sentence)
            }
        }
        return true
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


    private fun showPopup(data: PopupData, fingerX: Int, fingerY: Int) {
        currentEntry = data.entry
        // Popup takes over the same real estate as the magnifier — hide the
        // lens so the two don't visually fight. If the user keeps dragging,
        // [onPopupDismissed] brings the magnifier back.
        magnifier.hide()

        val screen = queryScreenSize()
        popup.show(
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
