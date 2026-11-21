package com.playtranslate.camera

import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import com.playtranslate.OcrManager
import com.playtranslate.camera.render.FrozenLookupScene
import kotlin.math.abs

/**
 * The scene's lines where the host shows them: AU coordinates through the
 * flow's fit ([CameraCoordinates] in [fitMode], onto a host of [hostW] x
 * [hostH]) and then the review zoom ([zoom], null at fit). The one
 * projection for everything that reads a frozen frame's text in view space:
 * the drag lookup's hit-tests ([CameraWordLookup]) and the Definitions word
 * boxes ([ReviewWordBoxes]), so a box sits exactly where a finger looks a
 * word up.
 *
 * The oriented dims ride the SAME transform as the bounds: a bare copy
 * would pair view-space bounds with AU-space dims. The chain is fit-scale
 * plus pinch (scale and translate), so the effective scales fall out of the
 * projected bounds; should they ever disagree (a non-similarity transform),
 * the line fails soft to an upright one: axis-aligned hit-testing, never
 * wrong geometry.
 */
fun FrozenLookupScene.linesInView(
    hostW: Int,
    hostH: Int,
    fitMode: CameraCoordinates.FitMode,
    zoom: Matrix?,
): List<OcrManager.OcrLine> {
    val coords = CameraCoordinates(auWidth, auHeight, hostW, hostH, fitMode)
    fun project(r: Rect): Rect {
        val v = coords.auToView(r)
        val z = zoom ?: return v
        val rf = RectF(v)
        z.mapRect(rf)
        return Rect(
            Math.round(rf.left), Math.round(rf.top),
            Math.round(rf.right), Math.round(rf.bottom),
        )
    }
    return lines.map { line ->
        val pb = project(line.bounds)
        val sx = if (line.bounds.width() > 0) pb.width().toFloat() / line.bounds.width() else 1f
        val sy = if (line.bounds.height() > 0) pb.height().toFloat() / line.bounds.height() else 1f
        val similar = abs(sx - sy) <= 0.04f * maxOf(sx, sy)
        val ang = if (similar) line.angleDeg else 0f
        line.copy(
            bounds = pb,
            symbols = line.symbols.map { s -> s.copy(bounds = project(s.bounds)) },
            angleDeg = ang,
            orientedWidth = if (ang != 0f) line.orientedWidth * sx else 0f,
            orientedHeight = if (ang != 0f) line.orientedHeight * sy else 0f,
        )
    }
}
