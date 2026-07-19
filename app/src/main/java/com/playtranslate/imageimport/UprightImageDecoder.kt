package com.playtranslate.imageimport

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log

/**
 * Decodes a picked/shared/pasted image URI into the upright ARGB_8888 bitmap
 * the review pipeline consumes ("AnalysisUpright" space).
 *
 * [ImageDecoder] applies EXIF orientation itself, so a sideways phone photo
 * arrives upright with no manual rotate; the software allocator is REQUIRED
 * (the default hardware bitmaps can't be read back by OCR, color sampling,
 * or the rasterizer).
 *
 * Downsampling: camera photos run 12-50 MP, far past what recognition uses —
 * Paddle's detector is internally bounded near 1920 px and ML Kit gains
 * little past ~2.5k. [MAX_DIMENSION_PX] = 2560 keeps ML Kit reads crisper
 * than a hard 1920 clamp while bounding a 50 MP decode to a workable ~4 MP
 * bitmap. Power-of-two sample sizes only ([sampleSizeFor]), so the result
 * lands at or under the cap.
 */
object UprightImageDecoder {

    const val MAX_DIMENSION_PX = 2560

    sealed class Result {
        data class Success(val bitmap: Bitmap) : Result()
        data class Failure(val reason: String?) : Result()
    }

    fun decode(ctx: Context, uri: Uri): Result =
        decodeSource("$uri") { ImageDecoder.createSource(ctx.contentResolver, uri) }

    /** File-source decode — the process-death restore path, which re-reads
     *  the review's own cache copy instead of the original content URI
     *  (whose read grant rarely survives the process: SAF grants die with
     *  it unless persisted, and photo-picker/share/clipboard grants are not
     *  persistable at all). */
    fun decode(file: java.io.File): Result =
        decodeSource(file.path) { ImageDecoder.createSource(file) }

    private inline fun decodeSource(label: String, source: () -> ImageDecoder.Source): Result = try {
        val bitmap = ImageDecoder.decodeBitmap(source()) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            decoder.setTargetSampleSize(
                sampleSizeFor(info.size.width, info.size.height, MAX_DIMENSION_PX),
            )
        }
        Result.Success(bitmap)
    } catch (e: Exception) {
        Log.w(TAG, "decode failed for $label", e)
        Result.Failure(e.message)
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "decode OOM for $label", e)
        Result.Failure(e.message)
    }

    /** Smallest power-of-two sample size that brings max([w], [h]) to at
     *  most [cap]. Pure — JVM-tested. */
    fun sampleSizeFor(w: Int, h: Int, cap: Int): Int {
        if (cap <= 0) return 1
        val longest = maxOf(w, h)
        var sample = 1
        while (longest / sample > cap) sample *= 2
        return sample
    }

    private const val TAG = "UprightImageDecoder"
}
