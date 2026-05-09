package com.playtranslate.translation

typealias BackendId = String

/** Coarse quality label for a translation backend, surfaced in the
 *  Settings row's line-1 subtitle. The renderer maps:
 *  - [Bad]    → "Bad quality"   (danger color)
 *  - [Okay]   → "Okay quality"  (default tint)
 *  - [Good]   → "Good quality"  (default tint)
 *  - [Better] → "Better quality" (accent color)
 *
 *  Subjective by nature; intended as a rough hint to help the user
 *  decide which backend to enable. Not load-bearing in the waterfall. */
enum class BackendQuality { Bad, Okay, Good, Better }

/** Coarse speed label for offline backends, surfaced in the Settings
 *  row's line-1 subtitle alongside [BackendQuality]. Online backends
 *  leave [TranslationBackend.speed] null — their perceived speed is
 *  network-bound, not a useful comparison axis. */
enum class BackendSpeed { VerySlow, Slow, Fast }

/**
 * A pluggable translation source. Implementations are pair-agnostic
 * singletons — pair-specific state (e.g. ML Kit's per-pair Translator
 * client) is owned internally by the implementation.
 *
 * The waterfall in [TranslationBackendRegistry.translate] iterates the
 * registry in priority order and picks the first backend whose
 * [isUsable] returns true and whose [translate] does not throw. A
 * thrown exception falls through to the next backend in the chain.
 *
 * Capability extensions (e.g. [QuotaAware]) are declared on side
 * interfaces so the base contract stays small. Future capabilities
 * (downloadable resources, quota readout, etc.) follow the same
 * pattern — see Capabilities.kt.
 */
interface TranslationBackend {
    /** Stable identifier used for cache keys, logs, and ordering overrides. */
    val id: BackendId

    /** Human-readable label shown in Settings. Distinct from [id]: `id`
     *  is a stable internal token (used by the cache, logs, ordering
     *  overrides), while `displayName` is the user-facing brand. */
    val displayName: String

    /** Lower = earlier in the waterfall. Resolved against [BackendId]
     *  ascending as the tiebreaker for determinism. */
    val priority: Int

    /** Whether the backend needs network connectivity to function. */
    val requiresInternet: Boolean

    /** Coarse quality hint shown in Settings. Default [BackendQuality.Good]. */
    val quality: BackendQuality get() = BackendQuality.Good

    /** Coarse speed hint shown in Settings for offline backends only.
     *  Default null = no speed indicator (online backends, where speed
     *  is dominated by network conditions rather than the backend itself). */
    val speed: BackendSpeed? get() = null

    /** True for backends whose translations are signalled to the user as
     *  lower-quality / "degraded" (currently only the on-device ML Kit
     *  fallback). When this backend wins the waterfall, the calling code
     *  surfaces a degraded-state badge and skips caching the result so
     *  online backends can reclaim the slot when they recover. */
    val isDegradedFallback: Boolean

    /** Synchronous gate that excludes a backend from the waterfall when
     *  configuration or pair compatibility precludes it. Network
     *  reachability is intentionally NOT checked here — try-and-catch is
     *  the source of truth for online failure modes. */
    fun isUsable(source: String, target: String): Boolean

    /** Translate [text] from [source] to [target]. Throws on any failure;
     *  the waterfall falls through to the next backend on exception. */
    suspend fun translate(text: String, source: String, target: String): String

    /** Release any held resources. Called from the registry's [close]
     *  during app teardown. Default no-op for stateless backends. */
    fun close() {}

    /** Synchronous snapshot of the backend's secondary-subtitle state in
     *  Settings. May return [BackendStatus.Loading] when a refresh is in
     *  flight; the UI then awaits [refreshStatus]. Read-only and idempotent
     *  — must not block. Default [BackendStatus.Hidden] (no status line). */
    val status: BackendStatus get() = BackendStatus.Hidden

    /** Refresh dynamic state (e.g. quota fetch) and return the fresh
     *  snapshot. Implementations cache the result and expose it via
     *  [status]. Default no-op returns the current [status]. Safe to call
     *  repeatedly; implementations should single-flight if appropriate. */
    suspend fun refreshStatus(): BackendStatus = status
}
