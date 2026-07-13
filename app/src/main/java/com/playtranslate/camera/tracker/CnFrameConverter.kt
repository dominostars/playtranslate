package com.playtranslate.camera.tracker

import androidx.camera.core.ImageProxy
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * YUV_420_888 [ImageProxy] → upright canonical-space gray [Mat] for the
 * tracker: zero-copy wrap of the luma plane (Y IS the gray channel — no
 * color conversion at all) → INTER_AREA downscale to [CN_LONG_EDGE] on the
 * long side → rotate upright per the proxy's rotationDegrees.
 *
 * Outputs are double-buffered: the returned Mat is owned by this converter
 * and stays valid until the SECOND-following [convert] call — so the
 * tracker can hold the previous frame by reference (its LK "prev" input)
 * instead of copying ~0.5 MB every frame. Clone to keep longer.
 *
 * Analysis-thread only.
 */
class CnFrameConverter {

    private companion object {
        const val CN_LONG_EDGE = 960
    }

    private val grayCn = arrayOf(Mat(), Mat())
    private val grayUpright = arrayOf(Mat(), Mat())
    private var slot = 0

    /** Uniform AU→CN scale of the last converted frame (CN px = AU px × this). */
    var cnScale: Double = 1.0
        private set

    fun convert(proxy: ImageProxy): Mat {
        slot = 1 - slot
        val yPlane = proxy.planes[0]
        yPlane.buffer.rewind()
        // Zero-copy view over the Y plane (rowStride-aware; the spec fixes
        // the luma pixelStride at 1).
        val gray = Mat(
            proxy.height, proxy.width, CvType.CV_8UC1,
            yPlane.buffer, yPlane.rowStride.toLong(),
        )
        val longEdge = maxOf(proxy.width, proxy.height)
        cnScale = CN_LONG_EDGE.toDouble() / longEdge
        Imgproc.resize(
            gray, grayCn[slot],
            Size(proxy.width * cnScale, proxy.height * cnScale),
            0.0, 0.0, Imgproc.INTER_AREA,
        )
        gray.release()

        return when (proxy.imageInfo.rotationDegrees) {
            90 -> { Core.rotate(grayCn[slot], grayUpright[slot], Core.ROTATE_90_CLOCKWISE); grayUpright[slot] }
            180 -> { Core.rotate(grayCn[slot], grayUpright[slot], Core.ROTATE_180); grayUpright[slot] }
            270 -> { Core.rotate(grayCn[slot], grayUpright[slot], Core.ROTATE_90_COUNTERCLOCKWISE); grayUpright[slot] }
            else -> grayCn[slot]
        }
    }

    fun release() {
        grayCn.forEach { it.release() }
        grayUpright.forEach { it.release() }
    }
}
