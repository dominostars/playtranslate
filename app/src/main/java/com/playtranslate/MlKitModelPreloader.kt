package com.playtranslate

import android.util.Log
import com.google.mlkit.common.MlKitException

private const val TAG = "MlKitPreload"

/**
 * Best-effort warm-up of the ML Kit fallback translation models for a
 * [sourceLangCode] → [targetLangCode] pair, plus the EN → target pivot model
 * the dictionary's definition-translation fallback uses when the target
 * isn't English.
 *
 * ML Kit is the degraded, last-resort translation backend
 * ([com.playtranslate.translation.MlKitBackend]); its model files download
 * through Google Play Services — a path the app neither owns nor can repair.
 * On some devices (e.g. a brand-new OS build whose GMS can't fetch models) the
 * download fails with `MlKitException: No existing model file`, and no app-side
 * retry or pack reinstall recovers it. Such a failure must NOT abort language
 * setup: the dictionary, OCR, and the online / on-device backends all work
 * without ML Kit. So we swallow the download failure, log it, and return whether
 * everything is ready; ML Kit's own `translate()` re-attempts the download
 * lazily on later use, so the model self-heals if GMS recovers.
 *
 * Same-language pairs (source == target) are NOT attempted: ML Kit has no
 * same-language model, so the download would fail deterministically — but that
 * pair is the OCR-only bypass (`CaptureService.translate` returns the OCR text
 * unchanged when source == target), so there is genuinely nothing to download.
 * Skipping it avoids a guaranteed failure and a misleading "offline
 * unavailable" toast.
 *
 * Only [MlKitException] — the expected GMS download/availability failure — is
 * swallowed quietly. Any other exception is unexpected, so it's logged at error
 * level (but still doesn't block setup — the fallback is optional). A
 * [kotlin.coroutines.cancellation.CancellationException] is re-thrown so the
 * caller's Cancel handling and structured concurrency stay intact.
 *
 * @return true if every needed model is present, false if any download failed.
 */
suspend fun preloadMlKitFallbackModels(
    sourceLangCode: String,
    targetLangCode: String,
): Boolean {
    var allReady = true

    suspend fun warm(source: String, target: String) {
        val tm = TranslationManager(source, target)
        try {
            tm.ensureModelReady()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: MlKitException) {
            // Expected: GMS couldn't fetch the model. Degrade, don't block.
            allReady = false
            Log.w(TAG, "ML Kit fallback model $source -> $target unavailable; continuing without it", e)
        } catch (e: Exception) {
            // Unexpected (not an ML Kit download error). Still non-fatal — the
            // fallback is optional — but log loudly so it isn't silently masked.
            allReady = false
            Log.e(TAG, "Unexpected error preparing ML Kit fallback model $source -> $target; continuing without it", e)
        } finally {
            tm.close()
        }
    }

    // Same-language is the OCR-only bypass and has no downloadable model, so
    // don't attempt it. The EN → target pivot below is always a distinct pair.
    if (sourceLangCode != targetLangCode) warm(sourceLangCode, targetLangCode)
    if (targetLangCode != "en") warm("en", targetLangCode)

    return allReady
}
