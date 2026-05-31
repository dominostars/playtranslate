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
 * pivot), then **proves the runtime path actually works** with a native load +
 * smoke translate; only on that success does the caller SKIP the ML Kit warm-up,
 * making Bergamot the default offline translator. It falls back to ML Kit
 * (returns false) when Bergamot is disabled, the pair is unsupported by Mozilla's
 * xx↔en model set, the device is 32-bit, any download fails, OR the native engine
 * can't load/translate — so we never suppress ML Kit provisioning on the strength
 * of files-on-disk the engine can't actually use (which would leave an offline
 * user with no fallback when Bergamot fails at runtime).
 */
object BergamotWarmup {
    private const val TAG = "BergamotWarmup"

    // Bergamot's resident working set is small (~150–200 MB int8); keep the
    // download preflight RAM floor low so we don't refuse on modest devices.
    private const val MEM_FLOOR_BYTES = 700_000_000L

    // Minimal non-empty input for the runtime smoke translate. We only care that
    // the native lib + model load and translate without crashing — not the
    // output — so any short, universally-tokenizable string works.
    private const val SMOKE_TEXT = "1"

    /**
     * [onProgress] reports byte progress of the model download(s) so the caller
     * can show a determinate dialog instead of an indeterminate "preloading"
     * spinner. It fires as `(index, count, received, total)` where index/count
     * are 1-based over the directions that actually need downloading (1 for a
     * single hop, 2 for an English pivot). Invoked on the download (IO) thread —
     * the caller must marshal to the UI thread. Null = no progress reporting.
     */
    suspend fun ensureForPair(
        context: Context,
        source: String,
        target: String,
        onProgress: ((index: Int, count: Int, received: Long, total: Long) -> Unit)? = null,
    ): Boolean {
        val ctx = context.applicationContext
        if (!Prefs(ctx).bergamotEnabled) return false
        if (!Process.is64Bit()) return false

        val manager = BergamotModelManager(ctx)
        val dirs = manager.requiredDirections(source, target) ?: return false // unsupported pair

        // Only the not-yet-installed directions are downloaded; index over those
        // so a pivot reusing an installed hop reports "1 of 1", not "2 of 2".
        val needed = dirs.filterNot { BergamotModel(it).isInstalled(ctx) }
        for ((idx, dir) in needed.withIndex()) {
            val helper = BergamotModel(dir)
            val outcome = try {
                OnDeviceLlmDownloader(ctx, helper, MEM_FLOOR_BYTES).run { progress ->
                    if (progress is OnDeviceLlmDownloader.Progress.Downloading) {
                        onProgress?.invoke(idx + 1, needed.size, progress.received, progress.total)
                    }
                }
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
        // Files are downloaded + hash-validated above, but that only proves
        // files-on-disk — it does NOT load libbergamot_jni, load the native
        // model, or translate. If we returned true here, a native load/translate
        // failure at real runtime would fall through to ML Kit (registry
        // waterfall), but this function's success already suppressed ML Kit
        // provisioning — leaving an offline user with no offline translation.
        // So run a real native load + smoke translate: success means the offline
        // path is genuinely ready (and the model is now warm in the translator's
        // cache); any failure returns false so the caller best-effort-preloads
        // ML Kit instead.
        if (!manager.isInstalled(source, target)) return false
        val files = dirs.mapNotNull { manager.filesFor(it) }
        if (files.size != dirs.size) return false
        return try {
            val translator = BergamotTranslator.getInstance(ctx)
            when (files.size) {
                1 -> translator.translateSingle(files[0], SMOKE_TEXT)
                else -> translator.translatePivot(files[0], files[1], SMOKE_TEXT)
            }
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Bergamot runtime smoke failed for $source->$target: ${e.message}")
            false
        }
    }
}
