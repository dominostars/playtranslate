package com.playtranslate.translation.bergamot

import android.content.Context
import android.os.Process
import android.util.Log
import com.playtranslate.Prefs
import com.playtranslate.translation.llm.OnDeviceLlmDownloader

/**
 * Language-setup warm-up for the Bergamot (Firefox Translations) offline tier.
 *
 * Called at the same point the app warms up ML Kit fallback models
 * ([com.playtranslate.preloadMlKitFallbackModels]). It downloads the Bergamot
 * model(s) for the chosen source→target pair (one for a hop, two for an English
 * pivot) and, on success, the caller SKIPS the ML Kit warm-up — making Bergamot
 * the default offline translator. It falls back to ML Kit (returns false) when
 * Bergamot is disabled, the pair is unsupported by Mozilla's xx↔en model set,
 * the device is 32-bit, or any download fails.
 */
object BergamotWarmup {
    private const val TAG = "BergamotWarmup"

    // Bergamot's resident working set is small (~150–200 MB int8); keep the
    // download preflight RAM floor low so we don't refuse on modest devices.
    private const val MEM_FLOOR_BYTES = 700_000_000L

    suspend fun ensureForPair(context: Context, source: String, target: String): Boolean {
        val ctx = context.applicationContext
        if (!Prefs(ctx).bergamotEnabled) return false
        if (!Process.is64Bit()) return false

        val manager = BergamotModelManager(ctx)
        val dirs = manager.requiredDirections(source, target) ?: return false // unsupported pair

        for (dir in dirs) {
            val helper = BergamotModel(dir)
            if (helper.isInstalled(ctx)) continue
            val outcome = try {
                OnDeviceLlmDownloader(ctx, helper, MEM_FLOOR_BYTES).run { /* indeterminate dialog */ }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Bergamot warm-up download of $dir failed: ${e.message}")
                return false
            }
            if (outcome !is OnDeviceLlmDownloader.Outcome.Success) {
                Log.w(TAG, "Bergamot warm-up of $dir not successful: $outcome")
                return false
            }
        }
        return manager.isInstalled(source, target)
    }
}
