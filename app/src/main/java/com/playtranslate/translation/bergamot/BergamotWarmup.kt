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
 *
 * A direction that fails its smoke test is deleted: there's no dynamic reinstall
 * (only a manual Settings re-download), so a broken-but-installed model would
 * otherwise be re-selected by [com.playtranslate.translation.BergamotBackend] on
 * every translation and fail natively before falling back.
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
        // provisioning — leaving an offline user with no offline translation. So
        // prove the engine can actually run the pair before reporting success.
        if (!manager.isInstalled(source, target)) return false
        val translator = BergamotTranslator.getInstance(ctx)
        // isInstalled implies every direction resolves; a null here (a race or a
        // hash change since that check) is treated as broken so we never claim
        // success on files we can't open.
        val resolved = dirs.map { it to manager.filesFor(it) }
        val broken = mutableListOf<String>()

        // 1) Per-direction smoke (native load + trivial translate). Testing each
        //    model on its own pinpoints a single broken model so it's deleted by
        //    itself, not dragging down a good (possibly shared) hop. Passing
        //    directions stay warm in the translator's cache.
        for ((dir, files) in resolved) {
            if (files == null) {
                broken.add(dir)
                continue
            }
            try {
                translator.translateSingle(files, SMOKE_TEXT)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Bergamot smoke failed for direction $dir ($source->$target): ${e.message}")
                broken.add(dir)
            }
        }

        // 2) For a pivot, also exercise the real runtime translatePivot path:
        //    both hops can load+translate alone yet the combined call still fail.
        //    That isn't attributable to one hop, so it condemns the whole pair.
        if (broken.isEmpty() && dirs.size == 2) {
            val f0 = resolved[0].second
            val f1 = resolved[1].second
            if (f0 != null && f1 != null) {
                try {
                    translator.translatePivot(f0, f1, SMOKE_TEXT)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Bergamot pivot smoke failed for $source->$target: ${e.message}")
                    broken.addAll(dirs)
                }
            }
        }

        if (broken.isEmpty()) return true
        // No dynamic reinstall exists (only a manual Settings re-download), so a
        // broken-but-installed model would otherwise be picked by
        // BergamotBackend.isUsable on every translation — failing natively before
        // falling back to ML Kit. Delete the directions the engine can't run so
        // isUsable stops choosing them; the caller best-effort-preloads ML Kit.
        for (dir in broken.distinct()) {
            Log.w(TAG, "Deleting unusable Bergamot direction $dir (smoke failed, $source->$target)")
            BergamotModel(dir).delete(ctx)
            translator.evictDirection(dir)
        }
        return false
    }
}
