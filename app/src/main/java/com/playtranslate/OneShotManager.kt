package com.playtranslate

import android.graphics.Bitmap
import com.playtranslate.language.SourceLanguageEngines
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Manages one-shot hold-to-preview captures. Stateless — every hold is a fresh
 * capture with no dedup or caching. Delegates box building to [OneShotProcessor]
 * implementations (furigana vs translation) via factory.
 *
 * Supports multi-display fan-out: hotkey + in-app translate-button hold target
 * every selected non-foreground display in parallel, while the floating-icon
 * hold targets exactly its own icon's display. Each [runHoldOverlay] call
 * defines a new generation; per-cycle parameters are captured by value into a
 * [Cycle] so concurrent in-flight cycles never read fields a later generation
 * has rewritten. Every side-effecting publish point (overlay paint, no-text
 * pill, panel emit) gen-checks before acting, so a zombie cycle that suspended
 * past cancellation can't mutate UI tied to a newer hold. Coroutine
 * cancellation propagated by [supersede]/[cancel] is the secondary defense
 * for in-flight suspends past the last gen-check.
 */
class OneShotManager(private val service: CaptureService) {

    /** Immutable per-cycle state. Captured at [runHoldOverlay] time and
     *  threaded through [runCycle] so each cycle observes its own
     *  parameters even when a later [runHoldOverlay] has incremented
     *  [currentGeneration] and overwritten the next cycle's params. */
    private data class Cycle(
        val forceMode: OverlayMode?,
        val panelDisplayId: Int,
        val generation: Long,
    )

    private val activeJobs: MutableMap<Int, Job> = mutableMapOf()
    /** Monotonic generation counter. Incremented by [runHoldOverlay] (new
     *  gesture) and [cancel] (gesture ended). Cycles compare their
     *  captured value against this on every publish point — mismatch
     *  means superseded and the cycle exits without emitting. Safe as a
     *  plain Long because the manager is only invoked from
     *  [CaptureService.serviceScope], which runs on Dispatchers.Main. */
    private var currentGeneration: Long = 0

    /** Single-display variant retained for the floating-icon path, where
     *  hold should only run on the icon's display. */
    fun runHoldOverlay(
        forceMode: OverlayMode? = null,
        displayId: Int = service.primaryGameDisplayId(),
    ) {
        runHoldOverlay(forceMode, setOf(displayId), displayId)
    }

    /** Multi-display variant. Cancels any prior cycles, then launches one
     *  cycle per id in [displayIds]. [panelDisplayId] picks which cycle is
     *  responsible for the in-app panel update. */
    fun runHoldOverlay(
        forceMode: OverlayMode?,
        displayIds: Set<Int>,
        panelDisplayId: Int,
    ) {
        currentGeneration += 1
        val cycle = Cycle(
            forceMode = forceMode,
            panelDisplayId = panelDisplayId,
            generation = currentGeneration,
        )
        supersede(displayIds)
        for (id in displayIds) {
            activeJobs[id] = service.serviceScope.launch { runCycle(id, cycle) }
        }
    }

    /** End the gesture entirely (user lifted finger). Cancels every
     *  in-flight cycle and hides every overlay it might still be painting.
     *  Bumps generation so any zombie cycle that resumes past this call
     *  exits at its next gen-check rather than emitting stale state. */
    fun cancel() {
        currentGeneration += 1
        val targets = activeJobs.keys.toList()
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        val a11y = PlayTranslateAccessibilityService.instance ?: return
        for (id in targets) a11y.hideTranslationOverlayForDisplay(id)
    }

    /** Replace the running set in preparation for a NEW cycle group.
     *  Cancels every prior cycle, but only hides overlays for displays
     *  the new generation isn't going to repaint — preserves continuity
     *  on overlapping displays so the new cycle's paint can land without
     *  a hide-flicker in between. Generation has already been bumped by
     *  [runHoldOverlay] so any zombie cycle here is already gen-stale. */
    private fun supersede(newTargets: Set<Int>) {
        val toHide = activeJobs.keys - newTargets
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        val a11y = PlayTranslateAccessibilityService.instance ?: return
        for (id in toHide) a11y.hideTranslationOverlayForDisplay(id)
    }


    private suspend fun runCycle(displayId: Int, cycle: Cycle) {
        if (!service.isConfigured) return
        if (cycle.generation != currentGeneration) return

        // 1. Capture clean screenshot
        val raw: Bitmap = service.captureScreen(displayId) ?: return

        try {
            if (cycle.generation != currentGeneration) return

            // 2. Flash region indicator
            service.flashRegionIndicator(displayId)

            // 3. OCR via shared pipeline
            val pipeline = service.runOcr(raw, displayId)
            if (cycle.generation != currentGeneration) return

            if (pipeline == null) {
                if (displayId == cycle.panelDisplayId) {
                    service.emitHoldLoading(false)
                    service.emitLiveNoText()
                }
                showNoTextPill(displayId)
                return
            }

            val (ocrResult, _, cropLeft, cropTop, screenshotW, screenshotH) = pipeline

            // 4. Save screenshot for Anki — per-display filename so a
            //    concurrent live cycle on another display can't clobber it.
            val screenshotPath = PlayTranslateAccessibilityService.instance
                ?.screenshotManager?.saveToCache(raw, displayId)

            // 5. Build boxes via processor (factory decides furigana vs translation)
            val processor = createProcessor(cycle.forceMode)
            val boxes = processor.buildBoxes(ocrResult, raw, cropLeft, cropTop, screenshotW, screenshotH) { intermediate ->
                // Shimmer placeholder callback. Gen-check so a superseded
                // cycle can't paint over the new generation's overlay.
                if (cycle.generation == currentGeneration) {
                    service.showLiveOverlay(intermediate, cropLeft, cropTop, screenshotW, screenshotH, force = true, displayId = displayId)
                }
            }

            if (cycle.generation != currentGeneration) return

            // 6. Show final overlay
            if (boxes.isNotEmpty()) {
                service.showLiveOverlay(boxes, cropLeft, cropTop, screenshotW, screenshotH, force = true, displayId = displayId)
            }

            // 7. Send translation to in-app panel — only the panel-target
            //    cycle is allowed to push, so concurrent fan-out cycles
            //    don't race the single global panel state. Coroutine
            //    cancellation propagated by [supersede]/[cancel] handles
            //    the in-flight case past this checkpoint.
            if (displayId == cycle.panelDisplayId) {
                service.translateAndSendToPanel(ocrResult, screenshotPath)
            }
        } finally {
            if (!raw.isRecycled) raw.recycle()
        }
    }

    private fun createProcessor(forceMode: OverlayMode?): OneShotProcessor {
        val mode = forceMode ?: Prefs(service).overlayMode
        return when (mode) {
            OverlayMode.FURIGANA -> {
                val engine = SourceLanguageEngines.get(service, Prefs(service).sourceLangId)
                FuriganaOneShotProcessor(engine, service.furiganaPaint)
            }
            OverlayMode.TRANSLATION -> TranslationOneShotProcessor(
                service::translateGroupsSeparately
            )
        }
    }

    private fun showNoTextPill(displayId: Int) {
        val a11y = PlayTranslateAccessibilityService.instance
        val dm = service.getSystemService(android.hardware.display.DisplayManager::class.java)
        val display = dm?.getDisplay(displayId)
        if (a11y != null && display != null) {
            a11y.showNoTextPill(display, service.noTextMessage(displayId))
        }
    }
}
