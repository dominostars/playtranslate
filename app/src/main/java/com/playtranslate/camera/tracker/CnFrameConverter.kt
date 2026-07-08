package com.playtranslate.camera.tracker

import androidx.camera.core.ImageProxy
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * RGBA [ImageProxy] → upright canonical-space gray [Mat] for the tracker:
 * zero-copy wrap of the RGBA buffer → cvtColor gray → INTER_AREA downscale to
 * [CN_LONG_EDGE] on the long side → rotate upright per the proxy's
 * rotationDegrees. All scratch Mats are reused; the returned Mat is owned by
 * this converter and valid until the next [convert] call (clone to keep).
 *
 * Analysis-thread only.
 */
class CnFrameConverter {

    private companion object {
        const val CN_LONG_EDGE = 960
    }

    private val grayFull = Mat()
    private val grayCn = Mat()
    private val grayUpright = Mat()

    /** Uniform AU→CN scale of the last converted frame (CN px = AU px × this). */
    var cnScale: Double = 1.0
        private set

    fun convert(proxy: ImageProxy): Mat {
        val plane = proxy.planes[0]
        plane.buffer.rewind()
        // Zero-copy view over the RGBA buffer (rowStride-aware).
        val rgba = Mat(
            proxy.height, proxy.width, CvType.CV_8UC4,
            plane.buffer, plane.rowStride.toLong(),
        )
        Imgproc.cvtColor(rgba, grayFull, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()

        val longEdge = maxOf(proxy.width, proxy.height)
        cnScale = CN_LONG_EDGE.toDouble() / longEdge
        Imgproc.resize(
            grayFull, grayCn,
            Size(proxy.width * cnScale, proxy.height * cnScale),
            0.0, 0.0, Imgproc.INTER_AREA,
        )

        return when (proxy.imageInfo.rotationDegrees) {
            90 -> { Core.rotate(grayCn, grayUpright, Core.ROTATE_90_CLOCKWISE); grayUpright }
            180 -> { Core.rotate(grayCn, grayUpright, Core.ROTATE_180); grayUpright }
            270 -> { Core.rotate(grayCn, grayUpright, Core.ROTATE_90_COUNTERCLOCKWISE); grayUpright }
            else -> grayCn
        }
    }

    fun release() {
        grayFull.release()
        grayCn.release()
        grayUpright.release()
    }
}
