package com.playtranslate.camera

import java.util.concurrent.atomic.AtomicInteger

/**
 * Monotonic ownership counter for the camera tool's display pipeline.
 *
 * The session has several producers of display state (acquire tails, relock
 * tails, mode re-flavors, scene wipes, resets) whose multi-step publications
 * used to be serialized by convention — each producer enrolling in an ad-hoc
 * set of guards, and each producer added since Phase 2 shipping with a missed
 * enrollment. This replaces that with one rule:
 *
 *  - a publication SOURCE calls [advance] atomically with updating the
 *    cached state it owns (under the same lock);
 *  - the display work it authorizes carries the returned epoch and re-checks
 *    [isCurrent] immediately before EVERY commit (region install, raster
 *    swap, payload snapshot) on that state's owning thread.
 *
 * Any newer source therefore invalidates every older tail at its next commit
 * point, structurally — no per-producer guard enrollment. The per-frame
 * homography stream is deliberately OUTSIDE the protocol: it is slaved to
 * the tracker's currently installed anchor, so its worst failure is a
 * single-frame pairing mismatch during a scene swap, not a persistent stale
 * publication.
 */
class DisplayEpoch {
    private val value = AtomicInteger(0)

    /** The epoch a not-yet-publishing observer runs under (launch-time
     *  capture for abort checks). */
    fun current(): Int = value.get()

    /** A new source takes ownership; returns the epoch its display work
     *  must carry. Call atomically with publishing the source's caches. */
    fun advance(): Int = value.incrementAndGet()

    /** True while [epoch] still owns the display — checked immediately
     *  before every commit. */
    fun isCurrent(epoch: Int): Boolean = value.get() == epoch
}
