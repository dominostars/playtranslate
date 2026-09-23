package com.playtranslate.capture

/**
 * Whether the MediaProjection mirror has delivered a frame since its
 * VirtualDisplay was created — the condition a clean capture's freshness
 * anchor depends on when the display is built BEFORE the blank (the
 * guarded order on AYN firmware, see [DisplayServiceGuard]).
 *
 * A fresh display composes its first frame from whatever layer state
 * SurfaceFlinger holds a frame or two later. If that composition predates
 * our blank but its delivery reaches the frame thread after the caller
 * read its anchor, the frame sits above the anchor, the freshness wait
 * accepts it, and it carries our overlays. The lazy-create order made that
 * impossible by construction; in the guarded order the session must not
 * be anchored on until the first delivery has landed. The state is per
 * OUTPUT SURFACE — a fresh display, or a resize's replacement reader, whose
 * first composition is just as dirty and just as late — whichever path
 * built it (a raw capture can be the first); it persists across attempts — a timed-out warm-up leaves it not ready, so
 * the retry waits again instead of letting a never-warmed display through
 * — and it dies with the session. Pure; main-thread only like the session
 * fields around it.
 */
class SessionReadiness {

    private var creationSeq = NONE

    /** True once a delivery above the creation seq has been observed. */
    @Volatile var isReady: Boolean = false
        private set

    /** A VirtualDisplay was just created; [seqBeforeCreation] is the
     *  delivery seq read immediately before the build, so the first
     *  delivery above it is the new display's first frame. */
    fun onCreated(seqBeforeCreation: Long) {
        creationSeq = seqBeforeCreation
        isReady = false
    }

    /** Feed a delivery seq; returns whether the session is ready after it.
     *  Nothing created ⇒ never ready. Ready stays ready. */
    fun observe(seq: Long): Boolean {
        if (creationSeq == NONE) return false
        if (seq > creationSeq) isReady = true
        return isReady
    }

    /** The session died: nothing is created, nothing is ready. */
    fun reset() {
        creationSeq = NONE
        isReady = false
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}
