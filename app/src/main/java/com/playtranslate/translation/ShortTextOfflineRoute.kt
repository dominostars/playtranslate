package com.playtranslate.translation

import android.util.Log
import com.playtranslate.language.offlineFallbackCandidates
import kotlin.coroutines.cancellation.CancellationException

/**
 * The fast offline tier for SHORT texts (≤2 content words — menu items, HUD
 * labels, one-word lines), chosen deliberately instead of spending an online
 * request. Distinct from [com.playtranslate.language.OfflineFallbackTranslators]
 * on purpose: that path is opaque about who answered and may download ML Kit
 * models on demand; this one NAMES its backend (the result is attributed and
 * cached like any first-class translation) and must never trigger a download —
 * [MlKitModelPresence] gates the ML Kit floor on models already on disk.
 *
 * Pick-one, not a waterfall: if the chosen backend fails, the text folds into
 * the caller's single online batch — online quality beats silently dropping to
 * the ML Kit floor while the outcome claims `kind = None`.
 *
 * `CameraTranslator` deliberately does NOT use this route: the camera/import
 * path is not where live-mode quota burns, and it keeps its own parallel
 * translation pipeline.
 */
internal class ShortTextOfflineRoute private constructor(
    private val backend: TranslationBackend,
    val displayName: String,
) {

    /** Translate [text], or null on any non-cancellation failure OR a blank
     *  result (the caller folds the text into the online batch). Blank is a
     *  failure here because empty translatedText is the overlay pipeline's
     *  PENDING sentinel — cached, it renders a permanent skeleton and blocks
     *  the pinhole fast path; every online backend guards the same way
     *  (blank → StructuralFailureException). Cancellation always propagates. */
    suspend fun translateOrNull(text: String, source: String, target: String): String? =
        try {
            backend.translate(text, source, target).takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "short-text offline ${backend.id} failed for $source->$target; folding into online batch", e)
            null
        }

    companion object {
        private const val TAG = "ShortTextRoute"

        // GATED OFF BY DEFAULT — Prefs.debugShortTextRouting (debug-build
        // Settings row) since the 2026-09-02 device-pass verdict: Bergamot's
        // output on real game shorts was bad a lot of the time, even for
        // ordinary short phrases (the 2★ tier's quality floor, not a routing
        // bug — the name veto had already pulled names out). The row exists
        // to A/B on device without a rebuild; if the idea returns for
        // production, the salvageable variant is fallback-not-override —
        // engage only when the online waterfall is down/cooled, where the
        // competition is a blank box, not Gemini.

        /**
         * The route for [source]→[target], or null when no offline backend
         * qualifies (fail-open: shorts stay on the online path). Walks the
         * shared offline-fallback candidate set in priority order (Bergamot
         * 28 before ML Kit 30); the ML Kit floor additionally requires its
         * models to be ALREADY downloaded. Resolve once per batch — Bergamot's
         * `isUsable` probes the filesystem — and OFF-MAIN.
         *
         * DELIBERATE (user decision, 2026-08-29): the route also engages when
         * the user's preferred backend is an on-device LLM (no online quota is
         * saved there) — shorts trade ~1★ of quality for a millisecond answer
         * instead of an LLM prefill. Do not add a `requiresInternet` gate
         * without revisiting that decision.
         */
        suspend fun resolve(
            source: String,
            target: String,
            mlKitReady: suspend (String, String) -> Boolean = MlKitModelPresence::hasModelsFor,
        ): ShortTextOfflineRoute? {
            for (backend in offlineFallbackCandidates(source, target)) {
                if (backend.id == MlKitBackend.ID && !mlKitReady(source, target)) continue
                return ShortTextOfflineRoute(backend, backend.displayName)
            }
            return null
        }
    }
}

/**
 * True when shorts should NOT route offline but ride the LLM batch instead:
 * the backend that would serve ([TranslationBackendRegistry.preferredBackend])
 * is an online LLM that batches with prompt context, and the user has LLM
 * context enabled. Riding the batch keeps context-sensitive one-worders
 * (dialogue choices: そうか / 別に / うそ) contextual; the accepted cost is
 * that an all-short menu page spends one LLM request pure offline routing
 * would have avoided. Self-correcting under cooldown: a rate-limited LLM
 * stops being the preferred backend, so short-routing engages exactly when
 * quota runs out.
 */
internal fun shouldBypassForLlm(backend: TranslationBackend?, contextEnabled: Boolean): Boolean =
    contextEnabled && backend is BatchTranslator && backend.providesLlmContext
