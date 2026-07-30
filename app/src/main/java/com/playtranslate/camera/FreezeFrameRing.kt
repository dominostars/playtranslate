package com.playtranslate.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

private const val TAG = "FreezeFrameRing"

/**
 * Short history of analysis frames for the snapshot shutter. The finger's
 * IMPACT on the shutter (ACTION_DOWN) is what shakes the device, and the
 * frame the freeze naturally grabs — the first one delivered after the
 * click, 100-250 ms into the ring-down — sits near the blurriest point of
 * the whole gesture. The ring keeps the last ~half second of frames (NV21
 * copies; the proxies themselves must be closed per-frame under
 * KEEP_ONLY_LATEST) so the freeze can serve a frame captured BEFORE the
 * finger landed.
 *
 * Selection ([FreezeSelector]) is sharpness-based, not a fixed offset: hand
 * tremor blurs pre-tap frames too (dim-light exposure times), so "the
 * sharpest frame received before ACTION_DOWN" degrades gracefully where
 * "the frame from N ms ago" just gambles. The just-delivered frame stays a
 * candidate — aim-settle-tap-fast can leave the whole pre-tap history
 * pan-blurred while the current frame is clean — but it must beat the best
 * pre-tap frame by a clear margin ([FreezeSelector.NEWEST_WIN_MARGIN]):
 * near-ties resolve to the frame with no impulse in it. Device data
 * (Moto G, 2026-07-30): picks landed 177-588 ms pre-service scoring 10-14
 * vs the post-tap frame's 8-13 — the sharpest frame is often well before
 * the tap, so selection beats any fixed offset empirically.
 *
 * CONTAINMENT CONTRACT: no ring failure may escape into the camera's
 * analyzer. CameraX dispatches the analyzer callback with no exception
 * guard (verified against camera-core 1.4.2 bytecode: the dispatching
 * lambda has no exception table), so anything thrown there reaches the
 * analysis thread's uncaught handler and KILLS THE PROCESS — at frame
 * rate, on exactly the devices whose HAL violates a layout assumption.
 * [push] and [selectUpright] therefore catch Throwable (deliberately, not
 * Exception: the ring's own multi-MB allocations are the likeliest OOM
 * site in the session, and dropping an optional-quality feature beats
 * crashing) at their boundaries: the first failure disables the ring for
 * the session ([broken]), clears the slots — a mid-pack throw leaves a
 * half-written slot behind — and every subsequent freeze degrades to the
 * pre-ring behavior (the post-tap frame via the caller's proxy fallback).
 * The contract lives HERE, not at call sites, so a future caller cannot
 * forget it.
 *
 * Entries are stamped with RECEIPT uptime — the tap anchor's own clock
 * (MotionEvent.downTime). Receipt trails capture by the pipeline latency,
 * so "received before the finger landed" implies "exposed before the finger
 * landed": the clock skew errs in the safe direction and costs only a
 * slightly narrower usable window.
 *
 * Analysis-thread only. Steady-state cost: one ~3 MB NV21 pack per
 * [MIN_PUSH_INTERVAL_MS] (~1-3 ms on the Moto G class); ≤ [CAPACITY]×3.2 MB
 * of heap at 1080p while live, dropped at every freeze/[clear] so a
 * minutes-long frozen episode retains nothing.
 */
class FreezeFrameRing {

    companion object {
        /** Slots × push interval = reach-back coverage: 10 × 50 ms ≥ 500 ms
         *  at full analysis rate, wider under acquire contention (delivery
         *  slows; the throttle stops binding). Covers a normal tap's
         *  down→click→next-frame latency (~150-300 ms) plus a ~250 ms
         *  pre-impact candidate pool. */
        internal const val CAPACITY = 10
        internal const val MIN_PUSH_INTERVAL_MS = 50L

        /** Pure throttle predicate (JVM-tested). [lastPushMs] == 0 is an
         *  EXPLICIT "never pushed" sentinel branch, not sentinel arithmetic:
         *  the first build used Long.MIN_VALUE and `nowMs - lastPushMs`
         *  wrapped negative, reading as "pushed too recently" and jamming
         *  the throttle shut permanently — every on-device freeze saw
         *  exactly 1 candidate, the force-pushed post-tap frame (Moto G,
         *  2026-07-30). uptimeMillis can't be ≤ 0 by the time a camera
         *  session is running, so 0 is unambiguous. */
        internal fun pushDue(nowMs: Long, lastPushMs: Long, force: Boolean): Boolean =
            force || lastPushMs == 0L || nowMs - lastPushMs >= MIN_PUSH_INTERVAL_MS

        /** YUV_420_888 planes → tightly-packed NV21 (Y then interleaved
         *  VU). Buffer-level and JVM-tested across the layouts real HALs
         *  produce: planar (chroma pixel stride 1) and semi-planar (2),
         *  padded row strides, buffers with no trailing padding after the
         *  last row. The format contract fixes the luma pixel stride at 1
         *  and gives both chroma planes the same row/pixel strides. Chroma
         *  reads go through per-row bulk gets into the caller's scratch
         *  arrays ([uRow]/[vRow], each ≥ [chromaRowStride]) — per-element
         *  ByteBuffer gets are an order of magnitude slower. A buffer
         *  violating the size contract THROWS (BufferUnderflow /
         *  IllegalArgument) rather than silently mispacking; the ring's
         *  containment boundary absorbs exactly that. */
        internal fun packNv21(
            out: ByteArray,
            w: Int,
            h: Int,
            yBuf: ByteBuffer,
            yRowStride: Int,
            uBuf: ByteBuffer,
            vBuf: ByteBuffer,
            chromaRowStride: Int,
            chromaPixelStride: Int,
            uRow: ByteArray,
            vRow: ByteArray,
        ) {
            if (yRowStride == w) {
                yBuf.position(0)
                yBuf.get(out, 0, w * h)
            } else {
                for (r in 0 until h) {
                    yBuf.position(r * yRowStride)
                    yBuf.get(out, r * w, w)
                }
            }
            val cw = w / 2
            val ch = h / 2
            var o = w * h
            for (r in 0 until ch) {
                vBuf.position(r * chromaRowStride)
                vBuf.get(vRow, 0, minOf(chromaRowStride, vBuf.remaining()))
                uBuf.position(r * chromaRowStride)
                uBuf.get(uRow, 0, minOf(chromaRowStride, uBuf.remaining()))
                var ci = 0
                for (c in 0 until cw) {
                    out[o++] = vRow[ci]
                    out[o++] = uRow[ci]
                    ci += chromaPixelStride
                }
            }
        }
    }

    private class Slot {
        var data: ByteArray? = null
        var width = 0
        var height = 0
        var rotationDegrees = 0

        /** 0 = INVALID (never filled, or a pack threw mid-write). Written
         *  0 before every pack and restored last, so only fully-packed
         *  slots are ever candidates — a half-written slot pairing a fresh
         *  buffer with a stale frame's metadata would otherwise score with
         *  mismatched dims (index out of bounds) or serve garbage. */
        var tMs = 0L
    }

    private val slots = Array(CAPACITY) { Slot() }
    private var writeIndex = 0

    /** 0 = never pushed — see [pushDue] for why 0, not Long.MIN_VALUE. */
    private var lastPushMs = 0L

    /** Latched by the first failure anywhere in the ring ([breakRing]):
     *  the ring goes inert for the rest of the session and every freeze
     *  serves the post-tap frame, exactly the pre-ring behavior. One-way —
     *  a HAL that broke one layout assumption will break it on the next
     *  frame too, and a retry loop is 20 failures a second. */
    private var broken = false

    // Scratch rows for the chroma de-stride, reused across pushes (analysis
    // thread only — no aliasing).
    private var chromaRowU = ByteArray(0)
    private var chromaRowV = ByteArray(0)

    /** Copy [proxy] into the ring as tightly-packed NV21. Throttled to one
     *  pack per [MIN_PUSH_INTERVAL_MS] unless [force] — the freeze
     *  force-pushes the frame in hand so the newest candidate IS the frame
     *  the pre-ring behavior would have frozen. Slot byte arrays are reused
     *  across pushes: zero steady-state allocation. Never throws (see the
     *  containment contract). */
    fun push(proxy: ImageProxy, nowMs: Long, force: Boolean = false) {
        if (broken || !pushDue(nowMs, lastPushMs, force)) return
        lastPushMs = nowMs
        try {
            pushFrame(proxy, nowMs)
        } catch (t: Throwable) {
            breakRing("push", t)
        }
    }

    private fun pushFrame(proxy: ImageProxy, nowMs: Long) {
        val slot = slots[writeIndex]
        writeIndex = (writeIndex + 1) % CAPACITY
        // Invalidate BEFORE touching the buffer: a throw between here and
        // the restore below must leave the slot un-selectable, not a
        // stale-stamped hybrid of two frames.
        slot.tMs = 0L
        val w = proxy.width
        val h = proxy.height
        val need = w * h + (w / 2) * (h / 2) * 2
        var buf = slot.data
        if (buf == null || buf.size != need) {
            buf = ByteArray(need)
            slot.data = buf
        }
        val yPlane = proxy.planes[0]
        val uPlane = proxy.planes[1]
        val vPlane = proxy.planes[2]
        val cStride = vPlane.rowStride
        if (chromaRowU.size < cStride) {
            chromaRowU = ByteArray(cStride)
            chromaRowV = ByteArray(cStride)
        }
        packNv21(
            out = buf, w = w, h = h,
            yBuf = yPlane.buffer.duplicate(), yRowStride = yPlane.rowStride,
            uBuf = uPlane.buffer.duplicate(), vBuf = vPlane.buffer.duplicate(),
            chromaRowStride = cStride, chromaPixelStride = vPlane.pixelStride,
            uRow = chromaRowU, vRow = chromaRowV,
        )
        slot.width = w
        slot.height = h
        slot.rotationDegrees = proxy.imageInfo.rotationDegrees
        slot.tMs = nowMs
    }

    /** A selected freeze frame. [isNewestFrame] is true when the pick IS
     *  the force-pushed frame in hand at freeze time — the only case where
     *  live overlays still describe the frozen image: kept boxes are pinned
     *  to the LATEST tracked frame's transform, and an older pre-tap pick
     *  is a frame they never tracked. */
    class Pick(val bitmap: Bitmap, val isNewestFrame: Boolean)

    /** Pick the freeze frame for a shutter tap whose ACTION_DOWN was at
     *  [tapDownUptimeMs] (null = no touch anchor — keyboard/a11y activation
     *  — which selects the newest frame, the pre-ring behavior) and convert
     *  it to an upright ARGB bitmap. Null when the ring is empty, broken,
     *  or the conversion fails; the caller falls back to the live proxy
     *  (which IS the newest frame). Never throws (see the containment
     *  contract), and leaves the ring cleared on every path — the frozen
     *  episode is starting, and the conversion's own allocations must not
     *  ride on top of the ring's ~30 MB (the caller's follow-up clear() is
     *  the episode boundary made explicit, not load-bearing). */
    fun selectUpright(tapDownUptimeMs: Long?, nowMs: Long): Pick? {
        if (broken) return null
        return try {
            selectUprightOrThrow(tapDownUptimeMs, nowMs)
        } catch (t: Throwable) {
            breakRing("select", t)
            null
        }
    }

    private fun selectUprightOrThrow(tapDownUptimeMs: Long?, nowMs: Long): Pick? {
        // Every valid slot is scored (≤ CAPACITY × ~1 ms, at shutter time
        // only, never per frame) so the eligibility rules live in ONE place
        // — FreezeSelector — instead of being split with a pre-filter here.
        val candidates = slots.withIndex().mapNotNull { (i, s) ->
            val data = s.data ?: return@mapNotNull null
            if (s.tMs <= 0L) return@mapNotNull null
            FreezeSelector.Candidate(i, s.tMs, FreezeSelector.sharpness(data, s.width, s.height))
        }
        val chosen = FreezeSelector.select(candidates, tapDownUptimeMs, nowMs) ?: return null
        val newest = candidates.maxBy { it.tMs }
        Log.d(
            TAG,
            "freeze: picked t-%dms score=%.0f (newest t-%dms score=%.0f, down %s, %d candidates)".format(
                nowMs - chosen.tMs, chosen.score,
                nowMs - newest.tMs, newest.score,
                tapDownUptimeMs?.let { "t-${nowMs - it}ms" } ?: "none",
                candidates.size,
            ),
        )
        val slot = slots[chosen.key]
        val data = slot.data ?: return null
        val w = slot.width
        val h = slot.height
        val rotation = slot.rotationDegrees
        // Peak-commit ordering: the conversion below allocates ~20 MB of
        // Mats + bitmaps at the session's highest-pressure moment. Drop
        // every ring reference FIRST — the locals above keep only the
        // chosen NV21 alive — so the other ~28 MB is collectable before
        // those allocations instead of riding through them.
        clear()
        val bitmap = buildUpright(data, w, h, rotation) ?: return null
        return Pick(bitmap, isNewestFrame = chosen.key == newest.key)
    }

    /** Drop every entry (the ~30 MB goes back to GC). Called at each freeze
     *  — a frozen episode can last minutes and services no shutter — and at
     *  shutdown. Also resets the throttle so the first post-unfreeze frame
     *  is pushed immediately. */
    fun clear() {
        for (s in slots) {
            s.data = null
            s.tMs = 0L
        }
        writeIndex = 0
        lastPushMs = 0L
    }

    /** The containment terminus: latch [broken], evict everything (incl.
     *  any half-written slot), release the memory, say why ONCE. */
    private fun breakRing(where: String, t: Throwable) {
        broken = true
        clear()
        Log.e(TAG, "ring disabled ($where): freezes fall back to the post-tap frame", t)
    }

    /** NV21 bytes → upright ARGB_8888, mirroring the live path's
     *  toUprightBitmap (YUV→RGB then rotate per the frame's own
     *  rotationDegrees). Takes raw fields, not a slot — the caller clears
     *  the ring before converting (peak-commit ordering). Null on failure —
     *  a transient conversion failure (native alloc under pressure) falls
     *  back to the live proxy WITHOUT breaking the ring; the Mats are
     *  constructed inside the try because Mat's native allocation itself
     *  can throw. */
    private fun buildUpright(data: ByteArray, w: Int, h: Int, rotationDegrees: Int): Bitmap? {
        var yuv: Mat? = null
        var rgba: Mat? = null
        return try {
            yuv = Mat(h + h / 2, w, CvType.CV_8UC1)
            rgba = Mat()
            yuv.put(0, 0, data)
            Imgproc.cvtColor(yuv, rgba, Imgproc.COLOR_YUV2RGBA_NV21)
            var bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgba, bmp)
            if (rotationDegrees != 0) {
                val m = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                val rotated = Bitmap.createBitmap(bmp, 0, 0, w, h, m, true)
                bmp.recycle()
                bmp = rotated
            }
            bmp
        } catch (e: Exception) {
            Log.w(TAG, "freeze frame conversion failed", e)
            null
        } finally {
            yuv?.release()
            rgba?.release()
        }
    }
}

/** The freeze frame's pure selection policy + sharpness metric, separated
 *  from the ring's Android/OpenCV machinery so the policy is JVM-testable. */
internal object FreezeSelector {

    /** Entries older than this (vs now) never serve a tap: the ring
     *  survives analyzer stalls (backgrounding, rebind) and must not
     *  resurrect a frame of whatever the camera pointed at seconds ago. */
    const val STALE_MS = 1500L

    /** The newest frame must be this much sharper than the best pre-tap
     *  frame to win: scores are noisy, and a near-tie should resolve to the
     *  frame with no tap impulse in it. */
    const val NEWEST_WIN_MARGIN = 1.25

    /** [key] identifies the ring slot; [tMs] is receipt uptime. */
    data class Candidate(val key: Int, val tMs: Long, val score: Double)

    /** The tap's freeze frame among [candidates] (every valid ring entry,
     *  newest = the frame in hand at freeze time): the sharpest fresh
     *  pre-tap frame, unless the newest beats it by [NEWEST_WIN_MARGIN] or
     *  no fresh pre-tap frame exists (no [tapDownUptimeMs] anchor, a slow
     *  press whose impact predates the ring, a post-stall tap). */
    fun select(candidates: List<Candidate>, tapDownUptimeMs: Long?, nowMs: Long): Candidate? {
        val newest = candidates.maxByOrNull { it.tMs } ?: return null
        if (tapDownUptimeMs == null) return newest
        val bestPreTap = candidates
            .filter { it.tMs <= tapDownUptimeMs && nowMs - it.tMs <= STALE_MS }
            .maxByOrNull { it.score }
            ?: return newest
        return if (newest.score > bestPreTap.score * NEWEST_WIN_MARGIN) newest else bestPreTap
    }

    /** Sharpness for same-scene ranking: mean absolute second difference
     *  (horizontal + vertical, ±2 px baseline) over a ×4-subsampled luma
     *  grid. Not an absolute blur measure — candidates are the same scene
     *  milliseconds apart, so only the ordering matters. ~130 k samples at
     *  1080p; evaluated at shutter time only, never per frame. */
    fun sharpness(luma: ByteArray, width: Int, height: Int): Double {
        var sum = 0L
        var n = 0
        var y = 2
        while (y < height - 2) {
            val row = y * width
            val up = (y - 2) * width
            val down = (y + 2) * width
            var x = 2
            while (x < width - 2) {
                val c = 2 * (luma[row + x].toInt() and 0xFF)
                val dh = c - (luma[row + x - 2].toInt() and 0xFF) - (luma[row + x + 2].toInt() and 0xFF)
                val dv = c - (luma[up + x].toInt() and 0xFF) - (luma[down + x].toInt() and 0xFF)
                sum += Math.abs(dh) + Math.abs(dv)
                n++
                x += 4
            }
            y += 4
        }
        return if (n == 0) 0.0 else sum.toDouble() / n
    }
}
