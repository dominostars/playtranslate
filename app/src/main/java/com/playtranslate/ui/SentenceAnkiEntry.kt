package com.playtranslate.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.playtranslate.AnkiManager
import com.playtranslate.PlayTranslateApplication
import com.playtranslate.R
import com.playtranslate.language.SourceLangId
import com.playtranslate.model.PendingTranslation
import kotlinx.coroutines.launch

/**
 * Everything a surface needs to make a sentence card: the sentence, its
 * translation (blank while deferred — [pendingTranslation] then rides so
 * the editor's lazy fill runs the deferred COMPLETION, History rows and
 * all), the capture, and the words as one LOCKED snapshot ([words]) — the
 * blank-meaning transport requires the meaning slots and the enrichment to
 * come from the SAME maps, and the process-global cache can rotate between
 * separate reads.
 */
data class SentenceAnkiArgs(
    val original: String,
    val translation: String,
    val screenshotPath: String?,
    val sourceLangId: SourceLangId,
    val pendingTranslation: PendingTranslation?,
    /** Game-audio ring anchor: when the sentence's capture happened, so the
     *  trim view opens at the line's own moment. Null without a capture. */
    val audioAnchorMs: Long?,
    val words: LastSentenceCache.WordsPayload?,
)

/**
 * Present the editable sentence card from an over-game surface — the one
 * entry the capture sheet and the workspace's Sentence page share. With
 * the AnkiDroid permission already held and a [route] that can present,
 * the editor opens as a floating-workspace page receiving the payload as
 * OBJECTS (the size-gated intent transport below is bypassed entirely);
 * otherwise the AnkiPermissionActivity → SentenceAnkiReviewActivity
 * trampoline, which owns the runtime permission request. Returns true when
 * the workspace page was presented (the caller's surface stays up under
 * it), false when the Activity was launched — after the route's
 * [WorkspaceRoute.prepareActivityLaunch] (a workspace page tears its window
 * down first, else it would sit above the launched activity). The
 * AnkiDroid-installed gate is the caller's — each surface presents that
 * dialog in its own window.
 */
fun presentSentenceAnkiReview(
    ctx: Context,
    displayId: Int,
    route: WorkspaceRoute,
    args: SentenceAnkiArgs,
): Boolean {
    val app = ctx.applicationContext
    if (AnkiManager(app).hasPermission()) {
        val opened = route.present(args.screenshotPath) {
            AnkiSentenceEditorPage(
                original = args.original,
                translation = args.translation,
                wordResults = args.words?.results ?: emptyMap(),
                surfaceForms = args.words?.surfaces ?: emptyMap(),
                wordEnrichment = args.words?.enrichment ?: emptyMap(),
                screenshotPath = args.screenshotPath,
                sourceLangId = args.sourceLangId,
                pendingTranslation = args.pendingTranslation,
                audioAnchorMs = args.audioAnchorMs,
            )
        }
        if (opened) return true
    }
    route.prepareActivityLaunch()
    launchSentenceAnkiTrampoline(app, displayId, args)
    return false
}

/** The Activity route for a sentence card: the permission trampoline
 *  forwarding to the review, with the payload as size-gated intent extras
 *  (see [transportPayloadFor]). Lands on the foreground PT display when
 *  there is one, else [displayId]. */
fun launchSentenceAnkiTrampoline(app: Context, displayId: Int, args: SentenceAnkiArgs) {
    SentenceAnkiReviewActivity.finishCurrentIfAny()
    AnkiPermissionActivity.finishCurrentIfAny()
    val intent = Intent(app, AnkiPermissionActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
        putExtra(AnkiPermissionActivity.EXTRA_FORWARD_TARGET, AnkiPermissionActivity.TARGET_SENTENCE)
        putExtra(SentenceAnkiReviewActivity.EXTRA_SENTENCE, args.original)
        putExtra(SentenceAnkiReviewActivity.EXTRA_TRANSLATION, args.translation)
        // A deferred result's translation is blank — carry the pending so
        // the sheet's lazy fill runs the deferred COMPLETION (History rows
        // fill too). The launching surface goes away with its own funnel,
        // so the sheet is the completion's only carrier.
        args.pendingTranslation?.let {
            putExtra(SentenceAnkiReviewActivity.EXTRA_PENDING_TRANSLATION, it)
        }
        args.screenshotPath?.let { putExtra(SentenceAnkiReviewActivity.EXTRA_SCREENSHOT_PATH, it) }
        putExtra(SentenceAnkiReviewActivity.EXTRA_SOURCE_LANG, args.sourceLangId.code)
        args.audioAnchorMs?.let {
            putExtra(SentenceAnkiReviewActivity.EXTRA_AUDIO_ANCHOR_MS, it)
        }
        args.words?.let { snap ->
            val keys = snap.results.keys.toTypedArray()
            // Size-gated pair: normally senses ride EXTRA_ENRICHMENT and
            // sense-bearing meaning slots are blanked (definition text
            // crosses the binder once; meaningFromTransport re-derives on
            // the sheet's read side). An oversized senses payload ships
            // stripped enrichment + real flat meanings instead — see
            // transportPayloadFor. Safe ONLY because every extra reads
            // the same [snap]: a blank slot's senses are the senses that
            // cross.
            val transport = transportPayloadFor(keys, snap.results, snap.enrichment)
            putExtra(SentenceAnkiReviewActivity.EXTRA_WORDS, keys)
            putExtra(SentenceAnkiReviewActivity.EXTRA_READINGS,
                snap.results.values.map { it.first }.toTypedArray())
            putExtra(SentenceAnkiReviewActivity.EXTRA_MEANINGS, transport.meanings)
            putExtra(SentenceAnkiReviewActivity.EXTRA_FREQ_SCORES,
                snap.results.values.map { it.third }.toIntArray())
            putExtra(SentenceAnkiReviewActivity.EXTRA_SURFACES,
                keys.map { snap.surfaces[it] ?: "" }.toTypedArray())
            putExtra(SentenceAnkiReviewActivity.EXTRA_ENRICHMENT, transport.enrichment)
        }
    }
    val targetDisplay = PlayTranslateApplication.foregroundDisplayId() ?: displayId
    val opts = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(targetDisplay).toBundle()
    app.startActivity(intent, opts)
}

/**
 * Headless one-tap send of a sentence card from an over-game surface.
 * Runs on the PROCESS-lived one-tap scope, not the surface's: a surface
 * that dismisses on the gesture would otherwise silently kill an in-flight
 * send (no card, no result toast). Every outcome is reported by Toast on
 * the app context, so nothing is lost by the surface being gone when the
 * send lands. [onNeedsMapping] runs on Main when the mapping needs UI —
 * the caller reopens its review only while it is still on screen. The
 * gates (AnkiDroid installed, permission, a deck picked) are the caller's;
 * it falls back to the review on any of them.
 */
fun launchSentenceOneTap(app: Context, args: SentenceAnkiArgs, onNeedsMapping: () -> Unit) {
    Toast.makeText(app, R.string.anki_adding_in_progress, Toast.LENGTH_SHORT).show()
    ankiOneTapSendScope.launch {
        val sendResult = app.oneTapSendSentence(
            original = args.original,
            translation = args.translation.takeIf { it.isNotEmpty() },
            wordsPayload = args.words,
            screenshotPath = args.screenshotPath,
            sourceLangId = args.sourceLangId,
            // Deferred result: the lazy translate runs the deferred
            // COMPLETION (History rows fill too). This scope outlives the
            // surface, so the attach survives a dismissal mid-send.
            pendingTranslation = args.pendingTranslation,
        )
        when (sendResult) {
            is AnkiSendResult.NeedsMapping -> onNeedsMapping()
            else -> oneTapResultToast(app, sendResult, CardMode.SENTENCE)
        }
    }
}
